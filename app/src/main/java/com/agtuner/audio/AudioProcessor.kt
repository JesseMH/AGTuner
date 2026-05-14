package com.agtuner.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles audio capture from the microphone and streams pitch detection results.
 *
 * Uses a non-blocking flow with DROP_OLDEST overflow policy to ensure the audio
 * capture loop never stalls waiting for slow collectors.
 *
 * Lifecycle: callers cancel the coroutine that called [startListening] to stop
 * capture. The AudioRecord is owned by that coroutine and released in a finally
 * block, so there is no cross-thread state to coordinate.
 */
@Singleton
class AudioProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pitchDetector = YinPitchDetector()

    // Use DROP_OLDEST to never block the audio capture loop
    // If collectors are slow, we skip old frames rather than lag
    private val _pitchFlow = MutableSharedFlow<PitchResult>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pitchFlow: SharedFlow<PitchResult> = _pitchFlow.asSharedFlow()

    private val minBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )
    }

    companion object {
        private const val TAG = "AudioProcessor"
    }

    /**
     * Check if microphone permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Capture audio and emit pitch results until the calling coroutine is cancelled.
     *
     * The AudioRecord is created, started, and released within this call's coroutine
     * scope; cancelling the caller's job exits the read loop within one hop (~23 ms
     * at the default config) and releases the AudioRecord in the finally block.
     */
    suspend fun startListening() {
        // Inline check (rather than via hasPermission()) so lint's PermissionDetector
        // can see the guard at the same call site as the @RequiresPermission helpers
        // below — it does not perform inter-procedural analysis through wrappers.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        withContext(Dispatchers.IO) {
            val record = try {
                initializeAudioRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord", e)
                return@withContext
            }
            try {
                record.startRecording()
                captureLoop(record)
            } finally {
                try {
                    record.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord", e)
                }
                record.release()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initializeAudioRecord(): AudioRecord {
        val bufferSize = maxOf(
            minBufferSize * AudioConfig.BUFFER_SIZE_MULTIPLIER,
            AudioConfig.AUDIO_RECORD_MIN_BUFFER_SAMPLES
        )

        val audioSource = chooseAudioSource(bufferSize)

        val record = AudioRecord(
            audioSource,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        return record
    }

    /**
     * Try UNPROCESSED source first (API 24+) for cleaner audio without AGC/noise
     * suppression. Falls back to MIC if UNPROCESSED is not available on the device.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun chooseAudioSource(bufferSize: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return MediaRecorder.AudioSource.MIC
        return try {
            val testRecord = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
            )
            val initialized = testRecord.state == AudioRecord.STATE_INITIALIZED
            testRecord.release()
            if (initialized) {
                Log.d(TAG, "Using UNPROCESSED audio source")
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                Log.d(TAG, "UNPROCESSED not available, using MIC")
                MediaRecorder.AudioSource.MIC
            }
        } catch (e: Exception) {
            Log.d(TAG, "UNPROCESSED failed, using MIC", e)
            MediaRecorder.AudioSource.MIC
        }
    }

    /**
     * Overlapped capture loop for smoother pitch updates.
     *
     * Uses a rolling window that shifts by hopSize samples each iteration:
     * - With 50% overlap: reads 1024 new samples, shifts window, runs YIN on 2048
     * - This doubles the update rate (~43 fps vs ~21.5 fps) for smoother gauge movement
     * - Reuses arrays to avoid per-frame allocations
     * - Warmup phase fills the window before first detection
     */
    private suspend fun captureLoop(record: AudioRecord) {
        val windowSize = AudioConfig.YIN_BUFFER_SIZE
        val hopSize = AudioConfig.HOP_SIZE

        // Rolling analysis window (reused)
        val window = ShortArray(windowSize)

        // Hop read buffer (reused)
        val hopBuffer = ShortArray(hopSize)

        // How many valid samples are currently in window (for startup warmup)
        var filled = 0

        while (coroutineContext.isActive) {
            val read = record.read(hopBuffer, 0, hopSize)
            if (read <= 0) {
                if (read < 0) {
                    Log.e(TAG, "Error reading audio: $read")
                    break
                }
                continue
            }

            // If we read less than hopSize, only shift by the amount we actually got
            val shift = read.coerceAtMost(windowSize)

            if (filled >= windowSize) {
                // Window is full: shift left and append new samples
                System.arraycopy(window, shift, window, 0, windowSize - shift)
                System.arraycopy(hopBuffer, 0, window, windowSize - shift, shift)
            } else {
                // Warmup phase: append until full, then start shifting
                val space = windowSize - filled
                if (shift <= space) {
                    System.arraycopy(hopBuffer, 0, window, filled, shift)
                    filled += shift
                    if (filled < windowSize) {
                        // Not enough samples yet for a full window
                        continue
                    }
                } else {
                    // Fill remaining space
                    System.arraycopy(hopBuffer, 0, window, filled, space)
                    filled = windowSize

                    // Extra samples beyond filling: shift and append remainder
                    val remaining = shift - space
                    if (remaining > 0) {
                        System.arraycopy(window, remaining, window, 0, windowSize - remaining)
                        System.arraycopy(hopBuffer, space, window, windowSize - remaining, remaining)
                    }
                }
            }

            // Detect pitch on the rolling window
            val result = pitchDetector.detect(window)

            // Never block the audio loop - if buffer is full, oldest is dropped
            _pitchFlow.tryEmit(result)
        }
    }
}
