package com.anjy.atlas

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The main call to action, a slide: a round knob at the start of an ink pill. Drag it to the end (Ember's light
 * trails behind it) and let go most of the way across, or tap and it slides across by itself. TalkBack users
 * double-tap. The label is the first child, so a caller can change its words.
 */
internal fun MainActivity.goPill(label: String, color: Int = BLUE, icon: String = "arrow", hint: Boolean = false, height: Int = 58, onClick: () -> Unit): View {
    val ink = if (color == BLUE) ON else Color.WHITE
    val frame = SlideFrame(this, ACCENT).apply {
        background = finished(color)
        minimumHeight = dp(height)
        // [hint]: when it sits untouched, the knob nudges toward the end and a light runs along the pill, showing it slides
        this.hint = hint && !Prefs.reduceMotion(this@goPill)
        shine = ink
        contentDescription = L.t(label)
    }
    val lab = text(label, 16f, ink, MED).apply { plain = true; text = L.t(label); gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    frame.addView(lab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(60); rightMargin = dp(60) })
    val knob = FrameLayout(this).apply {
        background = Finish(ink, -1f, !dark, resources.displayMetrics.density)
        elevation = dp(2).toFloat()
        addView(LineIcon(this@goPill, icon).apply { this.color = color }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        // the tick it turns into when the slide lands
        addView(LineIcon(this@goPill, "check").apply { this.color = color; alpha = 0f }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
    }
    val k = (height - 14).coerceAtLeast(44)
    frame.addView(knob, FrameLayout.LayoutParams(dp(k), dp(k), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(7) })
    frame.knob = knob; frame.label = lab
    frame.onGo = { onClick() }
    return frame
}

/**
 * A pill whose round knob slides from its start to its end: drag it across and let go past most of the way, or
 * tap and it slides across by itself; let go short of the end and it springs back. A light in [trail]'s colour
 * fills the pill behind the knob as it goes.
 */
class SlideFrame(context: android.content.Context, private val trail: Int) : FrameLayout(context) {
    var knob: View? = null
    var label: View? = null
    var onGo: () -> Unit = {}
    /** Nudge the knob and run a light along the pill while it's left alone. */
    var hint = false
    /** The colour of that light (the knob's own colour). */
    var shine = Color.WHITE
    private var pos = 0f
    private var armed = false
    private var sweep = -1f
    private var idle: android.animation.ValueAnimator? = null
    private var busy = false
    private var downX = 0f; private var downY = 0f; private var startPos = 0f
    private var dragging = false; private var tracking = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val box = android.graphics.RectF()
    private var anim: android.animation.ValueAnimator? = null

    init { isClickable = true; isFocusable = true; setWillNotDraw(false) }

    private fun maxPos(): Float {
        val k = knob ?: return 0f
        val m = (k.layoutParams as? MarginLayoutParams)?.leftMargin ?: 0
        return (width - m * 2 - k.width).toFloat().coerceAtLeast(0f)
    }

    private fun place(x: Float) {
        pos = x.coerceIn(0f, maxPos())
        knob?.translationX = pos
        val f = if (maxPos() > 0f) pos / maxPos() else 0f
        label?.alpha = (1f - f * 1.7f).coerceIn(0f, 1f)
        // a light tick when a drag passes the point where letting go will finish it (and back)
        val past = f >= 0.8f
        if (dragging && past != armed) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        armed = past
        invalidate()
    }

    // ---- the idle hint: every few seconds the knob leans toward the end and a light sweeps across
    private val hintRun = object : Runnable {
        override fun run() {
            if (!hint || !isAttachedToWindow) return
            if (!busy && !tracking && pos < 0.5f) playHint()
            postDelayed(this, 3600)
        }
    }
    private fun playHint() {
        idle?.cancel()
        val lean = 16 * resources.displayMetrics.density
        idle = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // out quickly, back with a small spring; the light runs from start to end meanwhile
                val nudge = if (t < 0.25f) t / 0.25f else kotlin.math.max(0f, 1f - (t - 0.25f) / 0.45f)
                if (!busy && !tracking) knob?.translationX = lean * android.view.animation.DecelerateInterpolator(1.4f).getInterpolation(nudge)
                sweep = t; invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { sweep = -1f; if (!busy && !tracking) knob?.translationX = pos; invalidate() } })
            start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (hint) postDelayed(hintRun, 1400) }
    override fun onDetachedFromWindow() { removeCallbacks(hintRun); idle?.cancel(); anim?.cancel(); super.onDetachedFromWindow() }

    override fun dispatchDraw(c: android.graphics.Canvas) {
        val k = knob
        if (k != null && pos > 0.5f) {
            // the trail: from the start of the pill to just past the knob, strongest where the knob is
            val h = height.toFloat()
            val end = (k.right + pos + k.left).coerceAtMost(width.toFloat())
            box.set(0f, 0f, end, h)
            path.reset(); path.addRoundRect(box, h / 2, h / 2, android.graphics.Path.Direction.CW)
            val r = Color.red(trail); val g = Color.green(trail); val b = Color.blue(trail)
            paint.shader = LinearGradient(0f, 0f, end, 0f, intArrayOf(Color.argb(0, r, g, b), Color.argb(120, r, g, b), Color.argb(255, r, g, b)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
            paint.shader = null
        }
        if (sweep in 0f..1f && width > 0) {
            // the hint's light: a soft band crossing the pill, inside its rounded shape
            val h = height.toFloat(); val band = width * 0.28f
            val x = -band + (width + band * 2) * sweep
            box.set(0f, 0f, width.toFloat(), h)
            path.reset(); path.addRoundRect(box, h / 2, h / 2, android.graphics.Path.Direction.CW)
            val r = Color.red(shine); val g = Color.green(shine); val b = Color.blue(shine)
            paint.shader = LinearGradient(x - band, 0f, x + band, 0f, intArrayOf(Color.argb(0, r, g, b), Color.argb(46, r, g, b), Color.argb(0, r, g, b)), null, Shader.TileMode.CLAMP)
            c.save(); c.clipPath(path); c.drawRect(box, paint); c.restore()
            paint.shader = null
        }
        super.dispatchDraw(c)
    }

    // the pill takes every touch itself: its words and knob are pictures, not buttons
    override fun onInterceptTouchEvent(e: android.view.MotionEvent) = true

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (busy) return true
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                anim?.cancel(); idle?.cancel(); sweep = -1f; knob?.translationX = pos
                downX = e.x; downY = e.y; startPos = pos; dragging = false; tracking = true; isPressed = true
                // sideways belongs to the knob here, not to the tab swipe; up and down is handed back below
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging) {
                    if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        tracking = false; isPressed = false; parent?.requestDisallowInterceptTouchEvent(false); return true
                    }
                    if (kotlin.math.abs(dx) > slop) { dragging = true; isPressed = false }
                }
                if (dragging) place(startPos + dx)
            }
            android.view.MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) { if (pos >= maxPos() * 0.8f) go() else back() }
                else if (tracking) go()
                dragging = false; tracking = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (dragging) back()
                dragging = false; tracking = false
            }
        }
        return true
    }

    override fun performClick(): Boolean { if (!busy) go(); return true }

    /** Runs a caller's own click listener (set with setOnClickListener) once the slide has landed. */
    private fun clicked() { super.performClick() }

    private fun slide(to: Float, ms: Long, then: () -> Unit) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(pos, to).apply {
            duration = ms; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) then() }
            })
            start()
        }
    }

    private fun go() {
        busy = true
        val m = maxPos()
        val left = if (m > 0f) 1f - pos / m else 1f
        idle?.cancel(); sweep = -1f
        slide(m, (90 + 230 * left).toLong()) {
            performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showTick(true)
            // a beat with the tick showing, then on: the page changes under a finished button, not a moving one
            postDelayed({
                try { clicked(); onGo() } finally {
                    // ready again: the knob glides home (if the page is still here)
                    postDelayed({ if (isAttachedToWindow) { showTick(false); busy = false; slide(0f, 260) {} } else busy = false }, 420)
                }
            }, 140)
        }
    }

    /** Swaps the knob's arrow for a tick (and back). */
    private fun showTick(on: Boolean) {
        val k = knob as? ViewGroup ?: return
        if (k.childCount < 2) return
        k.getChildAt(0).animate().alpha(if (on) 0f else 1f).setDuration(120).start()
        k.getChildAt(1).animate().alpha(if (on) 1f else 0f).setDuration(120).start()
    }

    private fun back() = slide(0f, 240) {}

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); place(pos) }
}

