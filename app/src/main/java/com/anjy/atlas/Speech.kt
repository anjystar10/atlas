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
        /** The quietest a voice frame can be (a soft-spoken person's, learned in "Teach Atlas Your Voice", is lower). */
        private val minVoice: Float = 0.012f,
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
        private var runMs = 0               // the current (or last) stretch of voice
        private var lastRunMs = 0
        /** The pauses inside what was said (ms), for learning how this person talks ([Pace]); the final silence isn't one. */
        val pauses = mutableListOf<Int>()
        /** Loudness of the voice (mean rms of voiced frames), for the calibration in "Teach Atlas Your Voice". */
        var voiceLevel = 0f; private set
        private var voicedFrames = 0
        /** True while the speaker has paused long enough that a cue ("still listening") helps, but not long enough to end. */
        var pausing = false; private set

        /** The loudness above which a frame counts as voice. */
        val threshold: Float get() = maxOf((if (floor < 0f) 0.004f else floor) * 3f, minVoice)

        /**
         * The silence that ends a turn now. Longer after a short false start or a stammered fragment ("I… I want"),
         * and after very little speech ("um"), so a speaker who needs a moment isn't cut off.
         */
        val needMs: Int get() = if (speechMs < 700 || (lastRunMs in 1..349 && speechMs < 2500)) endSilenceMs * 3 / 2 else endSilenceMs

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
            if (loud) {
                if (speaking && quietMs >= 200) pauses.add(quietMs)
                if (quietMs >= 200) runMs = 0
                quietMs = 0; runMs += FRAME_MS
            } else { if (quietMs == 0 && runMs > 0) lastRunMs = runMs; quietMs += FRAME_MS }
            if (!speaking && steady) { speaking = true; if (startFrame < 0) startFrame = (frames - 8).coerceAtLeast(0) }
            if (speaking && loud) { speechMs += FRAME_MS; voicedFrames++; voiceLevel += (rms - voiceLevel) / voicedFrames }
            pausing = speaking && speechMs >= 200 && quietMs >= 700 && quietMs < needMs
            val ms = frames * FRAME_MS
            return when {
                ms >= maxMs -> if (speechMs > 0) Heard.DONE else Heard.NOTHING
                speaking && speechMs >= 200 && quietMs >= needMs -> Heard.DONE
                speaking && speechMs < 200 && quietMs >= endSilenceMs -> { speaking = false; speechMs = 0; startFrame = -1; pauses.clear(); Heard.GOING }   // a click, not a voice
                !speaking && speechMs == 0 && ms >= noSpeechMs -> Heard.NOTHING
                else -> Heard.GOING
            }
        }
    }

    /**
     * How long this person pauses mid-sentence, learned on the phone from what they say (and from "Teach Atlas Your
     * Voice"): the end-of-speech silence is set well above their usual long pause, so a slow or careful speaker, or one
     * who stammers, isn't cut off, and a brisk one isn't kept waiting. [p90]: their 90th-percentile pause (ms), [n]: how
     * many pauses it's learned from. [patient]: "Give Me Time to Finish" in Me → Voice (longer still).
     */
    /** The lowest voice threshold for someone whose voice measured [level] (mean rms): a quarter of it, between 0.006 and the usual 0.012. */
    fun minVoiceFor(level: Float?): Float = if (level == null || level <= 0f) 0.012f else (level / 4f).coerceIn(0.006f, 0.012f)

    class Pace(val p90: Int = 0, val n: Int = 0) {
        fun endSilenceMs(patient: Boolean = false): Int {
            val base = if (n < 8) 1300 else (p90 * 3 / 2 + 300).coerceIn(1100, 2800)
            return if (patient) (base * 8 / 5).coerceIn(2200, 4500) else base
        }
        /** Takes in the pauses of one turn (the 90th percentile of the turn, blended in; a lone turn moves it a little). */
        fun learn(turn: List<Int>): Pace {
            val ps = turn.filter { it in 200..4000 }.sorted()
            if (ps.isEmpty()) return this
            val q = ps[(ps.size * 9 + 9) / 10 - 1]   // nearest-rank 90th percentile
            val w = minOf(ps.size, 6).toFloat() / (minOf(n, 60) + minOf(ps.size, 6))
            return Pace(if (n == 0) q else (p90 + (q - p90) * w).toInt(), minOf(n + ps.size, 1000))
        }
        fun save() = "$p90,$n"
        companion object {
            fun parse(s: String?): Pace = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let { Pace(it[0], it[1]) } ?: Pace()
        }
    }

    /**
     * What a speech model wrote down, with how sure it was. [language]: the language it heard (ISO 639-1) if it said.
     * [unsure]: the model's own scores say it may have misheard (Whisper's average log-probability is low, its
     * text repeats itself, or it thinks there was no speech), so Atlas checks before acting rather than guessing.
     */
    class Transcript(val text: String, val language: String? = null, val unsure: Boolean = false)

    /** Reads Whisper's verbose_json (Groq) or gpt-transcribe's json (OpenAI: "languages": [{"code": …}]). */
    fun heardFrom(json: String): Transcript {
        val o = org.json.JSONObject(json)
        val text = o.optString("text").trim()
        var lang: String? = o.optString("language").trim().takeIf { it.isNotEmpty() }
        if (lang == null) lang = o.optJSONArray("languages")?.optJSONObject(0)?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
        val segs = o.optJSONArray("segments")
        var unsure = false
        if (segs != null && segs.length() > 0) {
            var weight = 0.0; var lp = 0.0; var rep = false; var silent = true
            for (i in 0 until segs.length()) {
                val g = segs.optJSONObject(i) ?: continue
                val d = (g.optDouble("end", 0.0) - g.optDouble("start", 0.0)).coerceAtLeast(0.1)
                weight += d; lp += d * g.optDouble("avg_logprob", 0.0)
                if (g.optDouble("compression_ratio", 1.0) > 2.4) rep = true
                if (g.optDouble("no_speech_prob", 0.0) < 0.6) silent = false
            }
            unsure = (weight > 0 && lp / weight < -0.7) || rep || silent
        }
        return Transcript(text, isoName(lang), unsure)
    }

    /** "english" / "en" / "en-GB" → "en"; unknown → null. Whisper names the language in words, gpt-transcribe by code. */
    fun isoName(lang: String?): String? {
        val l = lang?.trim()?.lowercase() ?: return null
        if (Regex("^[a-z]{2}([-_][a-z]{2,4})?$").matches(l)) return l.take(2)
        return LANG_NAMES[l]
    }
    private val LANG_NAMES = mapOf("english" to "en", "french" to "fr", "spanish" to "es", "portuguese" to "pt", "arabic" to "ar", "hausa" to "ha",
        "yoruba" to "yo", "igbo" to "ig", "german" to "de", "italian" to "it", "dutch" to "nl", "chinese" to "zh", "japanese" to "ja", "korean" to "ko",
        "hindi" to "hi", "russian" to "ru", "turkish" to "tr", "swahili" to "sw", "polish" to "pl", "ukrainian" to "uk", "urdu" to "ur", "bengali" to "bn",
        "indonesian" to "id", "vietnamese" to "vi", "thai" to "th", "persian" to "fa", "hebrew" to "he", "greek" to "el", "swedish" to "sv", "tagalog" to "tl")

    /**
     * Words from "Teach Atlas Your Voice": those in the phrase that the speech model didn't write down as said, so
     * they're given to it as words to expect from then on. Compared without case or punctuation.
     */
    fun missedWords(expected: String, heard: String): List<String> {
        fun words(s: String) = s.split(Regex("""[^\p{L}\p{N}']+""")).filter { it.isNotBlank() }
        val got = words(heard).map { it.lowercase() }.toSet()
        return words(expected).filter { it.length >= 3 && it.lowercase() !in got }.distinct()
    }

    /**
     * What to read aloud in "Teach Atlas Your Voice": everyday requests with numbers, times, names and a natural pause,
     * using the user's own name and up to two of the words they've told Atlas to know.
     */
    fun teachPhrases(name: String, words: List<String>): List<String> {
        val who = words.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Tunde"
        val what = words.getOrNull(1)?.takeIf { it.isNotBlank() }
        val me = name.trim().split(" ").firstOrNull()?.takeIf { it.length >= 2 }
        return listOfNotNull(
            "Hey Atlas, what's on my calendar for tomorrow morning?",
            "Set a reminder for half past six to call my sister.",
            "Send a message to $who saying I'll be about ten minutes late.",
            what?.let { "Remind me about $it on Friday at four." },
            "What's the weather going to be like this weekend? And will it rain on Sunday?",
            "Play something relaxing, and turn the volume down a little.",
            me?.let { "My name is $it, and I'd like you to keep my answers short." },
        )
    }

    /** The names in a teaching phrase that weren't heard as said (capitalised words after the first), to expect from then on. */
    fun missedNames(phrase: String, heard: String): List<String> {
        val first = phrase.split(Regex("""[^\p{L}\p{N}']+""")).firstOrNull { it.isNotBlank() }
        return missedWords(phrase, heard).filter { it[0].isUpperCase() && it != first && it != "Atlas" && it != "I'd" && it != "I'll" }
    }

    /** Joins word lists for the speech model's hint: in order, no repeats (any case), at most [max] words and [chars] characters. */
    fun hintOf(groups: List<List<String>>, max: Int = 50, chars: Int = 700): String {
        val out = LinkedHashMap<String, String>()
        for (g in groups) for (w in g) { val t = w.trim(); if (t.isNotEmpty() && t.length <= 40) out.putIfAbsent(t.lowercase(), t) }
        val sb = StringBuilder()
        for ((i, w) in out.values.withIndex()) {
            if (i >= max || sb.length + w.length + 2 > chars) break
            if (sb.isNotEmpty()) sb.append(", "); sb.append(w)
        }
        return sb.toString()
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

    /**
     * How Atlas answers a spoken request (one line of its instructions; the PC's speech.js has the same words): short,
     * in the language the user spoke, the composed butler's manner, and a question instead of a guess when it didn't
     * catch something. [sir]: the user asked to be called "sir" now and then.
     */
    fun manner(sir: Boolean): String = "- This message came by voice and your answer will be read aloud. " +
        "Reply in the language the user spoke (if they mixed languages, the main one). " +
        "Keep it short: usually one or two sentences, three at most, the key fact first. No lists, no formatting, no links, no emoji; " +
        "say numbers, times and units the way a person says them. " +
        "Your manner: composed, precise and unflappable, like a superbly capable butler; courteous and quietly warm, " +
        "with a dry, understated wit when it fits (never forced, and never about bad news or anything serious); never gushing. " +
        "Confirm what you did in a few words (\"Done.\" \"Reminder set for six.\") rather than describing it. " +
        "The words were written down from speech, so a word may be misheard (accents, names, a stammer): read it sensibly in context, " +
        "and if it still could mean two different things that matter, ask one short question (\"Tunde or Tunji?\") instead of guessing." +
        (if (sir) " Address the user as \"sir\" now and then: at most once in a reply, and not in every reply." else "")

    /** What the speech model noticed about this request, for Atlas's instructions ("" when nothing to add). */
    fun heardNote(language: String?, unsure: Boolean): String =
        (if (language != null && language != "en") " The speech model heard this in the language with code \"$language\"; reply in it." else "") +
        (if (unsure) " The speech model was unsure of these words: if the request doesn't make clear sense, or a name or number in it could be misheard and matters, ask in one short question before acting." else "")

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

    /** (sample rate, sound) of a WAV file only if it is plain 16-bit mono PCM (what the finish works on); else null. */
    fun wavPcm16Mono(b: ByteArray): Pair<Int, ByteArray>? {
        fun tag(at: Int, t: String) = at + 4 <= b.size && String(b, at, 4, Charsets.US_ASCII) == t
        fun le16(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        fun le32(at: Int) = le16(at) or (le16(at + 2) shl 16)
        if (!(tag(0, "RIFF") && tag(8, "WAVE"))) return null
        var at = 12
        var ok = false
        while (at + 8 <= b.size) {
            val len = le32(at + 4).coerceAtLeast(0)
            if (tag(at, "fmt ") && at + 24 <= b.size) ok = le16(at + 8) == 1 && le16(at + 10) == 1 && le16(at + 22) == 16
            if (tag(at, "data")) return if (ok) wavData(b) else null
            at += 8 + len + (len and 1)
        }
        return null
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

    // ---- why the voice never changed: a voice remembered forever, and failures nobody saw (see VoiceCache, ttsKeyProblem) ----

    /**
     * Remembered voices (Gemini's designed Atlas voice, the library voice) used to be kept forever per key: once made (or
     * once turned down), no later change to the design, the choice or the code could ever reach the user. Every entry
     * now carries the voice revision it was made under, and a refusal is only remembered for [RETRY_MS].
     * Saved form: "rev|keyTag|id|madeAtMs" (id "-" = turned down).
     */
    object VoiceCache {
        /** Bump to make every device make Atlas's voice afresh (the design or the way it's chosen changed). */
        const val REV = "v3"
        const val RETRY_MS = 6 * 60 * 60_000L
        sealed class Hit { data class Use(val id: String) : Hit(); object Refused : Hit(); object Miss : Hit() }
        fun rev(design: String, model: String) = REV + "-" + Integer.toHexString((design + "|" + model).hashCode())
        fun entry(rev: String, keyTag: String, id: String?, now: Long) = "$rev|$keyTag|${id ?: "-"}|$now"
        fun read(saved: String?, rev: String, keyTag: String, now: Long): Hit {
            val p = saved?.split("|") ?: return Hit.Miss
            if (p.size != 4 || p[0] != rev || p[1] != keyTag) return Hit.Miss       // old format, older voice, another key
            if (p[2] == "-") return if (now - (p[3].toLongOrNull() ?: 0L) < RETRY_MS) Hit.Refused else Hit.Miss
            return if (p[2].isNotBlank()) Hit.Use(p[2]) else Hit.Miss
        }
    }

    /**
     * A speech request the next key in the pool should try: a used-up limit (Gemini's speech model allows only about
     * 10 requests a minute and 100 a day per project) or a key that's turned down. Not a bad request, not the network.
     */
    fun ttsKeyProblem(code: Int) = code == 429 || code == 401 || code == 403
    /** How long a key rests from speech after [code]: a daily limit until tomorrow-ish, a per-minute one for a minute. */
    fun ttsRestMs(code: Int, message: String, retryAfterMs: Long?): Long = when {
        code == 429 && Regex("(?i)per ?day|PerDay|daily").containsMatchIn(message) -> 6 * 60 * 60_000L
        code == 429 -> (retryAfterMs ?: 0L).coerceIn(30_000L, 10 * 60_000L)
        code == 401 || code == 403 -> 60 * 60_000L
        else -> 0L
    }
    /** Natural voices: fewer, longer pieces (every piece is one request against a small limit); a short first one so it starts fast. */
    const val NATURAL_FIRST = 220
    const val NATURAL_REST = 900
    /** One plain line for "the natural voice didn't work, so the phone's voice spoke" (shown once, not every reply). */
    fun fallbackNote(problem: String?, phone: String = "the phone's voice"): String =
        if (problem.isNullOrBlank()) "Atlas's natural voice isn't available, so $phone spoke. Add a Gemini, ElevenLabs or OpenAI key (Me → Voice)."
        else "Atlas's natural voice didn't work ($problem), so $phone spoke. Me → Voice → Test Each Voice shows why."
}
