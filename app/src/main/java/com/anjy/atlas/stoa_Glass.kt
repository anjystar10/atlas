package com.anjy.atlas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// ------------------------------------------------------------------ layout that fits the phone

/** Side margin for the page: tighter on narrow phones, roomier on big ones; tablets get a centred column. */
internal fun MainActivity.gutterDp(): Int {
    val w = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    return when {
        w >= 600 -> ((w - 560) / 2).toInt()
        w >= 400 -> 20
        w >= 360 -> 16
        else -> 12
    }
}

// ------------------------------------------------------------------ the glass Light / Dark switch

/**
 * A pill with a lens of glass resting on it. Light: the lens sits left over a glowing sun on a pale track.
 * Dark: it sits right over a moon on a black track. Tapping slides the lens across, stretching it like a drop
 * of water as it travels, while the sun closes into the moon and the words swap.
 */
class ThemeSwitch(context: Context, startDark: Boolean, private val label: Typeface) : View(context) {
    /** Called when the lens has landed, with the new state. */
    var onChange: (Boolean) -> Unit = {}
    var dark = startDark; private set
    private var t = if (startDark) 1f else 0f
    private var busy = false
    private val d0 = resources.displayMetrics.density
    /** Size: 1 is full size; smaller for a tighter spot. */
    var k = 1f; set(v) { field = v; requestLayout() }
    private val d get() = d0 * k
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = label; textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val path = Path()

