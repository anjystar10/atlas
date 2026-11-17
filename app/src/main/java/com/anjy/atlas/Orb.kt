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
    /** Slow drawing found (or a software graphics chip): repaint a few times a second instead of 15 to 30. */
    private var calm = OrbView.gpuProblem?.startsWith("software") == true
    private var lateFrames = 0
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
        val lively = energy > 0.05f || shownEnergy > 0.05f || level > 0.02f
        val gap = if (still) 0L else if (calm) (if (lively) 100L else 500L) else if (lively) 33L else 66L
        val wait = lastFrame + gap - now
        if (wait > 0) { removeCallbacks(tick); postDelayed(tick, wait); return }
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(if (calm) 0.6f else 0.2f)
        // A phone that can't keep up (every repaint of the orb makes the whole screen redraw, which is slow
        // without a real graphics chip) gets a calmer orb, so the app itself stays quick to respond.
        if (!calm && !still && lastFrame != 0L) {
            if (now - lastFrame > gap * 3 + 40) { if (++lateFrames >= 8) { calm = true; android.util.Log.w("AtlasOrb", "screen can't keep up: calmer orb") } }
            else if (lateFrames > 0) lateFrames--
        }
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

/** Something that listens for one thing said. */
interface Hearing {
    fun start()
    /** Stop listening and use what was heard so far. */
    fun finish()
    fun cancel()
}

/**
 * Listens for one thing said, using the phone's speech recognition, without Google's pop-up: Atlas shows its
 * own listening screen and gets the words as they come. It never leaves the screen stuck on "Listening": a
 * recogniser that doesn't start, hiccups, or never answers is tried again once and then reported.
 */
class VoiceInput(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onDone: (String?) -> Unit,
    private val onError: (String) -> Unit
) : Hearing {
    private var sr: SpeechRecognizer? = null
    private var finished = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPartial = ""
    private var tries = 0
    private var startedAt = 0L
    private var ready = false

    companion object {
        fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    // the recogniser didn't start, or took the sound and never answered
    private val notReady = Runnable { if (!finished && !ready) again("The microphone didn't start. Try again.") }
    private val noAnswer = Runnable { if (!finished) deliver(lastPartial.ifBlank { null }) }
    private val tooLong = Runnable { if (!finished) finish() }

    private fun clearTimers() { main.removeCallbacks(notReady); main.removeCallbacks(noAnswer); main.removeCallbacks(tooLong) }
    private fun drop() {
        try { sr?.cancel() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }
    private fun deliver(text: String?) {
        if (finished) return
        finished = true; clearTimers(); onLevel(0f)
        onDone(Speech.fixHeard(text).ifBlank { null })
        main.post { drop() }
    }
    private fun fail(msg: String) {
        if (finished) return
        finished = true; clearTimers(); onLevel(0f)
        onError(msg)
        main.post { drop() }
    }
    /** One more go with a fresh recogniser (they sometimes fail the first time after another app used the microphone). */
    private fun again(msg: String) {
        if (finished) return
        if (tries >= 2 || lastPartial.isNotBlank()) { if (lastPartial.isNotBlank()) deliver(lastPartial) else fail(msg); return }
        clearTimers(); drop()
        main.postDelayed({ if (!finished) begin() }, 350)
    }

    override fun start() {
        if (!allowed(ctx)) { onError("Atlas needs the microphone. Allow it and try again."); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onError("This phone has no speech recognition installed."); return }
        begin()
    }

    private fun begin() {
        tries++
        ready = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { fail("Speech recognition isn't available: ${e.message}"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun mine() = sr === r && !finished
            override fun onReadyForSpeech(params: Bundle?) { if (mine()) { ready = true; main.removeCallbacks(notReady) } }
            override fun onBeginningOfSpeech() { if (mine()) { ready = true; main.removeCallbacks(notReady) } }
            override fun onRmsChanged(rmsdB: Float) { if (mine()) onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (mine()) { onLevel(0f); main.removeCallbacks(noAnswer); main.postDelayed(noAnswer, 8000) } }
            override fun onError(error: Int) {
                if (!mine()) return
                val quick = android.os.SystemClock.elapsedRealtime() - startedAt < 1500
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        // some phones answer "nothing heard" the instant listening starts: that's a hiccup, not silence
                        if (quick && lastPartial.isBlank() && tries < 2) again("I didn't catch that.") else deliver(lastPartial.ifBlank { null })
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Atlas needs the microphone. Allow it in Me.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        if (lastPartial.isNotBlank()) deliver(lastPartial) else fail("Speech recognition needs the internet on this phone.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> again("The microphone is busy. Try again in a moment.")
                    SpeechRecognizer.ERROR_CLIENT, 11 /* server disconnected */ -> again("Speech recognition stopped. Try again.")
                    SpeechRecognizer.ERROR_AUDIO -> again("The microphone isn't working right now.")
                    12, 13 /* language not supported / unavailable */ -> fail("The phone's speech recognition doesn't have this language ready.")
                    else -> again("Speech recognition failed (error $error).")
                }
            }
            override fun onResults(results: Bundle?) {
                if (!mine()) return
                deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartial)
            }
            override fun onPartialResults(partial: Bundle?) {
                if (!mine()) return
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it; onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // a pause for breath shouldn't end the sentence (phones that honour these wait a little longer)
            // (as long as this person needs: their learned pace, or longer with "Give Me Time to Finish")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, maxOf(1500L, Hear.endSilenceMs(ctx).toLong()))
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, maxOf(1500L, Hear.endSilenceMs(ctx).toLong()))
        Hear.language(ctx)?.let { i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        try {
            r.startListening(i)
            main.postDelayed(notReady, 5000)
            main.removeCallbacks(tooLong); main.postDelayed(tooLong, 60_000)
        } catch (e: Exception) { fail("Couldn't start listening: ${e.message}") }
    }

    /** Stop listening and use what was heard so far. */
    override fun finish() {
        if (finished) return
        try { sr?.stopListening() } catch (_: Exception) {}
        main.removeCallbacks(noAnswer); main.postDelayed(noAnswer, 6000)
    }

    override fun cancel() {
        finished = true
        clearTimers()
        drop()
    }
}
