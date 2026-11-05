package com.anjy.atlas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints Atlas's orb, pixel by pixel, on the CPU. It's the same design as the GPU shader it replaces (tuned
 * against the reference in tools/orb_proto.py), but it can't fail silently on some phone's graphics chip: every
 * phone draws exactly the same picture. Plain Kotlin with no Android parts, so it's tested on a computer too.
 *
 * Not thread-safe per instance: one painter per drawing thread.
 */
class OrbPainter {
    private var cw = 0; private var ch = 0
    // what depends only on the pixel's place, worked out once per size
    private var grainA = FloatArray(0)
    private var linesA = FloatArray(0)
    private val radius = FloatArray(ANGLES + 1)

    private fun prepare(w: Int, h: Int) {
        if (w == cw && h == ch) return
        cw = w; ch = h
        grainA = FloatArray(w * h); linesA = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            grainA[i] = 0.7f + 0.6f * hash(x, y + 7919)
            linesA[i] = 0.62f + 0.38f * sin(y * PI_F / 1.15f)
        }
    }

    private fun wrapA(x: Float): Float { var a = x; while (a > PI_F) a -= 2 * PI_F; while (a < -PI_F) a += 2 * PI_F; return a }

    private fun radiusAt(th: Float, t: Float, energy: Float): Float {
        val k = 1f + 0.060f * sin(2f * th + 0.4f + t * 0.31f) + 0.045f * sin(3f * th + 1.9f - t * 0.23f) +
            0.025f * sin(5f * th + 4.1f + t * 0.41f) + 0.015f * energy * sin(7f * th + t * 1.7f)
        val bite = -0.42f + 0.12f * sin(t * 0.21f)
        val d0 = wrapA(th - bite); val d1 = wrapA(th - bite + 0.62f); val d2 = wrapA(th - bite - 2.6f)
        return k - 0.46f * exp(-(d0 * d0) / (2f * 0.0676f)) + 0.10f * exp(-(d1 * d1) / (2f * 0.0784f)) +
            0.07f * exp(-(d2 * d2) / (2f * 0.25f))
    }

    /**
     * Fills [out] (w×h, ARGB colour ints, not premultiplied) with the orb at [time]
     * seconds. [energy] 0..1 stirs and brightens it; [level] 0..1 swells it with the voice.
     */
    fun paint(out: IntArray, w: Int, h: Int, time: Float, energy: Float, level: Float) {
        prepare(w, h)
        val t = time
        for (a in 0..ANGLES) radius[a] = radiusAt(-PI_F + a * (2 * PI_F / ANGLES), t, energy)
        val side = min(w, h).toFloat()
        val s = side * 0.33f * (1f + 0.08f * level) * (1f + 0.05f * energy)
        val aa = 1.5f / s
        val ox = 0.35f * sin(t * 0.21f); val oy = 0.3f * cos(t * 0.17f)
        for (y in 0 until h) {
            val py = (y + 0.5f - h * 0.5f) / s / 1.12f
            for (x in 0 until w) {
                val i = y * w + x
                val px = (x + 0.5f - w * 0.5f) / s
                val rad = sqrt(px * px + py * py)
                val th = atan2(py, px)
                val fa = (th + PI_F) / (2 * PI_F) * ANGLES
                val ia = min(fa.toInt(), ANGLES - 1); val ft = fa - ia
                val r = radius[ia] + (radius[ia + 1] - radius[ia]) * ft
                val d = rad / r
                val inside = 1f - smooth(1f - aa, 1f + aa, d)
                val glow = exp(-max(d - 1f, 0f) * 6f) * (1f - inside) * (0.10f + 0.25f * energy)
                if (inside <= 0f && glow < 0.004f) { out[i] = 0; continue }

                var cr = 0.004f; var cg = 0.008f; var cb = 0.03f
                if (inside > 0f) {
                    val fx = px * 1.05f + ox; val fy = py * 1.05f + oy
                    val warpx = fbm(fx + 3.1f, fy - 1.3f + t * 0.05f)
                    val warpy = fbm(fx - 2.4f + t * 0.04f, fy + 5.2f)
                    val n = fbm(fx + 1.4f * warpx, fy + 1.4f * warpy)
                    val rib = 1f - smooth(0f, 0.07f, abs(n - 0.52f))
                    val rib2 = 1f - smooth(0f, 0.045f, abs(n - 0.42f))
                    val halo = 1f - smooth(0f, 0.16f, abs(n - 0.50f))
                    val zone = smooth(0.15f, 0.7f, d) * (1f - smooth(0.97f, 1f, d))
                    val band = (rib + rib2 * 0.5f) * zone * grainA[i]
                    val soft = smooth(0.30f, 0.8f, fbm(fx * 0.8f + 4f, fy * 0.8f - t * 0.03f)) * zone * 0.35f
                    val b1 = band * (0.9f + 0.5f * energy)
                    val b3 = band * band * band * 0.35f
                    val bs = soft * 0.4f + halo * zone * 0.35f
                    cr += 0.06f * b1 + 0.55f * b3 + 0.06f * bs
                    cg += 0.32f * b1 + 0.80f * b3 + 0.32f * bs
                    cb += 1.0f * b1 + 1.0f * b3 + 1.0f * bs

                    val inv = 1f / max(rad, 1e-6f)
                    val nx = px * inv; val ny = py * inv
                    val light = (0.7f - 0.75f * (nx * 0.70f + ny * 0.72f)).coerceIn(0f, 1f)
                    val shell = smooth(0.66f, 0.995f, d)
                    val sh = shell * sqrt(shell) * light * linesA[i] * 0.92f
                    cr += 0.80f * sh; cg += 0.88f * sh; cb += 1.0f * sh
                    val sb = shell * (1f - light) * 0.25f
                    cr += 0.06f * sb; cg += 0.32f * sb; cb += sb
                    val wl = (nx * 0.75f + ny * 0.66f).coerceIn(0f, 1f)
                    val warm = wl * wl * wl * smooth(0.88f, 1f, d) * 0.85f
                    cr += warm; cg += 0.50f * warm; cb += 0.32f * warm
                }
                val or = cr.coerceIn(0f, 1f) * inside + 0.1f * glow
                val og = cg.coerceIn(0f, 1f) * inside + 0.3f * glow
                val ob = cb.coerceIn(0f, 1f) * inside + 1.0f * glow
                val al = (inside + glow).coerceIn(0f, 1f)
                // the colours above are premultiplied (scaled by coverage); Android's pixels are not
                val un = 1f / max(al, 1e-4f)
                out[i] = (to255(al) shl 24) or (to255(or * un) shl 16) or (to255(og * un) shl 8) or to255(ob * un)
            }
        }
    }

    companion object {
        const val ANGLES = 512
        private const val PI_F = Math.PI.toFloat()
        private fun to255(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

        /** A steady random number 0..1 for a grid point (integer hash: the same on every phone). */
        private fun hash(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777215f
        }
        private fun vnoise(x: Float, y: Float): Float {
            val fx0 = floor(x); val fy0 = floor(y)
            val ix = fx0.toInt(); val iy = fy0.toInt()
            val fx = x - fx0; val fy = y - fy0
            val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
            val a = hash(ix, iy); val b = hash(ix + 1, iy); val c = hash(ix, iy + 1); val d = hash(ix + 1, iy + 1)
            val ab = a + (b - a) * ux; val cd = c + (d - c) * ux
            return ab + (cd - ab) * uy
        }
        private fun fbm(x0: Float, y0: Float): Float {
            var x = x0; var y = y0; var v = 0f; var amp = 0.5f
            for (k in 0 until 3) { v += amp * vnoise(x, y); x = x * 2.03f + 1.7f; y = y * 2.03f + 9.2f; amp *= 0.5f }
            return v
        }
    }
}
