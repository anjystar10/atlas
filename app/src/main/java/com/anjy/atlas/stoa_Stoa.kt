package com.anjy.atlas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** One shared tile of fine noise: the grain that keeps flat black and flat stone from looking digital. */
object Grain {
    private var tile: Bitmap? = null
    fun shader(): BitmapShader {
        val b = tile ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { bmp ->
            val r = java.util.Random(7)
            val px = IntArray(160 * 160) { val v = r.nextInt(256); Color.argb(255, v, v, v) }
            bmp.setPixels(px, 0, 160, 0, 0, 160, 160); tile = bmp
        }
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

/** Behind every page: the ground colour, one faint warm light from the top corner, and grain. */
class Backdrop(context: Context, private val bg: Int, private val dark: Boolean, private val warm: Int) : View(context) {
    var scrollRef: android.widget.ScrollView? = null
    var still = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint().apply { shader = Grain.shader() }
    private var glow: Shader? = null
    fun pause() {}
    fun resume() {}
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val c = if (dark) warm else Color.WHITE
        val a = if (dark) 34 else 70
        glow = RadialGradient(w * 0.92f, -h * 0.02f, w * 0.95f, Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)), Shader.TileMode.CLAMP)
    }
    override fun onDraw(cv: Canvas) {
        cv.drawColor(bg)
        p.shader = glow; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        // plain see-through grain: a MULTIPLY blend here also multiplied the window's alpha and turned the
        // whole Day page black on real phones
        g.alpha = if (dark) 9 else 6
        g.xfermode = null
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), g)
    }
}

/**
 * A surface. Night: a near-black panel with a hairline edge. Day: frosted glass, whiter at the top, with a bright rim
 * edge so it reads as raised.
 */
class StoneDrawable(private val fill: Int, private val radius: Float, private val dark: Boolean, private val density: Float, private val edge: Int? = null,
                    private val page: Int? = null) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        if (dark) {
            // a dark slab: faintly lighter at the top, a soft light along its top edge, an edge that fades downward
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, tint(fill, Color.WHITE, 0.05f), tint(fill, Color.BLACK, 0.15f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, p)
            p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height() * 0.35f, 90 * density), Color.argb(14, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, p); p.shader = null
            s.strokeWidth = density
            if (edge != null) { s.shader = null; s.color = edge }
            else s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(52, 255, 255, 255), Color.argb(16, 255, 255, 255), Color.argb(10, 255, 255, 255)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            r.inset(density / 2, density / 2); c.drawRoundRect(r, radius, radius, s); s.shader = null
        } else {
            val a = glassAlpha(fill, page)
            if (a != null) drawGlass(c, a) else {
                // a coloured fill: a plain panel, a touch lighter at the top and barely shaded toward its foot
                p.shader = LinearGradient(0f, r.top, 0f, r.bottom, tint(fill, Color.WHITE, 0.10f), tint(fill, Color.BLACK, 0.02f), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, radius, radius, p); p.shader = null
                s.strokeWidth = density; s.shader = null
                s.color = edge ?: Color.argb(26, 0, 0, 0)
                r.inset(density / 2, density / 2); c.drawRoundRect(r, radius, radius, s)
            }
        }
    }

    /**
     * By day a pale fill is frosted glass: white laid over whatever is behind it, as much white as it takes for the
     * pane to look like [fill] over the page. Null when the fill is no lighter than the page (a coloured panel).
     */
    private fun glassAlpha(fill: Int, page: Int?): Float? {
        if (page == null) return null
        val f = Color.red(fill) + Color.green(fill) + Color.blue(fill)
        val b = Color.red(page) + Color.green(page) + Color.blue(page)
        if (f <= b + 6 || b >= 760) return null
        return ((f - b).toFloat() / (765 - b)).coerceIn(0.12f, 0.92f)
    }

    /** A pane of frosted glass: thicker (whiter) at the top, light caught along its top, a bright rim fading downward. */
    private fun drawGlass(c: Canvas, a: Float) {
        fun w(alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, w(a * 1.18f), w(a * 0.84f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        // the light across the top
        p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height() * 0.4f, 110 * density), w(0.30f), w(0f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p); p.shader = null
        // the rim: bright white on top, thinning toward the foot, where a faint warm line gives the pane its thickness
        s.strokeWidth = density
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(w(0.95f), w(0.45f), Color.argb(34, 90, 60, 30)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val rr = RectF(r); rr.inset(density / 2, density / 2); c.drawRoundRect(rr, radius, radius, s); s.shader = null
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, radius) }
}

