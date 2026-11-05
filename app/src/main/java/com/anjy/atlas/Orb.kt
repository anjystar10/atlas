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
 * Atlas's face: a living glass blob, after the reference the user chose. A silvery shell with fine lines, lit from
 * the top left; a near-black core; wisps of blue light drifting inside; a warm edge at the lower right.
 * Drawn per pixel by a GPU shader (AGSL). It drifts slowly at rest, and stirs and brightens while Atlas
 * listens or works ([energy]); [level] swells it with your voice.
 */
class OrbView(context: Context) : View(context) {
    /** Loudness of the voice it's hearing, 0..1 (eased while drawing). */
    var level = 0f
    /** 0 at rest, 1 while thinking or listening. */
    var energy = 0f
    /** Reduce Motion: drawn once, holding still. */
    var still = false
    private var shownLevel = 0f
    private var shownEnergy = 0f
    private val start = SystemClock.uptimeMillis()
    private val shader: android.graphics.RuntimeShader? = try { android.graphics.RuntimeShader(AGSL) } catch (e: Throwable) {
        android.util.Log.e("AtlasOrb", "orb shader didn't compile", e); null
    }
    private val paint = Paint().apply { shader?.let { setShader(it) } }
    private val still2 = Paint(Paint.FILTER_BITMAP_FLAG)
    private var stillPic: android.graphics.Bitmap? = null
    private val dst = android.graphics.RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = if (still) 0f else (SystemClock.uptimeMillis() - start) / 1000f
        shownLevel += (level - shownLevel) * 0.25f
        shownEnergy += (energy - shownEnergy) * 0.08f
        val sh = shader
        if (sh != null && canvas.isHardwareAccelerated) {
            sh.setFloatUniform("size", w, h)
            sh.setFloatUniform("time", t)
            sh.setFloatUniform("energy", shownEnergy)
            sh.setFloatUniform("level", shownLevel)
            canvas.drawRect(0f, 0f, w, h, paint)
        } else {
            // a software canvas can't run shaders: the same orb, as a still picture that swells with the voice
            val pic = stillPic ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.orb_still).also { stillPic = it }
            val side = min(w, h) * (1f + 0.08f * shownLevel)
            dst.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            canvas.drawBitmap(pic, null, dst, still2)
        }
        // about 30 frames a second; nothing is drawn while it's off screen
        if (!still && isShown) postInvalidateDelayed(33)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        /** The orb, per pixel. Mirrors tools/orb_proto.py line for line (that's where it was tuned against the reference). */
        const val AGSL = """
uniform float2 size;
uniform float time;
uniform float energy;
uniform float level;

float hash2(float2 p) { return fract(sin(p.x * 127.1 + p.y * 311.7) * 43758.5453); }
float vnoise(float2 p) {
    float2 i = floor(p); float2 f = p - i;
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash2(i); float b = hash2(i + float2(1.0, 0.0));
    float c = hash2(i + float2(0.0, 1.0)); float d = hash2(i + float2(1.0, 1.0));
    float ab = a + (b - a) * u.x; float cd = c + (d - c) * u.x;
    return ab + (cd - ab) * u.y;
}
float fbm(float2 p) {
    float v = 0.0; float amp = 0.5;
    for (int k = 0; k < 3; k++) { v += amp * vnoise(p); p = p * 2.03 + float2(1.7, 9.2); amp *= 0.5; }
    return v;
}
float wrapA(float x) { return atan(sin(x), cos(x)); }
float radiusAt(float th, float t) {
    float k = 1.0 + 0.060 * sin(2.0 * th + 0.4 + t * 0.31) + 0.045 * sin(3.0 * th + 1.9 - t * 0.23)
        + 0.025 * sin(5.0 * th + 4.1 + t * 0.41) + 0.015 * energy * sin(7.0 * th + t * 1.7);
    float bite = -0.42 + 0.12 * sin(t * 0.21);
    float d0 = wrapA(th - bite); float d1 = wrapA(th - bite + 0.62); float d2 = wrapA(th - bite - 2.6);
    return k - 0.46 * exp(-(d0 * d0) / (2.0 * 0.0676)) + 0.10 * exp(-(d1 * d1) / (2.0 * 0.0784))
        + 0.07 * exp(-(d2 * d2) / (2.0 * 0.25));
}

half4 main(float2 xy) {
    float side = min(size.x, size.y);
    float s = side * 0.33 * (1.0 + 0.08 * level) * (1.0 + 0.05 * energy);
    float px = (xy.x - size.x * 0.5) / s;
    float py = (xy.y - size.y * 0.5) / s / 1.12;
    float th = atan(py, px);
    float rad = sqrt(px * px + py * py);
    float d = rad / radiusAt(th, time);
    float aa = 1.5 / s;
    float inside = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, d);
    float glow = exp(-max(d - 1.0, 0.0) * 6.0) * (1.0 - inside) * (0.10 + 0.25 * energy);
    if (inside <= 0.0 && glow < 0.004) { return half4(0.0); }

    float t = time;
    float fx = px * 1.05 + 0.35 * sin(t * 0.21);
    float fy = py * 1.05 + 0.3 * cos(t * 0.17);
    float warpx = fbm(float2(fx + 3.1, fy - 1.3 + t * 0.05));
    float warpy = fbm(float2(fx - 2.4 + t * 0.04, fy + 5.2));
    float n = fbm(float2(fx + 1.4 * warpx, fy + 1.4 * warpy));
    float rib = 1.0 - smoothstep(0.0, 0.07, abs(n - 0.52));
    float rib2 = 1.0 - smoothstep(0.0, 0.045, abs(n - 0.42));
    float halo = 1.0 - smoothstep(0.0, 0.16, abs(n - 0.50));
    float zone = smoothstep(0.15, 0.7, d) * (1.0 - smoothstep(0.97, 1.0, d));
    float grain = hash2(floor(xy));
    float speck = 0.7 + 0.6 * grain;
    float band = (rib + rib2 * 0.5) * zone * speck;
    float soft = smoothstep(0.30, 0.8, fbm(float2(fx * 0.8 + 4.0, fy * 0.8 - t * 0.03))) * zone * 0.35;

    float3 blue = float3(0.06, 0.32, 1.0);
    float3 bright = float3(0.55, 0.80, 1.0);
    float3 col = float3(0.004, 0.008, 0.03);
    col += blue * band * (0.9 + 0.5 * energy);
    col += bright * band * band * band * 0.35;
    col += blue * (soft * 0.4 + halo * zone * 0.35);

    float inv = 1.0 / max(rad, 0.000001);
    float nx = px * inv; float ny = py * inv;
    float light = clamp(0.7 - 0.75 * (nx * 0.70 + ny * 0.72), 0.0, 1.0);
    float shell = smoothstep(0.66, 0.995, d);
    float lines = 0.62 + 0.38 * sin(xy.y * 3.14159265 / 1.15);
    col += float3(0.80, 0.88, 1.0) * pow(shell, 1.5) * light * lines * 0.92;
    col += blue * shell * (1.0 - light) * 0.25;
    float wl = clamp(nx * 0.75 + ny * 0.66, 0.0, 1.0);
    col += float3(1.0, 0.50, 0.32) * wl * wl * wl * smoothstep(0.88, 1.0, d) * 0.85;

    float3 outc = clamp(col, 0.0, 1.0) * inside + float3(0.1, 0.3, 1.0) * glow;
    float a = clamp(inside + glow, 0.0, 1.0);
    return half4(half3(outc), half(a));
}
"""
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
