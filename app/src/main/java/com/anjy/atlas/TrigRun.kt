package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bot triggers on the phone: the watcher, like the PC's desktop/renderer/trigrun.js. It looks at each standing bot's
 * switched-on triggers and, when one fires, starts that bot's mission. Schedules, new email and price levels are checked
 * when the routine alarm wakes the phone (BotExtras.tick); phone notifications and opening an app arrive as they happen
 * (from the notification listener and the accessibility service). The rules (what counts as an event, de-duplication,
 * rate limits, where a bot runs) are TriggerRules, the same as the PC's; only one device starts each event (the cloud
 * claim). A trigger only starts the job: every risky step in it still asks the user.
 */
object TrigRun {
    private const val TAG = "AtlasTriggers"
    /** How often each kind looks (ms), like the PC's POLL; the phone's alarm wakes at most every [WAKE_FLOOR_MS] for polling. */
    val POLL = mapOf("schedule" to 0L, "email" to 120_000L, "price" to 60_000L)
    const val WAKE_FLOOR_MS = 5 * 60_000L

    private val lock = Any()
    private var state = JSONObject().put("v", 1).put("trig", JSONObject()).put("dedupe", JSONObject()).put("rate", JSONObject())
    private var loaded = false
    private val lastPoll = HashMap<String, Long>()
    private val errors = HashMap<String, String>()
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private fun file() = AtomicFile(File(app.filesDir, "triggers-state.json"))
    private fun load(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        try { val f = file(); if (f.baseFile.exists()) JSONObject(String(f.readFully(), Charsets.UTF_8)).let { s ->
            state = JSONObject().put("v", 1).put("trig", s.optJSONObject("trig") ?: JSONObject()).put("dedupe", s.optJSONObject("dedupe") ?: JSONObject()).put("rate", s.optJSONObject("rate") ?: JSONObject())
        } } catch (e: Exception) { Log.w(TAG, "state unreadable", e) }
        loaded = true
    }
    private fun save() {
        val text = synchronized(lock) { state.toString() }
        val f = file(); val out = f.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }
    private fun keyOf(botId: String, trigId: String) = "$botId|$trigId"
    private fun configKey(t: JSONObject): String {
        val c = JSONObject(t.optJSONObject("config")?.toString() ?: "{}"); c.remove("goal"); c.remove("perHour")
        return t.optString("type") + "|" + c.toString()
    }

    /** The triggers this phone watches now: bot active, trigger on, its type watchable here, the bot's home here. */
    private fun watched(types: Set<String>? = null): List<Pair<JSONObject, JSONObject>> {
        val out = mutableListOf<Pair<JSONObject, JSONObject>>()
        val bots = synchronized(BotExtras.specialists) { BotExtras.specialists.map { JSONObject(it.toString()) } }
        for (b in bots) {
            if (b.optString("state") == "paused" || TriggerRules.where(b, "phone") != "here") continue
            val ts = b.optJSONArray("triggers") ?: continue
            for (i in 0 until ts.length()) {
                val raw = ts.optJSONObject(i) ?: continue
                if (raw.opt("enabled") == false) continue
                val type = raw.optString("type")
                if (!TriggerRules.TYPES.containsKey(type) || !TriggerRules.watchable(type, "phone")) continue
                if (types != null && type !in types) continue
                out.add(b to TriggerRules.cleanTrigger(raw, i))
            }
        }
        return out
    }

