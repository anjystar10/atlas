package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * The phone's own calendar, contacts, location, alarm clock and timer, as tools for the chat and for bots.
 * Each asks for its Android permission the first time it's needed: on screen when Atlas is open, otherwise with a
 * notification (a bot gets a plain message and carries on). Atlas's own reminders stay the main reminder system.
 */
object PhoneData {
    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject =
        JSONObject().put("name", name).put("description", desc).apply {
            if (props.length() > 0) put("parameters", JSONObject().put("type", "object").put("properties", props).apply {
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }
    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc).apply { if (enum != null) put("enum", JSONArray(enum)) }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    val names = setOf("calendar_events", "calendar_add", "calendar_change", "find_contact", "get_location", "phone_alarm", "phone_timer")

    val declarations: List<JSONObject> by lazy {
        listOf(
            fn("calendar_events", "Read events from the phone's calendar (Google Calendar etc.) between two times. Includes each event's id for calendar_change.",
                JSONObject().put("from", str("Start, local 'YYYY-MM-DD HH:MM' (default: now)"))
                    .put("to", str("End, local 'YYYY-MM-DD HH:MM' (default: 7 days after from)"))
                    .put("query", str("Only events whose title, place or notes contain these words"))),
            fn("calendar_add", "Add an event to the phone's calendar. For things Atlas should remind them about, use set_reminder instead, unless they ask for their calendar.",
                JSONObject().put("title", str("Event title"))
                    .put("start", str("Local 'YYYY-MM-DD HH:MM'; for an all-day event 'YYYY-MM-DD'"))
                    .put("end", str("Local 'YYYY-MM-DD HH:MM' (default: start + duration_min)"))
                    .put("duration_min", int("Length in minutes if no end (default 60)"))
                    .put("all_day", bool("An all-day event"))
                    .put("location", str("Where"))
                    .put("notes", str("Details")), listOf("title", "start")),
            fn("calendar_change", "Change or delete one event in the phone's calendar by its id from calendar_events. Repeating events can't be changed here.",
                JSONObject().put("event_id", str("The event's id"))
                    .put("action", str("What to do", listOf("update", "delete")))
                    .put("title", str("New title")).put("start", str("New start 'YYYY-MM-DD HH:MM'")).put("end", str("New end 'YYYY-MM-DD HH:MM'"))
                    .put("location", str("New place")).put("notes", str("New details")), listOf("event_id", "action")),
            fn("find_contact", "Look up someone in the phone's contacts by name: their numbers and email addresses.",
                JSONObject().put("name", str("The name, or part of it")), listOf("name")),
            fn("get_location", "Where the phone is now: coordinates, accuracy and the nearest address.",
                JSONObject().put("precise", bool("Ask for a precise fix (GPS) instead of an approximate one. Only when it matters, e.g. directions."))),
            fn("phone_alarm", "Set an alarm in the phone's own Clock app (only when they ask for the phone's alarm; Atlas's set_reminder is the usual way). It can't list or cancel alarms.",
                JSONObject().put("time", str("Local time 'HH:MM' (24h)"))
                    .put("label", str("What it's for"))
                    .put("days", str("Repeat on these days, e.g. 'mon,tue,wed,thu,fri' (leave out for once)")), listOf("time")),
            fn("phone_timer", "Start a countdown timer in the phone's Clock app.",
                JSONObject().put("seconds", int("Length in seconds")).put("label", str("What it's for")), listOf("seconds")),
        )
    }

    /** For bots: what needs the user's OK first (null: go ahead). */
    fun approval(tool: String, args: JSONObject): String? = when (tool) {
        "calendar_change" -> if (args.optString("action") == "delete") "Delete an event from your calendar" else "Change an event in your calendar"
        else -> null
    }

    suspend fun run(ctx: Context, name: String, a: JSONObject): Tools.Result = when (name) {
        "calendar_events" -> need(ctx, listOf(Manifest.permission.READ_CALENDAR), "read your calendar") ?: io { calendarEvents(ctx, a) }
        "calendar_add" -> need(ctx, listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), "add to your calendar") ?: io { calendarAdd(ctx, a) }
        "calendar_change" -> need(ctx, listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), "change your calendar") ?: io { calendarChange(ctx, a) }
        "find_contact" -> need(ctx, listOf(Manifest.permission.READ_CONTACTS), "look up your contacts") ?: io { findContact(ctx, a.optString("name")) }
        "get_location" -> {
            val precise = a.optBoolean("precise", false)
            val perms = if (precise) listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            need(ctx, perms, "see where the phone is") ?: location(ctx, precise)
        }
        "phone_alarm" -> withContext(Dispatchers.Main) { alarm(ctx, a) }
        "phone_timer" -> withContext(Dispatchers.Main) { timer(ctx, a) }
        else -> Tools.Result("Error: there's no tool called $name.")
    }

    private suspend fun io(block: () -> Tools.Result) = withContext(Dispatchers.IO) { block() }

    // ---------------------------------------------------------------- permissions

    private const val CH_ACCESS = "atlas_access"
    private var pending: CompletableDeferred<Boolean>? = null

    /** True while one of Atlas's screens is in front (so asking on screen is allowed and makes sense). */
    private fun onScreen(): Boolean = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance ==
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    fun granted(ctx: Context, perms: List<String>) = perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Null when Atlas has [perms]; otherwise asks for them (on screen if Atlas is open, waiting up to a minute for the
     * answer; else by notification) and returns the result to give the model when they weren't granted.
     */
    private suspend fun need(ctx: Context, perms: List<String>, what: String): Tools.Result? {
        if (granted(ctx, perms)) return null
        if (onScreen()) {
            val d = CompletableDeferred<Boolean>()
            pending?.complete(false); pending = d
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(ctx, PermissionActivity::class.java).putExtra(PermissionActivity.EXTRA_PERMS, perms.toTypedArray())
                    .putExtra(PermissionActivity.EXTRA_WHY, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val ok = withTimeoutOrNull(60_000) { d.await() } ?: false
            if (ok && granted(ctx, perms)) return null
            return Tools.Result("Error: they didn't allow Atlas to $what. Tell them it can be turned on in Android Settings → Apps → Atlas → Permissions if they change their mind.")
        }
        notifyAsk(ctx, perms, what)
        return Tools.Result("Error: Atlas isn't allowed to $what yet. A notification asks them to allow it; carry on without it or ask them to try again after allowing.")
    }

    internal fun answered(ok: Boolean) { pending?.complete(ok); pending = null }

    private fun notifyAsk(ctx: Context, perms: List<String>, what: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ACCESS, "Permission requests", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 7301 + what.hashCode() % 50, Intent(ctx, PermissionActivity::class.java)
            .putExtra(PermissionActivity.EXTRA_PERMS, perms.toTypedArray()).putExtra(PermissionActivity.EXTRA_WHY, what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CH_ACCESS).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Atlas needs your OK").setContentText("Allow Atlas to $what").setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(7300 + what.hashCode() % 50, n) } catch (_: SecurityException) {}
    }

    // ---------------------------------------------------------------- calendar

    private val stamp = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(stamp)
    private fun dayStart(s: String): Long? = try { java.time.LocalDate.parse(s.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: Exception) { null }

    private fun calendarEvents(ctx: Context, a: JSONObject): Tools.Result {
        val now = System.currentTimeMillis()
        val from = a.optString("from").takeIf { it.isNotBlank() }?.let { Tools.parseWhen(it) ?: dayStart(it) ?: return Tools.Result("Error: couldn't read the time '$it'. Use 'YYYY-MM-DD HH:MM'.") } ?: now
        val to = a.optString("to").takeIf { it.isNotBlank() }?.let { Tools.parseWhen(it) ?: dayStart(it)?.plus(86_400_000L) ?: return Tools.Result("Error: couldn't read the time '$it'.") } ?: (from + 7 * 86_400_000L)
        if (to <= from) return Tools.Result("Error: 'to' must be after 'from'.")
        if (to - from > 366L * 86_400_000L) return Tools.Result("Error: ask for at most a year at a time.")
        val q = a.optString("query").trim().lowercase()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val lines = mutableListOf<String>()
        var total = 0
        ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1).orEmpty(); val place = c.getString(5).orEmpty(); val notes = c.getString(6).orEmpty()
                if (q.isNotEmpty() && q.split(' ').filter { it.length > 1 }.any { w -> w !in "$title $place $notes".lowercase() }) continue
                total++
                if (lines.size >= 50) continue
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(2); val end = c.getLong(3)
                val time = if (allDay) Instant.ofEpochMilli(begin).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() + " (all day)" else "${local(begin)} – ${Reminders.fmt(end)}"
                lines.add("[${c.getLong(0)}] ${title.ifBlank { "(no title)" }} — $time" + (if (place.isNotBlank()) " — at ${place.take(80)}" else "") +
                    (if (!c.getString(7).isNullOrBlank()) " — repeats" else "") + " — ${c.getString(8).orEmpty()}" + (if (notes.isNotBlank()) " — notes: ${notes.take(160).replace('\n', ' ')}" else ""))
            }
        } ?: return Tools.Result("Error: the phone's calendar couldn't be read.")
        val span = "${local(from)} to ${local(to)}"
        return if (lines.isEmpty()) Tools.Result("No calendar events from $span${if (q.isNotEmpty()) " matching \"$q\"" else ""}.", "Checked your calendar")
        else Tools.Result("Calendar events from $span (${lines.size}${if (total > lines.size) " of $total" else ""}):\n" + lines.joinToString("\n"), "Checked your calendar")
    }

    /** The calendar new events go into: the primary one Atlas may write to, else the first writable visible one. */
    private fun writableCalendar(ctx: Context): Pair<Long, String>? {
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE)
        var best: Pair<Long, String>? = null
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(4) != 1) continue
                val cal = c.getLong(0) to c.getString(1).orEmpty()
                if (c.getInt(2) == 1) return cal
                if (best == null) best = cal
            }
        }
        return best
    }

    private fun calendarAdd(ctx: Context, a: JSONObject): Tools.Result {
        val title = Plain.memory(a.optString("title")).ifBlank { return Tools.Result("Error: a title is needed.") }
        val cal = writableCalendar(ctx) ?: return Tools.Result("Error: there's no calendar on the phone Atlas can add to (sign in to Google Calendar or another calendar app first).")
        val allDay = a.optBoolean("all_day", false) || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(a.optString("start").trim())
        val v = ContentValues()
        if (allDay) {
            val d = try { java.time.LocalDate.parse(a.optString("start").trim().take(10)) } catch (_: Exception) { return Tools.Result("Error: couldn't read the date '${a.optString("start")}'.") }
            val s = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            v.put(CalendarContract.Events.DTSTART, s); v.put(CalendarContract.Events.DTEND, s + 86_400_000L)
            v.put(CalendarContract.Events.ALL_DAY, 1); v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            val start = Tools.parseWhen(a.optString("start")) ?: return Tools.Result("Error: couldn't read the start '${a.optString("start")}'. Use 'YYYY-MM-DD HH:MM'.")
            val end = a.optString("end").takeIf { it.isNotBlank() }?.let { Tools.parseWhen(it) ?: return Tools.Result("Error: couldn't read the end '$it'.") }
                ?: (start + a.optInt("duration_min", 60).coerceIn(1, 7 * 24 * 60) * 60_000L)
            if (end <= start) return Tools.Result("Error: the end must be after the start.")
            v.put(CalendarContract.Events.DTSTART, start); v.put(CalendarContract.Events.DTEND, end)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.CALENDAR_ID, cal.first)
        a.optString("location").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it.take(300)) }
        a.optString("notes").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.DESCRIPTION, it.take(4000)) }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return Tools.Result("Error: the calendar didn't accept the event.")
        val id = ContentUris.parseId(uri)
        return Tools.Result("Added to the ${cal.second} calendar: '$title' (event id $id).", "Added “${title.take(50)}” to your calendar")
    }

    private fun calendarChange(ctx: Context, a: JSONObject): Tools.Result {
        val id = a.optString("event_id").trim().toLongOrNull() ?: return Tools.Result("Error: give the event's id from calendar_events.")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        var title = ""; var recurring = false; var oldStart = 0L; var oldEnd = 0L
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.RRULE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DELETED), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(4) == 1) return Tools.Result("Error: there's no event with id $id.")
            title = c.getString(0).orEmpty(); recurring = !c.getString(1).isNullOrBlank(); oldStart = c.getLong(2); oldEnd = if (c.isNull(3)) 0L else c.getLong(3)
        } ?: return Tools.Result("Error: the calendar couldn't be read.")
        if (recurring) return Tools.Result("Error: '$title' repeats; Atlas can't change repeating events here. They can change it in their calendar app.")
        return when (a.optString("action")) {
            "delete" -> {
                val n = ctx.contentResolver.delete(uri, null, null)
                if (n > 0) Tools.Result("Deleted '$title' from the calendar.", "Deleted “${title.take(50)}” from your calendar") else Tools.Result("Error: the calendar didn't delete it.")
            }
            "update" -> {
                val v = ContentValues()
                a.optString("title").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.TITLE, Plain.memory(it)) }
                a.optString("location").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it.take(300)) }
                a.optString("notes").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.DESCRIPTION, it.take(4000)) }
                val start = a.optString("start").takeIf { it.isNotBlank() }?.let { Tools.parseWhen(it) ?: return Tools.Result("Error: couldn't read the start '$it'.") }
                val end = a.optString("end").takeIf { it.isNotBlank() }?.let { Tools.parseWhen(it) ?: return Tools.Result("Error: couldn't read the end '$it'.") }
                if (start != null || end != null) {
                    val s = start ?: oldStart
                    val e = end ?: (if (oldEnd > oldStart) s + (oldEnd - oldStart) else s + 3_600_000L)
                    if (e <= s) return Tools.Result("Error: the end must be after the start.")
                    v.put(CalendarContract.Events.DTSTART, s); v.put(CalendarContract.Events.DTEND, e)
                }
                if (v.size() == 0) return Tools.Result("Error: say what to change (title, start, end, location or notes).")
                val n = ctx.contentResolver.update(uri, v, null, null)
                if (n > 0) Tools.Result("Changed '$title' in the calendar.", "Changed “${title.take(50)}” in your calendar") else Tools.Result("Error: the calendar didn't take the change.")
            }
            else -> Tools.Result("Error: action is update or delete.")
        }
    }

    // ---------------------------------------------------------------- contacts

    private fun findContact(ctx: Context, name: String): Tools.Result {
        val q = name.trim()
        if (q.length < 2) return Tools.Result("Error: give at least two letters of the name.")
        val people = linkedMapOf<Long, Pair<String, MutableList<String>>>()
        ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%${q.replace("%", "").replace("_", "")}%"), "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC")?.use { c ->
            while (c.moveToNext() && people.size < 8) people[c.getLong(0)] = (c.getString(1).orEmpty()) to mutableListOf()
        }
        if (people.isEmpty()) return Tools.Result("No contact matches \"$q\".", "Looked in your contacts")
        val ids = people.keys.joinToString(",")
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($ids)", null, null)?.use { c ->
            while (c.moveToNext()) {
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), "").toString().lowercase()
                people[c.getLong(0)]?.second?.let { l -> val s = "phone (${label}): ${c.getString(1)}"; if (s !in l && l.size < 8) l.add(s) }
            }
        }
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($ids)", null, null)?.use { c ->
            while (c.moveToNext()) people[c.getLong(0)]?.second?.let { l -> val s = "email: ${c.getString(1)}"; if (s !in l && l.size < 10) l.add(s) }
        }
        return Tools.Result("Contacts matching \"$q\":\n" + people.values.joinToString("\n") { (n, d) -> "- $n" + (if (d.isEmpty()) " (no number or email)" else ": " + d.joinToString("; ")) },
            "Looked up “${q.take(30)}” in your contacts")
    }

    // ---------------------------------------------------------------- location

    private suspend fun location(ctx: Context, precise: Boolean): Tools.Result {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) return Tools.Result("Error: location is turned off on the phone (Quick Settings → Location).")
        val providers = buildList {
            if (precise && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        }
        var fix: Location? = null
        try {
            for (p in providers) {
                fix = withTimeoutOrNull(if (p == LocationManager.GPS_PROVIDER) 20_000L else 10_000L) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val sig = android.os.CancellationSignal()
                        cont.invokeOnCancellation { sig.cancel() }
                        lm.getCurrentLocation(p, sig, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    }
                }
                if (fix != null) break
            }
            if (fix == null) fix = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) { return Tools.Result("Error: Atlas isn't allowed to see the phone's location.") }
        val f = fix ?: return Tools.Result("Error: the phone couldn't get a location fix right now (try again outdoors or with Wi-Fi on).")
        val age = (System.currentTimeMillis() - f.time) / 60_000
        val address = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    if (!Geocoder.isPresent()) { cont.resume(null); return@suspendCancellableCoroutine }
                    Geocoder(ctx).getFromLocation(f.latitude, f.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(list: MutableList<android.location.Address>) { if (cont.isActive) cont.resume(list.firstOrNull()?.let { ad -> (0..ad.maxAddressLineIndex).joinToString(", ") { ad.getAddressLine(it) } }) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
        return Tools.Result("Location: %.5f, %.5f (accurate to about %d m%s)%s".format(java.util.Locale.US, f.latitude, f.longitude, f.accuracy.toInt(),
            if (age >= 2) ", $age min old" else "", address?.let { "\nNearest address: $it" } ?: ""), "Checked where you are")
    }

    // ---------------------------------------------------------------- the Clock app

    private val dayCodes = mapOf("sun" to java.util.Calendar.SUNDAY, "mon" to java.util.Calendar.MONDAY, "tue" to java.util.Calendar.TUESDAY, "wed" to java.util.Calendar.WEDNESDAY,
        "thu" to java.util.Calendar.THURSDAY, "fri" to java.util.Calendar.FRIDAY, "sat" to java.util.Calendar.SATURDAY)

    internal fun parseDays(s: String): ArrayList<Int>? {
        if (s.isBlank()) return null
        val out = ArrayList<Int>()
        for (p in s.lowercase().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            when (p) {
                "weekdays" -> out.addAll(listOf(2, 3, 4, 5, 6))
                "weekends" -> out.addAll(listOf(1, 7))
                "daily", "everyday", "every day" -> out.addAll(1..7)
                else -> out.add(dayCodes[p.take(3)] ?: return null)
            }
        }
        return ArrayList(out.distinct().sorted())
    }

    private fun clock(ctx: Context, i: Intent, done: String, step: String): Tools.Result {
        if (i.resolveActivity(ctx.packageManager) == null) return Tools.Result("Error: there's no clock app on the phone that takes this.")
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Tools.Result(done, step)
        } catch (e: Exception) { Tools.Result("Error: the clock app didn't take it (${e.javaClass.simpleName}). Atlas's own set_reminder can do it instead.") }
    }

    private fun alarm(ctx: Context, a: JSONObject): Tools.Result {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(a.optString("time").trim()) ?: return Tools.Result("Error: give the time as 'HH:MM' (24h).")
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return Tools.Result("Error: that isn't a real time.")
        val days = parseDays(a.optString("days")) ?: if (a.optString("days").isNotBlank()) return Tools.Result("Error: couldn't read the days '${a.optString("days")}'. Use e.g. 'mon,wed,fri'.") else null
        val label = a.optString("label").trim().take(60)
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label); if (!days.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days) }
        val t = "%02d:%02d".format(h, min)
        return clock(ctx, i, "Set the phone's alarm for $t${if (label.isNotEmpty()) " ($label)" else ""}${if (!days.isNullOrEmpty()) ", repeating" else ""}.", "Set a phone alarm for $t")
    }

    private fun timer(ctx: Context, a: JSONObject): Tools.Result {
        val s = a.optInt("seconds", 0)
        if (s !in 1..86_399) return Tools.Result("Error: a timer is 1 second to 24 hours.")
        val label = a.optString("label").trim().take(60)
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
        val span = if (s >= 3600) "${s / 3600} h ${s % 3600 / 60} min" else if (s >= 60) "${s / 60} min${if (s % 60 > 0) " ${s % 60} s" else ""}" else "$s s"
        return clock(ctx, i, "Started a $span timer${if (label.isNotEmpty()) " ($label)" else ""}.", "Started a $span timer")
    }
}

/** Asks Android for permissions on Atlas's behalf, then closes; the answer goes back to the waiting tool. */
class PermissionActivity : Activity() {
    companion object { const val EXTRA_PERMS = "perms"; const val EXTRA_WHY = "why" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra(EXTRA_PERMS)
        if (perms.isNullOrEmpty()) { PhoneData.answered(false); finish(); return }
        if (savedInstanceState == null) requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PhoneData.answered(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        finish()
    }

    override fun onDestroy() { if (isFinishing) PhoneData.answered(false); super.onDestroy() }
}
