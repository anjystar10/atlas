package com.anjy.atlas.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Turning "when" into an exact time, the same way as the PC (desktop/renderer/when.js).
 * The model is good at language and bad at calendar arithmetic, so the tools take what the user said
 * ("tomorrow at 6pm", "in 90 minutes", "monday 9am", "2026-11-14 18:00") and this works out the exact
 * moment from the clock and time zone. Unreadable, impossible (31 April, 25:00) or past times are refused
 * with a precise message instead of being guessed. Shared cases: tools/accuracy-fixtures.json.
 *
 * Clock changes follow java.time: a skipped time moves forward by the gap; a repeated time takes the earlier one
 * (the PC copies these rules).
 */
object When {
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L

    sealed class Result {
        data class Ok(val ms: Long, val local: String, val long: String, val relative: String) : Result()
        data class Bad(val error: String) : Result()
    }

    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val SHORT_DAYS = mapOf("sun" to 0, "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5, "sat" to 6)
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun zoned(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
    private fun two(n: Int) = n.toString().padStart(2, '0')
    /** Sunday = 0, like the PC. */
    private fun wd(z: ZonedDateTime) = z.dayOfWeek.value % 7

    fun fmtLocal(ms: Long, zone: ZoneId): String { val z = zoned(ms, zone); return "${z.year}-${two(z.monthValue)}-${two(z.dayOfMonth)} ${two(z.hour)}:${two(z.minute)}" }
    /** "Fri 13 Nov 2026, 18:00": the form tools report back, so the model quotes the stored time, not its own guess. */
    fun fmtLong(ms: Long, zone: ZoneId): String {
        val z = zoned(ms, zone); val w = WEEKDAYS[wd(z)]
        return "${w.substring(0, 1).uppercase()}${w.substring(1, 3)} ${z.dayOfMonth} ${MONTHS[z.monthValue - 1]} ${z.year}, ${two(z.hour)}:${two(z.minute)}"
    }

    fun relativeDay(ms: Long, now: Long, zone: ZoneId): String {
        val diff = ChronoUnit.DAYS.between(zoned(now, zone).toLocalDate(), zoned(ms, zone).toLocalDate()).toInt()
        return when { diff == 0 -> "today"; diff == 1 -> "tomorrow"; diff == -1 -> "yesterday"; diff > 1 -> "in $diff days"; else -> "${-diff} days ago" }
    }

    private fun at(date: LocalDate, h: Int, m: Int, zone: ZoneId): Long = ZonedDateTime.of(date.atTime(h, m), zone).toInstant().toEpochMilli()

    /** "6pm", "6:30 pm", "18:00", "18.30", "0930", "noon", "midnight" → [h, m]; BAD for an impossible time; null when it isn't a time. */
    val BAD = intArrayOf(-1, -1)
    fun parseClock(s: String?): IntArray? {
        val t = (s ?: "").trim().lowercase().replace(Regex("\\s+"), " ")
        if (t == "noon" || t == "midday") return intArrayOf(12, 0)
        if (t == "midnight") return intArrayOf(0, 0)
        Regex("^(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)$").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h < 1 || h > 12 || mi > 59) return BAD
            val pm = m.groupValues[3].startsWith("p")
            h = if (h == 12) (if (pm) 12 else 0) else if (pm) h + 12 else h
            return intArrayOf(h, mi)
        }
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(t) ?: Regex("^(\\d{2})(\\d{2})$").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) BAD else intArrayOf(h, mi)
    }

    /**
     * [text] → Ok(ms, local, long, relative) or Bad(error). Understands "YYYY-MM-DD HH:MM" (also T and seconds),
     * "in 90 minutes" / "in 2 hours" / "in 1h30m", "today 6pm", "tonight at 9", "tomorrow at 6:30pm", "monday 9am",
     * "next friday at noon", and a bare "6pm" (the next 6pm).
     */
    fun resolve(text: String?, now: Long, zone: ZoneId, allowPast: Boolean = false): Result {
        val raw = (text ?: "").trim()
        if (raw.isEmpty()) return Result.Bad("No time was given.")
        val s = raw.lowercase().replace(Regex("\\s+"), " ").replace(",", "").replace(Regex("\\bat\\b "), "").replace(Regex("^on "), "").trim()
        val n = zoned(now, zone); val today = n.toLocalDate()
        var ms: Long? = null

        // 1. exact local date and time
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ t](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$").find(s)?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            if (m.groupValues[4].isEmpty()) return Result.Bad("“$raw” has a date but no time. Ask the user what time.")
            val h = m.groupValues[4].toInt(); val mi = m.groupValues[5].toInt()
            val date = try { LocalDate.of(y, mo, d) } catch (_: Exception) { return Result.Bad("“$raw” isn't a real date.") }
            if (h > 23 || mi > 59) return Result.Bad("“$raw” isn't a real time.")
            ms = at(date, h, mi, zone)
        }

        // 2. "in 90 minutes", "in 2 hours", "in 1 hour 30 minutes", "in 1h30m", "in half an hour", "in an hour"
        if (ms == null && s.startsWith("in ")) {
            val rest = s.substring(3).replace(Regex("\\bhalf an hour\\b"), "30 minutes").replace(Regex("\\ban hour\\b"), "1 hour")
                .replace(Regex("\\ba minute\\b"), "1 minute").replace(Regex("\\band\\b"), " ").replace(Regex("\\s+"), " ").trim()
            val re = Regex("(\\d+(?:\\.\\d+)?) ?(hours?|hrs?|h|minutes?|mins?|m|days?|d)(?=\\d|\\s|$)")
            var total = 0.0
            re.findAll(rest).forEach { x -> val v = x.groupValues[1].toDouble(); total += when (x.groupValues[2][0]) { 'h' -> v * 60; 'd' -> v * 1440; else -> v } }
            if (total == 0.0 || rest.replace(re, "").replace(Regex("\\s"), "").isNotEmpty()) return Result.Bad("Couldn't read “$raw”. Say it like “in 90 minutes” or “in 2 hours”.")
            if (total > 60.0 * 24 * 366) return Result.Bad("“$raw” is more than a year away. Check it.")
            var t = now + (total * MIN).roundToLong()
            t -= t % MIN
            if (t <= now) t += MIN
            ms = t
        }

        // 3. day word + clock
        if (ms == null) {
            val m = Regex("^(today|tonight|this evening|this afternoon|this morning|tomorrow(?: morning| afternoon| evening| night)?|(?:next |this )?(sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)[a-z]*)?(?: )?(.*)$").find(s)
            val dayWord = m?.groupValues?.get(1)?.ifEmpty { null }
            val clockText = m?.groupValues?.get(3) ?: s
            var clock = if (clockText.isNotEmpty()) parseClock(clockText) else null
            if (clock === BAD) return Result.Bad("“$raw” isn't a real time.")
            val bareHour = Regex("^\\d{1,2}$").matches(clockText)
            if (clock == null && dayWord != null && bareHour && clockText.toInt() in 1..11) {
                if (Regex("^(tonight|this evening|this afternoon|tomorrow (evening|night|afternoon))$").matches(dayWord)) clock = intArrayOf(clockText.toInt() + 12, 0)
                else if (dayWord.endsWith("morning")) clock = intArrayOf(clockText.toInt(), 0)
            }
            if (clock == null && dayWord != null && bareHour) return Result.Bad("“$raw”: is that $clockText in the morning or the evening? Ask the user.")
            if (clock != null) {
                val (h, mi) = clock[0] to clock[1]
                ms = when {
                    dayWord == null -> at(today, h, mi, zone).let { if (it <= now) at(today.plusDays(1), h, mi, zone) else it }
                    dayWord.startsWith("tomorrow") -> at(today.plusDays(1), h, mi, zone)
                    Regex("^(today|tonight|this )").containsMatchIn(dayWord) -> at(today, h, mi, zone)
                    else -> {
                        val key = m!!.groupValues[2]
                        val target = SHORT_DAYS[key] ?: SHORT_DAYS[key.take(3)] ?: return Result.Bad("Couldn't read the day in “$raw”.")
                        var add = (target - wd(n) + 7) % 7
                        if (add == 0 && at(today, h, mi, zone) <= now) add = 7
                        if (dayWord.startsWith("next ") && add == 0) add = 7
                        at(today.plusDays(add.toLong()), h, mi, zone)
                    }
                }
            } else if (dayWord != null && clockText.isEmpty()) {
                return Result.Bad("“$raw” has a day but no time. Ask the user what time.")
            }
        }

        val t = ms ?: return Result.Bad("Couldn't read the time “$raw”. Use 'YYYY-MM-DD HH:MM', or words like “tomorrow at 6pm” or “in 90 minutes”.")
        if (!allowPast && t < now - MIN) return Result.Bad("${fmtLong(t, zone)} has already passed (it's ${fmtLong(now, zone)} now). Use the next time it comes round, or ask the user.")
        if (t - now > 5 * 366 * DAY) return Result.Bad("${fmtLong(t, zone)} is more than five years away. Check the year.")
        return Result.Ok(t, fmtLocal(t, zone), fmtLong(t, zone), relativeDay(t, now, zone))
    }

    val REPEATS = listOf("none", "daily", "weekdays", "weekly")
    /** "every day" / "Daily" / "" → "daily"; null when it isn't one Atlas can do. */
    fun normRepeat(v: String?): String? {
        val t = (v ?: "").trim().lowercase().replace(Regex("[^a-z ]"), "")
        return when (t) {
            "", "none", "no", "once", "never" -> "none"
            "daily", "every day", "everyday", "each day" -> "daily"
            "weekdays", "every weekday", "weekday", "mondays to fridays", "monday to friday" -> "weekdays"
            "weekly", "every week", "each week" -> "weekly"
            else -> null
        }
    }

    /** Minutes in [min, max]: "30", "1.5 hours", "2h". null when missing; -1 when unreadable or out of range. */
    fun normMinutes(v: Any?, min: Int, max: Int): Int? {
        if (v == null || v.toString().isBlank()) return null
        val m = Regex("^(\\d+(?:\\.\\d+)?) ?(h|hours?|hrs?|m|mins?|minutes?)?$").find(v.toString().trim().lowercase()) ?: return -1
        val n = (m.groupValues[1].toDouble() * (if (m.groupValues[2].startsWith("h")) 60 else 1)).roundToLong().toInt()
        return if (n in min..max) n else -1
    }
}
