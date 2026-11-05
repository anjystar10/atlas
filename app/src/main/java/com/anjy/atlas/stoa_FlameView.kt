package com.anjy.atlas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object Tiers {
    /** [skin]: a flame bought in the Shop, drawn in its own colours; every earned tier burns like 🔥. */
    data class Tier(val name: String, val min: Int, val color: Int, val glow: Int, val skin: Boolean = false)

    val list = listOf(
        Tier("No Flame Yet", 0, Color.parseColor("#C3C8CE"), Color.parseColor("#E3E5E8")),
        Tier("Spark", 1, Color.parseColor("#FFB23D"), Color.parseColor("#FFE08A")),
        Tier("Kindled", 3, Color.parseColor("#FF8A3D"), Color.parseColor("#FFD27A")),
        Tier("Burning", 7, Color.parseColor("#FF5A36"), Color.parseColor("#FFC15C")),
        Tier("Blazing", 14, Color.parseColor("#FF3D7F"), Color.parseColor("#FFB36B")),
        Tier("Inferno", 30, Color.parseColor("#B04DFF"), Color.parseColor("#FF8AD8")),
        Tier("Wildfire", 50, Color.parseColor("#FF2E63"), Color.parseColor("#FFC2D1")),
        Tier("Legendary", 100, Color.parseColor("#3DB8FF"), Color.parseColor("#C9F1FF")),
        Tier("Mythic", 200, Color.parseColor("#20C997"), Color.parseColor("#C3FAE8")),
        Tier("Elite Master", 365, Color.parseColor("#D4A017"), Color.parseColor("#FFF1B8"))
    )
    private val base = listOf(3, 7, 14, 30, 50, 90, 100, 180, 200, 365)

    /** Mini elite ranks for long unbroken runs, on top of the flame rank. */
    data class Elite(val min: Int, val name: String, val icon: String, val color: Int)
    val elites = listOf(
        Elite(30, "Elite Bronze", "🥉", Color.parseColor("#CD7F32")),
        Elite(90, "Elite Silver", "🥈", Color.parseColor("#8E9AA6")),
        Elite(180, "Elite Gold", "🥇", Color.parseColor("#FFB400")),
        Elite(365, "Elite Master", "👑", Color.parseColor("#D4A017"))
    )
    fun elite(streak: Int): Elite? = elites.lastOrNull { streak >= it.min }

    /** Named milestones, plus every 100 days after that. */
    fun isMilestone(n: Int) = n in base || (n > 0 && n % 100 == 0)
    val milestones: List<Int> get() = (base + (3..30).map { it * 100 }).distinct().sorted()

    /**
     * What gets said at each milestone. The 100-day messages use real, sourced figures only:
     * - About half of people who start an exercise program quit within six months (Harvard Health, citing common estimates).
     * - 24.2% of US adults met both the aerobic and muscle-strengthening guidelines in 2020 (CDC, NCHS Data Brief 443).
     */
    fun message(streak: Int): String? = when {
        streak == 3 -> "Three days in a row. The habit is starting to take shape."
        streak == 7 -> "A full week without breaking. You showed up on the days you didn't feel like it, and that's the whole skill."
        streak == 14 -> "Two weeks straight. This isn't a streak anymore. It's becoming who you are."
        streak == 30 -> "🥉 Elite Bronze.\n\nA full month without breaking. Your body is changing even where the mirror hasn't caught up yet."
        streak == 90 -> "🥈 Elite Silver.\n\nThree months in a row. This is where a habit stops being effort and starts being identity. Very few people ever see this rank."
        streak == 180 -> "🥇 Elite Gold.\n\nSix months, unbroken. By common estimates, about half of people who start exercising have quit by now. You never even missed a day."
        streak == 50 -> "Fifty days. Most of what you wanted back is built on days exactly like this one."
        streak == 100 -> "One hundred days without missing one.\n\nBy common estimates, about half of people who start an exercise program quit within six months. You're 100 days in and haven't broken once. That's not motivation. That's who you are now."
        streak == 200 -> "Two hundred days.\n\nYou're past the six-month mark where about half of new exercisers have already quit. And only about 1 in 4 US adults (24.2%, CDC) even meets the basic cardio and strength guidelines. You're doing it every single day."
        streak == 300 -> "Three hundred days.\n\nTen months without a break. The person who started this streak would barely recognise you. 65 more days to Elite Master."
        streak == 365 -> "Elite Master.\n\nA full year, every single day. Only about 1 in 4 adults meets the basic activity guidelines at all (CDC). You didn't just meet them; you never missed a day. This rank exists for the very few who get here."
        streak > 365 && streak % 100 == 0 -> "$streak days.\n\nYou're still an Elite Master, and still going. Past a year, every hundred days is proof it was never luck."
        else -> null
    }

    fun of(streak: Int) = list.last { streak >= it.min }
    fun next(streak: Int) = milestones.firstOrNull { it > streak }
    fun previous(streak: Int) = milestones.lastOrNull { it <= streak } ?: 0
}

