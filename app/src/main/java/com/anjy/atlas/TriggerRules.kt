package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * Atlas Bot v2 triggers: what starts a standing bot's mission without the user (docs/BOTS_V2.md §5). The rules only,
 * matching desktop/renderer/trigrules.js answer for answer (both are checked against tools/trigger-fixtures.json).
 * State is plain JSON so it can be saved as it is. New trigger types are a new entry in [TYPES] plus a watcher.
 */
object TriggerRules {
    const val V = 1
    private fun clip(s: Any?, n: Int): String = (if (s == null || s == JSONObject.NULL) "" else s.toString()).trim().take(n)
    private fun num(x: Any?): Double? = when (x) {
        null, JSONObject.NULL, "" -> null
        is Number -> x.toDouble().takeIf { it.isFinite() }
        else -> x.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }
    private fun lc(s: Any?) = clip(s, 100000).lowercase()
    /** A number the way JavaScript prints it (100000, not 100000.0). */
    fun js(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    fun words(x: Any?): List<String> {
        val parts = when (x) { is JSONArray -> (0 until x.length()).map { x.opt(it) }; else -> clip(x, 100000).split(",") }
        return parts.map { lc(it).trim() }.filter { it.isNotEmpty() }.take(20)
    }
    private fun JSONObject.numOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null

    class Type(val label: String, val where: String, val clean: (JSONObject) -> JSONObject, val summary: (JSONObject) -> String)

    /** The words of a schedule: "text", or "schedule" when it is written as words (how templates write it). */
    private fun scheduleText(c: JSONObject): String = clip(c.opt("text")?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() } ?: (c.opt("schedule") as? String) ?: "", 120)
    private fun scheduleOf(c: JSONObject): JSONObject? {
        val text = scheduleText(c)
        val s = c.optJSONObject("schedule") ?: c.optJSONObject("parsed") ?: if (text.isNotEmpty()) BotExtras.Rules.parseSchedule(text) else null
        return if (s == null || s.has("error")) null else s
    }

    val TYPES: LinkedHashMap<String, Type> = linkedMapOf(
        "schedule" to Type("On a schedule", "any",
            { c -> JSONObject().put("text", scheduleText(c)).put("schedule", scheduleOf(c) ?: JSONObject.NULL) },
            { c -> c.optJSONObject("schedule")?.let { BotExtras.Rules.describeSchedule(it) } ?: "On a schedule (not set yet)" }),
        "email" to Type("When an email arrives", "any",
            { c -> JSONObject().put("from", clip(c.opt("from"), 200)).put("subject", clip(c.opt("subject"), 200)).put("folder", clip(c.opt("folder"), 80).ifEmpty { "INBOX" }).put("unreadOnly", c.opt("unreadOnly") != false) },
            { c ->
                val from = c.optString("from"); val sub = c.optString("subject")
                "When an email " + listOf(if (from.isNotEmpty()) "from $from" else "", if (sub.isNotEmpty()) "about “$sub”" else "").filter { it.isNotEmpty() }.joinToString(" ") +
                    (if (from.isNotEmpty() || sub.isNotEmpty()) " arrives" else "arrives")
            }),
        "price" to Type("When a price moves", "any",
            { c ->
                JSONObject().put("symbol", clip(c.opt("symbol"), 30).uppercase()).put("account", clip(c.opt("account"), 60))
                    .put("above", num(c.opt("above")) ?: JSONObject.NULL).put("below", num(c.opt("below")) ?: JSONObject.NULL).put("movePct", num(c.opt("movePct")) ?: JSONObject.NULL)
                    .put("windowMin", Math.min(1440.0, Math.max(5.0, num(c.opt("windowMin"))?.takeIf { it != 0.0 } ?: 60.0)))
            },
            { c ->
                val p = mutableListOf<String>()
                c.numOrNull("above")?.let { p.add("goes above ${js(it)}") }; c.numOrNull("below")?.let { p.add("goes below ${js(it)}") }
                c.numOrNull("movePct")?.let { p.add("moves ${js(it)}% within ${js(c.optDouble("windowMin"))} min") }
                "When ${c.optString("symbol").ifEmpty { "a price" }} ${p.joinToString(" or ").ifEmpty { "moves" }}"
            }),
        "notification" to Type("When a phone notification comes", "phone",
            { c -> JSONObject().put("app", clip(c.opt("app"), 120)).put("contains", clip(c.opt("contains"), 200)) },
            { c -> "When ${c.optString("app").ifEmpty { "an app" }} sends a notification" + (if (c.optString("contains").isNotEmpty()) " mentioning “${c.optString("contains")}”" else "") }),
        "app" to Type("When you open an app", "phone", { c -> JSONObject().put("app", clip(c.opt("app"), 120)) }, { c -> "When you open ${c.optString("app").ifEmpty { "an app" }}" }),
        "file" to Type("When a file appears", "any",
            { c ->
                val src = c.optString("source").takeIf { it in setOf("folder", "downloads", "screenshots") } ?: "folder"
                JSONObject().put("folder", clip(c.opt("folder"), 400)).put("pattern", clip(c.opt("pattern"), 80).ifEmpty { "*" }).put("source", src)
            },
            { c ->
                val pat = c.optString("pattern"); val src = c.optString("source")
                "When a new ${if (pat.isNotEmpty() && pat != "*") "$pat " else ""}file appears in " +
                    when (src) { "downloads" -> "Downloads"; "screenshots" -> "Screenshots"; else -> c.optString("folder").ifEmpty { "a folder" } }
            }),
        "remote" to Type("When your other device sends it", "any", { c -> JSONObject().put("name", clip(c.opt("name"), 60)) }, { c -> "When your other device sends “${c.optString("name").ifEmpty { "an event" }}”" }),
        "thread" to Type("When @mentioned in a group chat", "any", { JSONObject() }, { "When @mentioned in a group chat" }),
        "link" to Type("When your other device sends work", "any", { JSONObject() }, { "When your other device sends work" }),
        "manual" to Type("Only when you start it", "any", { JSONObject() }, { "Only when you start it" }),
    )

