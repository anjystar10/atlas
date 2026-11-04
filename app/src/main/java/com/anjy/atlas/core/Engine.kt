package com.anjy.atlas.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/*
 * Atlas core engine: deterministic tasks, reminders, check-ins and memory.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM in CI.
 * Port of the tested web engine (v0.1), same rules.
 */

class Settings(
    var name: String = "",
    var tone: String = "firm",
    var quietStart: String = "22:30",
    var quietEnd: String = "06:30",
    var graceMin: Int = 15,
    var followUps: Int = 1,
    var sound: Boolean = true,
    var avatarOn: Boolean = true,
    var speakReplies: Boolean = false,
    var theme: String = "dark"
)

class Task(
    var id: String,
    var title: String,
    var notes: String = "",
    var priority: String = "normal",
    var startAt: Long? = null,
    var durationMin: Int = 30,
    var recurrence: String = "none",
    var status: String = "planned",
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var version: Int = 1,
    var deviceId: String = "",
    var completedAt: Long? = null,
    var blockedReason: String = "",
    var skipReason: String = "",
    var seriesId: String? = null,
    var remindedAt: Long? = null,
    var nextCheckAt: Long? = null,
    var promptedAt: Long? = null,
    var followUpsSent: Int = 0
)

class LogEntry(val id: String, val at: Long, val type: String, val taskId: String?, val title: String, val detail: String)

class Memory(val id: String, var text: String, val createdAt: Long)

class AtlasState(val deviceId: String = UUID.randomUUID().toString()) {
    val tasks = mutableListOf<Task>()
    val log = mutableListOf<LogEntry>()
    val memories = mutableListOf<Memory>()
    var settings = Settings()
}

data class Event(val type: String, val task: Task, val late: Boolean, val quiet: Boolean)

class AtlasException(message: String) : Exception(message)

data class Parsed(val title: String, val startAt: Long?, val durationMin: Int?)

object Engine {
    const val MIN = 60_000L
    val OPEN = setOf("planned", "active", "awaiting", "blocked")
    val RECURRENCES = listOf("none", "daily", "weekdays", "weekly")

    /** Time zone used for wall-clock rules (quiet hours, recurrence, parsing). Tests may override. */
    var zone: ZoneId = ZoneId.systemDefault()

    fun uid(): String = UUID.randomUUID().toString()

    fun clampDur(v: Int?): Int = if (v == null || v <= 0) 30 else v.coerceIn(5, 720)

    private fun log(s: AtlasState, now: Long, type: String, t: Task?, detail: String = "") {
        s.log.add(LogEntry(uid(), now, type, t?.id, t?.title ?: "", detail))
        if (s.log.size > 500) s.log.subList(0, s.log.size - 500).clear()
    }

    private fun touch(s: AtlasState, t: Task, now: Long) {
        t.updatedAt = now; t.version += 1; t.deviceId = s.deviceId
    }

    private fun resetRem(t: Task) {
        t.remindedAt = null; t.nextCheckAt = null; t.promptedAt = null; t.followUpsSent = 0
    }

    fun find(s: AtlasState, id: String): Task =
        s.tasks.firstOrNull { it.id == id } ?: throw AtlasException("Task not found.")

    fun create(
        s: AtlasState, title: String, now: Long, startAt: Long? = null, durationMin: Int? = 30,
        notes: String = "", priority: String = "normal", recurrence: String = "none", seriesId: String? = null
    ): Task {
        val clean = title.trim()
        if (clean.isEmpty()) throw AtlasException("Give the task a title.")
        val t = Task(
            id = uid(), title = clean, notes = notes, priority = priority, startAt = startAt,
            durationMin = clampDur(durationMin),
            recurrence = if (startAt != null && recurrence in RECURRENCES) recurrence else "none",
            createdAt = now, updatedAt = now, deviceId = s.deviceId, seriesId = seriesId
        )
        s.tasks.add(t)
        log(s, now, "created", t)
        return t
    }

