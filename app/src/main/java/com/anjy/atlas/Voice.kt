package com.anjy.atlas

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Atlas's voice. A natural, human-sounding voice from the user's AI service when there is one (Gemini's speech
 * model, or OpenAI's), else the best voice installed on the phone. Speaking is cut off when Atlas starts listening.
 */
object Voice {
    private const val TAG = "AtlasVoice"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    /** Why the natural voice last failed (shown in Me), or null. */
    @Volatile var lastProblem: String? = null; private set
    val geminiVoices = listOf("Charon", "Puck", "Fenrir", "Kore", "Zephyr")
    val openAiVoices = listOf("marin", "cedar")

    fun natural(ctx: Context) = Store.getFlag(ctx, "voice_natural", true)
    fun voiceName(ctx: Context, engine: String) = Store.getString(ctx, "voice_$engine") ?: if (engine == "gemini") "Charon" else "marin"
    /** Which natural voice can be used: "gemini", "openai" or null (phone voice). */
    fun engine(ctx: Context): String? = when {
        !natural(ctx) -> null
        Providers.key(ctx, "gemini") != null -> "gemini"
        Providers.key(ctx, "openai") != null -> "openai"
        else -> null
    }

    fun clean(s: String) = s.replace(Regex("[*_#`]"), "").replace(Regex("\\(What I did:.*\\)", RegexOption.DOT_MATCHES_ALL), "").trim()

    /** Says [text]; [onDone] runs on the main thread once it has finished (or failed). */
    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val say = clean(text)
        hush()
        val mine = ++gen
        if (say.isEmpty()) { onDone?.let { main.post(it) }; return }
        val eng = engine(app)
        if (eng == null) { system(app, say, mine, onDone); return }
        Thread {
            val file = try {
                if (eng == "gemini") geminiSpeech(app, say) else openAiSpeech(app, say)
            } catch (e: Exception) {
                Log.w(TAG, "natural voice failed", e)
                lastProblem = e.message ?: e.javaClass.simpleName
                null
            }
            main.post {
                if (mine != gen) return@post
                if (file == null) { system(app, say, mine, onDone); return@post }
                lastProblem = null
                play(file, mine, onDone) { system(app, say, mine, onDone) }
            }
        }.start()
    }

    fun hush() {
        gen++
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun play(file: File, mine: Int, onDone: (() -> Unit)?, fallback: () -> Unit) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; file.delete(); if (mine == gen) onDone?.invoke() }
            mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; if (mine == gen) fallback(); true }
            mp.prepare(); mp.start()
            player = mp
        } catch (e: Exception) { Log.w(TAG, "playback", e); fallback() }
    }

    // ---- Gemini's speech model (Interactions API), returns 24 kHz WAV ----
    private fun geminiSpeech(ctx: Context, text: String): File {
        val key = Providers.key(ctx, "gemini")!!
        val body = JSONObject()
            .put("model", "gemini-3.8-flash-tts")
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
                    .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", "warm, relaxed and natural, like a friend talking")))))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voiceName(ctx, "gemini")))))
        val r = Providers.post("https://generativelanguage.googleapis.com/v1beta/interactions", mapOf("x-goog-api-key" to key), body, "Gemini voice")
        val b64 = findAudio(r) ?: throw IllegalStateException("Gemini sent no audio")
        var bytes = Base64.decode(b64, Base64.DEFAULT)
        if (!(bytes.size > 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())) bytes = wav(bytes, 24000)
        return File(ctx.cacheDir, "speech-${System.nanoTime()}.wav").apply { writeBytes(bytes) }
    }

    /** The longest base64 "data" string in the response: the audio. */
    internal fun findAudio(o: Any?): String? {
        var best: String? = null
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> x.keys().forEach { k -> val v = x.opt(k); if (k == "data" && v is String && v.length > (best?.length ?: 200)) best = v else walk(v) }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return best
    }

    /** Raw 16-bit mono PCM to a WAV file. */
    internal fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    // ---- OpenAI's speech endpoint, returns MP3 ----
    private fun openAiSpeech(ctx: Context, text: String): File {
        val key = Providers.key(ctx, "openai")!!
        val body = JSONObject().put("model", "gpt-4o-mini-tts").put("input", text).put("voice", voiceName(ctx, "openai"))
            .put("instructions", "Speak warmly and naturally, like a relaxed friend. Not robotic.").put("response_format", "mp3")
        val con = java.net.URL("https://api.openai.com/v1/audio/speech").openConnection() as java.net.HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 15_000; con.readTimeout = 60_000
            con.setRequestProperty("Authorization", "Bearer $key"); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) throw IllegalStateException(Providers.explain("OpenAI voice", con.responseCode, con.errorStream?.use { String(it.readBytes()) } ?: ""))
            val f = File(ctx.cacheDir, "speech-${System.nanoTime()}.mp3")
            con.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
            return f
        } finally { con.disconnect() }
    }

    // ---- the phone's own voice: the best quality English voice installed ----
    private fun system(ctx: Context, text: String, mine: Int, onDone: (() -> Unit)?) {
        val t = tts
        if (t == null || !ttsReady) {
            pending = text to onDone
            if (t == null) tts = TextToSpeech(ctx) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) { pickVoice(); listen() }
                pending?.let { (p, d) -> pending = null; if (ttsReady) say(p, gen, d) else d?.let { main.post(it) } }
            }
            return
        }
        say(text, mine, onDone)
    }

    private val doneFor = mutableMapOf<String, () -> Unit>()

    private fun listen() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { k -> main.post { doneFor.remove(k)?.invoke() } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
        })
    }

    private fun say(text: String, mine: Int, onDone: (() -> Unit)?) {
        val id = "atlas-$mine"
        onDone?.let { doneFor[id] = it }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun pickVoice() {
        val t = tts ?: return
        try {
            val want = Locale.getDefault()
            val voices = t.voices?.filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } ?: return
            val best = voices.maxWithOrNull(compareBy({ it.quality }, { it.locale.country == want.country }, { if (it.isNetworkConnectionRequired) 1 else 0 }, { -it.latency }))
            if (best != null) t.voice = best
            t.setSpeechRate(1.0f); t.setPitch(1.0f)
        } catch (e: Exception) { Log.w(TAG, "voice choice", e) }
    }
}
