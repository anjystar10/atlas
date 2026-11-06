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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Listens for "Hey Atlas", entirely on the phone (Vosk, a small offline speech model). Nothing it hears is kept or
 * sent anywhere; it only reports the moment the phrase is said.
 *
 * Two stages, so look-alikes don't wake it: a listener that only knows "hey atlas" (anything else is "unknown") has
 * to hear it; then the same stretch of audio is decoded again with the whole English vocabulary, and Atlas wakes
 * only if "atlas" is still what was said (so "hey Dallas", "hey Alice", "at lunch" don't count). On synthetic
 * speech in CI this only ever fired on sentences that really contained "atlas"; it can miss, so say it clearly.
 *
 * The model (Vosk small English, 41 MB) is downloaded once, the first time "Hey Atlas" is switched on, and checked
 * against its known SHA-256 before use.
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
        const val MODEL_MB = HotwordFiles.MODEL_MB
        private const val RATE = 16000
        const val PHRASE = "hey atlas"
        val LISTEN = listOf("hey atlas", "[unk]")
        @Volatile private var sharedModel: Model? = null

        fun grammar(words: List<String>) = words.joinToString(",", "[", "]") { "\"$it\"" }


        private fun dir(ctx: Context) = HotwordFiles.dir(ctx)
        fun ready(ctx: Context): Boolean = HotwordFiles.ready(ctx)

        /** Loads the downloaded model. Slow: not on the main thread. */
        @Synchronized fun loadModel(ctx: Context): Model {
            sharedModel?.let { return it }
            if (!ready(ctx)) throw IllegalStateException("the Hey Atlas model isn't downloaded")
            try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (_: Throwable) {}
            return Model(dir(ctx).absolutePath).also { sharedModel = it }
        }

        /** Stage one: the listener's end-of-utterance result (Vosk JSON) says "hey atlas". Partial results never count. */
        fun listenerHeard(json: String?): Boolean {
            if (json.isNullOrBlank()) return false
            val o = try { JSONObject(json) } catch (_: Exception) { return false }
            return o.has("text") && o.optString("text").contains(PHRASE)
        }

        /** Stage two: decoded with the whole vocabulary, the same audio still contains the word "atlas". */
        fun checkPasses(json: String?): Boolean {
            val t = try { JSONObject(json ?: return false).optString("text") } catch (_: Exception) { return false }
            return "atlas" in t.split(' ')
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
        if (!ready(ctx)) { state = "not downloaded"; return }
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
            listen = Recognizer(model, RATE.toFloat(), grammar(LISTEN))
            check = Recognizer(model, RATE.toFloat())   // no grammar: the whole vocabulary
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
