package com.anjy.atlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Atlas's ember companion. Original design, drawn procedurally. */
class FlameView(context: Context, private val withGlow: Boolean = true, private val fps: Int = 30) : View(context) {

    enum class Mood(val scale: Float, val speed: Float, val sway: Float, val glow: Float, val embers: Float) {
        IDLE(1f, 1f, 1f, 1f, 2.2f),
        FOCUS(1f, 1.2f, 0.8f, 1.1f, 3f),
        ALERT(1.12f, 2.2f, 1.6f, 1.6f, 7f),
        HAPPY(1.08f, 1.6f, 1.2f, 1.5f, 6f),
        SLEEP(0.82f, 0.5f, 0.6f, 0.5f, 0.4f),
        THINKING(1.04f, 1.8f, 1.1f, 1.3f, 4f)
    }

    var mood: Mood = Mood.IDLE

    private var pScale = 1f; private var pSpeed = 1f; private var pSway = 1f; private var pGlow = 1f; private var pEmbers = 2f
    private var blinkIn = 2f + Random.nextFloat() * 3f
    private var blink = 0f
    private var lastMs = SystemClock.uptimeMillis()
    private var running = false

    private class Ember(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val decay: Float, val size: Float)
    private val embers = ArrayList<Ember>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A1606.toInt() }
    private val inkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A1606.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val layers = listOf(
        Triple(1.0f, 0f, intArrayOf(0xFFFF7A1A.toInt(), 0xFFD9381A.toInt())),
        Triple(0.78f, 0.12f, intArrayOf(0xFFFFB347.toInt(), 0xFFFF8A26.toInt())),
        Triple(0.54f, 0.22f, intArrayOf(0xFFFFF3D1.toInt(), 0xFFFFD27A.toInt()))
    )
    private val seeds = floatArrayOf(0f, 1.7f, 3.1f)

    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            // with the screen off nobody can see it: check back once a second instead of drawing
            if (power?.isInteractive == false) { postDelayed(this, 1000L); return }
            invalidate()
            postDelayed(this, (1000L / fps))
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; post(ticker) }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(ticker); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val vis = visibility == VISIBLE
        if (vis && !running) { running = true; post(ticker) } else if (!vis) { running = false; removeCallbacks(ticker) }
    }

    fun burst() { repeat(26) { spawn(1.8f) } }

    private fun halfw(h: Float): Float =
        if (h < 0.35f) { val k = (0.35f - h) / 0.35f; sqrt(max(0f, 1f - k * k)) }
        else max(0f, 1f - (h - 0.35f) / 0.65f).toDouble().pow(1.25).toFloat()

    private fun offset(h: Float, t: Float, r: Float): Float =
        sin(h * 3.2f - t * pSpeed * 2.2f) * r * 0.2f * pSway * h * h + sin(t * pSpeed * 1.3f) * r * 0.05f * h

    private fun shape(cx: Float, by: Float, r: Float, t: Float, seed: Float) {
        val hh = r / 0.35f
        val n = 48
        val lx = FloatArray(n + 1); val rx = FloatArray(n + 1); val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val h = i.toFloat() / n
            val hw = halfw(h)
            val w = hw * r
            val off = offset(h, t, r)
            val damp = min(1f, hw * 2.5f)
            val nl = sin(h * 11f + t * pSpeed * 5f + seed) * r * 0.06f * h * damp
            val nr = sin(h * 13f - t * pSpeed * 4.4f + seed * 2f) * r * 0.06f * h * damp
            lx[i] = cx + off - w + nl; rx[i] = cx + off + w + nr; ys[i] = by - h * hh
        }
        path.reset()
        path.moveTo(lx[0], ys[0])
        for (i in 0..n) path.lineTo(lx[i], ys[i])
        for (i in n downTo 0) path.lineTo(rx[i], ys[i])
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val nowMs = SystemClock.uptimeMillis()
        val dt = min(0.1f, (nowMs - lastMs) / 1000f)
        lastMs = nowMs
        val t = nowMs / 1000f
        val k = min(1f, dt * 3f)
        pScale += (mood.scale - pScale) * k; pSpeed += (mood.speed - pSpeed) * k
        pSway += (mood.sway - pSway) * k; pGlow += (mood.glow - pGlow) * k; pEmbers += (mood.embers - pEmbers) * k

        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val base = min(w * 0.22f, h * 0.2f)
        val r = base * pScale * (1f + 0.025f * sin(t * 2f))
        val cx = w / 2f
        val by = h * 0.9f - r * 0.1f

        if (withGlow) {
            val gcy = by - r * 1.1f
            val gr = max(r, min(min(r * 3.4f, w / 2f), min(h - gcy, gcy)))
            val a = (0.28f * pGlow).coerceIn(0f, 1f)
            glowPaint.shader = RadialGradient(cx, gcy, gr, intArrayOf(argb(a, 255, 140, 40), argb(0f, 255, 90, 20)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, gcy, gr, glowPaint)
        }

        for ((i, layer) in layers.withIndex()) {
            val lr = r * layer.first
            val y = by - r * layer.second
            fill.shader = LinearGradient(0f, y, 0f, y - lr / 0.35f, layer.third[0], layer.third[1], Shader.TileMode.CLAMP)
            shape(cx, y, lr, t, seeds[i])
            canvas.drawPath(path, fill)
        }
        face(canvas, cx, by, r, t, dt)
        drawEmbers(canvas, r, dt)
    }

    private fun face(canvas: Canvas, cx: Float, by: Float, r: Float, t: Float, dt: Float) {
        val eh = 0.36f
        val ox = offset(eh, t, r)
        val ey = by - eh * (r / 0.35f) + r * 0.05f
        val dx = r * 0.3f
        inkStroke.strokeWidth = max(2f, r * 0.075f)
        blinkIn -= dt
        if (blinkIn <= 0f) { blink = 0.14f; blinkIn = 3f + Random.nextFloat() * 3.5f }
        if (blink > 0f) blink -= dt
        val closed = blink > 0f
        for (s in intArrayOf(-1, 1)) {
            val x = cx + ox + s * dx
            when (mood) {
                Mood.HAPPY -> { rect.set(x - r * 0.1f, ey + r * 0.04f - r * 0.1f, x + r * 0.1f, ey + r * 0.04f + r * 0.1f); canvas.drawArc(rect, 198f, 144f, false, inkStroke) }
                Mood.SLEEP -> { rect.set(x - r * 0.09f, ey - r * 0.02f - r * 0.09f, x + r * 0.09f, ey - r * 0.02f + r * 0.09f); canvas.drawArc(rect, 27f, 126f, false, inkStroke) }
                else -> {
                    val ew = r * (if (mood == Mood.ALERT) 0.13f else 0.1f)
                    var eh2 = r * when (mood) { Mood.ALERT -> 0.24f; Mood.FOCUS -> 0.12f; else -> 0.19f }
                    if (closed) eh2 = r * 0.025f
                    rect.set(x - ew / 2, ey - eh2 / 2, x + ew / 2, ey + eh2 / 2)
                    val cr = min(ew, eh2) / 2
                    canvas.drawRoundRect(rect, cr, cr, ink)
                }
            }
        }
        when (mood) {
            Mood.ALERT -> { rect.set(cx + ox - r * 0.06f, ey + r * 0.26f - r * 0.075f, cx + ox + r * 0.06f, ey + r * 0.26f + r * 0.075f); canvas.drawOval(rect, ink) }
            Mood.HAPPY -> { rect.set(cx + ox - r * 0.12f, ey + r * 0.14f - r * 0.12f, cx + ox + r * 0.12f, ey + r * 0.14f + r * 0.12f); canvas.drawArc(rect, 27f, 126f, false, inkStroke) }
            Mood.THINKING -> {
                val phase = ((t * 2.5f) % 3f).toInt()
                for (i in 0 until 3) {
                    ink.alpha = if (i == phase) 255 else 110
                    canvas.drawCircle(cx + ox + (i - 1) * r * 0.14f, ey + r * 0.28f, r * 0.035f, ink)
                }
                ink.alpha = 255
            }
            else -> {}
        }
    }

    private fun spawn(boost: Float) {
        val base = min(width * 0.22f, height * 0.2f) * pScale
        embers.add(Ember(
            width / 2f + (Random.nextFloat() - 0.5f) * base * 1.2f,
            height * 0.9f - base * (0.6f + Random.nextFloat() * 1.2f),
            (Random.nextFloat() - 0.5f) * 18f * boost * resources.displayMetrics.density / 2f,
            -(20f + Random.nextFloat() * 40f) * boost * resources.displayMetrics.density / 2f,
            1f, 0.35f + Random.nextFloat() * 0.5f, 1f + Random.nextFloat() * 2.2f
        ))
    }

    private fun drawEmbers(canvas: Canvas, r: Float, dt: Float) {
        if (Random.nextFloat() < pEmbers * dt && embers.size < 60) spawn(1f)
        val d = resources.displayMetrics.density / 2f
        val it = embers.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.x += e.vx * dt + sin(e.y * 0.05f) * 0.3f; e.y += e.vy * dt; e.life -= e.decay * dt
            if (e.life <= 0f) { it.remove(); continue }
            emberPaint.color = argb(e.life, 255, (150 + 80 * e.life).toInt(), 90)
            canvas.drawCircle(e.x, e.y, (e.size * e.life + 0.4f) * d, emberPaint)
        }
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

}
