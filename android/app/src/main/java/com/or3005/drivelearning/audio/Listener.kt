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
     * [onResult] gets the recognizer's candidate transcripts, empty on silence or error.
     */
    fun listen(
        long: Boolean,
        audio: ParcelFileDescriptor? = null,
        onEnd: () -> Unit = {},
        onResult: (List<String>) -> Unit,
    ) {
        cancel()
        val id = ++session
        this.onEnd = onEnd
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
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
                onResult(list)
            }

            override fun onError(error: Int) {
                if (id != session) return
                finish()
                onResult(emptyList())
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent)
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
