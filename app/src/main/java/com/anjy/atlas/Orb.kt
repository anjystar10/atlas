package com.anjy.atlas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
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
 * The orb painted on the CPU by [OrbPainter], used only when the graphics chip can't draw it (see [OrbView]).
 * Painted off the main thread at up to [MAX_SIDE] pixels and smoothly scaled up; 15 frames a second at rest,
 * 30 while busy.
 */
class CpuOrbView(context: Context) : View(context) {
    /** Loudness of the voice it's hearing, 0..1 (eased while drawing). */
    var level = 0f
    /** 0 at rest, 1 while thinking or listening. */
    var energy = 0f
    /** Reduce Motion: painted once, holding still. */
    var still = false
        set(v) { field = v; invalidate() }
    private var shownLevel = 0f
    private var shownEnergy = 0f
    private val painter = OrbPainter()
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private var front: android.graphics.Bitmap? = null
    private var pixels = IntArray(0)
    private var busy = false
    private var lastFrame = 0L
    private var flow = 0.0
    private var paintedStill = false
    private var placeholder: android.graphics.Bitmap? = null
    private val dst = android.graphics.RectF()
    private val tick = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        val f = front
        if (f != null) canvas.drawBitmap(f, null, dst, filter)
        else {
            // the first frame is still being painted: show the same orb as a picture meanwhile
            val pic = placeholder ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.orb_still).also { placeholder = it }
            val side = min(w, h).toFloat()
            canvas.drawBitmap(pic, null, android.graphics.RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), filter)
        }
        next()
    }

    /** Paints the next frame on the orb thread, unless one is under way or it isn't time yet. */
    private fun next() {
        if (busy || !isAttachedToWindow || !isShown) return
        if (still && paintedStill && front != null && front!!.width == renderW() && front!!.height == renderH()) return
        val now = SystemClock.uptimeMillis()
        val gap = if (still) 0L else if (energy > 0.05f || shownEnergy > 0.05f || level > 0.02f) 33L else 66L
        val wait = lastFrame + gap - now
        if (wait > 0) { removeCallbacks(tick); postDelayed(tick, wait); return }
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.2f)
        lastFrame = now
        flow += dt * OrbView.flowRate(shownEnergy, shownLevel)
        shownLevel += (level - shownLevel) * 0.35f
        shownEnergy += (energy - shownEnergy) * 0.12f
        val rw = renderW(); val rh = renderH()
        val t = if (still) 0f else OrbView.clock()
        val e = shownEnergy; val l = shownLevel; val fl = if (still) 0f else flow.toFloat()
        if (pixels.size != rw * rh) pixels = IntArray(rw * rh)
        val buf = pixels
        busy = true
        worker.post {
            try { painter.paint(buf, rw, rh, t, e, l, fl) } catch (x: Throwable) { android.util.Log.e("AtlasOrb", "paint", x) }
            post {
                busy = false
                var b = front
                if (b == null || b.width != rw || b.height != rh) {
                    b = android.graphics.Bitmap.createBitmap(rw, rh, android.graphics.Bitmap.Config.ARGB_8888); front = b
                }
                if (buf.size == rw * rh) b.setPixels(buf, 0, rw, 0, 0, rw, rh)
                if (still) paintedStill = true
                invalidate()
            }
        }
    }

    private fun scale() = min(1f, MAX_SIDE.toFloat() / min(width, height).coerceAtLeast(1))
    private fun renderW() = (width * scale()).toInt().coerceAtLeast(8)
    private fun renderH() = (height * scale()).toInt().coerceAtLeast(8)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); paintedStill = false }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    companion object {
        /** The orb is painted at most this many pixels across, then scaled up smoothly. */
        const val MAX_SIDE = 150
        private val worker: android.os.Handler by lazy {
            val th = android.os.HandlerThread("atlas-orb", android.os.Process.THREAD_PRIORITY_DEFAULT); th.start()
            android.os.Handler(th.looper)
        }
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