/** From [a] toward [b] by [t] (0 = a, 1 = b), alpha included. */
internal fun tint(a: Int, b: Int, t: Float) = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

/**
 * Each kind of number gets its own light: a whole glowing tile at night, falling off into black; by day a tint of
 * the same colour, strongest at its heart.
 */
enum class Glow(val night: IntArray, val stops: FloatArray, val cx: Float, val cy: Float, val orb: Int) {
    STEPS(intArrayOf(0xFF86F2D3.toInt(), 0xFF1FA08B.toInt(), 0xFF0D4B4C.toInt(), 0xFF061517.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), 0.1f, 1.1f, 0xFF2BC4A6.toInt()),
    FOOD(intArrayOf(0xFFFFE29A.toInt(), 0xFFF0A126.toInt(), 0xFF8A4309.toInt(), 0xFF1E0D03.toInt()), floatArrayOf(0f, 0.28f, 0.62f, 1f), 0.9f, 1.0f, 0xFFF2A62B.toInt()),
    LEAGUE(intArrayOf(0xFFD9DEFF.toInt(), 0xFF7A7CF0.toInt(), 0xFF2D2789.toInt(), 0xFF0B0921.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), 1.0f, 0.0f, 0xFF6E70EE.toInt()),
    HEART(intArrayOf(0xFFFF9CC8.toInt(), 0xFFE0397F.toInt(), 0xFF6B0F3D.toInt(), 0xFF1B0410.toInt()), floatArrayOf(0f, 0.32f, 0.66f, 1f), 0.0f, 0.0f, 0xFFE7458C.toInt()),
    EMBER(intArrayOf(0xFFFFD27A.toInt(), 0xFFFF7A2E.toInt(), 0xFF7A1A0C.toInt(), 0xFF1A0503.toInt()), floatArrayOf(0f, 0.3f, 0.64f, 1f), 0.9f, 0.0f, 0xFFFF8A3D.toInt()),
    // for the choice tiles in setup
    LIME(intArrayOf(0xFFE6F5A6.toInt(), 0xFF93BE2E.toInt(), 0xFF3A520E.toInt(), 0xFF0D1503.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), 0.1f, 1.1f, 0xFF97C234.toInt()),
    CORAL(intArrayOf(0xFFFFC7AE.toInt(), 0xFFEE6A43.toInt(), 0xFF7A2312.toInt(), 0xFF1C0704.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), 0.9f, 1.0f, 0xFFF07049.toInt()),
    SKY(intArrayOf(0xFFBFE6FF.toInt(), 0xFF3A9BE8.toInt(), 0xFF123F73.toInt(), 0xFF040F1E.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), 0.1f, 0.0f, 0xFF3FA2EE.toInt())
}

class GlowDrawable(private val kind: Glow, private val dark: Boolean, private val radius: Float, private val density: Float,
                   private val cx: Float = kind.cx, private val cy: Float = kind.cy) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val w = r.width(); val h = r.height()
        p.shader = RadialGradient(r.left + w * cx, r.top + h * cy, max(w, h) * 1.25f, if (dark) kind.night else day(kind), kind.stops, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        if (!dark) {
            // by day: a clear sheen over the top and a bright rim, so the colour reads as a lit tile, not flat paint
            p.shader = LinearGradient(0f, r.top, 0f, r.top + h * 0.55f, Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, p)
            s.strokeWidth = density
            s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            val e = RectF(r); e.inset(density / 2, density / 2); c.drawRoundRect(e, radius, radius, s); s.shader = null
        }
        p.shader = null
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, radius) }

    companion object {
        /**
         * The day tile: the tile's colour with some white in it, most colour at its heart and lightest across the
         * tile. Between a pastel wash (too faint) and full strength (too loud).
         */
        val DAY_WHITE = floatArrayOf(0.10f, 0.24f, 0.36f, 0.43f)
        fun day(kind: Glow) = IntArray(4) { tint(kind.orb, Color.WHITE, DAY_WHITE[it]) }
        /** Words on a day tile: a deep shade of its own colour. */
        fun dayInk(kind: Glow) = Color.rgb((Color.red(kind.orb) * 0.32f).toInt(), (Color.green(kind.orb) * 0.32f).toInt(), (Color.blue(kind.orb) * 0.32f).toInt())
    }
}