/**
 * Picking a time in the app's own look (not the phone's clock dialog): the hour and the minutes, each with − and +.
 * Minutes move in fives; holding a button moves faster.
 */
internal fun MainActivity.timeSheet(title: String, hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    var h = hour.coerceIn(0, 23); var m = (minute.coerceIn(0, 59) / 5) * 5
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun column(get: () -> Int, step: (Int) -> Unit, what: String): Pair<View, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val num = text("%02d".format(java.util.Locale.US, get()), 52f, FG, NUM).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        fun btn(icon: String, d: Int) = FrameLayout(this).apply {
            background = quietBg(); isClickable = true; contentDescription = L.t(if (d > 0) "Later" else "Earlier") + ", " + L.t(what)
            addView(LineIcon(this@timeSheet, icon).apply { color = quietInk() }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); step(d); num.text = "%02d".format(java.util.Locale.US, get()) }
            setOnLongClickListener { repeat(3) { step(d) }; num.text = "%02d".format(java.util.Locale.US, get()); true }
        }
        col.addView(btn("plus", 1), LinearLayout.LayoutParams(dp(56), dp(44)))
        col.addView(num, LinearLayout.LayoutParams(WRAP_C, WRAP_C).apply { topMargin = dp(8); bottomMargin = dp(8) })
        col.addView(btn("minus", -1), LinearLayout.LayoutParams(dp(56), dp(44)))
        return col to num
    }
    val (hc, _) = column({ h }, { d -> h = (h + d + 24) % 24 }, "hour")
    val (mc, _) = column({ m }, { d -> m = (m + d * 5 + 60) % 60 }, "minutes")
    row.addView(hc)
    row.addView(text(":", 44f, MUTED, NUM).apply { gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), dp(6)) })
    row.addView(mc)
    sheet(null, title, null, content = row, primaryLabel = "Set Time", onPrimary = { onPick(h, m) }, secondaryLabel = "Cancel")
}
private val WRAP_C = ViewGroup.LayoutParams.WRAP_CONTENT

