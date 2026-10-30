package com.or3005.drivelearning.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.or3005.drivelearning.engine.Runs
import com.or3005.drivelearning.engine.Sentences
import java.util.Locale

/**
 * Hebrew text-to-speech, sentence by sentence, so the player can resume or repeat from a sentence.
 * Optionally reads English terms with an English voice.
 */
class Speaker(context: Context, private val onReady: (hebrewAvailable: Boolean) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private var generation = 0
    private var ready = false

    var hebrewAvailable = false
        private set
    private var englishAvailable = false

    /** Read English terms with an English voice. Off by default: the Hebrew voice usually copes. */
    var englishVoiceForTerms = false
    var rate = 1.0f

    private val tts: TextToSpeech = TextToSpeech(
        context.applicationContext,
        { status -> main.post { onInit(status) } },
        GOOGLE_TTS,
    )

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onReady(false)
            return
        }
        ready = true
        hebrewAvailable = tts.isLanguageAvailable(HEBREW) >= TextToSpeech.LANG_AVAILABLE
        englishAvailable = tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        if (hebrewAvailable) tts.setLanguage(HEBREW)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { fire(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { fire(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { fire(utteranceId) }
            }
        })
        onReady(hebrewAvailable)
    }

    /**
     * Speaks [text] starting at sentence [from]. [onSentence] gets the index of each finished
     * sentence; [onDone] runs after the last one. A later speak() or stop() cancels the callbacks.
     */
    fun speak(text: String, from: Int = 0, onSentence: (Int) -> Unit = {}, onDone: () -> Unit) {
        stop()
        val gen = generation
        val sentences = Sentences.split(text)
        if (!ready || from >= sentences.size) {
            main.post { if (gen == generation) onDone() }
            return
        }
        tts.setSpeechRate(rate)
        for (i in from.coerceAtLeast(0) until sentences.size) {
            val pieces = if (englishVoiceForTerms && englishAvailable && hebrewAvailable) {
                Runs.split(sentences[i])
            } else {
                listOf(com.or3005.drivelearning.engine.Run(sentences[i], latin = false))
            }
            pieces.forEachIndexed { j, piece ->
                if (englishVoiceForTerms) tts.setLanguage(if (piece.latin) Locale.US else HEBREW)
                val id = "g$gen-s$i-p$j"
                if (j == pieces.lastIndex) {
                    callbacks[id] = {
                        if (gen == generation) {
                            onSentence(i)
                            if (i == sentences.lastIndex) onDone()
                        }
                    }
                }
                tts.speak(piece.text, TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    fun stop() {
        generation++
        callbacks.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun fire(utteranceId: String?) {
        val id = utteranceId ?: return
        callbacks.remove(id)?.invoke()
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        val HEBREW: Locale = Locale.forLanguageTag("he-IL")
    }
}
