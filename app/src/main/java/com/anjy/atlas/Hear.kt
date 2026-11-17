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
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * How Atlas hears. With a Groq, OpenAI or Gemini key, Atlas records what you say itself and has a speech model
 * write it down (much more accurate with names, accents and noise than the phone's own recognition, and it's told
 * the names it should expect: yours, Atlas's, the people and things in your Log). Without one, or offline, or if
 * that fails, the phone's own speech recognition is used ([VoiceInput]), which also shows the words as you speak.
 */
object Hear {
    private const val TAG = "AtlasHear"
    const val RATE = 16_000

    /** A language to listen for ("en-GB", "fr"…), or null to let the recogniser decide. */
    fun language(ctx: Context): String? = Store.getString(ctx, "hear_lang")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", true) }
    fun setLanguage(ctx: Context, v: String) = Store.putString(ctx, "hear_lang", v.trim())

    /** The languages offered in Me (any other code can be set by asking Atlas). */
    val LANGUAGES = listOf("auto" to "Any", "en" to "English", "en-GB" to "English (UK)", "en-NG" to "English (Nigeria)", "fr" to "French", "es" to "Spanish",
        "pt" to "Portuguese", "ar" to "Arabic", "ha" to "Hausa", "yo" to "Yoruba", "ig" to "Igbo")

    /** Keep Listening: after a spoken answer, Atlas listens again without a tap. On unless switched off. */
    fun handsFree(ctx: Context) = !Store.getFlag(ctx, "no_hands_free")
    fun setHandsFree(ctx: Context, on: Boolean) = Store.setFlag(ctx, "no_hands_free", !on)

    /** How this person paces their speech, learned as they talk (see [Speech.Pace]). */
    fun pace(ctx: Context) = Speech.Pace.parse(Store.getString(ctx, "hear_pace"))
    private fun learnPace(ctx: Context, pauses: List<Int>) { if (pauses.isNotEmpty()) Store.putString(ctx, "hear_pace", pace(ctx).learn(pauses).save()) }
    /** "Give Me Time to Finish": a much longer pause is allowed before Atlas takes it that you've finished. */
    fun patient(ctx: Context) = Store.getFlag(ctx, "hear_patient")
    fun setPatient(ctx: Context, on: Boolean) = Store.setFlag(ctx, "hear_patient", on)
    /** How loud this person's voice usually is (mean rms of voiced frames), learned as they talk; null until it's known. */
    fun voiceLevel(ctx: Context): Float? = Store.getString(ctx, "hear_level")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 && it[1] >= 3 }?.get(0)
    private fun learnLevel(ctx: Context, level: Float) {
        if (level <= 0f) return
        val (l, n) = Store.getString(ctx, "hear_level")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (0f to 0f)
        val w = 1f / (minOf(n, 20f) + 1f)
        Store.putString(ctx, "hear_level", "${l + (level - l) * w},${n + 1}")
    }
    /** The silence that ends what you say, for this person now (ms). */
    fun endSilenceMs(ctx: Context) = pace(ctx).endSilenceMs(patient(ctx))

    /** Words taught in "Teach Atlas Your Voice" (and added there by hand): always given to the speech model to expect. */
    fun taughtWords(ctx: Context): List<String> = (Store.getString(ctx, "hear_words") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    fun setTaughtWords(ctx: Context, words: List<String>) = Store.putString(ctx, "hear_words", words.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(60).joinToString("\n"))

    /**
     * What the last thing heard was like: its language (ISO 639-1, from the speech model) and whether the model was
     * unsure of it. Atlas's instructions take these in, so it answers in that language and checks rather than guesses.
     */
    @Volatile var lastLanguage: String? = null; private set
    @Volatile var lastUnsure = false; private set
    @Volatile private var lastAt = 0L
    /** [lastUnsure] for a request made just now (within a minute), else false. */
    fun unsureNow() = lastUnsure && System.currentTimeMillis() - lastAt < 60_000

    /** "auto": a speech model when there's a key for one; "phone": always the phone's own recognition. */
    fun mode(ctx: Context) = if (Store.getString(ctx, "hearing") == "phone") "phone" else "auto"
    fun setMode(ctx: Context, v: String) = Store.putString(ctx, "hearing", if (v.contains("phone", true)) "phone" else "auto")

    /** After a speech model failed, the phone's recognition is used for a while. */
    @Volatile private var cloudDownUntil = 0L

    private val ORDER = listOf("groq", "openai", "gemini")   // fastest first
    private fun engines(ctx: Context): List<Pair<String, String>> = ORDER.mapNotNull { id -> Providers.readyKey(ctx, id)?.let { id to it } }

    private fun connected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }

    /** What will do the hearing right now, for Me → Voice. */
    fun describe(ctx: Context): String {
        if (mode(ctx) == "phone") return "the phone's own speech recognition"
        val e = engines(ctx).firstOrNull() ?: return "the phone's own speech recognition (add a Groq, OpenAI or Gemini key for sharper hearing)"
        return "a speech model (${Providers.byId(e.first)?.name ?: e.first}), with the phone's own as backup"
    }

    fun usesModel(ctx: Context) = mode(ctx) == "auto" && System.currentTimeMillis() >= cloudDownUntil && VoiceInput.allowed(ctx) && connected(ctx) && engines(ctx).isNotEmpty()

    /** Something to listen with, chosen as above. Same callbacks either way; [onPartial] gets "…" while a speech model writes it down. */
    fun open(ctx: Context, afterWake: Boolean = false, onPartial: (String) -> Unit, onLevel: (Float) -> Unit, onDone: (String?) -> Unit, onError: (String) -> Unit): Hearing =
        if (usesModel(ctx)) Ears(ctx.applicationContext, afterWake, onPartial, onLevel, onDone, onError) else VoiceInput(ctx, onPartial, onLevel, onDone, onError)

    /** Where the wake listener (its own process) leaves what was said right after "Hey Atlas". */
    fun tailFile(ctx: Context) = java.io.File(ctx.filesDir, "wake-tail.pcm")

    /**
     * The speech service's connection, opened ahead of time (on "Hey Atlas", while you're still talking), so the
     * words are written down sooner once you stop. Cheap: no audio, no charge.
     */
    fun prewarm(ctx: Context) {
        if (!usesModel(ctx)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val (id, key) = engines(app).firstOrNull() ?: return@Thread
                val url = when (id) { "groq" -> "https://api.groq.com/openai/v1/models"; "openai" -> "https://api.openai.com/v1/models"; else -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1" }
                val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                con.requestMethod = "HEAD"; con.connectTimeout = 5_000; con.readTimeout = 5_000
                if (id == "gemini") con.setRequestProperty("x-goog-api-key", key) else con.setRequestProperty("Authorization", "Bearer $key")
                con.responseCode   // the connection is kept for the real request
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * Names and words Atlas should expect to hear, so they're spelled right: "Atlas", your name, the words you taught
     * it, your favourite contacts' names, names from your Log. Whisper reads at most 224 tokens of it, so it's kept short.
     */
    fun hint(ctx: Context): String = try {
        val own = mutableListOf("Atlas")
        val log = mutableListOf<String>()
        Store.read(ctx) { st ->
            st.settings.name.trim().takeIf { it.isNotEmpty() }?.let { own.add(it) }
            (st.memories.takeLast(40).map { it.text } + st.tasks.takeLast(40).map { it.title }).forEach { log.addAll(names(it)) }
        }
        Speech.hintOf(listOf(own, taughtWords(ctx), contactNames(ctx), log), chars = 500)
    } catch (e: Exception) { "Atlas" }

    @Volatile private var contactsCache: Pair<Long, List<String>>? = null
    /** First names of the people starred in the phone's contacts (only with the contacts permission); kept for ten minutes. */
    private fun contactNames(ctx: Context): List<String> {
        contactsCache?.let { (at, l) -> if (System.currentTimeMillis() - at < 600_000L) return l }
        val out = LinkedHashSet<String>()
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ctx.contentResolver.query(android.provider.ContactsContract.Contacts.CONTENT_URI, arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    "${android.provider.ContactsContract.Contacts.STARRED} = 1", null, "${android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC")?.use { c ->
                    while (c.moveToNext() && out.size < 15) c.getString(0)?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.length >= 2 && it.first().isLetter() }?.let { out.add(it) }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "contacts for hearing", e) }
        return out.toList().also { contactsCache = System.currentTimeMillis() to it }
    }

    /** Capitalised words that aren't just the start of a sentence: likely names. */
    internal fun names(text: String): List<String> {
        val out = mutableListOf<String>()
        val words = text.split(Regex("""\s+"""))
        for ((i, raw) in words.withIndex()) {
            val w = raw.trim { !it.isLetterOrDigit() }
            if (w.length < 3 || !w[0].isUpperCase() || w.drop(1).none { it.isLowerCase() }) continue
            val startsSentence = i == 0 || words[i - 1].lastOrNull()?.let { it == '.' || it == '!' || it == '?' || it == ':' } == true
            if (!startsSentence) out.add(w)
        }
        return out
    }

    /**
     * [wav] written down by the first speech model that manages it. When that model says it's unsure (an accent, a
     * stammer, a noisy room), the next one there's a key for listens too and the sure one is kept; if both are unsure
     * the answer is marked so Atlas checks with you. Throws when none can.
     */
    fun transcribe(ctx: Context, wav: ByteArray, hint: String): Speech.Transcript {
        var last: Exception? = null
        var unsure: Speech.Transcript? = null
        for ((id, key) in engines(ctx)) {
            try {
                val t = when (id) {
                    // the full large-v3 (Groq: 10.3% word error rate against 12% for turbo), with its scores and the language it heard
                    "groq" -> Speech.heardFrom(whisper("https://api.groq.com/openai/v1/audio/transcriptions", key, "whisper-large-v3", wav, hint, language(ctx), "Groq", verbose = true))
                    "openai" -> Speech.heardFrom(openAi(key, wav, hint, language(ctx)))
                    else -> Speech.Transcript(gemini(ctx, key, wav, hint))
                }
                Providers.worked(ctx, id, key)
                if (!t.unsure || t.text.isBlank()) return if (t.text.isBlank() && unsure != null) unsure else t
                if (unsure == null) { unsure = t; continue }   // a second opinion
                return unsure
            } catch (e: Gemini.Failure) {
                Log.w(TAG, "$id couldn't transcribe: ${e.message}")
                if (Providers.keyExhausted(e)) Providers.rest(ctx, id, key, e.code, e.message ?: "")
                last = e
            } catch (e: Exception) { Log.w(TAG, "$id couldn't transcribe", e); last = e }
        }
        unsure?.let { return it }
        throw last ?: IllegalStateException("no speech model has a key")
    }

    /**
     * OpenAI's newest speech model (gpt-transcribe: takes the names as keywords and the language as a list), then the
     * older ones its account may still have. A model OpenAI turns down is skipped from then on, until Atlas restarts.
     */
    private val OPENAI_MODELS = listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1")
    @Volatile private var openAiSkip = 0
    private fun openAi(key: String, wav: ByteArray, hint: String, lang: String?): String {
        var i = openAiSkip.coerceAtMost(OPENAI_MODELS.lastIndex)
        while (true) {
            val model = OPENAI_MODELS[i]
            try {
                return whisper("https://api.openai.com/v1/audio/transcriptions", key, model, wav, hint, lang, "OpenAI", newer = model == "gpt-transcribe")
            } catch (e: Gemini.Failure) {
                if ((e.code == 404 || e.code == 400) && i < OPENAI_MODELS.lastIndex) { i++; openAiSkip = i; continue }
                throw e
            }
        }
    }

    /** The two-letter language code to tell a speech model, or null. */
    internal fun iso(lang: String?): String? = lang?.take(2)?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }

    /**
     * The OpenAI-style transcription endpoint (OpenAI, Groq): a multipart form with the sound file. [newer]: OpenAI's
     * gpt-transcribe, which takes the expected names as `keywords` and the language as `languages` (a list).
     */
    private fun whisper(url: String, key: String, model: String, wav: ByteArray, hint: String, lang: String?, service: String, newer: Boolean = false, verbose: Boolean = false): String {
        val boundary = "atlas" + System.nanoTime()
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) = out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model", model); field("response_format", if (verbose) "verbose_json" else "json")
        if (!newer) field("temperature", "0")
        if (hint.isNotBlank()) field("prompt", hint)
        if (newer) {
            hint.split(", ").map { it.trim() }.filter { it.isNotEmpty() }.take(30).forEach { field("keywords[]", it) }
            iso(lang)?.let { field("languages[]", it) }
        } else iso(lang)?.let { field("language", it) }
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav); out.write("\r\n--$boundary--\r\n".toByteArray())
        val con = try { java.net.URL(url).openConnection() as java.net.HttpURLConnection } catch (e: Exception) { throw Gemini.Failure("Couldn't reach $service.", offline = true) }
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = 30_000
            con.setRequestProperty("Authorization", "Bearer $key"); con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            con.setFixedLengthStreamingMode(out.size())
            val code = try { con.outputStream.use { out.writeTo(it) }; con.responseCode } catch (e: java.io.IOException) { throw Gemini.Failure("Couldn't reach $service.", offline = true) }
            if (code !in 200..299) throw Gemini.Failure(Providers.explain("$service hearing", code, con.errorStream?.use { String(it.readBytes()) } ?: ""), code = code)
            return con.inputStream.use { String(it.readBytes()) }   // the whole answer: [Speech.heardFrom] reads it
        } finally { con.disconnect() }
    }

    /** Gemini listens to the sound itself and writes down what was said. */
    private fun gemini(ctx: Context, key: String, wav: ByteArray, hint: String): String {
        val model = Providers.byId("gemini")?.let { Providers.model(ctx, it) } ?: Gemini.DEFAULT_MODEL
        val ask = "Write down exactly what is said in this recording, in the language spoken. Output only the words: no quotes, no notes, no timestamps. " +
            "If nobody speaks, output nothing at all." + (if (hint.isNotBlank()) " Names that may come up: $hint." else "")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", ask))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject().put("temperature", 0).apply { if (model.startsWith("gemini-3")) put("thinkingConfig", JSONObject().put("thinkingLevel", "low")) })
        val r = Providers.post(Gemini.ENDPOINT + model + ":generateContent", mapOf("x-goog-api-key" to key), body, "Gemini hearing")
        val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }.trim()
    }

    /**
     * Records what's said (until you stop talking, see [Speech.Endpointer]) and has a speech model write it down.
     * If that fails, it says so and the phone's own recognition is used for the next ten minutes.
     */
    private class Ears(
        private val ctx: Context,
        private val afterWake: Boolean,
        private val onPartial: (String) -> Unit,
        private val onLevel: (Float) -> Unit,
        private val onDone: (String?) -> Unit,
        private val onError: (String) -> Unit
    ) : Hearing {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var stop = false          // finish(): use what there is
        @Volatile private var cancelled = false
        private var thread: Thread? = null

        @SuppressLint("MissingPermission")
        override fun start() {
            if (!VoiceInput.allowed(ctx)) { onError("Atlas needs the microphone. Allow it and try again."); return }
            thread = Thread({ run() }, "atlas-ears").also { it.start() }
        }

        override fun finish() { stop = true }
        override fun cancel() { cancelled = true; stop = true }

        private fun toMain(f: () -> Unit) = main.post { if (!cancelled) f() }

        @SuppressLint("MissingPermission")
        private fun run() {
            val frame = RATE * Speech.FRAME_MS / 1000
            var rec: AudioRecord? = null
            var ns: NoiseSuppressor? = null; var agc: AutomaticGainControl? = null
            val pcm = ByteArrayOutputStream()
            val ep = Speech.Endpointer(endSilenceMs = endSilenceMs(ctx), minVoice = Speech.minVoiceFor(voiceLevel(ctx)))
            var cued = false
            var verdict = Speech.Heard.GOING
            var usedTail = false
            // after "Hey Atlas": the wake listener kept what was said after the phrase until it let go of the
            // microphone; it goes first, so nothing said in that moment is lost
            if (afterWake) {
                val f = tailFile(ctx)
                val until = System.currentTimeMillis() + 1500
                while (!cancelled && System.currentTimeMillis() < until && !(f.exists() && Speech.tailFresh(f.lastModified(), System.currentTimeMillis()))) Thread.sleep(40)
                try {
                    if (f.exists() && Speech.tailFresh(f.lastModified(), System.currentTimeMillis())) {
                        val tail = f.readBytes()
                        val step = frame * 2
                        val sh = ShortArray(frame)
                        var i = 0
                        while (i + step <= tail.size && verdict == Speech.Heard.GOING) {
                            java.nio.ByteBuffer.wrap(tail, i, step).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                            pcm.write(tail, i, step)
                            verdict = ep.feed(Speech.rms(sh))
                            i += step
                        }
                        Log.i(TAG, "kept ${tail.size / 2 * 1000 / RATE} ms said right after the wake word")
                        usedTail = tail.isNotEmpty()
                    }
                } catch (e: Exception) { Log.w(TAG, "wake tail", e) } finally { f.delete() }
            }
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // the wake word may be letting go of the microphone at this very moment: give it a few tries
                for (attempt in 0 until 4) {
                    val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 10))
                    // startRecording throws while another app holds the microphone: let this one go before trying again,
                    // or it keeps the microphone open until it's garbage-collected
                    val ok = try { r.state == AudioRecord.STATE_INITIALIZED && run { r.startRecording(); r.recordingState == AudioRecord.RECORDSTATE_RECORDING } }
                        catch (e: Exception) { Log.w(TAG, "microphone busy", e); false }
                    if (ok) { rec = r; break }
                    try { r.release() } catch (_: Exception) {}
                    if (cancelled) return
                    Thread.sleep(250)
                }
                val r = rec ?: run { toMain { onError("The microphone is busy. Try again in a moment.") }; return }
                try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
                try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(r.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
                val buf = ShortArray(frame)
                val bytes = ByteArray(frame * 2)
                var shown = 0f
                // (everything was already said in the moment after the wake word: nothing more to record)
                while (!stop && verdict == Speech.Heard.GOING) {
                    var got = 0
                    while (got < frame && !stop) { val n = r.read(buf, got, frame - got); if (n <= 0) { stop = true; break }; got += n }
                    if (got < frame) break
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf)
                    pcm.write(bytes)
                    val level = Speech.rms(buf)
                    verdict = ep.feed(level)
                    // a long pause mid-sentence: show that Atlas is still listening, so there's no rush
                    if (ep.pausing != cued) { cued = ep.pausing; val c = cued; toMain { onPartial(if (c) "Still listening…" else "Listening…") } }
                    val l = (level * 9f).coerceIn(0f, 1f); shown += (l - shown) * 0.5f
                    val s = shown; toMain { onLevel(s) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recording", e)
                if (pcm.size() == 0) { toMain { onError("The microphone isn't working right now.") }; return }
            } finally {
                try { ns?.release() } catch (_: Exception) {}
                try { agc?.release() } catch (_: Exception) {}
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }
            toMain { onLevel(0f) }
            if (cancelled) return
            if (verdict == Speech.Heard.NOTHING || ep.speechMs < 150) { toMain { onDone(null) }; return }
            // only the part with the voice in it (and a little either side) is sent
            val all = pcm.toByteArray()
            val from = ((ep.startFrame - 15).coerceAtLeast(0) * frame * 2).coerceAtMost(all.size)
            val wav = Voice.wav(all.copyOfRange(from, all.size), RATE)
            toMain { onPartial("…") }
            val hint = hint(ctx)
            try {
                val heard = transcribe(ctx, wav, hint)
                var text = Speech.fixHeard(heard.text, ep.speechMs, hint)
                if (usedTail) text = Speech.dropWakeLeftover(text)
                if (text.isNotBlank()) {
                    learnPace(ctx, ep.pauses.toList()); learnLevel(ctx, ep.voiceLevel)
                    lastLanguage = heard.language ?: iso(language(ctx)); lastUnsure = heard.unsure; lastAt = System.currentTimeMillis()
                }
                toMain { onDone(text.ifBlank { null }) }
            } catch (e: Exception) {
                cloudDownUntil = System.currentTimeMillis() + 10 * 60_000L
                val offline = (e as? Gemini.Failure)?.offline == true
                toMain { onError(if (offline) "I couldn't reach the internet to make that out. Say it again and I'll use the phone's own hearing." else "I couldn't make that out just now. Say it again and I'll use the phone's own hearing.") }
            }
        }
    }
}
