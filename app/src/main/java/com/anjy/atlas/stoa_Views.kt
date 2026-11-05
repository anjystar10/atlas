package com.anjy.atlas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Today's progress ring with a tick at the Pass Mark. */
class RingView(context: Context, private val track: Int, private val done: Int, private val fg: Int, private val muted: Int) : View(context) {
    var passMark = 0.8f
    private var shown = 0f
    private var target = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var animator: ValueAnimator? = null
    var accent = Color.parseColor("#FF8A3D")
    /** Off: just the ring (no number, no pass mark). */
    var showLabel = true
    var face: Typeface? = null

    fun setProgress(p: Float, animate: Boolean = true) {
        target = p.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) { shown = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.07f
        val r = RectF(stroke, stroke, size - stroke, size - stroke)
        r.offset((width - size) / 2, (height - size) / 2)
        paint.strokeWidth = if (showLabel) stroke else size * 0.055f
        paint.color = track
        canvas.drawArc(r, 0f, 360f, false, paint)
        paint.color = if (shown >= passMark) done else accent
        canvas.drawArc(r, -90f, 360f * shown, false, paint)
        if (!showLabel) return
        face?.let { text.typeface = it }
        // pass mark: a small dot on the track (hidden once passed)
        val a = Math.toRadians((-90 + 360 * passMark).toDouble())
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2
        if (shown < passMark && passMark > 0f) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted }
            canvas.drawCircle(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat(), stroke * 0.32f, dot)
        }
        // Size the number to fit comfortably inside the ring, whatever it says.
        val label = "${(shown * 100).toInt()}%"
        // once it counts, the number and the word take the ring's own colour
        val passed = shown >= passMark
        text.color = if (passed) done else fg; text.textSize = size * 0.21f
        val maxW = (rad - stroke * 1.4f) * 1.45f
        val tw = text.measureText(label)
        if (tw > maxW) text.textSize *= maxW / tw
        canvas.drawText(label, cx, cy + size * 0.05f, text)
        small.color = if (passed) done else muted; small.textSize = size * 0.08f
        canvas.drawText(if (passed) "counts" else "pass ${(passMark * 100).toInt()}%", cx, cy + size * 0.19f, small)
    }
}

/** Thin rounded progress bar. */
class BarView(context: Context, private val track: Int) : View(context) {
    var progress = 0f; set(v) { field = v.coerceIn(0f, 1f); contentDescription = "${(field * 100).toInt()}%"; invalidate() }
    var color = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = track; canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        if (progress > 0f) { p.color = color; canvas.drawRoundRect(0f, 0f, maxOf(h, w * progress), h, h / 2, h / 2, p) }
    }
}

