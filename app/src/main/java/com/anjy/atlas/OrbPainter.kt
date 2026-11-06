package com.anjy.atlas

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints Atlas's orb pixel by pixel on the processor: a line-for-line copy of the graphics-chip shader
 * (assets/orb.frag), used when a phone's chip can't draw it. Plain Kotlin with no Android parts, so it's
 * tested on a computer too. Not thread-safe per instance: one painter per drawing thread.
 */
class OrbPainter {
    private var cw = 0; private var ch = 0

    private fun prepare(w: Int, h: Int) {
        if (w == cw && h == ch) return
        cw = w; ch = h
    }

    private fun radiusAt(th: Float, t: Float, e: Float, lvl: Float): Float {
        var r = 1f + 0.045f * sin(2f * th + 0.6f + t * 0.21f) + 0.030f * sin(3f * th + 2.1f - t * 0.16f) +
            0.015f * sin(5f * th + 4.0f + t * 0.27f)
        val notch = 0.08f + 0.14f * sin(t * 0.09f)
        r -= 0.32f * bump(th, notch, 0.045f)
        r += 0.13f * bump(th, notch + 0.55f, 0.07f)
        r += 0.05f * bump(th, notch - 0.75f, 0.10f)
        r += 0.05f * bump(th, notch + 2.9f, 0.40f)
        r += (0.010f * e + 0.030f * lvl) * sin(6f * th - t * 3.1f) + 0.018f * lvl * sin(9f * th + t * 4.3f)
        return r
    }

