package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject

/**
 * Atlas Bot's journal: every job Atlas has sent a bot to do, saved so it survives Atlas being closed.
 * The rules here have no Android in them (they're unit-tested on their own); [Bots] runs the jobs.
 * States and safety rules follow docs/AGENT_RUNTIME.md.
 */
object BotRules {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_RETRY = "waiting_retry"
    const val AWAITING_APPROVAL = "awaiting_approval"
    const val AWAITING_ANSWER = "awaiting_answer"
    const val PAUSED = "paused"
    const val NEEDS_REVIEW = "needs_review"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val ALL = listOf(QUEUED, RUNNING, WAITING_RETRY, AWAITING_APPROVAL, AWAITING_ANSWER, PAUSED, NEEDS_REVIEW, COMPLETED, FAILED, CANCELLED)
    val FINAL = setOf(COMPLETED, FAILED, CANCELLED)
    /** Waiting on the user: the bot can't go on until they answer. */
    val WAITING_ON_USER = setOf(AWAITING_APPROVAL, AWAITING_ANSWER, NEEDS_REVIEW)

    /** On the phone: after this many steps without finishing, the bot suggests carrying on from the laptop. */
    const val BIG_JOB_STEPS = 40
    const val LAPTOP_HINT = "This is a big one: open Atlas on your laptop and I can do it there."

    /** How many bots work at once. */
    const val MAX_RUNNING = 2

    private val moves: Map<String, Set<String>> = mapOf(
        QUEUED to setOf(RUNNING, PAUSED, CANCELLED),
        RUNNING to setOf(QUEUED, WAITING_RETRY, AWAITING_APPROVAL, AWAITING_ANSWER, PAUSED, NEEDS_REVIEW, COMPLETED, FAILED, CANCELLED),
        WAITING_RETRY to setOf(QUEUED, RUNNING, PAUSED, FAILED, CANCELLED),
        AWAITING_APPROVAL to setOf(QUEUED, PAUSED, CANCELLED),
        AWAITING_ANSWER to setOf(QUEUED, PAUSED, CANCELLED),
        PAUSED to setOf(QUEUED, CANCELLED),
        NEEDS_REVIEW to setOf(QUEUED, CANCELLED),
    )

    fun canMove(from: String, to: String): Boolean = moves[from]?.contains(to) == true

    /**
     * After Atlas was closed or crashed: a job that was running goes back in the queue, unless it was in the middle
     * of an action, whose outcome is now unknown: that one waits for the user to check (never replayed on its own).
     */
    fun recover(state: String, actionPending: Boolean): String = when (state) {
        RUNNING -> if (actionPending) NEEDS_REVIEW else QUEUED
        else -> state
    }

    /** Which queued jobs to start now (oldest first), keeping at most [MAX_RUNNING] at work. */
    fun toStart(jobs: List<BotJob>, now: Long, max: Int = MAX_RUNNING): List<BotJob> {
        val busy = jobs.count { it.state == RUNNING }
        val room = (max - busy).coerceAtLeast(0)
        val ready = jobs.filter { it.state == QUEUED || (it.state == WAITING_RETRY && it.retryAt <= now) }.sortedBy { it.createdAt }
        return ready.take(room)
    }

    /** How long to wait before trying again after the [n]th failure in a row: 1, 2, 4, 8, 16 minutes. */
    fun retryDelay(n: Int): Long = 60_000L shl (n - 1).coerceIn(0, 4)
    const val MAX_RETRIES = 5

    /** In plain words, for the screen. */
    fun label(state: String): String = when (state) {
        QUEUED -> "Waiting to start"
        RUNNING -> "Working"
        WAITING_RETRY -> "Waiting to try again"
        AWAITING_APPROVAL -> "Needs your OK"
        AWAITING_ANSWER -> "Has a question"
        PAUSED -> "Paused"
        NEEDS_REVIEW -> "Needs a check"
        COMPLETED -> "Done"
        FAILED -> "Couldn't finish"
        CANCELLED -> "Stopped"
        else -> state
    }

