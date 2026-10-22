package com.stereodsp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stereodsp.audio.CaptureEngine
import com.stereodsp.audio.CaptureService
import com.stereodsp.audio.SessionFinder
import com.stereodsp.audio.PlaybackService
import com.stereodsp.audio.PlayerHolder
import com.stereodsp.audio.SystemEffect
import com.stereodsp.audio.SystemEffectService
import com.stereodsp.data.SettingsStore
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DspViewModel(app: Application) : AndroidViewModel(app) {

    /** Engine bersama (singleton) agar pemutaran tetap hidup saat Activity ditutup. */
    val engine = PlayerHolder.get(app)
    private val store = SettingsStore(app)

    private val _left = MutableStateFlow(store.loadCurrent(Channel.LEFT) ?: ChannelSettings())
    private val _right = MutableStateFlow(store.loadCurrent(Channel.RIGHT) ?: ChannelSettings())
    private val _linked = MutableStateFlow(false)
    private val _muteL = MutableStateFlow(store.isMuted(Channel.LEFT))
    private val _muteR = MutableStateFlow(store.isMuted(Channel.RIGHT))

    val left: StateFlow<ChannelSettings> = _left.asStateFlow()
    val right: StateFlow<ChannelSettings> = _right.asStateFlow()
    val linked: StateFlow<Boolean> = _linked.asStateFlow()
    val muteL: StateFlow<Boolean> = _muteL.asStateFlow()
    val muteR: StateFlow<Boolean> = _muteR.asStateFlow()

    /** True bila efek berlaku untuk seluruh aplikasi (YouTube dll.). */
    val systemOn: StateFlow<Boolean> = SystemEffect.active
    val systemError: StateFlow<String?> = SystemEffect.error
    val sessionCount: StateFlow<Int> = SystemEffect.sessions
    val reverbSessionCount: StateFlow<Int> = SystemEffect.reverbSessions

    /** Mode Tangkap: suara aplikasi lain ditangkap & diproses penuh (reverb mix, polaritas) tanpa izin DUMP. */
    val captureOn: StateFlow<Boolean> = CaptureEngine.active
    val captureError: StateFlow<String?> = CaptureEngine.error

    /** Bisukan suara asli aplikasi sumber saat Mode Tangkap (percobaan; butuh izin DUMP untuk menemukan sesi). */
    private val _muteOriginals = MutableStateFlow(true)
    val muteOriginals: StateFlow<Boolean> = _muteOriginals.asStateFlow()
    private var muterRunning = false

    fun setMuteOriginals(on: Boolean) {
        _muteOriginals.value = on
        if (CaptureEngine.active.value) { if (on) startMuter() else stopMuter() }
    }

    fun hasDump(): Boolean = SessionFinder.hasDump(getApplication<Application>())

    private fun startMuter() {
        val ctx = getApplication<Application>()
        if (muterRunning || !hasDump()) return
        muterRunning = true
        ctx.startForegroundService(Intent(ctx, SystemEffectService::class.java).putExtra(SystemEffectService.EXTRA_MUTER, true))
    }

    private fun stopMuter() {
        if (!muterRunning) return
        muterRunning = false
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, SystemEffectService::class.java))
    }

    init {
        pushToEngine()
        // Saat efek global menyala/mati, pemutar internal berganti mode agar tidak diproses dua kali.
        viewModelScope.launch { SystemEffect.active.collect { pushToEngine() } }
        viewModelScope.launch {
            CaptureEngine.active.collect { on ->
                if (!on) stopMuter()
                pushToEngine()
            }
        }
    }

    private fun flow(ch: Channel) = if (ch == Channel.LEFT) _left else _right
    private fun muteFlow(ch: Channel) = if (ch == Channel.LEFT) _muteL else _muteR

    private fun pushToEngine() {
        store.saveCurrent(Channel.LEFT, _left.value)
        store.saveCurrent(Channel.RIGHT, _right.value)
        if (SystemEffect.active.value && CaptureEngine.active.value) {
            // Mode Tangkap: sistem hanya membisukan suara asli; pemrosesan dilakukan CaptureEngine.
            engine.setSettings(_left.value, _right.value, _muteL.value, _muteR.value)
            SystemEffect.update(ChannelSettings(), ChannelSettings(), true, true)
        } else if (SystemEffect.active.value) {
            // Efek global sudah memproses semua suara, termasuk pemutar ini.
            engine.setSettings(ChannelSettings(), ChannelSettings(), false, false)
            SystemEffect.update(_left.value, _right.value, _muteL.value, _muteR.value)
        } else {
            engine.setSettings(_left.value, _right.value, _muteL.value, _muteR.value)
        }
        CaptureEngine.setSettings(_left.value, _right.value, _muteL.value, _muteR.value)
    }

    /** Ubah pengaturan satu kanal; bila Link aktif, hasilnya disalin ke kanal lain. */
    fun update(ch: Channel, transform: (ChannelSettings) -> ChannelSettings) {
        val new = transform(flow(ch).value)
        flow(ch).value = new
        if (_linked.value) flow(ch.other).value = new
        pushToEngine()
    }

    fun setLinked(on: Boolean) {
        _linked.value = on
    }

    fun setMute(ch: Channel, on: Boolean) {
        muteFlow(ch).value = on
        store.setMuted(ch, on)
        pushToEngine()
    }

    /** Hidupkan/matikan reverb satu kanal saja (tidak ikut Link). */
    fun setReverb(ch: Channel, on: Boolean) {
        flow(ch).value = flow(ch).value.copy(reverbEnabled = on)
        pushToEngine()
    }

    /** [code]/[data] adalah hasil izin MediaProjection dari dialog sistem. */
    fun startCapture(code: Int, data: Intent) {
        val ctx = getApplication<Application>()
        // Dua jalur sekaligus akan memproses suara dua kali.
        if (SystemEffect.active.value) setSystem(false)
        if (_muteOriginals.value) startMuter()
        ctx.startForegroundService(
            Intent(ctx, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, code)
                .putExtra(CaptureService.EXTRA_DATA, data),
        )
    }

    fun stopCapture() {
        val ctx = getApplication<Application>()
        stopMuter()
        CaptureEngine.stop()
        ctx.stopService(Intent(ctx, CaptureService::class.java))
    }

    fun setSystem(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on && CaptureEngine.active.value) stopCapture()
        store.setSystemEnabled(on)
        val intent = Intent(ctx, SystemEffectService::class.java)
        if (on) ctx.startForegroundService(intent) else ctx.stopService(intent)
    }

    fun copyToOther(from: Channel) {
        flow(from.other).value = flow(from).value
        pushToEngine()
    }

    fun open(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "audio"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (_: Exception) {
        }
        engine.load(uri, name)
        // Service foreground menjaga pemutaran tetap berjalan di latar belakang.
        PlaybackService.start(getApplication<Application>())
    }

    fun togglePause() {
        if (!engine.state.value.active && engine.hasTrack) {
            // Lagu sudah selesai/dihentikan: putar ulang dari awal.
            engine.replay()
            PlaybackService.start(getApplication<Application>())
        } else {
            engine.togglePause()
        }
    }

    fun stop() = engine.stop()
    fun seek(ms: Long) = engine.seekTo(ms)

    // Engine sengaja TIDAK dilepas di onCleared: pemutaran dikelola PlaybackService.
}
