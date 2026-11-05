package com.anjy.atlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Depth: the app's sense of space. A living background that drifts and leans with the phone,
 * glass panels you can see the light through, cards that tilt under your finger, and the streak
 * card as a small stage with embers rising behind the flame.
 */

/** How the phone is being held, smoothed, in -1..1 on each axis. Read by anything that wants parallax. */
object Tilt : SensorEventListener {
    @Volatile var x = 0f; private set
    @Volatile var y = 0f; private set
    private var baseX = Float.NaN
    private var baseY = Float.NaN
    private var sm: SensorManager? = null

    fun start(c: Context) {
        if (sm != null) return
        val m = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m; baseX = Float.NaN
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() { sm?.unregisterListener(this); sm = null }

    override fun onSensorChanged(e: SensorEvent) {
        val ax = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
        val ay = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
        if (baseX.isNaN()) { baseX = ax; baseY = ay }
        // the resting angle slowly becomes "neutral", so it reacts to movement, not to how you hold it
        baseX += (ax - baseX) * 0.01f; baseY += (ay - baseY) * 0.01f
        x += (((ax - baseX) * 2.2f).coerceIn(-1f, 1f) - x) * 0.12f
        y += (((ay - baseY) * 2.2f).coerceIn(-1f, 1f) - y) * 0.12f
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/**
 * The night sky behind every page: deep violet-blue, a soft bank of glowing blue clouds that drifts
 * slowly and leans with the phone, twinkling stars in front and behind, and now and then a shooting star.
 * The clouds are painted once (from noise, so they look natural) and then just moved around.
 */
class AuroraView(context: Context, private val bg: Int, private val dark: Boolean,
                 private val blue: Int, private val warm: Int) : View(context) {
    var scrollRef: android.widget.ScrollView? = null
    var still = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private var sky: Shader? = null
    private var cloudA: android.graphics.Bitmap? = null
    private var cloudB: android.graphics.Bitmap? = null
    @Volatile private var painting = false
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rpath = Path()
    private var ribbonShaders: List<Shader> = emptyList()
    private var cloudBorn = 0L
    private val src = android.graphics.Rect()
    private val dst = RectF()
    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float, val depth: Float, val tint: Int)
    private val stars = List(120) {
        val tints = intArrayOf(Color.rgb(235, 238, 255), Color.rgb(200, 190, 255), Color.rgb(190, 215, 255))
        Star(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.3f + 0.4f, Random.nextFloat() * 6.28f,
            Random.nextFloat() * 0.8f + 0.3f, Random.nextFloat() * 0.8f + 0.2f, tints[Random.nextInt(tints.size)])
    }
    private var shootAt = 4f
    private var shootX = 0f; private var shootY = 0f; private var shootA = 0f
    private val t0 = System.nanoTime()
    private var running = false

    // ---- cloud painting: value noise + soft puffs, lit from above
    private fun noise(seed: Int, fx: Int, fy: Int): (Float, Float) -> Float {
        val r = java.util.Random(seed.toLong())
        val g = Array(fy + 2) { FloatArray(fx + 2) { r.nextFloat() } }
        return { u, v ->
            val x = u * fx; val y = v * fy
            val x0 = x.toInt().coerceIn(0, fx); val y0 = y.toInt().coerceIn(0, fy)
            var tx = x - x0; var ty = y - y0
            tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
            val a = g[y0][x0]; val b = g[y0][x0 + 1]; val c = g[y0 + 1][x0]; val d = g[y0 + 1][x0 + 1]
            (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
        }
    }

    private fun fbm(seed: Int, base: Int, w: Int, h: Int): Array<FloatArray> {
        val oct = (0 until 6).map { o -> val f = base shl o; noise(seed + o, f, maxOf(1, f * h / w)) }
        return Array(h) { y -> FloatArray(w) { x ->
            var v = 0f; var amp = 0.5f; var tot = 0f
            for (n in oct) { v += amp * n(x / w.toFloat(), y / h.toFloat()); tot += amp; amp *= 0.5f }
            v / tot
        } }
    }

    private fun blur(a: Array<FloatArray>, r: Int): Array<FloatArray> {
        val h = a.size; val w = a[0].size
        var cur = a
        repeat(2) {
            val tmp = Array(h) { FloatArray(w) }
            for (y in 0 until h) { var s = 0f; for (x in -r..r) s += cur[y][x.coerceIn(0, w - 1)]
                for (x in 0 until w) { tmp[y][x] = s / (2 * r + 1); s += cur[y][(x + r + 1).coerceAtMost(w - 1)] - cur[y][(x - r).coerceAtLeast(0)] } }
            val out = Array(h) { FloatArray(w) }
            for (x in 0 until w) { var s = 0f; for (y in -r..r) s += tmp[y.coerceIn(0, h - 1)][x]
                for (y in 0 until h) { out[y][x] = s / (2 * r + 1); s += tmp[(y + r + 1).coerceAtMost(h - 1)][x] - tmp[(y - r).coerceAtLeast(0)][x] } }
            cur = out
        }
        return cur
    }

    /** Paint one cloud bank into a small bitmap. [lo]..[hi] is where the base of the bank sits (0..1). */
    private fun paintClouds(seed: Int, w: Int, h: Int, lo: Float, hi: Float, strength: Float): android.graphics.Bitmap {
        val n = fbm(seed, 2, w, h)
        val n2 = fbm(seed + 29, 4, w, h)
        val rr = java.util.Random(seed.toLong() * 7)
        val dens = Array(h) { FloatArray(w) }
        fun puffs(count: Int, y0: Float, y1: Float, r0: Float, r1: Float, k: Float) = repeat(count) {
            val cx = (rr.nextFloat() * 1.2f - 0.1f) * w; val cy = h * (y0 + rr.nextFloat() * (y1 - y0)); val r = w * (r0 + rr.nextFloat() * (r1 - r0))
            val ys = ((cy - r * 1.6f).toInt()).coerceAtLeast(0); val ye = ((cy + r * 1.6f).toInt()).coerceAtMost(h - 1)
            val xs = ((cx - r * 1.8f).toInt()).coerceAtLeast(0); val xe = ((cx + r * 1.8f).toInt()).coerceAtMost(w - 1)
            for (y in ys..ye) for (x in xs..xe) {
                val dx = (x - cx) / r; val dy = (y - cy) * 1.15f / r
                dens[y][x] += kotlin.math.exp(-(dx * dx + dy * dy) * 2.2f) * k
            }
        }
        puffs(26, lo, hi, 0.10f, 0.19f, 1f)
        puffs(30, lo - 0.12f, lo + 0.02f, 0.05f, 0.11f, 0.8f)
        for (y in 0 until h) for (x in 0 until w) {
            var d = 1f - kotlin.math.exp(-dens[y][x] * 0.9f)
            d = (d * (0.35f + 1.3f * n2[y][x]) - (1f - n[y][x]) * 0.25f).coerceIn(0f, 1f)
            dens[y][x] = (d * 1.25f).coerceIn(0f, 1f)
        }
        val bl = blur(dens, (3f * w / 190f).roundToInt().coerceAtLeast(3))
        val cloud = if (dark) Color.rgb(34, 40, 135) else Color.rgb(200, 214, 255)
        val light = if (dark) Color.rgb(78, 92, 212) else Color.rgb(255, 255, 255)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val up = bl[(y - (4f * w / 190f).roundToInt()).coerceAtLeast(0)][x]
            val lit = (((bl[y][x] - up) * 2f).coerceIn(0f, 1f) * 0.8f + (n2[y][x] - 0.5f).coerceIn(0f, 1f) * 0.6f).coerceIn(0f, 1f)
            val c = mix(light, cloud, lit)
            val a = (dens[y][x] * 0.88f * strength * 255).toInt().coerceIn(0, 255)
            px[y * w + x] = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            if (dark) intArrayOf(Color.parseColor("#0A0A26"), Color.parseColor("#0E0D33"), Color.parseColor("#13103A")) else intArrayOf(mix(Color.WHITE, bg, 0.3f), bg, bg),
            null, Shader.TileMode.CLAMP)
        // light ribbons fade in and out along their length: blue into violet into a warm end
        fun rib(a: Int, c1: Int, c2: Int, c3: Int) = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(0, Color.red(c1), Color.green(c1), Color.blue(c1)), Color.argb(a, Color.red(c1), Color.green(c1), Color.blue(c1)),
                Color.argb(a, Color.red(c2), Color.green(c2), Color.blue(c2)), Color.argb(a * 2 / 3, Color.red(c3), Color.green(c3), Color.blue(c3)), Color.argb(0, Color.red(c3), Color.green(c3), Color.blue(c3))),
            floatArrayOf(0f, 0.2f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP)
        val ra = if (dark) 80 else 45
        val violet = Color.parseColor("#8B6CFF")
        ribbonShaders = listOf(rib(ra, blue, violet, warm), rib(ra * 3 / 4, Color.parseColor("#4FC3FF"), blue, violet), rib(ra * 2 / 3, violet, blue, Color.parseColor("#4FC3FF")))
        if (cloudA == null && w > 0 && h > 0 && !painting) {
            // small bitmaps, 1.6x wider than the screen so they can drift; drawn scaled up (soft anyway).
            // Painted off the main thread so opening the app never waits on it; they fade in when ready.
            painting = true
            val cw = 240; val ch = (240 * (h.toFloat() / (w * 1.6f))).toInt().coerceAtLeast(120)
            Thread {
                val a = try { paintClouds(11, cw, ch, 0.40f, 0.52f, 1f) } catch (e: Throwable) { null }
                val b = try { paintClouds(53, cw, ch, 0.80f, 0.88f, 0.55f) } catch (e: Throwable) { null }
                post { cloudA = a; cloudB = b; cloudBorn = System.nanoTime(); invalidate() }
            }.start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    fun resume() { if (!running) { running = true; postInvalidateOnAnimation() } }
    fun pause() { running = false }

    private fun drawBank(c: Canvas, b: android.graphics.Bitmap?, w: Float, h: Float, shiftX: Float, shiftY: Float, swell: Float = 1f) {
        b ?: return
        src.set(0, 0, b.width, b.height)
        // swell: the bank slowly billows (grows and settles a little) around its middle
        val bw = w * 1.6f * swell; val bh = h * (1f + (swell - 1f) * 0.6f)
        val cx = -w * 0.3f + shiftX + w * 0.8f; val cy = shiftY + h / 2
        dst.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        c.drawBitmap(b, src, dst, bp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = if (still) 0f else (System.nanoTime() - t0) / 1e9f
        val sy = (scrollRef?.scrollY ?: 0).toFloat()
        val tx = Tilt.x * w * 0.05f; val ty = Tilt.y * h * 0.025f
        val d = resources.displayMetrics.density
        p.shader = sky; c.drawRect(0f, 0f, w, h, p); p.shader = null
        // far stars (behind the clouds)
        fun starsLayer(near: Boolean) {
            for (s in stars) {
                if ((s.depth > 0.6f) != near) continue
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * s.speed * 2f + s.phase))
                val x = ((s.x * w - tx * s.depth * 1.5f) % w + w) % w
                val y = ((s.y * h - sy * 0.12f * s.depth) % h + h) % h
                p.color = s.tint; p.alpha = ((if (dark) 230 else 90) * tw * (0.4f + 0.6f * s.depth)).toInt()
                c.drawCircle(x, y, s.r * d * 0.75f, p)
            }
        }
        starsLayer(false)
        // flowing light ribbons: bands of fine lines drifting like silk, each at its own depth, behind the clouds
        val bands = listOf(floatArrayOf(0.16f, 0.9f, 1.0f), floatArrayOf(0.60f, 0.55f, 0.6f), floatArrayOf(0.90f, 0.35f, 0.8f))
        bands.forEachIndexed { bi, band ->
            val sh = ribbonShaders.getOrNull(bi) ?: return@forEachIndexed
            ribbon.shader = sh; ribbon.strokeWidth = 1.1f * d
            val base = h * band[0] - sy * 0.15f * band[1] - ty * band[1] * 2f
            val amp = h * 0.045f
            for (li in 0 until 7) {
                rpath.reset()
                val off = li * 7f * d
                val ph = band[2] * 3f + li * 0.22f
                var x = -20f
                var first = true
                while (x <= w + 20f) {
                    val u = x / w
                    val y = base + off + amp * sin(u * 5.2f + t * 0.22f + ph) + amp * 0.6f * sin(u * 2.3f - t * 0.13f + bi) - tx * band[1] * 0.5f * u
                    if (first) { rpath.moveTo(x, y); first = false } else rpath.lineTo(x, y)
                    x += w / 36f
                }
                ribbon.alpha = (255 * (1f - kotlin.math.abs(li - 3) / 4f)).toInt()
                c.drawPath(rpath, ribbon)
            }
        }
        ribbon.shader = null
        bp.alpha = (((System.nanoTime() - cloudBorn) / 1e9f) / 0.8f * 255).toInt().coerceIn(0, 255)
        // the clouds drift slowly side to side, lean with the phone and rise a little as you scroll
        // gentle, visible motion: a slow drift (about two minutes each way), a soft rise and fall, and billowing
        drawBank(c, cloudB, w, h, sin(t * 0.04f + 2f) * w * 0.10f - tx * 0.6f, -sy * 0.08f - ty * 0.6f + sin(t * 0.07f + 1f) * h * 0.008f, 1f + 0.02f * sin(t * 0.06f + 3f))
        drawBank(c, cloudA, w, h, sin(t * 0.05f) * w * 0.12f - tx, -sy * 0.15f - ty + sin(t * 0.09f) * h * 0.01f, 1f + 0.025f * sin(t * 0.075f))
        starsLayer(true)
        // a shooting star every so often
        if (dark && !still) {
            if (t > shootAt && shootA <= 0f) { shootX = Random.nextFloat() * w * 0.7f + w * 0.2f; shootY = Random.nextFloat() * h * 0.35f; shootA = 1f; shootAt = t + 7f + Random.nextFloat() * 9f }
            if (shootA > 0f) {
                val k = 1f - shootA
                val hx = shootX - k * w * 0.35f; val hy = shootY + k * w * 0.15f
                p.strokeWidth = 1.6f * d; p.strokeCap = Paint.Cap.ROUND
                p.shader = LinearGradient(hx, hy, hx + 60 * d, hy - 26 * d, Color.argb((230 * shootA).toInt(), 235, 240, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawLine(hx, hy, hx + 60 * d, hy - 26 * d, p); p.shader = null
                shootA -= 0.045f
            }
        }
        // 30 fps is plenty for a sky this slow
        if (running && !still) postInvalidateDelayed(33)
    }
}

/**
 * Glass panel: slightly see-through so the light behind shows, brighter at the top like a sheet of
 * glass catching light, with a rim that is bright on top and fades away underneath.
 */
class GlassDrawable(private val fill: Int, private val radius: Float, private val dark: Boolean, private val tint: Int? = null, private val tintAmount: Float = 0f, private val density: Float = 2.6f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; r.set(b); if (r.width() <= 0) return
        val top = mix(Color.WHITE, fill, if (dark) 0.06f else 0f)
        val base = if (tint != null) mix(tint, fill, tintAmount) else fill
        val a = if (dark) 0xE0 else 0xFF
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.right * 0.4f, r.bottom,
            intArrayOf(withA(if (tint != null) mix(tint, top, tintAmount * 1.3f) else top, a), withA(base, a), withA(fill, a)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        // rim
        val sw = 1.1f * density
        r.inset(0.6f, 0.6f)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            if (dark) intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(8, 255, 255, 255)) else intArrayOf(Color.argb(30, 20, 40, 90), Color.argb(18, 20, 40, 90)),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
    }
    private fun withA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radius) }
}

internal fun mix(a: Int, b: Int, t: Float): Int {
    val u = 1 - t
    return Color.rgb((Color.red(a) * t + Color.red(b) * u).toInt(), (Color.green(a) * t + Color.green(b) * u).toInt(), (Color.blue(a) * t + Color.blue(b) * u).toInt())
}

/** A panel that leans toward your finger in 3D when you press it, then settles back. */
open class TiltLayout(context: Context) : LinearLayout(context) {
    var tiltOn = true
    protected var touching = false
    init { cameraDistance = 9000 * resources.displayMetrics.density }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (tiltOn) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                val nx = ((ev.x / width.coerceAtLeast(1)) - 0.5f) * 2f
                val ny = ((ev.y / height.coerceAtLeast(1)) - 0.5f) * 2f
                val k = (400f / height.coerceAtLeast(1)).coerceIn(0.25f, 1f)   // tall panels lean less
                animate().rotationY(nx * 5f * k).rotationX(-ny * 5f * k).scaleX(0.985f).scaleY(0.985f).setDuration(120).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settle()
        }
        val handled = super.dispatchTouchEvent(ev)
        if (tiltOn && ev.actionMasked == MotionEvent.ACTION_DOWN && !handled) postDelayed({ settle() }, 160)
        return handled
    }

    private fun settle() {
        touching = false
        animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
    }
}