/** Small line icons for buttons. */
class LineIcon(context: android.content.Context, private val kind: String) : View(context) {
    var color = Color.WHITE; set(v) { field = v; invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val path = android.graphics.Path()
    override fun onDraw(c: android.graphics.Canvas) {
        val s = minOf(width, height) / 24f
        c.save(); c.translate((width - 24 * s) / 2, (height - 24 * s) / 2); c.scale(s, s)
        p.color = color; p.strokeWidth = 2f; path.reset()
        when (kind) {
            "plus" -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            "minus" -> c.drawLine(5f, 12f, 19f, 12f, p)
            "check" -> { path.moveTo(5f, 12f); path.lineTo(10f, 17f); path.lineTo(19f, 7f); c.drawPath(path, p) }
            "camera" -> { path.moveTo(4f, 8f); path.lineTo(7f, 8f); path.lineTo(9f, 5f); path.lineTo(15f, 5f); path.lineTo(17f, 8f); path.lineTo(20f, 8f); path.lineTo(20f, 19f); path.lineTo(4f, 19f); path.close(); c.drawPath(path, p); c.drawCircle(12f, 13f, 3.5f, p) }
            "play" -> { // a filled, slightly rounded triangle
                val f = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = this@LineIcon.color; pathEffect = android.graphics.CornerPathEffect(2.2f) }
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, f)
            }
            "pause" -> { // two filled, rounded bars
                val f = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = this@LineIcon.color }
                c.drawRoundRect(6.5f, 5f, 10.5f, 19f, 1.6f, 1.6f, f); c.drawRoundRect(13.5f, 5f, 17.5f, 19f, 1.6f, 1.6f, f)
            }
            "close" -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
            "stop" -> { // a filled, rounded square
                val f = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = this@LineIcon.color }
                c.drawRoundRect(6.5f, 6.5f, 17.5f, 17.5f, 2.5f, 2.5f, f)
            }
            "mic" -> { // Atlas: a microphone in the same line style
                c.drawRoundRect(9f, 3.5f, 15f, 14.5f, 3f, 3f, p)
                path.moveTo(6f, 11.5f); path.cubicTo(6f, 19f, 18f, 19f, 18f, 11.5f); c.drawPath(path, p)
                c.drawLine(12f, 17.5f, 12f, 21f, p)
            }
            "chev" -> { path.moveTo(9f, 6f); path.lineTo(15f, 12f); path.lineTo(9f, 18f); c.drawPath(path, p) }
            "chats" -> { // two speech bubbles: earlier chats
                path.moveTo(4f, 5.5f); path.lineTo(15f, 5.5f); path.quadTo(16.5f, 5.5f, 16.5f, 7f); path.lineTo(16.5f, 12.5f); path.quadTo(16.5f, 14f, 15f, 14f)
                path.lineTo(8.5f, 14f); path.lineTo(5.5f, 16.8f); path.lineTo(5.5f, 14f); path.lineTo(4f, 14f); path.quadTo(2.5f, 14f, 2.5f, 12.5f); path.lineTo(2.5f, 7f); path.quadTo(2.5f, 5.5f, 4f, 5.5f)
                c.drawPath(path, p); path.reset()
                path.moveTo(19.5f, 9.5f); path.quadTo(21.5f, 9.5f, 21.5f, 11f); path.lineTo(21.5f, 16f); path.quadTo(21.5f, 17.5f, 20f, 17.5f); path.lineTo(19f, 17.5f); path.lineTo(19f, 20f); path.lineTo(16f, 17.5f); path.lineTo(11.5f, 17.5f)
                c.drawPath(path, p)
            }
            "compose" -> { // a pencil on a page: a new chat
                path.moveTo(11f, 4.5f); path.lineTo(6f, 4.5f); path.quadTo(4f, 4.5f, 4f, 6.5f); path.lineTo(4f, 18f); path.quadTo(4f, 20f, 6f, 20f); path.lineTo(17.5f, 20f); path.quadTo(19.5f, 20f, 19.5f, 18f); path.lineTo(19.5f, 13f)
                c.drawPath(path, p); path.reset()
                path.moveTo(17.5f, 3.5f); path.lineTo(20.5f, 6.5f); path.lineTo(12f, 15f); path.lineTo(8.5f, 15.5f); path.lineTo(9f, 12f); path.close(); c.drawPath(path, p)
            }
            "search" -> { c.drawCircle(10.5f, 10.5f, 6f, p); c.drawLine(15f, 15f, 20f, 20f, p) }
            else -> { c.drawLine(5f, 12f, 19f, 12f, p); path.moveTo(13f, 6f); path.lineTo(19f, 12f); path.lineTo(13f, 18f); c.drawPath(path, p) }
        }
        c.restore()
    }
}

