package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversations with bots: the pure rules, the same as the PC's desktop/renderer/threadcore.js (checked against
 * tools/threads-fixtures.json by ThreadsTest). A thread is either a bot's own conversation ("dm-<botId>", the main way
 * to work with a bot) or a group chat: the user and up to six bots. The user @mentions a bot to give it the job; bots
 * may @mention each other to hand work on, but only within limits, so two bots can never keep each other going forever.
 * Only messages from the user can authorize anything (docs/BOTS_V2.md §8).
 */
object ThreadRules {
    const val V = 2
    const val MAX_MESSAGES = 200
    const val MAX_TEXT = 8000
    const val MAX_BOT_TURNS = 8
    const val MAX_PAIR_HOPS = 3
    const val MAX_PARTICIPANTS = 7
    const val USER = "user"
    const val ATLAS = "atlas"
    const val DIRECT = "dm-"

    /** One message. [kind]: message (default), approval, question, report, note. */
    data class Msg(val id: String, val at: Long, val from: String, val text: String, val mentions: List<String> = emptyList(),
                   var mission: String? = null, val kind: String? = null) {
        val kindOrMessage get() = kind ?: "message"
        fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("from", from).put("text", text)
            .put("mentions", JSONArray(mentions)).put("mission", mission ?: JSONObject.NULL).also { if (kind != null) it.put("kind", kind) }
    }

    /** One conversation. [extra] keeps fields a newer Atlas wrote, so they survive a round trip through this phone. */
    class Thread(val id: String, val kind: String, var title: String, var participants: List<String>, val messages: MutableList<Msg>,
                 var updatedAt: Long, val extra: JSONObject = JSONObject()) {
        val direct get() = kind == "direct"
        fun json(): JSONObject {
            val o = JSONObject(extra.toString())
            o.put("id", id).put("v", V).put("kind", kind).put("title", title).put("participants", JSONArray(participants))
                .put("messages", JSONArray().also { a -> messages.forEach { a.put(it.json()) } }).put("updatedAt", updatedAt)
            return o
        }
    }

    class Decision(val starts: List<String>, val waiting: String?, val hint: String?)

    class Bot(val id: String, val name: String)

    private fun clip(s: Any?, n: Int): String = (if (s == null || s == JSONObject.NULL) "" else s.toString()).trim().take(n)
    private fun numL(x: Any?): Long = when (x) { is Number -> x.toLong(); is String -> x.trim().toDoubleOrNull()?.toLong() ?: 0L; else -> 0L }

    fun directId(botId: String) = DIRECT + clip(botId, 56)
    fun directBot(t: Thread?): String? = if (t != null && t.direct) t.participants.getOrNull(1) else null

    /** Messages since [seenAt] that the user hasn't read (anything not from the user). */
    fun unreadCount(t: Thread?, seenAt: Long): Int = t?.messages?.count { it.from != USER && it.at > seenAt } ?: 0

    /** The newest message worth showing in a list row. */
    fun preview(t: Thread?): Msg? = t?.messages?.lastOrNull { it.text.isNotBlank() }

