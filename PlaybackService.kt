package com.stereodsp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.stereodsp.MainActivity
import com.stereodsp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Menjaga pemutar internal tetap berjalan di latar belakang: foreground service dengan
 * notifikasi kontrol (Jeda/Putar, Stop), MediaSession (layar kunci / headset), audio focus
 * dan wake lock CPU. Berhenti sendiri saat lagu selesai atau dihentikan.
 */
class PlaybackService : Service() {

    private data class Snap(val name: String?, val playing: Boolean, val active: Boolean)

    private lateinit var engine: AudioEngine
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocus = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = PlayerHolder.get(this)
        audioManager = getSystemService(AudioManager::class.java)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pemutar musik", NotificationManager.IMPORTANCE_LOW),
        )

        session = MediaSession(this, "NJ Audio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = engine.setPaused(false)
                override fun onPause() = engine.setPaused(true)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }

        val initial = engine.state.value
        val notification = buildNotification(Snap(initial.fileName, initial.isPlaying, initial.active))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stereodsp:playback")
            .also { it.acquire(6 * 60 * 60 * 1000L) } // batas aman 6 jam

        scope.launch {
            engine.state
                .map { Snap(it.fileName, it.isPlaying, it.active) }
                .distinctUntilChanged()
                .collect { onSnap(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> engine.setPaused(!engine.isPaused)
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
        }
        if (!engine.state.value.active) shutdown()
        return START_NOT_STICKY
    }

    private fun onSnap(s: Snap) {
        if (!s.active) {
            shutdown()
            return
        }
        if (s.playing && !hasFocus) requestFocus()
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, buildNotification(s))
        updateSession(s)
    }

    private fun stopPlayback() {
        engine.stop()
        shutdown()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        abandonFocus()
        try { session.isActive = false; session.release() } catch (_: Throwable) {}
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        // Bila service dimatikan sistem saat masih memutar, hentikan agar tidak ada thread yatim.
        if (engine.state.value.active) engine.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnFocus = false
                engine.setPaused(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                resumeOnFocus = engine.state.value.isPlaying
                engine.setPaused(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.setVolume(0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                engine.setVolume(1f)
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    engine.setPaused(false)
                }
            }
        }
    }

    private fun requestFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) engine.setPaused(true)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasFocus = false
    }

    // ---------------------------------------------------------------- MediaSession & notifikasi

    private fun updateSession(s: Snap) {
        try {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.name ?: "NJ Audio")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, engine.state.value.durationMs)
                    .build(),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                    )
                    .setState(
                        if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        engine.state.value.positionMs,
                        if (s.playing) 1f else 0f,
                    )
                    .build(),
            )
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(s: Snap): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 10, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleIcon = if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nj)
            .setContentTitle(s.name ?: "NJ Audio")
            .setContentText(if (s.playing) "Memutar" else "Dijeda")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, toggleIcon),
                    if (s.playing) "Jeda" else "Putar",
                    toggle,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stop,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    companion object {
        const val ACTION_TOGGLE = "com.stereodsp.action.TOGGLE_PLAYBACK"
        const val ACTION_STOP = "com.stereodsp.action.STOP_PLAYBACK"
        private const val CHANNEL_ID = "player"
        private const val NOTIF_ID = 2

        /** Mulai service pemutaran (panggil dari Activity yang sedang tampil). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
