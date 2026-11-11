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
    class Result(val out: String, val step: String? = null, val phone: Boolean = false)

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
                    .put("when", str("Local date and time as 'YYYY-MM-DD HH:MM' (24h). Leave out only if the user gave no time at all."))
                    .put("duration_min", int("How long it takes, in minutes (default 30)"))
                    .put("repeat", str("How it repeats", listOf("none", "daily", "weekdays", "weekly")))
                    .put("notes", str("Optional details")), listOf("title")))
            put(fn("update_reminder", "Change a logged reminder: mark it done, cancel it, delete it, rename it, move it, or make it ring as an alarm or a notification.",
                JSONObject().put("id", str("The reminder's id from the list in your instructions"))
                    .put("action", str("What to do", listOf("done", "cancel", "delete", "move", "rename", "make_alarm", "make_notification")))
                    .put("when", str("For move: new local date and time 'YYYY-MM-DD HH:MM'"))
                    .put("title", str("For rename: the new title")), listOf("id", "action")))
            put(fn("remember", "Save a lasting fact or preference the user wants Atlas to keep (shown in the Log).",
                JSONObject().put("text", str("The fact, in a short sentence")), listOf("text")))
            put(fn("read_log", "Look things up in the user's Log: reminders (upcoming, done, snoozed, skipped, missed), things they asked you to remember, and what happened when. Newest first.",
                JSONObject().put("query", str("Words to search for; leave out to get the latest entries"))
                    .put("limit", int("How many entries at most (default 30)"))))
            put(fn("forget", "Delete a saved memory.", JSONObject().put("id", str("The memory's id from your instructions")), listOf("id")))
            put(fn("open_app", "Open an installed app by name. Returns the screen once it has opened.",
                JSONObject().put("name", str("The app's name as it appears on the phone, e.g. 'ChatGPT', 'Instagram', 'Settings'")), listOf("name")))
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
            put(fn("deploy_bot", "Send Atlas Bot to do a bigger job in the background: anything that needs many steps, like deep research with sources, comparing several options, writing a long piece, checking many pages, or a long chain of phone actions. The bot plans, works on its own, and its report lands in this chat when it's done; the chat stays free meanwhile. Don't use it for quick things you can do in a step or two.",
                JSONObject().put("goal", str("The job in one clear sentence, e.g. 'Find the best budget phone under $300 for photos, with sources'"))
                    .put("details", str("Everything the bot should know: what the user wants, preferences, limits, what to include in the report")), listOf("goal")))
            put(fn("wait", "Wait for something to load (an answer being written, a page loading). Returns the new screen.",
                JSONObject().put("seconds", int("1 to 15")), listOf("seconds")))
            // calendar, contacts, location and the Clock app (PhoneData.kt)
            PhoneData.declarations.forEach { put(it) }
        }
    }

    private val whenFmts = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss").map { DateTimeFormatter.ofPattern(it) }

    /** "2026-11-12 16:00" (the phone's local time) to epoch ms; null when missing or unreadable. */
    fun parseWhen(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in whenFmts) {
            try { return LocalDateTime.parse(t, f).atZone(Engine.zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        }
        return null
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
        return try {
            when (c.name) {
                "set_reminder" -> withContext(Dispatchers.IO) {
                    val title = Plain.memory(a.optString("title"))
                    if (title.isEmpty()) return@withContext Result("Error: a title is needed.")
                    val whenRaw = a.optString("when").trim()
                    val at = parseWhen(whenRaw)
                    if (whenRaw.isNotEmpty() && at == null) return@withContext Result("Error: couldn't read the time '$whenRaw'. Use 'YYYY-MM-DD HH:MM'.")
                    // a time already gone would ring at once: the model should recheck the date or ask the user
                    if (at != null && at < now - Engine.MIN) return@withContext Result("Error: $whenRaw has already passed (it's ${Instant.ofEpochMilli(now).atZone(Engine.zone).toLocalDateTime().format(whenFmts[0])} now). Use the next time it comes round, or ask the user.")
                    val rep = a.optString("repeat", "none").ifBlank { "none" }
                    val alarm = a.optString("alert") == "alarm"
                    val t = Store.edit(ctx) { Engine.create(it, title, now, at, a.optInt("duration_min", 30), Plain.memory(a.optString("notes", "")), if (alarm) "high" else "normal", rep) }
                    Reminders.tickAndSchedule(ctx)
                    val whenTxt = t.startAt?.let { fmtWhen(it) } ?: "with no time yet"
                    Result("Logged reminder [${t.id}] '${t.title}' ${whenTxt}${if (t.recurrence != "none") ", repeats ${t.recurrence}" else ""}; at its time: ${if (alarm) "an alarm" else "a notification with a sound"}.",
                        "Logged “${t.title}”, $whenTxt")
                }
                "update_reminder" -> withContext(Dispatchers.IO) {
                    val id = a.optString("id")
                    val title = Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == id }?.title } ?: return@withContext Result("Error: no reminder with id $id.")
                    when (a.optString("action")) {
                        "done" -> { Store.edit(ctx) { Engine.respond(it, id, "done", now) }; AvatarService.celebrate(ctx) }
                        "cancel" -> Store.edit(ctx) { Engine.cancel(it, id, now) }
                        "delete" -> Store.edit(ctx) { Engine.delete(it, id, now) }
                        "move" -> {
                            val at = parseWhen(a.optString("when")) ?: return@withContext Result("Error: give the new time as 'YYYY-MM-DD HH:MM'.")
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
                    Result("Done: $act on '$title'.", when (act) { "done" -> "Marked “$title” done"; "cancel" -> "Cancelled “$title”"; "delete" -> "Deleted “$title”"; "move" -> "Moved “$title”"; "make_alarm" -> "“$title” will ring as an alarm"; "make_notification" -> "“$title” will come as a notification"; else -> "Renamed “$title”" })
                }
                "read_log" -> withContext(Dispatchers.IO) { Result(readLog(ctx, a.optString("query"), a.optInt("limit", 30).coerceIn(1, 200))) }
                "remember" -> withContext(Dispatchers.IO) {
                    val txt = Plain.memory(a.optString("text"))
                    if (txt.isEmpty()) Result("Error: nothing to remember.") else {
                        val m = Store.edit(ctx) { Engine.remember(it, txt, now) }; ModelManager.markStale()
                        Result("Saved memory [${m.id}].", "Remembered “$txt”")
                    }
                }
                "forget" -> withContext(Dispatchers.IO) {
                    val id = a.optString("id")
                    val txt = Store.read(ctx) { s -> s.memories.firstOrNull { it.id == id }?.text } ?: return@withContext Result("Error: no memory with id $id.")
                    Store.edit(ctx) { Engine.forget(it, id, now) }; ModelManager.markStale()
                    Result("Forgotten.", "Forgot “$txt”")
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
                    val mins = a.optInt("minutes", 0).takeIf { it > 0 }
                    Focus.start(ctx, title, mins, a.optString("id").takeIf { it.isNotBlank() })
                    Result("Focus session started for '$title'${mins?.let { " ($it min)" } ?: ""}. The focus screen is open.", "Started a focus session: $title")
                }
                "read_screen" -> withContext(Dispatchers.Default) { Result(PhoneControl.readScreen(), null, phone = true) }
                "tap" -> withContext(Dispatchers.Default) { PhoneControl.tap(a.optInt("element", -1), long = false) }
                "long_press" -> withContext(Dispatchers.Default) { PhoneControl.tap(a.optInt("element", -1), long = true) }
                "type_text" -> withContext(Dispatchers.Default) { PhoneControl.type(if (a.has("element")) a.optInt("element", -1) else -1, a.optString("text"), a.optBoolean("press_enter", false)) }
                "scroll" -> withContext(Dispatchers.Default) { PhoneControl.scroll(a.optString("direction", "down"), if (a.has("element")) a.optInt("element", -1) else -1) }
                "press_key" -> withContext(Dispatchers.Default) { PhoneControl.key(a.optString("key")) }
                "wait" -> {
                    val s = a.optInt("seconds", 2).coerceIn(1, 15)
                    delay(s * 1000L)
                    withContext(Dispatchers.Default) { Result(PhoneControl.readScreen(), null, phone = true) }
                }
                "deploy_bot" -> withContext(Dispatchers.Main) {
                    val goal = a.optString("goal").trim()
                    if (goal.isEmpty()) return@withContext Result("Error: give the bot a goal.")
                    val j = Bots.deploy(ctx, goal, a.optString("details"))
                    Result("Atlas Bot is on it: '${j.goal}'. It works in the background; its report will appear in this chat when it's done, and the user can follow it from the Atlas Bot strip at the top of the chat. Tell them briefly, in your own words.",
                        "Sent Atlas Bot: ${j.goal}")
                }
                in PhoneData.names -> PhoneData.run(ctx, c.name, a)
                else -> Result("Error: there's no tool called ${c.name}.")
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
