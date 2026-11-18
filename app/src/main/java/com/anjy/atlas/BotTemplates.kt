package com.anjy.atlas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ready-made standing bots (templates): the same list as the PC's desktop/renderer/bottemplates.js, read from
 * assets/bot-templates.json (made from that file by desktop/test/make-bot-templates.mjs; a test keeps them equal).
 * A template is a complete bot: name, icon, job, instructions, what it may touch, its triggers, what always asks first
 * and what the user needs to set up. "New bot" on the Bots tab offers them with one tap.
 */
object BotTemplates {
    @Volatile private var cache: List<JSONObject>? = null

    /** Parses the asset's text: the templates, in order. */
    fun parse(text: String): List<JSONObject> {
        val a = JSONObject(text).optJSONArray("templates") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun all(ctx: Context): List<JSONObject> = cache ?: try {
        parse(ctx.assets.open("bot-templates.json").bufferedReader().use { it.readText() }).also { cache = it }
    } catch (_: Exception) { emptyList() }

    fun byId(ctx: Context, id: String?): JSONObject? = all(ctx).firstOrNull { it.optString("id") == id }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    fun asks(t: JSONObject) = strings(t.optJSONArray("asks"))
    fun setup(t: JSONObject) = strings(t.optJSONArray("setup"))

    /**
     * A standing bot (the v2 shape) from [t], like bottemplates.js instantiate: the template's triggers with their
     * schedules read, today's default budget, made by the user. [name] replaces the template's name.
     */
    fun instantiate(t: JSONObject, name: String?, now: Long, id: String): JSONObject {
        val triggers = JSONArray()
        val src = t.optJSONArray("triggers") ?: JSONArray()
        for (i in 0 until src.length()) {
            val tr = JSONObject(src.optJSONObject(i)?.toString() ?: continue)
            val c = tr.optJSONObject("config") ?: JSONObject()
            if (tr.optString("type") == "schedule") {
                val words = c.opt("schedule") as? String
                if (!words.isNullOrBlank()) BotExtras.Rules.parseSchedule(words, now)?.takeIf { !it.has("error") }?.let { c.put("parsed", it) }
            }
            tr.put("config", c); triggers.put(tr)
        }
        val bot = JSONObject().put("v", 2).put("id", id).put("name", name?.trim()?.ifEmpty { null } ?: t.optString("name"))
            .put("role", t.optString("role")).put("instructions", t.optString("instructions")).put("icon", t.optString("icon"))
            .put("title", t.optString("title")).put("persona", t.optString("persona"))
            .apply { t.optJSONObject("face")?.let { put("face", JSONObject(it.toString())) } }   // >>> named bots: who it is (BotFace)
            .put("home", t.optString("home").ifEmpty { "any" }).put("access", JSONObject(t.optJSONObject("access")?.toString() ?: "{}"))
            .put("triggers", triggers).put("budget", JSONObject(Crew.DEFAULT_BUDGET.toString())).put("notes", JSONArray()).put("log", JSONArray())
            .put("state", "active").put("createdBy", "user").put("template", JSONObject().put("id", t.optString("id")).put("tv", t.optInt("tv", 1)))
        return Crew.normalizeBot(bot, now, id)
    }

    /** What the user agrees to, in a few lines (the New bot sheet shows it before making the bot). */
    fun summary(t: JSONObject): String {
        val lines = mutableListOf<String>()
        val acc = t.optJSONObject("access")
        val can = Crew.CAP_IDS.mapNotNull { id ->
            when (acc?.optString(id)) { "write" -> "${Crew.CAP_LABELS[id] ?: id}: can act"; "read" -> "${Crew.CAP_LABELS[id] ?: id}: can look, not act"; else -> null }
        }
        lines.add("It may: " + (if (can.isEmpty()) "only plan and report" else can.joinToString("; ")) + ".")
        val asks = asks(t); if (asks.isNotEmpty()) lines.add("Always asks you first: " + asks.joinToString("; ") + ".")
        val setup = setup(t); if (setup.isNotEmpty()) lines.add("You'll need: " + setup.joinToString("; ") + ".")
        return lines.joinToString("\n\n")
    }
}
