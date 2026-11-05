package com.anjy.atlas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View

/*
 * Atlas's look, carried over from Stoa: the same two materials.
 * Night: true black, hairline slab panels, warm white ink, white glass pills.
 * Day: frosted glass panels on a brown page, near-black ink, black glass buttons.
 */

/** From [a] toward [b] by [t] (0 = a, 1 = b), alpha included. */
internal fun tint(a: Int, b: Int, t: Float) = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

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
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint().apply { shader = Grain.shader() }
    private var glow: Shader? = null
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // a gradient needs a radius above zero; a collapsed view has none
        if (w <= 0 || h <= 0) { glow = null; return }
        val c = if (dark) warm else Color.WHITE
        val a = if (dark) 34 else 70
        glow = RadialGradient(w * 0.92f, -h * 0.02f, w * 0.95f, Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)), Shader.TileMode.CLAMP)
    }
    override fun onDraw(cv: Canvas) {
        cv.drawColor(bg)
        p.shader = glow; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        g.alpha = if (dark) 9 else 6
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), g)
    }
}

/** A surface. Night: a near-black panel with a hairline edge. Day: frosted glass, whiter at the top, with a bright rim. */
class StoneDrawable(private val fill: Int, private val radius: Float, private val dark: Boolean, private val density: Float,
                    private val edge: Int? = null, private val page: Int? = null) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        if (dark) {
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
                p.shader = LinearGradient(0f, r.top, 0f, r.bottom, tint(fill, Color.WHITE, 0.10f), tint(fill, Color.BLACK, 0.02f), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, radius, radius, p); p.shader = null
                s.strokeWidth = density; s.shader = null
                s.color = edge ?: Color.argb(26, 0, 0, 0)
                r.inset(density / 2, density / 2); c.drawRoundRect(r, radius, radius, s)
            }
        }
    }
    private fun glassAlpha(fill: Int, page: Int?): Float? {
        if (page == null) return null
        val f = Color.red(fill) + Color.green(fill) + Color.blue(fill)
        val b = Color.red(page) + Color.green(page) + Color.blue(page)
        if (f <= b + 6 || b >= 760) return null
        return ((f - b).toFloat() / (765 - b)).coerceIn(0.12f, 0.92f)
    }
    private fun drawGlass(c: Canvas, a: Float) {
        fun w(alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, w(a * 1.18f), w(a * 0.84f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height() * 0.4f, 110 * density), w(0.30f), w(0f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p); p.shader = null
        s.strokeWidth = density
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(w(0.95f), w(0.45f), Color.argb(34, 90, 60, 30)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val rr = RectF(r); rr.inset(density / 2, density / 2); c.drawRoundRect(rr, radius, radius, s); s.shader = null
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: Outline) { o.setRoundRect(bounds, radius) }
}

/**
 * How buttons are finished, so they read as objects rather than flat paint.
 * Solid: glass over the colour, lit from above. Glass: a clear pane with an edge bright on top.
 */
class Finish(private val color: Int, private val radius: Float, private val dark: Boolean, private val density: Float,
             private val glass: Boolean = false, private val pressed: Boolean = false) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val rad = if (radius < 0) r.height() / 2 else radius
        val top = r.top; val bot = r.bottom
        if (glass) {
            p.shader = LinearGradient(0f, top, 0f, bot,
                if (dark) Color.argb(if (pressed) 34 else 20, 255, 255, 255) else Color.argb(if (pressed) 40 else 64, 255, 255, 255),
                if (dark) Color.argb(if (pressed) 22 else 8, 255, 255, 255) else Color.argb(if (pressed) 20 else 34, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            s.strokeWidth = density
            s.shader = LinearGradient(0f, top, 0f, bot,
                if (dark) Color.argb(90, 255, 255, 255) else Color.argb(190, 255, 255, 255),
                if (dark) Color.argb(18, 255, 255, 255) else Color.argb(46, 0, 0, 0), Shader.TileMode.CLAMP)
            r.inset(density / 2, density / 2); c.drawRoundRect(r, rad, rad, s)
            return
        }
        val light = Color.luminance(color) > 0.75f
        val tone = if (light) (if (dark) Color.rgb(222, 222, 218) else Color.rgb(244, 236, 226)) else color
        val base = if (pressed) tint(tone, Color.BLACK, 0.10f) else tone
        p.shader = LinearGradient(0f, top, 0f, bot, tint(base, Color.WHITE, if (light) 0.42f else 0.16f), tint(base, Color.BLACK, if (light) 0.05f else 0.10f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        val inset = 1.6f * density
        val sh = RectF(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * 0.56f)
        val shRad = (rad - inset).coerceAtLeast(0f)
        p.shader = LinearGradient(0f, sh.top, 0f, sh.bottom, Color.argb(if (pressed) 30 else if (light) 120 else 64, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(sh, shRad, shRad, p)
        p.shader = null
        s.strokeWidth = density
        s.shader = LinearGradient(0f, top, 0f, bot, intArrayOf(Color.argb(if (pressed) 60 else 170, 255, 255, 255), Color.argb(24, 255, 255, 255), Color.argb(if (light) 46 else 60, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        r.inset(density / 2, density / 2); c.drawRoundRect(r, rad, rad, s)
        s.shader = null
    }
    override fun getOutline(o: Outline) { val rad = if (radius < 0) bounds.height() / 2f else radius; o.setRoundRect(bounds, rad); o.alpha = 1f }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A finished background that darkens while pressed. [radius] < 0 means a pill. */
internal fun finished(color: Int, dark: Boolean, density: Float, radius: Float = -1f, glass: Boolean = false): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), Finish(color, radius, dark, density, glass, pressed = true))
        addState(intArrayOf(), Finish(color, radius, dark, density, glass))
    }

/** The pop-up card: a raised surface, lighter along the top, with an edge that catches the light. */
class SheetDrawable(private val fill: Int, private val radius: Float, private val dark: Boolean, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat() + radius)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, tint(fill, Color.WHITE, if (dark) 0.11f else 0.10f), tint(fill, Color.WHITE, if (dark) 0.03f else 0f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height() * 0.3f, 120 * density), Color.argb(if (dark) 18 else 30, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p); p.shader = null
        s.strokeWidth = density
        val k = if (dark) 255 else 0
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(if (dark) 90 else 255, 255, 255, 255), Color.argb(if (dark) 22 else 30, k, k, k), Color.argb(if (dark) 8 else 20, k, k, k)), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        r.inset(density / 2, density / 2); c.drawRoundRect(r, radius, radius, s); s.shader = null
    }
    override fun getOutline(o: Outline) { o.setRoundRect(bounds, radius) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Gives a button weight under the finger: it sinks a little while held and springs back on release. */
internal fun View.pressFx(): View {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).setInterpolator(null).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }
        false
    }
    return this
}

/** A tab icon (Phosphor, MIT): outlined when not current, filled when current, tinted to the bar's ink. */
class NavIcon(context: Context, offId: Int, onId: Int) : View(context) {
    private val off: Drawable = context.getDrawable(offId)!!.mutate()
    private val on: Drawable = context.getDrawable(onId)!!.mutate()
    var color = Color.WHITE; set(v) { field = v; invalidate() }
    var filled = false; set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val d = if (filled) on else off
        val s = minOf(width, height)
        d.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        d.setTint(color)
        d.draw(c)
    }
}