/**
 * The flame's outline at time [t] (seconds), in a 0..1 box. Three tongues on top flicker independently,
 * the main tip sways, so the silhouette is never static. [calm] 0..1 damps the motion (sleepy flame).
 */
internal fun flameOutline(w: Float, h: Float, t: Float, calm: Float): Path {
    fun x(v: Float) = v * w
    fun y(v: Float) = v * h
    val m = 1f - 0.75f * calm
    val sw = (kotlin.math.sin(t * 1.9f) * 0.05f + kotlin.math.sin(t * 3.7f) * 0.015f) * m
    val tipY = 0.03f + 0.035f * (0.5f + 0.5f * kotlin.math.sin(t * 3.1f)) * m + 0.04f * calm
    val rX = 0.80f + 0.02f * kotlin.math.sin(t * 2.7f + 1f) * m
    val rY = 0.22f + 0.05f * (0.5f + 0.5f * kotlin.math.sin(t * 4.3f + 0.5f)) * m + 0.05f * calm
    val lX = 0.20f + 0.02f * kotlin.math.sin(t * 2.3f + 2f) * m
    val lY = 0.30f + 0.05f * (0.5f + 0.5f * kotlin.math.sin(t * 3.9f + 1.7f)) * m + 0.04f * calm
    return Path().apply {
        moveTo(x(.5f + sw), y(tipY))
        cubicTo(x(.56f + sw * .6f), y(.14f), x(.64f), y(.30f), x(.66f), y(.36f))
        cubicTo(x(.70f), y(.30f), x(rX - .04f), y(rY + .05f), x(rX), y(rY))
        cubicTo(x(rX + .06f), y(.38f), x(.93f), y(.50f), x(.92f), y(.64f))
        cubicTo(x(.91f), y(.86f), x(.73f), y(.985f), x(.5f), y(.985f))
        cubicTo(x(.27f), y(.985f), x(.09f), y(.86f), x(.08f), y(.64f))
        cubicTo(x(.075f), y(.50f), x(lX - .03f), y(.42f), x(lX), y(lY))
        cubicTo(x(lX + .05f), y(lY + .04f), x(.33f), y(.38f), x(.36f), y(.40f))
        cubicTo(x(.36f), y(.26f), x(.44f + sw * .5f), y(.12f), x(.5f + sw), y(tipY))
        close()
    }
}

/** The warm core: a smaller flame low in the body that flickers on its own rhythm. */
internal fun flameCore(w: Float, h: Float, t: Float, calm: Float): Path {
    fun x(v: Float) = v * w
    fun y(v: Float) = v * h
    val m = 1f - 0.7f * calm
    val sw = kotlin.math.sin(t * 2.4f + 0.8f) * 0.03f * m
    val tip = 0.50f + 0.04f * kotlin.math.sin(t * 3.5f) * m + 0.06f * calm
    return Path().apply {
        moveTo(x(.5f + sw), y(tip))
        cubicTo(x(.56f + sw), y(tip + .12f), x(.72f), y(.66f), x(.72f), y(.80f))
        cubicTo(x(.72f), y(.92f), x(.62f), y(.965f), x(.5f), y(.965f))
        cubicTo(x(.38f), y(.965f), x(.28f), y(.92f), x(.28f), y(.80f))
        cubicTo(x(.28f), y(.66f), x(.44f + sw), y(tip + .12f), x(.5f + sw), y(tip))
        close()
    }
}

/**
 * The streak flame as a little character. It breathes, its tongues flicker, it blinks and glances
 * around. Before today's workout it's dim and sleepy; once lit it's awake; when celebrating it hops
 * with happy eyes.
 */
class FlameView(context: Context) : View(context) {
    var tier: Tiers.Tier = Tiers.list[0]; set(v) { field = v; invalidate() }
    var lit: Boolean = false; set(v) { field = v; invalidate() }
    var face: Boolean = true
    /** Ember colouring: whitish grey while today isn't done, lighting up into a normal orange flame once it is. */
    var dayTint: Boolean = false
    /** Not kept yet today: the flame in grey ash, with an ember still glowing at its foot, waiting to be lit. */
    var ash: Boolean = false; set(v) { field = v; invalidate() }
    private var dtCache: Tiers.Tier? = null; private var dtAt = -1L
    private val dtGrey = Tiers.list[0].copy(color = Color.parseColor("#C3CAD8"), glow = Color.parseColor("#F3F5FA"))
    private val dt: Tiers.Tier get() {
        if (!dayTint) return tier
        val m = System.currentTimeMillis() / 60000
        if (dtCache == null || m != dtAt || (dtCache === dtGrey) == lit) { dtCache = if (lit) Ember.litTier(tier) else dtGrey; dtAt = m }
        return dtCache!!
    }
    /** Streak about to die: wide darting eyes, worried brows, a sweat drop and a nervous tremble. */
    var panic: Boolean = false; set(v) { field = v; invalidate() }
    /**
     * Ember's mood. "" = work it out from lit/panic. Otherwise one of:
     * happy (done today), ready (waiting for you), worried (evening and not done), sleepy (Rest Day),
     * sad (streak lost), panic.
     */
    var mood: String = ""; set(v) { field = v; invalidate() }
    /** Sunglasses: earned with a 30-day streak. */
    var shades: Boolean = false
    /** Streams of tears when sad. Only in the app; the home-screen widget shows a sad face without them. */
    var tears: Boolean = true
    /** Little arms that sway, wave when you tap Ember, and cheer when it's happy. */
    var arms: Boolean = false
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B1D0E") }
    private val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6F7D") }
    private val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val zz = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var waveStart = -10f
    private var nextCheer = 3f