    init {
        isClickable = true; isFocusable = true
        contentDescription = if (startDark) "Dark look on. Double tap for the light look." else "Light look on. Double tap for the dark look."
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((128 * d).toInt(), (64 * d).toInt())

    private fun mix(a: Int, b: Int, f: Float) = tint(a, b, f)

    override fun onDraw(c: Canvas) {
        val u = d; val cy = height / 2f
        val tl = 4 * u; val tr = width - 4 * u; val th = 40 * u
        // the track
        r.set(tl, cy - th / 2, tr, cy + th / 2)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(0xFFD6D6DA.toInt(), 0xFF2A2A2E.toInt(), t), mix(0xFFA8A8AE.toInt(), 0xFF141416.toInt(), t), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, th / 2, th / 2, p); p.shader = null
        s.strokeWidth = u; s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb((60 * (1 - t) + 14 * t).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        r.inset(u / 2, u / 2); c.drawRoundRect(r, th / 2, th / 2, s); s.shader = null
        // the words
        txt.textSize = 15 * u
        txt.color = Color.argb((255 * (1 - t) * (1 - t)).toInt(), 255, 255, 255)
        c.drawText("Light", tl + (tr - tl) * 0.66f, cy + 5.2f * u, txt)
        txt.color = Color.argb((235 * t * t).toInt(), 140, 140, 146)
        c.drawText("Dark", tl + (tr - tl) * 0.33f, cy + 5.2f * u, txt)
        // where the lens is, and how much it stretches while moving
        val kx = 32 * u + (width - 64 * u) * t
        val stretch = sin(PI * t).toFloat()
        val kw = 56 * u * (1 + 0.28f * stretch); val kh = 60 * u * (1 - 0.07f * stretch)
        // a soft light under the lens by day
        if (t < 1f) {
            p.shader = RadialGradient(kx, cy + 6 * u, kw * 0.9f, Color.argb((150 * (1 - t)).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawCircle(kx, cy + 6 * u, kw * 0.9f, p); p.shader = null
        }
        // the disc under the icon
        p.color = mix(Color.argb(46, 255, 255, 255), 0xFF0E0E10.toInt(), t)
        c.drawCircle(kx, cy, 17 * u, p)
        drawIcon(c, kx, cy, u)
        // the glass
        r.set(kx - kw / 2, cy - kh / 2, kx + kw / 2, cy + kh / 2)
        val rad = kw.coerceAtMost(kh) / 2
        p.shader = RadialGradient(kx - kw * 0.15f, cy - kh * 0.2f, kw * 0.8f, Color.argb(40, 255, 255, 255), Color.argb(6, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p); p.shader = null
        s.strokeWidth = 1.1f * u
        s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(110, 255, 255, 255)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, s); s.shader = null
        // a bright edge along the top left and a smaller one at the bottom: light caught in the glass
        s.strokeWidth = 1.6f * u; s.strokeCap = Paint.Cap.ROUND; s.color = Color.argb(130, 255, 255, 255)
        r.inset(3 * u, 3 * u)
        c.drawArc(r, 200f, 55f, false, s)
        s.color = Color.argb(70, 255, 255, 255)
        c.drawArc(r, 60f, 40f, false, s)
    }

    /** The sun closing into the moon as [t] goes 0 → 1. */
    private fun drawIcon(c: Canvas, x: Float, y: Float, u: Float) {
        val ink = Color.WHITE
        p.color = ink
        p.setShadowLayer(7 * u, 0f, 0f, Color.argb(200, 255, 255, 255))
        val core = (4.6f + 3.2f * t) * u
        path.reset(); path.addCircle(x, y, core, Path.Direction.CW)
        if (t > 0f) {
            val off = (16f - 11.5f * t) * u
            val cut = Path().apply { addCircle(x + off * 0.72f, y - off * 0.62f, core * 0.88f, Path.Direction.CW) }
            path.op(cut, Path.Op.DIFFERENCE)
        }
        c.drawPath(path, p)
        // the rays shrink away
        val ray = 1f - (t * 1.8f).coerceAtMost(1f)
        if (ray > 0f) {
            s.color = Color.argb((255 * ray).toInt(), 255, 255, 255); s.strokeWidth = 1.8f * u; s.strokeCap = Paint.Cap.ROUND; s.shader = null
            for (i in 0 until 8) {
                val a = i * PI / 4
                val r0 = core + 2.6f * u; val r1 = r0 + 3.4f * u * ray
                c.drawLine(x + r0 * cos(a).toFloat(), y + r0 * sin(a).toFloat(), x + r1 * cos(a).toFloat(), y + r1 * sin(a).toFloat(), s)
            }
        }
        p.clearShadowLayer()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); return true }
            MotionEvent.ACTION_UP -> { animate().scaleX(1f).scaleY(1f).setDuration(160).start(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { animate().scaleX(1f).scaleY(1f).setDuration(160).start(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); flip(); return true }

    fun flip() {
        if (busy) return
        busy = true; dark = !dark
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val from = t; val to = if (dark) 1f else 0f
        ValueAnimator.ofFloat(from, to).apply {
            duration = 560; interpolator = PathInterpolator(0.6f, 0f, 0.3f, 1f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); busy = false; onChange(dark)
                }
            })
            start()
        }
    }

    /** Centre of the lens, in this view's coordinates. */
    fun lensX() = 32 * d + (width - 64 * d) * t
}

/**
 * The look changes by redrawing the app, so the old look is photographed first and, once the new one is up,
 * a circle opening from the switch wipes the photo away.
 */
object ThemeSwap {
    var shot: Bitmap? = null
    var cx = 0f; var cy = 0f
    var tab = -1; var scrollY = 0
}

class RevealView(context: Context, private val bmp: Bitmap, private val cx: Float, private val cy: Float) : View(context) {
    private var radius = 0f
    private val hole = Path()
    fun play(onEnd: () -> Unit) {
        val far = max(max(hypot(cx, cy), hypot(width - cx, cy)), max(hypot(cx, height - cy), hypot(width - cx, height - cy)))
        ValueAnimator.ofFloat(0f, far).apply {
            duration = 720; interpolator = PathInterpolator(0.55f, 0f, 0.2f, 1f)
            addUpdateListener { radius = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { onEnd() } })
            start()
        }
    }
    override fun onDraw(c: Canvas) {
        hole.reset(); hole.addCircle(cx, cy, radius, Path.Direction.CW)
        c.save(); c.clipOutPath(hole); c.drawBitmap(bmp, 0f, 0f, null); c.restore()
    }
}

/**
 * Light ↔ Dark from the switch: the screen as it is now is photographed, the new look is drawn underneath in
 * place (no restart, no blink), and a circle opening from the lens wipes the photo away.
 */
internal fun MainActivity.swapTheme(toDark: Boolean, from: ThemeSwitch) {
    val decor = window.decorView as ViewGroup
    val loc = IntArray(2); from.getLocationInWindow(loc)
    val shot = try { Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) } } catch (e: Throwable) { null }
    Store.putString(this, "theme2", if (toDark) "dark" else "light")
    relook()
    if (shot == null || Prefs.reduceMotion(this)) { shot?.recycle(); return }
    val v = RevealView(this, shot, loc[0] + from.lensX(), loc[1] + from.height / 2f)
    decor.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    v.post { v.play { decor.removeView(v); shot.recycle() } }
}

