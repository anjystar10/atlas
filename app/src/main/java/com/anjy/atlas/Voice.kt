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
 * Atlas's voice. A natural, human-sounding voice when there is a key for one, in this order: Atlas's own voice
 * designed on ElevenLabs, then Gemini's speech model (Atlas's voice designed there), then OpenAI's, else the best voice
 * installed on the phone. Every one of them gets the same "AI system" finish ([AudioFinish]) unless it's switched off
 * in Me → Voice. Speaking is cut off when Atlas starts listening.
 */
object Voice {
    private const val TAG = "AtlasVoice"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Triple<String, Int, (() -> Unit)?>? = null
    /** Why the natural voice last failed (shown in Me), or null. */
    @Volatile var lastProblem: String? = null; private set
    /** Who actually spoke last, for Me → Voice ("Atlas (Gemini)", "the phone's voice"). */
    @Volatile var lastSpoke: String? = null; private set
    /** True while Atlas's voice is actually playing (the floating orb pulses with it). */
    @Volatile var speaking = false; private set

    /** A voice to choose: who it is, which engine's voice it uses, and how it delivers lines. */
    class Choice(val id: String, val label: String, val about: String, val voice: String, val style: String)

    // An original voice with a butler's manner (not a copy of any actor or character's voice).
    private const val ATLAS_STYLE = "a composed, impeccably polite British assistant: Received Pronunciation, crisp consonants, low and even pitch, unhurried and precise, quietly warm with understated dry wit; never theatrical, never sing-song"
    /** How Atlas delivers a line with Gemini (Google asks for delivery only here: accent and timbre belong to the voice itself). */
    private const val ATLAS_DELIVERY = "composed and unhurried, crisp and precise, quietly warm with understated dry wit; never theatrical, never sing-song"
    /**
     * The voice itself, designed once from a description (Gemini's voice design) and kept: Atlas's own voice, made up
     * fresh rather than copied from anyone. Kept to traits; the description names no real person or character.
     */
    internal const val ATLAS_DESIGN = "A composed, impeccably polite British assistant in his forties with a crisp Received Pronunciation accent and a low, " +
        "smooth, even baritone; unhurried and precise, quietly warm, with an understated dry wit."
    val geminiChoices = listOf(
        Choice("atlas", "Atlas", "British, calm, refined", "Charon", ATLAS_DELIVERY),
        Choice("charon", "Charon", "deep, informative", "Charon", "clear and steady, informative, relaxed"),
        Choice("algenib", "Algenib", "gravelly, low", "Algenib", "low and gravelly, unhurried, quietly confident"),
        Choice("gacrux", "Gacrux", "mature, assured", "Gacrux", "mature and assured, measured pace"),
        Choice("puck", "Puck", "upbeat, playful", "Puck", "upbeat and playful, lively"),
        Choice("fenrir", "Fenrir", "energetic", "Fenrir", "energetic and excited, fast-paced"),
        Choice("sulafat", "Sulafat", "warm (female)", "Sulafat", "warm and friendly, relaxed"),
        Choice("kore", "Kore", "firm (female)", "Kore", "firm and confident, clear"),
        Choice("zephyr", "Zephyr", "bright (female)", "Zephyr", "bright and cheerful"),
    )
    /** ElevenLabs: one voice, Atlas's own, designed from [ElevenLabs.DESIGN] (traits only; no one's voice is copied). */
    val elevenChoices = listOf(Choice("atlas", "Atlas", "designed for Atlas: British, calm, refined", "", ""))
    val openAiChoices = listOf(
        Choice("atlas", "Atlas", "British, calm, refined", "fable", "Accent: British Received Pronunciation throughout. Manner: " + ATLAS_STYLE + ". Pacing: measured, with brief natural pauses at commas and full stops."),
        Choice("onyx", "Onyx", "deep", "onyx", "Deep, calm and confident."),
        Choice("ash", "Ash", "clear, direct", "ash", "Clear and direct, friendly."),
        Choice("echo", "Echo", "smooth", "echo", "Smooth and relaxed."),
        Choice("cedar", "Cedar", "natural", "cedar", "Natural and warm, like a friend."),
        Choice("marin", "Marin", "natural (female)", "marin", "Natural and warm, like a friend."),
        Choice("nova", "Nova", "bright (female)", "nova", "Bright and upbeat."),
    )

