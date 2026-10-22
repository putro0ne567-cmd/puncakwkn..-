package com.stereodsp.dsp

import kotlin.math.max

/**
 * Reverb mono bergaya Freeverb (8 comb + 4 allpass). [spread] menggeser panjang delay
 * sedikit agar reverb kanal L dan R tidak identik.
 */
class Reverb(fs: Double, spread: Int = 0) {
    private class Comb(size: Int) {
        val buf = FloatArray(max(size, 1))
        var idx = 0
        var store = 0f
        fun process(x: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = x + store * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }
        fun clear() { buf.fill(0f); store = 0f; idx = 0 }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(max(size, 1))
        var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            buf[idx] = x + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return b - x
        }
        fun clear() { buf.fill(0f); idx = 0 }
    }

    private val k = fs / 44100.0
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        .map { Comb(((it + spread) * k).toInt()) }
    private val allpasses = intArrayOf(556, 441, 341, 225)
        .map { AllPass(((it + spread) * k).toInt()) }

    @Volatile private var feedback = 0.84f
    private val damp = 0.4f

    /** [size] 0..1 menentukan lama gema. */
    fun setSize(size: Float) {
        feedback = size.coerceIn(0f, 1f) * 0.28f + 0.7f
    }

    fun clear() {
        combs.forEach { it.clear() }
        allpasses.forEach { it.clear() }
    }

    /** Mengembalikan sinyal basah (wet) untuk satu sampel masukan. */
    fun process(x: Float): Float {
        val input = x * 0.015f
        val fb = feedback
        var sum = 0f
        for (c in combs) sum += c.process(input, fb, damp)
        for (a in allpasses) sum = a.process(sum)
        return sum * 3f
    }
}
