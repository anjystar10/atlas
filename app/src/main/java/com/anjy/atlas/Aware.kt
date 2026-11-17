package com.anjy.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The main Atlas sees what's going on and can reach everything from the chat.
 *
 * - [Capabilities]: a registry of what Atlas can do beyond the basic tools. A new subsystem (email, projects, trading…)
 *   registers one [Capabilities.Cap]: its tool declarations, how to run them, and an optional line for the situation
 *   brief. It then shows up in the chat (and, if it says so, for bots) with no other wiring.
 * - [Situation]: a short, current brief put in front of every turn: bots and what they need, pending approvals, the
 *   other device, connections, recent files, AI services. Ids in it let Atlas fetch details with a tool.
 * - [ChatAsk]: actions Atlas started from the chat that need the user's OK; answered with answer_approval.
 */
object Capabilities {
    /**
     * [declarations] are function declarations (same shape as Tools.declarations). [run] returns null for a tool it
     * doesn't own. [brief] is one or two short lines for the situation brief (null: nothing to say right now).
     */
    class Cap(
        val id: String,
        val declarations: (Context) -> JSONArray,
        val run: suspend (Context, Gemini.Call) -> Tools.Result?,
        val brief: ((Context) -> String?)? = null,
        val forBots: Boolean = false,
        val forChat: Boolean = true,
    )

    private val caps = CopyOnWriteArrayList<Cap>()

    /** Adds (or replaces, by id) a capability. */
    fun register(c: Cap) { caps.removeAll { it.id == c.id }; caps.add(c) }

    init {
        // ---- built in: managing bots, answering approvals and quick web actions from the chat (AwareTools below)
        register(AwareTools.cap)
        register(Trading.cap)   // >>> trading: the trade_* tools in chat and for bots; LIVE orders ask (Trading.kt)
        // ---- new subsystems register here, one line each (e.g. register(Email.cap))
        register(Email.cap)   // >>> email (Email.kt)
        register(Messages.cap)   // >>> background first: messages from notifications, texts (Messages.kt)
        register(AttachTools.cap)   // >>> attachments: file_read / file_search over attached and Library files (AttachSend.kt)
    }

    fun all(): List<Cap> = caps.toList()

    private fun collect(ctx: Context, pick: (Cap) -> Boolean): JSONArray = JSONArray().apply {
        for (c in caps) if (pick(c)) try { val d = c.declarations(ctx); for (i in 0 until d.length()) put(d.get(i)) } catch (_: Exception) {}
    }
    fun chatDeclarations(ctx: Context) = collect(ctx) { it.forChat }
    fun botDeclarations(ctx: Context) = collect(ctx) { it.forBots }

    /** Every tool name a capability declares (for "does anyone own this tool?"). */
    fun names(ctx: Context, forBots: Boolean = false): Set<String> = collect(ctx) { if (forBots) it.forBots else it.forChat }.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.toSet()
    }

    /** Runs [c] with whichever capability owns it; null when none does. */
    suspend fun run(ctx: Context, c: Gemini.Call): Tools.Result? {
        for (cap in caps) {
            val d = try { cap.declarations(ctx) } catch (_: Exception) { continue }
            if ((0 until d.length()).any { d.optJSONObject(it)?.optString("name") == c.name }) return cap.run(ctx, c)
        }
        return null
    }
}

/** Something waiting for the user's yes or no (or an answer), wherever it came from. */
class Pending(val ref: String, val what: String, val from: String, val question: Boolean = false)

/** Actions the main Atlas wanted to take from the chat that need the user's OK first. */
object ChatAsk {
    class Ask(val ref: String, val what: String, val at: Long, val run: suspend (Context) -> String)
    private val asks = CopyOnWriteArrayList<Ask>()
    private var n = 0
    private const val TTL = 30 * 60_000L

    fun add(what: String, run: suspend (Context) -> String): String {
        prune()
        val ref = "chat${++n}"
        asks.add(Ask(ref, what, System.currentTimeMillis(), run))
        return ref
    }
    fun list(): List<Ask> { prune(); return asks.toList() }
    fun take(ref: String): Ask? = asks.firstOrNull { it.ref == ref }?.also { asks.remove(it) }
    private fun prune() { val now = System.currentTimeMillis(); asks.removeAll { now - it.at > TTL } }
}

object Situation {
    const val MAX_CHARS = 2400
    private const val SEEN = "aware_seen_reports"

