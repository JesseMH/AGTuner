package com.agtuner.tuning

/**
 * Represents the current tuning state relative to the target note.
 */
sealed class TuningState {
    /**
     * Note is flat (below target frequency).
     * @param cents How many cents below target (negative value)
     */
    data class TooLow(val cents: Float) : TuningState()

    /**
     * Note is sharp (above target frequency).
     * @param cents How many cents above target (positive value)
     */
    data class TooHigh(val cents: Float) : TuningState()

    /**
     * Note is in tune (within acceptable threshold).
     * @param cents Deviation in cents (small value, positive or negative)
     */
    data class InTune(val cents: Float) : TuningState()

    /**
     * No pitched sound detected.
     */
    data object NoSignal : TuningState()
}
