package com.anjy.atlas

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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

        /** Stage one, early: the listener's running guess already says "hey atlas" (so it wakes as the phrase ends, not after a pause). */
        fun partialHeard(json: String?): Boolean {
            if (json.isNullOrBlank()) return false
            return try { JSONObject(json).optString("partial").contains(PHRASE) } catch (_: Exception) { false }
        }

        /**
         * Stage two: decoded with the whole vocabulary, the same audio still contains the word "atlas".
         * [sensitive]: also accept how the small model often splits the name ("at las", "atlases"), for quiet rooms
         * or accents it misses; more look-alikes get through, so it's a setting.
         */
        fun checkPasses(json: String?, sensitive: Boolean = false): Boolean {
            val t = try { JSONObject(json ?: return false).optString("text") } catch (_: Exception) { return false }
            val w = t.split(' ')
            if ("atlas" in w) return true
            if (!sensitive) return false
            return w.any { it == "atlases" || it == "atlas's" } || listOf("at las", "at less", "at lass").any { it in t }
        }

        /** The longest stretch after the wake word that's kept for the request (the rest is heard by Atlas itself). */
        private const val TAIL_MAX_MS = 6000
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
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            val model = loadModel(ctx)
            val sensitive = HotwordFiles.sensitive(ctx)
            listen = Recognizer(model, RATE.toFloat(), grammar(LISTEN))
            check = Recognizer(model, RATE.toFloat())   // no grammar: the whole vocabulary
            try { check.setWords(true) } catch (_: Throwable) {}   // word times: where "atlas" ends, so the request after it is kept
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = RATE / 10 * 2   // 0.1 s of 16-bit audio
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("the microphone isn't available")
            // the phone's own clean-up for speech: steadier level, less background hiss (where the phone has them)
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
            try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
            rec.startRecording()
            state = "listening"
            Log.i(TAG, "listening")
            val buf = ByteArray(chunk)
            val utt = ByteArrayOutputStream()
            var tail: ByteArrayOutputStream? = null      // after a wake: what's said next, kept for the request
            var tailUntil = 0L
            var checkedThisUtterance = false
            while (runFlag) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) throw IllegalStateException("microphone read failed ($n)"); continue }
                if (tail != null) {
                    // woken: keep recording until Atlas takes over the microphone (or a few seconds pass)
                    tail.write(buf, 0, n)
                    if (System.currentTimeMillis() > tailUntil) { runFlag = false }
                    continue
                }
                utt.write(buf, 0, n)
                // an utterance that never ends (constant noise): keep only its last 6 seconds for the check
                if (utt.size() > RATE * 2 * 12) { val keep = utt.toByteArray().takeLast(RATE * 2 * 6).toByteArray(); utt.reset(); utt.write(keep) }
                val ended = listen.acceptWaveForm(buf, n)
                // stage one: the end-of-phrase result, or (earlier, once per utterance) the running guess
                val res = if (ended) listen.result else listen.partialResult
                val stageOne = if (ended) listenerHeard(res) else (!checkedThisUtterance && partialHeard(res))
                if (stageOne) {
                    checkedThisUtterance = true
                    val pcm = utt.toByteArray()
                    check.reset()
                    check.acceptWaveForm(pcm, pcm.size)
                    val verdict = check.finalResult
                    Log.i(TAG, "heard ${if (ended) "" else "(early) "}$res; check: ${try { JSONObject(verdict).optString("text") } catch (_: Exception) { verdict }}")
                    if (checkPasses(verdict, sensitive) && System.currentTimeMillis() - lastWake > 2500) {
                        // the request starts where "atlas" ends: keep from there on
                        val from = Speech.atlasEnd(verdict)?.let { ((it * RATE).toInt() * 2).coerceIn(0, pcm.size) } ?: pcm.size
                        tail = ByteArrayOutputStream().also { it.write(pcm, from - from % 2, pcm.size - (from - from % 2)) }
                        tailUntil = System.currentTimeMillis() + TAIL_MAX_MS
                        lastWake = System.currentTimeMillis()
                        main.post { fire() }
                        continue
                    }
                }
                if (ended) { utt.reset(); checkedThisUtterance = false }
            }
            // hand what was said after the wake word to Atlas (it reads it once it has the microphone)
            tail?.let { t ->
                try {
                    val f = File(ctx.filesDir, "wake-tail.pcm")
                    val tmp = File(ctx.filesDir, "wake-tail.pcm.tmp")
                    tmp.writeBytes(t.toByteArray()); tmp.renameTo(f)
                    Log.i(TAG, "kept ${t.size() / 2 * 1000 / RATE} ms after the wake word")
                } catch (e: Exception) { Log.w(TAG, "couldn't keep the words after the wake word", e) }
            }
            if (wanted) state = "paused"
        } catch (e: Throwable) {
            Log.e(TAG, "stopped", e)
            state = "error: ${e.message ?: e.javaClass.simpleName}"
            // try again a little later (the mic may have been busy)
            main.postDelayed({ if (wanted && runFlag) { thread = null; start() } }, 8000)
        } finally {
            try { ns?.release() } catch (_: Exception) {}
            try { agc?.release() } catch (_: Exception) {}
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { listen?.close() } catch (_: Throwable) {}
            try { check?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * The wake word was heard. The listener keeps the microphone a moment longer, recording what's said next, until
     * Atlas takes over (its "hold" pauses this listener, which then hands the recording over), so the start of the
     * request isn't lost while Atlas opens its own microphone.
     */
    private fun fire() {
        Log.i(TAG, "wake word")
        state = "woken"
        onWake()
    }
}