/**
 * The streak as a colonnade: one column per day. A kept day stands full; a Low Day is a slimmer column
 * (it still holds the roof); a Rest Day is outlined; a missed day is a broken stump; today, still open,
 * is dashed in Ember's colour. A beam runs over the unbroken run that ends today.
 */
class ColonnadeView(context: Context, private val ink: Int, private val shadow: Int, private val ember: Int, private val dark: Boolean) : View(context) {
    enum class Day { KEPT, LOW, REST, MISSED, OPEN, FUTURE }
    var days: List<Day> = emptyList(); set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val n = days.size; if (n == 0 || width == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val step = w / n
        val cw = min(step * 0.72f, h * 0.3f)
        val beam = h * 0.055f; val top = beam + h * 0.035f
        val cap = h * 0.07f; val base = h * 0.08f
        val marble = LinearGradient(0f, 0f, cw, 0f, intArrayOf(shadow, ink, shadow), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.MIRROR)
        // the beam: over the run of standing days that ends at the newest kept day
        val lastStanding = days.indexOfLast { it == Day.KEPT || it == Day.LOW || it == Day.REST }
        var first = lastStanding
        while (first > 0 && days[first - 1] != Day.MISSED && days[first - 1] != Day.FUTURE && days[first - 1] != Day.OPEN) first--
        if (lastStanding >= 0) {
            p.shader = null; p.color = ink
            val x0 = first * step + (step - cw) / 2 - cw * 0.08f; val x1 = lastStanding * step + (step + cw) / 2 + cw * 0.08f
            r.set(x0, 0f, x1, beam); c.drawRoundRect(r, beam / 2.6f, beam / 2.6f, p)
        }
        for ((i, d) in days.withIndex()) {
            val cx = i * step + step / 2
            when (d) {
                Day.KEPT, Day.LOW -> {
                    val k = if (d == Day.LOW) 0.46f else 1f
                    val sw = cw * 0.62f * k; val capW = cw * (if (d == Day.LOW) 0.78f else 1f)
                    c.save(); c.translate(cx - cw / 2, 0f); p.shader = marble
                    r.set((cw - capW) / 2, top, (cw + capW) / 2, top + cap); c.drawRoundRect(r, cap / 3, cap / 3, p)
                    r.set((cw - sw) / 2, top + cap * 1.35f, (cw + sw) / 2, h - base * 1.35f); c.drawRoundRect(r, sw / 8, sw / 8, p)
                    r.set((cw - capW * 0.9f) / 2, h - base, (cw + capW * 0.9f) / 2, h); c.drawRoundRect(r, base / 3, base / 3, p)
                    c.restore()
                }
                Day.REST, Day.OPEN, Day.FUTURE -> {
                    line.color = when (d) { Day.OPEN -> ember; Day.REST -> shadow; else -> tint(shadow, Color.TRANSPARENT, 0.6f) }
                    line.strokeWidth = max(1f, cw * 0.07f)
                    line.pathEffect = if (d == Day.OPEN) DashPathEffect(floatArrayOf(cw * 0.16f, cw * 0.16f), 0f) else null
                    val sw = cw * 0.62f; val x = cx - cw / 2; val hs = line.strokeWidth / 2
                    r.set(x + hs, top + hs, x + cw - hs, top + cap - hs); c.drawRoundRect(r, cap / 3, cap / 3, line)
                    r.set(cx - sw / 2 + hs, top + cap * 1.35f, cx + sw / 2 - hs, h - base * 1.35f); c.drawRoundRect(r, sw / 8, sw / 8, line)
                    r.set(x + cw * 0.05f + hs, h - base + hs, x + cw * 0.95f - hs, h - hs); c.drawRoundRect(r, base / 3, base / 3, line)
                }
                Day.MISSED -> {
                    // a broken stump: the base and a short shaft with a jagged top
                    p.shader = null; p.color = tint(shadow, Color.TRANSPARENT, 0.45f)
                    val sw = cw * 0.62f; val x = cx - cw / 2
                    r.set(x + cw * 0.05f, h - base, x + cw * 0.95f, h); c.drawRoundRect(r, base / 3, base / 3, p)
                    val y0 = h - base * 1.35f; val y1 = y0 - (h - top) * 0.32f
                    val path = Path().apply {
                        moveTo(cx - sw / 2, y0); lineTo(cx - sw / 2, y1 + sw * 0.3f); lineTo(cx - sw * 0.1f, y1)
                        lineTo(cx + sw * 0.12f, y1 + sw * 0.45f); lineTo(cx + sw / 2, y1 + sw * 0.15f); lineTo(cx + sw / 2, y0); close()
                    }
                    c.drawPath(path, p)
                }
            }
        }
    }
}