    /**
     * Fills [out] (w×h, ARGB colour ints, not premultiplied) with the orb at [time] seconds. [energy] 0..1 stirs
     * it; [level] 0..1 swells it with the voice; [flow] is how far its inner light has flowed.
     */
    fun paint(out: IntArray, w: Int, h: Int, time: Float, energy: Float, level: Float, flow: Float = time * 0.045f) {
        prepare(w, h)
        val t = time; val e = energy; val lvl = level
        val side = min(w, h).toFloat()
        val S = side * 0.33f * (1f + 0.06f * lvl + 0.03f * e)
        val sway = 0.07f * sin(t * 0.13f)
        val cs = cos(sway); val sn = sin(sway)
        val aa = 0.9f / S
        val seed = floor(t * 8f)
        val lightDir = norm2(-0.72f, 0.69f); val warmDir = norm2(0.70f, -0.71f)
        for (y in 0 until h) {
            // GL counts rows from the bottom; match it so both painters draw the same picture
            val fy = (h - 1 - y) + 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val fx = x + 0.5f
                val ux = (fx - 0.5f * w) / S; val uy = (fy - 0.5f * h) / S
                val px = cs * ux + sn * uy
                val py = (-sn * ux + cs * uy) / 1.16f
                val rad = sqrt(px * px + py * py)
                val th = atan2(py, px)
                val R = radiusAt(th, t, e, lvl)
                val d = rad / R
                val inside = 1f - smooth(1f - aa, 1f + aa, d)
                val g = exp(-max(d - 1f, 0f) * 6f) * (1f - inside)
                val gm = 0.5f + 0.5f * sin(t * 0.21f + th)
                val gr = 0.12f + (0.40f - 0.12f) * gm; val gg = 0.32f + (0.28f - 0.32f) * gm; val gb = 1f
                val glowA = g * (0.10f + 0.32f * e + 0.25f * lvl)
                if (inside <= 0f && glowA < 0.003f) { out[i] = 0; continue }

                var cr = 0f; var cg = 0f; var cb = 0f
                if (inside > 0f) {
                    val dd = d.coerceIn(0f, 0.999f)
                    val dx = if (rad > 1e-4f) px / rad else 0f; val dy = if (rad > 1e-4f) py / rad else 0f
                    var nx = dx * dd * 1.25f; var ny = dy * dd * 1.25f; var nz = sqrt(1f - dd * dd)
                    val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
                    val fres = (1f - nz).pow(1.8f)

                    val qx = px * 0.95f; val qy = py * 0.95f
                    val wx = fbm3(qx + 1.7f + flow, qy + 9.2f - flow * 0.7f)
                    val wy = fbm3(qx + 8.3f - flow * 0.8f, qy + 2.8f + flow * 0.5f)
                    val f = fbm3(qx * 0.9f + 1.6f * wx + 3.1f, qy * 0.9f + 1.6f * wy + 1.3f)
                    val wave = 0.5f + 0.5f * cos(f * 30f + flow * 1.5f)
                    var stream = wave * wave * wave
                    var core = wave.pow(16f)
                    val strength = 0.35f + 0.65f * smooth(-0.12f, 0.18f, fbm3(qx * 1.3f + wx * 0.8f - 4f + flow * 0.5f, qy * 1.3f + wy * 0.8f + 6f + flow * 0.5f))
                    stream *= strength; core *= strength
                    val rimBand = smooth(0.40f, 0.88f, d)
                    val hollow = smooth(0.62f, 0f, d)
                    val hb = (d - 0.88f) / 0.065f
                    val hug = exp(-hb * hb) * smooth(-0.05f, 0.25f, fbm3(cos(th) * 1.8f + t * 0.05f + wx * 1.5f, sin(th) * 1.8f - t * 0.04f + wy * 1.5f))

                    val grain = hash1(floor(fx + seed * 37f), floor(fy + seed * 11f))
                    val glitter = 0.55f + 0.9f * grain
                    val stip = if (grain >= 0.62f) 1f else 0f

                    var L1 = (stream * 1.0f + core * 1.3f) * (0.30f + 0.70f * rimBand) + hug * 0.9f
                    L1 *= (1f - 0.6f * hollow)
                    L1 *= 0.9f + 0.35f * e + 0.30f * lvl

                    val tint = smooth(0.1f, 0.5f, fbm3(qx * 0.5f + 5f + t * 0.02f, qy * 0.5f - 2f + t * 0.02f)) * 0.55f
                    val lr = 0.03f + (0.42f - 0.03f) * tint; val lg = 0.26f + (0.30f - 0.26f) * tint; val lb = 1f

                    val haze = 1f - hollow
                    cr = 0.006f; cg = 0.010f + 0.01f * haze; cb = 0.035f + 0.04f * haze
                    val k = L1 * glitter
                    cr += lr * k; cg += lg * k; cb += lb * k
                    val c1 = core * rimBand * 0.30f * (0.5f + 0.9f * stip)
                    cr += 0.50f * c1; cg += 0.82f * c1; cb += c1
                    val c2 = L1 * L1 * L1 * 0.12f
                    cr += 0.50f * c2; cg += 0.82f * c2; cb += c2

                    val rib = 0.5f + 0.5f * sin(d * S * 2.7f)
                    val shell = smooth(0.80f, 0.99f, d) * (1f - 0.35f * smooth(0.985f, 1f, d))
                    val lit = smooth(-0.25f, 0.85f, dx * lightDir.first + dy * lightDir.second)
                    val sr = 0.30f + 0.50f * lit; val sg = 0.50f + 0.40f * lit; val sb = 1f
                    val sk = shell * (0.06f + 0.94f * lit * lit) * (0.30f + 0.70f * rib) * (0.55f + 0.45f * fres) * 0.85f
                    cr += sr * sk; cg += sg * sk; cb += sb * sk
                    val win = smooth(0.55f, 0.92f, d) * (1f - smooth(0.92f, 1f, d)) * lit * lit * lit * 0.12f
                    cr += 0.55f * win; cg += 0.75f * win; cb += win
                    val warm = smooth(0f, 0.85f, dx * warmDir.first + dy * warmDir.second)
                    val we = smooth(0.88f, 1f, d) * warm * 0.75f
                    cr += we; cg += 0.50f * we; cb += 0.36f * we
                    val ce = smooth(0.93f, 1f, d) * (1f - warm) * 0.25f
                    cr += 0.35f * ce; cg += 0.65f * ce; cb += ce
                    cr = 1f - exp(-cr * 1.9f); cg = 1f - exp(-cg * 1.9f); cb = 1f - exp(-cb * 1.9f)
                }
                val or = cr * inside + gr * glowA
                val og = cg * inside + gg * glowA
                val ob = cb * inside + gb * glowA
                val al = (inside + glowA).coerceIn(0f, 1f)
                // the shader's colours are premultiplied (scaled by coverage); Android's pixel ints are not
                val un = 1f / max(al, 1e-4f)
                out[i] = (to255(al) shl 24) or (to255(or * un) shl 16) or (to255(og * un) shl 8) or to255(ob * un)
            }
        }
    }

    companion object {
        private const val PI_F = Math.PI.toFloat()
        private fun to255(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        private fun norm2(x: Float, y: Float): Pair<Float, Float> { val l = sqrt(x * x + y * y); return x / l to y / l }
        private fun wrapA(x: Float): Float { var a = x; while (a > PI_F) a -= 2 * PI_F; while (a < -PI_F) a += 2 * PI_F; return a }
        private fun bump(th: Float, at: Float, w: Float): Float { val d = wrapA(th - at); return exp(-d * d / w) }

        /** The shader's integer hash, bit for bit (32-bit wraparound arithmetic). */
        private fun hashU(x: Int, y: Int): Int {
            var h = x * 1664525 + y * 1013904223
            h = h xor (h ushr 16); h *= 0x7feb352d; h = h xor (h ushr 15); h *= 0x846ca68b.toInt(); h = h xor (h ushr 16)
            return h
        }
        private fun h01(x: Int, y: Int) = (hashU(x + 4096, y + 4096) and 0xFFFFFF) / 16777215f
        private fun hash1(fx: Float, fy: Float) = (hashU(fx.toInt() + 65536, fy.toInt() + 65536) and 0xFFFFFF) / 16777215f

        private fun vnoise(x: Float, y: Float): Float {
            val fx0 = floor(x); val fy0 = floor(y)
            val ix = fx0.toInt(); val iy = fy0.toInt()
            val fx = x - fx0; val fy = y - fy0
            val ux = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f); val uy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
            val a = h01(ix, iy); val b = h01(ix + 1, iy); val c = h01(ix, iy + 1); val d = h01(ix + 1, iy + 1)
            val ab = a + (b - a) * ux; val cd = c + (d - c) * ux
            return ab + (cd - ab) * uy - 0.5f
        }
        /** GLSL's mat2(0.8, 0.6, -0.6, 0.8) * p is (0.8x - 0.6y, 0.6x + 0.8y): matrices are column-major there. */
        private fun fbm3(x0: Float, y0: Float): Float {
            var x = x0; var y = y0; var v = 0f; var a = 0.55f
            for (k in 0 until 3) {
                val rx = 0.8f * x - 0.6f * y; val ry = 0.6f * x + 0.8f * y
                v += a * vnoise(rx, ry)
                x = rx * 2.03f + 7.3f; y = ry * 2.03f + 7.3f; a *= 0.45f
            }
            return v
        }
    }
}