/** One-shot confetti burst drawn over the screen. */
class ConfettiView(context: Context) : View(context) {
    private class Bit(var x: Float, var y: Float, val vx: Float, var vy: Float, val rot: Float, val spin: Float, val size: Float, val color: Int)
    private val bits = mutableListOf<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    fun burst(colors: List<Int>, onEnd: () -> Unit) {
        val w = width.toFloat().takeIf { it > 0 } ?: 1080f
        val h = height.toFloat().takeIf { it > 0 } ?: 1900f
        bits.clear()
        repeat(140) {
            val angle = Random.nextDouble(-Math.PI, 0.0)
            val speed = Random.nextDouble(0.6, 1.5).toFloat() * h
            bits.add(Bit(w / 2, h * 0.38f, (cos(angle) * speed * 0.9).toFloat(), (sin(angle) * speed).toFloat(),
                Random.nextFloat() * 360, Random.nextFloat() * 720 - 360, Random.nextFloat() * 14 + 10, colors.random()))
        }
        var last = 0L
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            addUpdateListener {
                val now = it.currentPlayTime
                val dt = ((now - last).coerceAtLeast(0)) / 1000f
                last = now
                t = it.animatedValue as Float
                bits.forEach { b -> b.vy += h * 1.4f * dt; b.x += b.vx * dt; b.y += b.vy * dt }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { bits.clear(); invalidate(); onEnd() }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = ((1f - t) * 1.6f).coerceIn(0f, 1f)
        bits.forEach { b ->
            paint.color = b.color
            paint.alpha = (alpha * 255).toInt()
            canvas.save()
            canvas.rotate(b.rot + b.spin * t, b.x, b.y)
            canvas.drawRect(b.x - b.size / 2, b.y - b.size / 4, b.x + b.size / 2, b.y + b.size / 4, paint)
            canvas.restore()
        }
    }
}

/** Lays children left to right and wraps to a new line when the next one won't fit. */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : android.view.ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            // honour fixed sizes (photo tiles), otherwise let the child size itself (chips)
            val lp = c.layoutParams
            c.measure(if (lp != null && lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                if (lp != null && lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/**
 * A vertical scroller that lets you drag the page sideways with your finger. The page follows the
 * finger 1:1; let go past a third of the screen (or flick) and it moves to the next tab, otherwise
 * it springs back. Vertical scrolling and taps behave normally.
 */
/** A scrolling page whose buttons show they're pressed the moment a finger lands, instead of 100 ms later. */
open class QuickScroll(context: Context) : android.widget.ScrollView(context) {
    override fun shouldDelayChildPressedState() = false
}

class SwipeScrollView(context: Context, private val listener: Listener) : QuickScroll(context) {
    interface Listener {
        fun onDrag(dx: Float, y: Float)
        fun onRelease(dx: Float, velocityX: Float, x: Float, y: Float)
    }

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vertical = false
    private var locked = false
    private var tracker: android.view.VelocityTracker? = null

    /** Pull down at the top to refresh: [onPull] hears how far the page is pulled, [onLetGo] how far it was when let go. */
    interface Pull { fun onPull(distance: Float); fun onLetGo(distance: Float) }
    var pull: Pull? = null
    private var pulling = false

    /** A child that scrolls sideways itself (a chip row, a chart) asks us to stay out of it. */
    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        locked = disallow
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    /**
     * Swipes are spotted here, before any child sees the move, so a sideways drag works wherever the
     * finger lands: on text, on a card, on a button. Once it's a swipe, the children get a cancel.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false; vertical = false; locked = false; pulling = false
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (!dragging && !vertical && !locked) {
                    if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) vertical = true
                    else if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                        dragging = true
                        downX = ev.x - kotlin.math.sign(dx) * 1f
                        val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) { listener.onDrag(ev.x - downX, ev.y); return true }
                // at the top, a downward drag pulls the page (half as far as the finger) instead of scrolling it
                val p = pull
                if (p != null && vertical && !locked && (pulling || (scrollY == 0 && dy > 0))) {
                    val d = (dy / 2f).coerceAtLeast(0f)
                    pulling = d > 0f
                    p.onPull(d)
                    if (pulling) return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    pull?.onLetGo(if (ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) 0f else ((ev.y - downY) / 2f).coerceAtLeast(0f))
                }
                if (dragging) {
                    tracker?.addMovement(ev); tracker?.computeCurrentVelocity(1000)
                    // a drag the system took away snaps back; only a finger lifted on purpose can turn the page
                    val taken = ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    listener.onRelease(if (taken) 0f else ev.x - downX, if (taken) 0f else tracker?.xVelocity ?: 0f, ev.x, ev.y)
                    tracker?.recycle(); tracker = null
                    dragging = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * A small bar chart over time: newest on the right, one slot per period, so a few values are a few slim bars
 * rather than wide blocks. Empty slots are faint dots on the baseline, a zero is a dot too; the best bar has its
 * own colour; a dashed line marks the target.
 */
class MiniChart(context: Context, private val track: Int, private val barColor: Int, private val bestColor: Int, private val lineColor: Int) : View(context) {
    var values: List<Int> = emptyList(); set(v) { field = v; start = android.os.SystemClock.uptimeMillis(); invalidate() }
    var target = 0
    /** At least this many slots across. */
    var slots = 14
    /** For a series like body weight: the bars start a little under the lowest value, so small changes show. */
    var floorAtMin = false
    private var start = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val d = resources.displayMetrics.density
        val n = maxOf(values.size, slots, 1)
        val slotW = w / n
        val bw = minOf(slotW * 0.56f, 12 * d).coerceAtLeast(2 * d)
        val r = minOf(bw / 2, 3 * d)
        var hi = maxOf(values.maxOrNull() ?: 1, target, 1).toFloat()
        var lo = 0f
        if (floorAtMin && values.isNotEmpty()) {
            // room below the lowest value and a little above the highest, so equal values sit mid-height, not as a wall
            val m = values.minOrNull()!!.toFloat()
            val pad = maxOf(hi - m, hi * 0.12f, 1f)
            lo = (m - pad).coerceAtLeast(0f); hi += pad * 0.25f
        }
        val grow = ((android.os.SystemClock.uptimeMillis() - start) / 450f).coerceIn(0f, 1f)
        val ease = 1f - (1f - grow) * (1f - grow)
        val base = h - d
        // the baseline
        p.color = track
        canvas.drawRect(0f, base, w, h, p)
        val empty = n - values.size
        for (i in 0 until empty) canvas.drawCircle(i * slotW + slotW / 2, base - 2.5f * d, 1.5f * d, p)
        // one bar is the best: the latest time the top value was reached
        val bestAt = values.indices.lastOrNull { values[it] == values.maxOrNull() } ?: -1
        values.forEachIndexed { i, v ->
            val cx = (empty + i) * slotW + slotW / 2
            val bh = ((v - lo) / (hi - lo).coerceAtLeast(1f)).coerceIn(0f, 1f) * (base - 2 * d) * ease
            if (v <= 0 || bh < 3 * d) {
                p.color = if (v > 0) barColor else track
                canvas.drawCircle(cx, base - 2.5f * d, 2.2f * d, p)
            } else {
                p.color = if (i == bestAt && values.size > 1) bestColor else barColor
                canvas.drawRoundRect(cx - bw / 2, base - bh, cx + bw / 2, base, r, r, p)
            }
        }
        if (target > 0 && target <= hi) {
            val y = base - (target - lo) / (hi - lo).coerceAtLeast(1f) * (base - 2 * d)
            dash.color = lineColor; dash.strokeWidth = d
            dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
            canvas.drawLine(0f, y, w, y, dash)
        }
        if (grow < 1f) postInvalidateOnAnimation()
    }
}

/** A ScrollView that never grows past [maxFraction] of the screen, so sheet buttons stay visible. */
class MaxScroll(context: Context, private val maxFraction: Float = 0.55f) : QuickScroll(context) {
    init {
        // a soft fade at the edge says there's more below (or above) to scroll to
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((28 * resources.displayMetrics.density).toInt())
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * maxFraction).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }
}
