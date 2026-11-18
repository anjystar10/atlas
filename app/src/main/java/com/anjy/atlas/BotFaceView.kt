package com.anjy.atlas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A bot's face (BotFace): its shape filled with its colour pair as a lit glass orb, a white glyph for its job, and its
 * state drawn on the face itself — working: a light orbiting it; waiting for you: a soft ring in the warm colour that
 * breathes; paused: dimmed. No letters, no emoji. Animations only run while the view is on screen, and not at all when
 * the phone's animations are off.
 */
class BotFaceView(ctx: Context, bot: JSONObject?, private val warm: Int, tone: String? = null) : View(ctx) {
    private val face = BotFace.faceOf(bot)
    private val light = Color.parseColor(BotFace.COLORS[face.color].first)
    private val deep = Color.parseColor(BotFace.COLORS[face.color].second)
    var tone: String? = tone
        set(v) { field = v; syncAnim(); invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shape = Path()
    private val glyph = Path()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w, h).toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = s * 0.40f   // leaves room for the ring and the orbiting light
        buildShape(cx, cy, r)
        fill.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.55f, intArrayOf(light, deep, blend(deep, Color.BLACK, 0.35f)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        shine.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 0.75f, intArrayOf(withAlpha(Color.WHITE, 120), withAlpha(Color.WHITE, 0)), null, Shader.TileMode.CLAMP)
        rim.strokeWidth = s * 0.018f; rim.color = withAlpha(Color.WHITE, 70)
        ink.strokeWidth = s * 0.062f
        ring.strokeWidth = s * 0.045f
        buildGlyph(cx, cy, r * 0.52f)
    }

    private fun buildShape(cx: Float, cy: Float, r: Float) {
        shape.reset()
        when (face.shape) {
            "hex" -> polygon(shape, cx, cy, r * 1.06f, 6, -PI / 2, r * 0.18f)
            "diamond" -> polygon(shape, cx, cy, r * 1.12f, 4, -PI / 2, r * 0.22f)
            "squircle" -> shape.addRoundRect(RectF(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f), r * 0.42f, r * 0.42f, Path.Direction.CW)
            "petal" -> {
                val k = r * 0.52f
                for (i in 0 until 4) { val a = PI / 4 + i * PI / 2; shape.op(Path().apply { addCircle(cx + (cos(a) * k * 0.62).toFloat(), cy + (sin(a) * k * 0.62).toFloat(), k * 1.05f, Path.Direction.CW) }, Path.Op.UNION) }
                shape.op(Path().apply { addCircle(cx, cy, r * 0.8f, Path.Direction.CW) }, Path.Op.UNION)
            }
            else -> shape.addCircle(cx, cy, r, Path.Direction.CW)
        }
    }

    /** A regular polygon with softly rounded corners. */
    private fun polygon(p: Path, cx: Float, cy: Float, r: Float, n: Int, start: Double, round: Float) {
        val pts = (0 until n).map { i -> val a = start + i * 2 * PI / n; (cx + cos(a) * r).toFloat() to (cy + sin(a) * r).toFloat() }
        for (i in 0 until n) {
            val (x, y) = pts[i]; val (px, py) = pts[(i + n - 1) % n]; val (nx, ny) = pts[(i + 1) % n]
            fun toward(ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> { val dx = bx - ax; val dy = by - ay; val d = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(); return (ax + dx / d * round) to (ay + dy / d * round) }
            val (ax, ay) = toward(x, y, px, py); val (bx, by) = toward(x, y, nx, ny)
            if (i == 0) p.moveTo(ax, ay) else p.lineTo(ax, ay)
            p.quadTo(x, y, bx, by)
        }
        p.close()
    }

    private fun buildGlyph(cx: Float, cy: Float, g: Float) {
        glyph.reset()
        val p = glyph
        when (face.motif) {
            "code" -> { p.moveTo(cx - g * 0.35f, cy - g * 0.55f); p.lineTo(cx - g, cy); p.lineTo(cx - g * 0.35f, cy + g * 0.55f)
                p.moveTo(cx + g * 0.35f, cy - g * 0.55f); p.lineTo(cx + g, cy); p.lineTo(cx + g * 0.35f, cy + g * 0.55f)
                p.moveTo(cx + g * 0.15f, cy - g * 0.7f); p.lineTo(cx - g * 0.15f, cy + g * 0.7f) }
            "build" -> { // a cube
                p.moveTo(cx, cy - g * 0.85f); p.lineTo(cx + g * 0.8f, cy - g * 0.42f); p.lineTo(cx + g * 0.8f, cy + g * 0.48f); p.lineTo(cx, cy + g * 0.9f)
                p.lineTo(cx - g * 0.8f, cy + g * 0.48f); p.lineTo(cx - g * 0.8f, cy - g * 0.42f); p.close()
                p.moveTo(cx - g * 0.8f, cy - g * 0.42f); p.lineTo(cx, cy); p.lineTo(cx + g * 0.8f, cy - g * 0.42f); p.moveTo(cx, cy); p.lineTo(cx, cy + g * 0.9f) }
            "chart" -> { p.moveTo(cx - g * 0.9f, cy + g * 0.55f); p.lineTo(cx - g * 0.3f, cy - g * 0.05f); p.lineTo(cx + g * 0.1f, cy + g * 0.3f); p.lineTo(cx + g * 0.85f, cy - g * 0.5f)
                p.moveTo(cx + g * 0.4f, cy - g * 0.55f); p.lineTo(cx + g * 0.88f, cy - g * 0.55f); p.lineTo(cx + g * 0.88f, cy - g * 0.08f) }
            "wave" -> { p.moveTo(cx - g * 0.95f, cy); for (i in 1..24) { val x = -0.95f + i * 1.9f / 24; p.lineTo(cx + x * g, cy - (sin(x * PI * 1.6) * 0.45).toFloat() * g) } }
            "mail" -> { p.addRoundRect(RectF(cx - g * 0.9f, cy - g * 0.6f, cx + g * 0.9f, cy + g * 0.6f), g * 0.16f, g * 0.16f, Path.Direction.CW)
                p.moveTo(cx - g * 0.8f, cy - g * 0.48f); p.lineTo(cx, cy + g * 0.1f); p.lineTo(cx + g * 0.8f, cy - g * 0.48f) }
            "play" -> { p.moveTo(cx - g * 0.42f, cy - g * 0.7f); p.lineTo(cx + g * 0.75f, cy); p.lineTo(cx - g * 0.42f, cy + g * 0.7f); p.close() }
            "search" -> { p.addCircle(cx - g * 0.18f, cy - g * 0.18f, g * 0.55f, Path.Direction.CW); p.moveTo(cx + g * 0.22f, cy + g * 0.22f); p.lineTo(cx + g * 0.8f, cy + g * 0.8f) }
            "coins" -> { p.addCircle(cx, cy, g * 0.78f, Path.Direction.CW); p.moveTo(cx, cy - g * 0.42f); p.lineTo(cx, cy + g * 0.42f)
                p.moveTo(cx + g * 0.28f, cy - g * 0.22f); p.lineTo(cx - g * 0.12f, cy - g * 0.22f); p.quadTo(cx - g * 0.32f, cy - g * 0.22f, cx - g * 0.32f, cy - g * 0.05f); p.quadTo(cx - g * 0.32f, cy + g * 0.08f, cx, cy + g * 0.08f)
                p.quadTo(cx + g * 0.3f, cy + g * 0.08f, cx + g * 0.3f, cy + g * 0.26f); p.lineTo(cx - g * 0.28f, cy + g * 0.26f) }
            "calendar" -> { p.addRoundRect(RectF(cx - g * 0.8f, cy - g * 0.62f, cx + g * 0.8f, cy + g * 0.75f), g * 0.14f, g * 0.14f, Path.Direction.CW)
                p.moveTo(cx - g * 0.8f, cy - g * 0.2f); p.lineTo(cx + g * 0.8f, cy - g * 0.2f)
                p.moveTo(cx - g * 0.4f, cy - g * 0.85f); p.lineTo(cx - g * 0.4f, cy - g * 0.45f); p.moveTo(cx + g * 0.4f, cy - g * 0.85f); p.lineTo(cx + g * 0.4f, cy - g * 0.45f) }
            else -> { // a four-point spark
                p.moveTo(cx, cy - g); p.quadTo(cx + g * 0.12f, cy - g * 0.12f, cx + g, cy); p.quadTo(cx + g * 0.12f, cy + g * 0.12f, cx, cy + g)
                p.quadTo(cx - g * 0.12f, cy + g * 0.12f, cx - g, cy); p.quadTo(cx - g * 0.12f, cy - g * 0.12f, cx, cy - g); p.close() }
        }
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        val off = tone == "off"
        val a = if (off) 0.45f else 1f
        if (tone == "warn") {
            val breathe = 0.55f + 0.45f * (0.5f + 0.5f * sin(phase * 2 * PI).toFloat())
            ring.color = withAlpha(warm, (255 * breathe).toInt())
            c.drawCircle(cx, cy, s * 0.47f, ring)
        }
        val save = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt())
        c.drawPath(shape, fill)
        c.drawPath(shape, shine)
        c.drawPath(shape, rim)
        if (face.motif == "play" || face.motif == "spark") c.drawPath(glyph, inkFill) else c.drawPath(glyph, ink)
        c.restoreToCount(save)
        if (tone == "live") {
            val ang = phase * 2 * PI - PI / 2
            val rr = s * 0.45f
            val x = cx + (cos(ang) * rr).toFloat(); val y = cy + (sin(ang) * rr).toFloat()
            dot.color = Color.WHITE; dot.setShadowLayer(s * 0.06f, 0f, 0f, light)
            c.drawCircle(x, y, s * 0.055f, dot)
        }
    }

    private fun syncAnim() {
        val want = isAttachedToWindow && (tone == "live" || tone == "warn") && ValueAnimator.areAnimatorsEnabled()
        if (want && anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (tone == "live") 2400 else 2000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!want) { anim?.cancel(); anim = null }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnim() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}