    fun natural(ctx: Context) = Store.getFlag(ctx, "voice_natural", true)
    /** The "AI system" finish on every spoken reply (on unless switched off in Me → Voice). */
    fun finish(ctx: Context) = Store.getFlag(ctx, "voice_finish", true)
    fun setFinish(ctx: Context, on: Boolean) = Store.setFlag(ctx, "voice_finish", on)

    /** Whether spoken replies may address the user as "sir" now and then (off unless chosen in Me → Voice). */
    fun sir(ctx: Context) = Store.getFlag(ctx, "voice_sir", false)
    fun setSir(ctx: Context, on: Boolean) = Store.setFlag(ctx, "voice_sir", on)

    /** Atlas Voice from the linked laptop: Atlas's own designed voice, made on the laptop's graphics card ([LaptopVoice]). */
    val laptopChoices = listOf(Choice("atlas", "Atlas", "Atlas's own designed voice, from your laptop", "", ""))

    /** The natural voices, best first (Atlas Voice from the laptop when it's linked and running it). */
    val ENGINES = listOf(LaptopVoice.ID, ElevenLabs.ID, "gemini", "openai")
    fun engineName(eng: String?) = when (eng) { LaptopVoice.ID -> "Atlas Voice (laptop)"; ElevenLabs.ID -> "ElevenLabs"; "gemini" -> "Gemini"; "openai" -> "OpenAI"; else -> "the phone" }
    /** The natural voices that can speak now, best first (none when Natural Voice is off). */
    fun engines(ctx: Context): List<String> = if (!natural(ctx)) emptyList() else ENGINES.filter { if (it == LaptopVoice.ID) LaptopVoice.available(ctx) else Providers.key(ctx, it) != null }
    /**
     * Which natural voice speaks: Atlas Voice from the laptop whenever it can (unless another voice was picked while it
     * was there), else the one chosen in Me → Voice if it can speak, else the best that can; null: the phone's voice.
     */
    fun engine(ctx: Context): String? = LaptopVoice.choose(engines(ctx), Store.getString(ctx, "voice_engine"), Store.getFlag(ctx, "voice_laptop_off", false))
    fun setEngine(ctx: Context, eng: String) {
        Store.putString(ctx, "voice_engine", eng)
        if (LaptopVoice.available(ctx)) Store.setFlag(ctx, "voice_laptop_off", eng != LaptopVoice.ID)
    }
    private fun list(engine: String?) = when (engine) { LaptopVoice.ID -> laptopChoices; "openai" -> openAiChoices; ElevenLabs.ID -> elevenChoices; else -> geminiChoices }
    fun current(ctx: Context, engine: String? = engine(ctx)): Choice {
        val id = Store.getString(ctx, "voice_${engine ?: "gemini"}")?.lowercase()
        return list(engine).firstOrNull { it.id == id } ?: list(engine).first()
    }
    fun currentLabel(ctx: Context): String = engine(ctx)?.let { current(ctx, it).label } ?: "the phone's voice"
    fun choices(ctx: Context): List<String> = list(engine(ctx)).map { it.label }
    fun setChoice(ctx: Context, engine: String, id: String) = Store.putString(ctx, "voice_$engine", id)

    /** Picks a voice by name (for Atlas's own settings). */
    fun choose(ctx: Context, name: String): String {
        val eng = engine(ctx) ?: return "Natural voices need an ElevenLabs, Gemini or OpenAI key (Me); until then I use the phone's voice."
        val n = name.trim().lowercase()
        val c = list(eng).firstOrNull { it.id == n || it.label.lowercase() == n } ?: list(eng).firstOrNull { n.contains(it.id) }
            ?: list(eng).firstOrNull { it.about.lowercase().split(", ", " ").any { w -> w.length > 3 && n.contains(w) } }
            ?: return "Error: no voice called \"$name\". Choices: ${choices(ctx).joinToString(", ")}."
        setChoice(ctx, eng, c.id)
        return "Voice set to ${c.label} (${c.about})."
    }

    fun clean(s: String) = Plain.spoken(s)

    /** True from the moment Atlas is asked to speak until it has finished or been hushed (so also while the voice is being fetched). */
    @Volatile var busy = false; private set

