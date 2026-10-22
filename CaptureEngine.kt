package com.stereodsp.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.stereodsp.dsp.ChannelProcessor
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mode Tangkap: menangkap suara aplikasi lain (YouTube dll.) lewat AudioPlaybackCapture
 * (MediaProjection), memprosesnya dengan [ChannelProcessor] yang SAMA dengan pemutar internal
 * (EQ penuh, HPF/LPF, polaritas/balik fase, reverb dengan mix & ukuran), lalu memutarnya lewat AudioTrack.
 *
 * Tidak memerlukan izin DUMP/ADB dan tidak perlu mengetahui ID sesi audio aplikasi lain.
 * Batasan Android: suara asli aplikasi sumber tetap ikut terdengar (tidak ada API untuk membisukannya
 * tanpa ID sesi), dan aplikasi yang melarang penangkapan (DRM / allowAudioPlaybackCapture=false) tidak terdengar.
 */
object CaptureEngine {
    private const val RATE = 48000

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var left = ChannelSettings()
    @Volatile private var right = ChannelSettings()
    @Volatile private var muteL = false
    @Volatile private var muteR = false
    @Volatile private var procL: ChannelProcessor? = null
    @Volatile private var procR: ChannelProcessor? = null
    @Volatile private var stopRequested = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    fun setSettings(l: ChannelSettings, r: ChannelSettings, mL: Boolean, mR: Boolean) {
        left = l; right = r; muteL = mL; muteR = mR
        procL?.update(l, mL)
        procR?.update(r, mR)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO diminta oleh UI sebelum service dijalankan
    @Synchronized
    fun start(ctx: Context, mp: MediaProjection): Boolean {
        stop()
        _error.value = null
        if (android.os.Build.VERSION.SDK_INT < 29) {
            _error.value = "Mode Tangkap butuh Android 10 (API 29) atau lebih baru"
            try { mp.stop() } catch (_: Throwable) {}
            return false
        }
        // Android 14+ mewajibkan callback terdaftar sebelum penangkapan dimulai.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val record: AudioRecord
        val track: AudioTrack
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid()) // jangan menangkap keluaran sendiri (umpan balik)
                .build()
            val inFmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(inFmt)
                .setBufferSizeInBytes(minIn.coerceAtLeast(4096) * 4)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord gagal dibuat")
            }
            val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(minOut.coerceAtLeast(4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            _error.value = "Mode Tangkap gagal: ${e.message ?: e.javaClass.simpleName}"
            try { mp.stop() } catch (_: Throwable) {}
            projection = null
            return false
        }

        SystemEffect.exclude(track.audioSessionId)
        val pl = ChannelProcessor(RATE.toDouble()).also { it.update(left, muteL) }
        val pr = ChannelProcessor(RATE.toDouble(), 23).also { it.update(right, muteR) }
        procL = pl
        procR = pr
        stopRequested = false
        _active.value = true
        worker = Thread({ loop(record, track, pl, pr) }, "dsp-capture").also { it.start() }
        return true
    }

    private fun loop(record: AudioRecord, track: AudioTrack, pl: ChannelProcessor, pr: ChannelProcessor) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val frames = 512
        val pcm = ShortArray(frames * 2)
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        try {
            record.startRecording()
            track.play()
            while (!stopRequested) {
                val n = record.read(pcm, 0, pcm.size) // jumlah short (L,R berselang-seling)
                if (n < 0) {
                    _error.value = "Penangkapan audio berhenti (kode $n)"
                    break
                }
                val fr = n / 2
                if (fr == 0) continue
                for (i in 0 until fr) {
                    l[i] = pcm[2 * i] / 32768f
                    r[i] = pcm[2 * i + 1] / 32768f
                }
                pl.process(l, fr)
                pr.process(r, fr)
                for (i in 0 until fr) {
                    pcm[2 * i] = (l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    pcm[2 * i + 1] = (r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                track.write(pcm, 0, fr * 2)
            }
        } catch (e: Throwable) {
            _error.value = "Mode Tangkap berhenti: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
            procL = null
            procR = null
            _active.value = false
        }
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        val w = worker
        worker = null
        if (w != null && w !== Thread.currentThread()) {
            try { w.join(800) } catch (_: InterruptedException) {}
        }
        projection?.let { try { it.stop() } catch (_: Throwable) {} }
        projection = null
        _active.value = false
    }
}
