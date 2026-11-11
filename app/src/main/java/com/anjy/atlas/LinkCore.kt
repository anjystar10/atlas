package com.anjy.atlas

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Device Link rules with no Android in them (the same as desktop/renderer/linkcore.js on the PC; tools/link-fixtures.json
 * checks both agree): which messages are ours, where a remote tap lands, and what a remote input may do here.
 *
 * A message (before encryption): { v: 1, id, k: kind, from, to, at, ...fields }. Kinds are listed in Link.kt.
 */
object LinkCore {
    const val VERSION = 1
    /** A device counts as online when it checked in within this long. */
    const val ONLINE_MS = 75_000L
    /** A remote-screen session ends when the viewer hasn't been heard from for this long. */
    const val SESSION_IDLE_MS = 20_000L
    private val ACTS = setOf("back", "home", "recents", "notifications", "lock", "volume_up", "volume_down", "mute", "show_desktop", "task_manager")

    /** Whether [msg] is a message of ours for device [me] (right version, has a kind and an id, addressed here). */
    fun accept(msg: JSONObject?, me: String): Boolean {
        if (msg == null || msg.optInt("v", -1) != VERSION || msg.optString("k").isEmpty() || msg.optString("id").isEmpty()) return false
        val to = msg.optString("to")
        return to.isEmpty() || to == "*" || to == me
    }

    /** JavaScript's Math.round (halves go up), so both apps land on the same pixel. */
    private fun jsRound(v: Double): Long = Math.floor(v + 0.5).roundToLong()

    /** A point given as fractions of the frame (0..1) in real screen pixels, clamped inside the screen. */
    fun toScreen(fx: Double, fy: Double, realW: Int, realH: Int): Pair<Int, Int> {
        fun cl(v: Double) = if (v.isFinite()) min(1.0, max(0.0, v)) else 0.5
        return Pair(min(realW - 1L, jsRound(cl(fx) * realW)).toInt(), min(realH - 1L, jsRound(cl(fy) * realH)).toInt())
    }

    /** Where a touch at (px, py) inside a picture drawn at (drawW × drawH) lands, as fractions; null when outside it. */
    fun toFraction(px: Float, py: Float, drawW: Float, drawH: Float): Pair<Double, Double>? {
        if (drawW <= 0f || drawH <= 0f || px < 0f || py < 0f || px > drawW || py > drawH) return null
        return Pair((px / drawW).toDouble(), (py / drawH).toDouble())
    }

    fun sessionAlive(lastKeep: Long, now: Long) = now - lastKeep < SESSION_IDLE_MS

    private fun num(o: JSONObject, k: String, d: Double = Double.NaN): Double =
        if (!o.has(k) || o.isNull(k)) d else o.opt(k).let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() ?: Double.NaN }

    /** What a remote input turns into on this screen (pixels); null when it's malformed or not allowed. */
    fun inputPlan(inp: JSONObject?, realW: Int, realH: Int): JSONObject? {
        val t = inp?.optString("t").orEmpty()
        if (inp == null || t.isEmpty()) return null
        fun p(xk: String, yk: String, d: Double = Double.NaN) = toScreen(num(inp, xk, d), num(inp, yk, d), realW, realH)
        return when (t) {
            "tap", "long", "double", "right" -> p("x", "y").let { JSONObject().put("t", t).put("x", it.first).put("y", it.second) }
            "drag" -> { val a = p("x", "y"); val b = p("x2", "y2"); JSONObject().put("t", "drag").put("x", a.first).put("y", a.second).put("x2", b.first).put("y2", b.second) }
            "scroll" -> {
                val a = p("x", "y", 0.5)
                val dy = num(inp, "dy").let { if (it.isFinite()) jsRound(it).coerceIn(-20, 20).toInt() else 0 }
                if (dy == 0) null else JSONObject().put("t", "scroll").put("x", a.first).put("y", a.second).put("dy", dy)
            }
            "text" -> inp.opt("text")?.toString()?.take(2000)?.takeIf { it.isNotEmpty() && !inp.isNull("text") }?.let { JSONObject().put("t", "text").put("text", it) }
            "key" -> inp.optString("key").lowercase().take(40).takeIf { Regex("^[a-z0-9+]+$").matches(it) }?.let { JSONObject().put("t", "key").put("key", it) }
            "act" -> inp.optString("act").takeIf { it in ACTS }?.let { JSONObject().put("t", "act").put("act", it) }
            else -> null
        }
    }
}
