package com.stereodsp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.stereodsp.MainActivity
import com.stereodsp.R
import com.stereodsp.data.SettingsStore
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings

/** Foreground service (tipe mediaProjection) yang menjaga [CaptureEngine] hidup di latar belakang. */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            CaptureEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mode Tangkap", NotificationManager.IMPORTANCE_LOW),
        )
        // startForeground (tipe mediaProjection) HARUS dipanggil sebelum getMediaProjection.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mp = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (_: Throwable) {
            null
        }
        val store = SettingsStore(this)
        CaptureEngine.setSettings(
            store.loadCurrent(Channel.LEFT) ?: ChannelSettings(),
            store.loadCurrent(Channel.RIGHT) ?: ChannelSettings(),
            store.isMuted(Channel.LEFT),
            store.isMuted(Channel.RIGHT),
        )
        if (mp == null || !CaptureEngine.start(this, mp)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Token MediaProjection hanya berlaku sekali; jangan dihidupkan ulang otomatis.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CaptureEngine.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nj)
            .setContentTitle("NJ Audio: Mode Tangkap aktif")
            .setContentText("Reverb & fase diterapkan ke suara aplikasi lain")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_nj), "Matikan", stop,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.stereodsp.action.STOP_CAPTURE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture_mode"
        private const val NOTIF_ID = 2
    }
}
