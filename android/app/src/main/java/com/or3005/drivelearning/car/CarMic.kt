package com.or3005.drivelearning.car

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import java.io.IOException

/**
 * Streams the car's microphone into a pipe that the speech recognizer reads (Android 13+,
 * Car App API level 5+). Returns null when not supported, and the phone microphone is used.
 */
class CarMic(private val carContext: CarContext) {

    @Volatile
    private var running = false
    private var focusRequest: AudioFocusRequest? = null

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 33 &&
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5 &&
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun open(): ParcelFileDescriptor? {
        if (!supported) return null
        return try {
            requestFocus()
            val pipe = ParcelFileDescriptor.createPipe()
            @Suppress("MissingPermission")
            val record = CarAudioRecord.create(carContext)
            record.startRecording()
            running = true
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
                        while (running) {
                            val n = record.read(buffer, 0, buffer.size)
                            if (n < 0) break
                            if (n > 0) out.write(buffer, 0, n)
                        }
                    }
                } catch (_: IOException) {
                    // The recognizer closed its end of the pipe.
                } finally {
                    runCatching { record.stopRecording() }
                    abandonFocus()
                }
            }.start()
            pipe[0]
        } catch (e: Exception) {
            running = false
            abandonFocus()
            null
        }
    }

    fun close() {
        running = false
    }

    private fun requestFocus() {
        val audioManager = carContext.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) close() }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        carContext.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}
