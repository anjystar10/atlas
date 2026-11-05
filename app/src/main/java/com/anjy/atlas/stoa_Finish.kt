package com.anjy.atlas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View

/**
 * How buttons are finished, so they read as objects rather than flat paint.
 *  SOLID: glass over the colour: lit from above, a clear sheen over the upper half, a rim bright on top and darker
 *         below. A white button is whitish glass (warm frosted glass by day).
 *  GLASS: a clear pane: barely-there fill (by day frosted white over the brown), an edge that
 *         is bright on top and fades toward the bottom.
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
        // every coloured button is glass over its colour; a white one is whitish glass, not bare white
        val light = Color.luminance(color) > 0.75f
        // a white button: whitish glass at night, warm frosted glass by day (never grey)
        val tone = if (light) (if (dark) Color.rgb(222, 222, 218) else Color.rgb(244, 236, 226)) else color
        val base = if (pressed) tint(tone, Color.BLACK, 0.10f) else tone
        // the body: light falling from the top, a deeper base
        p.shader = LinearGradient(0f, top, 0f, bot, tint(base, Color.WHITE, if (light) 0.42f else 0.16f), tint(base, Color.BLACK, if (light) 0.05f else 0.10f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        // the sheen: a clear pane catching the light over the upper half
        val inset = 1.6f * density
        val sh = RectF(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * 0.56f)
        val shRad = (rad - inset).coerceAtLeast(0f)
        p.shader = LinearGradient(0f, sh.top, 0f, sh.bottom, Color.argb(if (pressed) 30 else if (light) 120 else 64, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(sh, shRad, shRad, p)
        p.shader = null
        // the rim: bright along the top, a darker line along the bottom, so the edge reads as glass
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
internal fun MainActivity.finished(color: Int, radius: Float = -1f, glass: Boolean = false): StateListDrawable {
    val d = resources.displayMetrics.density
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), Finish(color, radius, dark, d, glass, pressed = true))
        addState(intArrayOf(), Finish(color, radius, dark, d, glass))
    }
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

/** A soft shadow in the button's own colour (Android 9+), so a coloured button seems to give off light. */
internal fun View.glowShadow(color: Int, dark: Boolean, elevationPx: Float) {
    elevation = elevationPx
    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        val c = if (dark && Color.luminance(color) > 0.6f) Color.argb(120, 0, 0, 0) else color
        outlineSpotShadowColor = c; outlineAmbientShadowColor = Color.argb(60, Color.red(c), Color.green(c), Color.blue(c))
    }
}


/** The pop-up card: a raised surface, lighter along the top, with an edge that catches the light. */
class SheetDrawable(private val fill: Int, private val radius: Float, private val dark: Boolean, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, tint(fill, Color.WHITE, if (dark) 0.11f else 0.10f), tint(fill, Color.WHITE, if (dark) 0.03f else 0f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height() * 0.3f, 120 * density), Color.argb(if (dark) 18 else 30, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p); p.shader = null
        s.strokeWidth = density
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(if (dark) 90 else 255, 255, 255, 255), Color.argb(if (dark) 22 else 30, if (dark) 255 else 0, if (dark) 255 else 0, if (dark) 255 else 0), Color.argb(if (dark) 8 else 20, if (dark) 255 else 0, if (dark) 255 else 0, if (dark) 255 else 0)), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        r.inset(density / 2, density / 2); c.drawRoundRect(r, radius, radius, s); s.shader = null
    }
    override fun getOutline(o: Outline) { o.setRoundRect(bounds, radius) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