    fun update(
        s: AtlasState, id: String, title: String, now: Long, startAt: Long?, durationMin: Int?,
        notes: String, priority: String, recurrence: String
    ): Task {
        val t = find(s, id)
        val clean = title.trim()
        if (clean.isEmpty()) throw AtlasException("Give the task a title.")
        val dur = clampDur(durationMin)
        val timeChanged = startAt != t.startAt || dur != t.durationMin
        t.title = clean; t.notes = notes; t.priority = priority; t.durationMin = dur; t.startAt = startAt
        t.recurrence = if (startAt != null && recurrence in RECURRENCES) recurrence else "none"
        if (timeChanged && t.status in setOf("planned", "active", "awaiting")) {
            t.status = "planned"; resetRem(t)
        }
        touch(s, t, now)
        log(s, now, "edited", t)
        return t
    }

    fun delete(s: AtlasState, id: String, now: Long) {
        val t = find(s, id)
        s.tasks.remove(t)
        log(s, now, "deleted", t)
    }

    fun cancel(s: AtlasState, id: String, now: Long): Task {
        val t = find(s, id)
        t.status = "cancelled"
        touch(s, t, now); log(s, now, "cancelled", t)
        return t
    }

    fun startNow(s: AtlasState, id: String, now: Long): Task {
        val t = find(s, id)
        t.startAt = now; t.status = "active"
        t.remindedAt = now; t.nextCheckAt = now + t.durationMin * MIN; t.promptedAt = null; t.followUpsSent = 0
        touch(s, t, now); log(s, now, "started", t)
        return t
    }

