package com.or3005.drivelearning.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Hebrew speech recognition. Can read audio from the car microphone through a pipe (Android 13+). */
class Listener(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var onEnd: (() -> Unit)? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens once. [long] allows a longer answer with pauses (explain-back questions).
     * [minimumMillis] keeps the recognizer open at least that long, so there is time to think.
     * [accept] is checked on every partial transcript: when it returns true, listening stops
     * right away and that transcript is the result (a short answer like "שתיים" needs no waiting).
     * [onResult] gets the recognizer's candidate transcripts, and the error code if it failed
     * (null on success). Silence comes back as an empty list with an error code. If the
     * recognizer fails after hearing something, the last partial transcript is returned instead.
     */
    fun listen(
        long: Boolean,
        minimumMillis: Long? = null,
        audio: ParcelFileDescriptor? = null,
        accept: (String) -> Boolean = { false },
        onEnd: () -> Unit = {},
        onResult: (results: List<String>, error: Int?) -> Unit,
    ) {
        cancel()
        val id = ++session
        this.onEnd = onEnd
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var lastPartial = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            minimumMillis?.let { putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, it) }
            if (long) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
            }
            if (audio != null && Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            }
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (id != session) return
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                finish()
                onResult(list, null)
            }

            override fun onError(error: Int) {
                if (id != session) return
                finish()
                if (lastPartial.isNotBlank()) onResult(listOf(lastPartial), null)
                else onResult(emptyList(), error)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (id != session) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() } ?: return
                lastPartial = text
                if (accept(text)) {
                    cancel()
                    onResult(listOf(text), null)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent)
    }

    companion object {
        /** Hebrew explanation of a SpeechRecognizer error code, or null when it just means silence. */
        fun describe(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "אין הרשאה למיקרופון (קוד 9)"
            SpeechRecognizer.ERROR_AUDIO -> "המיקרופון לא זמין (קוד 3)"
            SpeechRecognizer.ERROR_CLIENT -> "שגיאה פנימית בזיהוי הדיבור (קוד 5)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "זיהוי הדיבור תפוס (קוד 8)"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "אין חיבור לרשת (קוד $error)"
            SpeechRecognizer.ERROR_SERVER -> "שגיאת שרת בזיהוי הדיבור (קוד 4)"
            else -> "שגיאה בזיהוי הדיבור (קוד $error)"
        }
    }

    fun cancel() {
        session++
        finish()
    }

    private fun finish() {
        val r = recognizer
        recognizer = null
        if (r != null) main.post { runCatching { r.destroy() } }
        onEnd?.invoke()
        onEnd = null
    }
}