    /** "slow", "normal" or "fast". */
    fun speed(ctx: Context) = Speech.speed(Store.getString(ctx, "voice_speed"))
    fun setSpeed(ctx: Context, v: String): String { val sp = Speech.speed(v); Store.putString(ctx, "voice_speed", sp); return sp }

    /**
     * Says [text]; [onDone] runs on the main thread once it has finished (or failed). A long reply is spoken in
     * pieces (a short first one, so the voice starts quickly, the next fetched while it plays); if the natural
     * voice fails part-way, the phone's own voice carries on, so a reply is never silently dropped.
     */
    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val say = clean(text)
        hush()
        val mine = ++gen
        if (say.isEmpty()) { onDone?.let { main.post(it) }; return }
        begin(app)
        val finish = { ended(app, mine); onDone?.invoke(); Unit }
        val eng = engine(app)
        if (eng == null) { main.post { if (mine == gen) system(app, say, mine, finish) }; return }
        speakParts(app, eng, Speech.chunks(say), mine, finish)
    }

    /**
     * A voice memo: [text] spoken in the chosen natural voice into one audio file in [dir] (WAV from Gemini, MP3 from
     * OpenAI), or null when no natural voice is available. Blocking (network): call off the main thread.
     */
    fun memoFile(ctx: Context, text: String, dir: File): File? {
        val app = ctx.applicationContext
        val say = clean(text)
        if (say.isEmpty()) return null
        dir.mkdirs()
        val eng = engine(app) ?: return null
        // spoken in pieces (the voice services take a few hundred words at a time), joined into one WAV with the finish
        val pcm = java.io.ByteArrayOutputStream()
        var rate = 24000
        val parts = Speech.chunks(say)
        parts.forEachIndexed { i, part ->
            val (r, data) = rawSpeech(app, eng, part, parts.getOrNull(i - 1), parts.getOrNull(i + 1))
            rate = r; pcm.write(data)
        }
        val all = pcm.toByteArray().let { if (finish(app)) AudioFinish.apply(it, rate) else it }
        return File(dir, "voice-memo-${System.currentTimeMillis()}.wav").apply { writeBytes(wav(all, rate)) }
    }

    /** Plays a line in the chosen voice right away: the "Hear It" key in Me. */
    fun preview(ctx: Context, engine: String, id: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        hush()
        val mine = ++gen
        setChoice(app, engine, id)   // tapping a voice to hear it also chooses it
        begin(app)
        speakParts(app, engine, listOf("Good day. I'm Atlas. This is how I sound. What can I do for you?"), mine) { ended(app, mine); onDone?.invoke() }
    }

    private fun begin(app: Context) { busy = true; focus(app, true) }
    private fun ended(app: Context, mine: Int) { if (mine == gen) { busy = false; speaking = false; focus(app, false) } }

    private val FAILED = Any()

    private fun speakParts(app: Context, eng: String, parts: List<String>, mine: Int, finish: () -> Unit) {
        if (parts.isEmpty()) { main.post { if (mine == gen) finish() }; return }
        val got = arrayOfNulls<Any>(parts.size)      // a File, or FAILED
        var next = 0
        var playing = false
        lateinit var pump: () -> Unit
        fun fetch(i: Int) {
            Thread {
                if (i == 0) try { app.cacheDir.listFiles { f -> f.name.startsWith("speech-") && System.currentTimeMillis() - f.lastModified() > 10 * 60_000L }?.forEach { it.delete() } } catch (_: Exception) {}
                // the chosen voice first; if it fails, the next natural voice there's a key for says this piece
                var used: String? = null
                val file = if (mine != gen) null else {
                    var f: File? = null
                    for (e in listOf(eng) + engines(app).filter { it != eng }) {
                        if (mine != gen) break
                        f = try { speechFile(app, e, parts[i], parts.getOrNull(i - 1), parts.getOrNull(i + 1)) } catch (ex: Exception) {
                            Log.w(TAG, "natural voice failed ($e)", ex)
                            lastProblem = "${engineName(e)}: ${ex.message ?: ex.javaClass.simpleName}"
                            null
                        }
                        if (f != null) { used = e; break }
                    }
                    f
                }
                main.post {
                    if (mine != gen) { file?.delete(); return@post }
                    got[i] = file ?: FAILED
                    if (file != null) {
                        if (i == 0) { if (used == eng) lastProblem = null; lastSpoke = "${current(app, used).label} (${engineName(used)})" }
                        if (i + 1 < parts.size) fetch(i + 1)   // the next piece is fetched while this one plays
                    }
                    pump()
                }
            }.start()
        }
        pump = pump@{
            if (playing || mine != gen || next >= parts.size) return@pump
            val r = got[next] ?: return@pump                    // not here yet
            // the natural voice failed: the phone's own voice says the rest
            val rest = { got.forEach { (it as? File)?.delete() }; system(app, parts.drop(next).joinToString(" "), mine, finish) }
            if (r !is File) { rest(); return@pump }
            playing = true
            play(app, r, mine, onEnd = { playing = false; got[next] = null; next++; if (next >= parts.size) finish() else pump() }, fallback = { playing = false; rest() })
        }
        fetch(0)
    }

    fun hush() {
        gen++
        busy = false
        speaking = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tts?.stop() } catch (_: Exception) {}
        main.post { doneFor.keys.removeAll { !it.startsWith("atlas-$gen-") } }
        focusCtx?.let { focus(it, false) }
    }

    // other sound (music, a video) is turned down while Atlas speaks, and comes back after
    private var focusReq: android.media.AudioFocusRequest? = null
    private var focusCtx: Context? = null
    private val speechAttrs get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    @Synchronized private fun focus(app: Context, on: Boolean) {
        try {
            val am = app.getSystemService(android.media.AudioManager::class.java) ?: return
            if (on) {
                if (focusReq != null) return
                val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(speechAttrs).build()
                am.requestAudioFocus(r); focusReq = r; focusCtx = app
            } else focusReq?.let { am.abandonAudioFocusRequest(it); focusReq = null }
        } catch (e: Exception) { Log.w(TAG, "audio focus", e) }
    }

    private fun play(app: Context, file: File, mine: Int, onEnd: () -> Unit, fallback: () -> Unit) {
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            mp.setAudioAttributes(speechAttrs)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; file.delete(); speaking = false; if (mine == gen) onEnd() }
            mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; file.delete(); speaking = false; if (mine == gen) fallback(); true }
            mp.prepare()
            val rate = Speech.rate(speed(app))
            if (rate != 1f) try { mp.playbackParams = mp.playbackParams.setSpeed(rate) } catch (e: Exception) { Log.w(TAG, "speed", e) }
            mp.start(); speaking = true
            player = mp
        } catch (e: Exception) { Log.w(TAG, "playback", e); try { mp?.release() } catch (_: Exception) {}; file.delete(); speaking = false; fallback() }
    }

    /** One piece of speech from [eng] as (sample rate, 16-bit mono PCM). Blocking (network). */
    private fun rawSpeech(ctx: Context, eng: String, text: String, before: String?, after: String?): Pair<Int, ByteArray> = when (eng) {
        LaptopVoice.ID -> LaptopVoice.speech(ctx, text, Hear.lastLanguage)
        ElevenLabs.ID -> ElevenLabs.RATE to elevenSpeech(ctx, text, before, after)
        "openai" -> 24000 to openAiSpeech(ctx, text)
        else -> geminiSpeech(ctx, text)
    }

    /** One piece of speech from [eng], finished, as a WAV file in the cache. */
    private fun speechFile(ctx: Context, eng: String, text: String, before: String?, after: String?): File {
        val (rate, pcm) = rawSpeech(ctx, eng, text, before, after)
        if (pcm.size < 200) throw IllegalStateException("${engineName(eng)} sent no audio")
        val out = if (finish(ctx)) AudioFinish.apply(pcm, rate) else pcm
        return File(ctx.cacheDir, "speech-${System.nanoTime()}.wav").apply { writeBytes(wav(out, rate)) }
    }

    /** ElevenLabs, through the key pool: a key that's turned down or out of credit rests and the next key tries once. */
    private fun elevenSpeech(ctx: Context, text: String, before: String?, after: String?): ByteArray {
        var key = Providers.key(ctx, ElevenLabs.ID) ?: throw IllegalStateException("no ElevenLabs key")
        repeat(2) { attempt ->
            try { return ElevenLabs.speech(ctx, key, text, before, after).also { Providers.worked(ctx, ElevenLabs.ID, key) } }
            catch (e: ElevenLabs.Failure) {
                if (!ElevenLabs.keyProblem(e) || attempt == 1) throw e
                Providers.rest(ctx, ElevenLabs.ID, key, e.code, e.message ?: "")
                key = Providers.readyKey(ctx, ElevenLabs.ID, except = key) ?: throw e
            }
        }
        throw IllegalStateException("ElevenLabs")
    }

    // ---- Gemini's speech model (Interactions API), returns 24 kHz PCM ----
    private fun geminiSpeech(ctx: Context, text: String): Pair<Int, ByteArray> {
        val key = Providers.key(ctx, "gemini")!!
        val c = current(ctx, "gemini")
        // "Atlas": its own designed voice; else a calm British man's voice from Google's library; else Charon
        val designed = if (c.id == "atlas") designedVoice(ctx, key) else null
        val voice = if (c.id == "atlas") designed ?: britishVoice(ctx, key) ?: c.voice else c.voice
        val style = c.style + Speech.pace(speed(ctx)).let { if (it.isEmpty()) "" else "; $it" }
        val r = try { geminiCall(key, text, voice, style) } catch (e: Exception) {
            if (voice == c.voice) throw e
            // Gemini turned that voice down: don't try it again (a designed one falls back to the library's, then Charon).
            // A dropped connection, a busy server or a used-up limit says nothing about the voice, so it is kept.
            val refused = e is Gemini.Failure && !e.offline && e.code in 400..499 && e.code != 429 && e.code != 408
            if (refused) Store.putString(ctx, if (voice == designed) "atlas_voice_designed" else "atlas_voice_id", "-")
            geminiCall(key, text, c.voice, style)
        }
        val b64 = findAudio(r) ?: throw IllegalStateException("Gemini sent no audio")
        return Speech.wavData(Base64.decode(b64, Base64.DEFAULT))
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

    /**
     * Atlas's own voice, designed once on this Gemini key from [ATLAS_DESIGN] and remembered (Google keeps a designed
     * voice for a year after it was last used). Null if it can't be made; a refusal is remembered so it isn't asked again.
     */
    private fun designedVoice(ctx: Context, key: String): String? {
        val keyTag = key.takeLast(6)
        Store.getString(ctx, "atlas_voice_designed")?.let { saved ->
            if (saved == "-") return null
            val (tag, id) = saved.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to saved }
            if (tag == keyTag && id.isNotBlank()) return id   // a designed voice belongs to the project of the key that made it
        }
        val body = JSONObject().put("store", true).put("voice", JSONObject()
            .put("model", "gemini-3.8-flash-tts").put("type", "prompted").put("display_name", "Atlas")
            .put("gender", "male").put("language_code", "en-GB")
            .put("prompted", JSONObject().put("input", ATLAS_DESIGN)))
        return try {
            val r = Providers.post("https://generativelanguage.googleapis.com/v1beta/voices", mapOf("x-goog-api-key" to key), body, "Gemini voice design")
            val id = Speech.voiceId(r)
            Store.putString(ctx, "atlas_voice_designed", if (id != null) "$keyTag|$id" else "-")
            id
        } catch (e: Gemini.Failure) {
            Log.w(TAG, "voice design", e)
            // turned down (no access, bad request): don't ask again; a dropped connection or a busy server tries again next time
            if (!e.offline && e.code in 400..499 && e.code != 429 && e.code != 408) Store.putString(ctx, "atlas_voice_designed", "-")
            null
        } catch (e: Exception) { Log.w(TAG, "voice design", e); null }
    }

    /** A calm British male voice from Google's voice library, looked up once and remembered. Null if none. */
    private fun britishVoice(ctx: Context, key: String): String? {
        Store.getString(ctx, "atlas_voice_id")?.let { return it.takeIf { it != "-" } }
        // "-" (don't look again) only once the library has actually answered; a dropped connection tries again next time
        var answered = false
        val found = try {
            val q = "language_code=en-GB&gender=male&type=prebuilt&page_size=50"
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

    // ---- OpenAI's speech endpoint, returns raw 24 kHz 16-bit PCM ("pcm" format) ----
    // (OpenAI has marked gpt-4o-mini-tts for shutdown on 6 January 2027, pointing to its Realtime API instead; until then this
    // endpoint works. If it starts failing, the phone's voice takes over by itself and Me says why.)
    private fun openAiSpeech(ctx: Context, text: String): ByteArray {
        val key = Providers.key(ctx, "openai")!!
        val c = current(ctx, "openai")
        val body = JSONObject().put("model", "gpt-4o-mini-tts").put("input", text).put("voice", c.voice)
            .put("instructions", c.style + " Natural, never robotic.").put("response_format", "pcm")
        val con = java.net.URL("https://api.openai.com/v1/audio/speech").openConnection() as java.net.HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 15_000; con.readTimeout = 60_000
            con.setRequestProperty("Authorization", "Bearer $key"); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) throw IllegalStateException(Providers.explain("OpenAI voice", con.responseCode, con.errorStream?.use { String(it.readBytes()) } ?: ""))
            return con.inputStream.use { it.readBytes() }
        } finally { con.disconnect() }
    }

    // ---- the phone's own voice: the best quality English voice installed ----
    private fun system(ctx: Context, text: String, mine: Int, onDone: (() -> Unit)?) {
        val t = tts
        if (t == null || !ttsReady) {
            pending = Triple(text, mine, onDone)
            if (t == null) tts = TextToSpeech(ctx) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) { pickVoice(ctx); listen() } else { lastProblem = "the phone has no working voice installed"; tts = null }
                main.post { pending?.let { (p, m, d) -> pending = null; if (m == gen) { if (ttsReady) say(ctx, p, m, d) else d?.invoke() } } }
            }
            return
        }
        say(ctx, text, mine, onDone)
    }

    private val doneFor = mutableMapOf<String, () -> Unit>()

    private fun listen() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // "synth-…": a piece being made into a file for the finish (it plays later, through [play])
            override fun onStart(id: String?) { if (id?.startsWith("synth-") != true) speaking = true }
            override fun onDone(id: String?) { main.post { doneFor.remove(id)?.let { if (id?.startsWith("synth-") != true) speaking = false; it() } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
            override fun onError(id: String?, errorCode: Int) { onDone(id) }
            override fun onStop(id: String?, interrupted: Boolean) { if (id?.startsWith("synth-") != true) speaking = false }
        })
    }

    private fun say(ctx: Context, text: String, mine: Int, onDone: (() -> Unit)?) =
        if (finish(ctx)) sayFinished(ctx, text, mine, onDone) else sayPlain(ctx, text, mine, onDone)

    /**
     * The phone's voice with the finish: each piece is made into a file first (a short first piece, so it starts
     * quickly; the next is made while one plays), finished, then played like the natural voices. If the phone's voice
     * can't make files, or makes a kind the finish can't read, it simply speaks the rest as it is.
     */
    private fun sayFinished(ctx: Context, text: String, mine: Int, onDone: (() -> Unit)?) {
        phoneLanguage(ctx)
        val t = tts ?: run { sayPlain(ctx, text, mine, onDone); return }
        val app = ctx.applicationContext
        val parts = Speech.chunks(text)
        val got = arrayOfNulls<Any>(parts.size)      // a File, or FAILED
        var next = 0
        var playing = false
        var fellBack = false
        lateinit var pump: () -> Unit
        val rest = {
            if (!fellBack) { fellBack = true; got.forEach { (it as? File)?.delete() }; sayPlain(app, parts.drop(next).joinToString(" "), mine, onDone) }
        }
        fun synth(i: Int) {
            if (mine != gen || i >= parts.size) return
            val raw = File(app.cacheDir, "speech-phone-${System.nanoTime()}.wav")
            val id = "synth-$mine-$i"
            doneFor[id] = {
                Thread {
                    val f = try {
                        val b = raw.readBytes()
                        val pcm = Speech.wavPcm16Mono(b)
                        when {
                            pcm == null && b.size > 200 -> File(app.cacheDir, "speech-${System.nanoTime()}.wav").apply { writeBytes(b) }   // as it is
                            pcm == null || pcm.second.size < 200 -> null
                            else -> File(app.cacheDir, "speech-${System.nanoTime()}.wav").apply { writeBytes(wav(AudioFinish.apply(pcm.second, pcm.first), pcm.first)) }
                        }
                    } catch (e: Exception) { Log.w(TAG, "phone voice finish", e); null } finally { raw.delete() }
                    main.post {
                        if (mine != gen || fellBack) { f?.delete(); return@post }
                        got[i] = f ?: FAILED
                        if (f != null) synth(i + 1)
                        pump()
                    }
                }.start()
            }
            try {
                t.setSpeechRate(1f)   // the player sets the speed, as for the natural voices
                if (t.synthesizeToFile(parts[i], android.os.Bundle(), raw, id) != TextToSpeech.SUCCESS) { doneFor.remove(id); got[i] = FAILED; pump() }
            } catch (e: Exception) { Log.w(TAG, "phone voice to file", e); doneFor.remove(id); got[i] = FAILED; pump() }
        }
        pump = pump@{
            if (playing || fellBack || mine != gen || next >= parts.size) return@pump
            val r = got[next] ?: return@pump
            if (r !is File) { rest(); return@pump }
            playing = true
            play(app, r, mine, onEnd = { playing = false; got[next] = null; next++; if (next >= parts.size) { speaking = false; if (mine == gen) onDone?.invoke() } else pump() },
                fallback = { playing = false; rest() })
        }
        if (lastSpoke == null || engine(ctx) != null) lastSpoke = "the phone's voice"
        synth(0)
    }

    /** The language the phone's voice is set to (null: its best English voice, see [pickVoice]). */
    private var phoneLang: String? = null
    /** The phone's voice speaks the language the user last spoke in, when the phone has that language; else English. */
    private fun phoneLanguage(ctx: Context) {
        val t = tts ?: return
        val want = Hear.lastLanguage?.takeIf { it != "en" }
        if (want == phoneLang) return
        try {
            if (want != null && t.isLanguageAvailable(Locale(want)) >= TextToSpeech.LANG_AVAILABLE) { t.setLanguage(Locale(want)); phoneLang = want }
            else if (phoneLang != null) { phoneLang = null; pickVoice(ctx) }
        } catch (e: Exception) { Log.w(TAG, "phone voice language", e) }
    }

    /** The phone's voice takes a limited length at a time, so a long reply is queued in pieces; [onDone] follows the last. */
    private fun sayPlain(ctx: Context, text: String, mine: Int, onDone: (() -> Unit)?) {
        phoneLanguage(ctx)
        val t = tts
        val fail = { speaking = false; onDone?.let { main.post { if (mine == gen) it() } }; Unit }
        if (t == null) { fail(); return }
        try {
            t.setSpeechRate(Speech.rate(speed(ctx)))
            val max = (TextToSpeech.getMaxSpeechInputLength() - 200).coerceIn(400, 1500)
            val parts = Speech.chunks(text, first = max, rest = max)
            parts.forEachIndexed { i, p ->
                val id = "atlas-$mine-$i"
                if (i == parts.lastIndex && onDone != null) doneFor[id] = { if (mine == gen) onDone() }
                if (t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) { doneFor.remove(id); fail(); return }
            }
            if (lastSpoke == null || engine(ctx) != null) lastSpoke = "the phone's voice"
        } catch (e: Exception) { Log.w(TAG, "phone voice", e); fail() }
    }

    private fun pickVoice(ctx: Context) {
        val t = tts ?: return
        try {
            // the phone's best English voice, preferring a British one for Atlas
            val voices = t.voices?.filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } ?: return
            // British first (as long as it isn't a rough-quality voice), then a man's voice where the phone's voice
            // engine names them (Google's British male voices), then quality; needing the network counts against
            val best = voices.maxWithOrNull(compareBy({ it.locale.country == "GB" && it.quality >= android.speech.tts.Voice.QUALITY_NORMAL },
                { Speech.britishMale(it.name) }, { it.quality }, { if (it.isNetworkConnectionRequired) 0 else 1 }, { -it.latency }))
            if (best != null) { t.voice = best; lastSpoke = "the phone's voice (${best.locale.displayCountry})" }
            t.setPitch(0.94f)   // a touch lower and calmer
        } catch (e: Exception) { Log.w(TAG, "voice choice", e) }
    }
}
