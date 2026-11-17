package com.anjy.atlas

/**
 * The parts of hearing and speaking that are plain logic (no microphone, no network), so they can be tested:
 * when someone has finished talking, how a reply is cut into pieces to speak, and tidying what was heard.
 * The PC has the same rules in desktop/renderer/speech.js; keep the two in step.
 */
object Speech {
    // ---------------------------------------------------------------- when you've finished talking

    /** One frame of sound is [FRAME_MS] long. */
    const val FRAME_MS = 20

    enum class Heard { GOING, DONE, NOTHING }

    /**
     * Follows the loudness of the microphone, frame by frame, and says when the speaker has finished.
     * It learns how loud the room is (so a fan or traffic isn't taken for a voice), needs a moment of steady
     * sound before it counts as speech (so a click or a cough doesn't), and waits [endSilenceMs] of quiet after
     * speech before finishing (so a pause for breath doesn't cut you off).
     */
    class Endpointer(
        private val endSilenceMs: Int = 1300,
        private val noSpeechMs: Int = 7000,
        private val maxMs: Int = 60_000,
    ) {
        private var frames = 0
        private var floor = -1f
        private var recent = 0              // bit history of the last 8 frames: loud or not
        var speaking = false; private set
        /** How much speech there has been, in ms. */
        var speechMs = 0; private set
        /** The frame speech began at (-1 before any). */
        var startFrame = -1; private set
        private var quietMs = 0

        /** The loudness above which a frame counts as voice. */
        val threshold: Float get() = maxOf((if (floor < 0f) 0.004f else floor) * 3f, 0.012f)

        /** [rms]: this frame's loudness, 0..1 (root mean square of the samples, full scale = 1). */
        fun feed(rms: Float): Heard {
            frames++
            // the room's own loudness: start from the first frames, then follow it slowly, and only between words
            if (floor < 0f) floor = rms.coerceAtMost(0.02f)
            val loud = rms > threshold
            if (!loud) floor += (rms - floor) * (if (rms < floor) 0.2f else 0.02f)
            else if (!speaking && frames > 150 && speechMs == 0) floor += (rms.coerceAtMost(0.05f) - floor) * 0.01f   // a steady drone that never became speech is the room
            recent = ((recent shl 1) or (if (loud) 1 else 0)) and 0xFF
            val steady = Integer.bitCount(recent) >= 5
            if (loud) { quietMs = 0 } else quietMs += FRAME_MS
            if (!speaking && steady) { speaking = true; if (startFrame < 0) startFrame = (frames - 8).coerceAtLeast(0) }
            if (speaking && loud) speechMs += FRAME_MS
            val ms = frames * FRAME_MS
            return when {
                ms >= maxMs -> if (speechMs > 0) Heard.DONE else Heard.NOTHING
                speaking && speechMs >= 200 && quietMs >= endSilenceMs -> Heard.DONE
                speaking && speechMs < 200 && quietMs >= endSilenceMs -> { speaking = false; speechMs = 0; startFrame = -1; Heard.GOING }   // a click, not a voice
                !speaking && speechMs == 0 && ms >= noSpeechMs -> Heard.NOTHING
                else -> Heard.GOING
            }
        }
    }

