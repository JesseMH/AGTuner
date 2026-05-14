package com.agtuner.audio

/**
 * Result of pitch detection analysis.
 *
 * @param frequency Detected fundamental frequency in Hz, or -1 if no pitch detected
 * @param probability Confidence level of detection (0.0 to 1.0)
 * @param isPitched Whether a clear pitched sound was detected
 * @param rmsAmplitude RMS amplitude of the signal (0.0 to 1.0), used for attack detection
 */
data class PitchResult(
    val frequency: Float,
    val probability: Float,
    val isPitched: Boolean,
    val rmsAmplitude: Float = 0f
)
