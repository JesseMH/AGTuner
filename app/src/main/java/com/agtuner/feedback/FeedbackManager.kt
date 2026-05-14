package com.agtuner.feedback

import android.os.SystemClock
import com.agtuner.tuning.TuningState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates all feedback types (audio, haptic, voice) based on tuning state.
 *
 * Implements cooldown to prevent feedback spam and ensures proper
 * synchronization between feedback types.
 *
 * Thread-safe: provideFeedback may be invoked concurrently from coroutines
 * launched on viewModelScope, so a mutex protects the cooldown bookkeeping.
 */
@Singleton
class FeedbackManager @Inject constructor(
    private val audioFeedback: AudioFeedback,
    private val hapticFeedback: HapticFeedback,
    private val voiceFeedback: VoiceFeedback
) {
    companion object {
        // Minimum time between feedback events (ms)
        // Prevents feedback spam if multiple attacks are detected in quick succession
        private const val FEEDBACK_COOLDOWN_MS = 800L
    }

    // Mutex for thread-safe access to mutable state
    private val mutex = Mutex()

    // Use monotonic clock (elapsedRealtime) for cooldowns - immune to time changes
    private var lastFeedbackTime = 0L

    @Volatile
    private var preferences = FeedbackPreferences()

    // Debug: track audio feedback triggers (only meaningful in debug builds)
    @Volatile
    var audioFeedbackCount = 0
        private set

    /**
     * Update feedback preferences.
     * Pre-initializes TTS when voice is enabled so it's ready for first pluck.
     */
    fun setPreferences(prefs: FeedbackPreferences) {
        preferences = prefs
        // Pre-initialize TTS when voice is enabled so first pluck works immediately
        if (prefs.voiceEnabled) {
            voiceFeedback.ensureInitialized()
        }
    }

    /**
     * Get current feedback preferences.
     */
    fun getPreferences(): FeedbackPreferences = preferences

    /**
     * Provide feedback for the current tuning state.
     *
     * @param state The current tuning state
     * @param newAttack Whether a new string attack was detected (triggers voice feedback)
     */
    suspend fun provideFeedback(state: TuningState, newAttack: Boolean = false) {
        // Use monotonic clock for reliable timing regardless of system time changes
        val now = SystemClock.elapsedRealtime()

        // All feedback types (audio, haptic, voice) only trigger on new string attacks
        // This ensures consistent behavior across all accessibility feedback modes
        if (!newAttack) return

        val shouldGiveFeedback: Boolean

        mutex.withLock {
            // Check cooldown - prevents feedback spam if attacks double-fire
            shouldGiveFeedback = (now - lastFeedbackTime) >= FEEDBACK_COOLDOWN_MS

            if (shouldGiveFeedback) {
                lastFeedbackTime = now
            }
        }

        if (!shouldGiveFeedback) return

        // Execute feedback in parallel
        coroutineScope {
            // Audio feedback
            if (preferences.audioEnabled) {
                audioFeedbackCount++
                launch {
                    audioFeedback.play(state)
                }
            }

            // Haptic feedback - cancel any ongoing vibration first to prevent overlap
            if (preferences.hapticEnabled) {
                launch {
                    hapticFeedback.cancel()
                    hapticFeedback.vibrate(state)
                }
            }

            // Voice feedback (QUEUE_FLUSH inside VoiceFeedback replaces any
            // in-flight utterance, so no busy check is needed here).
            if (preferences.voiceEnabled && shouldProvideVoice(state)) {
                launch {
                    voiceFeedback.announce(state)
                }
            }
        }
    }

    private fun shouldProvideVoice(state: TuningState): Boolean {
        // VoiceFeedback uses QUEUE_FLUSH internally, so we never need to gate
        // on whether a previous utterance is still speaking — the next one
        // simply replaces it, keeping voice aligned with audio/haptic.

        // If voice is set to in-tune only, check state
        if (preferences.voiceOnInTuneOnly) {
            return state is TuningState.InTune
        }

        return true
    }

    /**
     * Stop all ongoing feedback.
     */
    fun stopAll() {
        audioFeedback.stop()
        hapticFeedback.cancel()
        voiceFeedback.stop()
    }

    /**
     * Release all feedback resources.
     */
    fun release() {
        audioFeedback.release()
        voiceFeedback.shutdown()
    }

    /**
     * Reset state tracking (call when switching strings or restarting agtuner).
     */
    suspend fun reset() {
        mutex.withLock {
            lastFeedbackTime = 0L
            audioFeedbackCount = 0
        }
    }
}