    private fun eff(): String = when {
        mood.isNotEmpty() -> mood
        panic && !lit -> "panic"
        lit -> "happy"
        else -> "sleepy"
    }

    /** Tap reaction: a quick hop and a wave. */
    fun react() { val n = now(); hopStart = n - 0.55f; waveStart = n; invalidate() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sweat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7CC8FF") }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B1D0E") }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eyeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B1D0E"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val start = android.os.SystemClock.uptimeMillis()
    private val rnd = kotlin.random.Random(System.nanoTime())
    private var calm = 1f
    // eyes
    private var lookX = 0f; private var lookY = 0f; private var tgtX = 0f; private var tgtY = 0f
    private var nextLook = 1.2f; private var nextBlink = 1.8f; private var blinkAt = -10f
    // celebration
    private var hopStart = -10f; private var happyUntil = -10f
    private var running = false

    private fun now() = (android.os.SystemClock.uptimeMillis() - start) / 1000f
    /** Reduce Motion: the flame holds still. */
    private val still = Prefs.reduceMotion(context)
    /** Drawn as a picture (the widget, notifications): no easing in, the first frame is the settled flame. */
    var settled = false

    /**
     * Atlas: frames per second when floating over other apps (0 = every screen frame, as in Stoa).
     * With the screen off it checks back once a second instead of drawing.
     */
    var maxFps = 0
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private fun nextFrame() {
        when {
            power?.isInteractive == false -> postInvalidateDelayed(1000L)
            maxFps > 0 -> postInvalidateDelayed(1000L / maxFps)
            else -> postInvalidateOnAnimation()
        }
    }

    /** Happy hop: two bounces with squash on landing, and ^ ^ eyes for a couple of seconds. */
    fun celebrate() { val n = now(); hopStart = n; happyUntil = n + 2.6f; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val t = if (still) 1.3f else now()
        // ease between sleepy and awake instead of snapping
        val m = eff()
        val calmTarget = when (m) { "happy", "panic", "cross" -> 0f; "ready", "worried", "uneasy" -> if (lit) 0f else 0.45f; "sad" -> 0.7f; else -> if (lit || panic) 0f else 1f }
        calm = if (still || settled) calmTarget else calm + (calmTarget - calm) * 0.08f
        if (m == "happy" && arms && t > nextCheer) { waveStart = t; nextCheer = t + 5f + rnd.nextFloat() * 4f }

        // hop: y offset (up is negative) and squash/stretch
        var hopY = 0f; var sx = 1f; var sy = 1f
        val ht = t - hopStart
        if (ht in 0f..1.1f) {
            val phase = if (ht < 0.55f) ht / 0.55f else (ht - 0.55f) / 0.55f
            val height = if (ht < 0.55f) 0.22f else 0.10f
            hopY = -kotlin.math.sin(phase * Math.PI.toFloat()) * height * h
            val land = kotlin.math.max(0f, 1f - kotlin.math.abs(phase - 1f) * 8f) + kotlin.math.max(0f, 1f - phase * 8f)
            sx += 0.14f * land; sy -= 0.14f * land
            if (phase in 0.15f..0.85f) { sx -= 0.05f; sy += 0.07f }   // stretch in the air
        }
        // breathing
        val br = kotlin.math.sin(t * (2.2f - 0.9f * calm))
        sx *= 1f - 0.02f * br; sy *= 1f + 0.025f * br

        canvas.save()
        // nervous tremble: quick small shivers in bursts
        val shiver = if (panic && !lit) kotlin.math.sin(t * 55f) * w * 0.012f * (0.4f + 0.6f * kotlin.math.max(0f, kotlin.math.sin(t * 1.7f))) else 0f
        canvas.translate(shiver, hopY)
        canvas.scale(sx, sy, w / 2, h)
        if (arms) {
            drawArms(canvas, w, h, t, m)
            canvas.scale(0.8f, 0.86f, w / 2, h)
        }

        drawFire(canvas, w, h, t)

        if (face) drawFace(canvas, w, h, t)
        canvas.restore()
        if (running && !still) nextFrame()
    }

    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val midP = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun mixC(a: Int, b: Int, f: Float): Int {
        val u = f.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }

    /** The colours of 🔥, from the deep red tips to the pale yellow heart. */
    private val fTip = Color.parseColor("#D11F17"); private val fRed = Color.parseColor("#F03A1C"); private val fOrange = Color.parseColor("#FF6A19")
    private val fAmber = Color.parseColor("#FF9A1F"); private val fGold = Color.parseColor("#FFC531"); private val fYellow = Color.parseColor("#FFE36A")
    private val fHeart = Color.parseColor("#FFF7D1")
    private val sparkP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowM = android.graphics.Matrix()

    /** Warm light behind the flame: an oval that fades out inside the view, so no edge of it ever shows. */
    private fun drawGlow(canvas: Canvas, w: Float, h: Float, color: Int, alpha: Int) {
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(alpha / 3, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        glowM.reset(); glowM.setScale(w * 0.5f, h * 0.46f); glowM.postTranslate(w / 2, h * 0.54f)
        g.setLocalMatrix(glowM)
        glowP.shader = g
        canvas.drawRect(0f, 0f, w, h, glowP)
    }

    /**
     * The fire in layers, the way 🔥 is drawn: a red outer flame, darkest at its tips, an orange middle flame that
     * flickers on its own rhythm, a yellow heart, and warm light behind it all. Every earned tier burns like this;
     * a longer streak throws more sparks. A Shop flame burns in its own colours; before the day is kept it's silver.
     */
    private fun drawFire(canvas: Canvas, w: Float, h: Float, t: Float) {
        val fade = 1f - 0.55f * calm
        if (ash || dt === dtGrey) { drawAsh(canvas, w, h, t, fade); return }
        val fire = !dt.skin && dt.min > 0
        if (!fire) { drawTinted(canvas, w, h, t, fade, dt.color, dt.glow, dt.min == 0); return }
        drawGlow(canvas, w, h, fOrange, (120 * fade).toInt())
        // the outer flame: deep red at the tips, orange lower down
        outer.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(fTip, fRed, fOrange, fAmber), floatArrayOf(0.04f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
        outer.alpha = (255 - 150 * calm).toInt()
        canvas.drawPath(flameOutline(w, h, t, calm), outer)
        // the middle flame: smaller, orange to gold, its tongues moving to their own beat
        canvas.save()
        canvas.scale(0.74f, 0.76f, w / 2, h * 0.99f)
        midP.shader = LinearGradient(0f, h * 0.18f, 0f, h, intArrayOf(fOrange, fAmber, fGold), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        midP.alpha = (245 - 140 * calm).toInt()
        canvas.drawPath(flameOutline(w, h, t * 1.17f + 1.3f, calm), midP)
        canvas.restore()
        // the heart: yellow, nearly white at the bottom
        core.shader = LinearGradient(0f, h * 0.5f, 0f, h, fYellow, fHeart, Shader.TileMode.CLAMP)
        core.alpha = (240 - 150 * calm).toInt()
        canvas.drawPath(flameCore(w, h, t, calm), core)
        drawSparks(canvas, w, h, t, fade)
    }

    /** Sparks rising off the flame: none for a new flame, more as the streak climbs the tiers. */
    private fun drawSparks(canvas: Canvas, w: Float, h: Float, t: Float, fade: Float) {
        val n = (Tiers.list.indexOfFirst { it.name == dt.name } - 2).coerceIn(0, 8)
        if (n == 0 || calm > 0.6f) return
        val on = (1f - calm) * fade
        for (i in 0 until n) {
            // each spark has its own lane, speed and start, so they never rise in step
            val seed = i * 0.618f
            val life = 1.5f + (i % 3) * 0.35f
            val k = ((t / life + seed) % 1f)
            val lane = (((i * 37) % 9) / 8f - 0.5f) * 0.56f
            val x = w * (0.5f + lane + kotlin.math.sin(t * 2.3f + i) * 0.035f * k)
            val y = h * (0.58f - 0.56f * k)
            val r = w * (0.022f - 0.012f * k)
            val a = (on * 255 * (if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f)).toInt().coerceIn(0, 255)
            sparkP.color = fAmber; sparkP.alpha = a / 3
            canvas.drawCircle(x, y, r * 2.2f, sparkP)
            sparkP.color = if (k < 0.5f) fYellow else fGold; sparkP.alpha = a
            canvas.drawCircle(x, y, r, sparkP)
        }
    }

    /** A Shop flame in its own colours, or the silver of a flame not yet lit: the same layers, in one colour. */
    private val ashC = Color.parseColor("#9AA1AE"); private val ashG = Color.parseColor("#DCE0E7")
    private val emberP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emberM = android.graphics.Matrix()

    /** Grey ash in the flame's shape, and at its foot an ember that glows and fades like breathing. */
    private fun drawAsh(canvas: Canvas, w: Float, h: Float, t: Float, fade: Float) {
        drawTinted(canvas, w, h, t, fade, ashC, ashG, true)
        val pulse = if (still) 0.85f else 0.72f + 0.28f * sin(t * 2.4f)
        val cx = w / 2; val cy = h * 0.87f
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb((235 * pulse).toInt(), 255, 214, 122), Color.argb((160 * pulse).toInt(), 255, 106, 25), Color.argb(0, 255, 80, 20)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        emberM.reset(); emberM.setScale(w * 0.2f, h * 0.09f); emberM.postTranslate(cx, cy)
        g.setLocalMatrix(emberM)
        emberP.shader = g
        canvas.drawRect(cx - w * 0.2f, cy - h * 0.09f, cx + w * 0.2f, cy + h * 0.09f, emberP)
        emberP.shader = null; emberP.color = Color.parseColor("#FFE7A3"); emberP.alpha = (230 * pulse).toInt()
        canvas.drawCircle(cx, cy, w * 0.028f, emberP)
    }

    private fun drawTinted(canvas: Canvas, w: Float, h: Float, t: Float, fade: Float, c: Int, g: Int, grey: Boolean) {
        drawGlow(canvas, w, h, c, (110 * fade).toInt())
        // a fire's tips are its deepest colour; a grey flame (not kept yet, or no streak) darkens to slate, not red
        val deep = if (grey) mixC(c, Color.parseColor("#5A6170"), 0.4f) else mixC(c, Color.parseColor("#7A0F0A"), 0.42f)
        outer.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(deep, c, mixC(c, g, 0.35f)), floatArrayOf(0.05f, 0.55f, 1f), Shader.TileMode.CLAMP)
        outer.alpha = (255 - 150 * calm).toInt()
        canvas.drawPath(flameOutline(w, h, t, calm), outer)
        canvas.save()
        canvas.scale(0.74f, 0.76f, w / 2, h * 0.99f)
        midP.shader = LinearGradient(0f, h * 0.2f, 0f, h, intArrayOf(c, mixC(c, g, 0.6f), g), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        midP.alpha = (240 - 140 * calm).toInt()
        canvas.drawPath(flameOutline(w, h, t * 1.17f + 1.3f, calm), midP)
        canvas.restore()
        core.shader = LinearGradient(0f, h * 0.5f, 0f, h, g, mixC(g, Color.WHITE, 0.55f), Shader.TileMode.CLAMP)
        core.alpha = (235 - 150 * calm).toInt()
        canvas.drawPath(flameCore(w, h, t, calm), core)
    }

    private fun drawArms(c: Canvas, w: Float, h: Float, t: Float, m: String) {
        arm.color = if (!dt.skin && dt.min > 0 && dt !== dtGrey) fRed else dt.color; arm.alpha = (255 - 120 * calm).toInt(); arm.strokeWidth = w * 0.075f
        val sy = h * 0.70f
        val wt = t - waveStart
        val waving = wt in 0f..1.3f
        val sway = sin(t * 2f) * 0.08f
        // left arm: hangs and sways, or flails in panic, or droops when sad
        val la = when (m) { "panic" -> -0.9f + sin(t * 18f) * 0.5f; "sad" -> 0.25f; "sleepy" -> 0.15f; else -> -0.25f + sway }
        // right arm: waves when greeting or cheering
        val ra = if (waving) -2.2f + sin(wt * 16f) * 0.45f else when (m) { "panic" -> -0.9f - sin(t * 18f) * 0.5f; "sad" -> 0.25f; "sleepy" -> 0.15f; else -> -0.25f - sway }
        val len = w * 0.17f
        fun limb(x0: Float, dir: Float, a: Float) {
            // a = angle from straight down-outward; negative raises the arm
            val ang = (Math.PI / 2 + dir * (Math.PI / 3 - a)).toFloat()
            val x1 = x0 + cos(ang) * len; val y1 = sy + sin(ang) * len
            c.drawLine(x0, sy, x1, y1, arm)
            c.drawCircle(x1, y1, arm.strokeWidth * 0.62f, Paint(arm).apply { style = Paint.Style.FILL })
        }
        limb(w * 0.27f, 1f, la)
        limb(w * 0.73f, -1f, ra)
    }

    private fun drawMouth(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, m: String, t: Float) {
        val mw = w * 0.11f
        eyeLine.strokeWidth = 0.03f * w
        when (m) {
            "happy" -> {
                // big open grin with a tongue
                val r = RectF(cx - mw, cy - mw * 0.55f, cx + mw, cy + mw * 0.95f)
                c.drawArc(r, 0f, 180f, true, mouth)
                c.save(); c.clipRect(r.left, r.centerY(), r.right, r.bottom)
                c.drawCircle(cx, r.bottom - mw * 0.1f, mw * 0.55f, tongue); c.restore()
            }
            "ready" -> c.drawArc(RectF(cx - mw * 0.7f, cy - mw * 0.6f, cx + mw * 0.7f, cy + mw * 0.45f), 20f, 140f, false, eyeLine)
            "worried" -> {
                val p = Path().apply { moveTo(cx - mw * 0.7f, cy + mw * 0.2f); quadTo(cx - mw * 0.35f, cy - mw * 0.15f, cx, cy + mw * 0.2f); quadTo(cx + mw * 0.35f, cy + mw * 0.5f, cx + mw * 0.7f, cy + mw * 0.15f) }
                c.drawPath(p, eyeLine)
            }
            "sad", "uneasy" -> c.drawArc(RectF(cx - mw * 0.65f, cy, cx + mw * 0.65f, cy + mw * 0.9f), 200f, 140f, false, eyeLine)
            // cross: a tight mouth pulled down at the corners
            "cross" -> { val p = Path().apply { moveTo(cx - mw * 0.6f, cy + mw * 0.45f); quadTo(cx, cy + mw * 0.05f, cx + mw * 0.6f, cy + mw * 0.45f) }; c.drawPath(p, eyeLine) }
            "panic" -> { val o = mw * (0.42f + 0.08f * sin(t * 12f)); c.drawOval(cx - o * 0.8f, cy - o * 0.2f, cx + o * 0.8f, cy + o * 1.3f, mouth) }
            "sleepy" -> c.drawOval(cx - mw * 0.25f, cy, cx + mw * 0.25f, cy + mw * 0.4f, mouth)
        }
    }

    private fun drawFace(canvas: Canvas, w: Float, h: Float, t: Float) {
        val m = eff()
        if (mood.isNotEmpty()) {
            drawMoodFace(canvas, w, h, t, m)
            return
        }
        drawClassicFace(canvas, w, h, t)
    }

    /**
     * Ember's moods. The eyes are the original Ember eyes (glancing, blinking, sleepy lids, ^ ^ when happy,
     * wide darting eyes in a panic); the mood adds a mouth, and when sad, streams of tears.
     */
    private fun drawMoodFace(canvas: Canvas, w: Float, h: Float, t: Float, m: String) {
        val happyLoop = m == "happy" && (t % 4.5f) > 3.6f
        if (happyLoop && happyUntil < t) happyUntil = t + 0.9f
        drawClassicFace(canvas, w, h, t)
        val cy = 0.68f * h + lookY * h
        val lx = (0.40f + lookX) * w; val rx = (0.60f + lookX) * w
        val ew = 0.075f * w; val eh = 0.13f * h
        mouth.alpha = (255 - 110 * calm).toInt()
        if (m == "sad" && tears) drawTears(canvas, w, h, t, lx, rx, cy, eh)
        // brows: raised in the middle when uneasy (sad), pulled down in the middle when cross (angry)
        if (m == "uneasy" || m == "cross") {
            eyeLine.strokeWidth = 0.032f * w
            val base = cy - eh * 1.0f; val lift = eh * 0.32f
            val inner = if (m == "cross") base + lift * 0.25f else base - lift
            val outer = if (m == "cross") base - lift else base + lift * 0.1f
            canvas.drawLine(lx - ew * 0.95f, outer, lx + ew * 0.75f, inner, eyeLine)
            canvas.drawLine(rx + ew * 0.95f, outer, rx - ew * 0.75f, inner, eyeLine)
        }
        if (m == "sleepy") {
            // z z z drifting up
            for (i in 0..2) {
                val k = ((t * 0.5f + i / 3f) % 1f)
                zz.color = Color.WHITE; zz.alpha = (220 * (1f - k)).toInt(); zz.textSize = w * (0.10f + 0.06f * k)
                canvas.drawText("z", w * (0.72f + 0.12f * k), h * (0.42f - 0.32f * k), zz)
            }
        }
        // not lit yet: a negative face, a frown instead of a smile
        val mm = if (!lit && m == "ready") "sad" else m
        drawMouth(canvas, w, h, w / 2 + lookX * w * 0.6f, cy + h * 0.115f, mm, t)
        if (shades) drawShades(canvas, w, lx, rx, cy, ew)
    }

    private val stream = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val drip = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Crying: a stream of water pours from under each eye, runs down the cheeks and drips off the bottom. */
    private fun drawTears(c: Canvas, w: Float, h: Float, t: Float, lx: Float, rx: Float, cy: Float, eh: Float) {
        val top = cy + eh * 0.18f
        val bottom = h * 0.985f
        val len = bottom - top
        for ((i, ex) in floatArrayOf(lx, rx).withIndex()) {
            val side = if (i == 0) -1f else 1f
            // the stream's centre line: leaves the eye, bows outward a little, wobbles as water does
            fun px(k: Float) = ex + side * w * (0.02f + 0.05f * k * k) + kotlin.math.sin(k * 9f - t * 7f + i) * w * 0.006f * k
            fun py(k: Float) = top + len * k
            val path = Path().apply { moveTo(px(0f), py(0f)); var k = 0.05f; while (k <= 1.001f) { lineTo(px(k), py(k)); k += 0.05f } }
            // body of the stream: wider as it falls
            stream.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(235, 150, 215, 255), Color.argb(150, 90, 175, 255), Shader.TileMode.CLAMP)
            stream.strokeWidth = w * 0.05f
            c.drawPath(path, stream)
            stream.shader = null
            // bright water running down it
            stream.color = Color.argb(220, 235, 248, 255); stream.strokeWidth = w * 0.014f
            for (j in 0..3) {
                val k0 = ((t * 1.25f + j / 4f + i * 0.13f) % 1f)
                val k1 = (k0 + 0.09f).coerceAtMost(1f)
                c.drawLine(px(k0), py(k0), px(k1), py(k1), stream)
            }
            // drops falling off the end
            val dk = ((t * 1.6f + i * 0.5f) % 1f)
            drip.color = Color.argb((210 * (1f - dk)).toInt(), 140, 205, 255)
            c.drawCircle(px(1f) + side * w * 0.01f * dk, bottom - h * 0.01f + dk * h * 0.04f, w * (0.022f - 0.008f * dk), drip)
        }
    }

    /** Sunglasses for a 30-day streak: two dark lenses, a bridge and a glint. */
    private fun drawShades(c: Canvas, w: Float, lx: Float, rx: Float, ey: Float, ew: Float) {
        val lw = ew * 1.55f; val lh = ew * 1.15f
        val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14151C") }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2E3A"); style = Paint.Style.STROKE; strokeWidth = w * 0.018f }
        for (ex in floatArrayOf(lx, rx)) {
            val r = RectF(ex - lw / 2, ey - lh * 0.45f, ex + lw / 2, ey + lh * 0.55f)
            c.drawRoundRect(r, lh * 0.45f, lh * 0.45f, lens); c.drawRoundRect(r, lh * 0.45f, lh * 0.45f, rim)
            shine.alpha = 170; c.drawLine(r.left + lw * 0.2f, r.top + lh * 0.3f, r.left + lw * 0.42f, r.top + lh * 0.12f, Paint(shine).apply { strokeWidth = w * 0.02f; strokeCap = Paint.Cap.ROUND }); shine.alpha = 255
        }
        c.drawLine(lx + lw / 2, ey - lh * 0.15f, rx - lw / 2, ey - lh * 0.15f, rim)
    }

    private fun drawClassicFace(canvas: Canvas, w: Float, h: Float, t: Float) {
        // glance around now and then
        if (t > nextLook) {
            tgtX = (rnd.nextFloat() - 0.5f) * 0.08f
            tgtY = (rnd.nextFloat() - 0.5f) * 0.04f
            if (rnd.nextFloat() < 0.35f) { tgtX = 0f; tgtY = 0f }
            nextLook = t + 1.2f + rnd.nextFloat() * 2.2f
        }
        lookX += (tgtX - lookX) * 0.12f; lookY += (tgtY - lookY) * 0.12f
        if (t > nextBlink) { blinkAt = t; nextBlink = t + 2.2f + rnd.nextFloat() * 2.8f; if (rnd.nextFloat() < 0.2f) nextBlink = t + 0.28f }

        val cy = 0.68f * h + lookY * h
        val lx = (0.40f + lookX) * w; val rx = (0.60f + lookX) * w
        val ew = 0.075f * w; val eh = 0.13f * h
        eyeLine.strokeWidth = 0.035f * w
        val alpha = (255 - 110 * calm).toInt()
        eye.alpha = alpha; eyeLine.alpha = alpha

        when {
            t < happyUntil -> {
                // ^ ^ happy eyes
                for (ex in floatArrayOf(lx, rx)) {
                    val p = Path().apply {
                        moveTo(ex - ew * 0.75f, cy + eh * 0.12f)
                        quadTo(ex, cy - eh * 0.55f, ex + ew * 0.75f, cy + eh * 0.12f)
                    }
                    canvas.drawPath(p, eyeLine)
                }
            }
            panic && !lit -> {
                // wide eyes, small pupils darting side to side
                val dart = if (kotlin.math.sin(t * 2.6f) > 0f) 1f else -1f
                val px = dart * ew * 0.28f
                val bt = t - blinkAt
                val open = if (bt in 0f..0.12f) kotlin.math.abs(bt - 0.06f) / 0.06f else 1f
                val r = ew * 0.78f
                for (ex in floatArrayOf(lx, rx)) {
                    val hh = kotlin.math.max(r * 0.15f, r * open)
                    canvas.drawOval(ex - r, cy - hh, ex + r, cy + hh, white)
                    eyeLine.strokeWidth = 0.012f * w
                    canvas.drawOval(ex - r, cy - hh, ex + r, cy + hh, eyeLine)
                    if (open > 0.5f) canvas.drawCircle(ex + px, cy + r * 0.05f, r * 0.42f, eye)
                }
                // sweat drop sliding down the side of the face, on a loop
                val k = (t % 1.6f) / 1.6f
                val sx0 = rx + r * 1.9f; val sy0 = cy - r * 1.6f + k * h * 0.12f
                sweat.alpha = (255 * (1f - k)).toInt()
                val dr = w * 0.035f
                val drop = Path().apply {
                    moveTo(sx0, sy0 - dr * 1.8f)
                    quadTo(sx0 + dr * 1.1f, sy0 - dr * 0.2f, sx0 + dr, sy0 + dr * 0.3f)
                    arcTo(sx0 - dr, sy0 - dr * 0.7f, sx0 + dr, sy0 + dr * 1.3f, 0f, 180f, false)
                    quadTo(sx0 - dr * 1.1f, sy0 - dr * 0.2f, sx0, sy0 - dr * 1.8f)
                    close()
                }
                canvas.drawPath(drop, sweat)
            }
            calm > 0.6f -> {
                // sleepy: soft, drooping lids
                for (ex in floatArrayOf(lx, rx)) {
                    val p = Path().apply {
                        moveTo(ex - ew * 0.7f, cy)
                        quadTo(ex, cy + eh * 0.28f, ex + ew * 0.7f, cy)
                    }
                    canvas.drawPath(p, eyeLine)
                }
            }
            else -> {
                // awake: rounded eyes that blink (a quick squash of the eye height)
                val bt = t - blinkAt
                val open = if (bt in 0f..0.16f) kotlin.math.abs(bt - 0.08f) / 0.08f else 1f
                val hh = kotlin.math.max(eh * 0.12f, eh * open)
                for (ex in floatArrayOf(lx, rx)) {
                    canvas.drawRoundRect(ex - ew / 2, cy - hh / 2, ex + ew / 2, cy + hh / 2, ew / 2, ew / 2, eye)
                    if (open > 0.6f) canvas.drawCircle(ex + ew * 0.12f, cy - hh * 0.22f, ew * 0.2f, shine)
                }
            }
        }
    }
}

