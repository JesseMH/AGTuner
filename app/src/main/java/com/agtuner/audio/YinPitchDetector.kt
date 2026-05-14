package com.agtuner.audio

import kotlin.math.abs

/**
 * YIN pitch detection algorithm implementation.
 *
 * Based on the paper "YIN, a fundamental frequency estimator for speech and music"
 * by Alain de Cheveigné and Hideki Kawahara (2002).
 *
 * The algorithm works in these steps:
 * 1. Compute difference function
 * 2. Compute cumulative mean normalized difference (CMND) function
 * 3. Find absolute threshold (first CMND dip below threshold)
 * 4. Apply parabolic interpolation for sub-sample accuracy
 *
 * Optimized to reuse buffers and avoid allocations in the hot path.
 */
class YinPitchDetector {
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE
    private val bufferSize: Int = AudioConfig.YIN_BUFFER_SIZE
    private val cmndThreshold: Float = AudioConfig.YIN_CMND_THRESHOLD

    companion object {
        // Minimum RMS amplitude to consider signal valid (normalized 0-1 scale)
        // Very low threshold to catch sustained guitar notes during decay
        // The confidence threshold in ViewModel provides additional filtering
        private const val MIN_RMS_THRESHOLD = 0.003f
    }

    // Pre-allocated buffers to avoid GC churn
    private val yinBuffer = FloatArray(bufferSize / 2)
    private val floatBuffer = FloatArray(bufferSize)

    /**
     * Detect pitch from audio samples.
     *
     * @param audioBuffer Raw audio samples (16-bit PCM)
     * @return PitchResult containing frequency and confidence
     */
    fun detect(audioBuffer: ShortArray): PitchResult {
        // Convert to normalized float array using pre-allocated buffer
        val size = minOf(audioBuffer.size, floatBuffer.size)
        for (i in 0 until size) {
            floatBuffer[i] = audioBuffer[i].toFloat() / Short.MAX_VALUE
        }

        return detectFromFloat(floatBuffer, size)
    }

    /**
     * Detect pitch from normalized float samples.
     */
    private fun detectFromFloat(buffer: FloatArray, size: Int): PitchResult {
        // Check if signal is loud enough to analyze
        // RMS (root mean square) amplitude check - reject very quiet signals
        val rms = calculateRms(buffer, size)
        if (rms < MIN_RMS_THRESHOLD) {
            // No pitch, but pass through the RMS so the pipeline can still update
            // its noise-floor / attack-detection state for this frame.
            return PitchResult(
                frequency = -1f,
                probability = 0f,
                isPitched = false,
                rmsAmplitude = rms
            )
        }

        // Step 1: Difference function
        computeDifference(buffer)

        // Step 2: Cumulative mean normalized difference
        cumulativeMeanNormalizedDifference()

        // Step 3: Absolute threshold - find the first CMND dip below threshold
        val tauEstimate = absoluteThreshold()

        if (tauEstimate == -1) {
            return PitchResult(
                frequency = -1f,
                probability = 0f,
                isPitched = false,
                rmsAmplitude = rms
            )
        }

        // Step 4: Parabolic interpolation for better accuracy
        val refinedTau = parabolicInterpolation(tauEstimate)

        // Convert lag to frequency
        val frequency = sampleRate / refinedTau

        // Check if frequency is in valid range
        if (frequency < AudioConfig.MIN_FREQUENCY || frequency > AudioConfig.MAX_FREQUENCY) {
            return PitchResult(
                frequency = -1f,
                probability = 0f,
                isPitched = false,
                rmsAmplitude = rms
            )
        }

        // Probability is inverse of the YIN value at the detected lag
        val probability = 1f - yinBuffer[tauEstimate]

        return PitchResult(
            frequency = frequency,
            probability = probability,
            isPitched = probability > (1f - cmndThreshold),
            rmsAmplitude = rms
        )
    }

    /**
     * Step 1: Compute the difference function.
     * This measures how similar the signal is to a delayed version of itself.
     *
     * Starts from tau=1 (tau=0 is always 0 by definition and not useful).
     */
    private fun computeDifference(buffer: FloatArray) {
        val halfSize = yinBuffer.size

        // tau=0 is always 0, set directly
        yinBuffer[0] = 0f

        // Start from tau=1 to skip unnecessary computation
        for (tau in 1 until halfSize) {
            var sum = 0f
            for (i in 0 until halfSize) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            yinBuffer[tau] = sum
        }
    }

    /**
     * Step 2: Cumulative mean normalized difference function.
     * This normalizes the difference function to make threshold selection easier.
     */
    private fun cumulativeMeanNormalizedDifference() {
        yinBuffer[0] = 1f
        var runningSum = 0f

        for (tau in 1 until yinBuffer.size) {
            runningSum += yinBuffer[tau]
            // Guard against division by zero (can happen with silent/weird buffers)
            if (runningSum == 0f) {
                yinBuffer[tau] = 1f
            } else {
                yinBuffer[tau] = yinBuffer[tau] * tau / runningSum
            }
        }
    }

    /**
     * Step 3: Find the first CMND value below the threshold.
     * We look for the first dip that goes below threshold, then find the minimum in that dip.
     */
    private fun absoluteThreshold(): Int {
        // Bound the search range by the configured frequency limits.
        // minTau corresponds to the highest detectable frequency (smallest lag),
        // maxTau to the lowest (largest lag, capped by buffer length).
        val minTau = (sampleRate / AudioConfig.MAX_FREQUENCY).toInt()
        val maxTau = (sampleRate / AudioConfig.MIN_FREQUENCY).toInt().coerceAtMost(yinBuffer.size - 1)

        var tau = minTau

        // Find first CMND value below threshold
        while (tau < maxTau) {
            if (yinBuffer[tau] < cmndThreshold) {
                // Found a dip below threshold, now find local minimum
                while (tau + 1 < maxTau && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++
                }
                return tau
            }
            tau++
        }

        return -1 // No pitch found
    }

    /**
     * Step 4: Parabolic interpolation for sub-sample accuracy.
     * Fits a parabola to the detected minimum and its neighbors.
     */
    private fun parabolicInterpolation(tauEstimate: Int): Float {
        if (tauEstimate <= 0 || tauEstimate >= yinBuffer.size - 1) {
            return tauEstimate.toFloat()
        }

        val s0 = yinBuffer[tauEstimate - 1]
        val s1 = yinBuffer[tauEstimate]
        val s2 = yinBuffer[tauEstimate + 1]

        // Parabolic fit
        val denominator = 2f * (2f * s1 - s2 - s0)

        return if (abs(denominator) < 1e-9f) {
            tauEstimate.toFloat()
        } else {
            tauEstimate + (s2 - s0) / denominator
        }
    }

    /**
     * Calculate RMS (root mean square) amplitude of the signal.
     * Used to detect if the signal is loud enough to analyze.
     */
    private fun calculateRms(buffer: FloatArray, size: Int): Float {
        var sum = 0f
        val samplesToCheck = minOf(size, bufferSize)
        for (i in 0 until samplesToCheck) {
            sum += buffer[i] * buffer[i]
        }
        return kotlin.math.sqrt(sum / samplesToCheck)
    }
}