    /** "1 working · 1 done" for the strip on the Atlas tab (done counts only recent ones). */
    fun summary(jobs: List<BotJob>, now: Long): String {
        val working = jobs.count { it.state == RUNNING || it.state == QUEUED || it.state == WAITING_RETRY }
        val waiting = jobs.count { it.state in WAITING_ON_USER }
        val paused = jobs.count { it.state == PAUSED }
        val done = jobs.count { it.state == COMPLETED && now - it.updatedAt < 24 * 3_600_000L }
        return listOfNotNull(
            if (working > 0) "$working working" else null,
            if (waiting > 0) "$waiting need${if (waiting == 1) "s" else ""} you" else null,
            if (paused > 0) "$paused paused" else null,
            if (done > 0) "$done done" else null,
        ).joinToString(" · ")
    }

    // ---- what needs the user's OK first

    private val riskyWords = Regex("\\b(send|post|publish|share|tweet|reply|buy|purchase|pay|payment|checkout|check out|order|place order|book|confirm|delete|remove|erase|transfer|subscribe|unsubscribe|submit|call|dial|sign out|log out|uninstall|reset|accept|decline|block|report)\\b", RegexOption.IGNORE_CASE)
    private val riskySchemes = listOf("tel:", "sms:", "smsto:", "mms:", "mailto:")

    /**
     * Phone tools that need the user's OK before a bot may run them, as a sentence saying what would happen; null when
     * it can go ahead. [elementLabel] is what the screen element [args] points at says (for tap and long_press).
     */
    fun phoneApproval(tool: String, args: JSONObject, elementLabel: String?): String? = when (tool) {
        "forget" -> "Delete a saved memory"
        "update_reminder" -> when (args.optString("action")) {
            "delete" -> "Delete a reminder from your Log"
            "cancel" -> "Cancel a reminder"
            else -> null
        }
        "open_link" -> args.optString("url").trim().lowercase().let { u -> if (riskySchemes.any { u.startsWith(it) }) "Open ${args.optString("url").trim().take(80)} (starts a call, message or email)" else null }
        "tap", "long_press" -> elementLabel?.takeIf { riskyWords.containsMatchIn(it) }?.let { "${if (tool == "tap") "Tap" else "Press and hold"} “${it.take(60)}”" }
        "type_text" -> if (args.optBoolean("press_enter", false)) "Type “${args.optString("text").take(60)}” and press send/enter" else null
        // calendar changes and the like (PhoneData.kt)
        else -> PhoneData.approval(tool, args)
    }

    private val readOnlyCommand = Regex("^\\s*(get-[a-z]+|test-path|test-connection|select-string|measure-object|resolve-path|where\\.exe|where|dir|ls|cat|type|echo|hostname|whoami|ipconfig|systeminfo|tasklist|ping)\\b[^;|&>]*$", RegexOption.IGNORE_CASE)

    /** The same for the PC's tools (kept here so both apps follow one rule; the PC's copy is in bots.js). */
    fun pcApproval(tool: String, args: JSONObject): String? = when (tool) {
        "run_command" -> args.optString("command").let { c -> if (readOnlyCommand.matches(c)) null else "Run this command on your PC: ${c.take(120)}" }
        "write_file" -> "Write the file ${args.optString("path").take(100)}"
        else -> null
    }
}

class PlanItem(val text: String, var done: Boolean = false)

/** An action the bot wants to take that needs the user's OK. [tool] is null when the bot asked for approval itself. */
class Approval(val what: String, val tool: String?, val args: JSONObject?, val elementLabel: String?, var decision: String? = null)

/**
 * One job. Plain JSON with no secrets (never a key), an id, updated_ms and the device that owns it, so it can later
 * sync between devices like the Log does.
 */
