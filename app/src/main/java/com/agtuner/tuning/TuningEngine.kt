package com.agtuner.tuning

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log2

/**
 * Engine for evaluating pitch accuracy against target notes.
 */
@Singleton
class TuningEngine @Inject constructor() {

    companion object {
        // Default threshold: within +/- 10 cents is considered "in tune"
        // Guitar strings naturally fluctuate, so 10 cents is more realistic
        const val DEFAULT_IN_TUNE_THRESHOLD_CENTS = 10f

        // Cents per octave
        private const val CENTS_PER_OCTAVE = 1200f
    }

    private var inTuneThresholdCents: Float = DEFAULT_IN_TUNE_THRESHOLD_CENTS

    /**
     * Set the threshold for considering a note "in tune".
     * @param cents Threshold in cents (e.g., 5 cents means +/- 5 cents)
     */
    fun setInTuneThreshold(cents: Float) {
        if (cents.isFinite()) {
            inTuneThresholdCents = abs(cents)
        }
    }

    /**
     * Evaluate how close the detected frequency is to the target.
     *
     * @param detectedFrequency The frequency detected from audio (Hz)
     * @param targetFrequency The target frequency to tune to (Hz)
     * @return TuningState — TooLow / TooHigh / InTune for valid input, or NoSignal
     *         when either frequency is non-positive
     */
    fun evaluatePitch(detectedFrequency: Float, targetFrequency: Float): TuningState {
        if (detectedFrequency <= 0 || targetFrequency <= 0) {
            return TuningState.NoSignal
        }

        val cents = calculateCents(detectedFrequency, targetFrequency)

        return when {
            cents < -inTuneThresholdCents -> TuningState.TooLow(cents)
            cents > inTuneThresholdCents -> TuningState.TooHigh(cents)
            else -> TuningState.InTune(cents)
        }
    }

    /**
     * Calculate the difference in cents between two frequencies.
     *
     * Cents are a logarithmic unit of measure for musical intervals.
     * 100 cents = 1 semitone
     * 1200 cents = 1 octave
     *
     * Caller must pass positive finite frequencies. Invalid input produces NaN/Inf
     * (which propagates honestly) rather than a silent 0f "in tune" reading.
     *
     * @param detected The detected frequency
     * @param target The target frequency
     * @return Difference in cents (positive = sharp, negative = flat)
     */
    fun calculateCents(detected: Float, target: Float): Float {
        return CENTS_PER_OCTAVE * log2(detected / target)
    }

    /**
     * Find which string the detected frequency is closest to.
     *
     * @param detectedFrequency The detected frequency
     * @param strings List of string configurations
     * @param maxCentsDistance If the closest match exceeds this, return -1.
     *        Default Float.MAX_VALUE means "always return the closest match". Pass a finite
     *        cap (e.g., 200f) to reject implausible matches like octave errors.
     * @return Index of the closest string, or -1 if frequency invalid, list empty, or beyond cap
     */
    fun findClosestString(
        detectedFrequency: Float,
        strings: List<StringNote>,
        maxCentsDistance: Float = Float.MAX_VALUE
    ): Int {
        if (detectedFrequency <= 0 || strings.isEmpty()) return -1

        var closestIndex = 0
        var smallestCentsDiff = Float.MAX_VALUE

        strings.forEachIndexed { index, string ->
            val centsDiff = abs(calculateCents(detectedFrequency, string.frequency))
            if (centsDiff < smallestCentsDiff) {
                smallestCentsDiff = centsDiff
                closestIndex = index
            }
        }

        return if (smallestCentsDiff <= maxCentsDistance) closestIndex else -1
    }
}
