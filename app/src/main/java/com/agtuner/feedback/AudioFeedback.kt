package com.agtuner.feedback

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.agtuner.tuning.TuningState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides audio tone feedback for tuning states.
 *
 * Uses distinct tones for each state:
 * - Too High: Descending tone (indicates need to tune down)
 * - Too Low: Ascending tone (indicates need to tune up)
 * - In Tune: Pleasant confirmation beep
 *
 * Uses STREAM_MUSIC with AudioAttributes.USAGE_ASSISTANCE_SONIFICATION semantics
 * for reliability - STREAM_NOTIFICATION can be muted by DND on some devices.
 */
@Singleton
class AudioFeedback @Inject constructor() {
    // ToneGenerator for feedback sounds
    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val TAG = "AudioFeedback"
        private const val TONE_DURATION_MS = 200
        private const val TONE_VOLUME = 80 // 0-100, slightly reduced to avoid mic feedback
    }

    init {
        try {
            // Use STREAM_MUSIC for reliability - STREAM_NOTIFICATION can be muted by DND
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create ToneGenerator", e)
            toneGenerator = null
        }
    }

    /**
     * Play a tone based on the tuning state.
     */
    fun play(state: TuningState) {
        val toneType = when (state) {
            is TuningState.TooHigh -> ToneGenerator.TONE_PROP_NACK // Descending "no" tone
            is TuningState.TooLow -> ToneGenerator.TONE_PROP_PROMPT // Ascending prompt tone
            is TuningState.InTune -> ToneGenerator.TONE_PROP_ACK // Confirmation "ding"
            is TuningState.NoSignal -> return // No tone for no signal
        }

        try {
            // Stop any currently playing tone before starting a new one
            // This ensures rapid successive attacks each produce audible feedback
            toneGenerator?.stopTone()
            toneGenerator?.startTone(toneType, TONE_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play tone", e)
        }
    }

    /**
     * Stop any currently playing tone.
     */
    fun stop() {
        try {
            toneGenerator?.stopTone()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop tone", e)
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release ToneGenerator", e)
        }
        toneGenerator = null
    }
}
