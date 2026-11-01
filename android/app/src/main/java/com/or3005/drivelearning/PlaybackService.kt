package com.or3005.drivelearning

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.or3005.drivelearning.ui.MainActivity

/**
 * Keeps the lesson alive with the screen off, and keeps microphone access for voice answers.
 *
 * Android only lets an app use the microphone in the background if a microphone foreground
 * service was started while the app was on screen. So the phone screen starts this service when
 * it opens, and the lesson in the car can then listen for answers.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY // already in the foreground; don't lose the microphone type
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val canRecord = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_learn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val media = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        val mic = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val withMic = canRecord && runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("מוכן לנסיעה, אפשר לענות בקול"), media or mic)
        }.isSuccess
        microphoneReady = withMic
        if (!withMic) {
            val ok = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("שיעור פעיל"), media)
            }.isSuccess
            if (!ok) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        microphoneReady = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "lesson"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        /** True when the service holds microphone access for answering by voice in the background. */
        @Volatile
        var microphoneReady = false
            private set
    }
}