    /** One event through de-duplication, the rate limit and the cloud claim, then the mission starts. */
    private fun deliver(b: JSONObject, t: JSONObject, ev: JSONObject, now: Long): BotJob? {
        val botId = b.optString("id"); val trigId = t.optString("id")
        val key = TriggerRules.eventKey(botId, trigId, ev)
        val fresh = synchronized(lock) {
            val d = TriggerRules.dedupe(state.optJSONObject("dedupe"), key, now); state.put("dedupe", d.state)
            if (!d.value) return@synchronized false
            val r = TriggerRules.rateAllow(state.optJSONObject("rate"), keyOf(botId, trigId), t.optJSONObject("config")?.optDouble("perHour", 12.0)?.toInt() ?: 12, now); state.put("rate", r.state)
            if (!r.value) { errors[keyOf(botId, trigId)] = "Skipped an event: it fired too often (its hourly limit)."; return@synchronized false }
            true
        }
        if (!fresh) return null
        // both devices may see the same email or price move: only one starts the mission
        val mine = try { Sync.claim(app, "trig:$key", 10 * 60_000L)?.mine } catch (_: Exception) { null }
        if (mine == false) return null
        return fire(b, t, ev)
    }

    /** Starts the bot's mission (on the main thread, where the bots' list lives). */
    private fun fire(b: JSONObject, t: JSONObject, ev: JSONObject): BotJob? {
        val c = t.optJSONObject("config") ?: JSONObject()
        val goal = (c.optString("goal").ifBlank { TriggerRules.summary(t) }).lineSequence().first().take(300)
        val details = TriggerRules.goalFor(b, t, ev)
        val source = JSONObject().put("type", if (t.optString("type") == "schedule") "routine" else "trigger").put("ref", t.optString("id")).put("at", System.currentTimeMillis())
        var job: BotJob? = null
        val run = Runnable { try { job = Bots.deploy(app, goal, details, specialist = b.optString("id"), source = source) } catch (e: Exception) { Log.w(TAG, "fire", e) } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else { val latch = CountDownLatch(1); main.post { try { run.run() } finally { latch.countDown() } }; latch.await(10, TimeUnit.SECONDS) }
        return job
    }

    /** One pass over the polled triggers (schedules, email, prices). Background thread. Returns the missions started. */
    fun tick(ctx: Context): List<BotJob> {
        load(ctx)
        val started = mutableListOf<BotJob>()
        val now = System.currentTimeMillis()
        val live = HashSet<String>()
        for ((b, t) in watched(POLL.keys)) {
            val botId = b.optString("id"); val k = keyOf(botId, t.optString("id")); live.add(k)
            val every = POLL[t.optString("type")] ?: 0L
            if (every > 0 && (lastPoll[k] ?: 0L) > 0 && now - (lastPoll[k] ?: 0L) < every) continue
            lastPoll[k] = now
            val events = try { check(b, t, k, now) } catch (e: Exception) { synchronized(lock) { errors[k] = (e.message ?: e.toString()).take(200) }; continue }
            for (ev in events) deliver(b, t, ev, now)?.let { started.add(it) }
        }
        synchronized(lock) {
            val trig = state.optJSONObject("trig") ?: JSONObject()
            trig.keys().asSequence().toList().forEach { if (it !in live) trig.remove(it) }   // a trigger switched back on starts from a fresh look
            state.put("trig", trig)
        }
        save()
        return started
    }

    private fun check(b: JSONObject, t: JSONObject, k: String, now: Long): List<JSONObject> {
        val c = t.optJSONObject("config") ?: JSONObject()
        val ck = configKey(t)
        val st: JSONObject? = synchronized(lock) {
            val trig = state.optJSONObject("trig") ?: JSONObject().also { state.put("trig", it) }
            var s = trig.optJSONObject(k)
            if (s == null || s.optString("ck") != ck) { s = JSONObject().put("ck", ck).put("st", JSONObject.NULL); trig.put(k, s) }
            s.optJSONObject("st")
        }
        var events: List<JSONObject> = emptyList()
        var next: JSONObject? = st
        when (t.optString("type")) {
            "schedule" -> {
                val sched = c.optJSONObject("schedule") ?: return emptyList()
                val r = TriggerRules.scheduleDue(st, sched, now); next = r.state
                r.value?.let { fireAt -> events = listOf(JSONObject().put("type", "schedule").put("key", "slot:$fireAt").put("title", "Scheduled run (${TriggerRules.summary(t)})")
                    .put("data", JSONObject().put("due", java.time.Instant.ofEpochMilli(fireAt).toString()))) }
            }
            "email" -> {
                val list = runBlocking { Email.list(app, c.optString("folder").ifEmpty { "INBOX" }, false, null, 25) }
                val mails = list.messages.map { m -> TriggerRules.Mail(MailText.parseId(m.id)?.uid ?: 0L, m.from, m.subject, m.unread, m.id) }
                val r = TriggerRules.emailEvents(st, mails, c); next = r.state; events = r.value
            }
            "price" -> {
                val sym = c.optString("symbol"); if (sym.isEmpty()) return emptyList()
                val res = runBlocking { Trading.run(app, "trade_quote", JSONObject().put("symbol", sym).put("account", c.optString("account"))) }
                val out = res.out ?: ""
                if (out.startsWith("Error", true) || out.startsWith("Not done", true)) throw IllegalStateException(out.take(160))
                val p = Regex("\\blast\\s+(-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)", RegexOption.IGNORE_CASE).find(out)?.groupValues?.get(1)?.toDoubleOrNull()
                if (p == null || p <= 0) return emptyList()
                val r = TriggerRules.priceEvents(st, p, now, c); next = r.state; events = r.value
            }
        }
        synchronized(lock) { state.optJSONObject("trig")?.optJSONObject(k)?.put("st", next ?: JSONObject.NULL); errors.remove(k) }
        return events
    }

    // ---------------------------------------------------------------- events as they happen

    /** A phone notification came (from the notification listener). Background thread. */
    fun notification(ctx: Context, pkg: String, app: String, title: String, text: String) {
        if (pkg == ctx.packageName) return
        load(ctx)
        val n = TriggerRules.Note(pkg, app, title, text)
        val now = System.currentTimeMillis()
        var any = false
        for ((b, t) in watched(setOf("notification"))) if (TriggerRules.notificationMatches(t.optJSONObject("config") ?: JSONObject(), n)) { deliver(b, t, TriggerRules.notificationEvent(n), now); any = true }
        if (any) save()
    }

    /** An app came to the front (from the accessibility service). Background thread. */
    fun appOpened(ctx: Context, pkg: String, label: String) {
        if (pkg == ctx.packageName) return
        load(ctx)
        val o = TriggerRules.Opened(pkg, label)
        val now = System.currentTimeMillis()
        var any = false
        for ((b, t) in watched(setOf("app"))) if (TriggerRules.appMatches(t.optJSONObject("config"), o)) { deliver(b, t, TriggerRules.appEvent(o, now), now); any = true }
        if (any) save()
    }

    /** True when some bot has a notification or app trigger on (the services only pass events on then). */
    fun wantsEvents(type: String): Boolean = try { watched(setOf(type)).isNotEmpty() } catch (_: Exception) { false }

    // ---------------------------------------------------------------- for the screens and the alarm

    /** When a schedule trigger fires next (for the bot's page), or 0. */
    fun nextAt(botId: String, trigId: String): Long = synchronized(lock) { state.optJSONObject("trig")?.optJSONObject(keyOf(botId, trigId))?.optJSONObject("st")?.optLong("nextAt") ?: 0L }
    fun problem(botId: String, trigId: String): String? = synchronized(lock) { errors[keyOf(botId, trigId)] }

    /** When the phone should next wake for triggers: the next schedule, or a few minutes when email or prices are watched. 0: never. */
    fun nextWake(ctx: Context): Long {
        load(ctx)
        val now = System.currentTimeMillis()
        var next = 0L
        for ((b, t) in watched(POLL.keys)) {
            val at = if (t.optString("type") == "schedule") nextAt(b.optString("id"), t.optString("id")).takeIf { it > 0 } ?: (now + 60_000L) else now + WAKE_FLOOR_MS
            if (next == 0L || at < next) next = at
        }
        return next
    }
}
