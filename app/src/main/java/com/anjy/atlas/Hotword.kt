package com.anjy.atlas

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Listens for "Hey Atlas", entirely on the phone (Vosk, a small offline speech model). Nothing it hears is kept or
 * sent anywhere; it only reports the moment the phrase is said.
 *
 * Two stages, so look-alikes don't wake it: a listener that only knows "hey atlas" (anything else is "unknown") has
 * to hear both words confidently; then the same stretch of audio is checked again against look-alike names
 * ("hey alice", "hey alex", "at last"…), and it wakes only if "atlas" still wins. Tested in CI on synthetic speech.
 */
class Hotword(private val ctx: Context, private val onWake: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wanted = false
    @Volatile private var thread: Thread? = null
    @Volatile private var runFlag = false
    private var lastWake = 0L
    @Volatile var state = "off"; private set

    companion object {
        private const val TAG = "AtlasHotword"
        private const val ASSET_DIR = "vosk-model"
        private const val VERSION = "small-en-us-0.15"
        private const val RATE = 16000
        const val PHRASE = "hey atlas"
        const val MIN_CONF = 0.7
        val LISTEN = listOf("hey atlas", "[unk]")
        val CHECK = listOf("hey atlas", "hey", "atlas", "hey alice", "hey alex", "hey adam", "hey ellis", "hey at last", "at last",
            "hey there", "okay", "hello", "alice", "alex", "ellis", "[unk]")
        private val NEAR = setOf("alice", "alex", "adam", "ellis")
        @Volatile private var sharedModel: Model? = null

        fun grammar(words: List<String>) = words.joinToString(",", "[", "]") { "\"$it\"" }

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

        /** Stage one: the listener's result (Vosk JSON, with words) says "hey atlas", both words at [MIN_CONF] or more. */
        fun listenerHeard(json: String?): Boolean {
            if (json.isNullOrBlank()) return false
            val o = try { JSONObject(json) } catch (_: Exception) { return false }
            if (!o.optString("text").contains(PHRASE)) return false
            val ws = o.optJSONArray("result") ?: return false
            for (i in 0 until ws.length() - 1) {
                val a = ws.optJSONObject(i) ?: continue; val b = ws.optJSONObject(i + 1) ?: continue
                if (a.optString("word") == "hey" && b.optString("word") == "atlas" &&
                    minOf(a.optDouble("conf", 0.0), b.optDouble("conf", 0.0)) >= MIN_CONF) return true
            }
            return false
        }

        /** Stage two: against the look-alikes, the same audio still reads as "atlas". [json] is the check's final result. */
        fun checkPasses(json: String?): Boolean {
            val t = try { JSONObject(json ?: return false).optString("text") } catch (_: Exception) { return false }
            val w = t.split(' ').filter { it.isNotEmpty() }
            return "at last" !in t && w.none { it in NEAR } && "atlas" in w
        }
    }

    /** Start (or resume) listening. Safe to call repeatedly. */
    fun start() {
        wanted = true
        thread?.let { t ->
            if (t.isAlive && runFlag) return
            // a paused listener is still letting go of the microphone: give it a moment (it checks every 0.1 s)
            if (t.isAlive) t.join(500)
        }
        if (!included(ctx)) { state = "missing"; return }
        state = "loading"
        runFlag = true
        thread = Thread({ loop() }, "atlas-hotword").also { it.start() }
    }

    /** Let go of the microphone for a while (Atlas is listening properly, or something else needs it). */
    fun pause() { runFlag = false; if (wanted) state = "paused" }

    fun stop() { wanted = false; runFlag = false; state = "off" }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var rec: AudioRecord? = null
        var listen: Recognizer? = null
        var check: Recognizer? = null
        try {
            val model = loadModel(ctx)
            listen = Recognizer(model, RATE.toFloat(), grammar(LISTEN)).apply { setWords(true) }
            check = Recognizer(model, RATE.toFloat(), grammar(CHECK)).apply { setWords(true) }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = RATE / 10 * 2   // 0.1 s of 16-bit audio
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("the microphone isn't available")
            rec.startRecording()
            state = "listening"
            Log.i(TAG, "listening")
            val buf = ByteArray(chunk)
            val utt = ByteArrayOutputStream()
            while (runFlag) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) throw IllegalStateException("microphone read failed ($n)"); continue }
                utt.write(buf, 0, n)
                // an utterance that never ends (constant noise): keep only its last 6 seconds for the check
                if (utt.size() > RATE * 2 * 12) { val keep = utt.toByteArray().takeLast(RATE * 2 * 6).toByteArray(); utt.reset(); utt.write(keep) }
                if (listen.acceptWaveForm(buf, n)) {
                    val res = listen.result
                    if (listenerHeard(res)) {
                        val pcm = utt.toByteArray()
                        check.reset()
                        check.acceptWaveForm(pcm, pcm.size)
                        val verdict = check.finalResult
                        Log.i(TAG, "heard ${JSONObject(res).optString("text")}; check: ${try { JSONObject(verdict).optString("text") } catch (_: Exception) { verdict }}")
                        if (checkPasses(verdict)) main.post { fire() }
                    }
                    utt.reset()
                }
            }
            if (wanted) state = "paused"
        } catch (e: Throwable) {
            Log.e(TAG, "stopped", e)
            state = "error: ${e.message ?: e.javaClass.simpleName}"
            // try again a little later (the mic may have been busy)
            main.postDelayed({ if (wanted && runFlag) { thread = null; start() } }, 8000)
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { listen?.close() } catch (_: Throwable) {}
            try { check?.close() } catch (_: Throwable) {}
        }
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 2500) return
        lastWake = now
        Log.i(TAG, "wake word")
        pause()
        onWake()
    }
}
