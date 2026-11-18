package com.anjy.atlas

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Atlas Voice from the laptop: when this phone is linked to the PC and the PC runs Atlas Voice (Atlas's own designed
 * voice on its graphics card, desktop/atlasvoice.js), the phone asks the laptop to speak each piece of a reply and plays
 * it, with its own "AI system" finish. Over the Device Link (end-to-end encrypted, the same relay as tasks); when the
 * laptop is off, asleep or slow, the phone's other voices speak instead, so a reply is never lost.
 */
object LaptopVoice {
    const val ID = "laptop"
    private const val WAIT_MS = 15_000L
    /** Longest piece sent to the laptop in one go (the voice server answers a few seconds of speech at a time). */
    const val MAX_CHARS = 1200
    /**
     * Playback pace for Atlas Voice: replies cloned from the designed voice come out about 20% brisker than it (voice lab:
     * ~215 words a minute against ~176), so they play at 0.86 (pitch kept): calm and unhurried, like the designed voice.
     */
    const val PACE = 0.86f

    /** Off unless the laptop advertises Atlas Voice; the user can also switch it off ("voice_laptop"). */
    fun enabled(ctx: Context) = Store.getFlag(ctx, "voice_laptop", true)
    fun setEnabled(ctx: Context, on: Boolean) = Store.setFlag(ctx, "voice_laptop", on)

    /** After a failure the laptop rests for a while, so the next pieces of a reply don't each wait for it. */
    @Volatile private var restUntil = 0L

    /**
     * The laptop says it can speak (its beat lists "voice" in caps), it was online at a recent check-in, it hasn't just
     * failed, and this phone is linked. In-memory only: safe on the main thread.
     */
    fun available(ctx: Context): Boolean {
        if (!enabled(ctx) || !Sync.linked(ctx) || System.currentTimeMillis() < restUntil) return false
        val lap = try { Link.init(ctx); Link.laptop() } catch (_: Exception) { null } ?: return false
        return lap.online && Link.beatAgeMs() < FRESH_MS && canSpeak(lap.info)
    }
    private const val FRESH_MS = 90_000L
    private const val REST_MS = 120_000L

    /** Pure: does a peer's beat info say it runs Atlas Voice? */
    fun canSpeak(info: JSONObject?): Boolean {
        val caps = info?.optJSONArray("caps") ?: return false
        for (i in 0 until caps.length()) if (caps.optString(i) == "voice") return true
        return false
    }

    /** Which voice speaks: Atlas Voice whenever it can (unless another was picked while it was there), else as before. */
    fun choose(have: List<String>, pick: String?, laptopOff: Boolean): String? {
        if (ID in have && (pick == ID || !laptopOff)) return ID
        return pick?.takeIf { it in have } ?: have.firstOrNull { it != ID } ?: have.firstOrNull()
    }

    /** Pure: the laptop's reply → (rate, 16-bit mono PCM). Throws with the laptop's reason when it couldn't speak. */
    fun decode(reply: JSONObject?): Pair<Int, ByteArray> {
        if (reply == null) throw IllegalStateException("the laptop didn't answer in time")
        reply.optString("error").takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        val rate = reply.optInt("rate", 0); val b64 = reply.optString("pcm")
        if (rate !in 8000..48000 || b64.isEmpty()) throw IllegalStateException("the laptop sent no audio")
        return rate to java.util.Base64.getDecoder().decode(b64)
    }

    /** Pure: the language name Qwen3-TTS takes, from the ears' code ("en", "fr-FR", "spanish"); others: English. */
    fun languageName(code: String?): String {
        val c = (code ?: "").lowercase().trim().split('-', '_').first()
        val names = mapOf("en" to "English", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "de" to "German",
            "fr" to "French", "ru" to "Russian", "pt" to "Portuguese", "es" to "Spanish", "it" to "Italian")
        return names[c] ?: names.values.firstOrNull { it.lowercase() == c } ?: "English"
    }

    /** One piece of speech from the laptop. Blocking (network): call off the main thread. */
    fun speech(ctx: Context, text: String, language: String?): Pair<Int, ByteArray> {
        Link.init(ctx)
        val lap = Link.laptop() ?: throw IllegalStateException("no laptop linked")
        val tid = UUID.randomUUID().toString()
        Link.setActive(true)   // listen continuously until the answer comes
        try {
            Link.send(lap.device, "voice_req", JSONObject().put("tid", tid).put("text", text.take(MAX_CHARS)).put("language", languageName(language)))
            return decode(Link.waitFor("voice_reply", tid, WAIT_MS))
        } catch (e: Exception) {
            restUntil = System.currentTimeMillis() + REST_MS   // the phone's other voices carry on meanwhile
            throw e
        } finally { Link.setActive(false) }
    }
}