    fun hm(str: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(str.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return h * 60 + mi
    }

    fun inQuiet(st: Settings, now: Long): Boolean {
        val s = hm(st.quietStart) ?: return false
        val e = hm(st.quietEnd) ?: return false
        if (s == e) return false
        val lt = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        val m = lt.hour * 60 + lt.minute
        return if (s < e) m in s until e else (m >= s || m < e)
    }

    /** First moment at or after [t] that is outside quiet hours. */
    fun quietEndAfter(st: Settings, t: Long): Long {
        if (!inQuiet(st, t)) return t
        val e = hm(st.quietEnd) ?: return t
        val z = Instant.ofEpochMilli(t).atZone(zone)
        var cand = z.toLocalDate().atTime(LocalTime.of(e / 60, e % 60)).atZone(zone)
        if (!cand.isAfter(z)) cand = cand.plusDays(1)
        return cand.toInstant().toEpochMilli()
    }

    fun maxFollowUps(s: AtlasState): Int = s.settings.followUps.coerceIn(0, 3)

    fun followUpsExhausted(s: AtlasState, t: Task): Boolean =
        t.status == "awaiting" && t.followUpsSent >= maxFollowUps(s)

    /** Advance the engine to [now]. Returns events for notifications. Safe to call any number of times. */
    fun tick(s: AtlasState, now: Long): List<Event> {
        val events = mutableListOf<Event>()
        val quiet = inQuiet(s.settings, now)
        val grace = s.settings.graceMin.coerceAtLeast(1) * MIN
        val maxF = maxFollowUps(s)
        for (t in s.tasks) {
            val start = t.startAt
            if (t.status == "planned" && start != null && t.remindedAt == null && now >= start) {
                t.status = "active"
                t.remindedAt = now
                t.nextCheckAt = maxOf(start + t.durationMin * MIN, now)
                touch(s, t, now)
                val late = now - start > 10 * MIN
                log(s, now, "reminded", t, if (late) "late" else "")
                events.add(Event("remind", t, late, quiet))
            }
            val check = t.nextCheckAt
            val prompted = t.promptedAt
            if (t.status == "active" && check != null && now >= check) {
                t.status = "awaiting"
                t.promptedAt = now
                t.followUpsSent = 0
                touch(s, t, now)
                log(s, now, "checkin", t)
                events.add(Event("checkin", t, false, quiet))
            } else if (t.status == "awaiting" && !quiet && prompted != null &&
                t.followUpsSent < maxF && now >= prompted + grace * (t.followUpsSent + 1)
            ) {
                t.followUpsSent += 1
                touch(s, t, now)
                log(s, now, "followup", t, t.followUpsSent.toString())
                events.add(Event("followup", t, false, quiet))
            }
        }
        return events
    }

    /** Earliest future moment the engine has something to do, or null. Used to set the single system alarm. */
    fun nextWake(s: AtlasState, now: Long): Long? {
        val grace = s.settings.graceMin.coerceAtLeast(1) * MIN
        val maxF = maxFollowUps(s)
        var best: Long? = null
        fun consider(x: Long) { val v = maxOf(x, now + 1000); if (best == null || v < best!!) best = v }
        for (t in s.tasks) {
            val start = t.startAt
            val check = t.nextCheckAt
            val prompted = t.promptedAt
            when {
                t.status == "planned" && start != null && t.remindedAt == null -> consider(start)
                t.status == "active" && check != null -> consider(check)
                t.status == "awaiting" && prompted != null && t.followUpsSent < maxF ->
                    consider(quietEndAfter(s.settings, prompted + grace * (t.followUpsSent + 1)))
            }
        }
        return best
    }

    fun nextOcc(ms: Long, rec: String): Long? {
        var d = Instant.ofEpochMilli(ms).atZone(zone)
        d = when (rec) {
            "daily" -> d.plusDays(1)
            "weekly" -> d.plusDays(7)
            "weekdays" -> {
                var x = d.plusDays(1)
                while (x.dayOfWeek.value >= 6) x = x.plusDays(1)
                x
            }
            else -> return null
        }
        return d.toInstant().toEpochMilli()
    }

    private fun spawnNext(s: AtlasState, t: Task, now: Long): Task? {
        val start = t.startAt ?: return null
        if (t.recurrence == "none") return null
        var at = nextOcc(start, t.recurrence) ?: return null
        var missed = 0
        while (at <= now && missed < 1000) { at = nextOcc(at, t.recurrence) ?: return null; missed++ }
        val n = create(s, t.title, now, at, t.durationMin, t.notes, t.priority, t.recurrence, t.seriesId ?: t.id)
        if (missed > 0) log(s, now, "note", n, "$missed past occurrence${if (missed == 1) "" else "s"} passed while Atlas was off")
        return n
    }

    /** kind: done | progress | delay | blocked | skipped | unblock. Returns the task and any next occurrence. */
    fun respond(
        s: AtlasState, id: String, kind: String, now: Long,
        minutes: Int? = null, startAt: Long? = null, reason: String = ""
    ): Pair<Task, Task?> {
        val t = find(s, id)
        var next: Task? = null
        when (kind) {
            "done" -> {
                t.status = "done"; t.completedAt = now
                touch(s, t, now); log(s, now, "done", t)
                next = spawnNext(s, t, now)
            }
            "progress" -> {
                val m = (minutes ?: 15).coerceIn(5, 240)
                t.status = "active"
                t.nextCheckAt = now + m * MIN; t.promptedAt = null; t.followUpsSent = 0
                if (t.remindedAt == null) t.remindedAt = now
                touch(s, t, now); log(s, now, "progress", t, "check again in $m min")
            }
            "delay", "unblock" -> {
                if (kind == "delay" && startAt == null) throw AtlasException("Pick a new time.")
                if (startAt != null && startAt < now - MIN) throw AtlasException("The new time is in the past.")
                t.startAt = startAt
                if (startAt == null) t.recurrence = "none"
                t.status = "planned"; t.blockedReason = ""
                resetRem(t)
                touch(s, t, now)
                log(s, now, if (kind == "delay") "delayed" else "unblocked", t, startAt?.toString() ?: "unscheduled")
            }
            "blocked" -> {
                t.status = "blocked"; t.blockedReason = reason.trim()
                touch(s, t, now); log(s, now, "blocked", t, t.blockedReason)
            }
            "skipped" -> {
                t.status = "skipped"; t.skipReason = reason.trim()
                touch(s, t, now); log(s, now, "skipped", t, t.skipReason)
                next = spawnNext(s, t, now)
            }
            else -> throw AtlasException("Unknown response.")
        }
        return Pair(t, next)
    }

    fun collisions(s: AtlasState): Set<String> {
        val list = s.tasks.filter { it.status in OPEN && it.status != "blocked" && it.startAt != null }
            .sortedBy { it.startAt }
        val ids = mutableSetOf<String>()
        for (i in list.indices) {
            val endA = list[i].startAt!! + list[i].durationMin * MIN
            var j = i + 1
            while (j < list.size && list[j].startAt!! < endA) { ids.add(list[i].id); ids.add(list[j].id); j++ }
        }
        return ids
    }

    fun remember(s: AtlasState, text: String, now: Long): Memory {
        val clean = text.trim()
        if (clean.isEmpty()) throw AtlasException("Nothing to save.")
        val m = Memory(uid(), clean, now)
        s.memories.add(m)
        log(s, now, "remembered", null, clean)
        return m
    }

    fun forget(s: AtlasState, id: String, now: Long) {
        val m = s.memories.firstOrNull { it.id == id } ?: throw AtlasException("Memory not found.")
        s.memories.remove(m)
        log(s, now, "forgot", null, m.text)
    }

    fun searchMemories(s: AtlasState, query: String): List<Memory> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return s.memories.toList()
        return s.memories.filter { m -> val l = m.text.lowercase(); words.any { l.contains(it) } }
    }