    /** A trigger in the v2 shape, its config cleaned for its type. Unknown types are kept (a newer Atlas made them) but never fire here. */
    fun cleanTrigger(t: JSONObject?, i: Int = 0): JSONObject {
        val type = clip(t?.opt("type"), 30)
        val raw = t?.optJSONObject("config") ?: JSONObject()
        val def = TYPES[type]
        val config = if (def == null) raw else {
            val c = JSONObject().put("v", V)
            val cleaned = def.clean(raw)
            cleaned.keys().forEach { c.put(it, cleaned.opt(it)) }
            c.put("goal", clip(raw.opt("goal"), 1000)).put("perHour", Math.min(120.0, Math.max(1.0, num(raw.opt("perHour"))?.takeIf { it != 0.0 } ?: 12.0)))
        }
        return JSONObject().put("id", clip(t?.opt("id"), 60).ifEmpty { "tr-$i" }).put("type", type).put("config", config).put("enabled", t?.opt("enabled") != false)
    }

    fun summary(t: JSONObject?): String { val d = TYPES[t?.optString("type")]; return d?.summary?.invoke(t!!.optJSONObject("config") ?: JSONObject()) ?: "“${t?.optString("type")?.ifEmpty { "?" } ?: "?"}” (needs a newer Atlas)" }

    private fun valueText(v: Any?): String = when (v) { is Double -> js(v); is Float -> js(v.toDouble()); is JSONObject, is JSONArray -> v.toString(); else -> v.toString() }
    /** The mission goal: the trigger's own goal, then what happened (spec §5). */
    fun goalFor(bot: JSONObject?, t: JSONObject, ev: JSONObject): String {
        val goal = t.optJSONObject("config")?.optString("goal").orEmpty()
        val base = goal.ifEmpty { if (bot != null && bot.optString("instructions").isNotEmpty()) "Do your job for this:" else "Handle this:" }
        val d = ev.optJSONObject("data")
        val data = d?.keys()?.asSequence()?.mapNotNull { k -> val v = d.opt(k); if (v == null || v == JSONObject.NULL || v == "") null else "$k: ${clip(valueText(v), 300)}" }?.joinToString("\n").orEmpty()
        return "$base\n\nWhat happened: ${clip(ev.opt("title"), 300)}" + (if (data.isNotEmpty()) "\n$data" else "")
    }
    fun eventKey(botId: String, trigId: String, ev: JSONObject): String = "$botId|$trigId|${clip(ev.opt("key")?.takeIf { it != JSONObject.NULL && it != "" } ?: ev.opt("title"), 200)}"

