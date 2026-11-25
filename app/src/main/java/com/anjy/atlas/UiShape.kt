package com.anjy.atlas

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure rules behind the chat's look (no Android here, so they're unit-tested):
 * how a reply is split into prose and tidy rows, the live line under the greeting, and saved chats' titles and search.
 */
object UiShape {

    // ---------------------------------------------------------------- replies: prose, then rows

    /** One line of a list Atlas wrote, laid out as a row: a key on the left (a time or a short label) and the rest. */
    data class Row(val key: String, val value: String, val tone: Tone)
    enum class Tone { TIME, ALERT, NOW, DONE, PLAIN }

    sealed class Seg {
        data class Text(val text: String) : Seg()
        data class Rows(val rows: List<Row>) : Seg()
    }

    private val BULLET = Regex("^\\s*(?:[-•*]|\\d{1,2}[.)])\\s+")
    // a clock time (0–23 h, 00–59 min) at the start of the line; "8.05" counts only with am/pm or a dash after it, so a
    // price ("12.50 for a coffee"), a rate ("1.0845 entry"), a version ("0.11.4: …") or "12:30:45" isn't taken for one
    private val TIME = Regex("^((?:[01]?\\d|2[0-3])(?::[0-5]\\d|\\.[0-5]\\d(?=\\s?[aApP][mM]|\\s*[-–—·:]))(?:\\s?[aApP][mM])?)(?!\\d|[.,:]\\d)\\s*(?:[-–—·:]\\s*)?(.+)$")
    private val LABEL = Regex("^\\**([A-Za-z][A-Za-z0-9 '’/&()-]{0,26}?)\\**\\s*:\\**\\s+(.+)$")

    /** A line as a row, or null if it isn't one ("Now: Code Atlas", "23:30 Stretch", "- Blocked: Fix bike"). */
    fun row(line: String): Row? {
        val l = line.replace(BULLET, "").trim()
        if (l.isEmpty() || l.length > 140) return null
        TIME.matchEntire(l)?.let { m ->
            val v = m.groupValues[2].trim().trim('*').trim()
            if (v.isNotEmpty()) return Row(m.groupValues[1].replace('.', ':').trim(), v, Tone.TIME)
        }
        val m = LABEL.matchEntire(l) ?: return null
        val key = m.groupValues[1].trim()
        val value = m.groupValues[2].trim().trim('*').trim()
        if (value.isEmpty() || key.split(' ').size > 4 || key.contains("http", ignoreCase = true)) return null
        if (value.startsWith("//")) return null   // "https: //…" isn't a label
        return Row(key, value, toneOf(key))
    }

    fun toneOf(key: String): Tone {
        val k = key.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(needs?|waiting|blocked|overdue|missed|late|urgent|action|warning|failed)\\b").containsMatchIn(k) -> Tone.ALERT
            Regex("\\b(done|finished|completed|sent|saved|booked)\\b").containsMatchIn(k) -> Tone.DONE
            Regex("\\b(now|current|currently|doing|working|in progress|today)\\b").containsMatchIn(k) -> Tone.NOW
            else -> Tone.PLAIN
        }
    }

    /**
     * Splits a reply into prose and runs of 3+ row-like lines (code blocks are never touched). The prose keeps its
     * Markdown; a run becomes a tidy card of rows. Fewer than 3 such lines stay as they were.
     */
    fun split(text: String, minRun: Int = 3): List<Seg> {
        val lines = text.split('\n')
        val out = mutableListOf<Seg>()
        val prose = StringBuilder()
        var run = mutableListOf<Pair<String, Row>>()
        var fence = false
        fun flushProse() { if (prose.isNotBlank()) out.add(Seg.Text(prose.toString().trim('\n'))); prose.clear() }
        fun endRun() {
            if (run.size >= minRun) { flushProse(); out.add(Seg.Rows(run.map { it.second })) }
            else run.forEach { prose.append(it.first).append('\n') }
            run = mutableListOf()
        }
        for (line in lines) {
            if (line.trimStart().startsWith("```")) { endRun(); fence = !fence; prose.append(line).append('\n'); continue }
            val r = if (fence) null else row(line)
            if (r != null) { run.add(line to r); continue }
            // a blank line inside a run ends it (lists are written together)
            endRun()
            prose.append(line).append('\n')
        }
        endRun()
        flushProse()
        return out
    }

    // ---------------------------------------------------------------- the line under the greeting

    /** A reminder, as far as the greeting line needs it. */
    data class Upcoming(val title: String, val at: Long?, val status: String, val reminded: Boolean)

    /**
     * "Next: Gym at 08:00 · 2 bots working", from what's really there; a quiet invitation when there's nothing.
     * Times read "at 08:00" today, "tomorrow 08:00", else "Thu 08:00".
     */
    fun homeLine(items: List<Upcoming>, now: Long, zone: ZoneId, bots: String): String {
        val parts = mutableListOf<String>()
        val waiting = items.count { it.status == "planned" && it.reminded && (it.at ?: Long.MAX_VALUE) <= now }
        if (waiting > 0) parts.add(if (waiting == 1) "1 reminder waiting for you" else "$waiting reminders waiting for you")
        items.filter { it.status == "planned" && it.at != null && it.at > now }.minByOrNull { it.at!! }?.let { n ->
            parts.add("Next: ${n.title.trim().take(40)} ${whenShort(n.at!!, now, zone)}")
        }
        if (bots.isNotBlank()) parts.add(bots.trim())
        return if (parts.isEmpty()) "Nothing scheduled. Ask for anything, or say “Hey Atlas”." else parts.joinToString("  ·  ")
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    fun whenShort(at: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (t.toLocalDate()) {
            today -> "at " + t.format(HM)
            today.plusDays(1) -> "tomorrow " + t.format(HM)
            else -> t.format(DAY_HM)
        }
    }

    // ---------------------------------------------------------------- saved chats

    /** A saved chat's title: its first question, tidied and kept short. */
    fun chatTitle(firstQuestion: String?): String {
        val t = (firstQuestion ?: "").replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
        if (t.isEmpty()) return "Chat"
        return if (t.length <= 48) t.replaceFirstChar { it.uppercase() } else t.take(47).trimEnd().replaceFirstChar { it.uppercase() } + "…"
    }

    /** Every word of the search appears in the title or the chat (any case, any order). */
    fun matches(query: String, title: String, body: String): Boolean {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = (title + "\n" + body).lowercase(Locale.ROOT)
        return words.all { hay.contains(it) }
    }

    /** "Today 14:05", "Yesterday", "Mon 9 Nov" for a chat's last message. */
    fun chatWhen(at: Long, now: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(at).atZone(zone)
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d.toLocalDate()) {
            today -> "Today " + d.format(HM)
            today.minusDays(1) -> "Yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
    }
}
