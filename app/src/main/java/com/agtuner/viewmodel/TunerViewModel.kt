package com.agtuner.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agtuner.audio.AudioProcessor
import com.agtuner.audio.PipelineDisplay
import com.agtuner.audio.PipelineParams
import com.agtuner.audio.PitchPipeline
import com.agtuner.audio.PitchResult
import com.agtuner.data.PreferencesRepository
import com.agtuner.feedback.FeedbackManager
import com.agtuner.feedback.FeedbackPreferences
import com.agtuner.tuning.NoteFrequencies
import com.agtuner.tuning.NoteLabel
import com.agtuner.tuning.StringNote
import com.agtuner.tuning.TuningEngine
import com.agtuner.tuning.TuningPreset
import com.agtuner.tuning.TuningState
import com.agtuner.widget.WidgetLaunchMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug statistics for the tuning screen.
 */
data class DebugStats(
    val rmsAmplitude: Float = 0f,
    val peakAmplitude: Float = 0f,
    val frequencySamples: Int = 0,
    val amplitudeSamples: Int = 0,
    val consecutiveSilentFrames: Int = 0,
    val pendingStateCount: Int = 0,
    val isLocked: Boolean = false,
    val isInDecay: Boolean = false,
    val lastAttackTime: Long = 0L,
    val timeSinceAttack: Long = 0L,
    val attackCount: Int = 0,  // Total attacks detected this session
    val audioFeedbackCount: Int = 0,  // Total audio feedback triggers
    // New fields for attack detection debugging
    val noiseFloor: Float = 0f,
    val attackArmed: Boolean = true,
    val hardLockActive: Boolean = false,
    val minAttackThreshold: Float = 0f  // Computed threshold for attack detection
)

/**
 * UI state for the agtuner screen.
 */
data class TunerUiState(
    val isListening: Boolean = false,
    val isCalibrating: Boolean = false,
    val hasPermission: Boolean = false,
    val detectedFrequency: Float = 0f,
    // Visual + spoken pair so the UI can render "E♭2" while TalkBack reads "E flat 2".
    val detectedNote: NoteLabel = NoteLabel.Empty,
    val tuningState: TuningState = TuningState.NoSignal,
    val cents: Float = 0f,
    val confidence: Float = 0f,
    val selectedStringIndex: Int = 0,
    val stringConfiguration: List<StringNote> = NoteFrequencies.STANDARD_GUITAR,
    // Available tuning presets and the id of the one currently matching `stringConfiguration`.
    // null means the strings don't match any preset (i.e. custom tuning).
    val tuningPresets: List<TuningPreset> = NoteFrequencies.PRESETS,
    val selectedPresetId: String? = NoteFrequencies.PRESET_STANDARD,
    val feedbackPreferences: FeedbackPreferences = FeedbackPreferences(),
    // Admin tuning parameters
    val inTuneThreshold: Float = 10f,
    val smoothingWindow: Int = 20,
    val confidenceThreshold: Float = 0.90f,
    val stabilityCount: Int = 10,
    // Auto string detection mode
    val autoStringDetection: Boolean = false,
    // Debug mode
    val debugMode: Boolean = false,
    val debugStats: DebugStats = DebugStats(),
)