    // ------------------------------------------------------------------ de-duplication and rate limits

    const val DEDUPE_MS = 3L * 24 * 3600000
    class Result<T>(val value: T, val state: JSONObject)
    fun dedupe(state: JSONObject?, key: String, now: Long): Result<Boolean> {
        val old = state?.optJSONObject("seen") ?: JSONObject()
        val seen = HashMap<String, Long>(); old.keys().forEach { k -> val at = old.optLong(k); if (now - at <= DEDUPE_MS) seen[k] = at }
        val fresh = key !in seen
        seen[key] = now
        if (seen.size > 2000) seen.entries.sortedBy { it.value }.take(seen.size - 2000).forEach { seen.remove(it.key) }
        val out = JSONObject(state?.toString() ?: "{}").put("seen", JSONObject().also { o -> seen.forEach { (k, v) -> o.put(k, v) } })
        return Result(fresh, out)
    }
    fun rateAllow(state: JSONObject?, trigKey: String, perHour: Int, now: Long): Result<Boolean> {
        val arr = state?.optJSONObject("hits")?.optJSONArray(trigKey)
        val hits = (0 until (arr?.length() ?: 0)).map { arr!!.optLong(it) }.filter { now - it < 3600000 }.toMutableList()
        val ok = hits.size < perHour && (hits.isEmpty() || now - hits.last() >= 60000)
        if (ok) hits.add(now)
        val out = JSONObject(state?.toString() ?: "{}")
        val h = out.optJSONObject("hits") ?: JSONObject()
        h.put(trigKey, JSONArray(hits)); out.put("hits", h)
        return Result(ok, out)
    }

    // ------------------------------------------------------------------ where it runs

    fun where(bot: JSONObject?, device: String): String { val home = bot?.optString("home")?.ifEmpty { "any" } ?: "any"; return if (home == "any" || home == device) "here" else "queue" }
    fun awakeLabel(bot: JSONObject?): String {
        val ts = bot?.optJSONArray("triggers") ?: return ""
        val any = (0 until ts.length()).mapNotNull { ts.optJSONObject(it) }.any { it.optBoolean("enabled") && it.optString("type") != "manual" }
        if (!any) return ""
        return when (bot.optString("home")) { "phone" -> "Runs when your phone is awake"; "pc" -> "Runs when your laptop is on and Atlas is open"; else -> "Runs when your phone or laptop is awake" }
    }
    fun watchable(type: String, device: String): Boolean { val w = TYPES[type]?.where ?: return false; return w == "any" || w == device }

    // ------------------------------------------------------------------ schedule

