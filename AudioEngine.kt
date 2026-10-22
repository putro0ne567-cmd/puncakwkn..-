package com.stereodsp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import com.stereodsp.dsp.ChannelProcessor
import com.stereodsp.dsp.ChannelSettings
import com.stereodsp.dsp.SpectrumAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class PlayerState(
    val fileName: String? = null,
    val isPlaying: Boolean = false,
    /** True selama thread pemutar hidup (termasuk saat dijeda). */
    val active: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    /** Spektrum 0..1 per pita untuk visualizer (panjang [SpectrumAnalyzer.BANDS]). */
    val spectrum: FloatArray = SpectrumAnalyzer.EMPTY,
    val error: String? = null,
)

/**
 * Mendekode file audio (MediaCodec), memproses kanal L dan R secara terpisah
 * dengan [ChannelProcessor], lalu memutar lewat AudioTrack stereo 16-bit.
 *
 * Satu instance dipakai bersama oleh UI dan [PlaybackService] (lihat [PlayerHolder]) sehingga
 * pemutaran tetap berjalan saat aplikasi berada di latar belakang.
 */
class AudioEngine(private val context: Context) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile private var left = ChannelSettings()
    @Volatile private var right = ChannelSettings()
    @Volatile private var muteL = false
    @Volatile private var muteR = false
    @Volatile private var procL: ChannelProcessor? = null
    @Volatile private var procR: ChannelProcessor? = null

    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var seekToMs = -1L
    @Volatile private var volume = 1f

    @Volatile private var lastUri: Uri? = null
    @Volatile private var lastName: String? = null

    val isPaused: Boolean get() = paused

    /** Ada trek yang pernah dimuat (bisa diputar ulang setelah selesai). */
    val hasTrack: Boolean get() = lastUri != null

    fun setSettings(l: ChannelSettings, r: ChannelSettings, mutedL: Boolean, mutedR: Boolean) {
        left = l
        right = r
        muteL = mutedL
        muteR = mutedR
        procL?.update(l, mutedL)
        procR?.update(r, mutedR)
    }

    fun load(uri: Uri, name: String) {
        stop()
        lastUri = uri
        lastName = name
        stopRequested = false
        paused = false
        seekToMs = -1L
        _state.value = PlayerState(fileName = name, active = true)
        worker = Thread({ playback(uri) }, "dsp-audio").also { it.start() }
    }

    /** Putar ulang trek terakhir dari awal (setelah selesai atau dihentikan). */
    fun replay() {
        val u = lastUri ?: return
        load(u, lastName ?: "audio")
    }

    fun togglePause() = setPaused(!paused)

    fun setPaused(on: Boolean) {
        if (worker?.isAlive != true) return
        paused = on
    }

    /** Volume 0..1 (dipakai untuk ducking saat aplikasi lain meminta fokus audio sesaat). */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun seekTo(ms: Long) {
        if (worker?.isAlive == true) seekToMs = ms
    }

    fun stop() {
        stopRequested = true
        worker?.let {
            try {
                it.join(800)
            } catch (_: InterruptedException) {
            }
        }
        worker = null
    }

    fun release() = stop()

    private fun fail(message: String) {
        _state.update { it.copy(isPlaying = false, error = message) }
    }

    private fun playback(uri: Uri) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                fail("File tidak berisi trek audio")
                return
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                0L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = sampleRate
            var pl = ChannelProcessor(rate.toDouble()).also { it.update(left, muteL) }
            var pr = ChannelProcessor(rate.toDouble(), 23).also { it.update(right, muteR) }
            procL = pl
            procR = pr
            var analyzer = SpectrumAnalyzer(rate.toDouble())
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            fun buildTrack(hz: Int): AudioTrack {
                val minBuf = AudioTrack.getMinBufferSize(
                    hz,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                return AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(hz)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    // Buffer lebih besar agar tidak putus-putus saat CPU dibatasi di latar belakang.
                    .setBufferSizeInBytes(minBuf * 6)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
            var cur: AudioTrack = buildTrack(rate)
            SystemEffect.exclude(cur.audioSessionId)
            track = cur
            cur.play()
            _state.update { it.copy(isPlaying = true, durationMs = durationMs, error = null) }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var src = ShortArray(0)
            var fsrc = FloatArray(0)
            var l = FloatArray(0)
            var r = FloatArray(0)
            var pcm = ShortArray(0)
            var wasPaused = false
            var peakL = 0f
            var peakR = 0f
            var appliedVolume = 1f

            while (!stopRequested && !outputDone) {
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1L
                    extractor.seekTo(seek * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    cur.flush()
                    pl.reset()
                    pr.reset()
                    inputDone = false
                    _state.update { it.copy(positionMs = seek) }
                }

                if (paused) {
                    if (!wasPaused) {
                        cur.pause()
                        wasPaused = true
                        analyzer.clear()
                        peakL = 0f
                        peakR = 0f
                        _state.update {
                            it.copy(isPlaying = false, peakL = 0f, peakR = 0f, spectrum = SpectrumAnalyzer.EMPTY)
                        }
                    }
                    Thread.sleep(30)
                    continue
                }
                if (wasPaused) {
                    cur.play()
                    wasPaused = false
                    _state.update { it.copy(isPlaying = true) }
                }

                val v = volume
                if (v != appliedVolume) {
                    cur.setVolume(v)
                    appliedVolume = v
                }

                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bo = ob.order(ByteOrder.nativeOrder())
                        val ch = max(channels, 1)
                        val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                        val total = if (isFloat) bo.asFloatBuffer().remaining() else bo.asShortBuffer().remaining()
                        val frames = total / ch
                        if (l.size < frames) {
                            l = FloatArray(frames)
                            r = FloatArray(frames)
                            pcm = ShortArray(frames * 2)
                        }
                        if (isFloat) {
                            if (fsrc.size < total) fsrc = FloatArray(total)
                            bo.asFloatBuffer().get(fsrc, 0, total)
                            for (i in 0 until frames) {
                                val a = fsrc[i * ch]
                                l[i] = a
                                r[i] = if (ch >= 2) fsrc[i * ch + 1] else a
                            }
                        } else {
                            if (src.size < total) src = ShortArray(total)
                            bo.asShortBuffer().get(src, 0, total)
                            for (i in 0 until frames) {
                                val a = src[i * ch] / 32768f
                                l[i] = a
                                r[i] = if (ch >= 2) src[i * ch + 1] / 32768f else a
                            }
                        }

                        pl.process(l, frames)
                        pr.process(r, frames)

                        var bl = 0f
                        var br = 0f
                        for (i in 0 until frames) {
                            val x = l[i]
                            val y = r[i]
                            bl = max(bl, abs(x))
                            br = max(br, abs(y))
                            pcm[2 * i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            pcm[2 * i + 1] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        cur.write(pcm, 0, frames * 2)

                        // Visualizer dihitung dari sinyal keluaran (setelah EQ/reverb/polaritas).
                        analyzer.push(l, r, frames)

                        peakL = max(bl, peakL * 0.9f)
                        peakR = max(br, peakR * 0.9f)
                        val pos = info.presentationTimeUs / 1000
                        val spectrum = analyzer.snapshot()
                        _state.update {
                            it.copy(positionMs = pos, peakL = peakL, peakR = peakR, spectrum = spectrum)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    // HE-AAC (SBR) dapat melaporkan laju sampel keluaran lebih tinggi dari header;
                    // bangun ulang AudioTrack agar nada tidak salah.
                    val outRate = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        rate
                    }
                    if (outRate != rate) {
                        rate = outRate
                        try { cur.release() } catch (_: Exception) {}
                        cur = buildTrack(rate)
                        SystemEffect.exclude(cur.audioSessionId)
                        track = cur
                        cur.setVolume(volume)
                        appliedVolume = volume
                        if (!paused) cur.play()
                        pl = ChannelProcessor(rate.toDouble()).also { it.update(left, muteL) }
                        pr = ChannelProcessor(rate.toDouble(), 23).also { it.update(right, muteR) }
                        procL = pl
                        procR = pr
                        analyzer = SpectrumAnalyzer(rate.toDouble())
                    }
                }
            }

            if (outputDone && !stopRequested) {
                cur.stop()
            }
        } catch (e: Exception) {
            fail("Gagal memutar: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { track?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            procL = null
            procR = null
            _state.update {
                it.copy(
                    isPlaying = false,
                    active = false,
                    positionMs = 0,
                    peakL = 0f,
                    peakR = 0f,
                    spectrum = SpectrumAnalyzer.EMPTY,
                )
            }
        }
    }
}

/** Satu [AudioEngine] per proses, supaya tetap hidup saat Activity ditutup dan [PlaybackService] berjalan. */
object PlayerHolder {
    @Volatile private var instance: AudioEngine? = null

    fun get(context: Context): AudioEngine =
        instance ?: synchronized(this) {
            instance ?: AudioEngine(context.applicationContext).also { instance = it }
        }
}
