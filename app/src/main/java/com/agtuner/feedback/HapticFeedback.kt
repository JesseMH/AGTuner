package com.agtuner.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.agtuner.tuning.TuningState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides haptic (vibration) feedback for tuning states.
 *
 * Each tuning state has a distinct vibration pattern:
 * - Too High: Rapid short pulses (feels "urgent")
 * - Too Low: Slow long pulses (feels "heavy")
 * - In Tune: Single steady vibration (feels "stable")
 */
@Singleton
class HapticFeedback @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        // Pattern format: [delay, vibrate, delay, vibrate, ...]

        // Too High: rapid pulses (feels urgent, "slow down")
        // 3 quick 50ms vibrations with 50ms gaps
        private val PATTERN_TOO_HIGH = longArrayOf(0, 50, 50, 50, 50, 50)

        // Too Low: slow heavy pulses (feels heavy, "bring up")
        // 2 long 200ms vibrations with 100ms gaps
        private val PATTERN_TOO_LOW = longArrayOf(0, 200, 100, 200)

        // In Tune: single steady vibration (feels stable, "hold here")
        // One sustained 500ms vibration
        private val PATTERN_IN_TUNE = longArrayOf(0, 500)

        // Vibration amplitude (0-255, where 255 is strongest)
        private const val AMPLITUDE_STRONG = 255
    }

    /**
     * Check if vibration is available on this device.
     */
    fun isAvailable(): Boolean {
        return vibrator?.hasVibrator() == true
    }

    /**
     * Vibrate with a pattern based on the tuning state.
     */
    fun vibrate(state: TuningState) {
        if (!isAvailable()) return

        val pattern = when (state) {
            is TuningState.TooHigh -> PATTERN_TOO_HIGH
            is TuningState.TooLow -> PATTERN_TOO_LOW
            is TuningState.InTune -> PATTERN_IN_TUNE
            is TuningState.NoSignal -> return // No vibration for no signal
        }

        performVibration(pattern)
    }

    /**
     * Cancel any ongoing vibration.
     */
    fun cancel() {
        vibrator?.cancel()
    }

    private fun performVibration(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Modern API with amplitude control
            val amplitudes = IntArray(pattern.size) { i ->
                if (i % 2 == 0) 0 else AMPLITUDE_STRONG // Alternate between off and on
            }

            val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
            vibrator?.vibrate(effect)
        } else {
            // Legacy API
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }
}
