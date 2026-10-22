package com.stereodsp.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stereodsp.DspViewModel
import com.stereodsp.audio.PlayerState
import com.stereodsp.audio.SessionFinder
import com.stereodsp.dsp.BandType
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings
import com.stereodsp.dsp.EqBand
import com.stereodsp.dsp.FilterChain
import com.stereodsp.dsp.PassFilter
import com.stereodsp.dsp.Slope
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** MP3, M4A/AAC, FLAC, OGG, WAV, dll. Sebagian pengelola file memberi M4A tipe audio/mp4, audio/x-m4a, atau video/mp4. */
private val AUDIO_TYPES = arrayOf(
    "audio/*", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "video/mp4",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StereoDspApp(vm: DspViewModel) {
    val left by vm.left.collectAsStateWithLifecycle()
    val right by vm.right.collectAsStateWithLifecycle()
    val linked by vm.linked.collectAsStateWithLifecycle()
    val muteL by vm.muteL.collectAsStateWithLifecycle()
    val muteR by vm.muteR.collectAsStateWithLifecycle()
    val systemOn by vm.systemOn.collectAsStateWithLifecycle()
    val systemError by vm.systemError.collectAsStateWithLifecycle()
    val sessionCount by vm.sessionCount.collectAsStateWithLifecycle()
    val reverbSessions by vm.reverbSessionCount.collectAsStateWithLifecycle()
    val captureOn by vm.captureOn.collectAsStateWithLifecycle()
    val captureError by vm.captureError.collectAsStateWithLifecycle()
    val muteOriginals by vm.muteOriginals.collectAsStateWithLifecycle()
    val player by vm.engine.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    // Izin notifikasi dimintakan sebelum memilih lagu agar kontrol pemutar di latar belakang terlihat.
    val notifForPlayer = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        picker.launch(AUDIO_TYPES)
    }
    val openPicker = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifForPlayer.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            picker.launch(AUDIO_TYPES)
        }
    }
    // Izin notifikasi (Android 13+) hanya agar notifikasi layanan terlihat; efek tetap jalan bila ditolak.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setSystem(true)
    }
    val toggleSystem = { on: Boolean ->
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setSystem(on)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val d = res.data
        if (res.resultCode == Activity.RESULT_OK && d != null) vm.startCapture(res.resultCode, d)
    }
    val askProjection = {
        projectionLauncher.launch(
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
        )
    }
    // AudioPlaybackCapture mewajibkan izin RECORD_AUDIO (hanya untuk menangkap keluaran aplikasi lain, bukan mikrofon).
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askProjection()
    }
    val toggleCapture = { on: Boolean ->
        if (!on) {
            vm.stopCapture()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            askProjection()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("NJ Audio") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SystemCard(systemOn, systemError, sessionCount, reverbSessions, left.reverbEnabled || right.reverbEnabled, toggleSystem)

            CaptureCard(captureOn, captureError, muteOriginals, vm.hasDump(), vm::setMuteOriginals, toggleCapture)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuteButton("L", muteL, Modifier.weight(1f)) { vm.setMute(Channel.LEFT, it) }
                MuteButton("R", muteR, Modifier.weight(1f)) { vm.setMute(Channel.RIGHT, it) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReverbButton("L", left.reverbEnabled, Modifier.weight(1f)) { vm.setReverb(Channel.LEFT, it) }
                ReverbButton("R", right.reverbEnabled, Modifier.weight(1f)) { vm.setReverb(Channel.RIGHT, it) }
            }

            PlayerCard(
                player = player,
                onOpen = openPicker,
                onToggle = vm::togglePause,
                onStop = vm::stop,
                onSeek = vm::seek,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Link L/R (ubah keduanya sekaligus)", Modifier.weight(1f))
                Switch(checked = linked, onCheckedChange = vm::setLinked)
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kiri (L)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Kanan (R)") })
            }

            val ch = if (tab == 0) Channel.LEFT else Channel.RIGHT
            ChannelPanel(
                channel = ch,
                settings = if (tab == 0) left else right,
                systemOn = systemOn && !captureOn,
                onChange = { t -> vm.update(ch, t) },
                onCopyToOther = { vm.copyToOther(ch) },
            )
        }
    }
}

// ---------------------------------------------------------------- Efek global & mute