/** Kept for a look change that did restart the screen (none now); harmless if nothing is waiting. */
internal fun MainActivity.finishThemeSwap() {
    val b = ThemeSwap.shot ?: return
    ThemeSwap.shot = null; b.recycle()
}

// ------------------------------------------------------------------ the navigation bar

/**
 * A floating pill of tabs with a round Start button beside it. The tab you're on opens into a chip with its
 * name; the others are icons. Sizes come from the screen: on a narrow phone the chip shows just the icon if the
 * name doesn't fit, and the bar never runs past the edges.
 */
internal fun MainActivity.renderNavBar() {
    val names = listOf("Atlas", "Log", "Me")
    val n = order.size
    val dm = resources.displayMetrics
    val wDp = dm.widthPixels / dm.density
    // everything scales from the width: the Start button, the gap, then the tab cells share the rest,
    // with the current tab's chip at least 1.5 cells wide so it always reads as the selected one
    val side = if (wDp < 360) 10 else 14
    val avail = wDp - side * 2
    val goD = (avail * 0.16f).coerceIn(44f, 56f).toInt()
    val gap = if (avail < 330) 8 else 10
    val padP = 5
    val item = ((avail - goD - gap - padP * 2) / (n + 0.6f)).coerceIn(32f, 48f).toInt()
    val iconD = 22; val chipPad = 12; val iconGap = 6
    // one label size for every tab: the largest (13sp down to 11sp) at which the longest name fits the chip;
    // if none fits, no tab shows its name, so the bar never looks inconsistent
    val labelPaint = Paint().apply { typeface = SEMI }
    fun widths(sp: Float) = names.map { labelPaint.textSize = sp * dm.scaledDensity; labelPaint.measureText(L.t(it)) / dm.density }
    val maxPill = (avail - goD - gap).coerceAtMost(420f)
    val roomChip = maxPill - padP * 2 - item * (n - 1)
    val labelSp = listOf(13f, 12f, 11f).firstOrNull { sp -> chipPad * 2 + iconD + iconGap + order.maxOf { widths(sp)[it] } <= roomChip }
    val showLabels = labelSp != null
    val longest = if (showLabels) chipPad * 2 + iconD + iconGap + order.maxOf { widths(labelSp!!)[it] } else item * 1.5f
    val pillW = (padP * 2 + item * (n - 1) + longest).coerceAtMost(maxPill).coerceAtLeast(padP * 2 + item * (n + 0.5f))
    val chipW = pillW - padP * 2 - item * (n - 1)
    val cellH = minOf(46, goD - padP * 2).coerceAtLeast(38)

    val key = "$dark|${dm.widthPixels}|${dm.scaledDensity}|${L.t("Home")}|$labelSp|$n"
    if (navKey != key || navPill == null) {
        navKey = key
        nav.removeAllViews(); nav.background = null; nav.setPadding(0, 0, 0, 0)
        nav.clipChildren = false; nav.clipToPadding = false
        (nav.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = dp(16)
        val pill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (dark) { setColor(Color.parseColor("#16172E")); setStroke(dp(1), Color.argb(30, 200, 205, 255)) }
                // by day, frosted glass: the page shows faintly through it, whiter at the top, with a bright white rim
                else { colors = intArrayOf(Color.argb(214, 255, 255, 255), Color.argb(170, 238, 239, 252)); orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(dp(1), Color.argb(235, 255, 255, 255)) }
            }
            // a shadow under see-through glass would show through it as a smudge, so the day bar floats without one
            elevation = if (dark) dp(6).toFloat() else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Color.argb(255, 0, 0, 0); outlineAmbientShadowColor = Color.argb(255, 0, 0, 0) }
        }
        // the liquid: one glowing drop in the current tab's colour, under the icons
        val blob = LiquidBlob(this)
        pill.addView(blob, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(padP), dp(padP), dp(padP), dp(padP))
        }
        pill.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        navBlob = blob
        navItems.clear()
        order.forEachIndexed { k, i ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { if (i != tab) switchTab(i, if (pos(i) > pos(tab)) 1 else -1) }
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }
                    false
                }
            }
            val icon = NavIcon(this, i)
            cell.addView(icon, LinearLayout.LayoutParams(dp(if (item < 40) 20 else iconD), dp(if (item < 40) 20 else iconD)))
            val lab = TextView(this).apply {
                text = L.t(names[i]); textSize = labelSp ?: 13f; typeface = SEMI; setSingleLine(true); includeFontPadding = false
            }
            cell.addView(lab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(iconGap) })
            row.addView(cell, LinearLayout.LayoutParams(dp(item), dp(cellH)))
            navItems += NavCell(cell, icon, lab, i)
        }
        nav.addView(pill, LinearLayout.LayoutParams(dp(pillW.toInt()), ViewGroup.LayoutParams.WRAP_CONTENT))
        navPill = row
        // the round Start button
        val go = FrameLayout(this).apply {
            // ink, so the coloured drop in the bar stays the one bright thing
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tint(BLUE, if (dark) Color.WHITE else Color.parseColor("#2A2C52"), 0.25f), BLUE)).apply { shape = GradientDrawable.OVAL }
            elevation = dp(10).toFloat()
            contentDescription = L.t("Talk to Atlas")
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                talk()
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(260).start()
                }
                false
            }
            addView(LineIcon(this@renderNavBar, "mic").apply { color = ON }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        nav.addView(go, LinearLayout.LayoutParams(dp(goD), dp(goD)).apply { leftMargin = dp(gap) })
        navGo = go
        navShown = -1
    }

    // the drop moves to the current tab: it stretches toward the new tab, the leading edge first, then settles
    val iconOff = if (dark) Color.parseColor("#8E93BC") else Color.parseColor("#3E4170")
    val from = navShown; val to = tab
    navShown = to
    val d = dm.density
    fun rectFor(active: Int): RectF {
        var x = padP.toFloat()
        var r = RectF()
        order.forEach { t -> val w = if (t == active) chipW else item.toFloat(); if (t == active) r = RectF(x * d, padP * d, (x + w) * d, (padP + cellH) * d); x += w }
        return r
    }
    navItems.forEach { c ->
        val on = c.tab == to
        c.icon.color = if (on) Color.WHITE else iconOff
        c.icon.filled = on
        c.label.setTextColor(Color.WHITE)
        c.cell.contentDescription = L.t(names[c.tab]) + if (on) ", " + L.t("current page") else ""
        c.label.visibility = if (on && showLabels) View.VISIBLE else View.GONE
        c.cell.background = null
    }
    fun setW(t: Int, w: Float) { navItems.firstOrNull { it.tab == t }?.cell?.let { v -> v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { width = (w * d).toInt() } } }
    val blob = navBlob ?: return
    if (from == -1 || from == to || Prefs.reduceMotion(this)) {
        navItems.forEach { setW(it.tab, if (it.tab == to) chipW else item.toFloat()) }
        blob.place(rectFor(to), NAV_COLORS[to])
    } else {
        navItems.forEach { if (it.tab != from && it.tab != to) setW(it.tab, item.toFloat()) }
        val lab = navItems.firstOrNull { it.tab == to }?.label
        val oldIcon = navItems.firstOrNull { it.tab == from }?.icon
        val newIcon = navItems.firstOrNull { it.tab == to }?.icon
        lab?.alpha = 0f
        oldIcon?.color = Color.WHITE; newIcon?.color = iconOff
        oldIcon?.filled = true; newIcon?.filled = false
        blob.travel(rectFor(from), rectFor(to), NAV_COLORS[from], NAV_COLORS[to]) { f ->
            setW(from, chipW + (item - chipW) * f); setW(to, item + (chipW - item) * f)
            lab?.alpha = ((f - 0.45f) / 0.55f).coerceIn(0f, 1f)
            oldIcon?.color = tint(Color.WHITE, iconOff, (f * 1.6f).coerceAtMost(1f))
            newIcon?.color = tint(iconOff, Color.WHITE, ((f - 0.3f) / 0.5f).coerceIn(0f, 1f))
            // outline ↔ fill swaps as the drop passes over
            oldIcon?.filled = f < 0.35f; newIcon?.filled = f > 0.45f
        }
    }
}

