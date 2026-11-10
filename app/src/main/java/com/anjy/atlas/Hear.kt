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
    fun open(ctx: Context, onPartial: (String) -> Unit, onLevel: (Float) -> Unit, onDone: (String?) -> Unit, onError: (String) -> Unit): Hearing =
        if (usesModel(ctx)) Ears(ctx.applicationContext, onPartial, onLevel, onDone, onError) else VoiceInput(ctx, onPartial, onLevel, onDone, onError)

    /** Names and words Atlas should expect to hear, so they're spelled right: "Atlas", your name, names from your Log. */
    fun hint(ctx: Context): String = try {
        val words = LinkedHashSet<String>()
        words.add("Atlas")
        Store.read(ctx) { st ->
            st.settings.name.trim().takeIf { it.isNotEmpty() }?.let { words.add(it) }
            (st.memories.takeLast(40).map { it.text } + st.tasks.takeLast(40).map { it.title }).forEach { words.addAll(names(it)) }
        }
        words.take(40).joinToString(", ")
    } catch (e: Exception) { "Atlas" }

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

    /** [wav] written down by the first speech model that manages it. Throws when none can. */
    fun transcribe(ctx: Context, wav: ByteArray, hint: String): String {
        var last: Exception? = null
        for ((id, key) in engines(ctx)) {
            try {
                val t = when (id) {
                    "groq" -> whisper("https://api.groq.com/openai/v1/audio/transcriptions", key, "whisper-large-v3-turbo", wav, hint, language(ctx), "Groq")
                    "openai" -> try { whisper("https://api.openai.com/v1/audio/transcriptions", key, "gpt-4o-mini-transcribe", wav, hint, language(ctx), "OpenAI") }
                        catch (e: Gemini.Failure) { if (e.code == 404 || e.code == 400) whisper("https://api.openai.com/v1/audio/transcriptions", key, "whisper-1", wav, hint, language(ctx), "OpenAI") else throw e }
                    else -> gemini(ctx, key, wav, hint)
                }
                Providers.worked(ctx, id, key)
                return t
            } catch (e: Gemini.Failure) {
                Log.w(TAG, "$id couldn't transcribe: ${e.message}")
                if (Providers.keyExhausted(e)) Providers.rest(ctx, id, key, e.code, e.message ?: "")
                last = e
            } catch (e: Exception) { Log.w(TAG, "$id couldn't transcribe", e); last = e }
        }
        throw last ?: IllegalStateException("no speech model has a key")
    }

    /** The OpenAI-style transcription endpoint (OpenAI, Groq): a multipart form with the sound file. */
    private fun whisper(url: String, key: String, model: String, wav: ByteArray, hint: String, lang: String?, service: String): String {
        val boundary = "atlas" + System.nanoTime()
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) = out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model", model); field("response_format", "json"); field("temperature", "0")
        if (hint.isNotBlank()) field("prompt", hint)
        lang?.take(2)?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }?.let { field("language", it) }
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav); out.write("\r\n--$boundary--\r\n".toByteArray())
        val con = try { java.net.URL(url).openConnection() as java.net.HttpURLConnection } catch (e: Exception) { throw Gemini.Failure("Couldn't reach $service.", offline = true) }
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = 30_000
            con.setRequestProperty("Authorization", "Bearer $key"); con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            con.setFixedLengthStreamingMode(out.size())
            val code = try { con.outputStream.use { out.writeTo(it) }; con.responseCode } catch (e: java.io.IOException) { throw Gemini.Failure("Couldn't reach $service.", offline = true) }
            if (code !in 200..299) throw Gemini.Failure(Providers.explain("$service hearing", code, con.errorStream?.use { String(it.readBytes()) } ?: ""), code = code)
            return JSONObject(con.inputStream.use { String(it.readBytes()) }).optString("text").trim()
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
            .put("generationConfig", JSONObject().put("temperature", 0))
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
            val ep = Speech.Endpointer()
            var verdict = Speech.Heard.GOING
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // the wake word may be letting go of the microphone at this very moment: give it a few tries
                for (attempt in 0 until 4) {
                    val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 10))
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        r.startRecording()
                        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { rec = r; break }
                    }
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
                while (!stop && verdict == Speech.Heard.GOING) {
                    var got = 0
                    while (got < frame && !stop) { val n = r.read(buf, got, frame - got); if (n <= 0) { stop = true; break }; got += n }
                    if (got < frame) break
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf)
                    pcm.write(bytes)
                    val level = Speech.rms(buf)
                    verdict = ep.feed(level)
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
                val text = Speech.fixHeard(transcribe(ctx, wav, hint), ep.speechMs, hint)
                toMain { onDone(text.ifBlank { null }) }
            } catch (e: Exception) {
                cloudDownUntil = System.currentTimeMillis() + 10 * 60_000L
                val offline = (e as? Gemini.Failure)?.offline == true
                toMain { onError(if (offline) "I couldn't reach the internet to make that out. Say it again and I'll use the phone's own hearing." else "I couldn't make that out just now. Say it again and I'll use the phone's own hearing.") }
            }
        }
    }
}
