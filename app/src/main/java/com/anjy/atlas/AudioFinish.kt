package com.anjy.atlas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The "AI system" finish on Atlas's spoken replies: a little presence and air, a light digital sheen (a touch of
 * harmonic exciter and a faint short chorus), a small, short room, and gentle compression, so every voice (designed,
 * Gemini, OpenAI, the phone's) sounds like the same composed assistant coming through a well-made speaker. Kept
 * subtle on purpose: words first. Pure arithmetic on 16-bit mono PCM, the same on the phone and the PC
 * (desktop/renderer/finish.js does the same with Web Audio).
 */
object AudioFinish {
    /** How much of each touch. [DEFAULT] is what Atlas uses; tests check its effect. */
    class Amount(
        val presenceDb: Double = 2.5,     // peak at 3.2 kHz: consonants
        val airDb: Double = 1.5,          // shelf above 8 kHz
        val boxDb: Double = -1.5,         // dip at 300 Hz: less boxy
        val exciter: Double = 0.07,       // added harmonics of the upper band
        val chorus: Double = 0.10,        // a faint second voice, 7 ms behind, gently wavering
        val room: Double = 0.085,         // a small room's reflections
        val ratio: Double = 2.5,          // compression above the threshold
        val thresholdDb: Double = -20.0,
    )
    val DEFAULT = Amount()
    /** Room tail added after the last word (seconds). */
    const val TAIL_S = 0.22

    /** [pcm]: 16-bit little-endian mono at [rate]; returns the finished audio (a little longer: the room's tail). */
    fun apply(pcm: ByteArray, rate: Int, a: Amount = DEFAULT): ByteArray {
        val n = pcm.size / 2
        val x = DoubleArray(n) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768.0 }
        val y = process(x, rate, a)
        val out = ByteArray(y.size * 2)
        for (i in y.indices) {
            val s = (y[i] * 32767.0).let { if (it > 32767) 32767 else if (it < -32768) -32768 else it.toInt() }
            out[2 * i] = (s and 0xff).toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    /** The same on samples in -1..1. */
    fun process(input: DoubleArray, rate: Int, a: Amount = DEFAULT): DoubleArray {
        val fs = rate.toDouble()
        val tail = (TAIL_S * fs).toInt()
        val n = input.size
        val x = DoubleArray(n + tail).also { input.copyInto(it) }
        val inRms = rms(input)
        if (inRms < 1e-6) return x                                   // silence stays silence

        // 1. tone: rumble and DC out, less box, more presence and air
        var y = Biquad.highPass(fs, 80.0, 0.707).run(x)
        y = Biquad.peak(fs, 300.0, 1.0, a.boxDb).run(y)
        y = Biquad.peak(fs, 3200.0, 0.9, a.presenceDb).run(y)
        if (8000.0 < 0.45 * fs) y = Biquad.highShelf(fs, 8000.0, a.airDb).run(y)

        // 2. sheen: harmonics of the upper band, then a faint wavering echo of the voice 7 ms behind
        if (a.exciter > 0) {
            val band = Biquad.highPass(fs, 2500.0, 0.707).run(y)
            for (i in band.indices) band[i] = tanh(4.0 * band[i]) / 4.0
            val harm = Biquad.highPass(fs, 4000.0, 0.707).run(band)
            for (i in y.indices) y[i] += a.exciter * harm[i]
        }
        if (a.chorus > 0) {
            val src = y.copyOf()
            val base = 0.007 * fs; val depth = 0.00035 * fs
            for (i in y.indices) {
                val d = base + depth * sin(2 * PI * 0.4 * i / fs)
                val p = i - d
                if (p < 0) continue
                val k = p.toInt(); val f = p - k
                y[i] += a.chorus * (src[k] * (1 - f) + src[min(k + 1, src.lastIndex)] * f)
            }
        }

        // 3. gentle compression (soft knee), so quiet words aren't lost and loud ones don't bark
        y = compress(y, fs, a.thresholdDb, a.ratio)

        // 4. a small room: short, bright-ish, well behind the voice
        if (a.room > 0) {
            val wet = room(y, fs)
            for (i in y.indices) y[i] += a.room * wet[i]
        }

        // 5. as loud as it came in, never clipping: matched loudness, then peaks kept under -1 dBFS
        val g = inRms / max(rms(y, n), 1e-9)
        for (i in y.indices) y[i] *= g
        limit(y, fs, 0.89)
        return y
    }

    private fun rms(v: DoubleArray, len: Int = v.size): Double {
        var s = 0.0; for (i in 0 until len) s += v[i] * v[i]; return sqrt(s / max(len, 1))
    }

    private fun compress(x: DoubleArray, fs: Double, thresholdDb: Double, ratio: Double): DoubleArray {
        val att = exp(-1.0 / (0.004 * fs)); val rel = exp(-1.0 / (0.09 * fs))
        val knee = 6.0
        var env = 0.0
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val lvl = abs(x[i])
            env = if (lvl > env) att * env + (1 - att) * lvl else rel * env + (1 - rel) * lvl
            val db = 20 * ln(max(env, 1e-9)) / ln(10.0)
            val over = db - thresholdDb
            val cut = when {
                over <= -knee / 2 -> 0.0
                over >= knee / 2 -> over * (1 - 1 / ratio)
                else -> (1 - 1 / ratio) * (over + knee / 2).pow(2) / (2 * knee)
            }
            out[i] = x[i] * 10.0.pow(-cut / 20)
        }
        return out
    }

    /** Four damped combs into two allpasses (a Schroeder room, a few metres across), after 8 ms. */
    private fun room(x: DoubleArray, fs: Double): DoubleArray {
        val pre = (0.008 * fs).toInt()
        val combs = doubleArrayOf(0.0231, 0.0267, 0.0299, 0.0323).map { (it * fs).toInt() }
        val fb = 0.62; val damp = 0.35
        val sum = DoubleArray(x.size)
        for (d in combs) {
            val buf = DoubleArray(d); var idx = 0; var lp = 0.0
            for (i in x.indices) {
                val inp = if (i >= pre) x[i - pre] else 0.0
                val o = buf[idx]
                lp = o * (1 - damp) + lp * damp
                buf[idx] = inp + lp * fb
                idx = (idx + 1) % d
                sum[i] += o * 0.25
            }
        }
        var y = sum
        for ((ms, g) in listOf(0.005 to 0.5, 0.0017 to 0.5)) {
            val d = max(1, (ms * fs).toInt()); val buf = DoubleArray(d); var idx = 0
            val o = DoubleArray(y.size)
            for (i in y.indices) {
                val b = buf[idx]; val v = y[i] + b * g
                o[i] = b - v * g; buf[idx] = v; idx = (idx + 1) % d
            }
            y = o
        }
        return y
    }

    /** Peaks over [ceiling] are pulled down smoothly (instant attack, 60 ms release); nothing goes over. */
    private fun limit(y: DoubleArray, fs: Double, ceiling: Double) {
        val rel = exp(-1.0 / (0.06 * fs))
        // look ahead 2 ms so the gain is already down when the peak arrives
        val ahead = max(1, (0.002 * fs).toInt())
        var gain = 1.0
        val need = DoubleArray(y.size) { i -> val p = abs(y[i]); if (p > ceiling) ceiling / p else 1.0 }
        // the smallest gain needed over the next [ahead] samples
        val minAhead = DoubleArray(y.size)
        val dq = ArrayDeque<Int>()
        for (i in y.indices.reversed()) {
            while (dq.isNotEmpty() && dq.first() > i + ahead) dq.removeFirst()
            while (dq.isNotEmpty() && need[dq.last()] >= need[i]) dq.removeLast()
            dq.addLast(i)
            minAhead[i] = need[dq.first()]
        }
        for (i in y.indices) {
            gain = if (minAhead[i] < gain) minAhead[i] else rel * gain + (1 - rel) * min(1.0, minAhead[i])
            y[i] *= gain
            if (y[i] > ceiling) y[i] = ceiling else if (y[i] < -ceiling) y[i] = -ceiling
        }
    }

    /** A second-order filter (RBJ cookbook). */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        fun run(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
            }
            return y
        }
        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            fun highPass(fs: Double, f: Double, q: Double): Biquad {
                val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            }
            fun peak(fs: Double, f: Double, q: Double, db: Double): Biquad {
                val A = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
                return make(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A)
            }
            fun highShelf(fs: Double, f: Double, db: Double): Biquad {
                val A = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0)
                val sa = 2 * sqrt(A) * al
                return make(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
                    (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa)
            }
        }
    }
}
