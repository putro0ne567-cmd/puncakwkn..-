package com.stereodsp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import com.stereodsp.MainActivity
import com.stereodsp.R
import com.stereodsp.data.SettingsStore
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings

/** Menjaga efek global tetap hidup saat pengguna berpindah ke YouTube / aplikasi lain. */
class SystemEffectService : Service() {

    @Volatile private var polling = false
    private var poller: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Pemutar yang mengumumkan sesi audionya (Spotify, VLC, YouTube Music, dll.) langsung
     * didaftarkan tanpa perlu izin DUMP lewat ADB.
     */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> SystemEffect.registerSession(id)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> SystemEffect.unregisterSession(id)
            }
        }
    }

    /** Memeriksa sesi audio aplikasi lain tiap ~2 detik dan memasang efek ke sesi baru. */
    private fun startPolling() {
        if (polling) return
        polling = true
        poller = Thread({
            while (polling) {
                try {
                    SystemEffect.sync(SessionFinder.find(applicationContext))
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "dsp-session-poller").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Efek audio", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        if (intent?.getBooleanExtra(EXTRA_MUTER, false) == true) {
            // Mode Tangkap: hanya membisukan suara asli aplikasi lain (efek diproses oleh CaptureEngine).
            SystemEffect.start(ChannelSettings(), ChannelSettings(), true, true)
            startPolling()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            store.setSystemEnabled(false)
            SystemEffect.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!store.isSystemEnabled() && intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = SystemEffect.start(
            store.loadCurrent(Channel.LEFT) ?: ChannelSettings(),
            store.loadCurrent(Channel.RIGHT) ?: ChannelSettings(),
            store.isMuted(Channel.LEFT),
            store.isMuted(Channel.RIGHT),
        )
        if (!ok) {
            store.setSystemEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        polling = false
        poller?.interrupt()
        try { unregisterReceiver(sessionReceiver) } catch (_: Throwable) {}
        SystemEffect.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SystemEffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nj)
            .setContentTitle("NJ Audio aktif")
            .setContentText("Efek berlaku untuk aplikasi lain (YouTube dll.)")
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
        const val ACTION_STOP = "com.stereodsp.action.STOP_SYSTEM_EFFECT"
        const val EXTRA_MUTER = "muter"
        private const val CHANNEL_ID = "system_effect"
        private const val NOTIF_ID = 1
    }
}