/** The glowing ring that rides the edge of the page reveal. */
class GlowRingView(context: Context) : View(context) {
    private var cx = 0f; private var cy = 0f; private var r = 0f; private var a = 0f
    private val wide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    var color = Color.parseColor("#3D6BF0")

    fun play(x: Float, y: Float, maxR: Float, duration: Long, interp: android.animation.TimeInterpolator) {
        cx = x; cy = y
        visibility = VISIBLE
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration; interpolator = interp
            addUpdateListener {
                val t = it.animatedValue as Float
                r = maxR * t
                a = if (t < 0.15f) t / 0.15f else (1f - t).coerceAtLeast(0f) / 0.85f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { a = 0f; invalidate(); visibility = GONE }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (a <= 0f || r <= 0f) return
        val d = resources.displayMetrics.density
        wide.color = color; wide.alpha = (a * 70).toInt(); wide.strokeWidth = 26 * d
        thin.color = color; thin.alpha = (a * 230).toInt(); thin.strokeWidth = 3.5f * d
        canvas.drawCircle(cx, cy, r, wide)
        wide.alpha = (a * 40).toInt(); wide.strokeWidth = 48 * d
        canvas.drawCircle(cx, cy, r, wide)
        canvas.drawCircle(cx, cy, r, thin)
    }
}

/**
 * Sits behind the flame in the celebration: a soft pulsing halo that is always there,
 * plus [burst] = a shock ring and a spray of embers that fly out, slow down and fade.
 */
class EmberBurstView(context: Context) : View(context) {
    var color = Color.parseColor("#3D6BF0")
    var glow = Color.parseColor("#FFD23F")
    private class Ember(val ang: Float, val speed: Float, val size: Float, val hot: Boolean, val wobble: Float)
    private val embers = mutableListOf<Ember>()
    private var burstAt = -1f
    private val start = android.os.SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var running = false
    private fun now() = (android.os.SystemClock.uptimeMillis() - start) / 1000f
    /** Reduce Motion: a still halo and no burst. */
    private val still = Prefs.reduceMotion(context)

