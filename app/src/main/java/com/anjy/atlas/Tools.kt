package com.anjy.atlas

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.anjy.atlas.core.AtlasException
import com.anjy.atlas.core.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What Atlas can do: keep reminders and memories (the Log), and use the phone like a person (open apps, read the
 * screen, tap, type, scroll, press keys). Declared to Gemini as functions; run here.
 */
object Tools {
    /** [out] goes back to the model; [step] is the short line the user sees under Atlas's reply (null: nothing to show). */
    class Result(val out: String, val step: String? = null, val phone: Boolean = false, val media: List<Attach.Item> = emptyList())

    /** A function for Gemini. One that takes nothing has no "parameters" at all: Gemini refuses an object with no properties. */
    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
        JSONObject().put("name", name).put("description", desc).apply {
            if (props.length() > 0) put("parameters", JSONObject().put("type", "object").put("properties", props).apply {
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }
    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc).apply { if (enum != null) put("enum", JSONArray(enum)) }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    val declarations: JSONArray by lazy {
        JSONArray().apply {
            put(fn("set_reminder", "Log a reminder. At its time Atlas either rings an alarm (important things) or sends a notification with a sound (lighter things), and asks if they'll do it.",
                JSONObject().put("title", str("Short title, e.g. 'Pick up my sister'"))
                    .put("alert", str("alarm: important or time-critical (picking someone up, appointments, medicine, leaving for something, waking up). notification: lighter things (drink water, stretch, read, small chores). If the user says which, do that.", listOf("alarm", "notification")))
                    .put("when", str("When, as the user said it (\"tomorrow at 6pm\", \"in 90 minutes\", \"monday 9am\") or local 'YYYY-MM-DD HH:MM'. Leave out only if the user gave no time at all."))
                    .put("duration_min", int("How long it takes, in minutes (default 30)"))
                    .put("repeat", str("How it repeats", listOf("none", "daily", "weekdays", "weekly")))
                    .put("notes", str("Optional details")), listOf("title")))
            put(fn("update_reminder", "Change a logged reminder: mark it done, cancel it, delete it, rename it, move it, or make it ring as an alarm or a notification.",
                JSONObject().put("id", str("The reminder's id from the list in your instructions"))
                    .put("action", str("What to do", listOf("done", "cancel", "delete", "move", "rename", "make_alarm", "make_notification")))
                    .put("when", str("For move: the new time, as said (\"tomorrow 9am\") or local 'YYYY-MM-DD HH:MM'"))
                    .put("title", str("For rename: the new title")), listOf("id", "action")))
            put(fn("remember", "Save a lasting fact or preference the user wants Atlas to keep (shown in the Log).",
                JSONObject().put("text", str("The fact, in a short sentence")), listOf("text")))
            put(fn("read_log", "Look things up in the user's Log: reminders (upcoming, done, snoozed, skipped, missed), things they asked you to remember, and what happened when. Newest first.",
                JSONObject().put("query", str("Words to search for; leave out to get the latest entries"))
                    .put("limit", int("How many entries at most (default 30)"))))
            put(fn("forget", "Delete a saved memory.", JSONObject().put("id", str("The memory's id from your instructions")), listOf("id")))
            put(fn("open_app", "Open an installed app by name. Returns the screen once it has opened. To get something DONE, prefer the background ways first (connections, messages_recent/reply_message, send_sms, phone_alarm, a bot's browser on the app's website); a bot that opens an app is pointed at those first.",
                JSONObject().put("name", str("The app's name as it appears on the phone, e.g. 'ChatGPT', 'Instagram', 'Settings'"))
                    .put("use_screen", JSONObject().put("type", "boolean").put("description", "Bots only: true when there's no background way and the screen must be used (the user gets a heads-up first)")), listOf("name")))
            put(fn("open_link", "Open a web link or app link (https://, tel:, mailto:, geo:).", JSONObject().put("url", str("The link")), listOf("url")))
            put(fn("read_screen", "Read what's on the phone's screen now: the app, and numbered elements (buttons, text, fields)."))
            put(fn("tap", "Tap a numbered element from the most recent screen. Returns the new screen.",
                JSONObject().put("element", int("Element number from the latest read_screen")), listOf("element")))
            put(fn("long_press", "Press and hold a numbered element. Returns the new screen.",
                JSONObject().put("element", int("Element number from the latest screen")), listOf("element")))
            put(fn("type_text", "Type text into a text field (replacing what's in it). Returns the new screen.",
                JSONObject().put("element", int("The field's element number; leave out to use the field that has focus"))
                    .put("text", str("What to type"))
                    .put("press_enter", bool("Press the keyboard's enter/send key afterwards")), listOf("text")))
            put(fn("scroll", "Scroll the screen or a list. Returns the new screen.",
                JSONObject().put("direction", str("Which way the content should move into view", listOf("down", "up", "left", "right")))
                    .put("element", int("A scrollable element's number; leave out for the main list")), listOf("direction")))
            put(fn("press_key", "Press a phone key. Returns the new screen.",
                JSONObject().put("key", str("The key", listOf("back", "home", "recents", "notifications", "enter"))), listOf("key")))
            put(fn("web_search", "Search the internet directly (no need to open a browser on the phone). Use it for anything current or that you're unsure of: news, prices, opening hours, facts, how-tos. Returns results with sources.",
                JSONObject().put("query", str("What to search for")), listOf("query")))
            put(fn("read_page", "Read a web page directly and get its text (from a search result or a link the user gave).",
                JSONObject().put("url", str("The page's address")), listOf("url")))
            put(fn("change_setting", "Change one of Atlas's own settings when the user asks (voice, speaking replies, theme, being on screen, Hey Atlas, motion, alarm sound, their name, not switching AI services). The current values are in your instructions.",
                JSONObject().put("setting", str("Which setting", Self.names)).put("value", str("The new value: on/off, a voice name, dark/light, or the name")), listOf("setting", "value")))
            put(fn("start_focus", "Start a focus session when the user says they're doing something right now (\"I'm doing it now\", \"starting my homework\"). Opens a distraction-free screen with a timer.",
                JSONObject().put("title", str("What they're doing")).put("minutes", int("Planned length in minutes, if they said or it's known (default: none, the timer counts up)"))
                    .put("id", str("The reminder's id, if it's one from the Log")), listOf("title")))
            // >>> device link
            put(fn("on_laptop", "Do something on the user's LAPTOP (they're on the phone): open or use a PC app, find or send a file from the laptop, run something there, look at its screen. Atlas on the laptop does it as a bot job with the PC's tools and reports back into this chat. Give the full task in plain words.",
                JSONObject().put("task", str("What to do on the laptop, with all the details")), listOf("task")))
            // <<< device link
            put(fn("deploy_bot", "Send Atlas Bot to get a bigger job DONE in the background: anything that takes many steps, such as doing things on websites (filling in and sending forms, booking, ordering, signing up, posting, downloading), working through email, building a project, trading within the user's limits, making documents or spreadsheets, a long chain of phone actions, or deep research with sources when the user wants information. The bot plans as many steps as the job needs, carries it through to the end (asking the user only for risky steps), and its report lands in this chat; the chat stays free meanwhile. Don't use it for quick things you can do in a step or two.",
                JSONObject().put("goal", str("The job in one clear sentence, as an outcome to achieve, e.g. 'Book the cheapest Lagos–Abuja flight on Friday morning' or 'Find the best budget phone under $300 for photos, with sources'"))
                    .put("details", str("Everything the bot should know: what the user wants, preferences, limits, what to include in the report"))
                    .put("voice_memo", bool("Also send the report as a spoken voice memo (when they ask for one, or are talking to you by voice)"))
                    .put("specialist", str("Optional: one of the user's specialist bots to send (by name), when the job fits its speciality")), listOf("goal")))
            // ---- Atlas Bot extras (BotExtras): skills taught by telling, and routines (bots on a schedule)
            put(fn("save_skill", "Save a skill: a reusable way of doing a kind of job that the user teaches you (\"when I ask for X, do it like this…\"). Bots load it when they do that kind of job. Saving with an existing name updates it.",
                JSONObject().put("name", str("A short name, e.g. \"Weekly money check\"")).put("when", str("When to use it")).put("steps", str("The steps, one per line"))
                    .put("inputs", str("What is needed first (optional)")).put("checks", str("What to check before finishing (optional)"))
                    .put("output", str("What the result should look like (optional)")).put("permissions", str("Actions in it that need the user's OK (optional)")), listOf("name", "when", "steps")))
            put(fn("set_routine", "Set up a routine: a bot sent on a schedule, like \"every weekday at 8am, summarise the news on AI\". Runs every 5 minutes at most.",
                JSONObject().put("goal", str("What the bot should do each time")).put("schedule", str("When, in plain words: \"every weekday at 8am\", \"every 2 hours\", \"every Sunday evening\", \"every month on the 1st at 9am\""))
                    .put("name", str("A short name (optional)")).put("specialist", str("A specialist bot to send (optional)")), listOf("goal", "schedule")))
            put(fn("wait", "Wait for something to load (an answer being written, a page loading). Returns the new screen.",
                JSONObject().put("seconds", int("1 to 15")), listOf("seconds")))
            // calendar, contacts, location and the Clock app (PhoneData.kt)
            PhoneData.declarations.forEach { put(it) }
            put(ImageGen.declaration)
            put(Vault.listDeclaration)
        }
    }

    // STRICT: an impossible date (30 February, 31 April) is refused, not quietly moved to the end of the month
    private val whenFmts = listOf("uuuu-MM-dd HH:mm", "uuuu-MM-dd'T'HH:mm", "uuuu-MM-dd'T'HH:mm:ss", "uuuu-MM-dd HH:mm:ss").map { DateTimeFormatter.ofPattern(it).withResolverStyle(java.time.format.ResolverStyle.STRICT) }

    /** "2026-11-12 16:00" (the phone's local time) to epoch ms; null when missing or unreadable. */
    fun parseWhen(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in whenFmts) {
            try { return LocalDateTime.parse(t, f).atZone(Engine.zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        }
        // words like "tomorrow 9am" or "in 2 hours" (past allowed: calendars can look back)
        if (Regex("^\\d{4}-").containsMatchIn(t)) return null   // a malformed or impossible date stays refused
        return (com.anjy.atlas.core.When.resolve(t, System.currentTimeMillis(), Engine.zone, allowPast = true) as? com.anjy.atlas.core.When.Result.Ok)?.ms
    }

    fun fmtWhen(ms: Long): String = "${Brain.dayLabel(ms)} at ${Reminders.fmt(ms)}"

    /** Context for the model: what's in the Log right now. */
    fun logContext(ctx: Context): String = Store.read(ctx) { s ->
        val sb = StringBuilder()
        val open = s.tasks.filter { it.status in Engine.OPEN }.sortedWith(compareBy({ it.startAt == null }, { it.startAt }))
        sb.append("Open reminders:")
        if (open.isEmpty()) sb.append(" none")
        open.take(40).forEach { t ->
            sb.append("\n- [${t.id}] ${t.title} — ")
            sb.append(t.startAt?.let { at -> Instant.ofEpochMilli(at).atZone(Engine.zone).toLocalDateTime().format(whenFmts[0]) } ?: "no time")
            if (t.recurrence != "none") sb.append(", repeats ${t.recurrence}")
            if (t.status != "planned") sb.append(" (${t.status})")
        }
        sb.append("\nSaved memories:")
        if (s.memories.isEmpty()) sb.append(" none")
        s.memories.takeLast(40).forEach { m -> sb.append("\n- [${m.id}] ${m.text}") }
        sb.toString()
    }

    private val stamp = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(Engine.zone).format(stamp)

    /** The Log as text for the model: entries newest first, optionally only those mentioning [query]. */
    fun readLog(ctx: Context, query: String?, limit: Int): String = Store.read(ctx) { s ->
        data class Line(val time: Long, val text: String)
        val lines = mutableListOf<Line>()
        s.tasks.forEach { t ->
            val status = when (t.status) {
                "planned" -> if (t.startAt != null) "upcoming" else "no time set"
                "done" -> "said they'd do it / done" + (t.completedAt?.let { " (${at(it)})" } ?: "")
                "skipped" -> "not doing it" + (t.skipReason.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                else -> t.status
            }
            lines.add(Line(t.createdAt, "[${t.id}] Reminder: ${t.title}" + (t.startAt?.let { " — for ${at(it)}" } ?: "") + " — $status" +
                (if (t.recurrence != "none") " — repeats ${t.recurrence}" else "") + (if (t.notes.isNotBlank()) " — notes: ${t.notes}" else "") + " (logged ${at(t.createdAt)})"))
        }
        s.memories.forEach { m -> lines.add(Line(m.createdAt, "[${m.id}] Remembered: ${m.text} (saved ${at(m.createdAt)})")) }
        s.log.filter { it.type in setOf("done", "skipped", "delayed", "note", "deleted", "cancelled") }.forEach { l ->
            lines.add(Line(l.at, "${at(l.at)}: ${l.type} — ${l.title}${if (l.detail.isNotBlank()) " (${l.detail})" else ""}"))
        }
        val q = query?.trim()?.lowercase().orEmpty()
        val hits = lines.filter { q.isEmpty() || q.split(' ').filter { w -> w.length > 1 }.all { w -> w in it.text.lowercase() } }
            .sortedByDescending { it.time }.take(limit)
        if (hits.isEmpty()) (if (q.isEmpty()) "The Log is empty." else "Nothing in the Log matches \"$query\".")
        else "Log (${hits.size} of ${lines.size} entries, newest first):\n" + hits.joinToString("\n") { it.text }
    }

    suspend fun run(ctx: Context, c: Gemini.Call): Result {
        val a = c.args
        val now = System.currentTimeMillis()
        // >>> connections: an MCP tool (mcp__server__tool) goes to Connections.kt
        if (c.name.startsWith("mcp__")) return try { Connections.run(ctx, c, approved = false) } catch (e: Exception) { Result("Error: ${e.message ?: e.javaClass.simpleName}") }
        // <<< connections
        // >>> background first: driving another app on screen is the last resort, with a heads-up (ScreenGate.kt)
        if (c.name == "open_app" || c.name in ScreenGate.driving) ScreenGate.before(ctx, c.name, a)?.let { return it }
        // <<< background first
        return try {
            when (c.name) {
                "set_reminder" -> withContext(Dispatchers.IO) {
                    val title = Plain.memory(a.optString("title"))
                    if (title.isEmpty()) return@withContext Result("Error: a title is needed.")
                    val whenRaw = a.optString("when").trim()
                    // exact time from what the user said (core/When.kt, the same rules as the PC); impossible or past times are refused
                    var at: Long? = null
                    if (whenRaw.isNotEmpty()) when (val r = com.anjy.atlas.core.When.resolve(whenRaw, now, Engine.zone)) {
                        is com.anjy.atlas.core.When.Result.Bad -> return@withContext Result("Error: ${r.error}")
                        is com.anjy.atlas.core.When.Result.Ok -> at = r.ms
                    }
                    val rep = com.anjy.atlas.core.When.normRepeat(a.optString("repeat"))
                        ?: return@withContext Result("Error: “${a.optString("repeat")}” isn't a repeat Atlas can do. Use none, daily, weekdays or weekly.")
                    if (rep != "none" && at == null) return@withContext Result("Error: a repeating reminder needs a time. Ask the user when.")
                    val dur = com.anjy.atlas.core.When.normMinutes(a.opt("duration_min"), 1, 1440)
                    if (dur == -1) return@withContext Result("Error: duration_min must be between 1 and 1440 minutes.")
                    val alarm = a.optString("alert") == "alarm"
                    val t = Store.edit(ctx) { Engine.create(it, title, now, at, dur ?: 30, Plain.memory(a.optString("notes", "")), if (alarm) "high" else "normal", rep) }
                    Reminders.tickAndSchedule(ctx)
                    val whenTxt = t.startAt?.let { "${com.anjy.atlas.core.When.relativeDay(it, now, Engine.zone)}, ${com.anjy.atlas.core.When.fmtLong(it, Engine.zone)} (${Engine.zone.id})" } ?: "with no time yet"
                    Result("Logged reminder [${t.id}] '${t.title}' for ${whenTxt}${if (t.recurrence != "none") ", repeats ${t.recurrence}" else ""}; at its time: ${if (alarm) "an alarm" else "a notification with a sound"}. Tell the user this stored time exactly.",
                        "Logged “${t.title}”, ${t.startAt?.let { fmtWhen(it) } ?: "with no time yet"}")
                }
                "update_reminder" -> withContext(Dispatchers.IO) {
                    val id = a.optString("id")
                    val title = Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == id }?.title } ?: return@withContext Result("Error: no reminder with id $id.")
                    when (a.optString("action")) {
                        "done" -> { Store.edit(ctx) { Engine.respond(it, id, "done", now) }; AvatarService.celebrate(ctx) }
                        "cancel" -> Store.edit(ctx) { Engine.cancel(it, id, now) }
                        "delete" -> Store.edit(ctx) { Engine.delete(it, id, now) }
                        "move" -> {
                            val at = when (val r = com.anjy.atlas.core.When.resolve(a.optString("when"), now, Engine.zone)) {
                                is com.anjy.atlas.core.When.Result.Bad -> return@withContext Result("Error: ${r.error}")
                                is com.anjy.atlas.core.When.Result.Ok -> r.ms
                            }
                            Store.edit(ctx) { s -> val t = Engine.find(s, id); Engine.update(s, id, t.title, now, at, t.durationMin, t.notes, t.priority, t.recurrence) }
                        }
                        "rename" -> {
                            val nt = Plain.memory(a.optString("title")).ifEmpty { return@withContext Result("Error: give the new title.") }
                            Store.edit(ctx) { s -> val t = Engine.find(s, id); Engine.update(s, id, nt, now, t.startAt, t.durationMin, t.notes, t.priority, t.recurrence) }
                        }
                        "make_alarm", "make_notification" -> Store.edit(ctx) { s -> Engine.find(s, id).priority = if (a.optString("action") == "make_alarm") "high" else "normal" }
                        else -> return@withContext Result("Error: unknown action.")
                    }
                    Reminders.tickAndSchedule(ctx)
                    val act = a.optString("action")
                    // read back what's stored now, so the reply quotes the real state, not a guess
                    val state = if (act == "delete") "deleted" else Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == id } }?.let { t ->
                        "now ${t.status}" + (t.startAt?.let { ", at ${com.anjy.atlas.core.When.fmtLong(it, Engine.zone)}" } ?: "") + (if (t.title != title) ", titled '${t.title}'" else "")
                    } ?: "gone"
                    Result("Done: $act on '$title' ($state).${if (act == "move") " Tell the user this stored time exactly." else ""}", when (act) { "done" -> "Marked “$title” done"; "cancel" -> "Cancelled “$title”"; "delete" -> "Deleted “$title”"; "move" -> "Moved “$title”"; "make_alarm" -> "“$title” will ring as an alarm"; "make_notification" -> "“$title” will come as a notification"; else -> "Renamed “$title”" })
                }
                "read_log" -> withContext(Dispatchers.IO) { Result(readLog(ctx, a.optString("query"), a.optInt("limit", 30).coerceIn(1, 200))) }
                "remember" -> withContext(Dispatchers.IO) {
                    val txt = Plain.memory(a.optString("text"))
                    val same = Store.read(ctx) { s -> s.memories.firstOrNull { it.text.trim().equals(txt, ignoreCase = true) } }
                    if (txt.isEmpty()) Result("Error: nothing to remember.")
                    else if (same != null) Result("Already saved as memory [${same.id}]: '${same.text}'. Nothing new was added.")
                    else {
                        val m = Store.edit(ctx) { Engine.remember(it, txt, now) }; ModelManager.markStale()
                        Result("Saved memory [${m.id}], exactly: '${m.text}'.", "Remembered “$txt”")
                    }
                }
                "forget" -> withContext(Dispatchers.IO) {
                    val id = a.optString("id")
                    val txt = Store.read(ctx) { s -> s.memories.firstOrNull { it.id == id }?.text } ?: return@withContext Result("Error: no memory with id $id.")
                    Store.edit(ctx) { Engine.forget(it, id, now) }; ModelManager.markStale()
                    Result("Forgotten: '$txt'.", "Forgot “$txt”")
                }
                "open_app" -> {
                    val name = a.optString("name").trim()
                    // the app list reads every app's name from the system: slow on a full phone, so off the main thread
                    val hit = withContext(Dispatchers.IO) { Brain.findApp(ctx, name) }
                        ?: return Result("No app called '$name' is installed. Installed apps include: ${withContext(Dispatchers.IO) { appNames(ctx) }}")
                    val said = Brain.launchApp(ctx, hit)
                    if (!said.startsWith("Opening")) return Result(said)
                    Result(afterAction(ctx, 1800, "Opened ${hit.first}."), "Opened ${hit.first}", phone = true)
                }
                "open_link" -> {
                    val url = a.optString("url").trim()
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Result(afterAction(ctx, 1800, "Opened the link."), "Opened ${url.take(60)}", phone = true)
                    } catch (e: Exception) { Result("Error: nothing on the phone can open that link.") }
                }
                // reading and touching the screen talks to the system for every element: kept off the main thread
                "web_search" -> withContext(Dispatchers.IO) {
                    val q = a.optString("query")
                    Result(Web.search(ctx, q), "Searched the web for “${q.take(60)}”")
                }
                "read_page" -> withContext(Dispatchers.IO) {
                    val u = a.optString("url")
                    Result(try { Web.read(u) } catch (e: Exception) { "Error: couldn't read that page (${e.message})." }, "Read ${u.removePrefix("https://").removePrefix("http://").take(50)}")
                }
                "change_setting" -> withContext(Dispatchers.Main) {
                    val out = Self.change(ctx, a.optString("setting"), a.optString("value"))
                    Result(out, if (out.startsWith("Error")) null else out)
                }
                "start_focus" -> withContext(Dispatchers.Main) {
                    val title = Plain.memory(a.optString("title")).ifEmpty { "Focus" }
                    val mins = com.anjy.atlas.core.When.normMinutes(a.opt("minutes"), 1, 600)
                    if (mins == -1) return@withContext Result("Error: minutes must be between 1 and 600 (or left out for an open session).")
                    Focus.start(ctx, title, mins, a.optString("id").takeIf { it.isNotBlank() })
                    Result("Focus session started for '$title'${mins?.let { " ($it min)" } ?: ""}. The focus screen is open.", "Started a focus session: $title")
                }
                "read_screen" -> withContext(Dispatchers.Default) { Result(PhoneControl.readScreen(), null, phone = true) }
                "tap" -> withContext(Dispatchers.Default) { PhoneControl.tap(a.optInt("element", -1), long = false) }.also { ScreenGate.touched(ctx) }
                "long_press" -> withContext(Dispatchers.Default) { PhoneControl.tap(a.optInt("element", -1), long = true) }.also { ScreenGate.touched(ctx) }
                "type_text" -> withContext(Dispatchers.Default) { PhoneControl.type(if (a.has("element")) a.optInt("element", -1) else -1, a.optString("text"), a.optBoolean("press_enter", false)) }.also { ScreenGate.touched(ctx) }
                "scroll" -> withContext(Dispatchers.Default) { PhoneControl.scroll(a.optString("direction", "down"), if (a.has("element")) a.optInt("element", -1) else -1) }.also { ScreenGate.touched(ctx) }
                "press_key" -> withContext(Dispatchers.Default) { PhoneControl.key(a.optString("key")) }.also { ScreenGate.touched(ctx) }
                "wait" -> {
                    val s = a.optInt("seconds", 2).coerceIn(1, 15)
                    delay(s * 1000L)
                    withContext(Dispatchers.Default) { Result(PhoneControl.readScreen(), null, phone = true) }
                }
                // >>> device link
                "on_laptop" -> withContext(Dispatchers.IO) {
                    val task = a.optString("task").trim()
                    if (task.isEmpty()) Result("Error: say what to do on the laptop.")
                    else { Link.init(ctx); val out = LinkTasks.sendTask(task); Result("$out Tell the user briefly, in your own words.", if (out.startsWith("Error")) null else "Sent to your laptop: ${task.take(80)}") }
                }
                // <<< device link
                "deploy_bot" -> withContext(Dispatchers.Main) {
                    val goal = a.optString("goal").trim()
                    if (goal.isEmpty()) return@withContext Result("Error: give the bot a goal.")
                    val sp = a.optString("specialist").takeIf { it.isNotBlank() }?.let { BotExtras.specialist(it)?.optString("id") }
                    val j = Bots.deploy(ctx, goal, a.optString("details"), specialist = sp, memo = a.optBoolean("voice_memo", false))
                    Result("Atlas Bot is on it: '${j.goal}'. It works in the background; its report will appear in this chat when it's done, and the user can follow it from the Atlas Bot strip at the top of the chat. Tell them briefly, in your own words.",
                        "Sent Atlas Bot: ${j.goal}")
                }
                "save_skill" -> {
                    BotExtras.init(ctx)
                    val name = Plain.memory(a.optString("name"))
                    if (name.isEmpty() || a.optString("steps").isBlank()) Result("Error: a skill needs a name and steps.")
                    else {
                        val sk = BotExtras.saveSkill(JSONObject().put("name", name).put("when", a.optString("when")).put("steps", a.optString("steps")).put("inputs", a.optString("inputs"))
                            .put("checks", a.optString("checks")).put("output", a.optString("output")).put("permissions", a.optString("permissions")))
                        Result("Saved the skill \"${sk.optString("name")}\". Bots will use it for that kind of job; the user can edit it on the Bots tab.", "Saved a skill: ${sk.optString("name")}")
                    }
                }
                "set_routine" -> withContext(Dispatchers.Main) {
                    BotExtras.init(ctx)
                    val sp = a.optString("specialist").takeIf { it.isNotBlank() }?.let { BotExtras.specialist(it)?.optString("id") }
                    val (r, err) = BotExtras.saveRoutine(null, a.optString("goal"), a.optString("schedule"), a.optString("name").takeIf { it.isNotBlank() }, sp)
                    if (r == null) Result("Error: $err")
                    else Result("Routine set: \"${r.optString("name")}\", next run ${android.text.format.DateUtils.formatDateTime(ctx, r.optLong("nextAt"), android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)}. The user can pause, test or delete it on the Bots tab.", "Set a routine: ${r.optString("name")}")
                }
                in PhoneData.names -> PhoneData.run(ctx, c.name, a)
                // >>> connections (Connections.kt): MCP servers and web APIs; anything that changes things is asked on screen first
                in setOf(McpWire.CALL_API) -> Connections.run(ctx, c, approved = false)
                // <<< connections
                "vault_list" -> withContext(Dispatchers.IO) { Result(Vault.forModel(Vault.all(ctx)), "Checked your saved sign-ins") }
                "generate_image" -> withContext(Dispatchers.Main) { if (a.optBoolean("use_my_pictures", false)) Conversation.latestUserImages() else emptyList() }.let { refs -> withContext(Dispatchers.IO) {
                    val prompt = a.optString("prompt").trim()
                    if (prompt.isEmpty()) return@withContext Result("Error: describe the picture.")
                    val aspect = a.optString("aspect").takeIf { it in ImageGen.ASPECTS } ?: "1:1"
                    if (a.optBoolean("use_my_pictures", false) && refs.isEmpty()) return@withContext Result("Error: there are no pictures in their latest message to base it on.")
                    val made = try { ImageGen.make(ctx, prompt, aspect, refs, Attach.dir(ctx)) } catch (e: Exception) { return@withContext Result("Error: couldn't make the picture: ${e.message}") }
                    Result("Made the picture with ${made.service}; it's shown with this reply (with Save and Share)." + (made.note.takeIf { it.isNotBlank() }?.let { " The image model added: ${it.take(300)}" } ?: ""),
                        "Made a picture", media = listOf(Attach.Item(made.file.path, made.mime, made.file.name)))
                } }
                else -> Capabilities.run(ctx, c) ?: Result("Error: there's no tool called ${c.name}.")
            }
        } catch (e: AtlasException) { Result("Error: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Result("Error: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** After opening something: give it a moment, then report the screen (or just [said] if Atlas can't see it). */
    private suspend fun afterAction(ctx: Context, waitMs: Long, said: String): String {
        delay(waitMs)
        return if (PhoneControl.on()) "$said\n" + withContext(Dispatchers.Default) { PhoneControl.readScreen() } else "$said (Atlas can't see the screen: phone control is off.)"
    }

    private fun appNames(ctx: Context): String {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }.distinct().sorted().take(80).joinToString(", ")
    }
}
