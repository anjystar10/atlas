package com.anjy.atlas

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * Who each bot is, and the Bots tab's History. Pure (JVM-tested), the same rules as the PC's desktop/renderer/botface.js,
 * checked against the same fixtures (tools/botface-fixtures.json).
 *
 *  - Names: bots are teammates with human names and a short title ("Ted · Developer"), like Grok Bot's named Bots. The
 *    name is the bot's own (the user can rename it); the title says its job. Bots made before this still carry the old
 *    "Developer Bot"-style names: [migrate] gives them their new name once, keeping the id and everything else.
 *  - Faces: like Grok Bot's "pick a shape and a colour", each bot has a shape, a colour pair and a motif for its job
 *    ([BotFaceView] draws it; Atlas keeps its own liquid orb).
 *  - History: every job any bot ran, here or on the other device, grouped by day and filterable by bot.
 */
object BotFace {
    val SHAPES = listOf("orb", "hex", "diamond", "squircle", "petal")
    /** Colour pairs (light, deep): saturated enough for a white glyph on top in both looks. */
    val COLORS = listOf(
        "#8FA2FF" to "#4B4FD8", "#6FE3D2" to "#138A8A", "#FFD27A" to "#C9771A", "#FF9BB5" to "#C2386A",
        "#C9A2FF" to "#7445D6", "#8EE6A0" to "#2E9A55", "#8FD3FF" to "#2A7BC9", "#FFAA8C" to "#D2533A",
    )
    val MOTIFS = listOf("build", "code", "chart", "wave", "mail", "play", "search", "coins", "calendar", "spark")

    class Rename(val name: String, val title: String, val shape: String, val color: Int, val persona: String)
    /** The old names → who they are now. Only an exact old name is renamed, so a name the user chose is never touched. */
    val RENAMES: Map<String, Rename> = linkedMapOf(
        "Research Bot" to Rename("Nova", "Researcher", "orb", 6, "Curious and exact. I follow the evidence, cross-check what matters and say plainly what I couldn't verify."),
        "Planner Bot" to Rename("Iris", "Planner", "petal", 4, "Calm and organised. I turn messy goals into clear steps with real times, and I protect what matters most to you."),
        "Developer Bot" to Rename("Ted", "Developer", "hex", 0, "Practical and steady. I read before I change anything, and it isn't done until it runs and its tests pass."),
        "Finance Bot" to Rename("Mara", "Finance", "squircle", 5, "Careful with numbers. I show every figure and where it came from, and I never move money."),
        "Trader" to Rename("Rex", "Trader", "diamond", 2, "Disciplined. I trade the plan inside your limits, report every move, and never promise profit."),
        "Inbox" to Rename("Ivy", "Inbox", "squircle", 1, "Brisk and discreet. I keep your email under control and draft replies in your voice for your OK."),
        "Reel Poster" to Rename("Remi", "Reels", "petal", 3, "Creative but tidy. I write hooks that land, learn what performs, and never post without your OK."),
        "Researcher" to Rename("Sage", "Deep Research", "orb", 5, "Thorough and sceptical. I go deep, separate fact from opinion, and deliver a clean document with sources."),
        "Builder" to Rename("Bo", "Builder", "hex", 7, "Hands-on. I build in small steps until it runs and passes its checks, and I tell you what is not done."),
        "Chief of Staff" to Rename("Quinn", "Chief of Staff", "hex", 2, "Warm and direct. I plan your day around what matters most and tell you honestly when it is overloaded."),
        "Market Watcher" to Rename("Otto", "Markets", "diamond", 6, "Alert and level-headed. I report what moved and why, and I never dress a guess up as a signal."),
    )

    /** 32-bit FNV-1a over UTF-16 code units: the same number as the PC's. */
    fun hash(s: String?): Long {
        var h = 0x811c9dc5.toInt()
        for (c in s ?: "") { h = h xor c.code; h *= 0x01000193 }
        return h.toLong() and 0xFFFFFFFFL
    }

    private fun has(re: String, s: String) = Regex(re).containsMatchIn(s)
    /** The glyph for a bot's job, from its title, role and name (first match wins). */
    fun motifOf(b: JSONObject?): String {
        val s = "${b?.optString("title").orEmpty()} ${b?.optString("role").orEmpty()} ${b?.optString("name").orEmpty()}".lowercase()
        return when {
            has("\\bbuild", s) -> "build"
            has("develop|\\bcode|coding|program|software|debug", s) -> "code"
            has("\\btrad(e|er|es|ing)\\b", s) -> "chart"
            has("market|news|watch", s) -> "wave"
            has("inbox|e-?mail|\\bmail", s) -> "mail"
            has("reel|video|instagram|tiktok|youtube|\\bpost", s) -> "play"
            has("research|find|search|learn", s) -> "search"
            has("financ|budget|money|price|cost", s) -> "coins"
            has("plan|schedul|calendar|chief|\\bday\\b", s) -> "calendar"
            else -> "spark"
        }
    }

