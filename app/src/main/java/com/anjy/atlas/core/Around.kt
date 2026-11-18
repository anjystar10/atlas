package com.anjy.atlas.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * What's around Atlas right now (the phone side of desktop/renderer/around.js; both follow
 * tools/around-fixtures.json word for word):
 * - [nowLine]: one short line in front of every turn (time, battery, network, sound mode, what's in front…).
 * - [alerts]: the few things worth saying ("battery at 9% and the trading bot is running"), each with a key, so
 *   [markNew] lets Atlas mention each one once instead of every turn.
 * - [detail]: everything, for the whats_around tool, so the brief stays short.
 * Facts are a JSONObject with the same field names as the PC: time, zone, battery, charging, network, dnd, ringer,
 * audio, foreground, notifications, notifApps, nextAlarm, nextEvents, location, storageFreeGb, screenOn, thermal,
 * cpu, ram, gpu, activeWindow, jobs, other. Missing, null or "" means unknown. Pure: no Android here.
 */
object Around {
    const val SECTION_MAX = 450
    class Alert(val key: String, val text: String)

    private val NET = mapOf("wifi" to "Wi-Fi", "mobile" to "mobile data", "ethernet" to "Ethernet", "none" to "offline")

    private fun has(f: JSONObject, k: String): Boolean = f.has(k) && !f.isNull(k) && f.opt(k).toString() != ""
    private fun num(f: JSONObject, k: String): Double? = if (has(f, k)) f.opt(k).toString().toDoubleOrNull() else null
    private fun str(f: JSONObject, k: String): String = if (has(f, k)) f.opt(k).toString() else ""
    private fun bool(f: JSONObject, k: String): Boolean? = if (has(f, k)) f.opt(k) as? Boolean else null
    private fun pct(d: Double): Long = Math.round(d)
    private fun one(d: Double) = String.format(Locale.US, "%.1f", d)

    fun nowLine(f: JSONObject): String {
        val p = mutableListOf<String>()
        if (has(f, "time")) p.add(str(f, "time") + if (has(f, "zone")) " (${str(f, "zone")})" else "")
        num(f, "battery")?.let { p.add("battery ${pct(it)}%" + if (bool(f, "charging") == true) " charging" else "") }
        if (has(f, "network")) p.add(NET[str(f, "network")] ?: str(f, "network"))
        val ringer = str(f, "ringer")
        if (bool(f, "dnd") == true) p.add("Do Not Disturb") else if (ringer == "silent" || ringer == "vibrate") p.add(ringer)
        when (str(f, "audio")) { "bluetooth" -> p.add("Bluetooth audio"); "headphones" -> p.add("headphones") }
        if (has(f, "foreground")) p.add("in " + str(f, "foreground").take(40))
        num(f, "notifications")?.takeIf { it > 0 }?.let { val n = pct(it); p.add("$n notification${if (n == 1L) "" else "s"}") }
        val load = mutableListOf<String>()
        num(f, "cpu")?.takeIf { it >= 70 }?.let { load.add("CPU ${pct(it)}%") }
        num(f, "ram")?.takeIf { it >= 70 }?.let { load.add("RAM ${pct(it)}%") }
        num(f, "gpu")?.takeIf { it >= 70 }?.let { load.add("GPU ${pct(it)}%") }
        if (load.isNotEmpty()) p.add(load.joinToString(", "))
        val th = str(f, "thermal")
        if (th.isNotEmpty() && th !in setOf("cool", "unknown", "slightly warm")) p.add(th)
        return p.joinToString(" · ")
    }

    /** [c]: botsRunning (number), tradingRunning (boolean). */
    fun alerts(f: JSONObject, c: JSONObject = JSONObject()): List<Alert> {
        val out = mutableListOf<Alert>()
        val bots = c.optDouble("botsRunning", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        val trading = c.optBoolean("tradingRunning", false)
        val running = if (trading) "the trading bot is" else if (bots == 1) "a bot is" else "$bots bots are"
        val b = num(f, "battery")
        if (b != null && bool(f, "charging") == false) {
            val p = pct(b)
            if (p <= 15 && (bots > 0 || trading)) out.add(Alert("battery-low", "Battery at $p% and not charging while $running running: it stops if the device dies."))
            else if (p <= 10) out.add(Alert("battery-low", "Battery at $p% and not charging."))
        }
        if (str(f, "network") == "none") out.add(Alert("offline", if (bots > 0 || trading) "No internet: $running running but can't reach anything until it's back." else "No internet right now: only the offline brain works."))
        num(f, "storageFreeGb")?.takeIf { it < 1 }?.let { out.add(Alert("storage", "Storage almost full: ${one(it)} GB free, so downloads and recordings may fail.")) }
        val th = str(f, "thermal")
        if (th in setOf("hot", "very hot", "dangerously hot")) out.add(Alert("thermal", "The device is $th: heavy work is held back."))
        val cpu = num(f, "cpu") ?: 0.0; val ram = num(f, "ram") ?: 0.0
        if (cpu >= 90 || ram >= 90) out.add(Alert("load", "The PC is under heavy load (CPU ${pct(cpu)}%, RAM ${pct(ram)}%)."))
        return out
    }

    /** Lines with "(NEW: …)" on alerts not told yet, and the keys to remember (only those still true). */
    fun markNew(list: List<Alert>, seen: Collection<String>): Pair<List<String>, List<String>> =
        list.map { it.text + if (it.key in seen) "" else " (NEW: mention it briefly if it matters)" } to list.map { it.key }

    /** The brief's "Around you" lines and the keys to remember; kept under [SECTION_MAX]. */
    fun section(f: JSONObject, c: JSONObject, seen: Collection<String>): Pair<List<String>, List<String>> {
        val n = nowLine(f)
        val (alertLines, keys) = markNew(alerts(f, c), seen)
        val lines = (if (n.isNotEmpty()) listOf("Now: $n") else emptyList()) + alertLines
        val kept = mutableListOf<String>(); var len = 0
        for (l in lines) { if (len + l.length + 1 > SECTION_MAX) break; kept.add(l); len += l.length + 1 }
        return kept to keys
    }

    /** Everything known, for the whats_around tool. */
    fun detail(f: JSONObject): String {
        val l = mutableListOf<String>()
        fun row(label: String, v: String) { if (v.isNotEmpty()) l.add("$label: $v") }
        row("Time", if (has(f, "time")) str(f, "time") + if (has(f, "zone")) " (${str(f, "zone")})" else "" else "")
        row("Battery", num(f, "battery")?.let { "${pct(it)}%" + when (bool(f, "charging")) { true -> ", charging"; false -> ", not charging"; else -> "" } } ?: "")
        row("Network", if (has(f, "network")) (NET[str(f, "network")] ?: str(f, "network")) + if (bool(f, "metered") == true) " (metered)" else "" else "")
        val ringer = str(f, "ringer")
        row("Sound", if (bool(f, "dnd") == true) "Do Not Disturb on" + if (ringer.isNotEmpty()) ", ringer $ringer" else "" else if (ringer.isNotEmpty()) "ringer $ringer" else "")
        row("Audio out", when (str(f, "audio")) { "bluetooth" -> "Bluetooth"; "headphones" -> "headphones"; "speaker" -> "speaker"; else -> "" })
        row("Screen", when (bool(f, "screenOn")) { true -> "on"; false -> "off"; else -> "" })
        row("In front", str(f, "foreground"))
        row("Active window", str(f, "activeWindow"))
        val n = num(f, "notifications")
        val apps = f.optJSONArray("notifApps")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
        row("Notifications", if (n != null && n > 0) "${pct(n)}" + if (apps.isNotEmpty()) " (${apps.take(6).joinToString(", ")})" else "" else if (n == 0.0) "none" else "")
        row("Next alarm", str(f, "nextAlarm"))
        row("Next events", f.optJSONArray("nextEvents")?.let { a -> (0 until minOf(3, a.length())).joinToString("; ") { a.optString(it) } } ?: str(f, "nextEvents"))
        row("Approximate location", str(f, "location"))
        row("Storage free", num(f, "storageFreeGb")?.let { "${one(it)} GB" } ?: "")
        row("Temperature", str(f, "thermal"))
        row("CPU", num(f, "cpu")?.let { "${pct(it)}%" } ?: "")
        row("Memory", num(f, "ram")?.let { "${pct(it)}% used" + (num(f, "ramTotalGb")?.let { t -> " of ${String.format(Locale.US, "%.0f", t)} GB" } ?: "") } ?: "")
        row("GPU", num(f, "gpu")?.let { "${pct(it)}%" + if (has(f, "gpuName")) " (${str(f, "gpuName")})" else "" } ?: "")
        row("Jobs running", str(f, "jobs"))
        row("Other device", str(f, "other"))
        return if (l.isEmpty()) "Nothing known about the surroundings right now." else l.joinToString("\n")
    }

    /** Small helper for callers: a JSONArray of strings. */
    fun arr(xs: List<String>) = JSONArray().apply { xs.forEach { put(it) } }
}