    /** Everything waiting on the user: bots' approvals and questions, the laptop's approvals, the chat's own. */
    suspend fun pending(ctx: Context): List<Pending> {
        val out = mutableListOf<Pending>()
        // the bots' list lives on the main thread
        val jobs = withContext(Dispatchers.Main.immediate) { try { Bots.visible().let { v -> v + v.flatMap { Bots.helpers(it.id) } }.map { BotView(it) } } catch (_: Exception) { emptyList() } }
        try {
            for (j in jobs) {
                if (j.state == BotRules.AWAITING_APPROVAL) j.approval?.let { out.add(Pending("bot:${j.id}", it.what, "the bot “${j.goal.take(60)}”")) }
                if (j.state == BotRules.AWAITING_ANSWER) j.question?.let { out.add(Pending("bot:${j.id}", it, "the bot “${j.goal.take(60)}”", question = true)) }
            }
        } catch (_: Exception) {}
        try {
            if (Sync.linked(ctx)) { Link.init(ctx); val o = LinkTasks.outbox()
                for (tid in o.keys().asSequence().toList()) { val t = o.optJSONObject(tid) ?: continue
                    if (t.optString("state") == "awaiting_approval") out.add(Pending("laptop:$tid", t.optString("approval"), "your laptop (doing “${t.optString("title").ifEmpty { t.optString("text") }.take(60)}”)"))
                } }
        } catch (_: Exception) {}
        ChatAsk.list().forEach { out.add(Pending(it.ref, it.what, "you, in this chat")) }
        return out
    }

    /** The brief that goes in front of the main Atlas each turn. Kept under [MAX_CHARS]. */
    /** A copy of what the brief needs from a job, taken on the main thread. */
    class BotView(j: BotJob) {
        val id = j.id; val goal = j.goal; val state = j.state; val final = j.final; val updatedAt = j.updatedAt
        val approval = j.approval; val question = j.question; val current = j.current; val report = j.report; val error = j.error
        val planDone = j.plan.count { it.done }; val planSize = j.plan.size
    }

    suspend fun brief(ctx: Context, markSeen: Boolean = true): String {
        val parts = mutableListOf<String>()
        fun add(title: String, lines: List<String>) { if (lines.isNotEmpty()) parts.add("$title:\n" + lines.joinToString("\n") { "- $it" }) }
        val now = System.currentTimeMillis()

        // waiting on the user: first, because it's what they most need to hear about
        add("Waiting for the user (answer with answer_approval; if they just say \"yes\"/\"go ahead\"/\"no\" and one thing is waiting, that's the one)",
            pending(ctx).take(6).map { "[${it.ref}] ${if (it.question) "question" else "needs OK"} from ${it.from}: ${it.what.take(140)}" })

        // bots
        try {
            val jobs = withContext(Dispatchers.Main.immediate) { Bots.visible().map { BotView(it) } }
            val live = jobs.filter { !it.final }.take(6).map { j ->
                val plan = if (j.planSize > 0) " · plan ${j.planDone}/${j.planSize}" else ""
                "[${j.id.take(8)}] “${j.goal.take(70)}” — ${BotRules.label(j.state)}${j.current?.let { if (j.state == BotRules.RUNNING) ": ${it.take(60)}" else "" } ?: ""}$plan"
            }
            add("Bots working (manage with the bots tool)", live)
            val seen = Store.getString(ctx, SEEN)?.split(',')?.toMutableSet() ?: mutableSetOf()
            val done = jobs.filter { it.final && now - it.updatedAt < 24 * 3_600_000L }.take(4)
            add("Bots finished in the last day", done.map { j ->
                val first = (j.report ?: j.error ?: "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.take(110) ?: ""
                val fresh = j.id !in seen
                "[${j.id.take(8)}] “${j.goal.take(60)}” — ${BotRules.label(j.state)}${if (first.isNotEmpty()) ": $first" else ""}${if (fresh) " (NEW: mention it briefly if it fits)" else ""}"
            })
            if (markSeen && done.isNotEmpty()) { seen.addAll(done.map { it.id }); Store.putString(ctx, SEEN, seen.toList().takeLast(60).joinToString(",")) }
        } catch (_: Exception) {}

        // the other device
        try {
            if (Sync.linked(ctx)) {
                Link.init(ctx)
                val lap = Link.laptop()
                val tasks = LinkTasks.outbox().let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { it.optString("state") != "denied" }.toList() }
                add("Your laptop", listOfNotNull(
                    lap?.let { "${it.name}: ${if (it.online) "online" else "offline, last seen ${Link.ago(it.agoMs)}"} (use on_laptop to do things there)" } ?: "linked, but it hasn't checked in yet",
                    if (tasks.isNotEmpty()) "jobs sent there: " + tasks.take(3).joinToString("; ") { "“${it.optString("title").ifEmpty { it.optString("text") }.take(50)}” ${it.optString("state")}" } else null,
                ))
            }
        } catch (_: Exception) {}

        // connections, recent files, AI services
        try {
            val sv = Connections.servers(ctx); val api = Connections.apis(ctx)
            add("Connections", sv.map { "${it.name}: ${it.status}${if (it.status == "connected") ", ${it.tools.size} tools" else ""}" } + api.map { "${it.name} (web API)" })
        } catch (_: Exception) {}
        try {
            add("Recent files bots made (Library)", BotFiles.library(ctx).take(4).map { "${it.title.take(60)} (${it.kind}, ${Link.ago(now - it.at)})" })
        } catch (_: Exception) {}
        try {
            val ready = Providers.all.filter { Providers.readyKey(ctx, it.id) != null }.map { it.name }
            add("AI services", listOf(if (ready.isEmpty()) "no key ready right now (offline brain only)" else "ready: ${ready.joinToString(", ")}" +
                if (KeyRouter.dontSwitch(ctx)) " (Don't Switch is on)" else ""))
        } catch (_: Exception) {}

        // whatever else has registered
        val extra = Capabilities.all().mapNotNull { c -> try { c.brief?.invoke(ctx)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null } }
        add("Also", extra)

        return cap(if (parts.isEmpty()) "Nothing running, nothing waiting." else parts.joinToString("\n"))
    }

