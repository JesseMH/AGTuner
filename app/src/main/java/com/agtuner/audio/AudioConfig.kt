package com.agtuner.audio

import android.media.AudioFormat

/**
 * Configuration constants for audio capture and pitch detection.
 */
object AudioConfig {
    // Audio recording parameters
    const val SAMPLE_RATE = 44100
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BUFFER_SIZE_MULTIPLIER = 2

    // YIN algorithm parameters
    // Buffer size determines analysis window: 2048 samples at 44100 Hz = ~46.4 ms.
    // Frame rate depends on OVERLAP_FACTOR (see below).
    const val YIN_BUFFER_SIZE = 2048

    // AudioRecord internal buffer floor (in samples). The OS minimum is often smaller
    // than YIN_BUFFER_SIZE; we require room for two analysis windows so a slow
    // consumer can't cause the driver buffer to underflow between read() calls.
    const val AUDIO_RECORD_MIN_BUFFER_SAMPLES = YIN_BUFFER_SIZE * 2

    // YIN cumulative mean normalized difference threshold.
    // Lower = stricter (fewer false positives, may miss weak signals)
    // Higher = more lenient (catches weaker signals, more false positives)
    // Maps to probability as: probability = 1 - CMND_value
    // With threshold 0.20, we accept pitches where CMND < 0.20, i.e. probability > 0.80
    const val YIN_CMND_THRESHOLD = 0.20f

    // Frequency range for pitch detection
    // Supports standard guitar (E2=82Hz to E4=330Hz) plus margin for:
    // - Flat/sharp readings
    // - Custom tunings with higher strings (e.g., mandolin, ukulele)
    // - Harmonics that may push perceived pitch upward
    const val MIN_FREQUENCY = 60f     // Below E2 (82Hz) to catch flat notes
    const val MAX_FREQUENCY = 600f    // Headroom for custom tunings and harmonics

    // Overlap factor for rolling window capture.
    // 0.0 = no overlap (each frame is entirely new samples) -> ~21.5 fps
    // 0.5 = 50% overlap (half new samples each frame) -> ~43 fps
    // Higher overlap = smoother updates but more CPU usage
    const val OVERLAP_FACTOR = 0.5f

    // Number of new samples read per capture iteration. The capture loop reuses
    // the prior YIN_BUFFER_SIZE - HOP_SIZE samples and appends HOP_SIZE new ones.
    // maxOf(1, ...) guards against pathological OVERLAP_FACTOR values (>= 1.0).
    val HOP_SIZE: Int = maxOf(1, (YIN_BUFFER_SIZE * (1f - OVERLAP_FACTOR)).toInt())
}
