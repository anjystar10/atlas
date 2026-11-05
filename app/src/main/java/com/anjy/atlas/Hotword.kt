package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Listens for "Hey Atlas", entirely on the phone (Vosk, a small offline speech model, limited to that one phrase).
 * Nothing it hears is kept or sent anywhere; it only reports the moment the phrase is said.
 */
class Hotword(private val ctx: Context, private val onWake: () -> Unit) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var wanted = false
    private var lastWake = 0L
    var state = "off"; private set

    companion object {
        private const val TAG = "AtlasHotword"
        private const val ASSET_DIR = "vosk-model"
        private const val VERSION = "small-en-us-0.15"
        const val PHRASE = "hey atlas"
        private val GRAMMAR = "[\"$PHRASE\", \"[unk]\"]"
        @Volatile private var sharedModel: Model? = null

        /** Whether this build carries the wake word model. */
        fun included(ctx: Context): Boolean = try { ctx.assets.list(ASSET_DIR)?.isNotEmpty() == true } catch (_: Exception) { false }

        /** Copies the model out of the app once (Vosk reads it from a folder) and loads it. Slow: not on the main thread. */
        @Synchronized fun loadModel(ctx: Context): Model {
            sharedModel?.let { return it }
            val dir = File(ctx.filesDir, "vosk-$VERSION")
            val mark = File(dir, ".complete")
            if (!mark.exists()) {
                dir.deleteRecursively()
                copyAssets(ctx, ASSET_DIR, dir)
                mark.writeText(VERSION)
            }
            try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (_: Throwable) {}
            return Model(dir.absolutePath).also { sharedModel = it }
        }

        private fun copyAssets(ctx: Context, from: String, to: File) {
            val list = ctx.assets.list(from) ?: emptyArray()
            if (list.isEmpty()) {
                to.parentFile?.mkdirs()
                ctx.assets.open(from).use { i -> to.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                return
            }
            to.mkdirs()
            list.forEach { copyAssets(ctx, "$from/$it", File(to, it)) }
        }

        /** True when [json] (a Vosk result or partial) contains the phrase. */
        fun heard(json: String?): Boolean {
            if (json.isNullOrBlank()) return false
            val o = try { JSONObject(json) } catch (_: Exception) { return false }
            val t = (o.optString("text").ifEmpty { o.optString("partial") }).lowercase()
            return t.contains(PHRASE)
        }
    }

    /** Start (or resume) listening. Safe to call repeatedly. */
    fun start() {
        wanted = true
        if (service != null) { try { service?.setPause(false) } catch (_: Exception) {}; state = "listening"; return }
        if (!included(ctx)) { state = "missing"; return }
        state = "loading"
        Thread {
            try {
                val m = model ?: loadModel(ctx).also { model = it }
                main.post {
                    if (!wanted || service != null) return@post
                    try {
                        val rec = Recognizer(m, 16000f, GRAMMAR)
                        service = SpeechService(rec, 16000f).also { it.startListening(this) }
                        state = "listening"
                        Log.i(TAG, "listening")
                    } catch (e: Exception) { Log.e(TAG, "couldn't start", e); state = "error: ${e.message}" }
                }
            } catch (e: Throwable) { Log.e(TAG, "model failed", e); state = "error: ${e.message}" }
        }.start()
    }

    /** Let go of the microphone for a while (Atlas is listening properly, or something else needs it). */
    fun pause() {
        try { service?.setPause(true) } catch (_: Exception) {}
        // a paused Vosk keeps the recorder open; release it fully so the speech recogniser can have the mic
        stopService()
        if (wanted) state = "paused"
    }

    fun stop() { wanted = false; stopService(); state = "off" }

    private fun stopService() {
        val s = service ?: return
        service = null
        try { s.stop() } catch (_: Exception) {}
        try { s.shutdown() } catch (_: Exception) {}
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 2500) return
        lastWake = now
        Log.i(TAG, "wake word")
        pause()
        onWake()
    }

    override fun onPartialResult(hypothesis: String?) { if (heard(hypothesis)) main.post { fire() } }
    override fun onResult(hypothesis: String?) { if (heard(hypothesis)) main.post { fire() } }
    override fun onFinalResult(hypothesis: String?) { if (heard(hypothesis)) main.post { fire() } }
    override fun onError(exception: Exception?) {
        Log.w(TAG, "error", exception)
        state = "error: ${exception?.message}"
        main.post { stopService(); if (wanted) main.postDelayed({ if (wanted && service == null) start() }, 5000) }
    }
    override fun onTimeout() { main.post { stopService(); if (wanted) start() } }
}
