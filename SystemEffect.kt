package com.stereodsp.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Efek untuk aplikasi lain (YouTube dll.). Efek dipasang langsung ke setiap sesi audio yang
 * sedang aktif (dicari oleh [SessionFinder] dan broadcast OPEN_AUDIO_EFFECT_CONTROL_SESSION);
 * session 0 (global) juga dicoba, tetapi banyak perangkat mengabaikannya.
 *
 * Per sesi dipasang dua efek yang INDEPENDEN (kegagalan satu tidak membatalkan yang lain):
 *  - [DynamicsProcessing]: Gain/Mute, HPF/LPF (crossover MBC), EQ 10 band (Pre-EQ, pendekatan)
 *  - [PresetReverb]: reverb (tidak per kanal: aktif bila L atau R menyalakan reverb)
 *
 * Polaritas (balik fase) TIDAK bisa dilakukan lewat API efek Android untuk aplikasi lain;
 * ia hanya berlaku di pemutar internal.
 */
object SystemEffect {
    private const val MUTE_DB = -80f
    private const val TOP_EDGE = 22000f
    private const val EQ_BANDS = ChannelSettings.BAND_COUNT

    private class Chain(
        val session: Int,
        var dp: DynamicsProcessing?,
        var reverb: PresetReverb?,
    ) {
        /** Pembuatan reverb gagal pada sesi ini; jangan dicoba lagi sampai reverb dimatikan-dinyalakan. */
        var reverbFailed = false
    }

    private val chains = HashMap<Int, Chain>()

    /** Sesi yang dilaporkan aplikasi pemutar lewat broadcast (tetap dipasang walau dumpsys tak tersedia). */
    private val registered = HashSet<Int>()

    /** Sesi milik aplikasi ini sendiri (pemutar internal, keluaran Mode Tangkap): jangan diberi efek/dibisukan. */
    private val excluded = HashSet<Int>()
    private var left = ChannelSettings()
    private var right = ChannelSettings()
    private var muteL = false
    private var muteR = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Jumlah sesi audio aplikasi lain yang sedang diberi efek (tidak termasuk session 0). */
    private val _sessions = MutableStateFlow(0)
    val sessions: StateFlow<Int> = _sessions.asStateFlow()

    /** Dari [sessions], berapa yang reverb-nya benar-benar terpasang dan menyala. */
    private val _reverbSessions = MutableStateFlow(0)
    val reverbSessions: StateFlow<Int> = _reverbSessions.asStateFlow()

    @Synchronized
    fun start(l: ChannelSettings, r: ChannelSettings, mL: Boolean, mR: Boolean): Boolean {
        left = l; right = r; muteL = mL; muteR = mR
        _error.value = null
        _active.value = true
        if (!chains.containsKey(0)) attach(0)
        for (id in registered) if (!chains.containsKey(id)) attach(id)
        refreshCounts()
        return true
    }