    fun burst() {
        if (still) return
        embers.clear()
        repeat(34) {
            embers.add(Ember(Random.nextFloat() * 6.2832f, 0.55f + Random.nextFloat() * 0.6f,
                0.012f + Random.nextFloat() * 0.018f, Random.nextFloat() < 0.4f, Random.nextFloat() * 6f))
        }
        burstAt = now(); invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val t = now()
        val cx = w / 2; val cy = h * 0.56f
        val m = minOf(w, h)
        // breathing halo
        val pulse = 0.5f + 0.5f * sin(t * 2.2f)
        val hr = m * (0.36f + 0.03f * pulse)
        p.shader = RadialGradient(cx, cy, hr, intArrayOf(
            Color.argb((90 + 40 * pulse).toInt(), Color.red(glow), Color.green(glow), Color.blue(glow)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, hr, p)
        p.shader = null

        if (burstAt >= 0f) {
            val e = t - burstAt
            // two shock rings, the second a beat behind
            for ((delay, width) in listOf(0f to 0.05f, 0.12f to 0.025f)) {
                val k = ((e - delay) / 0.7f)
                if (k in 0f..1f) {
                    val ease = 1f - (1f - k) * (1f - k) * (1f - k)
                    ring.color = if (delay == 0f) color else glow
                    ring.alpha = (255 * (1f - k)).toInt()
                    ring.strokeWidth = m * width * (1f - k * 0.6f)
                    canvas.drawCircle(cx, cy, m * (0.18f + 0.34f * ease), ring)
                }
            }
            // embers: fast out, drag, slight float upward and a wiggle
            val life = 1.3f
            if (e < life) {
                val k = e / life
                val travel = 1f - (1f - k) * (1f - k)
                for (em in embers) {
                    val d = m * 0.5f * em.speed * travel
                    val x = cx + cos(em.ang) * d + sin(e * 9f + em.wobble) * m * 0.01f
                    val y = cy + sin(em.ang) * d - m * 0.12f * k * k
                    p.color = if (em.hot) glow else color
                    p.alpha = (255 * (1f - k)).toInt()
                    canvas.drawCircle(x, y, m * em.size * (1f - 0.5f * k), p)
                }
            }
        }
        if (running && !still) postInvalidateOnAnimation()
    }
}