/** Each tab's light, in Atlas's blues and purples: Atlas violet, Log blue, Me purple. */
// the three pages glow in three neighbours on the colour wheel (blue-violet, blue, violet), anchored on the logo's indigo
internal val NAV_COLORS = intArrayOf(0xFF7C6CFF.toInt(), 0xFF4F86FF.toInt(), 0xFF9A6BFF.toInt())

/**
 * The drop of light behind the current tab. Moving, it stretches: the end heading for the new tab races ahead
 * and swells while the other end lags and thins, the middle pinches, and the colour runs from one tab's to the
 * next. Landing, it wobbles once and settles into a pill.
 */
class LiquidBlob(context: Context) : View(context) {
    private val r = RectF()
    private var color = NAV_COLORS[0]
    private var rl = 1f; private var rr = 1f; private var pinch = 0f; private var jiggle = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var anim: ValueAnimator? = null
    private val d = resources.displayMetrics.density

    // takes no room of its own: the bar's height comes from the row of tabs, and the drop fills it
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(
        if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) MeasureSpec.getSize(w) else 0,
        if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else 0)

    fun place(to: RectF, c: Int) { anim?.cancel(); r.set(to); color = c; rl = 1f; rr = 1f; pinch = 0f; jiggle = 0f; invalidate() }

    fun travel(from: RectF, to: RectF, c0: Int, c1: Int, step: (Float) -> Unit) {
        anim?.cancel()
        val right = to.centerX() > from.centerX()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val m = (t / 0.78f).coerceAtMost(1f)                      // the move, then a short settle
                val lead = 1f - (1f - m) * (1f - m) * (1f - m)            // the front races ahead
                val trail = Math.pow(m.toDouble(), 2.2).toFloat()          // the back lets go late
                val s = kotlin.math.sin(Math.PI * m).toFloat()
                if (right) { r.left = from.left + (to.left - from.left) * trail; r.right = from.right + (to.right - from.right) * lead }
                else { r.left = from.left + (to.left - from.left) * lead; r.right = from.right + (to.right - from.right) * trail }
                r.top = from.top + (to.top - from.top) * m; r.bottom = from.bottom + (to.bottom - from.bottom) * m
                // the leading end swells a little, the trailing end thins
                rl = if (right) 1f - 0.38f * s else 1f + 0.06f * s
                rr = if (right) 1f + 0.06f * s else 1f - 0.38f * s
                pinch = 0.22f * s
                color = android.animation.ArgbEvaluator().evaluate((m * 1.15f).coerceAtMost(1f), c0, c1) as Int
                // landing: one damped wobble
                jiggle = if (t > 0.78f) { val k = (t - 0.78f) / 0.22f; kotlin.math.sin(k * Math.PI * 2).toFloat() * (1f - k) * 0.09f } else 0f
                step(m)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: android.animation.Animator) { r.set(to); rl = 1f; rr = 1f; pinch = 0f; jiggle = 0f; color = c1; invalidate() }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (r.width() <= 0f) return
        val cy = r.centerY()
        val h = r.height() * (1f + jiggle); val w = r.width() * (1f - jiggle * 0.6f)
        val L = r.centerX() - w / 2; val R = r.centerX() + w / 2
        val base = minOf(h, w) / 2
        val a = base * rl; val b = base * rr
        val mid = (L + R) / 2; val pin = h * pinch
        path.reset()
        path.moveTo(L + a, cy - a)
        path.quadTo(mid, cy - minOf(a, b) + pin, R - b, cy - b)
        path.arcTo(RectF(R - 2 * b, cy - b, R, cy + b), -90f, 180f)
        path.quadTo(mid, cy + minOf(a, b) - pin, L + a, cy + a)
        path.arcTo(RectF(L, cy - a, L + 2 * a, cy + a), 90f, 180f)
        path.close()
        // glow
        g.color = color
        g.alpha = 70
        if (android.os.Build.VERSION.SDK_INT >= 28) g.setShadowLayer(9 * d, 0f, 0f, Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)))
        c.drawPath(path, g)
        g.clearShadowLayer()
        // the drop, lit from above
        p.shader = LinearGradient(0f, cy - h / 2, 0f, cy + h / 2, tint(color, Color.WHITE, 0.28f), tint(color, Color.BLACK, 0.06f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = LinearGradient(0f, cy - h / 2, 0f, cy, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
    }
}

class NavCell(val cell: LinearLayout, val icon: NavIcon, val label: TextView, val tab: Int)