class BotJob(
    val id: String,
    val goal: String,
    val details: String,
    val createdAt: Long,
    /** The device running it (Store's deviceId). */
    var device: String = "",
) {
    var state: String = BotRules.QUEUED
    var updatedAt: Long = createdAt
    /** Every change of state, with its time. */
    val history = mutableListOf<Pair<Long, String>>()
    val plan = mutableListOf<PlanItem>()
    /** The bot's own notes: what it found and what's left (kept across steps, so older tool results can be dropped). */
    val notes = mutableListOf<String>()
    /** What it did, in plain words, for the screen. */
    val steps = mutableListOf<String>()
    /** Pages it read (for the Sources list). */
    val sources = mutableListOf<String>()
    /** The latest tool results, cut short: what the next round picks up from. */
    val recent = mutableListOf<String>()
    var toolCalls = 0
    /** Time spent working, across restarts. */
    var workMs = 0L
    var provider: String? = null
    /** Set just before a tool runs and cleared after: if Atlas dies in between, the outcome is unknown. */
    var pending: String? = null
    var approval: Approval? = null
    var question: String? = null
    var answer: String? = null
    var report: String? = null
    var error: String? = null
    var retryAt: Long = 0
    var retries = 0
    /** What it's doing right now, in a few words. */
    var current: String? = null
    var posted = false
    /** Why this job would be better done on the laptop, once the bot has said so (null: it hasn't). */
    var suggestPc: String? = null

    val final get() = state in BotRules.FINAL

    /** Moves to [to] if the rules allow it; returns whether it moved. */
    fun move(to: String, now: Long): Boolean {
        if (state == to) return true
        if (!BotRules.canMove(state, to)) return false
        state = to; updatedAt = now; history.add(now to to)
        if (history.size > 60) history.removeAt(0)
        return true
    }

    fun step(s: String) { steps.add(s); if (steps.size > 200) steps.removeAt(0) }
    fun addRecent(s: String) { recent.add(s); while (recent.size > 3) recent.removeAt(0) }

    fun json(): JSONObject = JSONObject().put("id", id).put("goal", goal).put("details", details).put("createdAt", createdAt)
        .put("state", state).put("updatedAt", updatedAt)
        .put("history", JSONArray().apply { history.forEach { (t, s) -> put(JSONObject().put("at", t).put("state", s)) } })
        .put("plan", JSONArray().apply { plan.forEach { put(JSONObject().put("text", it.text).put("done", it.done)) } })
        .put("notes", JSONArray(notes)).put("steps", JSONArray(steps)).put("sources", JSONArray(sources)).put("recent", JSONArray(recent))
        .put("toolCalls", toolCalls).put("workMs", workMs).put("provider", provider ?: JSONObject.NULL).put("pending", pending ?: JSONObject.NULL)
        .put("approval", approval?.let { a -> JSONObject().put("what", a.what).put("tool", a.tool ?: JSONObject.NULL).put("args", a.args ?: JSONObject.NULL)
            .put("elementLabel", a.elementLabel ?: JSONObject.NULL).put("decision", a.decision ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("question", question ?: JSONObject.NULL).put("answer", answer ?: JSONObject.NULL).put("report", report ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL).put("retryAt", retryAt).put("retries", retries).put("current", current ?: JSONObject.NULL).put("posted", posted)
        .put("device", device).put("updated_ms", updatedAt).put("suggestPc", suggestPc ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { has(k) }
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

        fun from(o: JSONObject): BotJob = BotJob(o.getString("id"), o.optString("goal"), o.optString("details"), o.optLong("createdAt"), o.optString("device")).apply {
            state = o.optString("state", BotRules.QUEUED).takeIf { it in BotRules.ALL } ?: BotRules.FAILED
            updatedAt = o.optLong("updatedAt", createdAt)
            o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { history.add(it.optLong("at") to it.optString("state")) } }
            o.optJSONArray("plan")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { plan.add(PlanItem(it.optString("text"), it.optBoolean("done"))) } }
            notes.addAll(o.optJSONArray("notes").strings()); steps.addAll(o.optJSONArray("steps").strings())
            sources.addAll(o.optJSONArray("sources").strings()); recent.addAll(o.optJSONArray("recent").strings())
            toolCalls = o.optInt("toolCalls"); workMs = o.optLong("workMs"); provider = o.str("provider"); pending = o.str("pending")
            approval = o.optJSONObject("approval")?.let { a -> Approval(a.optString("what"), a.str("tool"), a.optJSONObject("args"), a.str("elementLabel"), a.str("decision")) }
            question = o.str("question"); answer = o.str("answer"); report = o.str("report"); error = o.str("error")
            retryAt = o.optLong("retryAt"); retries = o.optInt("retries"); current = o.str("current"); posted = o.optBoolean("posted")
            suggestPc = o.str("suggestPc")
        }

        fun listJson(jobs: List<BotJob>): String = JSONArray().apply { jobs.forEach { put(it.json()) } }.toString()
        fun parseList(text: String): MutableList<BotJob> {
            val a = JSONArray(text)
            val out = mutableListOf<BotJob>()
            for (i in 0 until a.length()) try { out.add(from(a.getJSONObject(i))) } catch (_: Exception) {}
            return out
        }
    }
}