internal fun TextView.fitLine(minSp: Float = 12f): TextView {
    if (getTag(R.id.widget_art) == "fit") return this   // already fitting
    setTag(R.id.widget_art, "fit")
    var base = textSize
    var lastSet = textSize
    val minPx = minSp * resources.displayMetrics.scaledDensity
    setSingleLine(true); ellipsize = null
    fun fit() {
        // someone set a new size on purpose: that becomes the size to fit from
        if (kotlin.math.abs(textSize - lastSet) > 0.5f) base = textSize
        // a wrap-content text shrinks with its text, so measure against the room its parent gives it
        val lp = layoutParams
        val par = parent as? View
        val avail = if (lp != null && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT && par != null) {
            val m = lp as? ViewGroup.MarginLayoutParams
            // in a row, the words beside the number keep the room they need on one line
            var others = 0
            if (par is LinearLayout && par.orientation == LinearLayout.HORIZONTAL) for (i in 0 until par.childCount) {
                val v = par.getChildAt(i); if (v === this || v.visibility == View.GONE) continue
                val vm = v.layoutParams as? ViewGroup.MarginLayoutParams
                val want = if (v is TextView) (android.text.Layout.getDesiredWidth(v.text, v.paint) + v.totalPaddingLeft + v.totalPaddingRight).toInt() else v.measuredWidth
                others += want + (vm?.leftMargin ?: 0) + (vm?.rightMargin ?: 0)
            }
            par.width - par.paddingLeft - par.paddingRight - (m?.leftMargin ?: 0) - (m?.rightMargin ?: 0) - totalPaddingLeft - totalPaddingRight - others
        } else width - totalPaddingLeft - totalPaddingRight
        if (avail <= 0) return
        val p = android.text.TextPaint(paint).apply { textSize = base }
        val want = android.text.Layout.getDesiredWidth(text, p)
        val size = if (want <= avail) base else maxOf(minPx, base * avail / want * 0.97f)
        if (kotlin.math.abs(size - textSize) > 0.5f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        lastSet = textSize
    }
    // refit when the parent's room changes (the text's own size follows from the fit, so it can't loop)
    var lastRoom = -1
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val room = (parent as? View)?.width ?: width
        if (room != lastRoom) { lastRoom = room; post { fit() } }
    }
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { post { fit() } }
    })
    post { fit() }
    return this
}

