package com.anjy.atlas.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Atlas knows itself: its version, what changed in each update, whether a newer release is out (the phone side of
 * desktop/renderer/updates.js; both follow tools/updates-fixtures.json word for word). CHANGELOG.json (repo root,
 * bundled as an asset) is the one source. Pure: no Android here.
 */
object Updates {
    const val RECENT_DAYS = 5
    const val CHECK_EVERY_MS = 12 * 3_600_000L
    const val BRIEF_MAX = 700
    private const val DAY = 86_400_000L

    class Entry(val version: String, val versionCode: Int, val date: String, val released: Boolean, val notes: List<String>, val abilities: List<String>)
    class Latest(val version: String, val versionCode: Int, val url: String, val sha256: String, val size: Long, val notes: List<String>, val builtFrom: String)
    class Launch(val kind: String, val from: String?)
    class Other(val platform: String, val name: String, val app: String)
    class State(val version: String, val versionCode: Int, val device: String, val from: String?, val updatedAt: Long, val now: Long,
                val others: List<Other> = emptyList(), val latest: Latest? = null)

    private val VER = Regex("""^\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
    fun parseVersion(v: String?): IntArray? {
        val m = VER.find(v ?: "") ?: return null
        return try { intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].ifEmpty { "0" }.toInt()) } catch (_: NumberFormatException) { null }
    }
    fun compareVersions(a: String?, b: String?): Int {
        val x = parseVersion(a); val y = parseVersion(b)
        if (x == null || y == null) return if (x != null) 1 else if (y != null) -1 else 0
        for (i in 0..2) if (x[i] != y[i]) return if (x[i] < y[i]) -1 else 1
        return 0
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> if (it.isNull(i)) "" else it.opt(i).toString() }.filter { s -> s.isNotEmpty() } } ?: emptyList()
    private fun intOf(o: JSONObject, k: String): Int = o.opt(k)?.toString()?.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toInt() ?: 0

    /** Newest first. Takes the file's text. */
    fun parseChangelog(text: String): List<Entry> {
        val list = try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("versions") ?: JSONArray()
        } catch (_: Exception) { return emptyList() }
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { parseVersion(it.optString("version")) != null && it.has("version") }.map { e ->
            Entry(e.optString("version").trim(), intOf(e, "versionCode"), e.optString("date"), !(e.has("released") && e.opt("released") == false),
                strings(e.optJSONArray("notes")), strings(e.optJSONArray("abilities")))
        }.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    fun entryFor(cl: List<Entry>, version: String) = cl.firstOrNull { compareVersions(it.version, version) == 0 }

    fun between(cl: List<Entry>, from: String?, to: String): List<Entry> {
        if (from.isNullOrEmpty()) return listOfNotNull(entryFor(cl, to))
        return cl.filter { compareVersions(it.version, from) > 0 && compareVersions(it.version, to) <= 0 }
    }

    fun launchState(last: String?, current: String, hadData: Boolean): Launch {
        if (last.isNullOrEmpty()) return Launch(if (hadData) "updated" else "fresh", null)
        val c = compareVersions(current, last)
        return Launch(if (c == 0) "same" else if (c > 0) "updated" else "downgraded", last)
    }

    fun updatedMessage(cl: List<Entry>, from: String?, to: String, where: String = ""): String {
        val notes = between(cl, from, to).flatMap { it.notes }
        val head = "I've been updated to $to${if (where.isNotEmpty()) " $where" else ""}" + (if (!from.isNullOrEmpty()) " (from $from)" else "") + "."
        if (notes.isEmpty()) return head
        val shown = notes.take(5)
        return head + " New:\n" + shown.joinToString("\n") { "- $it" } + if (notes.size > shown.size) "\n- …and ${notes.size - shown.size} more (ask me what's new)" else ""
    }

    fun identityLine(version: String?, device: String) = "You are Atlas ${if (version.isNullOrEmpty()) "(unknown version)" else version} on $device."

    fun briefLines(cl: List<Entry>, s: State): List<String> {
        val out = mutableListOf<String>()
        if (s.updatedAt > 0 && s.now - s.updatedAt < RECENT_DAYS * DAY) {
            val abil = between(cl, s.from, s.version).flatMap { it.abilities }
            val days = Math.floorDiv(s.now - s.updatedAt, DAY)
            out.add("Updated to ${s.version}${if (!s.from.isNullOrEmpty()) " from ${s.from}" else ""} ${if (days <= 0) "today" else if (days == 1L) "yesterday" else "$days days ago"}. New since then (use these; about_atlas has the full list):")
            abil.take(4).forEach { out.add("  " + it.take(160)) }
        }
        for (o in s.others) {
            if (o.app.isEmpty()) continue
            val c = compareVersions(o.app, s.version)
            val who = when (o.platform) { "android" -> "phone"; "windows" -> "PC"; else -> o.name.ifEmpty { "other device" } }
            if (c != 0) out.add("The $who runs Atlas ${o.app} (${if (c < 0) "older than this one: tell the user if it matters" else "newer than this one"}).")
        }
        if (s.latest != null && isNewer(s.latest, s.version, s.versionCode)) {
            out.add("Update available: Atlas ${s.latest.version} (this is ${s.version}). Mention it once if it fits; update_atlas when the user says to.")
        }
        var text = out.joinToString("\n")
        if (text.length > BRIEF_MAX) { val cut = text.lastIndexOf('\n', BRIEF_MAX - 2); text = text.substring(0, if (cut > 0) cut else BRIEF_MAX - 2) + "\n…" }
        return if (text.isEmpty()) emptyList() else text.split("\n")
    }

    fun aboutText(cl: List<Entry>, s: State, max: Int = 4): String {
        val lines = mutableListOf(identityLine(s.version, s.device))
        entryFor(cl, s.version)?.takeIf { it.date.isNotEmpty() }?.let { lines.add("This version: ${s.version}, released ${it.date}.") }
        if (!s.from.isNullOrEmpty()) lines.add("Updated from ${s.from}${if (s.updatedAt > 0) " on " + java.time.Instant.ofEpochMilli(s.updatedAt).toString().take(10) else ""}.")
        for (o in s.others) if (o.app.isNotEmpty()) lines.add("${when (o.platform) { "android" -> "Phone"; "windows" -> "PC"; else -> o.name.ifEmpty { "Other device" } }}: Atlas ${o.app}.")
        s.latest?.let { lines.add(if (isNewer(it, s.version, s.versionCode)) "Newer release available: ${it.version}." else "Up to date (newest release: ${it.version}).") }
        for (e in cl.filter { compareVersions(it.version, s.version) <= 0 }.take(max)) {
            lines.add(""); lines.add("${e.version}${if (e.date.isNotEmpty()) " (${e.date})" else ""}:")
            e.notes.forEach { lines.add("- $it") }
            if (e.abilities.isNotEmpty()) { lines.add("What you can do because of it:"); e.abilities.forEach { lines.add("- $it") } }
        }
        return lines.joinToString("\n")
    }

    private val SHA = Regex("^[0-9a-f]{64}$")
    fun parseLatest(text: String): Latest? {
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val url = o.opt("url") as? String ?: return null
        if (parseVersion(o.optString("version")) == null || !o.has("version") || !url.startsWith("https://")) return null
        val sha = o.optString("sha256").lowercase()
        return Latest(o.optString("version").trim(), intOf(o, "versionCode"), url, if (SHA.matches(sha)) sha else "",
            o.opt("size")?.toString()?.toDoubleOrNull()?.toLong() ?: 0L, strings(o.optJSONArray("notes")), o.optString("builtFrom"))
    }

    fun isNewer(latest: Latest?, version: String, versionCode: Int): Boolean {
        if (latest == null) return false
        if (latest.versionCode > 0 && versionCode > 0 && latest.versionCode != versionCode) return latest.versionCode > versionCode
        return compareVersions(latest.version, version) > 0
    }

    fun checkDue(lastCheck: Long, now: Long) = lastCheck <= 0 || now - lastCheck >= CHECK_EVERY_MS || now < lastCheck
    fun shouldAnnounce(latest: Latest?, version: String, versionCode: Int, announced: String?) = latest != null && isNewer(latest, version, versionCode) && announced != latest.version

    fun availableMessage(latest: Latest, version: String, where: String = ""): String {
        val notes = latest.notes.take(3)
        return "Atlas ${latest.version} is out${if (where.isNotEmpty()) " for $where" else ""} (you have $version)." +
            (if (notes.isNotEmpty()) " New: " + notes.joinToString("; ") + "." else "") + " Say \"update Atlas\" when you want it."
    }

    /** The signing certificate check: the downloaded APK must be signed by the same key as the installed app. */
    fun sameSigner(installed: Collection<String>, downloaded: Collection<String>): Boolean =
        installed.isNotEmpty() && downloaded.isNotEmpty() && installed.map { it.lowercase().replace(":", "") }.toSet() == downloaded.map { it.lowercase().replace(":", "") }.toSet()
}
