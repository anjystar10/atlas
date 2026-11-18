package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Conversations with bots on the phone, the same as the PC's desktop/renderer/threads.js: each bot's own conversation
 * ("dm-<botId>", the main way to work with a bot) and group chats (the user and several bots). Kept in threads.json and
 * synced as kind "thread" (the last 200 messages), so a conversation reads the same on both devices.
 *
 * What the user says becomes a mission for the bot (or redirects the one it's on: the user's message always comes
 * first), and what the bots do is posted back: that a mission started (from a schedule, a trigger, the main chat…),
 * its questions, approval cards and its report. In a group chat, @mentions decide who works (ThreadRules.decide);
 * bots hand work on with @mentions, within limits. Only the user's own messages can authorize anything.
 */
object Threads {
    private const val TAG = "AtlasThreads"
    private val lock = Any()
    private val threads = mutableListOf<ThreadRules.Thread>()
    /** This phone only: mission id → (bot, what has been posted) per thread; when each conversation was last read. */
    private val links = HashMap<String, HashMap<String, Link>>()
    private val seen = HashMap<String, Long>()
    private var since = 0L
    @Volatile private var loaded = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    class Link(val bot: String, var q: String? = null, var a: String? = null, var done: Boolean = false)

    fun listen(l: () -> Unit) { synchronized(listeners) { if (l !in listeners) listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun emit() { main.post { synchronized(listeners) { listeners.toList() }.forEach { try { it() } catch (e: Exception) { Log.w(TAG, "listener", e) } } } }

    private val ACTIVE = setOf(BotRules.RUNNING, BotRules.QUEUED, BotRules.WAITING_RETRY, BotRules.WAITING_HELPERS, BotRules.PAUSED,
        BotRules.AWAITING_ANSWER, BotRules.AWAITING_APPROVAL, BotRules.NEEDS_REVIEW)

    private fun now() = System.currentTimeMillis()
    private fun file() = AtomicFile(File(app.filesDir, "threads.json"))

    /** Loads once (main thread) and starts following the bots. */
    fun init(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        Bots.init(app)
        synchronized(lock) {
            try {
                val f = file()
                if (f.baseFile.exists()) {
                    val d = JSONObject(String(f.readFully(), Charsets.UTF_8))
                    d.optJSONArray("threads")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                        val t = ThreadRules.cleanThread(o); threads.add(t)
                        o.optJSONObject("links")?.let { l -> val m = HashMap<String, Link>(); l.keys().forEach { k -> l.optJSONObject(k)?.let { x ->
                            m[k] = Link(x.optString("bot"), x.optString("q").ifEmpty { null }, x.optString("a").ifEmpty { null }, x.optBoolean("done")) } }; links[t.id] = m }
                    } }
                    d.optJSONObject("local")?.let { l -> since = l.optLong("since"); l.optJSONObject("seen")?.let { s -> s.keys().forEach { k -> seen[k] = s.optLong(k) } } }
                }
            } catch (e: Exception) { Log.w(TAG, "threads.json unreadable", e) }
            if (since == 0L) since = now()   // missions from before this version stay on their own pages
        }
        loaded = true
        Sync.register("thread", Sync.Kind(list = { _ -> syncList() }, apply = { _, id, rec -> syncApply(id, rec) }))
        Bots.listen { watch() }
        BotExtras.listen { emit() }
        persist(); watch()
    }