    class Face(val shape: String, val color: Int, val motif: String)
    /** A bot's face: its own choice if it made one, else steady from its id. */
    fun faceOf(b: JSONObject?): Face {
        val f = b?.optJSONObject("face")
        val h = hash(b?.optString("id")?.ifEmpty { null } ?: b?.optString("name").orEmpty())
        val shape = f?.optString("shape")?.takeIf { it in SHAPES } ?: SHAPES[(h % SHAPES.size).toInt()]
        val c = f?.opt("color")
        val color = if (c is Int && c in COLORS.indices) c else if (c is Long && c in 0L until COLORS.size) c.toInt() else ((h ushr 3) % COLORS.size).toInt()
        return Face(shape, color, motifOf(b))
    }

    /** "Ted · Developer" (or just the name when it has no title). */
    fun label(b: JSONObject?): String {
        val n = b?.optString("name").orEmpty().trim().ifEmpty { "A bot" }
        val t = b?.optString("title").orEmpty().trim()
        return if (t.isNotEmpty()) "$n · $t" else n
    }
    /** The short line under a bot's name: its title, else its role. */
    fun subtitle(b: JSONObject?): String =
        b?.optString("title").orEmpty().trim().ifEmpty { b?.optString("role").orEmpty().trim().ifEmpty { "A bot of yours" } }

    /** Gives a bot from before names its new name, title, face and personality, once. Returns a NEW object when it changed something, else the same one. */
    fun migrate(b: JSONObject?): JSONObject? {
        if (b == null) return null
        val r = RENAMES[b.optString("name").trim()] ?: return b
        val out = JSONObject(b.toString())
        out.put("name", r.name)
        if (out.optString("title").isBlank()) out.put("title", r.title)
        val f = out.optJSONObject("face")
        if (f == null || f.optString("shape") !in SHAPES) out.put("face", JSONObject().put("shape", r.shape).put("color", r.color))
        if (out.optString("persona").isBlank()) out.put("persona", r.persona)
        return out
    }

    /** One line for the bot's prompt: who it is and how it sounds. */
    fun personaPrompt(b: JSONObject?): String {
        val n = b?.optString("name").orEmpty().trim(); if (n.isEmpty()) return ""
        val t = b!!.optString("title")
        var s = "Your name is $n${if (t.isNotEmpty()) ", the user's ${t.lowercase()}" else ""}. Speak in the first person as $n, briefly and warmly."
        val p = b.optString("persona"); if (p.isNotEmpty()) s += " Your manner: $p"
        return s
    }

    // ================================================================ History

    fun dayKey(ms: Long, tzMin: Int = 0): String = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.ofTotalSeconds(tzMin * 60)).toLocalDate().toString()

    val WAITING_STATES = setOf("awaiting_approval", "awaiting_answer", "needs_review")
    val RUNNING_STATES = setOf("running", "queued", "waiting_retry", "waiting_helpers")

    class Item(val id: String, val goal: String, val state: String, val bot: String, val at: Long, val where: String, val raw: JSONObject)
    class Group(val day: String, val label: String, val items: MutableList<Item> = mutableListOf())
    class History(val groups: List<Group>, val waiting: Int, val total: Int)

    /** An item from a job record ({id, goal, state, bot, at, where, …}); a job with no bot belongs to Atlas. */
    fun item(o: JSONObject): Item = Item(o.optString("id"), o.optString("goal"), o.optString("state"),
        o.optString("bot").ifEmpty { ThreadRules.ATLAS }, o.optLong("at"), o.optString("where").ifEmpty { "here" }, o)

    /** Every job, grouped by day, newest first; [bot] only that bot's; [query] words that must all appear in the goal. */
    fun historyGroups(items: List<JSONObject>, bot: String? = null, query: String? = null, tzMin: Int = 0, now: Long = System.currentTimeMillis()): History {
        val today = dayKey(now, tzMin); val yesterday = dayKey(now - 86_400_000L, tzMin)
        val words = (query ?: "").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val list = mutableListOf<Item>()
        for (o in items) {
            val id = o.optString("id"); if (id.isEmpty() || !seen.add(id)) continue
            val it = item(o)
            if (!bot.isNullOrEmpty() && it.bot != bot) continue
            val hay = it.goal.lowercase()
            if (words.any { w -> !hay.contains(w) }) continue
            list.add(it)
        }
        list.sortWith(compareByDescending<Item> { it.at }.thenBy { it.id })
        val groups = mutableListOf<Group>()
        for (it in list) {
            val d = dayKey(it.at, tzMin)
            val g = groups.lastOrNull()?.takeIf { g -> g.day == d } ?: Group(d, if (d == today) "Today" else if (d == yesterday) "Yesterday" else d).also { g -> groups.add(g) }
            g.items.add(it)
        }
        return History(groups, list.count { it.state in WAITING_STATES }, list.size)
    }
}
