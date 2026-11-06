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
    /** Who actually spoke last, for Me → Voice ("Atlas (Gemini)", "the phone's voice"). */
    @Volatile var lastSpoke: String? = null; private set
    /** True while Atlas's voice is actually playing (the floating orb pulses with it). */
    @Volatile var speaking = false; private set

    /** A voice to choose: who it is, which engine's voice it uses, and how it delivers lines. */
    class Choice(val id: String, val label: String, val about: String, val voice: String, val style: String)

    private const val ATLAS_STYLE = "calm, composed and refined; crisp and articulate with a British accent; quietly warm with a touch of dry wit; unhurried, like a trusted AI butler"
    val geminiChoices = listOf(
        Choice("atlas", "Atlas", "British, calm, refined", "Charon", ATLAS_STYLE),
        Choice("charon", "Charon", "deep, informative", "Charon", "clear and steady, informative, relaxed"),
        Choice("algenib", "Algenib", "gravelly, low", "Algenib", "low and gravelly, unhurried, quietly confident"),
        Choice("gacrux", "Gacrux", "mature, assured", "Gacrux", "mature and assured, measured pace"),
        Choice("puck", "Puck", "upbeat, playful", "Puck", "upbeat and playful, lively"),
        Choice("fenrir", "Fenrir", "energetic", "Fenrir", "energetic and excited, fast-paced"),
        Choice("sulafat", "Sulafat", "warm (female)", "Sulafat", "warm and friendly, relaxed"),
        Choice("kore", "Kore", "firm (female)", "Kore", "firm and confident, clear"),
        Choice("zephyr", "Zephyr", "bright (female)", "Zephyr", "bright and cheerful"),
    )
    val openAiChoices = listOf(
        Choice("atlas", "Atlas", "British, calm, refined", "fable", "Speak with a refined British accent. " + ATLAS_STYLE + "."),
        Choice("onyx", "Onyx", "deep", "onyx", "Deep, calm and confident."),
        Choice("ash", "Ash", "clear, direct", "ash", "Clear and direct, friendly."),
        Choice("echo", "Echo", "smooth", "echo", "Smooth and relaxed."),
        Choice("cedar", "Cedar", "natural", "cedar", "Natural and warm, like a friend."),
        Choice("marin", "Marin", "natural (female)", "marin", "Natural and warm, like a friend."),
        Choice("nova", "Nova", "bright (female)", "nova", "Bright and upbeat."),
    )

    fun natural(ctx: Context) = Store.getFlag(ctx, "voice_natural", true)
    /** Which natural voice can be used: "gemini", "openai" or null (phone voice). */
    fun engine(ctx: Context): String? = when {
        !natural(ctx) -> null
        Providers.key(ctx, "gemini") != null -> "gemini"
        Providers.key(ctx, "openai") != null -> "openai"
        else -> null
    }
    private fun list(engine: String?) = if (engine == "openai") openAiChoices else geminiChoices
    fun current(ctx: Context, engine: String? = engine(ctx)): Choice {
        val id = Store.getString(ctx, "voice_${engine ?: "gemini"}")?.lowercase()
        return list(engine).firstOrNull { it.id == id } ?: list(engine).first()
    }
    fun currentLabel(ctx: Context): String = engine(ctx)?.let { current(ctx, it).label } ?: "the phone's voice"
    fun choices(ctx: Context): List<String> = list(engine(ctx)).map { it.label }
    fun setChoice(ctx: Context, engine: String, id: String) = Store.putString(ctx, "voice_$engine", id)

    /** Picks a voice by name (for Atlas's own settings). */
    fun choose(ctx: Context, name: String): String {
        val eng = engine(ctx) ?: return "Natural voices need a Gemini or OpenAI key (Me → Brain); until then I use the phone's voice."
        val n = name.trim().lowercase()
        val c = list(eng).firstOrNull { it.id == n || it.label.lowercase() == n } ?: list(eng).firstOrNull { n.contains(it.id) }
            ?: list(eng).firstOrNull { it.about.lowercase().split(", ", " ").any { w -> w.length > 3 && n.contains(w) } }
            ?: return "Error: no voice called \"$name\". Choices: ${choices(ctx).joinToString(", ")}."
        setChoice(ctx, eng, c.id)
        return "Voice set to ${c.label} (${c.about})."
    }

    fun clean(s: String) = Plain.spoken(s)

    /** Says [text]; [onDone] runs on the main thread once it has finished (or failed). */
    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val say = clean(text)
        hush()
        val mine = ++gen
        if (say.isEmpty()) { onDone?.let { main.post(it) }; return }
        val eng = engine(app)
        if (eng == null) { system(app, say, mine, onDone); return }
        speakWith(app, eng, say, mine, onDone)
    }

    /** Plays [text] in the chosen voice right away: the "Hear It" key in Me. */
    fun preview(ctx: Context, engine: String, id: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        hush()
        val mine = ++gen
        setChoice(app, engine, id)   // tapping a voice to hear it also chooses it
        speakWith(app, engine, "Hello, I'm Atlas. Here's how I sound. What can I help you with today?", mine, onDone)
    }

    private fun speakWith(app: Context, eng: String, say: String, mine: Int, onDone: (() -> Unit)?) {
        Thread {
            // speech cut off before it finished leaves its file behind: clear out old ones
            try { app.cacheDir.listFiles { f -> f.name.startsWith("speech-") && System.currentTimeMillis() - f.lastModified() > 10 * 60_000L }?.forEach { it.delete() } } catch (_: Exception) {}
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
                lastSpoke = "${current(app, eng).label} (${if (eng == "gemini") "Gemini" else "OpenAI"})"
                play(file, mine, onDone) { system(app, say, mine, onDone) }
            }
        }.start()
    }

    fun hush() {
        gen++
        speaking = false
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
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; file.delete(); speaking = false; if (mine == gen) onDone?.invoke() }
            mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; speaking = false; if (mine == gen) fallback(); true }
            mp.prepare(); mp.start(); speaking = true
            player = mp
        } catch (e: Exception) { Log.w(TAG, "playback", e); fallback() }
    }

    // ---- Gemini's speech model (Interactions API), returns 24 kHz WAV ----
    private fun geminiSpeech(ctx: Context, text: String): File {
        val key = Providers.key(ctx, "gemini")!!
        val c = current(ctx, "gemini")
        // "Atlas" uses a real British voice from Google's voice library when one can be found; otherwise Charon
        val voice = if (c.id == "atlas") britishVoice(ctx, key) ?: c.voice else c.voice
        val r = try { geminiCall(key, text, voice, c.style) } catch (e: Exception) {
            if (voice == c.voice) throw e
            Store.putString(ctx, "atlas_voice_id", "-")   // that library voice didn't work: don't try it again
            geminiCall(key, text, c.voice, c.style)
        }
        val b64 = findAudio(r) ?: throw IllegalStateException("Gemini sent no audio")
        return wavFile(ctx, b64)
    }

    private fun geminiCall(key: String, text: String, voice: String, style: String): JSONObject {
        val body = JSONObject()
            .put("model", "gemini-3.8-flash-tts")
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
                    .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        return Providers.post("https://generativelanguage.googleapis.com/v1beta/interactions", mapOf("x-goog-api-key" to key), body, "Gemini voice")
    }

    /** A calm British male voice from Google's voice library, looked up once and remembered. Null if none. */
    private fun britishVoice(ctx: Context, key: String): String? {
        Store.getString(ctx, "atlas_voice_id")?.let { return it.takeIf { it != "-" } }
        // "-" (don't look again) only once the library has actually answered; a dropped connection tries again next time
        var answered = false
        val found = try {
            val q = "language_code=en-GB&gender=male&page_size=50"
            val con = java.net.URL("https://generativelanguage.googleapis.com/v1beta/voices?$q").openConnection() as java.net.HttpURLConnection
            try {
                con.connectTimeout = 10_000; con.readTimeout = 15_000; con.setRequestProperty("x-goog-api-key", key)
                val code = con.responseCode
                answered = code in 200..499 && code != 429 && code != 408
                if (code !in 200..299) null else {
                    val voices = JSONObject(con.inputStream.bufferedReader().readText()).optJSONArray("voices") ?: JSONArray()
                    val all = (0 until voices.length()).mapNotNull { voices.optJSONObject(it) }
                    fun score(v: JSONObject): Int {
                        val d = (v.optString("description") + " " + v.optString("display_name") + " " + v.optString("persona")).lowercase()
                        var sc = 0
                        listOf("calm", "refined", "sophisticat", "composed", "butler", "measured", "smooth", "warm", "deep", "narrat").forEach { if (d.contains(it)) sc += 2 }
                        if (v.optString("pitch").equals("low", true) || v.optString("pitch").equals("medium", true)) sc += 1
                        listOf("child", "young", "excit", "energetic", "cheer").forEach { if (d.contains(it)) sc -= 3 }
                        return sc
                    }
                    all.filter { it.optString("id").isNotBlank() }.maxByOrNull { score(it) }?.optString("id")
                }
            } finally { con.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "voice library", e); null }
        if (found != null || answered) Store.putString(ctx, "atlas_voice_id", found ?: "-")
        return found
    }

    private fun wavFile(ctx: Context, b64: String): File {
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
        val c = current(ctx, "openai")
        val body = JSONObject().put("model", "gpt-4o-mini-tts").put("input", text).put("voice", c.voice)
            .put("instructions", c.style + " Natural, never robotic.").put("response_format", "mp3")
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
                if (ttsReady) { pickVoice(ctx); listen() }
                pending?.let { (p, d) -> pending = null; if (ttsReady) say(p, gen, d) else d?.let { main.post(it) } }
            }
            return
        }
        say(text, mine, onDone)
    }

    private val doneFor = mutableMapOf<String, () -> Unit>()

    private fun listen() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false; id?.let { k -> main.post { doneFor.remove(k)?.invoke() } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
        })
    }

    private fun say(text: String, mine: Int, onDone: (() -> Unit)?) {
        val id = "atlas-$mine"
        onDone?.let { doneFor[id] = it }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun pickVoice(ctx: Context) {
        val t = tts ?: return
        try {
            // the phone's best English voice, preferring a British one for Atlas
            val voices = t.voices?.filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } ?: return
            val best = voices.maxWithOrNull(compareBy({ it.quality }, { it.locale.country == "GB" }, { if (it.isNetworkConnectionRequired) 1 else 0 }, { -it.latency }))
            if (best != null) { t.voice = best; lastSpoke = "the phone's voice (${best.locale.displayCountry})" }
            t.setSpeechRate(1.0f); t.setPitch(1.0f)
        } catch (e: Exception) { Log.w(TAG, "voice choice", e) }
    }
}