@HiltViewModel
class TunerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val audioProcessor: AudioProcessor,
    private val tuningEngine: TuningEngine,
    private val feedbackManager: FeedbackManager,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    // Pitch detection / smoothing / locking pipeline. VM-scoped because state
    // is per-ViewModel-instance; constructed inline (no extra Hilt binding needed).
    private val pipeline = PitchPipeline(tuningEngine)

    // Current tuning parameters (updated from preferences, fed into pipeline params)
    private var smoothingWindowSize: Int = 20
    private var minConfidenceThreshold: Float = 0.90f
    private var stateStabilityCount: Int = 10

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    private var listeningJob: Job? = null

    // Throttle for the "last in-tune at" DataStore write. Without this, micro-oscillations
    // across the in-tune cents boundary (e.g. +3.5 ↔ -3.6 cents) cause ~10 writes/second
    // while the user holds a note steady. We only persist the first transition within
    // [IN_TUNE_WRITE_THROTTLE_MS] of the previous one — the timestamp still reflects
    // the most recent in-tune moment, but DataStore (and the widget) aren't hammered.
    private var lastInTuneWriteEpochMs = 0L

    init {
        // Check initial permission state
        _uiState.update { it.copy(hasPermission = audioProcessor.hasPermission()) }

        // Observe preferences
        viewModelScope.launch {
            preferencesRepository.feedbackPreferences.collect { prefs ->
                feedbackManager.setPreferences(prefs)
                _uiState.update { it.copy(feedbackPreferences = prefs) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.stringConfiguration.collect { config ->
                _uiState.update { state ->
                    state.copy(
                        stringConfiguration = config,
                        selectedStringIndex = state.selectedStringIndex.coerceIn(0, config.size - 1),
                        selectedPresetId = NoteFrequencies.findMatchingPreset(config)?.id
                    )
                }
            }
        }

        // Persisted selected-string index: restore on launch + react to widget-driven changes.
        // Clamp against current configuration size to handle the user removing strings between
        // sessions. The selectString() and pipeline auto-switch paths write back here, so this
        // collector only reads — no feedback loop.
        viewModelScope.launch {
            preferencesRepository.selectedStringIndex.collect { savedIndex ->
                _uiState.update { state ->
                    val clamped = savedIndex.coerceIn(0, (state.stringConfiguration.size - 1).coerceAtLeast(0))
                    if (state.selectedStringIndex == clamped) state else state.copy(selectedStringIndex = clamped)
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.inTuneThreshold.collect { threshold ->
                tuningEngine.setInTuneThreshold(threshold)
                _uiState.update { it.copy(inTuneThreshold = threshold) }
            }
        }

        // Observe admin tuning parameters
        viewModelScope.launch {
            preferencesRepository.smoothingWindow.collect { window ->
                smoothingWindowSize = window
                _uiState.update { it.copy(smoothingWindow = window) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.confidenceThreshold.collect { threshold ->
                minConfidenceThreshold = threshold
                _uiState.update { it.copy(confidenceThreshold = threshold) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.stabilityCount.collect { count ->
                stateStabilityCount = count
                _uiState.update { it.copy(stabilityCount = count) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.debugMode.collect { enabled ->
                _uiState.update { it.copy(debugMode = enabled) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.autoStringDetection.collect { enabled ->
                _uiState.update { it.copy(autoStringDetection = enabled) }
            }
        }

        // Observe pitch detection results
        // Use collectLatest: if a new pitch result arrives while processing,
        // cancel the previous processing and handle the new one.
        // This keeps the agtuner responsive and avoids backlog.
        viewModelScope.launch {
            audioProcessor.pitchFlow.collectLatest { result ->
                processPitchResult(result)
            }
        }
    }

    /**
     * Update permission state after user grants/denies permission.
     */
    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
    }

    /**
     * Re-query the system for the current mic permission state. Call on
     * lifecycle resume so the UI reflects changes made outside the app
     * (e.g. user toggled the permission in system Settings).
     */
    fun refreshPermissionState() {
        _uiState.update { it.copy(hasPermission = audioProcessor.hasPermission()) }
    }

    /**
     * Start listening for audio input.
     */
    fun startListening() {
        if (_uiState.value.isListening) return
        if (!_uiState.value.hasPermission) return

        // Reset all tracking state for clean start, including a fresh noise-floor
        // calibration so thresholds adapt to current room conditions.
        pipeline.resetForNewSession()
        // Allow the next in-tune transition to write the widget timestamp even if
        // we just stopped a session moments ago.
        lastInTuneWriteEpochMs = 0L

        _uiState.update {
            it.copy(
                isListening = true,
                isCalibrating = true,
                tuningState = TuningState.NoSignal
            )
        }

        listeningJob = viewModelScope.launch {
            feedbackManager.reset()
            audioProcessor.startListening()
        }
    }

    /**
     * Stop listening for audio input.
     */
    fun stopListening() {
        // Cancelling the job exits AudioProcessor.startListening's withContext block,
        // which releases the AudioRecord in its finally handler.
        listeningJob?.cancel()
        listeningJob = null
        feedbackManager.stopAll()

        pipeline.reset()

        _uiState.update {
            it.copy(
                isListening = false,
                isCalibrating = false,
                tuningState = TuningState.NoSignal,
                detectedFrequency = 0f,
                detectedNote = NoteLabel.Empty,
                cents = 0f,
                confidence = 0f,
            )
        }
    }

    /**
     * Select a string to tune.
     */
    fun selectString(index: Int) {
        if (index < 0 || index >= _uiState.value.stringConfiguration.size) return

        pipeline.reset()

        _uiState.update {
            it.copy(
                selectedStringIndex = index,
                tuningState = TuningState.NoSignal,
            )
        }
        viewModelScope.launch {
            feedbackManager.reset()
            preferencesRepository.setSelectedStringIndex(index)
        }
    }

    /**
     * Update the string configuration.
     */
    fun updateStringConfiguration(config: List<StringNote>) {
        viewModelScope.launch {
            preferencesRepository.setStringConfiguration(config)
        }
    }

    /**
     * Apply a tuning preset by id. Updates the persisted string configuration
     * to match the preset; the stringConfiguration collector then refreshes UI state.
     */
    fun selectTuningPreset(presetId: String) {
        val preset = NoteFrequencies.PRESETS.firstOrNull { it.id == presetId } ?: return
        updateStringConfiguration(preset.strings)
    }

    /**
     * Update a single string's note.
     */
    fun updateStringNote(index: Int, note: StringNote) {
        val currentConfig = _uiState.value.stringConfiguration.toMutableList()
        if (index in currentConfig.indices) {
            currentConfig[index] = note
            updateStringConfiguration(currentConfig)
        }
    }

    /**
     * Add a new string to the configuration.
     */
    fun addString() {
        val currentConfig = _uiState.value.stringConfiguration
        if (currentConfig.size >= MAX_STRINGS) return

        // Add a string higher than the highest current string
        val lastNote = currentConfig.lastOrNull() ?: NoteFrequencies.STANDARD_GUITAR.last()
        val allNotes = NoteFrequencies.getAllNotes()
        val nextNote = allNotes.find { it.frequency > lastNote.frequency } ?: lastNote

        updateStringConfiguration(currentConfig + nextNote)
    }

    /**
     * Remove a string from the configuration.
     */
    fun removeString(index: Int) {
        val currentConfig = _uiState.value.stringConfiguration
        if (currentConfig.size <= 1) return // Min 1 string

        updateStringConfiguration(currentConfig.filterIndexed { i, _ -> i != index })
    }

    /**
     * Update feedback preferences.
     */
    fun setAudioFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAudioEnabled(enabled)
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHapticEnabled(enabled)
        }
    }

    fun setVoiceFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVoiceEnabled(enabled)
        }
    }

    fun setVoiceOnInTuneOnlyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVoiceOnInTuneOnly(enabled)
        }
    }

    // Admin tuning parameter setters
    fun setInTuneThreshold(cents: Float) {
        viewModelScope.launch {
            preferencesRepository.setInTuneThreshold(cents)
        }
    }

    fun setSmoothingWindow(window: Int) {
        viewModelScope.launch {
            preferencesRepository.setSmoothingWindow(window)
        }
    }

    fun setConfidenceThreshold(threshold: Float) {
        viewModelScope.launch {
            preferencesRepository.setConfidenceThreshold(threshold)
        }
    }

    fun setStabilityCount(count: Int) {
        viewModelScope.launch {
            preferencesRepository.setStabilityCount(count)
        }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDebugMode(enabled)
        }
    }

    fun setAutoStringDetection(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoStringDetection(enabled)
        }
    }

    /**
     * Toggle auto string detection mode.
     */
    fun toggleAutoStringDetection() {
        setAutoStringDetection(!_uiState.value.autoStringDetection)
    }

    /**
     * Entry point for the home-screen widget's launch actions. Each preset
     * button applies its tuning, enables auto-string-detection, and starts
     * listening (calibration runs as the first phase of [startListening]).
     *
     * If permission isn't granted yet the listener won't start; the TunerScreen
     * will still render the GRANT MICROPHONE button. Once the user grants and
     * re-enters, they tap START as usual.
     */
    fun startListeningFromWidget(mode: WidgetLaunchMode) {
        viewModelScope.launch {
            val preset = NoteFrequencies.PRESETS.firstOrNull { it.id == mode.presetId }
            if (preset != null) {
                preferencesRepository.setStringConfiguration(preset.strings)
            }
            preferencesRepository.setAutoStringDetection(true)
            // startListening() reads autoStringDetection from the UI state on each
            // frame, so a stale value for the very first frame is harmless.
            startListening()
        }
    }

    /**
     * Hand a single PitchResult to the pipeline and route the structured output
     * back into _uiState plus FeedbackManager. All pipeline state lives in
     * [PitchPipeline]; this method is pure orchestration.
     */
    private fun processPitchResult(result: PitchResult) {
        val current = _uiState.value
        val params = PipelineParams(
            selectedStringIndex = current.selectedStringIndex,
            stringConfiguration = current.stringConfiguration,
            autoStringDetection = current.autoStringDetection,
            smoothingWindowSize = smoothingWindowSize,
            minConfidenceThreshold = minConfidenceThreshold,
            stateStabilityCount = stateStabilityCount,
            isCalibrating = current.isCalibrating,
            debugMode = current.debugMode,
            previousDebugStats = current.debugStats,
            audioFeedbackCount = feedbackManager.audioFeedbackCount,
        )

        val output = pipeline.process(result, params)

        val priorTuningState = current.tuningState

        // Apply pipeline output atomically (single _uiState.update per frame).
        _uiState.update { state ->
            var next = state
            if (output.calibrationCompleted) {
                next = next.copy(isCalibrating = false)
            }
            output.newSelectedStringIndex?.let { idx ->
                next = next.copy(selectedStringIndex = idx)
            }
            when (val display = output.display) {
                PipelineDisplay.NoChange -> { /* no-op */ }
                PipelineDisplay.ClearAll -> {
                    next = next.copy(
                        tuningState = TuningState.NoSignal,
                        detectedFrequency = 0f,
                        detectedNote = NoteLabel.Empty,
                        cents = 0f,
                        confidence = 0f,
                    )
                }
                is PipelineDisplay.Show -> {
                    next = next.copy(
                        detectedFrequency = display.detectedFrequency,
                        detectedNote = display.detectedNote,
                        tuningState = display.tuningState,
                        cents = display.cents,
                        confidence = display.confidence,
                    )
                }
            }
            output.debugStats?.let { next = next.copy(debugStats = it) }
            next
        }

        // Persist side-effects after the state update has settled.
        // Auto-switched string: keep the persisted index in sync so the widget's
        // "last string" button reflects what the user actually played most recently.
        output.newSelectedStringIndex?.let { idx ->
            viewModelScope.launch {
                preferencesRepository.setSelectedStringIndex(idx)
            }
        }
        // InTune transition: record the moment the user achieves a stable in-tune
        // reading so the home-screen widget can show "Last tuned X ago." Throttled
        // because cents oscillating across the in-tune boundary produces many edge
        // transitions in quick succession; only the first one in each throttle
        // window writes.
        val newTuningState = (output.display as? PipelineDisplay.Show)?.tuningState
        if (newTuningState is TuningState.InTune && priorTuningState !is TuningState.InTune) {
            val now = System.currentTimeMillis()
            if (now - lastInTuneWriteEpochMs >= IN_TUNE_WRITE_THROTTLE_MS) {
                lastInTuneWriteEpochMs = now
                val presetId = _uiState.value.selectedPresetId
                Log.d(TAG, "InTune transition detected (prior=$priorTuningState) — writing lastInTuneAt=$now presetId=$presetId")
                viewModelScope.launch {
                    preferencesRepository.setLastInTuneAt(now)
                    preferencesRepository.setLastTunedPresetId(presetId)
                }
            }
        }

        // Provide feedback - DECOUPLED from pitch processing
        // Launch in separate coroutine so feedback doesn't block UI updates
        output.feedback?.let { trigger ->
            viewModelScope.launch {
                feedbackManager.provideFeedback(
                    trigger.state,
                    newAttack = trigger.newAttack
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
        feedbackManager.release()
    }

    companion object {
        const val MAX_STRINGS = 12
        private const val TAG = "TunerViewModel"
        // Minimum gap between two "last in-tune at" writes. Long enough to drop
        // oscillation bursts; short enough that switching strings mid-session still
        // updates the widget.
        private const val IN_TUNE_WRITE_THROTTLE_MS = 5_000L
    }
}