/**
 * Slide to confirm: drag the knob to the end of the track. Harder to trigger by accident than a tap,
 * so it's what logs a set. TalkBack users double-tap it (performClick confirms).
 */
class SlideToConfirm(context: Context, private val track: Int, private val knob: Int, private val ink: Int, private val labelColor: Int,
                     private val face: android.graphics.Typeface) : View(context) {
    var label = ""; set(v) { field = v; contentDescription = v; invalidate() }
    var onConfirm: () -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    private var x = 0f
    private var dragging = false
    private var grabAt = 0f
    private var busy = false
    init { isClickable = true; isFocusable = true }
    private fun pad() = height * 0.1f
    private fun knobD() = height - pad() * 2
    private fun maxX() = (width - knobD() - pad() * 2).coerceAtLeast(1f)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = track; r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, p)
        val k = (x / maxX()).coerceIn(0f, 1f)
        t.typeface = face; t.textSize = h * 0.23f; t.color = labelColor; t.alpha = (255 * (1 - k * 1.4f)).toInt().coerceIn(0, 255)
        c.drawText(label, w / 2 + knobD() * 0.3f, h / 2 - (t.descent() + t.ascent()) / 2, t)
        s.color = labelColor; s.strokeWidth = h * 0.028f
        for (i in 0..2) {
            s.alpha = ((0.75f - i * 0.25f) * 255 * (1 - k)).toInt().coerceIn(0, 255)
            val cx = w - h * 0.62f + i * h * 0.16f; val cy = h / 2; val a = h * 0.07f
            c.drawLine(cx - a, cy - a * 1.4f, cx + a * 0.6f, cy, s); c.drawLine(cx + a * 0.6f, cy, cx - a, cy + a * 1.4f, s)
        }
        val d = knobD(); val kx = pad() + x; val ky = pad()
        p.color = knob; c.drawCircle(kx + d / 2, ky + d / 2, d / 2, p)
        s.color = ink; s.alpha = 255; s.strokeWidth = d * 0.06f
        val cx = kx + d / 2; val cy = ky + d / 2; val a = d * 0.17f
        c.drawLine(cx - a, cy, cx + a, cy, s); c.drawLine(cx + a * 0.15f, cy - a * 0.85f, cx + a, cy, s); c.drawLine(cx + a * 0.15f, cy + a * 0.85f, cx + a, cy, s)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (busy) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; grabAt = e.x - x; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> if (dragging) { x = (e.x - grabAt).coerceIn(0f, maxX()); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (x >= maxX() * 0.82f && e.actionMasked == MotionEvent.ACTION_UP) finish() else settle(0f)
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); if (!busy) finish(); return true }
    private fun settle(to: Float, then: () -> Unit = {}) {
        val from = x
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { x = from + (to - from) * (it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { then() } })
            start()
        }
    }
    private fun finish() {
        busy = true
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        settle(maxX()) { onConfirm(); postDelayed({ x = 0f; busy = false; invalidate() }, 350) }
    }
}

