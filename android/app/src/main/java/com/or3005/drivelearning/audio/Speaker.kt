package com.or3005.drivelearning.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.or3005.drivelearning.engine.Pronunciation
import com.or3005.drivelearning.engine.Runs
import com.or3005.drivelearning.engine.Sentences
import java.io.File
import java.util.Locale

/**
 * Hebrew text-to-speech, sentence by sentence, so the player can resume or repeat from a sentence.
 * Optionally reads English terms with an English voice. A sentence that has a recording (a course
 * recorded with a good voice, see [AudioLibrary]) is played from its file instead.
 */
class Speaker(context: Context, private val onReady: (hebrewAvailable: Boolean) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private var generation = 0
    private var ready = false

    var hebrewAvailable = false
        private set
    private var englishAvailable = false

    /** Read English terms with an English voice, so Body sounds like body. Hebrew voice only if off. */
    var englishVoiceForTerms = true
    var rate = 1.0f
    var pronunciation: Pronunciation = Pronunciation.NONE

    /** Finds the recording of a sentence, or null to use the phone's voice. */
    var recorded: ((String) -> File?)? = null

    private var player: MediaPlayer? = null
    /** Files that failed to play; their sentences use the phone's voice. */
    private val broken = HashSet<String>()

    private fun recordingFor(sentence: String): File? =
        recorded?.invoke(sentence)?.takeIf { it.path !in broken }

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
        if (from >= sentences.size || (!ready && recorded == null)) {
            main.post { if (gen == generation) onDone() }
            return
        }
        playFrom(gen, sentences, from.coerceAtLeast(0), onSentence, onDone)
    }

    /** Plays sentences from [start]: a recorded one from its file, a run of others with the phone's voice. */
    private fun playFrom(gen: Int, sentences: List<String>, start: Int, onSentence: (Int) -> Unit, onDone: () -> Unit) {
        if (gen != generation) return
        if (start >= sentences.size) {
            onDone()
            return
        }
        val file = recordingFor(sentences[start])
        if (file != null) {
            playFile(gen, file,
                onDone = { onSentence(start); playFrom(gen, sentences, start + 1, onSentence, onDone) },
                onFail = { broken += file.path; playFrom(gen, sentences, start, onSentence, onDone) },
            )
            return
        }
        var end = start
        while (end < sentences.size && recordingFor(sentences[end]) == null) end++
        if (!ready) {
            // No phone voice: skip what isn't recorded.
            for (i in start until end) onSentence(i)
            main.post { playFrom(gen, sentences, end, onSentence, onDone) }
            return
        }
        tts.setSpeechRate(rate)
        for (i in start until end) {
            val pieces = if (englishVoiceForTerms && englishAvailable && hebrewAvailable) {
                Runs.split(sentences[i]).map { run ->
                    if (run.latin) run.copy(text = pronunciation.applyEnglishRun(run.text))
                    else run.copy(text = pronunciation.applyHebrewRun(run.text))
                }
            } else {
                listOf(com.or3005.drivelearning.engine.Run(pronunciation.apply(sentences[i]), latin = false))
            }
            pieces.forEachIndexed { j, piece ->
                if (englishVoiceForTerms) tts.setLanguage(if (piece.latin) Locale.US else HEBREW)
                val id = "g$gen-s$i-p$j"
                if (j == pieces.lastIndex) {
                    callbacks[id] = {
                        if (gen == generation) {
                            onSentence(i)
                            if (i == end - 1) playFrom(gen, sentences, end, onSentence, onDone)
                        }
                    }
                }
                tts.speak(piece.text, TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun playFile(gen: Int, file: File, onDone: () -> Unit, onFail: () -> Unit) {
        val mp = MediaPlayer()
        fun finish(ok: Boolean) {
            mp.release()
            if (player === mp) player = null
            main.post { if (gen == generation) if (ok) onDone() else onFail() }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.path)
            mp.prepare()
            mp.setOnCompletionListener { finish(ok = true) }
            mp.setOnErrorListener { _, _, _ -> finish(ok = false); true }
            player = mp
            if (rate != 1.0f) mp.playbackParams = mp.playbackParams.setSpeed(rate)
            mp.start()
        } catch (e: Exception) {
            finish(ok = false)
        }
    }

    fun stop() {
        generation++
        callbacks.clear()
        if (ready) tts.stop()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
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