@Composable
private fun SystemCard(
    on: Boolean,
    error: String?,
    sessions: Int,
    reverbSessions: Int,
    reverbWanted: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val hasDump = SessionFinder.hasDump(LocalContext.current)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Efek untuk semua aplikasi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "YouTube dan aplikasi lain ikut diproses. Tetap jalan di latar belakang.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
            if (on) {
                Text(
                    if (sessions > 0) "Terhubung ke $sessions sesi audio aplikasi lain (EQ/gain/filter)."
                    else "Belum ada aplikasi terdeteksi. Putar video/musik di aplikasi lain.",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (reverbWanted && sessions > 0) {
                    Text(
                        if (reverbSessions > 0) "Reverb aktif di $reverbSessions sesi."
                        else "Reverb tidak bisa dipasang pada sesi ini (tidak didukung perangkat/aplikasinya).",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (sessions == 0 && !hasDump) {
                    Text(
                        "Android tidak memberi daftar sesi audio ke aplikasi biasa. Beri izin sekali lewat ADB: " +
                            "adb shell pm grant com.stereodsp android.permission.DUMP, lalu matikan dan nyalakan lagi sakelar ini.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "Mode ini: EQ didekati (tanpa beda peak/shelf & Q), slope HPF/LPF tetap 24 dB/okt, polaritas tidak berlaku (hanya di pemutar internal), reverb berlaku untuk kedua kanal (preset ruang, tanpa mix).",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun CaptureCard(
    on: Boolean,
    error: String?,
    muteOriginals: Boolean,
    hasDump: Boolean,
    onMuteOriginals: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mode Tangkap (tanpa DUMP)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reverb (mix & ukuran), polaritas/fase, EQ penuh untuk YouTube dan semua aplikasi.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hasDump) "Bisukan suara asli (percobaan)" else "Bisukan suara asli (butuh izin DUMP via ADB)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Switch(checked = muteOriginals && hasDump, onCheckedChange = onMuteOriginals, enabled = hasDump)
            }
            if (on && muteOriginals && hasDump) {
                Text(
                    "Jika sekarang HENING, berarti Android menangkap suara setelah efek sesi: matikan 'Bisukan suara asli'.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (on && !(muteOriginals && hasDump)) {
                Text(
                    "Aktif. Suara asli aplikasi sumber tetap ikut terdengar (Android tidak bisa membisukannya tanpa ID sesi) " +
                        "sehingga bisa terdengar ganda/bergema; pakai headset, atau untuk suara bersih gunakan mode DUMP di atas. " +
                        "Aplikasi yang melarang penangkapan audio tidak akan terdengar.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun ReverbButton(label: String, on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    if (on) {
        Button(onClick = { onChange(false) }, modifier = modifier) { Text("Reverb $label ON") }
    } else {
        OutlinedButton(onClick = { onChange(true) }, modifier = modifier) { Text("Reverb $label OFF") }
    }
}

@Composable
private fun MuteButton(label: String, muted: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    if (muted) {
        Button(
            onClick = { onChange(false) },
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("$label MUTED") }
    } else {
        FilledTonalButton(onClick = { onChange(true) }, modifier = modifier) { Text("Mute $label") }
    }
}

// ---------------------------------------------------------------- Pemutar

@Composable
private fun PlayerCard(
    player: PlayerState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(player.fileName ?: "Belum ada file audio", style = MaterialTheme.typography.titleSmall)
            player.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            var dragging by remember { mutableStateOf<Float?>(null) }
            val dur = player.durationMs.coerceAtLeast(1)
            val shown = dragging ?: (player.positionMs.toFloat() / dur)
            Slider(
                value = shown.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * dur).toLong()) }
                    dragging = null
                },
                enabled = player.durationMs > 0,
            )
            Text(
                "${fmtTime(player.positionMs)} / ${fmtTime(player.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
            )

            SpectrumView(player.spectrum)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Buka") }
                OutlinedButton(onClick = onToggle, enabled = player.fileName != null) {
                    Text(if (player.isPlaying) "Jeda" else "Putar")
                }
                OutlinedButton(onClick = onStop, enabled = player.fileName != null) { Text("Stop") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("L", Modifier.padding(end = 4.dp))
                LinearProgressIndicator(progress = { player.peakL.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("R", Modifier.padding(end = 4.dp))
                LinearProgressIndicator(progress = { player.peakR.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- Visualizer

/** Batang spektrum (32 pita log, 40 Hz-16 kHz) dari sinyal keluaran pemutar internal. */
@Composable
private fun SpectrumView(bands: FloatArray) {
    val top = MaterialTheme.colorScheme.primary
    val bottom = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        val n = bands.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = size.height)
        val minH = 2.dp.toPx()
        for (i in 0 until n) {
            val h = (bands[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(minH)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------- Panel kanal

@Composable
private fun ChannelPanel(
    channel: Channel,
    settings: ChannelSettings,
    systemOn: Boolean,
    onChange: ((ChannelSettings) -> ChannelSettings) -> Unit,
    onCopyToOther: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCopyToOther) { Text("Salin ke ${channel.other.label}") }

        ResponseGraph(settings)

        SectionCard("Gain & polaritas") {
            ParamSlider(
                label = "Gain",
                valueText = "%+.1f dB".format(settings.gainDb),
                value = settings.gainDb,
                range = -24f..12f,
                onChange = { v -> onChange { it.copy(gainDb = snap(v, 0.5f)) } },
            )
            SwitchRow("Polaritas dibalik (180°)", settings.polarityInverted) { on ->
                onChange { it.copy(polarityInverted = on) }
            }
            if (systemOn) {
                Text(
                    "Polaritas hanya berlaku di pemutar internal. Android tidak menyediakan balik fase " +
                        "untuk aplikasi lain (YouTube dll.).",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        SectionCard("HPF (high-pass)") {
            PassFilterEditor(settings.hpf, 20f, 20000f) { f -> onChange { it.copy(hpf = f(it.hpf)) } }
        }

        SectionCard("LPF (low-pass)") {
            PassFilterEditor(settings.lpf, 20f, 20000f) { f -> onChange { it.copy(lpf = f(it.lpf)) } }
        }

        SectionCard("Reverb ${channel.label}") {
            SwitchRow("Reverb aktif", settings.reverbEnabled) { on -> onChange { it.copy(reverbEnabled = on) } }
            ParamSlider(
                label = "Mix",
                valueText = "${(settings.reverbMix * 100).roundToInt()}%",
                value = settings.reverbMix,
                range = 0f..1f,
                onChange = { v -> onChange { it.copy(reverbMix = v) } },
            )
            ParamSlider(
                label = "Ukuran ruang",
                valueText = "${(settings.reverbSize * 100).roundToInt()}%",
                value = settings.reverbSize,
                range = 0f..1f,
                onChange = { v -> onChange { it.copy(reverbSize = v) } },
            )
        }

        SectionCard("EQ parametrik 10 band") {
            EqEditor(settings, onChange)
        }
    }
}

@Composable
private fun PassFilterEditor(
    pf: PassFilter,
    minFreq: Float,
    maxFreq: Float,
    onChange: ((PassFilter) -> PassFilter) -> Unit,
) {
    SwitchRow("Aktif", pf.enabled) { on -> onChange { it.copy(enabled = on) } }
    ParamLogSlider(
        label = "Frekuensi",
        valueText = fmtFreq(pf.freq),
        value = pf.freq.coerceIn(minFreq, maxFreq),
        min = minFreq,
        max = maxFreq,
        onChange = { v -> onChange { it.copy(freq = v) } },
    )
    Text("Slope", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slope.entries.forEach { s ->
            FilterChip(
                selected = pf.slope == s,
                onClick = { onChange { it.copy(slope = s) } },
                label = { Text("${s.db} dB/okt") },
            )
        }
    }
}

@Composable
private fun EqEditor(settings: ChannelSettings, onChange: ((ChannelSettings) -> ChannelSettings) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    SwitchRow("EQ aktif", settings.eqEnabled) { on -> onChange { it.copy(eqEnabled = on) } }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.bands.forEachIndexed { i, b ->
            FilterChip(
                selected = selected == i,
                onClick = { selected = i },
                label = { Text("${i + 1} · ${fmtFreqShort(b.freq)}") },
            )
        }
    }

    val band = settings.bands[selected]
    val update = { f: (EqBand) -> EqBand -> onChange { s -> s.withBand(selected) { f(it) } } }

    SwitchRow("Band ${selected + 1} aktif", band.enabled) { on -> update { it.copy(enabled = on) } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BandType.entries.forEach { t ->
            FilterChip(
                selected = band.type == t,
                onClick = { update { it.copy(type = t) } },
                label = { Text(t.label) },
            )
        }
    }
    ParamLogSlider("Frekuensi", fmtFreq(band.freq), band.freq, 20f, 20000f) { v -> update { it.copy(freq = v) } }
    ParamSlider(
        label = "Gain",
        valueText = "%+.1f dB".format(band.gainDb),
        value = band.gainDb,
        range = -18f..18f,
        onChange = { v -> update { it.copy(gainDb = snap(v, 0.5f)) } },
    )
    ParamLogSlider("Q", "%.2f".format(band.q), band.q, 0.2f, 10f) { v -> update { it.copy(q = v) } }
}

// ---------------------------------------------------------------- Grafik respons

@Composable
private fun ResponseGraph(settings: ChannelSettings) {
    val freqs = remember { DoubleArray(240) { 20.0 * 1000.0.pow(it / 239.0) } }
    val db = remember(settings) { FilterChain.responseCurve(settings, freqs, 48000.0) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val zeroColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val range = 18.0

    Card(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(8.dp),
        ) {
            fun x(f: Double) = (ln(f / 20.0) / ln(1000.0)).toFloat() * size.width
            fun y(d: Double) = size.height * (0.5f - (d.coerceIn(-range, range) / (2 * range)).toFloat())

            for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
                drawLine(gridColor, Offset(x(f), 0f), Offset(x(f), size.height))
            }
            for (d in listOf(-12.0, -6.0, 6.0, 12.0)) {
                drawLine(gridColor, Offset(0f, y(d)), Offset(size.width, y(d)))
            }
            drawLine(zeroColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))

            val path = Path()
            freqs.indices.forEach { i ->
                val px = x(freqs[i])
                val py = y(db[i])
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f))
        }
    }
}

// ---------------------------------------------------------------- Komponen umum

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ParamLogSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    val pos = (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = pos,
            onValueChange = { onChange((min * (max / min).pow(it)).coerceIn(min, max)) },
        )
    }
}

// ---------------------------------------------------------------- Format

private fun snap(v: Float, step: Float) = (v / step).roundToInt() * step

private fun fmtFreq(f: Float): String =
    if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "%.0f Hz".format(f)

private fun fmtFreqShort(f: Float): String =
    if (f >= 1000f) "%.1fk".format(f / 1000f).replace(".0k", "k") else "%.0f".format(f)

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