/** After a session: the colonnade close up, and today's column rising into place in Ember's colour. */
class RisingColumnView(context: Context, private val ink: Int, private val shadow: Int, private val bg: Int) : View(context) {
    var rise = 0f; set(v) { field = v; invalidate() }
    var older = 4
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val emberA = Color.parseColor("#C2410C"); private val emberB = Color.parseColor("#FFD9A8"); private val emberC = Color.parseColor("#D9541C")
    fun play(delay: Long = 250, onTop: () -> Unit = {}) {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; startDelay = delay; interpolator = android.view.animation.OvershootInterpolator(0.6f)
            addUpdateListener { rise = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { onTop() } })
            start()
        }
    }
    private fun column(c: Canvas, x: Float, cw: Float, top: Float, bottom: Float, shader: Shader) {
        p.shader = shader
        val cap = cw * 0.25f; val sw = cw * 0.64f
        r.set(x - cw * 0.08f, top, x + cw * 1.08f, top + cap); c.drawRoundRect(r, cap / 4, cap / 4, p)
        r.set(x + (cw - sw) / 2, top + cap * 1.15f, x + (cw + sw) / 2, bottom - cap * 1.2f); c.drawRoundRect(r, sw / 10, sw / 10, p)
        r.set(x - cw * 0.12f, bottom - cap, x + cw * 1.12f, bottom); c.drawRoundRect(r, cap / 4, cap / 4, p)
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w == 0f) return
        val step = w / (older + 1.15f); val cw = step * 0.5f
        val beam = h * 0.05f; val top = beam + h * 0.06f; val floor = h * 0.92f
        val marble = LinearGradient(0f, 0f, cw, 0f, intArrayOf(shadow, ink, shadow), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.MIRROR)
        // floor and the old beam
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.argb(40, Color.red(ink), Color.green(ink), Color.blue(ink)), ink), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
        r.set(-10f, floor, w + 10f, h); c.drawRoundRect(r, 4f, 4f, p)
        val newX = older * step + step * 0.35f
        r.set(-10f, 0f, newX - cw * 0.2f, beam); c.drawRoundRect(r, 4f, 4f, p)
        for (i in 0 until older) {
            val x = i * step + step * 0.35f - step * 0.55f
            val save = c.saveLayerAlpha(0f, 0f, w, h, (90 + 165 * (i + 1f) / older).toInt())
            c.translate(x, 0f); column(c, 0f, cw, top, floor, marble); c.restoreToCount(save)
        }
        // today's column, rising from below the floor
        val k = rise.coerceAtLeast(0f)
        val lift = (1 - k.coerceAtMost(1f)) * (floor - top + h * 0.1f)
        val ember = LinearGradient(0f, 0f, cw, 0f, intArrayOf(emberA, emberB, emberC), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.MIRROR)
        val save = c.save()
        c.clipRect(0f, 0f, w, floor)
        c.translate(newX, lift); column(c, 0f, cw, top, floor, ember)
        c.restoreToCount(save)
        // the beam reaches over it once it stands
        if (k > 0.85f) {
            p.shader = LinearGradient(newX, 0f, newX + cw, 0f, intArrayOf(emberA, emberB, emberC), null, Shader.TileMode.CLAMP)
            p.alpha = (255 * ((k - 0.85f) / 0.15f).coerceIn(0f, 1f)).toInt()
            r.set(newX - cw * 0.2f, 0f, newX + cw * 1.25f, beam); c.drawRoundRect(r, 4f, 4f, p); p.alpha = 255
        }
        // a warm glow at its foot
        p.shader = RadialGradient(newX + cw / 2, floor, cw * 2.2f, Color.argb((120 * k.coerceAtMost(1f)).toInt(), 255, 122, 46), Color.argb(0, 255, 122, 46), Shader.TileMode.CLAMP)
        c.drawRect(newX - cw * 2.5f, floor - cw * 2.4f, newX + cw * 3.5f, h, p)
    }
}

