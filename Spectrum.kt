package com.stereodsp.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Penganalisis spektrum untuk visualizer: FFT 4096 titik (jendela Hann) pada campuran mono L+R,
 * dikelompokkan ke [bands] pita berspasi logaritmik (40 Hz .. ~16 kHz).
 * Nilai keluaran 0..1 (skala dB -70..0) dengan pelepasan halus. Hanya dipakai dari satu thread.
 */
class SpectrumAnalyzer(private val fs: Double = 44100.0, val bands: Int = BANDS) {
    private val n = 4096
    private val ring = FloatArray(n)
    private var pos = 0
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { (-sin(2.0 * PI * it / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val out = FloatArray(bands)
    private val lo = IntArray(bands)
    private val hi = IntArray(bands)
    private val tiltDb = FloatArray(bands)

    init {
        val fMin = 40.0
        val fMax = minOf(16000.0, fs / 2 * 0.95)
        val ratio = fMax / fMin
        val binHz = fs / n
        for (b in 0 until bands) {
            val f0 = fMin * ratio.pow(b.toDouble() / bands)
            val f1 = fMin * ratio.pow((b + 1).toDouble() / bands)
            val a = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            lo[b] = a
            hi[b] = max(a, (f1 / binHz).toInt().coerceIn(1, n / 2 - 1))
            // Musik cenderung turun ~3 dB/oktaf; kompensasi ringan agar batang kanan tidak selalu rendah.
            val fc = sqrt(f0 * f1)
            tiltDb[b] = (2.5 * ln(fc / 1000.0) / ln(2.0)).toFloat()
        }
    }

    /** Masukkan [frames] sampel L/R; spektrum dihitung ulang dari [n] sampel terakhir. */
    fun push(l: FloatArray, r: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            ring[pos] = 0.5f * (l[i] + r[i])
            if (++pos >= n) pos = 0
        }
        analyze()
    }

    /** Salinan keluaran saat ini (0..1 per pita). */
    fun snapshot(): FloatArray = out.copyOf()

    fun clear() {
        ring.fill(0f)
        out.fill(0f)
    }

    private fun analyze() {
        for (i in 0 until n) {
            re[i] = ring[(pos + i) % n] * window[i]
            im[i] = 0f
        }
        fft()
        val norm = n / 4f // amplitudo sinus skala penuh pada jendela Hann
        for (b in 0 until bands) {
            var m = 0f
            for (k in lo[b]..hi[b]) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > m) m = mag
            }
            val db = 20f * log10(m / norm + 1e-9f) + tiltDb[b]
            val v = ((db + 70f) / 70f).coerceIn(0f, 1f)
            out[b] = max(v, out[b] * 0.85f)
        }
    }

    /** FFT radix-2 di tempat pada [re]/[im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val a = i + m
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 32
        /** Spektrum kosong (jangan diubah). */
        val EMPTY = FloatArray(BANDS)
    }
}
