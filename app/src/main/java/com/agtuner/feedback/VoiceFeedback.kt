package com.agtuner.feedback

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.agtuner.tuning.TuningState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides text-to-speech voice feedback for blind and visually impaired users.
 *
 * Announces the tuning state with short, clear phrases:
 * - "in tune"
 * - "up" (pitch is too low, tune up)
 * - "down" (pitch is too high, tune down)
 *
 * TTS is lazily initialized on first use to avoid startup overhead when voice
 * feedback is not enabled.
 *
 * Thread-safe: TTS's `onInit` callback fires on a binder thread while
 * `announce`/`shutdown` run on caller threads. All lifecycle state is
 * guarded by `lock` so init/shutdown/queue transitions cannot interleave.
 */
@Singleton
class VoiceFeedback @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isInitializing = false

    // Pending message to speak once TTS finishes initializing
    private var pendingMessage: String? = null

    /**
     * Initialize TTS if not already initialized.
     * Called lazily on first use or when voice is enabled.
     */
    fun ensureInitialized() {
        synchronized(lock) {
            if (tts == null && !isInitializing) {
                isInitializing = true
                tts = TextToSpeech(context, this)
            }
        }
    }

    override fun onInit(status: Int) {
        synchronized(lock) {
            isInitializing = false
            // shutdown() may have nulled `tts` before onInit fired; if so, do
            // nothing — otherwise we'd resurrect isInitialized=true on a
            // torn-down instance.
            val ttsRef = tts ?: return
            if (status != TextToSpeech.SUCCESS) return

            val result = ttsRef.setLanguage(Locale.US)
            isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            // Slightly slower speech rate for clarity
            ttsRef.setSpeechRate(0.9f)

            // Set audio attributes for accessibility usage
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            ttsRef.setAudioAttributes(audioAttributes)

            // Speak any message that arrived during initialization
            val pending = pendingMessage
            pendingMessage = null
            if (pending != null && isInitialized) {
                speakLocked(pending)
            }
        }
    }

    /**
     * Announce the tuning state with a short, clear phrase.
     */
    fun announce(state: TuningState) {
        val message = when (state) {
            is TuningState.InTune -> "in tune"
            is TuningState.TooLow -> "up"
            is TuningState.TooHigh -> "down"
            is TuningState.NoSignal -> return // Don't announce no signal
        }

        synchronized(lock) {
            if (isInitialized) {
                speakLocked(message)
            } else {
                // Queue the message before kicking off init: if onInit fires
                // immediately on another thread, it will see this pending
                // message rather than racing past us.
                pendingMessage = message
                ensureInitialized()
            }
        }
    }

    /**
     * Stop any current speech.
     */
    fun stop() {
        synchronized(lock) {
            tts?.stop()
        }
    }

    /**
     * Release TTS resources. Call when no longer needed.
     */
    fun shutdown() {
        synchronized(lock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            isInitializing = false
            pendingMessage = null
        }
    }

    // Caller must hold `lock`. QUEUE_FLUSH replaces any pending speech so
    // voice feedback stays aligned with audio/haptic feedback.
    private fun speakLocked(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }
}