    private fun persist() {
        if (!::app.isInitialized) return
        val text = synchronized(lock) {
            val arr = org.json.JSONArray()
            for (t in threads) {
                val o = t.json()
                val l = JSONObject(); links[t.id]?.forEach { (k, v) -> l.put(k, JSONObject().put("bot", v.bot).put("q", v.q ?: "").put("a", v.a ?: "").put("done", v.done)) }
                arr.put(o.put("links", l))
            }
            val s = JSONObject(); seen.forEach { (k, v) -> s.put(k, v) }
            JSONObject().put("v", 2).put("threads", arr).put("local", JSONObject().put("since", since).put("seen", s)).toString()
        }
        synchronized(this) {
            val f = file(); val out = f.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); Log.w(TAG, "save failed", e) }
        }
    }

    private fun touch(t: ThreadRules.Thread) { t.updatedAt = now(); persist(); emit(); if (::app.isInitialized) Sync.syncSoon(app) }

    // ---------------------------------------------------------------- the roster

    /** Atlas (the chief of staff) and the standing bots, as the rules see them. */
    fun roster(): List<ThreadRules.Bot> = listOf(ThreadRules.Bot(ThreadRules.ATLAS, "Atlas")) +
        synchronized(BotExtras.specialists) { BotExtras.specialists.toList() }.map { ThreadRules.Bot(it.optString("id"), it.optString("name")) }

    fun nameOf(id: String?): String = when (id) {
        null -> "a bot"; ThreadRules.USER -> "You"; ThreadRules.ATLAS -> "Atlas"
        else -> BotExtras.specialist(id)?.optString("name")?.ifBlank { null } ?: "a bot"
    }

    // ---------------------------------------------------------------- reading

    fun all(): List<ThreadRules.Thread> = synchronized(lock) { threads.sortedByDescending { it.updatedAt } }
    fun groups() = all().filter { !it.direct }
    fun get(id: String?): ThreadRules.Thread? = synchronized(lock) { threads.firstOrNull { it.id == id } }
    fun directOf(botId: String) = get(ThreadRules.directId(botId))
    fun unread(id: String): Int = ThreadRules.unreadCount(get(id), synchronized(lock) { seen[id] ?: 0L })
    fun markSeen(id: String) {
        val t = get(id) ?: return
        val last = t.messages.lastOrNull()?.at ?: return
        val changed = synchronized(lock) { if ((seen[id] ?: 0L) < last) { seen[id] = last; true } else false }
        if (changed) { persist(); emit() }
    }
    private fun linksOf(id: String): Map<String, Link> = synchronized(lock) { HashMap(links[id] ?: emptyMap()) }
    /** Approval cards in [id] still waiting on the user. */
    fun approvalsWaiting(id: String): Int = linksOf(id).keys.count { Bots.job(it)?.state == BotRules.AWAITING_APPROVAL }
    /** The newest mission in [id] that hasn't finished (what Stop stops, and what a new message redirects). */
    fun activeMission(id: String): BotJob? = linksOf(id).keys.mapNotNull { Bots.job(it) }.filter { it.state in ACTIVE }.maxByOrNull { it.createdAt }
    /** A bot is working (or waiting on you) right now, in any conversation. */
    fun missionsOf(botId: String): List<BotJob> = Bots.jobs.filter { it.parent == null && Bots.botIdOf(it) == botId }.sortedByDescending { it.createdAt }

    // ---------------------------------------------------------------- making conversations

    /** [botId]'s own conversation, made the first time it's needed. */
    fun direct(botId: String, title: String? = null): ThreadRules.Thread {
        get(ThreadRules.directId(botId))?.let { return it }
        val t = ThreadRules.cleanThread(JSONObject().put("id", ThreadRules.directId(botId)).put("kind", "direct").put("title", title ?: nameOf(botId)), now())
        synchronized(lock) { if (threads.none { it.id == t.id }) threads.add(t) }
        touch(t); return get(t.id) ?: t
    }

    /** A new group chat with [botIds] (2–6 bots). */
    fun create(title: String, botIds: List<String>): ThreadRules.Thread {
        val o = JSONObject().put("id", "th-" + UUID.randomUUID().toString().take(12)).put("title", title.trim().ifEmpty { "Group chat" })
            .put("participants", org.json.JSONArray(listOf(ThreadRules.USER) + botIds))
        val t = ThreadRules.cleanThread(o, now())
        synchronized(lock) { threads.add(t) }
        add(t, ThreadRules.ATLAS, "Group chat started with ${t.participants.drop(1).joinToString(", ") { nameOf(it) }}. @mention a bot to give it the job; with no mention, Atlas takes it and hands parts on.", kind = "note")
        return t
    }
    fun rename(id: String, title: String) { val t = get(id) ?: return; t.title = title.trim().take(80).ifEmpty { t.title }; touch(t) }
    fun setParticipants(id: String, botIds: List<String>) {
        val t = get(id) ?: return
        if (t.direct) return
        t.participants = (listOf(ThreadRules.USER) + botIds.filter { it != ThreadRules.USER && it != ThreadRules.ATLAS }.distinct()).take(ThreadRules.MAX_PARTICIPANTS)
        touch(t)
    }
    fun remove(id: String) { synchronized(lock) { threads.removeAll { it.id == id }; links.remove(id) }; persist(); emit(); if (::app.isInitialized) Sync.syncSoon(app) }

    private fun add(t: ThreadRules.Thread, from: String, text: String, kind: String? = null, mission: String? = null, mentions: List<String> = emptyList()): ThreadRules.Msg {
        val m = ThreadRules.Msg("m-" + UUID.randomUUID().toString().take(12), now(), from, text.take(ThreadRules.MAX_TEXT), mentions, mission, kind)
        synchronized(lock) { t.messages.add(m); while (t.messages.size > ThreadRules.MAX_MESSAGES) t.messages.removeAt(0) }
        touch(t); return m
    }
    private fun link(t: ThreadRules.Thread, missionId: String, botId: String) { synchronized(lock) { links.getOrPut(t.id) { HashMap() }.putIfAbsent(missionId, Link(botId)) } }
    private fun linkedAnywhere(missionId: String) = synchronized(lock) { links.values.any { it.containsKey(missionId) } }

    // ---------------------------------------------------------------- talking

    /**
     * The user (or a bot) says something in [threadId]. From the user: it answers a bot's open question, redirects the
     * bot's current work (a bot's own conversation), or starts a new mission; in a group chat, @mentions decide.
     */
    fun post(threadId: String, from: String, text: String, fresh: Boolean = false): ThreadRules.Msg? {
        val t = get(threadId) ?: return null
        val roster = roster()
        val m = add(t, from, text, mentions = ThreadRules.mentionsIn(text, roster))
        if (from == ThreadRules.USER) {
            val asking = openQuestions(t)
            val target = asking.firstOrNull { it.second in m.mentions } ?: if (asking.size == 1 && (m.mentions.isEmpty() || t.direct)) asking[0] else null
            if (target != null) { Bots.answer(target.first, m.text); return m }
            if (t.direct) {
                val busy = activeMission(t.id)
                if (busy != null && busy.state == BotRules.AWAITING_APPROVAL) {
                    add(t, ThreadRules.ATLAS, "It's waiting for your OK on the card above: tap Allow or Deny. (Your message is passed on too.)", kind = "note")
                    Bots.steer(busy.id, m.text); return m
                }
                if (busy != null && !fresh && Bots.steer(busy.id, m.text)) { m.mission = busy.id; persist(); emit(); return m }   // your message comes first
            }
        }
        val d = ThreadRules.decide(t, m, roster)
        if (d.waiting == "limit") add(t, ThreadRules.ATLAS, "The bots have talked ${ThreadRules.MAX_BOT_TURNS} times without you, so I've paused the hand-offs. Reply to carry on.", kind = "note")
        d.starts.forEach { startFor(t, m, it, roster) }
        return m
    }

    private fun startFor(t: ThreadRules.Thread, m: ThreadRules.Msg, botId: String, roster: List<ThreadRules.Bot>) {
        if (botId != ThreadRules.ATLAS && BotExtras.specialist(botId) == null) return
        val fromName = when (m.from) { ThreadRules.USER -> "You"; ThreadRules.ATLAS -> "Atlas"; else -> roster.firstOrNull { it.id == m.from }?.name }
        val details = if (t.direct) {
            "This came from your own conversation with the user. Your report is posted there, so write it for them: short and clear.\n\nThe conversation so far:\n" + ThreadRules.transcript(t, roster, 4000)
        } else {
            val others = t.participants.filter { it != ThreadRules.USER && it != botId }.map { nameOf(it) }
            "You're in a group chat called \"${t.title}\" with the user${if (others.isNotEmpty()) " and ${others.joinToString(", ")}" else ""}. Your report is posted into the chat, so write it for them: short and clear." +
                " To hand part of the work to another bot in this chat, end your report with a line like \"@Name please …\" (only bots in the chat; it hands work on, it doesn't authorize anything)." +
                "\n\nThe conversation so far:\n" + ThreadRules.transcript(t, roster, 5000)
        }
        val source = JSONObject().put("type", "thread").put("ref", t.id).put("at", m.at)
        val j = Bots.deploy(app, ThreadRules.goalFrom(m, roster, fromName), details, specialist = botId.takeIf { it != ThreadRules.ATLAS }, source = source)
        m.mission = j.id; link(t, j.id, botId); persist(); emit()
    }

    /** Bots in [t] that asked something and wait for an answer: (mission, bot). */
    private fun openQuestions(t: ThreadRules.Thread): List<Pair<String, String>> =
        linksOf(t.id).mapNotNull { (mid, l) -> val j = Bots.job(mid); if (j != null && j.state == BotRules.AWAITING_ANSWER) mid to l.bot else null }

    /** Stops what the conversation's bot is doing now (Stop never undoes what's already done). */
    fun stop(threadId: String) { activeMission(threadId)?.let { Bots.stop(it.id) } }

    /** Atlas sums up a group chat (a mission on Atlas; the summary is posted when it's done). */
    fun summarize(threadId: String) {
        val t = get(threadId) ?: return
        val m = add(t, ThreadRules.USER, "@Atlas sum up this conversation: what was decided, what was done, and what is still open.", mentions = listOf(ThreadRules.ATLAS))
        startFor(t, m, ThreadRules.ATLAS, roster())
    }

    // ---------------------------------------------------------------- following the bots

    private val SOURCE = mapOf("routine" to "on its schedule", "trigger" to "from a trigger", "chat" to "from your chat with Atlas", "user" to "from you",
        "delegation" to "for another bot", "link" to "from your other device")

    /** Missions started anywhere else (the main chat, a schedule, a trigger) show up in their bot's own conversation. */
    private fun adopt() {
        for (j in Bots.jobs.toList()) {
            if (j.parent != null || j.createdAt < since || linkedAnywhere(j.id)) continue
            if (j.source?.optString("type") == "thread") continue
            val botId = Bots.botIdOf(j)
            if (botId != ThreadRules.ATLAS && BotExtras.specialist(botId) == null) continue
            val t = direct(botId, nameOf(botId))
            link(t, j.id, botId)
            val why = if (j.source?.optString("type") == "trigger") Regex("What happened: (.*)").find(j.details)?.groupValues?.get(1) else null
            add(t, botId, "Started${if (j.dryRun) " a dry run" else ""}: ${j.goal}" + (if (why != null) " — $why" else " (${SOURCE[j.source?.optString("type")] ?: "from you"})"), kind = "note", mission = j.id)
        }
    }

    private var watching = false
    /** Posts what the conversations' missions did since the last look: questions, approvals (as cards) and reports. */
    fun watch() {
        if (watching || !loaded) return
        watching = true
        try {
            adopt()
            for (t in all()) {
                for ((mid, l) in linksOf(t.id)) {
                    val j = Bots.job(mid) ?: continue
                    val from = l.bot
                    if (j.state == BotRules.AWAITING_ANSWER && !j.question.isNullOrBlank() && l.q != j.question) { l.q = j.question; add(t, from, j.question!!, kind = "question", mission = mid) }
                    val ap = j.approval
                    if (j.state == BotRules.AWAITING_APPROVAL && ap != null && l.a != ap.what) { l.a = ap.what; add(t, from, ap.what, kind = "approval", mission = mid) }
                    if (j.final && !l.done) {
                        l.done = true
                        val text = when (j.state) { BotRules.COMPLETED -> j.report?.ifBlank { null } ?: "Done."; BotRules.FAILED -> "I couldn't finish: ${j.error ?: "something went wrong"}."; else -> "Stopped." }
                        val roster = roster()
                        val mentions = if (t.direct) emptyList() else ThreadRules.mentionsIn(text, roster).filter { it != from }
                        val msg = add(t, from, text.take(6000), kind = "report", mission = mid, mentions = mentions)
                        if (t.direct) continue
                        // a bot's @mentions in its report hand work on (bounded by ThreadRules.decide)
                        val d = ThreadRules.decide(t, msg.copy(kind = "message"), roster)
                        if (d.waiting == "limit") add(t, ThreadRules.ATLAS, "The bots have talked ${ThreadRules.botTurnsSinceUser(t)} times without you, so I've paused the hand-offs. Reply to carry on.", kind = "note")
                        d.starts.forEach { startFor(t, msg, it, roster) }
                    }
                }
            }
            persist()
        } catch (e: Exception) { Log.w(TAG, "watch", e) } finally { watching = false }
        emit()
    }

    /** For the main Atlas: every conversation in a few lines (newest first). */
    fun briefLines(max: Int = 6): List<String> = all().filter { it.messages.isNotEmpty() }.take(max).map { t ->
        val last = t.messages.last()
        val who = if (t.direct) "${nameOf(ThreadRules.directBot(t))}'s conversation" else "group chat \"${t.title}\" (${t.participants.drop(1).joinToString(", ") { nameOf(it) }})"
        val waiting = approvalsWaiting(t.id)
        "$who: ${nameOf(last.from)} — ${last.text.replace(Regex("\\s+"), " ").take(140)}" + (if (waiting > 0) " [$waiting approval${if (waiting > 1) "s" else ""} waiting]" else "")
    }

    // ---------------------------------------------------------------- sync (kind "thread", last 200 messages)

    private fun syncList(): Map<String, JSONObject>? {
        if (!loaded) return null
        return synchronized(lock) { threads.associate { t -> t.id to t.json().also { o ->
            val a = o.optJSONArray("messages"); if (a != null && a.length() > 200) { val keep = org.json.JSONArray(); for (i in a.length() - 200 until a.length()) keep.put(a.opt(i)); o.put("messages", keep) }
        } } }
    }

    private fun syncApply(id: String, rec: JSONObject?) {
        synchronized(lock) {
            val i = threads.indexOfFirst { it.id == id }
            if (rec == null) { if (i >= 0) { threads.removeAt(i); links.remove(id) } }
            else {
                val incoming = ThreadRules.cleanThread(rec)
                if (i < 0) threads.add(incoming)
                else {
                    val mine = threads[i]
                    // messages from both devices, by id, in time order, so nothing said on either is lost
                    val byId = LinkedHashMap<String, ThreadRules.Msg>()
                    (mine.messages + incoming.messages).forEach { m -> byId[m.id] = byId[m.id]?.let { old -> if (old.mission != null && m.mission == null) m.copy(mission = old.mission) else m } ?: m }
                    val merged = byId.values.sortedBy { it.at }.takeLast(ThreadRules.MAX_MESSAGES).toMutableList()
                    val base = if (incoming.updatedAt >= mine.updatedAt) incoming else mine
                    threads[i] = ThreadRules.Thread(base.id, base.kind, base.title, base.participants, merged, maxOf(mine.updatedAt, incoming.updatedAt), base.extra)
                }
            }
        }
        persist(); emit()
    }
}