    /** Loudness (0..1) of 16-bit samples. */
    fun rms(pcm: ShortArray, n: Int = pcm.size): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) { val v = pcm[i] / 32768.0; sum += v * v }
        return Math.sqrt(sum / n).toFloat()
    }

    // ---------------------------------------------------------------- speaking a reply in pieces

    private val ABBREV = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "no", "approx", "a.m", "p.m")

    /** Sentences of [text], without splitting after "Dr." or inside "3.5". */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val t = text.trim()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // take any run of closing marks with it: ?! ." .)
                while (i + 1 < t.length && t[i + 1] in ".!?…\"')”’") { i++; sb.append(t[i]) }
                val atEnd = i + 1 >= t.length
                val spaceNext = !atEnd && t[i + 1].isWhitespace()
                val lastWord = sb.trimEnd('.', '!', '?', '…', '"', '\'', ')', '”', '’').toString().substringAfterLast(' ').lowercase()
                val abbrev = c == '.' && (lastWord in ABBREV || (lastWord.length == 1 && lastWord[0].isLetter() && lastWord != "i" && lastWord != "a"))
                if (atEnd || (spaceNext && !abbrev)) { sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }; sb.clear() }
            }
            i++
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    /**
     * [text] cut into pieces to speak one after another: a short first piece so the voice starts quickly, then
     * pieces of a few sentences each (never mid-sentence unless a single sentence is very long).
     */
    fun chunks(text: String, first: Int = 140, rest: Int = 320): List<String> {
        val parts = sentences(text).flatMap { s -> if (s.length <= rest) listOf(s) else splitLong(s, rest) }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in parts) {
            val limit = if (out.isEmpty()) first else rest
            if (sb.isNotEmpty() && sb.length + 1 + p.length > limit) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun splitLong(s: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var left = s
        while (left.length > limit) {
            val window = left.substring(0, limit)
            var cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "))
            if (cut < limit / 3) cut = window.lastIndexOf(' ')
            if (cut < limit / 3) cut = limit - 1
            out.add(left.substring(0, cut + 1).trim())
            left = left.substring(cut + 1).trim()
        }
        if (left.isNotEmpty()) out.add(left)
        return out
    }

    // ---------------------------------------------------------------- tidying what was heard

    /** Things speech models write when there was really only silence or noise. */
    private val GHOSTS = setOf("thank you", "thanks for watching", "thank you for watching", "you", "bye", "okay", "thanks", "so", "the", "uh", "um", "hmm",
        "subtitles by the amara.org community", "please subscribe")
    private val HEY = Regex("""^(hey|hi|ok|okay|yo)[ ,]+(at last|atlus|atlass|alas|atlis|atless|at las|outlast|atlas's)\b[,.!]?""", RegexOption.IGNORE_CASE)
    private val NAME = Regex("""\b(atlus|atlass|atlis)\b""", RegexOption.IGNORE_CASE)

    /**
     * What was heard, tidied: nothing at all when it's only what a speech model invents for silence ([speechMs] is
     * how much voice the microphone really picked up; pass a large number when unknown) or the hint echoed back;
     * and "Hey at last" put right to "Hey Atlas".
     */
    fun fixHeard(text: String?, speechMs: Int = 10_000, hint: String = ""): String {
        var t = (text ?: "").trim().trim('"').trim()
        if (t.isEmpty()) return ""
        val bare = t.lowercase().trim('.', '!', '?', ',', ' ', '…')
        if (bare.isEmpty()) return ""
        if (speechMs < 700 && bare in GHOSTS) return ""
        if (hint.isNotBlank() && bare == hint.lowercase().trim('.', '!', '?', ',', ' ')) return ""
        t = HEY.replace(t) { m -> m.groupValues[1].replaceFirstChar { it.uppercase() } + " Atlas," }
        t = NAME.replace(t, "Atlas")
        return t.replace(Regex("""\s+"""), " ").replace(",,", ",").trim().trimEnd(',')
    }

    private val END = Regex("""^(no[, ]+)?(thanks?( you)?[, ]+)?(that'?s all|that is all|that will be all|that'll be all|never ?mind|stop listening|good ?bye|bye( bye)?|nothing else|that'?s it|i'?m done|we'?re done)( for now)?([, ]+(thanks?( you)?|atlas))*$""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- talking while Atlas works

    /** What something said while Atlas is working means. */
    enum class WhileWorking { STOP, END_TALK, PASS_ON }

    private val STOP_WORK = Regex("""^(please[, ]+)?(stop( it| that| now| everything| working)?|cancel( it| that)?|abort|halt|hold on,? stop|wait,? stop|don'?t do (it|that)|never ?mind,? stop)([, ]+(please|atlas|now))*$""", RegexOption.IGNORE_CASE)

    /**
     * While Atlas is doing something and the conversation carries on: "stop" stops the work, "that's all" ends the
     * conversation (the work goes on), anything else is passed on to Atlas as it works (a question, a change of plan).
     */
    fun whileWorking(text: String): WhileWorking {
        val t = text.trim().trim('.', '!', '?', ',', ' ')
        return when {
            STOP_WORK.matches(t) -> WhileWorking.STOP
            endsTalk(t) -> WhileWorking.END_TALK
            else -> WhileWorking.PASS_ON
        }
    }

    // ---------------------------------------------------------------- the words after "Hey Atlas"

    /**
     * Where the request starts in the audio the wake listener already has, in seconds, from a Vosk result with word
     * times ({"result":[{"word":"atlas","start":…,"end":…},…]}); null if "atlas" isn't there. The small model's word
     * ends run late into the next word (measured: cutting at the end of "atlas" lost the start of the next word in
     * 17 of 28 clips), so the cut is a quarter second before the earlier of "atlas" ending and the next word
     * starting; a leftover of "atlas" before the request is removed from the words afterwards ([dropWakeLeftover]).
     */
    fun requestStart(voskJson: String?): Double? = try {
        val arr = org.json.JSONObject(voskJson ?: return null).optJSONArray("result") ?: return null
        var at = -1
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("word") == "atlas") at = i
        if (at < 0) null else {
            val w = arr.getJSONObject(at)
            val next = arr.optJSONObject(at + 1)?.optDouble("start")?.takeIf { !it.isNaN() }
            val edge = if (next != null) minOf(w.optDouble("end"), next) else w.optDouble("end")
            maxOf(w.optDouble("start"), edge - 0.25)
        }
    } catch (_: Exception) { null }

    private val LEFTOVER = Regex("""^\s*(hey[ ,]+)?(atlas|at las|las|lass|les|less|lis)\b[,.!]?\s*""", RegexOption.IGNORE_CASE)

    /** What's left of "Hey Atlas" at the start of the words after it ("las, set a reminder…" → "set a reminder…"). */
    fun dropWakeLeftover(text: String): String {
        val t = LEFTOVER.replace(text, "").trim()
        return if (t.isEmpty()) text.trim() else t.replaceFirstChar { it.uppercase() }
    }

    /** A recording the wake listener kept is used only if it's fresh: the wake was moments ago. */
    fun tailFresh(writtenAt: Long, now: Long): Boolean = now - writtenAt in 0..8_000

    /** Google's British male voices on Android are named like "en-gb-x-rjs-local" (rjs, gbb, gbd are men's voices). */
    fun britishMale(name: String?): Boolean = name != null && Regex("en-gb-x-(rjs|gbb|gbd)(-|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** "That's all", "never mind", "goodbye": the conversation is over. */
    fun endsTalk(text: String): Boolean = END.matches(text.trim().trim('.', '!', ' '))

    /** The `voice_…` id in a voice-design answer (it may come as "id" or as a resource name ending in it). */
    fun voiceId(o: Any?): String? {
        var found: String? = null
        fun walk(x: Any?) {
            if (found != null) return
            when (x) {
                is org.json.JSONObject -> {
                    for (k in listOf("id", "name", "voice")) { val v = x.opt(k); if (v is String && v.substringAfterLast('/').startsWith("voice_")) { found = v.substringAfterLast('/'); return } }
                    x.keys().forEach { k -> if (k != "sample_audio") walk(x.opt(k)) }
                }
                is org.json.JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return found
    }


    // ---------------------------------------------------------------- how fast Atlas talks

    /** "slow", "normal" or "fast" from whatever was said or stored. */
    fun speed(s: String?): String = when {
        s == null -> "normal"
        s.contains("slow", true) -> "slow"
        s.contains("fast", true) || s.contains("quick", true) -> "fast"
        else -> "normal"
    }
    /** The rate to give a speech engine (1 = its normal pace). */
    fun rate(speed: String): Float = when (speed) { "slow" -> 0.88f; "fast" -> 1.15f; else -> 1f }
    /** The same as words, for engines that are directed in plain language. */
    fun pace(speed: String): String = when (speed) { "slow" -> "speaking slowly and clearly"; "fast" -> "speaking briskly"; else -> "" }

    // ---------------------------------------------------------------- voice memos

    /**
     * The sample rate and sound data of a WAV file (its "data" chunk), so several pieces can be joined into one
     * memo. Anything that isn't a RIFF/WAVE file is taken as raw 16-bit sound at 24 kHz (what Gemini sends).
     */
    fun wavData(b: ByteArray): Pair<Int, ByteArray> {
        fun tag(at: Int, t: String) = at + 4 <= b.size && String(b, at, 4, Charsets.US_ASCII) == t
        fun le32(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
        if (!(tag(0, "RIFF") && tag(8, "WAVE"))) return 24000 to b
        var rate = 24000
        var at = 12
        while (at + 8 <= b.size) {
            val len = le32(at + 4).coerceAtLeast(0)
            if (tag(at, "fmt ") && at + 16 <= b.size) rate = le32(at + 12)
            if (tag(at, "data")) return rate to b.copyOfRange(at + 8, minOf(b.size, at + 8 + len))
            at += 8 + len + (len and 1)
        }
        return rate to ByteArray(0)
    }

    /**
     * What a bot's voice memo says: the report as speech, without its Sources list, cut at a sentence end after about
     * [max] characters (a minute or so), with a pointer to the full report when it was cut.
     */
    fun memoText(report: String, max: Int = 1100): String {
        val body = report.lines().takeWhile { !Regex("^\\s*(#+\\s*|\\*\\*)?sources\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.joinToString("\n")
        val said = Plain.spoken(body).trim()
        if (said.length <= max) return said
        val cut = said.take(max)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        return (if (end > max / 3) cut.take(end + 1) else cut.substringBeforeLast(' ') + "…") + " The full report is in the chat."
    }
}