    /** Deterministic quick-add parser: "Gym tomorrow at 6pm for 1h". */
    fun parseQuick(text: String, now: Long): Parsed {
        var s = " $text "
        var durationMin: Int? = null
        var dayOffset: Int? = null
        var hour: Int? = null
        var minute = 0
        var explicitDay = false
        var tonight = false
        Regex("\\sfor\\s+(\\d+(?:\\.\\d+)?)\\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h)(?=\\s)", RegexOption.IGNORE_CASE)
            .find(s)?.let { m ->
                val v = m.groupValues[1].toDouble()
                durationMin = if (m.groupValues[2].lowercase().startsWith("h")) Math.round(v * 60).toInt() else Math.round(v).toInt()
                s = s.replaceFirst(m.value, " ")
            }
        Regex("\\s(today|tomorrow|tmrw|tmr|tonight)(?=\\s)", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val w = m.groupValues[1].lowercase()
            dayOffset = if (w == "today" || w == "tonight") 0 else 1
            tonight = w == "tonight"
            explicitDay = true
            s = s.replaceFirst(m.value, " ")
        }
        Regex("\\sat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?=\\s)", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val mi = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toInt() else 0
            val ap = m.groupValues[3].lowercase().ifEmpty { null }
            var ok = mi <= 59
            if (ap != null) {
                ok = ok && h in 1..12
                h = if (ap == "am") h % 12 else (h % 12) + 12
            } else ok = ok && h <= 23
            if (ok) { hour = h; minute = mi; s = s.replaceFirst(m.value, " ") }
        }
        if (hour == null && tonight) { hour = 20; minute = 0 }
        val title = s.replace(Regex("\\s+"), " ").trim()
        val h = hour
        if (h != null) {
            val nowZ = Instant.ofEpochMilli(now).atZone(zone)
            var d: ZonedDateTime = nowZ.toLocalDate().plusDays((dayOffset ?: 0).toLong())
                .atTime(h, minute).atZone(zone)
            if (!explicitDay && d.toInstant().toEpochMilli() < now - MIN) d = d.plusDays(1)
            return Parsed(title, d.toInstant().toEpochMilli(), durationMin)
        }
        if (explicitDay && dayOffset == 1) {
            // A day without a time stays unscheduled; keep the word in the title so nothing is lost.
            return Parsed(text.replace(Regex("\\s+"), " ").trim(), null, durationMin)
        }
        return Parsed(title, null, durationMin)
    }

    /** Resolve a model tool call's day ("today", "tomorrow", "YYYY-MM-DD") and time ("HH:MM"). */
    fun resolveDayTime(day: String?, time: String?, now: Long): Long? {
        val mins = time?.let { hm(it) } ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date: LocalDate = when (day?.trim()?.lowercase()) {
            null, "", "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> try { LocalDate.parse(day.trim()) } catch (e: Exception) { return null }
        }
        return date.atTime(mins / 60, mins % 60).atZone(zone).toInstant().toEpochMilli()
    }
}
