package com.agtuner.audio

import android.os.SystemClock
import android.util.Log
import com.agtuner.tuning.NoteFrequencies
import com.agtuner.tuning.NoteLabel
import com.agtuner.tuning.StringNote
import com.agtuner.tuning.TuningEngine
import com.agtuner.tuning.TuningState
import com.agtuner.tuning.toLabel
import com.agtuner.viewmodel.DebugStats
import kotlin.math.abs
import kotlin.math.round

/**
 * Per-frame inputs to [PitchPipeline.process]. The ViewModel snapshots the
 * relevant UI state and admin-tuning prefs and passes them in so the pipeline
 * does not need to read mutable shared state mid-frame.
 */
data class PipelineParams(
    val selectedStringIndex: Int,
    val stringConfiguration: List<StringNote>,
    val autoStringDetection: Boolean,
    val smoothingWindowSize: Int,
    val minConfidenceThreshold: Float,
    val stateStabilityCount: Int,
    val isCalibrating: Boolean,
    val debugMode: Boolean,
    val previousDebugStats: DebugStats,
    val audioFeedbackCount: Int,
)

/**
 * Display update produced by the pipeline.
 *
 * - [NoChange]: do not modify visible readings (suppressed/rejected/below holdoff)
 * - [ClearAll]: wipe everything (NO_SIGNAL_HOLDOFF reached)
 * - [Show]:    apply these readings to the UI state
 */
sealed class PipelineDisplay {
    object NoChange : PipelineDisplay()
    object ClearAll : PipelineDisplay()
    data class Show(
        val detectedFrequency: Float,
        val detectedNote: NoteLabel,
        val tuningState: TuningState,
        val cents: Float,
        val confidence: Float,
    ) : PipelineDisplay()
}

/**
 * Feedback dispatch request produced by the pipeline.
 * The ViewModel forwards this to FeedbackManager.provideFeedback.
 */
data class FeedbackTrigger(
    val state: TuningState,
    val newAttack: Boolean,
)

/**
 * Result of processing a single PitchResult.
 *
 * The ViewModel applies all of these atomically to its `_uiState` in a single
 * `update { it.copy(...) }`, then optionally dispatches feedback in a coroutine.
 */
data class PipelineOutput(
    /** True the frame on which calibration phase finishes (VM should clear isCalibrating). */
    val calibrationCompleted: Boolean = false,
    /** Set when auto-string detection (or candidate machine) chose a different string. */
    val newSelectedStringIndex: Int? = null,
    /** What to render. */
    val display: PipelineDisplay = PipelineDisplay.NoChange,
    /** New DebugStats for this frame, or null to keep existing. */
    val debugStats: DebugStats? = null,
    /** Feedback to dispatch this frame, or null. */
    val feedback: FeedbackTrigger? = null,
)

/**
 * Pitch detection / smoothing / locking pipeline.
 *
 * Owns ALL state that used to live in TunerViewModel for audio processing:
 * - Calibration phase (noise floor warmup)
 * - Frequency smoothing ring buffer
 * - Attack detection (silence→sound, spike, implied attack, attackless acquisition)
 * - Arm/disarm + decay/hard-lock state
 * - State stability tracking + no-signal holdoff
 * - Candidate-string state machine for auto-detection
 * - Feedback suppression deadline
 *
 * Held by the ViewModel as a private field; not @Singleton because the state
 * is per-ViewModel-instance.
 */
