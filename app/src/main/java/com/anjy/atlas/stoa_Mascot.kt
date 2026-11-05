package com.anjy.atlas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ember: the streak, as a character. It lives in the streak card, has moods that follow your day,
 * talks when you tap it, writes your reminders, and sits on your home screen.
 */
object Ember {
    const val NAME = "Ember"
    /** Sunglasses at a 30-day streak. */
    const val SHADES_AT = 30
    /** The streak always has a face (eyes and mouth, no eyebrows). */
    @Suppress("UNUSED_PARAMETER") fun hasFace(streak: Int, lit: Boolean) = true

    /**
     * The streak card's colour follows the sun: really light sky blue when the sun is highest (about 1pm),
     * easing to dark navy at the darkest point of the night (about 1am), and back again.
     */
    fun dayColors(now: LocalTime = LocalTime.now()): Pair<Int, Int> {
        val b = dayLevel(now)
        val color = mix(Color.parseColor("#6CC4FF"), Color.parseColor("#0E1B54"), b)   // light sky blue ↔ dark navy
        val glow = mix(Color.parseColor("#B5E3FF"), Color.parseColor("#2A4699"), b)
        return color to glow
    }

    /** How bright the day is, 0..1: follows the sun, brightest around 1pm, darkest around 1am. */
    fun dayLevel(now: LocalTime = LocalTime.now()): Float {
        val h = now.hour + now.minute / 60f
        return ((kotlin.math.cos(2 * Math.PI * (h - 13f) / 24f) + 1f) / 2f).toFloat()
    }

    fun dayTier(base: Tiers.Tier): Tiers.Tier { val (c, g) = dayColors(); return base.copy(color = c, glow = g) }

    /** A lit flame: the tier's own fire, or a plain orange flame if the tier has no colour yet. */
    fun litTier(base: Tiers.Tier): Tiers.Tier = if (base.min == 0) Tiers.list[2] else base

    /** Ember drawn into a picture (for the widget and notifications, where views can't live). */
    fun bitmap(c: Context, tier: Tiers.Tier, mood: String, lit: Boolean, wPx: Int, hPx: Int, streak: Int = 0): Bitmap {
        // streak off (0 and not done today): a plain grey flame with no face.
        // Not done yet but the streak's alive: grey with a negative face. 30+ days: sunglasses.
        val v = FlameView(c).apply { this.tier = tier; this.lit = lit; this.mood = mood; this.panic = mood == "panic"; dayTint = true
            face = hasFace(streak, lit); shades = streak >= SHADES_AT; tears = false; settled = true }
        v.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, wPx, hPx)
        val b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        // a few frames, so the eyes come to rest
        val cv = Canvas(b)
        repeat(8) { b.eraseColor(Color.TRANSPARENT); v.draw(cv) }
        return b
    }
}

/**
 * Today's button on the path: a raised coin inside a segmented progress ring, with bolts of
 * electricity crackling across it every few seconds, the way a "do this now" button should feel.
 */
class TodayNodeView(context: Context, private val face: Int, private val track: Int, private val label: String) : View(context) {
    var progress = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var segments = 4
    var still = false
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val start = System.nanoTime()
    private var running = false

    private class Bolt(val pts: List<FloatArray>, val born: Float, val life: Float)
    private val bolts = mutableListOf<Bolt>()
    private var nextBurst = 0.9f
    private val rnd = Random(System.nanoTime())

