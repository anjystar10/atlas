package com.anjy.atlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.anjy.atlas.core.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Atlas Bot: an agent Atlas sends off to do a bigger job (research with sources, comparing options, a long piece of
 * writing, a chain of phone actions) while the chat stays free. It plans, works step by step with tools, keeps notes,
 * checks its work and writes a report, which lands in the chat when it's done.
 *
 * It runs on whichever AI service the key pool gives it (through [KeyRouter], like the chat), in the background (with
 * [BotService] keeping Atlas alive), and every job is saved in a journal ([BotJob], bots.json) so it survives Atlas
 * being closed. Anything consequential waits for the user's OK. All state is read and changed on the main thread.
 */
object Bots {
    private const val TAG = "AtlasBot"
    /** The most tool steps one job may take, and the most working time. */
    const val MAX_TOOL_CALLS = BotRules.MAX_TOOL_CALLS
    const val MAX_WORK_MS = BotRules.MAX_WORK_MS
    /** After this many rounds the conversation with the model starts afresh from the journal (keeps it small). */
    private const val ROUNDS_PER_PART = 10
    /** Finished jobs kept in the journal. */
    private const val KEEP_FINISHED = 30

    const val CH_BOT = "atlas_bot"
    const val CH_BOT_ALERT = "atlas_bot_alert"

    val jobs = mutableListOf<BotJob>()
    private var loaded = false
    private lateinit var app: Context
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val workers = mutableMapOf<String, Job>()
    private val main = Handler(Looper.getMainLooper())
    /** For helpers (the bot browser's downloads) that need Atlas Bot's own background work. */
    fun scopeLaunch(block: suspend () -> Unit) = scope.launch { block() }

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() {
        listeners.toList().forEach { try { it() } catch (e: Exception) { Log.w(TAG, "listener", e) } }
        if (::app.isInitialized) BotService.sync(app)
    }