/**
 * The streak card as a small stage: glass, embers rising behind the content, and a rim of light
 * (the icon's blue running into orange) that slowly travels around the edge. It leans with the phone.
 */
class StageLayout(context: Context, private val fill: Int, private val dark: Boolean, private val blue: Int, private val warm: Int) : TiltLayout(context) {
    var emberColor = warm
    var intensity = 0.5f          // 0..1, grows with the streak
    var panic = false
    var still = false
    private val radius = 26 * resources.displayMetrics.density
    private var glass = GlassDrawable(fill, radius, dark, warm, if (dark) 0.14f else 0.08f, resources.displayMetrics.density)

    /** Colour the card's glass (the streak card follows the time of day). */
    fun tintCard(c: Int, amount: Float) {
        glass = GlassDrawable(fill, radius, dark, c, amount, resources.displayMetrics.density)
        glass.setBounds(0, 0, width, height); invalidate()
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val r = RectF()
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    private class Ember(var x: Float, var y: Float, val vx: Float, val vy: Float, val size: Float, var life: Float, val maxLife: Float, val wob: Float)
    private val embers = mutableListOf<Ember>()
    private var last = 0L
    private var t = 0f
    private var running = false

    init { setWillNotDraw(false); background = null; clipToOutline = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        glass.setBounds(0, 0, w, h)
        clip.reset(); r.set(0f, 0f, w.toFloat(), h.toFloat()); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        val cx = w / 2f; val cy = h / 2f
        sweep = SweepGradient(cx, cy, intArrayOf(blue, Color.argb(0, Color.red(blue), Color.green(blue), Color.blue(blue)), Color.argb(0, 0, 0, 0),
            Color.argb(0, Color.red(warm), Color.green(warm), Color.blue(warm)), warm, Color.argb(0, Color.red(warm), Color.green(warm), Color.blue(warm)), Color.argb(0, 0, 0, 0), blue),
            floatArrayOf(0f, 0.18f, 0.35f, 0.45f, 0.6f, 0.75f, 0.9f, 1f))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0L; invalidate() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now; if (!still) t += dt
        glass.draw(c)
        val w = width.toFloat(); val h = height.toFloat()
        // embers rise from the lower left, where the flame sits
        c.save(); c.clipPath(clip)
        val rate = (if (panic) 40f else 6f + 26f * intensity) * dt
        if (!still) {
            var n = rate + (if (Random.nextFloat() < rate % 1f) 1 else 0)
            while (n >= 1f && embers.size < 70) {
                val d = resources.displayMetrics.density
                embers.add(Ember(w * (0.08f + Random.nextFloat() * 0.38f), h * (0.75f + Random.nextFloat() * 0.25f),
                    (Random.nextFloat() - 0.4f) * 18f * d, -(24f + Random.nextFloat() * 46f) * d * (if (panic) 1.6f else 1f),
                    (Random.nextFloat() * 2.2f + 1f) * d, 0f, 1.6f + Random.nextFloat() * 2.2f, Random.nextFloat() * 6.28f))
                n -= 1f
            }
        }
        val col = if (panic) Color.parseColor("#FF4D5E") else emberColor
        val it = embers.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!still) { e.life += dt; e.x += (e.vx + sin(t * 3f + e.wob) * 10f) * dt; e.y += e.vy * dt }
            val f = e.life / e.maxLife
            if (f >= 1f || e.y < -10f) { it.remove(); continue }
            val a = (if (f < 0.15f) f / 0.15f else 1f - (f - 0.15f) / 0.85f).coerceIn(0f, 1f)
            p.color = Color.argb((210 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(e.x, e.y, e.size * (1f - f * 0.5f), p)
            p.color = Color.argb((50 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(e.x, e.y, e.size * 3f, p)
        }
        c.restore()
        // gentle lean with the phone
        if (!still && tiltOn && !touching) { rotationY = Tilt.x * 4f; rotationX = Tilt.y * 3f }
        if (running && !still) postInvalidateOnAnimation()
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val sw = sweep ?: return
        m.reset(); m.setRotate(t * 40f % 360f, width / 2f, height / 2f); sw.setLocalMatrix(m)
        rim.shader = sw; rim.strokeWidth = 2f * resources.displayMetrics.density
        r.set(0f, 0f, width.toFloat(), height.toFloat()); r.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
        if (panic) { rim.shader = null; rim.color = Color.argb((160 + 90 * sin(t * 8f)).toInt().coerceIn(0, 255), 255, 77, 94) }
        c.drawRoundRect(r, radius, radius, rim)
    }
}

/** Paint a TextView's letters with a vertical gradient (used for the big streak number). */
fun android.widget.TextView.gradientText(top: Int, bottom: Int) {
    post {
        val h = (lineHeight).toFloat().coerceAtLeast(1f)
        paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        invalidate()
    }
}

@Suppress("unused") private val TAU = (2 * PI).toFloat()

/**
 * The glowing circle morph. The page you're leaving is a frozen picture on top; a circle opens in it
 * and the new page shows through the hole, with a glowing ring riding the edge. Meanwhile the old page
 * sinks back and drifts away. [progress] (0..1) can be driven by a finger (swipe) or an animation (tap).
 */
class MorphView(context: Context, private val bg: Int) : View(context) {
    var bitmap: android.graphics.Bitmap? = null
    var cx = 0f; var cy = 0f; var dir = 1
    var ring = Color.parseColor("#3D6BF0")
    var warm = Color.parseColor("#FF7A2E")
    var progress = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val path = Path()
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun maxR(): Float {
        val w = width.toFloat(); val h = height.toFloat()
        return kotlin.math.hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat() + 30f * resources.displayMetrics.density
    }

    // the old page breaks into tiles; each one spins, shrinks and flies outward as the ring reaches it
    private class Tile(val sx: Int, val sy: Int, val sw: Int, val sh: Int, val spin: Float, val jx: Float, val jy: Float)
    private var tiles: List<Tile> = emptyList()
    private var tilesFor: android.graphics.Bitmap? = null
    private val src = android.graphics.Rect()
    private val dst = RectF()

    private fun buildTiles(b: android.graphics.Bitmap) {
        val cols = 7
        val tw = b.width / cols
        val rows = (b.height + tw - 1) / tw
        val rnd = Random(b.hashCode())
        tiles = (0 until rows).flatMap { r -> (0 until cols).map { c0 ->
            val x = c0 * tw; val y = r * tw
            Tile(x, y, if (c0 == cols - 1) b.width - x else tw, minOf(tw, b.height - y), (rnd.nextFloat() - 0.5f) * 70f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
        } }
        tilesFor = b
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        if (tilesFor !== b) buildTiles(b)
        val p = progress
        val maxR = maxR()
        val r = maxR * p
        val d = resources.displayMetrics.density
        val band = 90 * d
        c.drawColor(Color.TRANSPARENT)
        for (t in tiles) {
            val tcx = t.sx + t.sw / 2f; val tcy = t.sy + t.sh / 2f
            val dx = tcx - cx; val dy = tcy - cy
            val dist = kotlin.math.hypot(dx, dy)
            val q = ((r + band - dist) / (band * 2.4f)).coerceIn(0f, 1f)
            src.set(t.sx, t.sy, t.sx + t.sw, t.sy + t.sh)
            if (q <= 0f) {
                dst.set(t.sx.toFloat(), t.sy.toFloat(), (t.sx + t.sw).toFloat(), (t.sy + t.sh).toFloat())
                bp.alpha = 255; c.drawBitmap(b, src, dst, bp)
                continue
            }
            if (q >= 1f) continue
            val e = q * q * (3 - 2 * q)          // smooth start and end
            val ux = if (dist > 1f) dx / dist else 0f; val uy = if (dist > 1f) dy / dist else -1f
            val push = 70 * d * e
            c.save()
            c.translate(tcx + ux * push + t.jx * 30 * d * e, tcy + uy * push + t.jy * 30 * d * e - 12 * d * e)
            c.rotate(t.spin * e)
            val sc = 1f - 0.5f * e
            c.scale(sc, sc)
            dst.set(-t.sw / 2f, -t.sh / 2f, t.sw / 2f, t.sh / 2f)
            bp.alpha = (255 * (1f - e)).toInt()
            c.drawBitmap(b, src, dst, bp)
            c.restore()
        }
        if (r > 0f && p < 1f) {
            val a = if (p < 0.12f) p / 0.12f else (1f - p) / 0.88f
            val col = ring
            wide.color = col; wide.alpha = (a * 40).toInt(); wide.strokeWidth = 48 * d; c.drawCircle(cx, cy, r, wide)
            wide.alpha = (a * 75).toInt(); wide.strokeWidth = 24 * d; c.drawCircle(cx, cy, r, wide)
            // a warm inner edge, like the icon's orange, under the bright blue line
            wide.color = warm; wide.alpha = (a * 60).toInt(); wide.strokeWidth = 8 * d; c.drawCircle(cx, cy, maxOf(0f, r - 6 * d), wide)
            thin.color = mix(Color.WHITE, ring, 0.35f); thin.alpha = (a * 240).toInt(); thin.strokeWidth = 3.5f * d; c.drawCircle(cx, cy, r, thin)
        }
    }
}
