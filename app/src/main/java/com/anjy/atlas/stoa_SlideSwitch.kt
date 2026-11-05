package com.anjy.atlas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator

/** The glass navy of every switched-on setting. */
internal object Navy {
    fun of(dark: Boolean) = if (dark) Color.parseColor("#2C4C8C") else Color.parseColor("#1E3A72")
}

/**
 * An on/off switch. Turning it on slides the knob across while it turns from white to black, and the glass track
 * fills with navy; off, it slides back. The row it sits in is the thing to tap.
 */
class SlideSwitch(context: Context, on: Boolean, private val dark: Boolean) : View(context) {
    var on = on
        private set
    private var t = if (on) 1f else 0f
    private var anim: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val navy = Navy.of(dark)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Moves to [value]; [animate] off jumps there (for drawing a page). */
    fun set(value: Boolean, animate: Boolean = true) {
        on = value
        anim?.cancel()
        val to = if (value) 1f else 0f
        if (!animate) { t = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(t, to).apply {
            duration = 240; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((52 * d).toInt(), (31 * d).toInt())

    private fun mix(a: Int, b: Int, f: Float): Int {
        val u = f.coerceIn(0f, 1f)
        return Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(d, d, w - d, h - d)
        val rad = r.height() / 2
        // the track: frosted glass when off, glass navy when on. A paint drawing a gradient still takes the alpha of
        // the colour it last had, so each gradient starts from full alpha (the knob's soft shadow left it at 50,
        // which drew the knob and, from the second frame on, the track faint)
        val off = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        val base = mix(off, navy, t)
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(base, Color.WHITE, if (dark) 0.10f else 0.14f), base, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        s.strokeWidth = d
        s.color = Color.BLACK
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(if (dark) 70 else 120, 255, 255, 255), Color.argb(if (dark) 10 else 30, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, s)
        s.shader = null
        // the knob: white sliding to black, with a soft shadow under it and light on its top
        val kr = rad - 2.5f * d
        val cx = r.left + rad + (r.width() - 2 * rad) * t
        val cy = r.centerY()
        p.color = Color.argb(50, 0, 0, 0)
        c.drawCircle(cx, cy + 1.2f * d, kr, p)
        val knob = mix(Color.WHITE, Color.parseColor("#0D0D0C"), t)
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, cy - kr, 0f, cy + kr, mix(knob, Color.WHITE, 0.18f), knob, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, kr, p)
        p.shader = null
        s.strokeWidth = d * 0.8f
        s.color = Color.argb((60 * t + 20).toInt(), 255, 255, 255)
        c.drawCircle(cx, cy, kr - 0.4f * d, s)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true; info.isChecked = on
    }
}

/**
 * A setting that is on or off: its label and a [SlideSwitch]. Tapping the row slides the switch first and then
 * runs [onChange], so the move is seen before the page redraws.
 */
internal fun MainActivity.switchRow(icon: String?, label: String, on: Boolean, enabled: Boolean = true, navyWhenOn: Boolean = false, onChange: (Boolean) -> Unit): android.widget.LinearLayout {
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(12), dp(12))
        alpha = if (enabled) 1f else 0.5f
    }
    val name = text(label, 15f, FG, MED).apply { plain = true; text = L.t(label) }
    // a chosen row turns glass navy, with its words in white
    fun look(state: Boolean) {
        row.contentDescription = noEmoji(L.t(label)) + ", " + L.t(if (state) "on" else "off")
        if (navyWhenOn && state) { row.background = Finish(Navy.of(dark), dp(12).toFloat(), dark, resources.displayMetrics.density); name.setTextColor(Color.WHITE) }
        else { row.background = pressable(SURFACE2, 12, LINE); name.setTextColor(FG) }
    }
    // rows speak in words: the icon a caller passes is no longer drawn
    row.addView(name, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sw = SlideSwitch(this, on, dark)
    row.addView(sw)
    look(on)
    var busy = false
    row.setOnClickListener {
        if (busy || !enabled) return@setOnClickListener
        busy = true
        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val next = !sw.on
        sw.set(next)
        look(next)
        row.postDelayed({ busy = false; onChange(next) }, if (Prefs.reduceMotion(this)) 0L else 250L)
    }
    return row
}
