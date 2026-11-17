package com.anjy.atlas

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * ElevenLabs as Atlas's voice: a voice of Atlas's own, made once per key with ElevenLabs' Voice Design from a written
 * description (text-to-voice: design → previews → create), then spoken with the streaming speech endpoint as raw
 * 24 kHz PCM. Checked against ElevenLabs' API reference (November 2026): POST /v1/text-to-voice/design (model
 * eleven_ttv_v3), POST /v1/text-to-voice (generated_voice_id → voice_id), POST /v1/text-to-speech/{voice}/stream
 * (output_format pcm_24000, model eleven_multilingual_v2, which works out the language from the text itself).
 * The description names traits only: no real person, actor or character, and no recording of anyone is used.
 */
object ElevenLabs {
    private const val TAG = "AtlasEleven"
    private const val API = "https://api.elevenlabs.io/v1"
    const val ID = "elevenlabs"
    const val DESIGN_MODEL = "eleven_ttv_v3"
    const val SPEECH_MODEL = "eleven_multilingual_v2"
    const val RATE = 24000
    /** The same seed with the same description gives the same voice (per ElevenLabs), so Atlas sounds alike on every key and device. */
    const val SEED = 1096043603

    internal const val DESIGN = "A refined British man in his mid-forties with a crisp, educated Received Pronunciation accent. " +
        "A calm, low, smooth baritone with precise diction; unhurried, composed and quietly confident, with an understated dry wit. " +
        "Courteous and warm but reserved, like a polished butler who is also a highly capable AI assistant. " +
        "Clean studio recording, close microphone, no background noise."

    class Failure(message: String, val code: Int = 0, val offline: Boolean = false) : Exception(message)

    /** Whether a failure says this key can't go on (wrong key, no credit, limit) rather than a passing hiccup. */
    fun keyProblem(e: Failure) = e.code == 401 || e.code == 402 || e.code == 403 || e.code == 429

    /**
     * Atlas's designed voice on this key, made the first time and remembered with the key it belongs to. A refusal
     * (no access to Voice Design on this plan, say) is remembered for this key so it isn't asked again; a dropped
     * connection tries again next time. Throws [Failure] when there is no voice to speak with.
     */
    fun voice(ctx: Context, key: String): String {
        val tag = key.takeLast(6)
        Store.getString(ctx, "eleven_voice")?.split("|", limit = 2)?.takeIf { it.size == 2 && it[0] == tag }?.let { (_, id) ->
            if (id == "-") throw Failure("ElevenLabs couldn't design Atlas's voice on this key.", 403)
            if (id.isNotBlank()) return id
        }
        try {
            val design = post(key, "$API/text-to-voice/design?output_format=mp3_22050_32", JSONObject()
                .put("voice_description", DESIGN).put("model_id", DESIGN_MODEL)
                .put("auto_generate_text", true).put("seed", SEED))
            val gen = JSONObject(String(design)).optJSONArray("previews")?.optJSONObject(0)?.optString("generated_voice_id")?.takeIf { it.isNotBlank() }
                ?: throw Failure("ElevenLabs designed no voice.")
            val made = JSONObject(String(post(key, "$API/text-to-voice", JSONObject()
                .put("voice_name", "Atlas").put("voice_description", DESIGN).put("generated_voice_id", gen)
                .put("labels", JSONObject().put("made_by", "Atlas")))))
            val id = made.optString("voice_id").takeIf { it.isNotBlank() } ?: throw Failure("ElevenLabs saved no voice.")
            Store.putString(ctx, "eleven_voice", "$tag|$id")
            return id
        } catch (e: Failure) {
            Log.w(TAG, "voice design", e)
            if (!e.offline && e.code in 400..499 && e.code != 429 && e.code != 408 && e.code != 409 && e.code != 401) Store.putString(ctx, "eleven_voice", "$tag|-")
            throw e
        }
    }

    /**
     * [text] spoken in Atlas's designed voice: raw 16-bit mono PCM at [RATE]. [before]/[after]: the text around this
     * piece, so the pieces of one reply flow as one (ElevenLabs' previous_text / next_text). Blocking (network).
     */
    fun speech(ctx: Context, key: String, text: String, before: String?, after: String?): ByteArray {
        val voice = voice(ctx, key)
        val body = JSONObject().put("text", text).put("model_id", SPEECH_MODEL)
            .put("voice_settings", JSONObject().put("stability", 0.55).put("similarity_boost", 0.8).put("style", 0.0).put("use_speaker_boost", true))
        before?.takeIf { it.isNotBlank() }?.let { body.put("previous_text", it.takeLast(600)) }
        after?.takeIf { it.isNotBlank() }?.let { body.put("next_text", it.take(600)) }
        return try { post(key, "$API/text-to-speech/$voice/stream?output_format=pcm_$RATE", body) }
        catch (e: Failure) {
            // the voice was removed from the account: design it again next time
            if (e.code == 404 || (e.code == 400 && (e.message ?: "").contains("voice", true))) Store.putString(ctx, "eleven_voice", "")
            throw e
        }
    }

    private fun post(key: String, url: String, body: JSONObject): ByteArray {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 15_000; con.readTimeout = 90_000
            con.setRequestProperty("xi-api-key", key); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            if (code !in 200..299) throw Failure(explain(code, con.errorStream?.use { String(it.readBytes()) } ?: ""), code)
            return con.inputStream.use { it.readBytes() }
        } catch (e: Failure) { throw e
        } catch (e: UnknownHostException) { throw Failure("No internet connection.", offline = true)
        } catch (e: SocketTimeoutException) { throw Failure("ElevenLabs took too long to answer.", offline = true)
        } catch (e: IOException) { throw Failure("Couldn't reach ElevenLabs: ${e.message ?: e.javaClass.simpleName}", offline = true)
        } finally { con.disconnect() }
    }

    /** ElevenLabs' errors come as {"detail": {"status": …, "message": …}} (or a list of problems for a bad request). */
    internal fun explain(code: Int, body: String): String {
        val msg = try {
            val o = JSONObject(body)
            o.optJSONObject("detail")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optJSONArray("detail")?.optJSONObject(0)?.optString("msg")
                ?: o.optString("detail")
        } catch (_: Exception) { "" } ?: ""
        return when (code) {
            401 -> "ElevenLabs turned the key down: ${msg.ifBlank { "not authorised" }.take(160)}"
            402 -> "ElevenLabs says the account has no credit left: ${msg.take(120)}"
            403 -> "ElevenLabs won't allow that on this plan: ${msg.ifBlank { "forbidden" }.take(160)}"
            429 -> "The ElevenLabs limit is used up for now."
            in 500..599 -> "ElevenLabs is having trouble right now ($code)."
            else -> "ElevenLabs error $code: ${msg.ifBlank { body }.take(160)}"
        }
    }
}
