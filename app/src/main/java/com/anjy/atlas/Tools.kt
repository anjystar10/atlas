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
            put(fn("set_reminder", "Log a reminder or task the user wants to be reminded about. Atlas notifies them at the time and checks in afterwards.",
                JSONObject().put("title", str("Short title, e.g. 'Pick up my sister'"))
                    .put("when", str("Local date and time as 'YYYY-MM-DD HH:MM' (24h). Leave out only if the user gave no time at all."))
                    .put("duration_min", int("How long it takes, in minutes (default 30)"))
                    .put("repeat", str("How it repeats", listOf("none", "daily", "weekdays", "weekly")))
                    .put("notes", str("Optional details")), listOf("title")))
            put(fn("update_reminder", "Change a logged reminder: mark it done, cancel it, delete it, rename it or move it.",
                JSONObject().put("id", str("The reminder's id from the list in your instructions"))
                    .put("action", str("What to do", listOf("done", "cancel", "delete", "move", "rename")))
                    .put("when", str("For move: new local date and time 'YYYY-MM-DD HH:MM'"))
                    .put("title", str("For rename: the new title")), listOf("id", "action")))
            put(fn("remember", "Save a lasting fact or preference the user wants Atlas to keep (shown in the Log).",
                JSONObject().put("text", str("The fact, in a short sentence")), listOf("text")))
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
            put(fn("wait", "Wait for something to load (an answer being written, a page loading). Returns the new screen.",
                JSONObject().put("seconds", int("1 to 15")), listOf("seconds")))
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

    suspend fun run(ctx: Context, c: Gemini.Call): Result {
        val a = c.args
        val now = System.currentTimeMillis()
        return try {
            when (c.name) {
                "set_reminder" -> withContext(Dispatchers.IO) {
                    val title = a.optString("title").trim()
                    if (title.isEmpty()) return@withContext Result("Error: a title is needed.")
                    val whenRaw = a.optString("when").trim()
                    val at = parseWhen(whenRaw)
                    if (whenRaw.isNotEmpty() && at == null) return@withContext Result("Error: couldn't read the time '$whenRaw'. Use 'YYYY-MM-DD HH:MM'.")
                    val rep = a.optString("repeat", "none").ifBlank { "none" }
                    val t = Store.edit(ctx) { Engine.create(it, title, now, at, a.optInt("duration_min", 30), a.optString("notes", ""), "normal", rep) }
                    Reminders.tickAndSchedule(ctx)
                    val whenTxt = t.startAt?.let { fmtWhen(it) } ?: "with no time yet"
                    Result("Logged reminder [${t.id}] '${t.title}' ${whenTxt}${if (t.recurrence != "none") ", repeats ${t.recurrence}" else ""}.",
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
                            val nt = a.optString("title").trim().ifEmpty { return@withContext Result("Error: give the new title.") }
                            Store.edit(ctx) { s -> val t = Engine.find(s, id); Engine.update(s, id, nt, now, t.startAt, t.durationMin, t.notes, t.priority, t.recurrence) }
                        }
                        else -> return@withContext Result("Error: unknown action.")
                    }
                    Reminders.tickAndSchedule(ctx)
                    val act = a.optString("action")
                    Result("Done: $act on '$title'.", when (act) { "done" -> "Marked “$title” done"; "cancel" -> "Cancelled “$title”"; "delete" -> "Deleted “$title”"; "move" -> "Moved “$title”"; else -> "Renamed “$title”" })
                }
                "remember" -> withContext(Dispatchers.IO) {
                    val txt = a.optString("text").trim()
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
                    val hit = Brain.findApp(ctx, name) ?: return Result("No app called '$name' is installed. Installed apps include: ${appNames(ctx)}")
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
