package com.or3005.drivelearning.audio

import android.media.AudioManager
import android.media.ToneGenerator

/** A short beep before listening, so the driver knows when to speak without looking. */
object Beeper {
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    fun listen() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }
}