    fun cleanMessage(m: JSONObject): Msg {
        val ms = m.optJSONArray("mentions")
        val mentions = if (ms == null) emptyList() else (0 until ms.length()).map { clip(ms.opt(it), 60) }.filter { it.isNotEmpty() }
        val kind = m.opt("kind")?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() }?.let { clip(it, 20) }
        val mission = m.opt("mission")?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() && it != false }?.let { clip(it, 60) }
        return Msg(clip(m.opt("id"), 60), numL(m.opt("at")), clip(m.opt("from"), 60).ifEmpty { USER },
            (m.opt("text")?.takeIf { it != JSONObject.NULL }?.toString() ?: "").take(MAX_TEXT), mentions, mission, kind)
    }

    /** A thread in the v2 shape (unknown fields kept, messages kept to the last [MAX_MESSAGES]). */
    fun cleanThread(x: JSONObject?, now: Long = 0, id: String = ""): Thread {
        val src = x ?: JSONObject()
        val tid = clip(src.opt("id"), 60).ifEmpty { id }
        val kind = if (src.optString("kind") == "direct" || tid.startsWith(DIRECT)) "direct" else "group"
        val pa = src.opt("participants") as? JSONArray
        val ps = if (pa == null) emptyList() else (0 until pa.length()).map { clip(pa.opt(it), 60) }.filter { it.isNotEmpty() }
        val title: String
        val participants: List<String>
        if (kind == "direct") {
            val b = if (tid.startsWith(DIRECT)) tid.substring(DIRECT.length) else ps.firstOrNull { it != USER } ?: ATLAS
            participants = listOf(USER, b)
            title = clip(src.opt("title"), 80).ifEmpty { "Conversation" }
        } else {
            title = clip(src.opt("title"), 80).ifEmpty { "Group chat" }
            participants = (listOf(USER) + ps.filter { it != USER && it != ATLAS }.distinct()).take(MAX_PARTICIPANTS)
        }
        val ma = src.opt("messages") as? JSONArray
        val msgs = mutableListOf<Msg>()
        if (ma != null) for (i in 0 until ma.length()) {
            val m = ma.opt(i) as? JSONObject ?: continue
            val mid = m.opt("id"); if (mid == null || mid == JSONObject.NULL || mid.toString().isEmpty()) continue
            val txt = m.opt("text"); val k = m.opt("kind")
            val hasText = txt != null && txt != JSONObject.NULL && txt.toString().isNotEmpty()
            val hasKind = k != null && k != JSONObject.NULL && k.toString().isNotEmpty()
            if (!hasText && !hasKind) continue
            msgs.add(cleanMessage(m))
        }
        while (msgs.size > MAX_MESSAGES) msgs.removeAt(0)
        val extra = JSONObject()
        val known = setOf("id", "v", "kind", "title", "participants", "messages", "updatedAt", "links")
        src.keys().forEach { k -> if (k !in known) extra.put(k, src.opt(k)) }
        val upd = numL(src.opt("updatedAt")).takeIf { it != 0L } ?: now
        return Thread(tid, kind, title, participants, msgs, upd, extra)
    }

    /**
     * The bots @mentioned in [text], in the order they appear: "@" followed by a bot's name (any case; spaces allowed;
     * the longest name that fits wins) or its id. "@Atlas" is the chief of staff. Duplicates are dropped.
     */
    fun mentionsIn(text: String?, bots: List<Bot>): List<String> {
        val t = text ?: ""
        val names = mutableListOf<Pair<String, String>>()
        for (b in bots) { if (b.id.isEmpty()) continue; names.add(b.name.lowercase() to b.id); names.add(b.id.lowercase() to b.id) }
        names.add("atlas" to ATLAS)
        val list = names.filter { it.first.isNotEmpty() }.sortedByDescending { it.first.length }
        val out = mutableListOf<String>()
        val lower = t.lowercase()
        var i = 0
        while (true) {
            i = lower.indexOf('@', i); if (i < 0) break
            if (i > 0 && (lower[i - 1].isLetterOrDigit() || lower[i - 1] == '_' || lower[i - 1] == '.') && lower[i - 1].code < 128) { i++; continue }
            val rest = lower.substring(i + 1)
            val hit = list.firstOrNull { (k, _) -> rest.startsWith(k) && !(rest.getOrNull(k.length)?.let { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' } ?: false) }
            if (hit != null) { if (hit.second !in out) out.add(hit.second); i += 1 + hit.first.length } else i++
        }
        return out
    }

    /** The message with the @mentions taken out (what the bot is asked to do). */
    fun stripMentions(text: String?, bots: List<Bot>): String {
        var t = text ?: ""
        val keys = mutableListOf<String>()
        for (b in bots) { if (b.name.isNotEmpty()) keys.add(b.name); if (b.id.isNotEmpty()) keys.add(b.id) }
        keys.add("Atlas")
        keys.sortByDescending { it.length }
        for (k in keys) {
            val re = Regex("(^|[^\\w.])@" + Regex.escape(k) + "(?![\\w])[,:]?", RegexOption.IGNORE_CASE)
            t = re.replace(t) { it.groupValues[1] }
        }
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    fun botTurnsSinceUser(t: Thread?): Int {
        var n = 0
        val ms = t?.messages ?: return 0
        for (i in ms.indices.reversed()) { if (ms[i].from == USER) break; val k = ms[i].kind; if (k == null || k == "message" || k == "report") n++ }
        return n
    }

    fun pairHops(t: Thread?, a: String, b: String): Int {
        var n = 0
        val ms = t?.messages ?: return 0
        for (i in ms.indices.reversed()) {
            val m = ms[i]; if (m.from == USER) break
            if ((m.from == a && b in m.mentions) || (m.from == b && a in m.mentions)) n++
        }
        return n
    }

    /** What a new message should set going (see threadcore.js decide). [t] must already include [m] as its last message. */
    fun decide(t: Thread?, m: Msg, bots: List<Bot>): Decision {
        val known = bots.map { it.id }.toSet()
        val inThread = (t?.participants ?: emptyList()).filter { it != USER }.toSet()
        val mentions = if (m.mentions.isNotEmpty()) m.mentions else mentionsIn(m.text, bots)
        if (m.kind != null && m.kind != "message") return Decision(emptyList(), null, null)
        if (m.from == USER) {
            if (t != null && t.direct) return Decision(listOf(t.participants[1]), null, null)
            val picked = mentions.filter { it == ATLAS || it in known }
            if (picked.isNotEmpty()) return Decision(picked, null, null)
            val bs = inThread.filter { it in known }
            if (bs.size == 1) return Decision(bs, null, null)
            return Decision(listOf(ATLAS), null, if (bs.isNotEmpty()) "mention" else null)
        }
        val targets = mentions.filter { it != m.from && it != USER && (it == ATLAS || (it in known && it in inThread)) }
        if (targets.isEmpty()) return Decision(emptyList(), null, null)
        if (botTurnsSinceUser(t) >= MAX_BOT_TURNS) return Decision(emptyList(), "limit", null)
        val ok = targets.filter { pairHops(t, m.from, it) <= MAX_PAIR_HOPS }
        return Decision(ok, if (ok.size < targets.size) "limit" else null, null)
    }

    /** The conversation as text for a bot: newest last, kept under [max] characters. */
    fun transcript(t: Thread?, bots: List<Bot>, max: Int = 6000): String {
        fun nameOf(id: String) = when (id) { USER -> "You (the user)"; ATLAS -> "Atlas"; else -> bots.firstOrNull { it.id == id }?.name ?: id }
        val lines = (t?.messages ?: emptyList()).filter { it.kind != "approval" }
            .map { "${nameOf(it.from)}: ${it.text.replace(Regex("\\s+\\n"), "\n").trim()}" }
        var out = ""
        for (i in lines.indices.reversed()) {
            val next = lines[i] + (if (out.isNotEmpty()) "\n\n$out" else "")
            if (next.length > max) { out = "(earlier messages left out)\n\n$out"; break }
            out = next
        }
        return out
    }

    /** The goal a bot gets from a message (the words without the @mentions). */
    fun goalFrom(m: Msg, bots: List<Bot>, fromName: String?): String {
        val g = stripMentions(m.text, bots).ifEmpty { "Pick up from the conversation." }
        return if (m.from == USER) g else "${fromName ?: "Another bot"} handed this to you: $g"
    }
}
