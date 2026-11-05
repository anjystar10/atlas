package com.anjy.atlas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Atlas's face: a living blob of blue and violet light. It drifts slowly at rest, stirs while Atlas works,
 * and swells with your voice while it listens.
 */
class OrbView(context: Context) : View(context) {
    /** Loudness of the voice it's hearing, 0..1 (eased while drawing). */
    var level = 0f
    /** 0 at rest, 1 while thinking or listening. */
    var energy = 0f
    var still = false
    private var shownLevel = 0f
    private var shownEnergy = 0f
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private val path = Path()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val swirl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sized = 0f

    private val phases = floatArrayOf(0.3f, 1.7f, 2.9f, 4.1f, 5.3f)
    private val speeds = floatArrayOf(0.31f, -0.23f, 0.41f, -0.37f, 0.53f)
    private val harmonics = intArrayOf(2, 3, 4, 5, 7)
    private val weights = floatArrayOf(1f, 0.7f, 0.45f, 0.3f, 0.18f)

    private fun setup(r: Float) {
        sized = r
        glow.maskFilter = BlurMaskFilter(r * 0.42f, BlurMaskFilter.Blur.NORMAL)
        rim.maskFilter = BlurMaskFilter(r * 0.05f, BlurMaskFilter.Blur.NORMAL)
        rim.strokeWidth = r * 0.07f
        swirl.maskFilter = BlurMaskFilter(r * 0.06f, BlurMaskFilter.Blur.NORMAL)
        shine.maskFilter = BlurMaskFilter(r * 0.12f, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(w, h) * 0.34f
        if (r != sized) setup(r)
        val cx = w / 2; val cy = h / 2
        val t = if (still) 0f else (SystemClock.uptimeMillis() - start) / 1000f
        shownLevel += (level - shownLevel) * 0.25f
        shownEnergy += (energy - shownEnergy) * 0.08f
        val amp = 0.045f + 0.05f * shownEnergy + 0.12f * shownLevel
        val pace = 1f + 1.8f * shownEnergy
        val scale = 1f + 0.10f * shownLevel

        path.reset()
        val n = 96
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            var k = 1f
            for (j in harmonics.indices) k += amp * weights[j] * sin(harmonics[j] * a + phases[j] + t * speeds[j] * pace)
            val rr = r * k * scale
            val x = cx + rr * cos(a); val y = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // the light it gives off
        glow.color = Color.argb((90 + 60 * shownEnergy).toInt().coerceAtMost(170), 70, 110, 255)
        c.drawPath(path, glow)
        // the body: deep navy at the heart, bright blue toward the light, a touch of violet
        body.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.55f,
            intArrayOf(0xFFB7DCFF.toInt(), 0xFF4C7DFF.toInt(), 0xFF2236C9.toInt(), 0xFF140F5C.toInt(), 0xFF080A2A.toInt()),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, body)
        // swirls of light inside, turning slowly
        c.save()
        c.clipPath(path)
        c.rotate(t * 14f * pace, cx, cy)
        for (k in 0 until 3) {
            val ang = k * 2.1f + t * 0.2f
            swirl.strokeWidth = r * (0.10f - k * 0.02f)
            swirl.color = if (k == 1) Color.argb(150, 170, 140, 255) else Color.argb(170, 120, 210, 255)
            val p = Path()
            val sx = cx + r * 0.75f * cos(ang); val sy = cy + r * 0.75f * sin(ang)
            p.moveTo(sx, sy)
            p.cubicTo(cx + r * 0.2f * cos(ang + 1.4f), cy + r * 0.2f * sin(ang + 1.4f),
                cx + r * 0.6f * cos(ang + 2.6f), cy + r * 0.6f * sin(ang + 2.6f),
                cx + r * 0.9f * cos(ang + 3.4f), cy + r * 0.9f * sin(ang + 3.4f))
            c.drawPath(p, swirl)
        }
        c.restore()
        // a rim of light that travels round: violet and cyan
        c.save()
        c.rotate(-t * 22f * pace, cx, cy)
        rim.shader = SweepGradient(cx, cy, intArrayOf(0xCC8B6CFF.toInt(), 0x00000000, 0xCC5FD0FF.toInt(), 0x00000000, 0xCC8B6CFF.toInt()), null)
        c.drawPath(path, rim)
        c.restore()
        // the shine at the top left
        shine.shader = LinearGradient(cx - r, cy - r, cx, cy, Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawOval(cx - r * 0.62f, cy - r * 0.7f, cx - r * 0.05f, cy - r * 0.3f, shine)

        if (!still && isShown) {
            // about 30 frames a second is plenty for a slow drift
            val now = SystemClock.uptimeMillis()
            val wait = (33 - (now - lastFrame)).coerceAtLeast(0)
            lastFrame = now + wait
            postInvalidateDelayed(wait)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}

/**
 * Listens for one thing said, using the phone's speech recognition, without Google's pop-up: Atlas shows its
 * own listening screen and gets the words as they come.
 */
class VoiceInput(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onDone: (String?) -> Unit,
    private val onError: (String) -> Unit
) {
    private var sr: SpeechRecognizer? = null
    private var finished = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Once an answer is in, the recogniser is let go (after the callback returns). */
    private fun release() { main.post { cancel() } }
    private var lastPartial = ""

    companion object {
        fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        if (!allowed(ctx)) { onError("Atlas needs the microphone. Allow it and try again."); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onError("This phone has no speech recognition installed."); return }
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { onError("Speech recognition isn't available: ${e.message}"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onLevel(0f) }
            override fun onError(error: Int) {
                if (finished) return
                finished = true
                onLevel(0f)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (lastPartial.isNotBlank()) onDone(lastPartial) else onDone(null)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Atlas needs the microphone. Allow it in Me.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onError("Speech recognition needs the internet on this phone.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onError("The microphone is busy. Try again in a moment.")
                    SpeechRecognizer.ERROR_AUDIO -> onError("The microphone isn't working right now.")
                    else -> onError("Speech recognition failed (error $error).")
                }
                release()
            }
            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartial
                onDone(said.ifBlank { null })
                release()
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it; onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try { r.startListening(i) } catch (e: Exception) { finished = true; onError("Couldn't start listening: ${e.message}") }
    }

    /** Stop listening and use what was heard so far. */
    fun finish() { try { sr?.stopListening() } catch (_: Exception) {} }

    fun cancel() {
        finished = true
        try { sr?.cancel() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }
}