    private fun now() = (System.nanoTime() - start) / 1e9f

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    private fun makeBolt(cx: Float, cy: Float, r: Float, born: Float): Bolt {
        val a = rnd.nextFloat() * 6.283f
        val b = a + Math.PI.toFloat() + (rnd.nextFloat() - 0.5f) * 1.4f
        val r0 = r * (1.05f + rnd.nextFloat() * 0.25f); val r1 = r * (1.05f + rnd.nextFloat() * 0.25f)
        val x0 = cx + cos(a) * r0; val y0 = cy + sin(a) * r0
        val x1 = cx + cos(b) * r1; val y1 = cy + sin(b) * r1
        val n = 7
        val nx = -(y1 - y0); val ny = x1 - x0
        val len = kotlin.math.hypot(nx, ny).coerceAtLeast(1f)
        val pts = (0..n).map { i ->
            val f = i / n.toFloat()
            val j = if (i == 0 || i == n) 0f else (rnd.nextFloat() - 0.5f) * r * 0.55f
            floatArrayOf(x0 + (x1 - x0) * f + nx / len * j, y0 + (y1 - y0) * f + ny / len * j)
        }
        return Bolt(pts, born, 0.75f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = now()
        val cx = w / 2; val depth = 6 * d
        val size = minOf(w, h)
        val ringR = size / 2 - 5 * d
        val coinR = size * 0.33f
        val cy = h / 2 - depth / 2

        // segmented progress ring
        ring.strokeWidth = 6 * d
        rect.set(cx - ringR, cy + depth / 2 - ringR, cx + ringR, cy + depth / 2 + ringR)
        val n = segments.coerceIn(1, 10)
        val gap = 14f
        val seg = 360f / n
        val filled = progress * n
        for (i in 0 until n) {
            val a0 = -90f + i * seg + gap / 2
            ring.color = track
            c.drawArc(rect, a0, seg - gap, false, ring)
            val f = (filled - i).coerceIn(0f, 1f)
            if (f > 0f) { ring.color = mix(Color.WHITE, face, 0.38f); c.drawArc(rect, a0, (seg - gap) * f, false, ring) }
        }

        // raised coin: darker rim underneath, face lit from above; it sinks when pressed
        val press = if (isPressed) depth * 0.8f else 0f
        p.shader = null; p.color = mix(Color.BLACK, face, 0.32f)
        c.drawCircle(cx, cy + depth, coinR, p)
        p.shader = LinearGradient(0f, cy - coinR + press, 0f, cy + coinR + press, mix(Color.WHITE, face, 0.22f), face, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + press, coinR, p)
        p.shader = null
        // shine
        p.color = Color.argb(60, 255, 255, 255)
        c.drawOval(cx - coinR * 0.55f, cy + press - coinR * 0.82f, cx + coinR * 0.25f, cy + press - coinR * 0.45f, p)
        txt.textSize = coinR * 0.95f
        val fm = txt.fontMetrics
        c.drawText(label, cx, cy + press - (fm.ascent + fm.descent) / 2, txt)

        // electricity
        if (!still) {
            if (t > nextBurst) {
                bolts.add(makeBolt(cx, cy, coinR, t))
                if (rnd.nextFloat() < 0.75f) bolts.add(makeBolt(cx, cy, coinR, t + 0.45f + rnd.nextFloat() * 0.3f))
                nextBurst = t + 2.2f + rnd.nextFloat() * 1.8f
            }
            val it = bolts.iterator()
            while (it.hasNext()) {
                val b = it.next()
                val k = (t - b.born) / b.life
                if (k < 0f) continue
                if (k > 1f) { it.remove(); continue }
                drawBolt(c, b, k)
            }
        }
        if (running && !still) postInvalidateOnAnimation()
    }

    private fun drawBolt(c: Canvas, b: Bolt, k: Float) {
        val pts = b.pts
        val n = pts.size - 1
        // grow 0..0.3, flicker 0.3..0.55, break apart and fade 0.55..1
        val head = (k / 0.3f).coerceAtMost(1f) * n
        val flick = if (k in 0.3f..0.55f) (if (((k * 40).toInt() % 2) == 0) 1f else 0.55f) else 1f
        val fade = if (k > 0.55f) 1f - (k - 0.55f) / 0.45f else 1f
        val path = Path()
        val broken = k > 0.55f
        for (i in 0 until n) {
            if (i >= head) break
            if (broken && (i % 2 == 1)) continue   // segments drop out as it breaks
            val a = pts[i]; val e = pts[i + 1]
            val f = (head - i).coerceAtMost(1f)
            val ex = a[0] + (e[0] - a[0]) * f; val ey = a[1] + (e[1] - a[1]) * f
            if (broken) {
                // shrink each surviving piece toward its middle
                val mx = (a[0] + ex) / 2; val my = (a[1] + ey) / 2; val s = fade
                path.moveTo(mx + (a[0] - mx) * s, my + (a[1] - my) * s); path.lineTo(mx + (ex - mx) * s, my + (ey - my) * s)
            } else { path.moveTo(a[0], a[1]); path.lineTo(ex, ey) }
        }
        val al = (flick * fade).coerceIn(0f, 1f)
        bolt.color = Color.argb((70 * al).toInt(), 120, 230, 255); bolt.strokeWidth = 9 * d; c.drawPath(path, bolt)
        bolt.color = Color.argb((170 * al).toInt(), 150, 240, 255); bolt.strokeWidth = 4.5f * d; c.drawPath(path, bolt)
        bolt.color = Color.argb((255 * al).toInt(), 235, 252, 255); bolt.strokeWidth = 2f * d; c.drawPath(path, bolt)
        if (broken) {
            // sparks where it snapped
            p.color = Color.argb((220 * fade).toInt(), 200, 248, 255)
            for (i in 1 until n step 2) c.drawCircle(pts[i][0], pts[i][1], 1.6f * d * fade, p)
        }
    }
}
