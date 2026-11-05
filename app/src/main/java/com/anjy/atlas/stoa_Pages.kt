package com.anjy.atlas

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A full-screen page over the app: a title with a close button, and a scrolling column that [build] fills
 * (it gets the column and a function that closes the page). Back closes it too. Returns the close function.
 */
internal fun MainActivity.fullPage(title: String, onClosed: () -> Unit = {}, build: (col: LinearLayout, close: () -> Unit) -> Unit): () -> Unit {
    val root = confetti.parent as ViewGroup
    val page = FrameLayout(this).apply { setBackgroundColor(BG); isClickable = true; alpha = 0f }
    val sc = QuickScroll(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(gutterDp()), dp(16), dp(gutterDp()), dp(48)) }
    sc.addView(col)
    page.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
    var closed = false
    var close: () -> Unit = {}
    close = {
        if (!closed) {
            closed = true
            backStack.remove(close)
            page.animate().alpha(0f).setDuration(170).withEndAction { root.removeView(page); onClosed() }.start()
        }
    }
    backStack.add(close)
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(text(title, 26f, FG, HEAD).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, WRAP, 1f))
    top.addView(FrameLayout(this).apply {
        background = quietBg(); contentDescription = "Close"; isClickable = true
        addView(LineIcon(this@fullPage, "close").apply { color = quietMuted() }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        setOnClickListener { close() }
    }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(12) })
    col.addView(top)
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    build(content, close)
    root.addView(page, root.indexOfChild(confetti), FrameLayout.LayoutParams(MATCH, MATCH))
    page.animate().alpha(1f).setDuration(190).start()
    return close
}

/**
 * Pages side by side that a sideways swipe moves between (a flick, or a drag past half a page). Taps go through to
 * the page; an up-and-down drag scrolls the screen as usual. [onPage] hears which page settles.
 */
class SwipePager(context: Context) : ViewGroup(context) {
    var page = 0
        private set
    var onPage: (Int) -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startScroll = 0
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var h = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            h = maxOf(h, c.measuredHeight)
        }
        // every page as tall as the tallest, so the card doesn't jump as it turns
        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (anim?.isRunning != true && !dragging) scrollTo(page * w, 0)
    }

    /** Goes to page [i]; [animate] off jumps there. */
    fun show(i: Int, animate: Boolean = true) {
        val target = i.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != page
        page = target
        anim?.cancel()
        val to = target * width
        if (!animate || width == 0) scrollTo(to, 0)
        else anim = ValueAnimator.ofInt(scrollX, to).apply {
            duration = 260; interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
        if (changed) onPage(target)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false; startScroll = scrollX
                anim?.cancel()
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                // sideways belongs to the pages here, not to the tab swipe
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) { parent?.requestDisallowInterceptTouchEvent(false); return false }
                if (abs(dx) > slop && abs(dx) > abs(dy)) { dragging = true; downX = e.x; startScroll = scrollX; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (!dragging) show(page) }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; startScroll = scrollX; anim?.cancel(); parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = e.x - downX; val dy = e.y - downY
                    // up and down is the screen's: hand it back
                    if (abs(dy) > slop && abs(dy) > abs(dx)) { parent?.requestDisallowInterceptTouchEvent(false); return true }
                    if (abs(dx) <= slop) return true
                    dragging = true; downX = e.x; startScroll = scrollX
                }
                val max = (childCount - 1) * width
                var x = startScroll - (e.x - downX)
                // a little give past the first and last page
                if (x < 0) x /= 3f else if (x > max) x = max + (x - max) / 3f
                scrollTo(x.roundToInt(), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val w = width.coerceAtLeast(1)
                val target = when {
                    v < -800 -> page + 1
                    v > 800 -> page - 1
                    else -> ((scrollX + w / 2) / w)
                }
                dragging = false
                show(target)
            }
        }
        return true
    }
}

/** Dots under a [SwipePager]: the page showing is the long one. */
internal fun MainActivity.pagerDots(n: Int, pager: SwipePager, labels: List<String> = emptyList()): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun draw(on: Int) {
        row.removeAllViews()
        for (i in 0 until n) row.addView(View(this).apply {
            background = rounded(if (i == on) FG else if (dark) android.graphics.Color.argb(60, 255, 255, 255) else android.graphics.Color.argb(50, 0, 0, 0), 99)
            contentDescription = labels.getOrNull(i)?.let { "$it" + if (i == on) ", showing" else "" }
            isClickable = true; setOnClickListener { pager.show(i) }
        }, LinearLayout.LayoutParams(if (i == on) dp(18) else dp(7), dp(7)).apply { leftMargin = dp(3); rightMargin = dp(3) })
    }
    draw(pager.page)
    val before = pager.onPage
    pager.onPage = { before(it); draw(it) }
    return row
}
