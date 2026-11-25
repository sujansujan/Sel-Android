package io.github.selclip.actions

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/**
 * Strictly lazy Android TextToSpeech wrapper.
 * Initializes zero binder connections until the user explicitly taps Speak Aloud.
 */
class LazyTtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    fun speak(text: String) {
        val snippet = text.trim().take(500)
        if (snippet.isEmpty()) return

        if (ready && tts != null) {
            tts?.speak(snippet, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
            Toast.makeText(context, "Speaking…", Toast.LENGTH_SHORT).show()
            return
        }

        pendingText = snippet
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    ready = true
                    pendingText?.takeIf { it.isNotBlank() }?.let {
                        tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "sel_tts")
                    }
                    pendingText = null
                }
            }
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        pendingText = null
    }
}