    /** Keeps the brief short: cut at a line end. */
    fun cap(s: String, max: Int = MAX_CHARS): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf('\n', max - 2).takeIf { it > max / 2 } ?: (max - 2)
        return s.take(cut) + "\n…"
    }

    /** Which pending item a short "yes"/"no" answers: the one named, or the only one; null when it's unclear. */
    fun pick(all: List<Pending>, ref: String?): Pending? {
        val r = ref?.trim().orEmpty()
        if (r.isNotEmpty()) return all.firstOrNull { it.ref == r } ?: all.firstOrNull { it.ref.substringAfter(':').startsWith(r.substringAfter(':')) && r.substringAfter(':').length >= 4 }
        return all.singleOrNull()
    }
}

/** The chat's own tools for the bots, approvals and quick web actions. */
object AwareTools {
    private fun fn(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) = JSONObject().put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
    private fun str(d: String, e: List<String>? = null) = JSONObject().put("type", "string").put("description", d).apply { if (e != null) put("enum", JSONArray(e)) }
    private fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)

    private val decls: JSONArray by lazy { JSONArray().apply {
        put(fn("bots", "See and manage Atlas Bot jobs from the chat: list them, check one, tell it something (steer), make it wrap up, pause, resume, stop, read its report, or answer its question.",
            JSONObject().put("action", str("What to do", listOf("list", "status", "steer", "wrap_up", "pause", "resume", "stop", "report", "answer")))
                .put("id", str("The job's id from the brief (the first characters are enough). Leave empty when only one bot fits."))
                .put("text", str("For steer: what to tell the bot. For answer: the answer to its question.")), listOf("action")))
        put(fn("answer_approval", "Give the user's yes or no to something waiting for their OK (a bot's action, the laptop's action, or one of yours), or their answer to a bot's question. Only when the user actually said so.",
            JSONObject().put("ref", str("The [ref] from the brief. May be empty if exactly one thing is waiting."))
                .put("yes", bool("true: go ahead. false: don't."))
                .put("always", bool("Also allow this kind of action from now on (only if the user said so, e.g. 'always allow that')."))
                .put("answer", str("For a bot's question: the user's answer.")), listOf("yes")))
        put(fn("web", "Do something on a website yourself, right now, in Atlas's own browser (quick jobs of a few steps: look something up on a site, fill a short form, check a page). For long jobs send a bot instead. Each action returns the page as text with numbered links, buttons and fields. Risky clicks or submits (send, buy, post, delete…) wait for the user's OK.",
            JSONObject().put("action", str("open (needs url), read, click (target), type (target, text, submit), select (target, option), press (key), scroll, back", listOf("open", "read", "click", "type", "select", "press", "scroll", "back")))
                .put("url", str("For open: the web address")).put("target", str("A number from the latest page list, or the visible text of the element"))
                .put("text", str("For type: what to type")).put("submit", bool("For type: press Enter afterwards"))
                .put("option", str("For select: the option to choose")).put("key", str("For press: Enter, Tab, Escape, Backspace, Up, Down…"))
                .put("direction", str("For scroll: up or down", listOf("up", "down"))), listOf("action")))
    } }

    val cap = Capabilities.Cap("aware", { decls }, { ctx, c -> run(ctx, c) })

    const val CHAT_JOB = "chat"

    private fun findJob(id: String?, pickLive: Boolean = true): BotJob? {
        val all = Bots.visible()
        val i = id?.trim().orEmpty()
        if (i.isNotEmpty()) return all.firstOrNull { it.id == i } ?: all.firstOrNull { it.id.startsWith(i) }
            ?: all.firstOrNull { it.goal.contains(i, ignoreCase = true) }
        val live = all.filter { !it.final }
        return if (pickLive && live.size == 1) live.first() else if (all.size == 1) all.first() else null
    }

    suspend fun run(ctx: Context, c: Gemini.Call): Tools.Result? {
        val a = c.args
        return when (c.name) {
            "bots" -> withContext(Dispatchers.Main) { bots(ctx, a) }
            "answer_approval" -> withContext(Dispatchers.Main) { approve(ctx, a) }
            "web" -> web(ctx, a)
            else -> null
        }
    }

    private fun bots(ctx: Context, a: JSONObject): Tools.Result {
        Bots.init(ctx)
        val action = a.optString("action")
        if (action == "list") {
            val all = Bots.visible()
            if (all.isEmpty()) return Tools.Result("No bots right now.", "Checked your bots")
            return Tools.Result(all.take(12).joinToString("\n") { j -> "[${j.id.take(8)}] “${j.goal.take(80)}” — ${BotRules.label(j.state)}${j.current?.let { if (j.state == BotRules.RUNNING) ": $it" else "" } ?: ""}" }, "Checked your bots")
        }
        val j = findJob(a.optString("id")) ?: return Tools.Result("Error: which bot? ${Bots.visible().take(6).joinToString("; ") { "[${it.id.take(8)}] ${it.goal.take(50)}" }.ifEmpty { "There are no bots." }}")
        val name = "“${j.goal.take(60)}”"
        return when (action) {
            "status" -> Tools.Result(buildString {
                append("$name — ${BotRules.label(j.state)}\n")
                if (j.plan.isNotEmpty()) append("Plan:\n" + j.plan.joinToString("\n") { "${if (it.done) "✓" else "·"} ${it.text}" } + "\n")
                if (j.steps.isNotEmpty()) append("Latest steps: " + j.steps.takeLast(5).joinToString("; ") + "\n")
                j.approval?.takeIf { j.state == BotRules.AWAITING_APPROVAL }?.let { append("Waiting for OK [bot:${j.id}]: ${it.what}\n") }
                j.question?.takeIf { j.state == BotRules.AWAITING_ANSWER }?.let { append("Asking [bot:${j.id}]: $it\n") }
                j.error?.let { append("Problem: $it\n") }
            }.trim(), "Checked $name")
            "report" -> Tools.Result(j.report?.take(6000) ?: "No report yet (${BotRules.label(j.state)}).", "Read the report of $name")
            "steer" -> { val t = a.optString("text").trim(); if (t.isEmpty()) Tools.Result("Error: what should I tell the bot?") else if (Bots.steer(j.id, t)) Tools.Result("Told the bot. It reads it at its next step.", "Told $name: ${t.take(60)}") else Tools.Result("Error: that bot has finished; send a new one instead.") }
            "wrap_up" -> { Bots.wrapUp(j.id); Tools.Result("Asked the bot to wrap up with what it has.", "Asked $name to wrap up") }
            "pause" -> { Bots.pause(j.id); Tools.Result("Paused.", "Paused $name") }
            "resume" -> { Bots.resume(j.id); Tools.Result("Resumed.", "Resumed $name") }
            "stop" -> { Bots.stop(j.id); Tools.Result("Stopped.", "Stopped $name") }
            "answer" -> { val t = a.optString("text").trim(); if (j.state != BotRules.AWAITING_ANSWER || t.isEmpty()) Tools.Result("Error: that bot isn't waiting for an answer.") else { Bots.answer(j.id, t); Tools.Result("Answered the bot.", "Answered $name") } }
            else -> Tools.Result("Error: unknown action $action.")
        }
    }

    private suspend fun approve(ctx: Context, a: JSONObject): Tools.Result {
        Bots.init(ctx)
        val all = Situation.pending(ctx)
        if (all.isEmpty()) return Tools.Result("Nothing is waiting for the user's OK right now.")
        val p = Situation.pick(all, a.optString("ref").ifBlank { null })
            ?: return Tools.Result("Error: several things are waiting; ask which one: " + all.joinToString("; ") { "[${it.ref}] ${it.what.take(60)} (from ${it.from})" })
        val yes = a.optBoolean("yes", false)
        val kind = p.ref.substringBefore(':'); val id = p.ref.substringAfter(':')
        return when {
            kind == "bot" && p.question -> { val t = a.optString("answer").trim(); if (t.isEmpty()) Tools.Result("Error: the bot asked “${p.what}”; give the user's answer in answer.") else { Bots.answer(id, t); Tools.Result("Gave the bot the answer.", "Answered the bot") } }
            kind == "bot" -> { Bots.approve(id, yes, always = yes && a.optBoolean("always", false)); Tools.Result(if (yes) "Allowed; the bot carries on." else "Denied; the bot will find another way or stop.", if (yes) "Allowed: ${p.what.take(60)}" else "Denied: ${p.what.take(60)}") }
            kind == "laptop" -> { withContext(Dispatchers.IO) { LinkTasks.answer(id, yes) }; Tools.Result(if (yes) "Allowed on the laptop." else "Denied on the laptop.", if (yes) "Allowed on your laptop: ${p.what.take(50)}" else "Denied on your laptop") }
            else -> { val ask = ChatAsk.take(p.ref) ?: return Tools.Result("Error: that request has expired; do it again.")
                if (!yes) Tools.Result("OK, not doing it.", "Didn't: ${ask.what.take(60)}") else Tools.Result(ask.run(ctx), "Did: ${ask.what.take(60)}") }
        }
    }

    // ---------------------------------------------------------------- the chat's own browser (the bot browser, as job "chat")

    private val toolFor = mapOf("open" to "browser_open", "read" to "browser_read", "click" to "browser_click", "type" to "browser_type",
        "select" to "browser_select", "press" to "browser_press", "scroll" to "browser_scroll", "back" to "browser_back")

    private suspend fun web(ctx: Context, a: JSONObject): Tools.Result {
        val tool = toolFor[a.optString("action")] ?: return Tools.Result("Error: unknown web action.")
        val args = JSONObject(a.toString()).apply { remove("action") }
        return withContext(Dispatchers.Main) {
            val (label, inForm) = BotBrowser.inspect(ctx, CHAT_JOB, tool, args)
            val what = BrowserRules.approval(tool, args, label, inForm, BotBrowser.site())
            if (what != null) {
                BotExtras.init(ctx)
                val cat = BotExtras.Rules.categoryOf(tool, args, what)
                when (BotExtras.mode(cat)) {
                    "deny" -> return@withContext Tools.Result("Error: the user's standing choice is never to allow this kind of action (${what.take(80)}). Tell them; they can change it in Me → Bot Permissions.")
                    "allow" -> {}
                    else -> {
                        val ref = ChatAsk.add(what) { c2 -> withContext(Dispatchers.Main) {
                            // the page must still show what they agreed to
                            if (label != null && BotBrowser.inspect(c2, CHAT_JOB, tool, args).first != label) "The page changed, so I didn't do it. Read it again and ask again."
                            else BotBrowser.run(c2, CHAT_JOB, tool, args).text } }
                        return@withContext Tools.Result("Needs the user's OK first [$ref]: $what. Ask them in one short line; when they say yes, call answer_approval with ref $ref.", "Waiting for your OK: ${what.take(60)}")
                    }
                }
            }
            val out = BotBrowser.run(ctx, CHAT_JOB, tool, args)
            Tools.Result(out.text + (out.waitForUser?.let { "\n\n(The user must do this themselves: $it They can open the bot's browser from the Bots tab.)" } ?: ""), out.step)
        }
    }
}