    // >>> sync: the other device's jobs (BotMirror)
    /** The journal is loaded (sync can show this device's jobs on the other one). */
    val ready get() = loaded
    /** A snapshot of the jobs, read on the main thread where they change (null: not ready, or busy). */
    fun jobsForSync(): List<BotJob>? {
        if (!loaded) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return visible()
        val latch = java.util.concurrent.CountDownLatch(1); var out: List<BotJob>? = null
        main.post { try { out = visible().toList() } finally { latch.countDown() } }
        return if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) out else null
    }
    /** The other device's jobs changed: the screens showing jobs refresh. */
    fun mirrorChanged() { main.post { listeners.toList().forEach { try { it() } catch (e: Exception) { Log.w(TAG, "listener", e) } } } }
    // <<< sync

    fun job(id: String?) = jobs.firstOrNull { it.id == id }
    /** True while any job is working or about to (the background service stays up for these). */
    fun active() = jobs.any { it.state == BotRules.RUNNING || it.state == BotRules.QUEUED || it.state == BotRules.WAITING_RETRY }
    // helpers show inside their bot's page, not in the list
    fun visible() = jobs.filter { it.parent == null && (!it.final || System.currentTimeMillis() - it.updatedAt < 7 * 24 * 3_600_000L) }.sortedByDescending { it.createdAt }
    fun helpers(id: String) = job(id)?.children?.mapNotNull { job(it) } ?: emptyList()

    // ---------------------------------------------------------------- the journal

    private fun file() = AtomicFile(File(app.filesDir, "bots.json"))

    /** Loads the journal once and picks up where things were left. Main thread. */
    fun init(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        loaded = true
        BotExtras.init(app)
        try {
            val f = file()
            if (f.baseFile.exists()) jobs.addAll(BotJob.parseList(String(f.readFully(), Charsets.UTF_8)))
        } catch (e: Exception) { Log.w(TAG, "journal unreadable", e) }
        val now = System.currentTimeMillis()
        jobs.forEach { j ->
            val to = BotRules.recover(j.state, j.pending != null)
            if (to != j.state) {
                // straight from running: not a normal move, so set it directly and note it
                j.state = to; j.updatedAt = now; j.history.add(now to to)
                if (to == BotRules.NEEDS_REVIEW) { j.current = null; notifyAlert(j, "Needs a check", "Atlas was closed while the bot was doing: ${j.pending}. It may or may not have happened.") }
            }
        }
        persist()
        pump()
    }

    private fun snapshot(): ByteArray {
        // a finished helper stays while its bot still needs its report
        val finished = jobs.filter { it.final && !(it.parent != null && job(it.parent)?.final == false) }.sortedByDescending { it.updatedAt }.drop(KEEP_FINISHED).toSet()
        if (finished.isNotEmpty()) jobs.removeAll(finished)
        return BotJob.listJson(jobs).toByteArray(Charsets.UTF_8)
    }

    /** Saves run on background threads in any order: each carries a number, and an older one never overwrites a newer. */
    private var saveSeq = 0L
    private var savedSeq = 0L
    private fun write(seq: Long, bytes: ByteArray) {
        synchronized(this) {
            if (seq <= savedSeq) return
            val f = file(); val out = f.startWrite()
            try { out.write(bytes); f.finishWrite(out); savedSeq = seq } catch (e: Exception) { f.failWrite(out); Log.w(TAG, "save failed", e) }
        }
    }

    /** Saves in the background. */
    private fun persist() { val b = snapshot(); val n = ++saveSeq; scope.launch(Dispatchers.IO) { write(n, b) } }
    /** Saves and waits until it's on disk (before an action runs, so a crash can't lose the "doing it now" mark). */
    private suspend fun persistNow() { val b = snapshot(); val n = ++saveSeq; withContext(Dispatchers.IO) { write(n, b) } }

    // ---------------------------------------------------------------- what the user (or Atlas) does

    /** Sends a bot to do [goal]. Returns the job. */
    fun deploy(ctx: Context, goal: String, details: String, specialist: String? = null, routine: String? = null, parent: String? = null, maxCalls: Int = 0, maxMs: Long = 0, memo: Boolean = false): BotJob {
        init(ctx)
        val now = System.currentTimeMillis()
        val device = try { Store.read(ctx) { it.deviceId } } catch (_: Exception) { "" }
        val j = BotJob(UUID.randomUUID().toString(), Plain.memory(goal).ifBlank { goal.trim() }.take(300), details.trim().take(4000), now, device)
        j.history.add(now to BotRules.QUEUED)
        j.memo = memo
        j.specialist = specialist; j.routine = routine; j.maxCalls = maxCalls; j.maxMs = maxMs
        if (parent != null) { j.parent = parent; j.depth = (job(parent)?.depth ?: 0) + 1 }
        jobs.add(j)
        persist(); changed(); pump()
        return j
    }

    fun pause(id: String) {
        val j = job(id) ?: return
        if (j.move(BotRules.PAUSED, System.currentTimeMillis())) { j.current = null; persist(); changed() }
    }

    fun resume(id: String) {
        val j = job(id) ?: return
        if (j.state == BotRules.PAUSED && j.move(BotRules.QUEUED, System.currentTimeMillis())) { persist(); changed(); pump() }
    }

    fun stop(id: String) {
        val j = job(id) ?: return
        if (j.final) return
        workers.remove(id)?.cancel()
        j.move(BotRules.CANCELLED, System.currentTimeMillis()); j.current = null
        BotBrowser.release(id)
        cancelAlert(j)
        j.children.toList().forEach { stop(it) }   // its helpers stop with it
        persist(); changed(); pump()
    }

    /** A message from the user to a bot while it works (or waits): it reads it at its next step. */
    fun steer(id: String, text: String): Boolean {
        val j = job(id) ?: return false
        val t = text.trim().take(1000)
        if (t.isEmpty() || j.final) return false
        j.inbox.add(t); j.step("You said: ${t.take(120)}"); j.updatedAt = System.currentTimeMillis()
        persist(); changed(); return true
    }

    /** "Wrap up now": the bot stops after its current step and writes the report with what it has. */
    fun wrapUp(id: String) {
        val j = job(id) ?: return
        if (j.final) return
        j.wrapUp = true; j.step("You asked it to wrap up"); j.updatedAt = System.currentTimeMillis()
        if (j.state == BotRules.PAUSED || j.state == BotRules.WAITING_HELPERS) j.move(BotRules.QUEUED, System.currentTimeMillis())
        persist(); changed(); pump()
    }

    /** [always]: also allow this kind of action from now on (the user's standing choice, in Me). */
    fun approve(id: String, yes: Boolean, always: Boolean = false) {
        val j = job(id) ?: return
        val a = j.approval ?: return
        if (j.state != BotRules.AWAITING_APPROVAL) return
        if (yes && always) a.category?.let { BotExtras.setMode(it, "allow") }
        a.decision = if (yes) "approved" else "denied"
        j.move(BotRules.QUEUED, System.currentTimeMillis()); cancelAlert(j)
        persist(); changed(); pump()
    }

    fun answer(id: String, text: String) {
        val j = job(id) ?: return
        if (j.state != BotRules.AWAITING_ANSWER || text.isBlank()) return
        j.answer = text.trim().take(2000)
        j.move(BotRules.QUEUED, System.currentTimeMillis()); cancelAlert(j)
        persist(); changed(); pump()
    }

    /** After "needs a check": carry on (the bot is told the last action may or may not have happened). */
    fun carryOn(id: String) {
        val j = job(id) ?: return
        if (j.state != BotRules.NEEDS_REVIEW) return
        j.pending?.let { j.notes.add("Atlas was interrupted while you were doing: $it. It may or may not have happened: check before doing it again.") }
        j.pending = null
        j.move(BotRules.QUEUED, System.currentTimeMillis()); cancelAlert(j)
        persist(); changed(); pump()
    }

    fun remove(id: String) {
        val j = job(id) ?: return
        if (!j.final) return
        jobs.remove(j); cancelAlert(j); persist(); changed()
    }

    // ---------------------------------------------------------------- running

    private val retryTick = Runnable { pump() }

    /** Starts what can start (at most [BotRules.MAX_RUNNING] at once) and wakes up for the next retry. */
    /** A bot waiting on helpers carries on once they've all finished (their reports go into its notes). */
    private fun wakeParents() {
        jobs.filter { it.state == BotRules.WAITING_HELPERS }.forEach { p ->
            if (p.children.mapNotNull { job(it) }.all { it.final }) { takeHelperReports(p); p.move(BotRules.QUEUED, System.currentTimeMillis()); persist() }
        }
    }
    private fun takeHelperReports(p: BotJob) {
        p.children.mapNotNull { job(it) }.filter { it.final && !it.reported }.forEach { k ->
            k.reported = true
            val who = k.specialist?.let { BotExtras.specialist(it)?.optString("name") } ?: "A helper"
            val body = if (k.state == BotRules.COMPLETED) (k.report ?: "").take(4000) else "It couldn't finish (${k.error ?: BotRules.label(k.state)})."
            p.notes.add("$who reported on \"${k.goal}\":\n$body"); while (p.notes.size > 60) p.notes.removeAt(0)
            p.toolCalls += k.toolCalls   // the helpers' work counts against the whole job's budget
            p.step("$who reported back")
        }
    }

    fun pump() {
        if (!loaded) return
        wakeParents()
        val now = System.currentTimeMillis()
        // how many may work at once depends on the phone right now: one when it's hot, short of memory or saving battery
        val slots = try { minOf(BotRules.MAX_RUNNING, ModelDirector.botSlots(DeviceProfiler.read(app))) } catch (_: Exception) { 1 }
        BotRules.toStart(jobs.filter { it.id !in workers && it.state != BotRules.RUNNING }, now, slots - workers.size)
            .forEach { j ->
                if (workers.size >= slots) return@forEach
                if (j.state == BotRules.WAITING_RETRY) j.move(BotRules.QUEUED, now)
                if (!j.move(BotRules.RUNNING, now)) return@forEach
                workers[j.id] = scope.launch {
                    try { work(j) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "bot failed", e); fail(j, "Something went wrong: ${e.message ?: e.javaClass.simpleName}") }
                    finally {
                        if (workers[j.id] === kotlinx.coroutines.currentCoroutineContext()[Job]) workers.remove(j.id)
                        if (j.state == BotRules.RUNNING) { j.move(BotRules.QUEUED, System.currentTimeMillis()) }
                        j.current = null
                        if (j.final) BotBrowser.release(j.id)
                        persist(); changed()
                        main.post { pump() }
                    }
                }
            }
        main.removeCallbacks(retryTick)
        jobs.filter { it.state == BotRules.WAITING_RETRY }.minOfOrNull { it.retryAt }?.let { main.postDelayed(retryTick, (it - now).coerceIn(1_000, 3_600_000)) }
        changed()
    }

    private fun fail(j: BotJob, why: String) {
        j.error = why; j.current = null
        if (j.move(BotRules.FAILED, System.currentTimeMillis())) notifyAlert(j, "Couldn't finish", why)
        persist()
    }

    /** One job, from where the journal says it is, until it finishes or has to wait. */
    private suspend fun work(j: BotJob) {
        var p = Providers.active(app) ?: return fail(j, "Atlas Bot needs an AI service: add a key in Atlas → Me → Brain.")
        j.provider = p.name
        var last = System.currentTimeMillis()
        fun tick() { val n = System.currentTimeMillis(); j.workMs += n - last; last = n }
        fun stillMine() = j.state == BotRules.RUNNING

        takeDecisions(j)
        if (!stillMine()) return
        val tried = mutableSetOf(p.id)
        var nudges = 0
        var extra = ""
        // the user's messages since the last turn become standing instructions (kept in the notes for the whole job)
        fun inbox() {
            if (j.inbox.isEmpty()) return
            j.inbox.forEach { j.notes.add("NEW INSTRUCTION FROM THE USER (follow it; it overrides earlier plans where they differ): $it") }
            j.inbox.clear(); while (j.notes.size > 60) j.notes.removeAt(0); persist()
        }
        while (stillMine()) {
            tick()
            inbox()
            val wrapping = j.wrapUp || j.toolCalls >= BotRules.callsOf(j) || j.workMs >= BotRules.msOf(j)
            val brief = brief(j) + extra + when {
                j.wrapUp -> "\n\nThe user asked you to wrap up now. Call finish with the best report you can from what you have, and say plainly what's left undone."
                wrapping -> "\n\nYour budget for this job is used up. Call finish now with the best report you can from your plan and notes, and say plainly what's left undone."
                else -> ""
            }
            extra = ""
            val session = try { withContext(Dispatchers.IO) { Providers.session(app, p, emptyList(), brief, tools = if (wrapping) finishOnly else toolsFor(j)) } }
                catch (f: Gemini.Failure) { return fail(j, f.message ?: "Couldn't start ${p.name}.") }
            var rounds = 0
            while (stillMine() && rounds < ROUNDS_PER_PART) {
                rounds++
                j.current = "Thinking"; changed()
                val system = withContext(Dispatchers.IO) { systemPrompt(app, j) }
                val turn = try {
                    KeyRouter.call(app, p, session, onStatus = { st -> if (stillMine()) { j.current = st ?: "Thinking"; changed() } }) { session.call(system) }
                } catch (sw: KeyRouter.SwitchService) {
                    if (!tried.add(sw.to.id)) { waitAndRetry(j, sw.message ?: "All your keys are used up for now."); return }
                    p = sw.to; j.provider = p.name; j.step("Switched to ${p.name}"); persist()
                    break // a fresh start on the new service, from the journal
                } catch (f: Gemini.Failure) {
                    if (f.offline || f.code == 429 || f.code == 500 || f.code == 502 || f.code == 503 || f.code == 504) { waitAndRetry(j, f.message ?: "The AI service is busy."); return }
                    return fail(j, f.message ?: "${p.name} failed.")
                }
                j.retries = 0
                if (!stillMine()) return
                tick()
                if (turn.calls.isEmpty()) {
                    // it answered in words instead of calling finish: take that as the report once it has done some work
                    if (turn.text.isNotBlank() && BotRules.textEndsJob(nudges, wrapping)) { complete(j, turn.text); return }
                    nudges++
                    extra = "\n\n" + BotRules.NUDGE
                    break
                }
                if (wrapping && turn.calls.none { it.name == "finish" }) { complete(j, compiled(j)); return }
                val results = mutableListOf<Pair<Gemini.Call, String>>()
                for (c in turn.calls) {
                    if (!stillMine()) break
                    when (c.name) {
                        "finish" -> {
                            val busy = j.children.mapNotNull { job(it) }.filter { !it.final }
                            if (busy.isNotEmpty() && !j.finishNudged && !wrapping) {
                                j.finishNudged = true
                                results.add(c to "${busy.size} of your helpers are still working. Call wait_for_helpers to get their reports first (or call finish again to finish without them)."); continue
                            }
                            val open = j.plan.count { !it.done }
                            if (BotRules.remindOpenSteps(open, j.planNudged, wrapping)) {
                                j.planNudged = true
                                results.add(c to BotRules.openStepsReminder(open)); continue
                            }
                            busy.forEach { stop(it.id) }
                            complete(j, c.args.optString("report").ifBlank { turn.text.ifBlank { compiled(j) } }); return
                        }
                        // ---- extras (BotExtras): skills, helpers, a specialist's own notes
                        "use_skill" -> {
                            val sk = BotExtras.skill(c.args.optString("name"))
                            results.add(c to (sk?.let { BotExtras.Rules.skillText(it) } ?: "There's no skill called \"${c.args.optString("name")}\". Skills: ${BotExtras.skills.joinToString(", ") { it.optString("name") }.ifEmpty { "none yet" }}."))
                            sk?.let { j.step("Using the skill: ${it.optString("name")}") }
                        }
                        "delegate" -> {
                            val sp = BotExtras.specialist(c.args.optString("specialist"))
                            val task = c.args.optString("task").trim()
                            when {
                                j.depth >= BotRules.MAX_DEPTH -> results.add(c to "Helpers can't hand work on any further: do it yourself.")
                                sp == null -> results.add(c to "There's no specialist bot called \"${c.args.optString("specialist")}\". The team: ${BotExtras.specialists.joinToString(", ") { it.optString("name") }}.")
                                task.isEmpty() -> results.add(c to "Error: say what the helper should do.")
                                j.children.size >= BotRules.MAX_CHILDREN -> results.add(c to "You already have ${BotRules.MAX_CHILDREN} helpers: that's the limit. Do the rest yourself.")
                                else -> {
                                    val (calls, ms) = BotRules.childBudget(j)
                                    val k = deploy(app, task.lineSequence().first().take(300), task, specialist = sp.optString("id"), parent = j.id, maxCalls = calls, maxMs = ms)
                                    j.children.add(k.id); j.step("Handed to ${sp.optString("name")}: ${k.goal.take(100)}"); persist()
                                    results.add(c to "${sp.optString("name")} is on it. Keep working on other parts; call wait_for_helpers when you need its report.")
                                }
                            }
                        }
                        "wait_for_helpers" -> {
                            val busy = j.children.mapNotNull { job(it) }.filter { !it.final }
                            if (busy.isEmpty()) { takeHelperReports(j); results.add(c to if (j.children.isEmpty()) "You haven't handed anything to a helper." else "All your helpers have reported: their reports are in your notes now."); continue }
                            j.current = null; j.move(BotRules.WAITING_HELPERS, System.currentTimeMillis()); j.step("Waiting for ${busy.size} helper${if (busy.size == 1) "" else "s"}")
                            persistNow(); changed(); return
                        }
                        "bot_remember", "bot_forget" -> {
                            val sp = j.specialist
                            results.add(c to if (sp == null) "Only specialist bots keep their own notes." else BotExtras.specialistNote(sp, c.args.optString("text"), c.name == "bot_forget"))
                        }
                        "update_plan" -> {
                            val lines = c.args.optString("plan").lines().map { it.trim() }.filter { it.isNotEmpty() }
                            if (lines.isNotEmpty()) {
                                val first = j.plan.isEmpty()
                                j.plan.clear()
                                lines.take(BotRules.MAX_PLAN).forEach { l ->
                                    val done = Regex("^(\\d+[.)]\\s*)?\\[(x|X|✓)]").containsMatchIn(l)
                                    j.plan.add(PlanItem(Plain.memory(l.replace(Regex("^(\\d+[.)]\\s*)?(\\[[ xX✓]?]\\s*)?(\\d+[.)]\\s*)?"), "")).take(160), done))
                                }
                                j.step(if (first) "Made a plan" else "Updated the plan")
                            }
                            results.add(c to "Plan saved.")
                        }
                        "note" -> {
                            val n = c.args.optString("text").trim()
                            if (n.isNotEmpty()) { j.notes.add(n.take(1500)); while (j.notes.size > 60) j.notes.removeAt(0) }
                            results.add(c to "Noted.")
                        }
                        "ask_user" -> {
                            val q = Plain.reply(c.args.optString("question")).ifBlank { "Could you tell me more about what you want?" }
                            j.question = q.take(600); j.current = null
                            j.move(BotRules.AWAITING_ANSWER, System.currentTimeMillis()); j.step("Asked you: ${q.take(120)}")
                            // a question about the bot's browser (sign in, a human check): the alert opens the browser
                            val forBrowser = BotBrowser.live(j.id) && Regex("browser|sign ?in|log ?in|captcha|human check|verif", RegexOption.IGNORE_CASE).containsMatchIn(q)
                            persistNow(); notifyAlert(j, "Atlas Bot has a question", q, browser = forBrowser); changed()
                            return
                        }
                        "suggest_laptop" -> {
                            suggestLaptop(j, c.args.optString("reason"))
                            results.add(c to "Told the user. Keep working on the phone as well as you can.")
                        }
                        "request_approval" -> {
                            val what = Plain.reply(c.args.optString("action")).ifBlank { "Go ahead with the next step" }
                            askApproval(j, Approval(what.take(300), null, null, null)); return
                        }
                        // ---- files (BotFiles): only inside this job's folder, so nothing to approve
                        in BotFiles.names -> results.add(c to runFiles(j, c))
                        // ---- the bot's web browser (BotBrowser): act on websites, with risky clicks and submits waiting for an OK
                        in browserTools -> {
                            val (label, inForm) = BotBrowser.inspect(app, j.id, c.name, c.args)
                            // the page around it (payment fields, the form, the element's hidden words): money always asks
                            val sig = if (c.name in BrowserRules.moneyTools) BotBrowser.signals(app, j.id, c.name, c.args) else BrowserRules.Signals()
                            val money = BrowserRules.moneyRisk(c.name, sig)
                            // a saved sign-in the user allowed on this site goes ahead without asking (Vault.kt), but never on a payment page
                            val trusted = !money && c.name == "browser_fill_login" && withContext(Dispatchers.IO) { Vault.find(Vault.all(app), c.args.optString("entry")) }
                                ?.let { it.trusted && Vault.siteMatches(it.site, BotBrowser.site()) } == true
                            if (!trusted) BrowserRules.approval(c.name, c.args, label, inForm, BotBrowser.site(), sig)?.let { what ->
                                // its kind is the strongest risk in everything the page says, not just the button's words
                                val kind = if (c.name == "browser_fill_login" && !money) "account" else BotExtras.Rules.guessCategory(BrowserRules.riskText(label, sig) + " " + what)
                                askApproval(j, Approval(what, c.name, JSONObject(c.args.toString()), label, category = kind, mustAsk = money)); return
                            }
                            val out = runBrowser(j, c)
                            if (out.waitForUser != null) { waitForUser(j, out.waitForUser); return }
                            results.add(c to out.text)
                        }
                        // >>> trading: a LIVE order waits for the user's tap on the job page; "always allow" never covers it (Trading.kt)
                        in Trading.names -> {
                            val need = Trading.needForBot(app, j.id, c)
                            if (need != null) { askApproval(j, Approval(need.take(600), c.name, JSONObject(c.args.toString()), null, category = "purchase", mustAsk = true)); return }
                            results.add(c to runTrade(j, c, approved = false))
                        }
                        // <<< trading
                        else -> {
                            if (c.name !in toolNames && !Connections.owns(c.name) && c.name !in Capabilities.names(app, forBots = true)) { results.add(c to "Error: there's no tool called ${c.name}."); continue }
                            val label = if (c.name == "tap" || c.name == "long_press") withContext(Dispatchers.Default) { PhoneControl.labelOf(c.args.optInt("element", -1)) } else null
                            BotRules.phoneApproval(c.name, c.args, label)?.let { what ->
                                askApproval(j, Approval(what, c.name, JSONObject(c.args.toString()), label)); return
                            }
                            results.add(c to runTool(j, c))
                        }
                    }
                }
                if (!stillMine()) return
                if (j.toolCalls >= BotRules.BIG_JOB_STEPS && j.suggestPc == null) suggestLaptop(j, "It's taking many steps.")
                session.results(results)
                persist(); changed()
                if (j.toolCalls >= BotRules.callsOf(j) || j.workMs >= BotRules.msOf(j) || j.wrapUp) break
                if (j.inbox.isNotEmpty()) break   // the user said something: a fresh turn that includes it
            }
        }
    }

    /** Runs one of Atlas's own tools for the bot, with the "doing it now" mark saved around it. */
    private suspend fun runTool(j: BotJob, c: Gemini.Call): String {
        val what = label(c)
        j.current = what; j.pending = what; persistNow(); changed()
        // a connection tool reaching here was already cleared by the bot's approval rules
        val r = if (Connections.owns(c.name)) Connections.run(app, c, approved = true, who = "bots")
            else if (c.name in Email.names) Email.run(app, c, approved = true)   // >>> email: the bot's approval (if one was needed) is already given
            else Tools.run(app, c)
        // pictures the bot made go into its job folder (listed on the job page)
        r.media.forEach { m -> try { val d = BotBrowser.jobDir(app, j.id).apply { mkdirs() }; m.file.copyTo(java.io.File(d, m.file.name), overwrite = true) } catch (e: Exception) { Log.w(TAG, "picture", e) } }
        j.pending = null; j.toolCalls++
        j.step(r.step ?: what)
        if (c.name == "read_page") c.args.optString("url").trim().takeIf { it.startsWith("http") && it !in j.sources }?.let { j.sources.add(it); if (j.sources.size > 40) j.sources.removeAt(0) }
        j.addRecent("$what:\n${r.out.take(if (c.name == "read_page" || c.name == "web_search") 2500 else 1200)}")
        persist()
        return r.out
    }

    // >>> trading
    /** Runs a trading tool for the bot, with the "doing it now" mark ([approved]: the user OK'd this exact order). */
    private suspend fun runTrade(j: BotJob, c: Gemini.Call, approved: Boolean): String {
        val what = Trading.stepLabel(c)
        j.current = what; j.pending = what; persistNow(); changed()
        val out = Trading.runForBot(app, j.id, c, approved)
        j.pending = null; j.toolCalls++
        j.step(if (out.startsWith("Error") || out.startsWith("Not ")) "$what: ${out.take(120)}" else what)
        j.addRecent("$what:\n${out.take(1200)}")
        persist()
        return out
    }
    // <<< trading

    /** Runs one file tool for the bot (BotFiles), with the same "doing it now" mark as [runTool]. */
    private suspend fun runFiles(j: BotJob, c: Gemini.Call): String {
        val what = BotFiles.label(c.name, c.args)
        j.current = what; j.pending = what; persistNow(); changed()
        val r = try { BotFiles.run(app, j.id, j.goal, c.name, c.args) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "files", e); BotFiles.Out("Error: that didn't work (${e.message ?: e.javaClass.simpleName}).", "A file step failed") }
        j.pending = null; j.toolCalls++
        j.step(r.step)
        j.addRecent("$what:\n${r.text.take(1200)}")
        persist()
        return r.text
    }

    /** Runs one browser action for the bot, with the same "doing it now" mark as [runTool]. */
    private suspend fun runBrowser(j: BotJob, c: Gemini.Call): BotBrowser.Out {
        val what = BotBrowser.label(c.name, c.args)
        j.current = what; j.pending = what; persistNow(); changed()
        val r = try { BotBrowser.run(app, j.id, c.name, c.args) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "browser", e); BotBrowser.Out("Error: the browser failed (${e.message ?: e.javaClass.simpleName}). Try again or another way.", "The browser failed") }
        j.pending = null; j.toolCalls++
        j.step(r.step)
        r.url?.takeIf { it.startsWith("http") && it !in j.sources }?.let { j.sources.add(it); if (j.sources.size > 40) j.sources.removeAt(0) }
        j.addRecent("$what:\n${r.text.take(1500)}")
        persist()
        return r
    }

    /** The bot needs the user in its browser (a sign-in, a human check): the job waits, and the alert opens the browser. */
    private suspend fun waitForUser(j: BotJob, what: String) {
        j.question = what.take(600); j.current = null
        j.move(BotRules.AWAITING_ANSWER, System.currentTimeMillis()); j.step(what.take(120))
        persistNow(); notifyAlert(j, "Atlas Bot needs you in its browser", what, browser = true); changed()
    }

    /** The user decided on an approval, or answered a question: act on it before the bot carries on. */
    private suspend fun takeDecisions(j: BotJob) {
        j.question?.let { q -> j.answer?.let { a -> j.notes.add("You asked the user: $q. They answered: $a"); j.step("You answered: ${a.take(120)}") } }
        if (j.answer != null) { j.question = null; j.answer = null }
        val a = j.approval ?: return
        val d = a.decision ?: return
        j.approval = null
        if (d == "denied") {
            j.notes.add(if (a.auto) "The user never allows this kind of action (${a.what}). Don't do it; find another way or finish without it." else "The user said NO to: ${a.what}. Don't do it; find another way or finish without it.")
            if (!a.auto) j.step("You said no to: ${a.what}")
            persist(); return
        }
        if (!a.auto) j.step("You approved: ${a.what}")
        if (a.tool == null || a.args == null) { j.notes.add("The user approved: ${a.what}. Go ahead with exactly that."); persist(); return }
        if (a.tool in browserTools) {
            // a browser action: check the page still shows the same thing, then do it
            if (a.elementLabel != null && BotBrowser.inspect(app, j.id, a.tool, a.args).first != a.elementLabel) {
                j.notes.add("The user approved: ${a.what}, but the page changed before it could be done, so it was NOT done. Read the page again; ask again if it's still needed."); persist(); return
            }
            val out = runBrowser(j, Gemini.Call(a.tool, a.args, null))
            j.notes.add("Approved and done: ${a.what}. Result: ${out.text.lineSequence().firstOrNull()?.take(300) ?: ""}")
            persist(); return
        }
        // >>> trading: the approved order exactly as shown (its ticket); the risk engine checks it again before it's sent
        if (a.tool in Trading.names) {
            val out = runTrade(j, Gemini.Call(a.tool, a.args, null), approved = true)
            j.notes.add("Approved: ${a.what.take(200)}. Result: ${out.lineSequence().firstOrNull()?.take(300) ?: ""}")
            persist(); return
        }
        // <<< trading
        if (a.elementLabel != null) {
            val now = withContext(Dispatchers.Default) { PhoneControl.labelOf(a.args.optInt("element", -1)) }
            if (now != a.elementLabel) { j.notes.add("The user approved: ${a.what}, but the screen changed before it could be done, so it was NOT done. Read the screen again; ask again if it's still needed."); persist(); return }
        }
        val out = runTool(j, Gemini.Call(a.tool, a.args, null))
        j.notes.add("Approved and done: ${a.what}. Result: ${out.lineSequence().firstOrNull()?.take(300) ?: ""}")
        persist()
    }

    /** A heavy job: tell the user (once) that the laptop would do it better. The bot carries on here meanwhile. */
    private fun suggestLaptop(j: BotJob, reason: String) {
        if (j.suggestPc != null) return
        j.suggestPc = Plain.reply(reason).ifBlank { "It's a big job." }.take(200)
        j.step(BotRules.LAPTOP_HINT)
        notifyAlert(j, "Atlas Bot: ${j.goal}", "${BotRules.LAPTOP_HINT} (${j.suggestPc})")
        persist(); changed()
    }

    private suspend fun askApproval(j: BotJob, a: Approval) {
        // the user's standing choice for this kind of action: always allowed, never allowed, or ask (BotExtras)
        a.category = a.category ?: BotExtras.Rules.categoryOf(a.tool, a.args, a.what)
        // "Always allow" never covers money: a payment page, or a buy/pay/order action, always waits for a tap
        val mode = BotExtras.mode(a.category!!).let { if (it == "allow" && a.mustAsk) "ask" else it }
        if (mode != "ask") {
            a.decision = if (mode == "allow") "approved" else "denied"; a.auto = true
            j.approval = a; j.current = null
            j.step((if (mode == "allow") "Allowed by your rule (you always allow: " else "Not done: you never allow ") + BotExtras.Rules.label(a.category).lowercase() + (if (mode == "allow") ")" else "") + ": ${a.what}")
            // straight back in the queue: the decision is acted on at the start of its next turn, like a tapped one
            j.move(BotRules.AWAITING_APPROVAL, System.currentTimeMillis()); j.move(BotRules.QUEUED, System.currentTimeMillis())
            persistNow(); changed(); main.post { pump() }
            return
        }
        j.approval = a; j.current = null
        j.move(BotRules.AWAITING_APPROVAL, System.currentTimeMillis()); j.step("Waiting for your OK: ${a.what}")
        persistNow(); notifyAlert(j, "Atlas Bot needs your OK", a.what, approval = true); changed()
    }

    private fun waitAndRetry(j: BotJob, why: String) {
        j.retries++
        if (j.retries > BotRules.MAX_RETRIES) return fail(j, "$why I tried ${BotRules.MAX_RETRIES} times; it still didn't work.")
        val wait = BotRules.retryDelay(j.retries)
        j.retryAt = System.currentTimeMillis() + wait; j.error = why; j.current = null
        j.move(BotRules.WAITING_RETRY, System.currentTimeMillis()); j.step("Waiting ${wait / 60_000} min to try again: ${why.take(100)}")
        persist(); changed()
    }

    private fun complete(j: BotJob, report: String) {
        val now = System.currentTimeMillis()
        j.report = Plain.reply(report).ifBlank { compiled(j) } + BotFileRules.footer(BotFiles.madeBy(app, j.id))   // the files it made, and where
        j.plan.forEach { it.done = true }
        j.current = null; j.error = null
        j.move(BotRules.COMPLETED, now)
        // a helper's report goes back to the bot that sent it, not into the chat
        if (j.parent != null) { persist(); changed(); main.post { pump() }; return }
        if (!j.posted) { j.posted = true; Conversation.postFromBot(app, "**Atlas Bot finished:** ${j.goal}\n\n${j.report}") }
        if (j.memo) sendMemo(j)
        notifyAlert(j, "Atlas Bot is done", j.goal)
        persist(); changed()
    }

    /** The report as a spoken voice memo in the chat, in the user's chosen voice (needs a Gemini or OpenAI key). */
    private fun sendMemo(j: BotJob) {
        val report = j.report ?: return
        Thread {
            val f = try { Voice.memoFile(app, Speech.memoText(report), Attach.dir(app)) } catch (e: Exception) { Log.w(TAG, "voice memo", e); null }
            Handler(Looper.getMainLooper()).post {
                if (f != null) Conversation.postFromBot(app, "**Voice memo:** ${j.goal}", listOf(Attach.Item(f.path, if (f.name.endsWith(".mp3")) "audio/mpeg" else "audio/wav", "Voice memo")))
                else Conversation.postFromBot(app, "I couldn't record the voice memo for “${j.goal}” (it needs a Gemini or OpenAI key for the natural voice). The written report is above.")
            }
        }.start()
    }

    /** A report built from the notes, when the bot ran out of budget without writing one. */
    private fun compiled(j: BotJob): String = buildString {
        append("## ").append(j.goal).append("\n\nI ran out of time before writing a full report. Here is what I found:\n\n")
        if (j.notes.isEmpty()) append("- I didn't get far enough to find anything useful.\n") else j.notes.forEach { append("- ").append(it.replace("\n", " ")).append("\n") }
        if (j.sources.isNotEmpty()) { append("\n## Sources\n"); j.sources.forEach { append("- ").append(it).append("\n") } }
    }

    // ---------------------------------------------------------------- what the model sees

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
        JSONObject().put("name", name).put("description", desc).apply {
            if (props.length() > 0) put("parameters", JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
        }
    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private val finishTool by lazy {
        fn("finish", "Finish the job and hand over the report. The report is shown to the user and posted in their chat.",
            JSONObject().put("report", str("The full report in neat Markdown, ending with a Sources section when you used the web.")), listOf("report"))
    }
    private val finishOnly by lazy { JSONArray().put(finishTool) }

    /** Atlas's own tools that bots don't get: settings, focus sessions, and sending more bots. */
    private val notForBots = setOf("change_setting", "start_focus", "deploy_bot", "save_skill", "set_routine", "on_laptop")   // on_laptop: a bot (maybe itself sent from the laptop) doesn't bounce work back
    private val browserTools = setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_select", "browser_press", "browser_scroll", "browser_back", "browser_wait", "browser_download", "browser_fill_login" /* vault: Vault.kt */)

    private val declarations: JSONArray by lazy {
        JSONArray().apply {
            for (i in 0 until Tools.declarations.length()) {
                val d = Tools.declarations.getJSONObject(i)
                if (d.optString("name") !in notForBots) put(d)
            }
            // the bot's own web browser: for doing things on websites (signing up, filling forms, booking, buying with an OK)
            put(fn("browser_open", "Open a web page in your own browser (it keeps sign-ins between jobs). Returns the page: its text and numbered buttons, links and fields.",
                JSONObject().put("url", str("The web address, e.g. https://www.example.com/page")), listOf("url")))
            put(fn("browser_read", "Read the open page again: its text and numbered buttons, links and fields. Numbers change after every action, so use the latest list."))
            put(fn("browser_click", "Click a button, link or other element on the open page, by its number from the latest list (or its exact words). Returns the new page.",
                JSONObject().put("target", str("The element's number, e.g. \"12\", or its words, e.g. \"Add to cart\"")), listOf("target")))
            put(fn("browser_type", "Type into a field on the open page (replacing what's in it). Set submit to true to press Enter afterwards (searching, sending a form). Never for passwords. Returns the new page.",
                JSONObject().put("target", str("The field's number or label")).put("text", str("What to type")).put("submit", JSONObject().put("type", "boolean").put("description", "Press Enter after typing")), listOf("target", "text")))
            put(fn("browser_select", "Choose an option in a dropdown on the open page. Returns the new page.",
                JSONObject().put("target", str("The dropdown's number or label")).put("option", str("The option's words")), listOf("target", "option")))
            put(fn("browser_press", "Press a key on the open page: Enter, Tab, Escape, Backspace, Space, Up, Down, Left or Right. Returns the new page.",
                JSONObject().put("key", str("The key")), listOf("key")))
            put(fn("browser_scroll", "Scroll the open page. Returns what's now visible.",
                JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up", "top", "bottom")))), listOf("direction")))
            put(fn("browser_back", "Go back to the previous page."))
            put(fn("browser_wait", "Wait for the page to finish loading or updating, then read it.",
                JSONObject().put("seconds", JSONObject().put("type", "number").put("description", "1 to 15 seconds"))))
            put(Vault.fillDeclaration)   // a saved sign-in from the vault (Vault.kt)
            put(fn("browser_download", "Download a file: a link's number from the page, or a web address. It's kept with this job (the user can save it to Downloads).",
                JSONObject().put("target", str("The link's number or the file's web address")), listOf("target")))
            // >>> files the bot hands over (BotFiles): its job folder, a PDF document, an Excel spreadsheet, an interactive page
            BotFiles.declarations.forEach { put(it) }
            // <<< files
            put(fn("update_plan", "Write or update your plan for this job: as many short steps as the job needs (no fixed number), one per line, in order. Start a line with [x] when that step is done.",
                JSONObject().put("plan", str("The steps, one per line, e.g. \"[x] Search for reviews\\n[ ] Compare prices\"")), listOf("plan")))
            put(fn("note", "Save something you'll need later: a fact, figure, name or link you found, with where it came from. Older tool results are dropped as you go, but notes are kept for the whole job.",
                JSONObject().put("text", str("The note, short and specific")), listOf("text")))
            put(fn("ask_user", "Ask the user one clear question when you truly can't go on without their answer (a choice only they can make, a missing detail). The job waits until they answer.",
                JSONObject().put("question", str("The question")), listOf("question")))
            put(fn("suggest_laptop", "Tell the user once that this job would go better on their laptop (Atlas runs there too, with bigger local models and PC tools): use it when the job is heavy, e.g. very long, many big files, needs the PC's apps or files, or would benefit from a bigger model. You keep working here meanwhile.",
                JSONObject().put("reason", str("Why, in a few words, e.g. 'It needs dozens of pages and a long write-up'")), listOf("reason")))
            put(fn("request_approval", "Ask the user's OK before anything that sends a message, posts, buys or pays, deletes something, or changes an account or security setting. Say exactly what you'll do. The job waits for their answer.",
                JSONObject().put("action", str("Exactly what you're about to do, e.g. 'Send \"See you at 6\" to Mum on WhatsApp'")), listOf("action")))
            put(finishTool)
        }
    }
    private val extraTools: Map<String, JSONObject> by lazy {
        mapOf(
            "use_skill" to fn("use_skill", "Load one of the skills the user taught you (listed in your instructions) and follow it for this kind of job.", JSONObject().put("name", str("The skill's name")), listOf("name")),
            "delegate" to fn("delegate", "Hand part of this job to one of the user's specialist bots (listed in your instructions). It works on its own and reports back; call wait_for_helpers when you need the reports. Up to 4 helpers; give each a clear, self-contained task.",
                JSONObject().put("specialist", str("The specialist bot's name")).put("task", str("Exactly what it should do and report back, with everything it needs to know")), listOf("specialist", "task")),
            "wait_for_helpers" to fn("wait_for_helpers", "Wait until your helpers have reported. Their reports are added to your notes, then you carry on."),
            "bot_remember" to fn("bot_remember", "Save a note for yourself (this specialist) for future jobs: a lasting fact about how the user likes this kind of work done.", JSONObject().put("text", str("The note")), listOf("text")),
            "bot_forget" to fn("bot_forget", "Remove one of your own saved notes that is wrong or out of date.", JSONObject().put("text", str("The note, or words from it")), listOf("text")),
        )
    }
    /** The tools this job's bot gets: a specialist only its own kinds; helpers only at the allowed depth; skills when there are any. */
    private fun toolsFor(j: BotJob): JSONArray {
        val sp = j.specialist?.let { BotExtras.specialist(it) }
        val out = JSONArray()
        for (i in 0 until declarations.length()) {
            val d = declarations.getJSONObject(i); val n = d.optString("name")
            if (n != "finish" && BotExtras.Rules.toolAllowed(sp, n)) out.put(d)
        }
        // >>> connections (Connections.kt): the servers the user lets bots use, as they are now
        Connections.declarations(app, "bots").let { c -> for (i in 0 until c.length()) { val d = c.getJSONObject(i); if (BotExtras.Rules.toolAllowed(sp, d.optString("name"))) out.put(d) } }
        // <<< connections
        // capabilities that say bots may use them (Aware.kt registry: email, projects, trading…)
        Capabilities.botDeclarations(app).let { c -> for (i in 0 until c.length()) { val d = c.getJSONObject(i); if (BotExtras.Rules.toolAllowed(sp, d.optString("name"))) out.put(d) } }
        out.put(extraTools["use_skill"])
        if (j.depth < BotRules.MAX_DEPTH && BotExtras.specialists.isNotEmpty()) { out.put(extraTools["delegate"]); out.put(extraTools["wait_for_helpers"]) }
        if (sp != null) { out.put(extraTools["bot_remember"]); out.put(extraTools["bot_forget"]) }
        out.put(finishTool)
        return out
    }
    private val toolNames: Set<String> by lazy { (0 until Tools.declarations.length()).map { Tools.declarations.getJSONObject(it).optString("name") }.filter { it !in notForBots }.toSet() }

    private val stampFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    private fun systemPrompt(ctx: Context, j: BotJob? = null): String {
        val name = Store.read(ctx) { it.settings.name }.ifBlank { "the user" }
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(Engine.zone)
        val phone = if (PhoneControl.on()) "on" else "OFF (you can't use apps; do what you can without them, or ask the user to turn it on in Atlas → Me → Phone control)"
        return """
You are Atlas Bot, an agent that Atlas ($name's personal AI assistant) sends to do a job in the background on their Android phone. You're not chatting: nobody reads what you write until you finish. Work through the job with your tools, then call finish with the report.

How you work:
1. Plan first: call update_plan with a plan of as many steps as the job really needs (a small job may need 3, a big one 30 or more; there's no fixed number). The plan gives you structure, not a limit: add, split or reorder steps whenever you learn something, and tick steps off with [x] as you finish them.
   Your job is to get the thing DONE, not just to find out about it: research only as far as doing it needs, unless the user asked for information. Keep going until every step is done or truly can't be.
2. Work step by step with tools. When the job is to get something done online (not just find out), do it in your browser. For research, run several focused web searches and read the best pages instead of guessing; cross-check important facts in two sources when you can. Prefer recent, primary sources.
3. Older tool results are dropped as you go to keep things small, so save what you'll need later (facts, figures, names, links, with the source) with note as soon as you find it. Your plan and notes are kept for the whole job.
4. Before you finish, check your work: does the report do the whole job? Is every number and claim backed by something you read or did?
5. If you truly can't go on without the user (a choice only they can make, a missing detail), call ask_user with one clear question. Decide sensible things yourself.
6. Call finish with the report.

The report:
- Neat Markdown: a title line (## heading), a short summary first, then sections with headings, bullet lists, **bold** key words, and a table when comparing options. No filler.
- Honest: say what you couldn't find, couldn't verify or didn't do. Never say you did something a tool result didn't show.
- Never show code, ids, tool or function names, or JSON, unless the job asked for code.
- If you used the web, end with a "Sources" section: each page you relied on as [Title or site](link).

Safety:
- Anything that sends a message, posts, buys or pays, deletes something, or changes an account or security setting needs the user's OK first: call request_approval saying exactly what you'll do, and wait. Some actions are also checked automatically and will wait for the OK.
- Never type passwords, payment details or verification codes.

Doing things on websites (your own browser):
- You have a real web browser: browser_open a page, then work from its numbered buttons, links and fields with browser_click, browser_type (submit to send), browser_select, browser_press, browser_scroll, browser_back. Each action returns the new page: read it and check the action really worked before moving on.
- Use it to actually do things the job asks for on the web: fill in and send forms, search a site, add to a cart, book, sign up, download files. Use web_search and read_page for plain reading; use the browser when you need to click, type or stay signed in.
- Sign-ins: never type passwords or codes. If a page needs the user to sign in, or shows a human check (CAPTCHA), call ask_user asking them to do it in the bot's browser; you carry on when they're done. Their sign-ins are kept for next time.
- Clicking things that send, post, buy, pay, book, sign up, accept terms or delete waits for the user's OK automatically; say clearly in your report what you did and what's left for them.
- Report evidence: the confirmation text, order or reference numbers, the final page's address.

Files you hand over (kept in this job's folder on the phone):
- When the job calls for something the user will keep, make the real file: make_document for a report, brief, letter or plan (a typeset PDF); make_spreadsheet for data, comparisons, budgets or trackers (Excel, with formulas where they help); make_page for an interactive view with tables to sort and filter and charts.
- workspace_write / workspace_read / workspace_list keep drafts, notes and data in the job folder; downloads land there too.
- The report still gives the answer itself (the key findings), then names the files you made. They show in Bots › Library.

Using the phone (only when the job needs it):
- open_app, then work from the numbered elements on the screen. Every action returns the new screen; check it. Numbers change after each action, so use numbers from the latest screen only.
- If an approach fails twice, try another; after a few tries, note what blocked you and move on or finish.
- Phone control is $phone.

Big jobs: the user also has Atlas on their laptop, with bigger local models and PC tools. If this job is heavy (very long, many big files, needs the PC's apps or files, or would be much better with a bigger model), call suggest_laptop once early, then keep doing what you can here.

Budget: up to ${j?.let { BotRules.callsOf(it) } ?: MAX_TOOL_CALLS} tool steps and ${(j?.let { BotRules.msOf(it) } ?: MAX_WORK_MS) / 60_000} minutes for the job. When it's nearly used up, finish with what you have.
${teamPart(j)}
${Connections.promptLine(ctx)}

Now: ${now.format(stampFmt)} (${Engine.zone.id}).
${Tools.logContext(ctx)}
""".trim()
    }

    /** Skills, the team, the user's standing rules and (for a specialist) who it is: from BotExtras. */
    private fun teamPart(j: BotJob?): String {
        val out = mutableListOf<String>()
        BotExtras.Rules.skillsIndex(BotExtras.skills.toList()).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        if ((j?.depth ?: 0) < BotRules.MAX_DEPTH) BotExtras.Rules.teamIndex(BotExtras.specialists.filter { it.optString("id") != j?.specialist }).takeIf { it.isNotEmpty() }?.let {
            out.add("$it\nHand a part to one with delegate when it fits their speciality and the job is big enough to split; small jobs you do yourself.")
        }
        BotExtras.Rules.rulesPrompt(BotExtras.policy).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        j?.routine?.let { id -> BotExtras.routines.firstOrNull { it.optString("id") == id } }?.let { r ->
            out.add("This job is the routine \"${r.optString("name")}\" (${BotExtras.Rules.describeSchedule(r.optJSONObject("schedule"))}). Keep the report short and focused on what's new since last time.")
        }
        val sp = j?.specialist?.let { BotExtras.specialist(it) }
        return (if (out.isNotEmpty()) "\n" + out.joinToString("\n\n") else "") + BotExtras.Rules.specialistPrompt(sp)
    }

    /** Where the job stands, as the opening message of each fresh round with the model. */
    private fun brief(j: BotJob): String = buildString {
        append("Your job: ").append(j.goal)
        if (j.details.isNotBlank()) append("\nDetails: ").append(j.details)
        val left = ((BotRules.msOf(j) - j.workMs) / 60_000).coerceAtLeast(0)
        append("\n(Tool steps used: ${j.toolCalls} of ${BotRules.callsOf(j)}. Time left: about $left min.)")
        if (j.parent != null) append("\n(You are a helper: another bot gave you this task. Your report goes back to it, not to the user.)")
        if (j.plan.isEmpty()) append("\n\nNo plan yet: start by making one with update_plan.")
        else { append("\n\nYour plan:"); j.plan.forEachIndexed { i, it -> append("\n${i + 1}. ${if (it.done) "[x]" else "[ ]"} ${it.text}") } }
        if (j.notes.isNotEmpty()) { append("\n\nYour notes so far:"); j.notes.forEach { append("\n- ").append(it) } }
        if (j.sources.isNotEmpty()) { append("\n\nPages you've read:"); j.sources.forEach { append("\n- ").append(it) } }
        val done = j.steps.takeLast(20)
        if (done.isNotEmpty()) { append("\n\nWhat you've done so far (latest last):"); done.forEach { append("\n- ").append(it) } }
        if (j.recent.isNotEmpty()) { append("\n\nLatest results:"); j.recent.forEach { append("\n---\n").append(it) }; append("\n---") }
        append(if (j.toolCalls == 0 && j.plan.isEmpty()) "\n\nBegin." else "\n\nCarry on from here.")
    }

    private fun label(c: Gemini.Call): String = when (c.name) {
        "web_search" -> "Searching for “${c.args.optString("query").take(60)}”"
        "read_page" -> "Reading ${c.args.optString("url").removePrefix("https://").removePrefix("http://").take(50)}"
        "open_app" -> "Opening ${c.args.optString("name")}"
        "read_screen" -> "Looking at the screen"
        "tap" -> "Tapping"
        "long_press" -> "Pressing"
        "type_text" -> "Typing"
        "scroll" -> "Scrolling"
        "press_key" -> "Pressing ${c.args.optString("key")}"
        "wait" -> "Waiting"
        "set_reminder" -> "Logging a reminder"
        "update_reminder" -> "Changing a reminder"
        "remember" -> "Saving to memory"
        "forget" -> "Deleting a memory"
        "read_log" -> "Checking your Log"
        "open_link" -> "Opening a link"
        else -> if (c.name in browserTools) BotBrowser.label(c.name, c.args) else if (c.name in BotFiles.names) BotFiles.label(c.name, c.args) else Connections.label(c.name, c.args) ?: Email.label(c.name, c.args) ?: "Working"
    }

    // ---------------------------------------------------------------- notifications

    @Volatile private var channelsReady = false

    /** Set up once per run of the app: each call is a slow round trip to Android (see Reminders.channels). */
    fun channels(ctx: Context) {
        if (channelsReady) return
        channelsReady = true
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BOT, "Atlas Bot working", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while Atlas Bot works on a job" })
        nm.createNotificationChannel(NotificationChannel(CH_BOT_ALERT, "Atlas Bot", NotificationManager.IMPORTANCE_HIGH).apply { description = "When a bot finishes, has a question or needs your OK" })
    }

    fun notifId(j: BotJob) = 5100 + (j.id.hashCode() and 0xFFF)

    fun openIntent(ctx: Context, j: BotJob?, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, Intent(ctx, BotActivity::class.java).putExtra(BotActivity.EXTRA_ID, j?.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun actionIntent(ctx: Context, j: BotJob, action: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, notifId(j) * 8 + action.hashCode() % 7, Intent(ctx, BotReceiver::class.java).setAction(action).putExtra(BotActivity.EXTRA_ID, j.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyAlert(j: BotJob, title: String, text: String, approval: Boolean = false, browser: Boolean = false) {
        if (!::app.isInitialized) return
        channels(app)
        val b = Notification.Builder(app, CH_BOT_ALERT).setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
            .setContentText(Plain.reply(text).take(200)).setStyle(Notification.BigTextStyle().bigText(Plain.reply(text).take(600)))
            .setContentIntent(if (browser && BotBrowser.live(j.id)) BotBrowserActivity.openIntent(app, j.id, notifId(j)) else openIntent(app, j, notifId(j))).setAutoCancel(true)
        if (approval) {
            b.addAction(Notification.Action.Builder(null, "Approve", actionIntent(app, j, BotReceiver.ACTION_APPROVE)).build())
            b.addAction(Notification.Action.Builder(null, "Deny", actionIntent(app, j, BotReceiver.ACTION_DENY)).build())
        }
        try { app.getSystemService(NotificationManager::class.java).notify(notifId(j), b.build()) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
    }

    private fun cancelAlert(j: BotJob) { if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(notifId(j)) }
}