class PitchPipeline(
    private val tuningEngine: TuningEngine,
) {
    // Pitch smoothing - ring buffer (zero allocations in hot path)
    private val frequencyRingBuffer = FloatArray(MAX_SMOOTHING_WINDOW)
    private var frequencyIndex = 0
    private var frequencyCount = 0

    // State stability tracking - only tracks category (TooLow/InTune/TooHigh/NoSignal)
    private var lastDisplayedCategory: Int = -1  // -1 = NoSignal
    private var pendingCategory: Int = -1
    private var pendingCategoryCount = 0

    // No-signal holdoff: keep showing last reading during note decay
    // Much higher threshold than state stability - holds reading for ~2-3 seconds
    private var noSignalCount = 0

    // Attack detection for voice feedback and frequency locking
    // Ring buffer for amplitude history (zero allocations in hot path)
    private val amplitudeRingBuffer = FloatArray(AMPLITUDE_HISTORY_SIZE)
    private var amplitudeIndex = 0
    private var amplitudeCount = 0
    private var lastAttackTime = 0L
    private var consecutiveSilentFrames = 0  // Count frames below silence threshold
    private var totalAttackCount = 0  // Debug: count total attacks detected
    private var pendingAttack = false  // Attack detected but not yet processed with valid pitch
    private var pendingVoiceFeedback = false  // Voice feedback pending until state stabilizes
    private var pendingAutoDetection = false  // Auto string detection pending after attack
    private var autoDetectionStartTime = 0L   // When pendingAutoDetection was set (for time-based window)

    // Candidate-string state machine (auto-mode acquisition decoupled from attack detection).
    // Fires a string switch on pitch-class shift alone, regardless of amplitude.
    private var candidateStringIndex: Int = -1
    private var candidateStringFrames: Int = 0

    // Locked frequency: lock reading when signal decays, unlock on new attack
    // This prevents wild frequency jumps during note decay
    private var lockedFrequency: Float? = null
    private var lockedNote: NoteLabel? = null
    private var lockedTuningState: TuningState? = null
    private var peakAmplitude: Float = 0f  // Track peak amplitude of current note

    // Track when we last had a valid pitched frame (for spike attack gating)
    private var lastGoodPitchTime = 0L

    // Attack arm/disarm state: only allow one attack per "note event"
    // Prevents multiple voice triggers from envelope wobble during sustain
    private var attackArmed = true
    private var notePeakAmplitude = 0f  // Peak amplitude for current note (for re-arm logic)
    private var belowRearmCount = 0     // Frames below re-arm threshold

    // Noise floor estimation (EMA) - adapts thresholds to ambient environment
    private var noiseFloorEma: Float = 0.01f  // Initialize to typical ambient
    private var noiseFloorInitialized = false

    // Calibration: collect ambient noise before enabling detection
    private var calibrationFrameCount = 0

    // Hard note lock: once stable, freeze the reading until next attack
    // Prevents any drift or octave errors during decay from changing the display
    private var hardLockActive = false

    // Feedback suppression: ignore mic input during/after audio playback
    // This prevents the agtuner from "hearing itself" and triggering false attacks
    private var suppressUntilMs = 0L

    // Noise floor update gating: only update when in confirmed silence
    private var nonPitchedFrameCount = 0
    private var consecutivePitchedFrames = 0 // For attackless acquisition

    // Per-call snapshot of params consumed by helpers (debugMode logs everywhere,
    // autoStringDetection drives resetForNewNoteAcquisition). Set at the start of
    // every process() call to avoid threading them through every helper signature.
    private var debugMode: Boolean = false
    private var autoStringDetectionEnabled: Boolean = false

    companion object {
        // Timing constants are calibrated for ~43 fps (50% overlap at 44100 Hz / 2048 window)
        // Each frame is ~23ms. Adjust these if OVERLAP_FACTOR changes.

        // Number of consecutive no-pitch results before showing "No Signal"
        // At ~23ms per frame with 50% overlap, 100 readings = ~2.3 seconds of silence
        const val NO_SIGNAL_HOLDOFF = 100

        // Attack detection parameters
        const val AMPLITUDE_HISTORY_SIZE = 16  // ~370ms of history for smoother baseline
        const val MAX_SMOOTHING_WINDOW = 10    // Max frequency smoothing window size

        // Noise floor estimation - adapts all thresholds to ambient environment
        // ASYMMETRIC: rises slowly to avoid poisoning from decaying notes,
        //             falls quickly to adapt to quieter environments
        const val NOISE_EMA_ALPHA_UP = 0.01f    // Slow rise (~2.3 second time constant)
        const val NOISE_EMA_ALPHA_DOWN = 0.05f  // Faster fall (~0.5 second time constant)
        const val NOISE_UPDATE_CEILING = 0.018f // Don't update noise floor above this (lowered for safety)

        // Noise-relative threshold multipliers (actual thresholds = noiseFloor * multiplier)
        const val SILENCE_LOW_MULT = 0.7f       // Enter silence below noiseFloor * 0.7
        const val SILENCE_HIGH_MULT = 1.3f      // Exit silence above noiseFloor * 1.3 (real hysteresis)
        const val MIN_ATTACK_MULT = 1.1f        // Base multiplier for min attack amplitude (lowered for high strings)
        const val MIN_ATTACK_OFFSET = 0.003f    // Minimum offset above noise for attack detection (lowered for high strings)
        const val MIN_SPIKE_DELTA_MULT = 0.25f  // Spike delta multiplier (lowered for high strings)
        const val MIN_SPIKE_DELTA_FLOOR = 0.002f // Absolute minimum spike delta (lowered for high strings)
        const val REARM_NOISE_MULT = 1.2f       // Noise-based re-arm threshold = noiseFloor * 1.2
        // FIX: Cap re-arm threshold so loud→quiet transitions (E2→D3) work reliably
        // Without this cap, after a loud E2 (peak ~0.15), the re-arm threshold becomes
        // 0.15 * 0.25 = 0.0375, which is HIGHER than D3's entire pluck amplitude (~0.03)
        const val MAX_REARM_THRESHOLD = 0.022f  // Cap ensures any real pluck can trigger re-arm

        const val SILENCE_FRAMES_REQUIRED = 6   // ~140ms of silence to consider "in silence"

        // NOTE: Time gate only applies to spike attacks, NOT silence→sound transitions
        const val MIN_ATTACK_INTERVAL_MS = 300L // Minimum time between spike attacks (lowered from 400)
        const val MAX_DISARM_TIME_MS = 1200L    // Force re-arm after 1.2 seconds (lowered from 1.5)
        const val SPIKE_RATIO = 1.8f            // Amplitude must be 1.8x baseline to count as re-strike
        const val MAX_PITCH_AGE_FOR_SPIKE_MS = 300L  // Only allow spike attacks if we had pitch recently

        // Frequency locking: only lock when amplitude drops significantly from peak
        // This allows updates while signal is strong, locks during decay
        const val DECAY_LOCK_RATIO = 0.3f  // Lock when amplitude drops to 30% of peak

        // Pitch continuity: reject jumps larger than this without a new attack
        // 150 cents = 1.5 semitones, enough for vibrato but catches octave errors
        const val MAX_PITCH_JUMP_CENTS = 150f

        // Attack re-arm: only allow new attack after note has decayed enough
        // Prevents multiple attacks from one pluck due to envelope wobble
        const val REARM_RATIO = 0.25f          // Re-arm when amplitude < 25% of note peak
        const val REARM_FRAMES_REQUIRED = 6    // ~140ms below threshold to re-arm

        // Implied attack: treat large pitch jump with strong signal as new attack
        // Catches new plucks that RMS attack detection misses
        const val IMPLIED_ATTACK_CONFIDENCE = 0.90f  // High confidence required

        // Feedback suppression: ignore mic input during/after audio/voice playback
        // This prevents the agtuner from detecting its own feedback tones as attacks
        const val FEEDBACK_SUPPRESS_MS = 400L

        // Noise floor update: only update after confirmed silence (no pitch for N frames)
        // This prevents ringing note decay from poisoning the noise estimate
        const val SILENCE_FRAMES_FOR_NOISE_UPDATE = 20  // ~460ms of non-pitched frames (raised for safety)

        // Attackless acquisition: if we were silent, this many stable pitch frames triggers a new note
        const val ATTACKLESS_ACQUISITION_FRAMES = 3

        // Calibration: collect ambient noise samples before enabling detection
        // At ~43 fps (50% overlap), 43 frames = ~1000ms of ambient measurement
        const val CALIBRATION_FRAMES = 43

        // Auto-detection: time window and minimum frames for string detection
        // E4/B3 often fail confidence gating, so we use a longer window and lower frame threshold
        const val AUTO_DETECTION_WINDOW_MS = 1000L  // Keep auto-detection pending for up to 1 second
        const val AUTO_DETECTION_MIN_FRAMES = 2     // Require 2 frames to reduce single-frame fragility

        // Auto-mode candidate string switching (decoupled from attack detection).
        // The candidate machine fires on pitch-class shift alone — required because amplitude-based
        // attack detection misses loud→quiet transitions (E2→E4, A2→B3).
        const val AUTO_SWITCH_CONFIRM_FRAMES = 3        // Frames of agreement before switch fires (~70ms at 43fps)
        const val AUTO_SWITCH_MAX_CENTS = 200f          // Reject if no string within ~2 semitones (kills octave errors)
        const val AUTO_SWITCH_MIN_PROBABILITY = 0.80f   // Lower gate than minConfidenceThreshold (helps E4/B3)
        const val AUTO_SWITCH_MIN_SHIFT_CENTS = 80f     // Suppress vibrato; require ≥ ~1 semitone from current target
        const val AUTO_SWITCH_OCTAVE_GUARD_CENTS = 50f  // Reject candidates within this of integer-octave (YIN sub-harmonic protection)
    }

    /**
     * Reset all per-note tracking state (smoothing buffers, attack detection,
     * frequency locking, candidate machine). Call when switching strings or
     * stopping the agtuner.
     *
     * Preserves noise floor — that should persist across string switches.
     * Use [resetForNewSession] when starting a fresh listening session.
     */
    fun reset() {
        // Ring buffer reset (just reset indices, no allocation)
        frequencyIndex = 0
        frequencyCount = 0
        amplitudeIndex = 0
        amplitudeCount = 0
        // Note: Don't reset noiseFloorEma - it should persist across string switches
        nonPitchedFrameCount = 0
        consecutivePitchedFrames = 0
        lastDisplayedCategory = -1
        pendingCategory = -1
        pendingCategoryCount = 0
        noSignalCount = 0
        // Assume we were in silence before starting - enables first note detection
        consecutiveSilentFrames = SILENCE_FRAMES_REQUIRED
        lastAttackTime = 0L
        lastGoodPitchTime = 0L
        pendingAttack = false
        pendingVoiceFeedback = false
        pendingAutoDetection = false
        autoDetectionStartTime = 0L
        candidateStringIndex = -1
        candidateStringFrames = 0
        lockedFrequency = null
        lockedNote = null
        lockedTuningState = null
        peakAmplitude = 0f
        // Attack arm/disarm state
        attackArmed = true
        notePeakAmplitude = 0f
        belowRearmCount = 0
        // Hard lock and suppression
        hardLockActive = false
        suppressUntilMs = 0L
    }

    /**
     * Reset state for a fresh listening session: includes [reset], plus reseeds
     * the noise floor and re-enters calibration phase. The ViewModel pairs this
     * with setting `isCalibrating = true` on the UI state.
     */
    fun resetForNewSession() {
        reset()
        // Reset noise floor for fresh calibration each session
        // This ensures thresholds adapt to current room conditions
        noiseFloorEma = 0.01f
        noiseFloorInitialized = false
        calibrationFrameCount = 0
    }

    /**
     * Process one pitch detection result and produce a structured update for the
     * ViewModel to apply atomically to its UI state.
     */
    fun process(result: PitchResult, params: PipelineParams): PipelineOutput {
        debugMode = params.debugMode
        autoStringDetectionEnabled = params.autoStringDetection

        val now = SystemClock.elapsedRealtime()

        // CALIBRATION PHASE: Collect ambient noise samples before enabling detection
        // This ensures attack detection thresholds are properly calibrated to room conditions
        if (params.isCalibrating) {
            calibrationFrameCount++

            // Collect noise floor samples during calibration (no gating required)
            // Use direct measurement for first sample, then EMA for smoothing
            if (result.rmsAmplitude < NOISE_UPDATE_CEILING) {
                noiseFloorEma = if (noiseFloorInitialized) {
                    // Use faster alpha during calibration for quicker convergence
                    noiseFloorEma + 0.15f * (result.rmsAmplitude - noiseFloorEma)
                } else {
                    noiseFloorInitialized = true
                    result.rmsAmplitude
                }
            }

            if (debugMode) {
                Log.d("Tuner", "Calibration: frame $calibrationFrameCount/$CALIBRATION_FRAMES noise=${"%.4f".format(noiseFloorEma)}")
            }

            // Check if calibration is complete
            val completed = calibrationFrameCount >= CALIBRATION_FRAMES
            if (completed && debugMode) {
                Log.d("Tuner", "Calibration complete: noiseFloor=${"%.4f".format(noiseFloorEma)}")
            }

            // Skip all normal processing during calibration
            return PipelineOutput(calibrationCompleted = completed)
        }

        // Snapshot silence state *before* detectAttack updates it for the next frame's logic
        val wasInSilence = consecutiveSilentFrames >= SILENCE_FRAMES_REQUIRED

        // ALWAYS run attack detection first - even during suppression
        // This keeps amplitude history fresh and allows strong attacks to break through
        val attackDetectedThisFrame = detectAttack(result.rmsAmplitude)

        // UNLOCK ON SILENCE: If we enter a state of confirmed silence, release any hard lock
        // so the next note can be acquired cleanly. Re-arming is handled by detectAttack.
        val isInSilence = consecutiveSilentFrames >= SILENCE_FRAMES_REQUIRED
        if (isInSilence && hardLockActive) {
            if (debugMode) {
                Log.d("Tuner", "HardLock: true → false (confirmed silence)")
            }
            hardLockActive = false
            // Don't clear lockedFrequency here; allow NO_SIGNAL_HOLDOFF to manage display decay.
        }

        // If attack detected, IMMEDIATELY clear all lock state
        // This runs BEFORE suppression and confidence gating so we don't stay locked
        // on the previous note when E4 (or other high strings) have low confidence
        if (attackDetectedThisFrame) {
            // Clear ALL lock state - critical for E4 which may fail confidence gating
            resetForNewNoteAcquisition(result.rmsAmplitude, "attack")

            // Mark acquisition pending (will be consumed by first valid pitch frame)
            pendingAttack = true

            // Break suppression - user is playing a new note
            if (debugMode && suppressUntilMs > now) {
                Log.d("Tuner", "Suppression: broken by attack (was ${suppressUntilMs - now}ms remaining)")
            }
            suppressUntilMs = 0L
        }

        // FEEDBACK SUPPRESSION: Ignore mic input during/after audio playback
        // This prevents the agtuner from "hearing itself" when playing tones or TTS
        // Note: An attack detection above will have cleared suppressUntilMs
        if (now < suppressUntilMs) {
            if (debugMode) {
                Log.d("Tuner", "Suppression: skipping frame (${suppressUntilMs - now}ms remaining)")
            }
            // Keep showing the locked display, don't process this frame
            return PipelineOutput()
        }

        // AUTO-MODE CANDIDATE: Evaluate before confidence gate so high strings (E4/B3)
        // at probability 0.80–0.89 can still drive a string switch. The candidate machine
        // uses its own (lower) probability threshold and fires on pitch shift alone.
        val autoSwitchIdx = if (result.isPitched) {
            evaluateAutoStringCandidate(
                result.frequency, result.probability,
                params.selectedStringIndex,
                params.stringConfiguration
            )
        } else -1
        val autoSwitchFiring = autoSwitchIdx >= 0

        // Track the string index we're operating on this frame. Starts as the VM's
        // current selection; auto-switch / legacy-auto-detection can update it before
        // we compute the display. Output's newSelectedStringIndex captures any change.
        var currentSelectedIndex = params.selectedStringIndex

        // Filter out low-confidence readings (likely noise or harmonics).
        // Exception: if auto-switch is firing this frame, force-process the frame so
        // the new string acquires immediately rather than waiting for confidence to recover.
        if ((!result.isPitched || result.probability < params.minConfidenceThreshold) && !autoSwitchFiring) {
            consecutivePitchedFrames = 0 // Reset on non-pitched frame
            // Increment no-signal counter but DON'T clear the display yet
            // This allows the last valid reading to persist during note decay
            noSignalCount++
            nonPitchedFrameCount++

            // NOISE FLOOR UPDATE: Only during confirmed silence (no pitch for N frames)
            // This prevents ringing note decay from poisoning the noise estimate
            if (nonPitchedFrameCount >= SILENCE_FRAMES_FOR_NOISE_UPDATE &&
                result.rmsAmplitude < NOISE_UPDATE_CEILING) {
                val oldNoiseFloor = noiseFloorEma
                noiseFloorEma = if (noiseFloorInitialized) {
                    // ASYMMETRIC EMA: rises slowly (protects against poisoning), falls quickly (adapts to quiet)
                    val alpha = if (result.rmsAmplitude > noiseFloorEma) NOISE_EMA_ALPHA_UP else NOISE_EMA_ALPHA_DOWN
                    noiseFloorEma + alpha * (result.rmsAmplitude - noiseFloorEma)
                } else {
                    noiseFloorInitialized = true
                    result.rmsAmplitude
                }
                if (debugMode) {
                    val direction = if (result.rmsAmplitude > oldNoiseFloor) "↑" else "↓"
                    Log.d("Tuner", "NoiseFloor: ${"%.4f".format(oldNoiseFloor)} $direction ${"%.4f".format(noiseFloorEma)} (nonPitched=$nonPitchedFrameCount rms=${"%.4f".format(result.rmsAmplitude)})")
                }
            }

            // Update debug stats even for rejected frames
            val rejectedDebugStats = buildRejectedDebugStats(result.rmsAmplitude, params)

            // Only show "No Signal" after extended silence (NO_SIGNAL_HOLDOFF readings)
            // This is much longer than state stability to handle guitar note decay
            if (noSignalCount >= NO_SIGNAL_HOLDOFF) {
                // Clear everything only after prolonged silence (reset ring buffer indices)
                frequencyIndex = 0
                frequencyCount = 0
                amplitudeIndex = 0
                amplitudeCount = 0
                lastDisplayedCategory = -1
                pendingCategory = -1
                pendingCategoryCount = 0
                lockedFrequency = null
                lockedNote = null
                lockedTuningState = null
                peakAmplitude = 0f
                return PipelineOutput(
                    display = PipelineDisplay.ClearAll,
                    debugStats = rejectedDebugStats,
                )
            }
            // Keep showing the last valid state - don't update UI
            return PipelineOutput(debugStats = rejectedDebugStats)
        }

        // Got a valid pitch - reset the no-signal counter and non-pitched frame count
        noSignalCount = 0
        nonPitchedFrameCount = 0
        consecutivePitchedFrames++ // We have a good pitch

        // Check if we have a pending attack to process
        // This captures attacks that were detected on frames with invalid pitch
        // Note: resetForNewNoteAcquisition() was already called in attackDetectedThisFrame block
        val hadPendingAttack = pendingAttack
        var isNewAttack = pendingAttack
        if (pendingAttack) {
            pendingAttack = false  // Clear the pending flag - consumed exactly once
        }

        // AUTO-MODE CANDIDATE FIRING:
        // The candidate machine confirmed a different configured string. Switch immediately
        // and treat this frame as new acquisition — bypasses the 150-cent jump check below
        // and clears stale lock state from the previous note.
        // Note: do NOT call triggerAttack — there's no amplitude rise, so feedback/voice
        // gating remains amplitude-based as before.
        if (autoSwitchFiring) {
            if (debugMode) {
                Log.d(
                    "Tuner",
                    "AutoSwitch[candidate]: → idx=$autoSwitchIdx freq=${"%.1f".format(result.frequency)}Hz prob=${"%.2f".format(result.probability)} frames=$candidateStringFrames"
                )
            }
            resetForNewNoteAcquisition(result.rmsAmplitude, "auto-switch")
            currentSelectedIndex = autoSwitchIdx
            // resetForNewNoteAcquisition already cleared candidate state, but explicit for clarity
            candidateStringIndex = -1
            candidateStringFrames = 0
            // Suppress legacy pendingAutoDetection block — we already chose the string
            pendingAutoDetection = false
            // Force-accept this frame's frequency in the jump check below
            isNewAttack = true
        }

        // ATTACKLESS ACQUISITION FALLBACK:
        // If we were in confirmed silence and now get a stable pitched signal,
        // treat it as a new note, even if the main attack detector missed it.
        // This makes muting between notes much more reliable.
        if (!isNewAttack && wasInSilence && consecutivePitchedFrames >= ATTACKLESS_ACQUISITION_FRAMES) {
            // This path did NOT go through attackDetectedThisFrame, so we need to reset here
            resetForNewNoteAcquisition(result.rmsAmplitude, "attackless")
            isNewAttack = true
            // Call triggerAttack to correctly update the attack state machine (sets lastAttackTime, disarms)
            triggerAttack(now, result.rmsAmplitude)
            consecutivePitchedFrames = 0 // Consume the event
        }

        // For attacks that came through attackDetectedThisFrame (hadPendingAttack),
        // the reset was already done pre-confidence. Just update peakAmplitude if needed
        // since we now have the valid pitch frame's amplitude.
        if (isNewAttack && hadPendingAttack) {
            // peakAmplitude was set in attackDetectedThisFrame, but update if this frame is higher
            if (result.rmsAmplitude > peakAmplitude) {
                peakAmplitude = result.rmsAmplitude
            }
        }

        // Track peak amplitude for this note
        if (result.rmsAmplitude > peakAmplitude) {
            peakAmplitude = result.rmsAmplitude
        }

        // PITCH CONTINUITY / OUTLIER REJECTION:
        // Without a new attack, reject frequency jumps larger than ~1.5 semitones.
        // This prevents YIN octave errors during decay from contaminating the buffer.
        // Also reject frequencies far from the target string in manual mode.
        val incomingFreq = result.frequency
        val shouldAcceptFrequency: Boolean

        // Track if this frame triggers an implied attack (large jump + strong signal)
        var impliedAttack = false

        if (isNewAttack || frequencyCount == 0) {
            // Always accept on new attack or first frame
            shouldAcceptFrequency = true
        } else {
            // Check continuity with reference frequency
            // Prefer lockedFrequency (stable locked reading) over last frequency in ring buffer
            // This anchors continuity to the "known good" pitch during decay
            val lastFreqIdx = (frequencyIndex - 1 + MAX_SMOOTHING_WINDOW) % MAX_SMOOTHING_WINDOW
            val referenceFreq = lockedFrequency ?: frequencyRingBuffer[lastFreqIdx]
            val jumpCents = abs(tuningEngine.calculateCents(incomingFreq, referenceFreq))

            if (jumpCents > MAX_PITCH_JUMP_CENTS) {
                // Large jump without RMS attack detected
                // Check if this looks like a new pluck that the RMS detector missed:
                // - Amplitude is RISING (a pluck has a clear amplitude increase)
                // - Strong signal (above attack threshold)
                // - High confidence (clean pitch detection)
                // - Respects time gate (not too close to last attack)
                val nowJump = SystemClock.elapsedRealtime()
                // Get previous amplitude from ring buffer
                val lastAmp = if (amplitudeCount >= 2) {
                    val prevIdx = (amplitudeIndex - 2 + AMPLITUDE_HISTORY_SIZE) % AMPLITUDE_HISTORY_SIZE
                    amplitudeRingBuffer[prevIdx]
                } else 0f
                val ampRise = result.rmsAmplitude - lastAmp
                val minSpikeDelta = minSpikeDelta()
                val looksLikePluck = ampRise >= minSpikeDelta

                val minAttackAmplitude = minAttackThreshold()
                val strongSignal = looksLikePluck &&
                    result.rmsAmplitude >= minAttackAmplitude &&
                    result.probability >= IMPLIED_ATTACK_CONFIDENCE &&
                    (nowJump - lastAttackTime) >= MIN_ATTACK_INTERVAL_MS

                if (strongSignal) {
                    // Treat as implied attack - new string plucked while old was ringing
                    impliedAttack = true
                    shouldAcceptFrequency = true
                    if (debugMode) {
                        Log.d("Tuner", "ImpliedAttack: jumpCents=${"%.1f".format(jumpCents)} ampRise=${"%.4f".format(ampRise)} prob=${"%.2f".format(result.probability)} gate=${nowJump - lastAttackTime}ms")
                    }
                } else {
                    // Likely YIN error during decay - reject
                    shouldAcceptFrequency = false
                    if (debugMode) {
                        val gate = nowJump - lastAttackTime
                        Log.d("Tuner", "Reject[jump]: jumpCents=${"%.1f".format(jumpCents)} freq=${"%.1f".format(incomingFreq)}Hz ref=${"%.1f".format(referenceFreq)}Hz ampRise=${"%.4f".format(ampRise)} minDelta=${"%.4f".format(minSpikeDelta)} amp=${"%.4f".format(result.rmsAmplitude)} minAmp=${"%.4f".format(minAttackAmplitude)} prob=${"%.2f".format(result.probability)} gate=${gate}ms")
                    }
                }
            } else {
                // In manual mode (not auto), also reject if too far from target string
                val targetForReject = params.stringConfiguration.getOrNull(currentSelectedIndex)
                if (!params.autoStringDetection && targetForReject != null) {
                    val distanceFromTarget = abs(
                        tuningEngine.calculateCents(incomingFreq, targetForReject.frequency)
                    )
                    // Allow up to ~3 semitones from target (300 cents) for tuning range
                    shouldAcceptFrequency = distanceFromTarget <= 300f
                    if (!shouldAcceptFrequency && debugMode) {
                        Log.d("Tuner", "Reject[distance]: freq=${"%.1f".format(incomingFreq)}Hz target=${"%.1f".format(targetForReject.frequency)}Hz (${targetForReject.displayName}) distance=${"%.1f".format(distanceFromTarget)}cents")
                    }
                } else {
                    shouldAcceptFrequency = true
                }
            }
        }

        // Handle implied attack: treat as a real note event via triggerAttack
        // This ensures lastAttackTime is set so it cannot spam every frame
        if (impliedAttack) {
            // Use triggerAttack to properly set lastAttackTime and disarm
            triggerAttack(SystemClock.elapsedRealtime(), result.rmsAmplitude)

            // Reset tracking like a normal new attack
            resetForNewNoteAcquisition(result.rmsAmplitude, "implied")
            pendingVoiceFeedback = true  // Voice feedback for new note
        }

        if (!shouldAcceptFrequency) {
            // Reject this frame - don't update frequency buffer
            // But update debug stats so we can see why frames are rejected
            return PipelineOutput(
                newSelectedStringIndex = currentSelectedIndex.takeIf { it != params.selectedStringIndex },
                debugStats = buildRejectedDebugStats(result.rmsAmplitude, params),
            )
        }

        // Valid pitch accepted - update lastGoodPitchTime for spike attack gating
        lastGoodPitchTime = SystemClock.elapsedRealtime()

        // Add to frequency ring buffer (respects smoothingWindowSize)
        val effectiveWindowSize = minOf(params.smoothingWindowSize, MAX_SMOOTHING_WINDOW)
        frequencyRingBuffer[frequencyIndex] = incomingFreq
        frequencyIndex = (frequencyIndex + 1) % effectiveWindowSize
        frequencyCount = minOf(frequencyCount + 1, effectiveWindowSize)

        // Calculate smoothed (averaged) frequency from ring buffer
        val smoothedFrequency = computeSmoothedFrequency(params.smoothingWindowSize)

        // AUTO STRING DETECTION:
        // When enabled, find the closest configured string to the detected frequency
        // and automatically select it. Only evaluate ONCE after a new attack, once we
        // have enough samples for reliable detection. Then lock that string until the
        // next attack to prevent jumping during note decay.
        // Uses TuningEngine.findClosestString which always returns the closest match.
        //
        // FIX for E4/B3: Use lower frame threshold (1 instead of 3) because high strings
        // often fail confidence gating. Also use time-based window to keep trying longer.
        val autoDetectionElapsed = now - autoDetectionStartTime
        val withinAutoWindow = autoDetectionElapsed < AUTO_DETECTION_WINDOW_MS

        if (pendingAutoDetection && frequencyCount >= AUTO_DETECTION_MIN_FRAMES) {
            pendingAutoDetection = false  // Only evaluate once per attack
            val closestStringIndex = tuningEngine.findClosestString(
                smoothedFrequency,
                params.stringConfiguration,
                AUTO_SWITCH_MAX_CENTS
            )
            // Switch if different from current selection AND a plausible match was found
            if (closestStringIndex >= 0 && closestStringIndex != currentSelectedIndex) {
                if (debugMode) {
                    val oldIndex = currentSelectedIndex
                    val oldNote = params.stringConfiguration.getOrNull(oldIndex)?.displayName ?: "?"
                    val newNote = params.stringConfiguration.getOrNull(closestStringIndex)?.displayName ?: "?"
                    val cents = tuningEngine.calculateCents(smoothedFrequency, params.stringConfiguration[closestStringIndex].frequency)
                    Log.d("Tuner", "AutoString: $oldNote(idx=$oldIndex) → $newNote(idx=$closestStringIndex) freq=${"%.1f".format(smoothedFrequency)}Hz cents=${"%.1f".format(cents)}")
                }
                // CRITICAL: Reset stability tracking when switching strings
                // This prevents the old string's category (InTune/TooLow/TooHigh) from affecting the new string
                lastDisplayedCategory = -1
                pendingCategory = -1
                pendingCategoryCount = 0
                currentSelectedIndex = closestStringIndex
            }
        } else if (pendingAutoDetection && !withinAutoWindow) {
            // Time window expired without getting enough valid frames - give up on this attack
            // This prevents pendingAutoDetection from staying true forever if E4/B3 never passes confidence
            if (debugMode) {
                Log.d("Tuner", "AutoString: window expired after ${autoDetectionElapsed}ms, frequencyCount=$frequencyCount (needed $AUTO_DETECTION_MIN_FRAMES)")
            }
            pendingAutoDetection = false
        }

        // Resolve target note for the selected string (after possible auto-switch).
        val targetNote = params.stringConfiguration.getOrNull(currentSelectedIndex)
            ?: return PipelineOutput(
                newSelectedStringIndex = currentSelectedIndex.takeIf { it != params.selectedStringIndex },
            )

        // FREQUENCY LOCKING LOGIC:
        // - While signal is strong (above decay threshold): keep updating frequency
        // - When signal decays significantly: lock the last good reading
        // - New attack: unlock and start fresh
        // - Once "InTune" is locked, keep it locked until next attack
        val isInDecay = peakAmplitude > 0 && result.rmsAmplitude < peakAmplitude * DECAY_LOCK_RATIO
        val frequencyToUse: Float
        val noteToUse: NoteLabel
        val tuningStateToUse: TuningState

        // Whether the active tuning prefers flat spellings — used so detected accidental
        // notes match the convention of the user's tuning (Eb on half-step-down).
        val preferFlats = params.stringConfiguration.any { it.name.endsWith("b") }

        // Check if we should use locked values:
        // 1. We have locked values AND
        // 2. Either hard lock is active OR signal is decaying
        // Note: We no longer use "lockedTuningState is InTune" as a lock condition.
        // Previously this caused the UI to stay locked indefinitely on the old note
        // when E4 (or other high strings with low YIN confidence) was played,
        // because attacks were detected but frames were rejected by confidence gating.
        val shouldUseLocked = lockedFrequency != null && !isNewAttack && !impliedAttack &&
            (hardLockActive || isInDecay)

        if (shouldUseLocked) {
            // Hard locked or signal is decaying - keep showing locked values
            frequencyToUse = lockedFrequency!!
            noteToUse = lockedNote!!
            tuningStateToUse = lockedTuningState!!
        } else {
            // Signal is strong OR new attack - calculate fresh values
            frequencyToUse = smoothedFrequency
            val tuningState = tuningEngine.evaluatePitch(smoothedFrequency, targetNote.frequency)

            // When in the "green zone" (in tune), always show the target note name
            val noteForDisplay = if (tuningState is TuningState.InTune) {
                targetNote
            } else {
                NoteFrequencies.findClosestNote(smoothedFrequency, preferFlats)
            }
            noteToUse = noteForDisplay.toLabel()
            tuningStateToUse = tuningState

            // Update locked values while signal is strong (before hard lock activates)
            // Lock after a small minimum (5 samples) not the full smoothing window
            val minSamplesForLock = minOf(5, params.smoothingWindowSize)
            if (frequencyCount >= minSamplesForLock) {
                lockedFrequency = frequencyToUse
                lockedNote = noteToUse
                lockedTuningState = tuningStateToUse
            }
        }

        val cents = tuningEngine.calculateCents(frequencyToUse, targetNote.frequency)

        // State stability: only change displayed category if new category persists
        val stateCategory = getStateCategory(tuningStateToUse)

        if (stateCategory == pendingCategory) {
            pendingCategoryCount++
        } else {
            pendingCategory = stateCategory
            pendingCategoryCount = 1
        }

        // Update UI with frequency (for gauge movement)
        // But only change the state category after stability threshold
        val previousCategory = lastDisplayedCategory
        val categoryStabilized = pendingCategoryCount >= params.stateStabilityCount || stateCategory == previousCategory

        // HARD LOCK ACTIVATION: Once we have enough samples AND stable category, freeze the lock
        // This prevents any drift or octave errors during decay from changing the display
        // The lock is only released on the next real attack
        val minSamplesForLock = minOf(5, params.smoothingWindowSize)
        if (!hardLockActive &&
            lockedFrequency != null &&
            frequencyCount >= minSamplesForLock &&
            categoryStabilized
        ) {
            hardLockActive = true
            // Update lock one final time with the stable display state
            lockedTuningState = tuningStateToUse
            if (debugMode) {
                Log.d("Tuner", "HardLock: false → true (stabilized, samples=$frequencyCount, state=$tuningStateToUse)")
            }
        }

        val displayState = if (categoryStabilized) {
            lastDisplayedCategory = stateCategory
            tuningStateToUse
        } else {
            // Keep showing the last stable category, but with updated cents
            when (previousCategory) {
                0 -> TuningState.TooLow(cents)
                1 -> TuningState.InTune(cents)
                2 -> TuningState.TooHigh(cents)
                else -> tuningStateToUse // -1 = NoSignal, switch immediately
            }
        }

        val newDebugStats = if (debugMode) {
            DebugStats(
                rmsAmplitude = result.rmsAmplitude,
                peakAmplitude = peakAmplitude,
                frequencySamples = frequencyCount,
                amplitudeSamples = amplitudeCount,
                consecutiveSilentFrames = consecutiveSilentFrames,
                pendingStateCount = pendingCategoryCount,
                isLocked = lockedFrequency != null,
                isInDecay = isInDecay,
                lastAttackTime = lastAttackTime,
                timeSinceAttack = SystemClock.elapsedRealtime() - lastAttackTime,
                attackCount = totalAttackCount,
                audioFeedbackCount = params.audioFeedbackCount,
                noiseFloor = noiseFloorEma,
                attackArmed = attackArmed,
                hardLockActive = hardLockActive,
                minAttackThreshold = minAttackThreshold()
            )
        } else null

        // Mark voice feedback pending; the ViewModel dispatches the actual feedback off
        // the audio coroutine via FeedbackManager.
        if (isNewAttack) {
            pendingVoiceFeedback = true
        }

        // All feedback (audio, haptic, voice) triggers together once state has stabilized
        // after a new attack. FeedbackManager only fires on newAttack=true, so we don't
        // dispatch otherwise.
        var feedbackTrigger: FeedbackTrigger? = null
        if (pendingCategoryCount >= params.stateStabilityCount && pendingVoiceFeedback) {
            pendingVoiceFeedback = false

            // Suppress mic input during feedback to prevent hearing ourselves
            suppressUntilMs = SystemClock.elapsedRealtime() + FEEDBACK_SUPPRESS_MS
            if (debugMode) {
                Log.d("Tuner", "Suppression: entered for ${FEEDBACK_SUPPRESS_MS}ms")
                Log.d("Tuner", "Feedback: trigger state=$displayState note=${targetNote.displayName}")
            }
            feedbackTrigger = FeedbackTrigger(displayState, newAttack = true)
        }

        return PipelineOutput(
            newSelectedStringIndex = currentSelectedIndex.takeIf { it != params.selectedStringIndex },
            display = PipelineDisplay.Show(
                detectedFrequency = frequencyToUse,
                detectedNote = noteToUse,
                tuningState = displayState,
                cents = cents,
                confidence = result.probability,
            ),
            debugStats = newDebugStats,
            feedback = feedbackTrigger,
        )
    }

    /**
     * Get a simple category for state comparison (ignores cents value).
     * Returns: 0 = TooLow, 1 = InTune, 2 = TooHigh, -1 = NoSignal
     */
    private fun getStateCategory(state: TuningState): Int {
        return when (state) {
            is TuningState.TooLow -> 0
            is TuningState.InTune -> 1
            is TuningState.TooHigh -> 2
            is TuningState.NoSignal -> -1
        }
    }

    /**
     * Detect if a new string attack (strike) has occurred.
     *
     * Uses two strategies:
     * 1. Silence -> sound transition (string plucked from quiet)
     * 2. Amplitude spike during sustain (re-strike while note is still sounding)
     *
     * Improvements over naive detection:
     * - Arm/disarm state: only one attack per note event until decay re-arms
     * - Hysteresis thresholds prevent flip-flopping around silence boundary
     * - Spike detection requires both ratio AND absolute delta
     * - Spike detection gated by recent valid pitch (prevents mic bump false positives)
     * - 3-frame RMS smoothing reduces jitter-induced false triggers
     *
     * @param currentAmplitudeRaw The current RMS amplitude (unsmoothed)
     * @return true if a new attack was detected
     */
    private fun detectAttack(currentAmplitudeRaw: Float): Boolean {
        val now = SystemClock.elapsedRealtime()

        // Add to amplitude ring buffer first (always, needed for baseline)
        amplitudeRingBuffer[amplitudeIndex] = currentAmplitudeRaw
        amplitudeIndex = (amplitudeIndex + 1) % AMPLITUDE_HISTORY_SIZE
        amplitudeCount = minOf(amplitudeCount + 1, AMPLITUDE_HISTORY_SIZE)

        // 3-frame smoothing without allocation
        val currentAmplitude = if (amplitudeCount >= 3) {
            val i0 = (amplitudeIndex - 1 + AMPLITUDE_HISTORY_SIZE) % AMPLITUDE_HISTORY_SIZE
            val i1 = (amplitudeIndex - 2 + AMPLITUDE_HISTORY_SIZE) % AMPLITUDE_HISTORY_SIZE
            val i2 = (amplitudeIndex - 3 + AMPLITUDE_HISTORY_SIZE) % AMPLITUDE_HISTORY_SIZE
            (amplitudeRingBuffer[i0] + amplitudeRingBuffer[i1] + amplitudeRingBuffer[i2]) / 3f
        } else {
            currentAmplitudeRaw
        }

        // NOTE: Noise floor EMA is updated in process() during confirmed silence
        // (after SILENCE_FRAMES_FOR_NOISE_UPDATE frames with no valid pitch).
        // This prevents ringing note decay from poisoning the noise estimate.

        // Derive thresholds from noise floor (hybrid formulas for robustness)
        val silenceThresholdLow = noiseFloorEma * SILENCE_LOW_MULT
        val silenceThresholdHigh = noiseFloorEma * SILENCE_HIGH_MULT
        val minAttackAmplitude = minAttackThreshold()
        val minSpikeDelta = minSpikeDelta()

        // CRITICAL: Snapshot silence state BEFORE updating hysteresis
        val wasInSilence = consecutiveSilentFrames >= SILENCE_FRAMES_REQUIRED

        // Update silence tracking with noise-adaptive hysteresis thresholds
        if (currentAmplitude < silenceThresholdLow) {
            consecutiveSilentFrames++
        } else if (currentAmplitude > silenceThresholdHigh) {
            consecutiveSilentFrames = 0
        }
        // else: in hysteresis band, keep current state

        val isInSilence = consecutiveSilentFrames >= SILENCE_FRAMES_REQUIRED

        // ARM/DISARM LOGIC: Only allow one attack per "note event"
        // While disarmed, track peak and check for re-arm conditions
        if (!attackArmed) {
            // Track peak amplitude while note is active
            if (currentAmplitude > notePeakAmplitude) {
                notePeakAmplitude = currentAmplitude
            }

            // Re-arm conditions (any one triggers re-arm):
            // 1. Amplitude dropped below threshold for enough frames
            // 2. True silence detected
            // 3. Time-based: been disarmed too long (note sustained but user wants to play another)
            //
            // Threshold = max(decay-based, noise-based), CAPPED at MAX_REARM_THRESHOLD:
            // - Decay-based: notePeak * 0.25 (works for loud strings)
            // - Noise-based: noiseFloor * 1.2 (ensures quiet strings can always re-arm)
            // - Cap: prevents loud→quiet transitions (E2→D3) from getting stuck
            val decayThreshold = notePeakAmplitude * REARM_RATIO
            val noiseThreshold = noiseFloorEma * REARM_NOISE_MULT
            val rearmThreshold = minOf(maxOf(decayThreshold, noiseThreshold), MAX_REARM_THRESHOLD)
            val belowRearm = currentAmplitude < rearmThreshold

            if (belowRearm) {
                belowRearmCount++
            } else {
                belowRearmCount = 0
            }

            val timeSinceAttack = now - lastAttackTime
            val timedOut = timeSinceAttack >= MAX_DISARM_TIME_MS

            if (belowRearmCount >= REARM_FRAMES_REQUIRED || isInSilence || timedOut) {
                if (debugMode) {
                    val reason = when {
                        isInSilence -> "silence"
                        timedOut -> "timeout(${timeSinceAttack}ms)"
                        else -> "decay(${belowRearmCount}frames)"
                    }
                    Log.d("Tuner", "Rearm: reason=$reason amp=${"%.4f".format(currentAmplitude)} threshold=${"%.4f".format(rearmThreshold)} noise=${"%.4f".format(noiseFloorEma)}")
                }
                attackArmed = true
                belowRearmCount = 0
                notePeakAmplitude = 0f
            }

            // Still disarmed - no attack can be detected
            return false
        }

        // STRATEGY 1: Coming out of silence (BEFORE time gate)
        // If we were in extended silence and now we're not, that's an attack.
        // This bypasses the time gate because attackArmed already prevents multi-triggers,
        // and we want fast string switching (E2→A2→D3 in quick succession) to work.
        if (wasInSilence && !isInSilence && currentAmplitude >= minAttackAmplitude) {
            val timeSinceLastAttack = now - lastAttackTime
            if (debugMode) {
                Log.d("Tuner", "Attack[silence→sound]: ampRaw=${"%.4f".format(currentAmplitudeRaw)} ampSmooth=${"%.4f".format(currentAmplitude)} noise=${"%.4f".format(noiseFloorEma)} thresh=${"%.4f".format(minAttackAmplitude)} armed=$attackArmed timeSince=${timeSinceLastAttack}ms")
            }
            triggerAttack(now, currentAmplitude)
            return true
        }

        // Time gate - only applies to spike attacks (re-strikes during sustain)
        // Silence→sound attacks already handled above and bypass this gate
        val timeSinceLastAttack = now - lastAttackTime
        if (timeSinceLastAttack < MIN_ATTACK_INTERVAL_MS) {
            return false
        }

        // STRATEGY 2: Detect amplitude spike while note is sustaining
        // Only allow if we had a valid pitched frame recently (prevents mic bumps)
        val pitchIsRecent = (now - lastGoodPitchTime) <= MAX_PITCH_AGE_FOR_SPIKE_MS

        if (pitchIsRecent && amplitudeCount >= 6 && currentAmplitude >= minAttackAmplitude) {
            // Compute baseline from ring buffer (older samples, exclude recent 3)
            val baseline = computeBaselineFromRingBuffer()

            if (baseline > 0f) {
                val delta = currentAmplitude - baseline

                // Require BOTH ratio AND noise-adaptive delta to avoid false triggers
                if (currentAmplitude > baseline * SPIKE_RATIO && delta >= minSpikeDelta) {
                    if (debugMode) {
                        Log.d("Tuner", "Attack[spike]: amp=${"%.4f".format(currentAmplitude)} baseline=${"%.4f".format(baseline)} delta=${"%.4f".format(delta)} noise=${"%.4f".format(noiseFloorEma)} minDelta=${"%.4f".format(minSpikeDelta)}")
                    }
                    triggerAttack(now, currentAmplitude)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Minimum amplitude required to count as an attack. Hybrid formula: scales with the
     * noise floor for typical conditions, with an additive offset so very-quiet rooms
     * still need a clear rise above ambient.
     */
    private fun minAttackThreshold(): Float =
        maxOf(noiseFloorEma * MIN_ATTACK_MULT, noiseFloorEma + MIN_ATTACK_OFFSET)

    /**
     * Build the debug-stats frame to surface to the UI when we're rejecting (or holding)
     * a frame and don't have a fresh pitch reading. Returns null when debug mode is off
     * so we don't allocate.
     */
    private fun buildRejectedDebugStats(rmsAmplitude: Float, params: PipelineParams): DebugStats? {
        if (!debugMode) return null
        return params.previousDebugStats.copy(
            rmsAmplitude = rmsAmplitude,
            amplitudeSamples = amplitudeCount,
            consecutiveSilentFrames = consecutiveSilentFrames,
            timeSinceAttack = SystemClock.elapsedRealtime() - lastAttackTime,
            noiseFloor = noiseFloorEma,
            attackArmed = attackArmed,
            hardLockActive = hardLockActive,
            minAttackThreshold = minAttackThreshold()
        )
    }

    /**
     * Minimum amplitude delta (vs. baseline) for a sustain-spike attack. Hybrid formula:
     * noise-relative for quiet rooms with an absolute floor for high strings.
     */
    private fun minSpikeDelta(): Float =
        maxOf(noiseFloorEma * MIN_SPIKE_DELTA_MULT, MIN_SPIKE_DELTA_FLOOR)

    /**
     * Compute baseline amplitude from older samples in ring buffer (excludes last 3).
     * Used for spike detection to compare current amplitude against recent history.
     */
    private fun computeBaselineFromRingBuffer(): Float {
        if (amplitudeCount < 6) return 0f

        var sum = 0f
        var count = 0
        // Iterate from oldest to (current - 3), excluding the most recent 3 samples
        val samplesToUse = amplitudeCount - 3
        for (i in 0 until samplesToUse) {
            // Calculate index going backwards from current, skipping the last 3
            val idx = (amplitudeIndex - 4 - i + AMPLITUDE_HISTORY_SIZE) % AMPLITUDE_HISTORY_SIZE
            sum += amplitudeRingBuffer[idx]
            count++
        }
        return if (count > 0) sum / count else 0f
    }

    /**
     * Auto-mode acquisition path that fires on pitch-class shift alone (no amplitude rise required).
     * Returns the index to switch to once N consecutive frames agree, or -1 if not firing this frame.
     *
     * Uses raw incoming frequency (NOT smoothed — buffer still holds old-string samples).
     * Uses raw probability (NOT minConfidenceThreshold — separate, lower gate for high strings).
     */
    private fun evaluateAutoStringCandidate(
        rawFreq: Float,
        probability: Float,
        currentSelectedIdx: Int,
        strings: List<StringNote>
    ): Int {
        if (!autoStringDetectionEnabled || strings.isEmpty()) return -1
        if (probability < AUTO_SWITCH_MIN_PROBABILITY) {
            candidateStringIndex = -1; candidateStringFrames = 0; return -1
        }
        val idx = tuningEngine.findClosestString(rawFreq, strings, AUTO_SWITCH_MAX_CENTS)
        if (idx < 0 || idx == currentSelectedIdx) {
            candidateStringIndex = -1; candidateStringFrames = 0; return -1
        }
        val targetFreq = strings.getOrNull(currentSelectedIdx)?.frequency ?: return -1
        if (abs(tuningEngine.calculateCents(rawFreq, targetFreq)) < AUTO_SWITCH_MIN_SHIFT_CENTS) {
            candidateStringIndex = -1; candidateStringFrames = 0; return -1
        }

        // Octave-error guard: reject if rawFreq is within ~50 cents of an integer-octave
        // shift from the currently anchored frequency. This catches YIN sub-harmonics
        // during decay (e.g., D3@147Hz reading as 73Hz). Real octave plucks must come
        // through the amplitude-rise paths (spike or implied attack).
        val anchorFreq = lockedFrequency ?: targetFreq
        if (anchorFreq > 0f) {
            val centsFromAnchor = tuningEngine.calculateCents(rawFreq, anchorFreq)
            val nearestOctave = round(centsFromAnchor / 1200f) * 1200f
            if (nearestOctave != 0f && abs(centsFromAnchor - nearestOctave) < AUTO_SWITCH_OCTAVE_GUARD_CENTS) {
                candidateStringIndex = -1; candidateStringFrames = 0; return -1
            }
        }
        if (idx == candidateStringIndex) {
            candidateStringFrames++
        } else {
            candidateStringIndex = idx
            candidateStringFrames = 1
        }
        return if (candidateStringFrames >= AUTO_SWITCH_CONFIRM_FRAMES) idx else -1
    }

    /**
     * Compute smoothed frequency from ring buffer.
     */
    private fun computeSmoothedFrequency(smoothingWindowSize: Int): Float {
        if (frequencyCount == 0) return 0f

        var sum = 0f
        val effectiveWindowSize = minOf(smoothingWindowSize, MAX_SMOOTHING_WINDOW)
        for (i in 0 until frequencyCount) {
            val idx = (frequencyIndex - 1 - i + effectiveWindowSize) % effectiveWindowSize
            sum += frequencyRingBuffer[idx]
        }
        return sum / frequencyCount
    }

    /**
     * Record an attack event and disarm until note decays.
     */
    private fun triggerAttack(now: Long, amplitude: Float) {
        lastAttackTime = now
        totalAttackCount++
        attackArmed = false
        notePeakAmplitude = amplitude
        belowRearmCount = 0
    }

    /**
     * Reset state for acquiring a new note.
     * Consolidates the common reset logic used by attack detection, implied attack,
     * and attackless acquisition to avoid duplication and ensure consistency.
     *
     * Always resets lastDisplayedCategory because any call to this function represents
     * a genuine new note event. Preserving stale category state across note boundaries
     * causes stability gating to incorrectly compare the new note against the old one.
     *
     * @param rmsAmplitude Current RMS amplitude to set as initial peak
     * @param reason Debug tag for logging (e.g., "attack", "implied", "attackless")
     */
    private fun resetForNewNoteAcquisition(rmsAmplitude: Float, reason: String) {
        if (debugMode) {
            val wasLocked = lockedFrequency != null || hardLockActive
            Log.d("Tuner", "NewNote[$reason]: rms=${"%.4f".format(rmsAmplitude)} wasLocked=$wasLocked hardLock=$hardLockActive lockedNote=${lockedNote?.display}")
        }

        // Clear ALL lock state
        hardLockActive = false
        lockedFrequency = null
        lockedNote = null
        lockedTuningState = null
        peakAmplitude = rmsAmplitude

        // Reset acquisition buffers for clean note acquisition
        frequencyIndex = 0
        frequencyCount = 0

        // Reset ALL stability tracking - new note must not inherit prior category
        lastDisplayedCategory = -1
        pendingCategory = -1
        pendingCategoryCount = 0

        // Reset candidate machine — new note event starts a fresh candidate count
        candidateStringIndex = -1
        candidateStringFrames = 0

        // Mark auto-detection pending with timestamp for time-based window
        if (autoStringDetectionEnabled) {
            pendingAutoDetection = true
            autoDetectionStartTime = SystemClock.elapsedRealtime()
        }
    }
}