    @Synchronized
    fun update(l: ChannelSettings, r: ChannelSettings, mL: Boolean, mR: Boolean) {
        left = l; right = r; muteL = mL; muteR = mR
        if (!_active.value) return
        val iter = chains.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            applyAll(e.value)
            if (e.value.dp == null && e.value.reverb == null && !wantReverb()) {
                // Tidak ada efek tersisa pada sesi ini (mis. sesi sudah ditutup).
                iter.remove()
            }
        }
        refreshCounts()
    }

    @Synchronized
    fun exclude(id: Int) {
        if (id <= 0) return
        excluded.add(id)
        chains.remove(id)?.let { release(it) }
        refreshCounts()
    }

    @Synchronized
    fun include(id: Int) {
        excluded.remove(id)
    }

    /** Samakan sesi yang diberi efek dengan daftar [ids] sesi audio yang sedang aktif. */
    @Synchronized
    fun sync(ids: Set<Int>) {
        if (!_active.value) return
        val all = (ids + registered) - excluded
        val gone = chains.keys.filter { it != 0 && it !in all }
        for (id in gone) chains.remove(id)?.let { release(it) }
        for (id in all) if (id > 0 && !chains.containsKey(id)) attach(id)
        refreshCounts()
    }

    /** Aplikasi pemutar membuka sesi efek (broadcast OPEN_AUDIO_EFFECT_CONTROL_SESSION). */
    @Synchronized
    fun registerSession(id: Int) {
        if (id <= 0 || id in excluded) return
        registered.add(id)
        if (_active.value && !chains.containsKey(id)) {
            attach(id)
            refreshCounts()
        }
    }

    /** Aplikasi pemutar menutup sesi efek (broadcast CLOSE_AUDIO_EFFECT_CONTROL_SESSION). */
    @Synchronized
    fun unregisterSession(id: Int) {
        registered.remove(id)
        chains.remove(id)?.let { release(it) }
        refreshCounts()
    }

    @Synchronized
    fun stop() {
        chains.values.forEach { release(it) }
        chains.clear()
        _sessions.value = 0
        _reverbSessions.value = 0
        _active.value = false
    }

    private fun refreshCounts() {
        _sessions.value = chains.keys.count { it != 0 }
        _reverbSessions.value = chains.count { it.key != 0 && it.value.reverb != null && wantReverb() }
    }

    private fun release(c: Chain) {
        try { c.reverb?.setEnabled(false) } catch (_: Throwable) {}
        try { c.reverb?.release() } catch (_: Throwable) {}
        try { c.dp?.setEnabled(false) } catch (_: Throwable) {}
        try { c.dp?.release() } catch (_: Throwable) {}
        c.reverb = null
        c.dp = null
    }

    private fun attach(session: Int) {
        var dp: DynamicsProcessing? = null
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, EQ_BANDS,
                true, 3,
                false, 0,
                false,
            ).build()
            dp = DynamicsProcessing(0, session, cfg)
        } catch (_: Throwable) {
            dp = null
        }
        val chain = Chain(session, dp, null)
        applyAll(chain)
        // Sesi 0 boleh kosong (sering ditolak); sesi lain disimpan walau hanya satu efek yang berhasil.
        if (chain.dp != null || chain.reverb != null || session == 0) {
            chains[session] = chain
        }
    }

    private fun applyAll(c: Chain) {
        c.dp?.let { d ->
            try {
                applyChannel(d, 0, left, muteL)
                applyChannel(d, 1, right, muteR)
                d.setEnabled(true)
            } catch (_: Throwable) {
                try { d.release() } catch (_: Throwable) {}
                c.dp = null
            }
        }
        applyReverb(c)
    }

    private fun reverbSource(): ChannelSettings? = when {
        left.reverbEnabled -> left
        right.reverbEnabled -> right
        else -> null
    }

    private fun wantReverb() = reverbSource() != null

    private fun applyReverb(c: Chain) {
        val src = reverbSource()
        if (src == null) {
            c.reverbFailed = false
            try { c.reverb?.setEnabled(false) } catch (_: Throwable) {}
            return
        }
        if (c.reverbFailed) return
        try {
            val rv = c.reverb ?: PresetReverb(0, c.session).also { c.reverb = it }
            rv.preset = when {
                src.reverbSize < 0.2f -> PresetReverb.PRESET_SMALLROOM
                src.reverbSize < 0.4f -> PresetReverb.PRESET_MEDIUMROOM
                src.reverbSize < 0.6f -> PresetReverb.PRESET_LARGEROOM
                src.reverbSize < 0.8f -> PresetReverb.PRESET_MEDIUMHALL
                else -> PresetReverb.PRESET_LARGEHALL
            }
            rv.setEnabled(true)
        } catch (_: Throwable) {
            try { c.reverb?.release() } catch (_: Throwable) {}
            c.reverb = null
            c.reverbFailed = true
        }
    }

    private fun applyChannel(d: DynamicsProcessing, ch: Int, s: ChannelSettings, muted: Boolean) {
        d.setInputGainbyChannel(ch, if (muted) MUTE_DB else s.gainDb)

        // --- HPF / LPF lewat crossover MBC (3 band) ---
        var hp = if (s.hpf.enabled) s.hpf.freq else 20f
        hp = hp.coerceIn(20f, 20000f)
        var lp = if (s.lpf.enabled) s.lpf.freq else 20000f
        lp = lp.coerceIn(20f, 20000f)
        if (lp < hp * 1.05f) lp = (hp * 1.05f).coerceAtMost(21500f)
        val lowGain = if (s.hpf.enabled) MUTE_DB else 0f
        val highGain = if (s.lpf.enabled) MUTE_DB else 0f
        d.setMbcBandByChannelIndex(ch, 0, mbc(hp, lowGain))
        d.setMbcBandByChannelIndex(ch, 1, mbc(lp, 0f))
        d.setMbcBandByChannelIndex(ch, 2, mbc(TOP_EDGE, highGain))

        // --- EQ 10 band lewat Pre-EQ ---
        val bands = s.bands.sortedBy { it.freq }
        for (i in 0 until EQ_BANDS) {
            val b = bands[i]
            val edge = if (i == EQ_BANDS - 1) TOP_EDGE else sqrt(b.freq * bands[i + 1].freq)
            val gain = if (s.eqEnabled && b.enabled) b.gainDb.coerceIn(-18f, 18f) else 0f
            d.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, edge.coerceAtMost(TOP_EDGE), gain))
        }
    }

    /** Band MBC netral (tanpa kompresi) dengan batas atas [cutoff] dan penguatan keluaran [postGain]. */
    private fun mbc(cutoff: Float, postGain: Float) = DynamicsProcessing.MbcBand(
        true, cutoff,
        1f, 50f,      // attack, release (ms)
        1f, 0f, 0f,   // ratio, threshold, knee -> tidak mengompresi
        -90f, 1f,     // noise gate, expander ratio
        0f, postGain, // pre gain, post gain
    )
}

/** Mencari ID sesi audio dari aplikasi yang sedang/baru saja memutar suara. */
object SessionFinder {
    private val sessionRegex = Regex("""session(?:Id)?\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun hasDump(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    fun find(ctx: Context): Set<Int> {
        val out = HashSet<Int>()
        // 1) API publik + refleksi (tanpa izin khusus; tidak tersedia di semua versi Android)
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            for (c in am.activePlaybackConfigurations) {
                for (name in arrayOf("getAudioSessionId", "getSessionId")) {
                    try {
                        val v = AudioPlaybackConfiguration::class.java.getMethod(name).invoke(c) as? Int
                        if (v != null && v > 0) {
                            out.add(v)
                            break
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 2) dumpsys audio (butuh izin DUMP yang diberikan sekali lewat ADB)
        if (hasDump(ctx)) out.addAll(fromDumpsys())
        return out
    }

    private fun fromDumpsys(): Set<Int> {
        val ids = HashSet<Int>()
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("dumpsys", "audio"))
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.contains("AudioPlaybackConfiguration")) continue
                    if (line.contains("released", ignoreCase = true)) continue
                    val m = sessionRegex.find(line) ?: continue
                    val id = m.groupValues[1].toIntOrNull() ?: continue
                    if (id > 0) ids.add(id)
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { p?.destroy() } catch (_: Throwable) {}
        }
        return ids
    }
}