/** A Low Day on the calendar: outlined, with the lower part filled (it counted, at reduced effort). */
class HalfFillDrawable(private val ink: Int, private val radius: Float, private val stroke: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2)
        val save = c.save()
        c.clipRect(b.left.toFloat(), b.top + b.height() * 0.56f, b.right.toFloat(), b.bottom.toFloat())
        p.style = Paint.Style.FILL; p.color = ink; c.drawRoundRect(r, radius, radius, p)
        c.restoreToCount(save)
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; c.drawRoundRect(r, radius, radius, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A ruler of ticks: the part eaten in ink, the rest faint, a small marker where you are. */
class TickRuler(context: Context, private val on: Int, private val off: Int) : View(context) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val n = 40
        p.strokeWidth = maxOf(2f, w / 180f)
        val m = h * 0.2f
        for (i in 0..n) {
            val x = p.strokeWidth / 2 + (w - p.strokeWidth) * i / n
            val major = i % 5 == 0
            p.color = if (i.toFloat() / n <= fraction) on else off
            c.drawLine(x, if (major) m else h * 0.36f, x, if (major) h - m * 0.2f else h * 0.78f, p)
        }
        val fx = (w - p.strokeWidth) * fraction + p.strokeWidth / 2
        p.color = on
        c.drawPath(Path().apply { moveTo(fx - h * 0.12f, 0f); lineTo(fx + h * 0.12f, 0f); lineTo(fx, h * 0.15f); close() }, p)
    }
}

/** The welcome picture: a colonnade running into a low sun, in perspective. */
class PerspectiveColonnade(context: Context, private val dark: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w == 0f) return
        val u = w / 390f
        // the sun
        // kept inside the picture so the light fades out instead of being cut off at an edge
        val sx = w * 0.88f; val sy = h * 0.5f; val sr = min(w * 0.5f, h * 0.5f)
        val sun = if (dark) intArrayOf(0xFFFFD27A.toInt(), 0xFFFF7A2E.toInt(), 0xFFC2301A.toInt(), 0x00781414) else intArrayOf(0xFFFFC98A.toInt(), 0xFFFF9A5A.toInt(), 0x59FF8C50, 0x00FF8C50)
        p.shader = RadialGradient(sx, sy, sr, sun, floatArrayOf(0f, 0.3f, 0.58f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        val ink = if (dark) 0xFFF3F1EA.toInt() else 0xFF2A2A28.toInt(); val shade = if (dark) 0xFF7E7C76.toInt() else 0xFF0D0D0C.toInt()
        val vx = 640 * u; val vy = 236 * u * (h / (470 * u)).coerceAtMost(1.2f); val top0 = 92 * u; val bot0 = h - 78 * u; val x0 = 26 * u
        fun edge(y0: Float) = y0 + (vy - y0) * (w / vx)
        val fade = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFFFFFFF.toInt(), 0x40FFFFFF), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        val layer = c.saveLayer(0f, 0f, w, h, null)
        // the beam above and the floor below
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(shade, ink, shade), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        fun band(a: Float, b: Float, alpha: Int = 255) {
            p.alpha = alpha
            c.drawPath(Path().apply { moveTo(0f, a); lineTo(w, edge(a)); lineTo(w, edge(b)); lineTo(0f, b); close() }, p)
            p.alpha = 255
        }
        band(top0 - 48 * u, top0 - 16 * u); band(top0 - 12 * u, top0 - 6 * u, 180); band(bot0 + 16 * u, bot0 + 34 * u)
        for (i in 0 until 9) {
            val k = Math.pow(0.8, i.toDouble()).toFloat()
            val x = vx - (vx - x0) * k; if (x > w + 2) break
            val cw = 46 * u * k; val t = vy - (vy - top0) * k; val b = vy + (bot0 - vy) * k; val cap = 12 * u * k
            p.shader = LinearGradient(x, 0f, x + cw, 0f, intArrayOf(shade, ink, shade), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            r.set(x - cw * 0.22f, t - cap, x + cw * 1.22f, t); c.drawRoundRect(r, 2 * u * k, 2 * u * k, p)
            r.set(x, t, x + cw, b); c.drawRoundRect(r, 2 * u * k, 2 * u * k, p)
            r.set(x - cw * 0.28f, b, x + cw * 1.28f, b + cap * 1.1f); c.drawRoundRect(r, 2 * u * k, 2 * u * k, p)
            p.shader = null; p.color = Color.argb(70, 0, 0, 0); p.strokeWidth = u
            c.drawLine(x + cw * 0.3f, t + 3 * k, x + cw * 0.3f, b - 3 * k, p); c.drawLine(x + cw * 0.66f, t + 3 * k, x + cw * 0.66f, b - 3 * k, p)
        }
        // fade toward the sun
        p.shader = fade; p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, h, p); p.xfermode = null
        c.restoreToCount(layer)
    }
}