    fun scheduleDue(state: JSONObject?, schedule: JSONObject?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Result<Long?> {
        val s = JSONObject(state?.toString() ?: "{}")
        if (schedule == null) return Result(null, s)
        val next = s.optLong("nextAt", 0)
        if (next == 0L) { s.put("nextAt", BotExtras.Rules.nextRun(schedule, now, zone)); return Result(null, s) }
        if (now < next) return Result(null, s)
        s.put("nextAt", BotExtras.Rules.nextRun(schedule, now, zone)).put("lastAt", now)
        return Result(next, s)
    }

    // ------------------------------------------------------------------ email

    class Mail(val uid: Long, val from: String, val subject: String, val unread: Boolean, val id: String? = null)
    fun emailEvents(state: JSONObject?, messages: List<Mail>, cfg: JSONObject): Result<List<JSONObject>> {
        val folder = cfg.optString("folder")
        val top = messages.maxOfOrNull { it.uid } ?: 0L
        if (state == null || !state.has("lastUid") || state.isNull("lastUid") || state.optString("folder") != folder)
            return Result(emptyList(), JSONObject().put("lastUid", top).put("folder", folder))
        val last = state.optLong("lastUid")
        val froms = words(cfg.opt("from")); val subs = words(cfg.opt("subject"))
        val unreadOnly = cfg.optBoolean("unreadOnly", true)
        val events = messages.filter { it.uid > last }
            .filter { !unreadOnly || it.unread }
            .filter { m -> froms.isEmpty() || froms.any { lc(m.from).contains(it) } }
            .filter { m -> subs.isEmpty() || subs.any { lc(m.subject).contains(it) } }
            .sortedBy { it.uid }
            .map { m ->
                JSONObject().put("type", "email").put("key", "$folder:${m.uid}")
                    .put("title", "New email from ${clip(m.from, 120).ifEmpty { "someone" }}: ${clip(m.subject, 160).ifEmpty { "(no subject)" }}")
                    .put("data", JSONObject().put("email_id", m.id ?: JSONObject.NULL).put("from", m.from).put("subject", m.subject))
            }
        return Result(events, JSONObject().put("lastUid", Math.max(last, top)).put("folder", folder))
    }

    // ------------------------------------------------------------------ price

    private const val HYSTERESIS = 0.002
    fun priceEvents(state: JSONObject?, price: Any?, now: Long, cfg: JSONObject): Result<List<JSONObject>> {
        val p = num(price)
        if (p == null || p <= 0) return Result(emptyList(), state ?: JSONObject())
        val s = JSONObject().put("armedAbove", true).put("armedBelow", true)
        state?.keys()?.forEach { s.put(it, state.opt(it)) }
        val above = cfg.numOrNull("above"); val below = cfg.numOrNull("below"); val move = cfg.numOrNull("movePct")
        val sym = cfg.optString("symbol").ifEmpty { "the price" }
        val events = mutableListOf<JSONObject>()
        if (!s.has("last") || s.isNull("last")) {
            return Result(events, s.put("last", p).put("ref", p).put("refAt", now)
                .put("armedAbove", !(above != null && p >= above)).put("armedBelow", !(below != null && p <= below)))
        }
        val last = s.optDouble("last")
        if (above != null) {
            if (s.optBoolean("armedAbove") && last < above && p >= above) {
                events.add(JSONObject().put("type", "price").put("key", "above:${js(above)}:$now").put("title", "$sym went above ${js(above)} (now ${js(p)})")
                    .put("data", JSONObject().put("symbol", cfg.optString("symbol")).put("price", p).put("level", above)))
                s.put("armedAbove", false)
            } else if (!s.optBoolean("armedAbove") && p < above * (1 - HYSTERESIS)) s.put("armedAbove", true)
        }
        if (below != null) {
            if (s.optBoolean("armedBelow") && last > below && p <= below) {
                events.add(JSONObject().put("type", "price").put("key", "below:${js(below)}:$now").put("title", "$sym went below ${js(below)} (now ${js(p)})")
                    .put("data", JSONObject().put("symbol", cfg.optString("symbol")).put("price", p).put("level", below)))
                s.put("armedBelow", false)
            } else if (!s.optBoolean("armedBelow") && p > below * (1 + HYSTERESIS)) s.put("armedBelow", true)
        }
        if (move != null && move > 0) {
            if (now - s.optLong("refAt") > cfg.optDouble("windowMin", 60.0) * 60000) s.put("ref", last).put("refAt", now)
            val ref = s.optDouble("ref")
            val pct = (p - ref) / ref * 100
            if (Math.abs(pct) >= move) {
                val fixed = String.format(Locale.US, "%.2f", pct)
                events.add(JSONObject().put("type", "price").put("key", "move:$now").put("title", "$sym moved ${if (pct > 0) "+" else ""}$fixed% (from ${js(ref)} to ${js(p)})")
                    .put("data", JSONObject().put("symbol", cfg.optString("symbol")).put("price", p).put("from", ref).put("pct", fixed.toDouble())))
                s.put("ref", p).put("refAt", now)
            }
        }
        s.put("last", p)
        return Result(events, s)
    }

    // ------------------------------------------------------------------ notifications

    class Note(val pkg: String, val app: String, val title: String, val text: String)
    fun notificationMatches(cfg: JSONObject, n: Note?): Boolean {
        if (n == null) return false
        val app = lc(cfg.opt("app")).trim()
        if (app.isNotEmpty() && lc(n.pkg) != app && !lc(n.app).contains(app)) return false
        val ws = words(cfg.opt("contains"))
        val body = lc("${n.title} ${n.text}")
        return ws.isEmpty() || ws.any { body.contains(it) }
    }
    fun notificationEvent(n: Note): JSONObject = JSONObject().put("type", "notification").put("key", "${n.pkg}:${clip(n.title, 80)}:${clip(n.text, 120)}")
        .put("title", "${n.app.ifEmpty { n.pkg }}: ${clip(n.title, 120)}" + (if (n.text.isNotEmpty()) " — ${clip(n.text, 200)}" else ""))
        .put("data", JSONObject().put("app", n.app.ifEmpty { n.pkg }).put("title", n.title).put("text", n.text))

    // ------------------------------------------------------------------ an app opening

    class Opened(val pkg: String, val app: String)
    /** Matches the package exactly or the app's name in part; an empty app never fires. */
    fun appMatches(cfg: JSONObject?, o: Opened?): Boolean {
        val app = lc(cfg?.opt("app")).trim()
        if (o == null || app.isEmpty()) return false
        return lc(o.pkg) == app || lc(o.app).contains(app)
    }
    fun appEvent(o: Opened, at: Long): JSONObject = JSONObject().put("type", "app").put("key", "${o.pkg}:${Math.floorDiv(at, 60000L)}")
        .put("title", "You opened ${o.app.ifEmpty { o.pkg }}").put("data", JSONObject().put("app", o.app.ifEmpty { o.pkg }).put("pkg", o.pkg))

    // ------------------------------------------------------------------ files

    fun globMatch(pattern: String?, name: String): Boolean {
        val p = lc(pattern ?: "*").trim().ifEmpty { "*" }
        val re = StringBuilder("^")
        for (ch in p) re.append(when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) })
        return Regex(re.append("$").toString()).matches(lc(name))
    }
    const val SETTLE_MS = 3000L
    class FileInfo(val name: String, val size: Long, val mtime: Long, val path: String? = null)
    fun fileEvents(state: JSONObject?, listing: List<FileInfo>, now: Long, cfg: JSONObject): Result<List<JSONObject>> {
        val items = listing.filter { globMatch(cfg.optString("pattern"), it.name) }
        fun entry(f: FileInfo, done: Boolean, since: Long) = JSONObject().put("size", f.size).put("mtime", f.mtime).put("done", done).put("since", since)
        if (state == null || !state.optBoolean("base")) {
            val known = JSONObject(); items.forEach { known.put(it.name, entry(it, true, now)) }
            return Result(emptyList(), JSONObject().put("base", true).put("known", known))
        }
        val known = JSONObject(state.optJSONObject("known")?.toString() ?: "{}")
        val events = mutableListOf<JSONObject>()
        for (f in items) {
            val k = known.optJSONObject(f.name)
            if (k == null || k.optLong("size") != f.size || k.optLong("mtime") != f.mtime) { known.put(f.name, entry(f, k?.optBoolean("done") == true, now)); continue }
            if (!k.optBoolean("done") && now - k.optLong("since") >= SETTLE_MS) {
                known.put(f.name, JSONObject(k.toString()).put("done", true))
                events.add(JSONObject().put("type", "file").put("key", "${f.name}:${f.size}:${f.mtime}").put("title", "New file: ${f.name}")
                    .put("data", JSONObject().put("file", f.name).put("path", f.path ?: JSONObject.NULL).put("size", f.size)))
            }
        }
        known.keys().asSequence().toList().forEach { name -> if (items.none { it.name == name }) known.remove(name) }
        return Result(events, JSONObject().put("base", true).put("known", known))
    }
}