/** A number with a small unit after it ("1,650 kcal") on one line, the unit in the body face. */
internal fun MainActivity.numberUnit(big: String, unit: String?, size: Float, color: Int, unitColor: Int = color, face: android.graphics.Typeface = NUM): TextView {
    val tv = text(big, size, color, face).apply { includeFontPadding = false; letterSpacing = -0.01f }
    tv.text = numberUnitText(big, unit, unitColor)
    return tv.fitLine(14f) as TextView
}

internal fun MainActivity.numberUnitText(big: String, unit: String?, unitColor: Int): CharSequence {
    if (unit == null) return big
    val ratio = unit.startsWith("/")
    val s = android.text.SpannableString(if (ratio) "$big$unit" else "$big $unit")
    val from = big.length
    s.setSpan(android.text.style.RelativeSizeSpan(if (ratio) 0.5f else 0.36f), from, s.length, 0)
    s.setSpan(android.text.style.ForegroundColorSpan(unitColor), from, s.length, 0)
    if (!ratio) s.setSpan(FaceSpan(MED), from, s.length, 0)
    return s
}

/** A typeface for part of a text (TypefaceSpan(Typeface) needs Android 9). */
class FaceSpan(private val face: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(p: android.text.TextPaint) { p.typeface = face; p.letterSpacing = 0f }
    override fun updateMeasureState(p: android.text.TextPaint) { p.typeface = face; p.letterSpacing = 0f }
}

/** Text filled with a vertical gradient (used for the streak number once today is done). */
internal fun TextView.gradientFill(top: Int, bottom: Int) {
    post { paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP); invalidate() }
}

/** One row of equal segments on a glass track (sections, sign in or create), always on one line: labels shrink to fit. */
internal fun MainActivity.segmented(options: List<Pair<String, String>>, isOn: (String) -> Boolean, onTap: (String) -> Unit): LinearLayout {
    val d = resources.displayMetrics.density
    val track = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Finish(SURFACE, -1f, dark, d, glass = true); setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    fun build() {
        track.removeAllViews()
        options.forEach { (key, label) ->
            val on = isOn(key)
            val t = TextView(this).apply {
                text = noEmoji(L.t(label)); textSize = 14f; typeface = if (on) SEMI else MED; gravity = Gravity.CENTER
                setTextColor(if (on) ON else FG)
                background = if (on) Finish(BLUE, -1f, dark, d) else null
                setPadding(dp(6), dp(10), dp(6), dp(10))
                contentDescription = L.t(label) + if (on) ", selected" else ""
                setOnClickListener { if (!isOn(key)) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onTap(key); build() } }
            }
            track.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            t.fitLine(10f)
        }
    }
    build()
    return track
}
