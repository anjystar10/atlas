package com.anjy.atlas

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Atlas Bot's extras on the phone: skills (how the user likes a kind of job done), routines (bots on a schedule),
 * specialist bots (a small team bots hand parts to), and the approval rules (ask / always / never per kind of action,
 * plus standing rules in the user's words). The same rules and the same saved shape as the PC (desktop/renderer/botx.js),
 * and synced between them (kinds "skill", "routine", "specialist", "botpolicy").
 *
 * [Rules] is pure (tested on the JVM); the store and the routine clock below it need Android.
 */
object BotExtras {
    private const val TAG = "AtlasBotx"

    // ================================================================ the rules (pure)

    object Rules {
        class Category(val id: String, val label: String)
        val CATEGORIES = listOf(
            Category("message", "Send a message or email"),
            Category("publish", "Publish or post something"),
            Category("purchase", "Buy or pay"),
            Category("delete", "Delete or overwrite"),
            Category("account", "Change an account, permission or security setting"),
            Category("terms", "Accept terms or sign up"),
            Category("command", "Run a command that changes things"),
            Category("screen", "Use your screen to work in an app"),   // >>> background first (ScreenGate.kt)
            Category("other", "Anything else that needs an OK"),
        )
        val CATEGORY_IDS = CATEGORIES.map { it.id }
        fun label(id: String?) = (CATEGORIES.firstOrNull { it.id == id } ?: CATEGORIES.last()).label

        private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
        private val purchase = rx("\\b(buy|purchase|pay|payment|checkout|check out|order|subscribe|donate|transfer money|book(ing)?|ticket)\\b")
        private val delete = rx("\\b(delete|remove|erase|wipe|overwrite|discard|clear|uninstall|trash|empty)\\b")
        private val account = rx("\\b(password|permission|security|account setting|settings|two[- ]factor|2fa|log ?out|sign ?out|privacy|role|access)\\b")
        private val terms = rx("\\b(accept|agree|terms|sign ?up|register|create an account|consent)\\b")
        private val publish = rx("\\b(post|publish|tweet|share publicly|upload|comment|reply publicly|go live)\\b")
        private val message = rx("\\b(send|email|e-mail|message|text|sms|whatsapp|dm|call|invite|reply|forward)\\b")
        private val command = rx("\\b(command|script|install|run)\\b")

        /** The kind of risky action a described action is, from its wording (same order as the PC). */
        fun guessCategory(text: String?): String {
            val t = text ?: ""
            return when {
                purchase.containsMatchIn(t) -> "purchase"
                delete.containsMatchIn(t) -> "delete"
                account.containsMatchIn(t) -> "account"
                terms.containsMatchIn(t) -> "terms"
                publish.containsMatchIn(t) -> "publish"
                message.containsMatchIn(t) -> "message"
                command.containsMatchIn(t) -> "command"
                else -> "other"
            }
        }
        /** From the tool when the wording says nothing. */
        /**
         * The kind of an MCP tool (mcp__server__tool) or web API call (call_api), from the tool's own name or the method
         * and path, so the user's ask / always / never choices cover them too; null when it isn't one. (PC: botx.js.)
         */
        fun connectionCategory(tool: String?, args: JSONObject?): String? {
            if (tool == McpWire.CALL_API) {
                if (args?.optString("method").orEmpty().uppercase() == "DELETE") return "delete"
                val words = args?.optString("path").orEmpty().replace(Regex("[/_.?=&-]+"), " ")
                return guessCategory(words + " " + words.replace(Regex("(\\w{3,})s\\b"), "$1")).let { if (it == "command") "other" else it }
            }
            if (tool == null || !tool.startsWith("mcp__")) return null
            return guessCategory(tool.split("__").drop(2).joinToString(" ").replace(Regex("_+"), " ")).let { if (it == "command") "other" else it }
        }
        fun categoryFor(tool: String?, args: JSONObject?): String = when (tool) {
            "run_command" -> "command"
            "write_file", "forget", "update_reminder", "calendar_change", "email_delete" -> "delete"
            "email_send", "reply_message", "send_sms" -> "message"   // >>> email; background first (Messages.kt)
            "open_link" -> "message"
            "press_keys", "type_text", "tap", "click", "long_press" -> guessCategory(args?.optString("label") ?: args?.optString("text")).let { if (it == "other") "message" else it }
            else -> "other"
        }
        fun categoryOf(tool: String?, args: JSONObject?, what: String): String {
            if (tool == "browser_fill_login") return "account"   // a saved sign-in (vault): always the account kind, whatever the site is called
            connectionCategory(tool, args)?.let { return it }   // connections: MCP tools and web API calls
            val g = guessCategory(what)
            return if (g != "other" || tool == null) g else categoryFor(tool, args)
        }
        /** "ask" (the default), "allow" or "deny". */
        fun modeFor(policy: JSONObject, category: String): String =
            policy.optJSONObject("modes")?.optString(category)?.takeIf { it == "allow" || it == "deny" } ?: "ask"

        // ---- routines: schedules in plain words

        const val MIN_INTERVAL_MIN = 5
        const val MAX_ROUTINES = 50
        private val DAY_NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private val PARTS = linkedMapOf("morning" to "08:00", "noon" to "12:00", "midday" to "12:00", "afternoon" to "14:00", "evening" to "18:00", "night" to "21:00", "tonight" to "21:00", "midnight" to "00:00")
        private fun pad(n: Int) = n.toString().padStart(2, '0')

        private fun timeIn(t: String): String? {
            val m = Regex("\\b(?:at\\s+)?(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?\\b").find(t)
                ?: Regex("\\b(?:at\\s+)?(\\d{1,2})()\\s*(am|pm)\\b").find(t)
                ?: Regex("\\bat\\s+(\\d{1,2})()()\\b").find(t)
            if (m != null) {
                var hh = m.groupValues[1].toInt(); val mm = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = m.groupValues[3]
                if (ap == "pm" && hh < 12) hh += 12
                if (ap == "am" && hh == 12) hh = 0
                if (hh > 23 || mm > 59) return null
                return "${pad(hh)}:${pad(mm)}"
            }
            for ((w, v) in PARTS) if (Regex("\\b$w\\b").containsMatchIn(t)) return v
            return null
        }

        /** {kind:"interval", everyMin} | {kind:"daily", time, days:[1..7]} | {kind:"monthly", day, time}; null: not a schedule; {error}: not allowed. */
        fun parseSchedule(text: String?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject? {
            val t = " " + (text ?: "").lowercase().replace(Regex("[,;]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            if (t.isBlank()) return null
            var m = Regex("\\b(?:every|each)\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b").find(t)
            if (m != null || Regex("\\bhourly\\b|\\bevery hour\\b|\\beach hour\\b").containsMatchIn(t) || Regex("\\bevery half[- ]hour\\b|\\bevery half an hour\\b").containsMatchIn(t)) {
                val every = if (m != null) m.groupValues[1].toInt() * (if (m.groupValues[2].startsWith("h")) 60 else 1) else if (t.contains("half")) 30 else 60
                if (every < MIN_INTERVAL_MIN) return JSONObject().put("error", "Routines can run every $MIN_INTERVAL_MIN minutes at most.")
                if (every > 7 * 24 * 60) return JSONObject().put("error", "For something that rare, name the day instead (like \"every Monday at 9am\").")
                return JSONObject().put("kind", "interval").put("everyMin", every)
            }
            if (Regex("\\b(every|each) month\\b|\\bmonthly\\b").containsMatchIn(t)) {
                m = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)?\\b|\\b(\\d{1,2})(?:st|nd|rd|th)\\b").find(t)
                val day = m?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() } ?: 1
                if (day < 1 || day > 31) return null
                val rest = if (m != null) t.replaceFirst(m.value, " ") else t
                return JSONObject().put("kind", "monthly").put("day", day).put("time", timeIn(rest) ?: "09:00")
            }
            val days = sortedSetOf<Int>()
            if (Regex("\\bweekdays?\\b|\\bwork ?days?\\b").containsMatchIn(t)) days.addAll(1..5)
            if (Regex("\\bweekends?\\b").containsMatchIn(t)) days.addAll(6..7)
            DAY_NAMES.forEachIndexed { i, n -> if (Regex("\\b($n|${n.take(3)}|${n.take(4)})s?\\b").containsMatchIn(t)) days.add(i + 1) }
            val everyDay = Regex("\\b(every|each) (day|morning|evening|night|afternoon)\\b|\\bdaily\\b|\\bevery ?day\\b").containsMatchIn(t)
            val weekly = Regex("\\bweekly\\b|\\b(every|each) week\\b").containsMatchIn(t)
            if (days.isEmpty() && !everyDay && !weekly) return null
            if (days.isNotEmpty() && !Regex("\\b(every|each|weekly|on|weekdays?|weekends?)\\b").containsMatchIn(t) && DAY_NAMES.none { Regex("\\b${it}s\\b").containsMatchIn(t) }) return null
            if (days.isEmpty() && weekly) days.add(Instant.ofEpochMilli(nowMs).atZone(zone).dayOfWeek.value)
            return JSONObject().put("kind", "daily").put("time", timeIn(t) ?: "09:00").put("days", JSONArray(if (days.isNotEmpty() && days.size < 7) days.toList() else emptyList<Int>()))
        }

        fun describeSchedule(s: JSONObject?): String {
            if (s == null) return ""
            return when (s.optString("kind")) {
                "interval" -> s.optInt("everyMin").let { e -> if (e % 60 == 0) (if (e == 60) "Every hour" else "Every ${e / 60} hours") else "Every $e minutes" }
                "monthly" -> s.optInt("day").let { d ->
                    val suf = if (d % 10 == 1 && d != 11) "st" else if (d % 10 == 2 && d != 12) "nd" else if (d % 10 == 3 && d != 13) "rd" else "th"
                    "Every month on the $d$suf at ${s.optString("time")}"
                }
                else -> {
                    val d = s.optJSONArray("days")?.let { a -> List(a.length()) { a.optInt(it) } } ?: emptyList()
                    val which = when {
                        d.isEmpty() || d.size == 7 -> "Every day"
                        d == listOf(1, 2, 3, 4, 5) -> "Every weekday"
                        d == listOf(6, 7) -> "Every weekend"
                        else -> "Every " + d.joinToString(", ") { DAY_SHORT[it - 1] }
                    }
                    "$which at ${s.optString("time")}"
                }
            }
        }

        /** The next time [s] is due strictly after [after] (ms), in this device's time zone. */
        fun nextRun(s: JSONObject?, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            if (s == null) return 0
            if (s.optString("kind") == "interval") return after + maxOf(MIN_INTERVAL_MIN, s.optInt("everyMin")) * 60_000L
            val (hh, mm) = s.optString("time", "09:00").split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 9).coerceIn(0, 23) to (it.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59) }   // a bad "time" from sync or a model must not throw in the alarm
            val a = Instant.ofEpochMilli(after).atZone(zone)
            if (s.optString("kind") == "monthly") {
                for (i in 0 until 14) {
                    val first = LocalDate.of(a.year, a.monthValue, 1).plusMonths(i.toLong())
                    val day = minOf(s.optInt("day", 1), first.lengthOfMonth())
                    val t = LocalDateTime.of(first.withDayOfMonth(day), java.time.LocalTime.of(hh, mm)).atZone(zone).toInstant().toEpochMilli()
                    if (t > after) return t
                }
                return 0
            }
            val days = s.optJSONArray("days")?.let { arr -> List(arr.length()) { arr.optInt(it) } } ?: emptyList()
            for (i in 0 until 9) {
                val d = a.toLocalDate().plusDays(i.toLong())
                val t = LocalDateTime.of(d, java.time.LocalTime.of(hh, mm)).atZone(zone).toInstant().toEpochMilli()
                if (t > after && (days.isEmpty() || d.dayOfWeek.value in days)) return t
            }
            return 0
        }

        // ---- skills

        private fun clip(s: String?, n: Int) = (s ?: "").trim().take(n)
        fun cleanSkill(x: JSONObject, now: Long, id: String): JSONObject = JSONObject()
            .put("id", x.optString("id").ifEmpty { id }).put("name", clip(x.optString("name"), 60).ifEmpty { "Untitled skill" })
            .put("when", clip(x.optString("when"), 300)).put("inputs", clip(x.optString("inputs"), 400)).put("steps", clip(x.optString("steps"), 3000))
            .put("checks", clip(x.optString("checks"), 600)).put("output", clip(x.optString("output"), 600)).put("permissions", clip(x.optString("permissions"), 400))
            .put("updatedAt", now)
        fun skillsIndex(skills: List<JSONObject>): String =
            if (skills.isEmpty()) "" else "Skills the user has taught you (load one with use_skill before doing that kind of job, and follow it):\n" +
                skills.take(40).joinToString("\n") { "- ${it.optString("name")}: ${it.optString("when").ifEmpty { "no description" }}" }
        fun skillText(s: JSONObject): String {
            fun part(title: String, k: String) = s.optString(k).let { if (it.isNotEmpty()) "\n$title:\n$it\n" else "" }
            return "Skill: ${s.optString("name")}\n" + part("When to use it", "when") + part("What you need first", "inputs") + part("Steps", "steps") +
                part("Checks before finishing", "checks") + part("What the result should look like", "output") + part("Needs the user's OK", "permissions")
        }
        fun findByName(list: List<JSONObject>, name: String?): JSONObject? {
            val n = (name ?: "").trim().lowercase(); if (n.isEmpty()) return null
            return list.firstOrNull { it.optString("name").lowercase() == n } ?: list.firstOrNull { it.optString("id") == name }
                ?: list.firstOrNull { val x = it.optString("name").lowercase(); x.contains(n) || n.contains(x) }
        }
        /** A first draft of a skill from a finished job's journal, for the user to review. */
        fun draftSkill(j: BotJob): JSONObject {
            val plan = j.plan.map { it.text }.filter { it.isNotBlank() }
            val did = j.steps.filter { !Regex("^(Made a plan|Updated the plan|Waiting|You |Asked you|Switched to)").containsMatchIn(it) }
            val steps = (if (plan.isNotEmpty()) plan else did.take(12)).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            val asked = j.steps.filter { Regex("^(You approved|Waiting for your OK): ").containsMatchIn(it) }.map { it.substringAfter(": ") }
            val heads = (j.report ?: "").lines().filter { Regex("^#{1,3}\\s").containsMatchIn(it) }.map { it.replace(Regex("^#+\\s*"), "") }.take(8)
            return JSONObject().put("name", clip(j.goal, 60)).put("when", clip("When asked to: ${j.goal}", 300)).put("inputs", clip(j.details, 400)).put("steps", steps)
                .put("checks", "Check the result does the whole job, and that every number and claim comes from something read or done.")
                .put("output", if (heads.isNotEmpty()) "A report in neat Markdown with these sections: " + heads.joinToString("; ") else "A short report in neat Markdown.")
                .put("permissions", asked.distinct().take(6).joinToString("\n"))
        }

        // ---- specialist bots

        class Group(val id: String, val label: String, val tools: Set<String>)
        val TOOL_GROUPS = listOf(
            Group("web", "Search and read the web", setOf("web_search", "read_page")),
            Group("device", "Use this device (apps, files, screen)", setOf("open_app", "list_apps", "open_link", "open_path", "list_files", "read_file", "write_file", "run_command", "look_at_screen", "click",
                "type_text", "press_keys", "scroll", "read_screen", "tap", "swipe", "go_back", "go_home", "wait", "long_press", "press_key")),
            Group("log", "Your Log, reminders and memory", setOf("set_reminder", "update_reminder", "remember", "forget", "read_log")),
        )
        private val GROUPED = TOOL_GROUPS.flatMap { it.tools }.toSet()
        /** No groups chosen = everything; tools outside the groups are always allowed. */
        fun toolAllowed(sp: JSONObject?, tool: String): Boolean {
            val groups = sp?.optJSONArray("groups")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            if (sp == null || groups.isEmpty() || tool !in GROUPED) return true
            return TOOL_GROUPS.any { it.id in groups && tool in it.tools }
        }
        fun cleanSpecialist(x: JSONObject, now: Long, id: String): JSONObject {
            val groups = x.optJSONArray("groups")?.let { a -> List(a.length()) { a.optString(it) } }?.filter { g -> TOOL_GROUPS.any { it.id == g } } ?: emptyList()
            val notes = x.optJSONArray("notes")?.let { a -> List(a.length()) { clip(a.optString(it), 400) } }?.filter { it.isNotEmpty() }?.takeLast(40) ?: emptyList()
            // the v2 standing-bot shape (Crew.normalizeBot), like the PC's botx.cleanSpecialist: triggers, access, budget, icon,
            // home and state survive a save on the phone (they used to be dropped, which lost what the PC had set)
            val src = JSONObject(x.toString()).put("groups", JSONArray(groups)).put("updatedAt", now)
            val b = Crew.normalizeBot(src, now, x.optString("id").ifEmpty { id })
            return b.put("notes", JSONArray(notes))
        }
        val DEFAULT_SPECIALISTS = listOf(
            JSONObject().put("id", "sp-research").put("name", "Research Bot").put("role", "Finds things out and backs them with sources")
                .put("instructions", "Search widely, read the best primary sources, cross-check key facts in two places, and report with links. Say plainly what you could not verify.").put("groups", JSONArray(listOf("web"))),
            JSONObject().put("id", "sp-planner").put("name", "Planner Bot").put("role", "Turns goals into schedules and step-by-step plans")
                .put("instructions", "Break the goal into clear steps with realistic timings. Check the Log for what is already planned and avoid clashes. Offer to log reminders rather than logging many without being asked.").put("groups", JSONArray(listOf("web", "log"))),
            JSONObject().put("id", "sp-developer").put("name", "Developer Bot").put("role", "Programming, debugging and technical work")
                .put("instructions", "Read before you change. Prefer small, safe steps and check each one worked: code is not done until it has run and its tests pass. Building something new happens on the laptop, in a project. Explain technical findings in plain words.").put("groups", JSONArray()),
            JSONObject().put("id", "sp-finance").put("name", "Finance Bot").put("role", "Numbers, budgets, prices and comparisons")
                .put("instructions", "Show the figures and where each came from. Recheck every sum. Never move money or buy anything; lay out the options and let the user decide.").put("groups", JSONArray(listOf("web", "log"))),
        )
        fun specialistPrompt(sp: JSONObject?): String {
            if (sp == null) return ""
            val role = sp.optString("role")
            var s = "\n\nYou are working as ${sp.optString("name")}${if (role.isNotEmpty()) " ($role)" else ""}, one of the user's specialist bots."
            sp.optString("instructions").takeIf { it.isNotEmpty() }?.let { s += "\nYour standing instructions: $it" }
            val notes = sp.optJSONArray("notes")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            if (notes.isNotEmpty()) s += "\nYour own notes from earlier jobs (keep what's still true in mind; fix them with bot_remember / bot_forget):\n" + notes.joinToString("\n") { "- $it" }
            return s
        }
        fun teamIndex(list: List<JSONObject>): String =
            if (list.isEmpty()) "" else "Specialist bots you can hand part of the work to (delegate):\n" + list.joinToString("\n") { "- ${it.optString("name")}: ${it.optString("role").ifEmpty { "general helper" }}" }
        fun rulesPrompt(policy: JSONObject): String {
            val out = mutableListOf<String>()
            val rules = policy.optJSONArray("rules")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            if (rules.isNotEmpty()) out.add("The user's standing rules (always follow them):\n" + rules.joinToString("\n") { "- $it" })
            val never = CATEGORY_IDS.filter { modeFor(policy, it) == "deny" }
            if (never.isNotEmpty()) out.add("Never do these, the user has ruled them out: " + never.joinToString("; ") { label(it).lowercase() } + ".")
            return out.joinToString("\n")
        }
    }

    // ================================================================ the store (botx.json) and the routine clock

    private lateinit var app: Context
    private var loaded = false
    private val lock = Any()
    val skills = mutableListOf<JSONObject>()
    val routines = mutableListOf<JSONObject>()
    val specialists = mutableListOf<JSONObject>()
    var policy: JSONObject = emptyPolicy(); private set
    private var seeded = false
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { if (l !in listeners) listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun changed(local: Boolean) {
        if (local && ::app.isInitialized) Sync.syncSoon(app)
        main.post { synchronized(listeners) { listeners.toList() }.forEach { try { it() } catch (e: Exception) { Log.w(TAG, "listener", e) } } }
    }

    private fun emptyPolicy() = JSONObject().put("modes", JSONObject()).put("rules", JSONArray()).put("updatedAt", 0)
    private fun file() = AtomicFile(File(app.filesDir, "botx.json"))
    private fun deviceId() = try { Store.read(app) { it.deviceId } } catch (_: Exception) { "" }
    private fun uid() = UUID.randomUUID().toString()
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            app = ctx.applicationContext
            loaded = true
            try {
                val f = file()
                if (f.baseFile.exists()) {
                    val d = JSONObject(String(f.readFully(), Charsets.UTF_8))
                    skills.addAll(d.optJSONArray("skills").objs()); routines.addAll(d.optJSONArray("routines").objs()); specialists.addAll(d.optJSONArray("specialists").objs())
                    d.optJSONObject("policy")?.let { p -> policy = emptyPolicy().also { e -> p.keys().forEach { k -> e.put(k, p.get(k)) } } }
                    seeded = d.optBoolean("seeded")
                }
            } catch (e: Exception) { Log.w(TAG, "unreadable", e) }
            if (!seeded) { seeded = true; Rules.DEFAULT_SPECIALISTS.forEach { s -> if (specialists.none { it.optString("id") == s.optString("id") }) specialists.add(Rules.cleanSpecialist(s, 0, s.optString("id"))) } }
            persist()
        }
        for (kind in listOf("skill", "routine", "specialist", "botpolicy")) {
            Sync.register(kind, Sync.Kind(list = { _ -> synced(kind) }, apply = { _, id, rec -> applySynced(kind, id, rec) }))
        }
        schedule()
    }

    private fun persist() {
        val d = synchronized(lock) {
            JSONObject().put("skills", JSONArray(skills)).put("routines", JSONArray(routines)).put("specialists", JSONArray(specialists)).put("policy", policy).put("seeded", seeded).toString()
        }
        synchronized(this) {
            val f = file(); val out = f.startWrite()
            try { out.write(d.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); Log.w(TAG, "save failed", e) }
        }
    }

    // ---- approvals
    /** A copy of the approval policy (autonomy: level, grants, recipients… live in it too, so they sync; Autonomy.kt). */
    fun policySnapshot(): JSONObject = synchronized(lock) { JSONObject(policy.toString()) }
    /** Changes the policy (only from the user's own tap or own words; Autonomy.kt checks that) and syncs it. */
    fun editPolicy(f: (JSONObject) -> Unit) {
        synchronized(lock) { f(policy); policy.put("updatedAt", System.currentTimeMillis()) }
        if (::app.isInitialized) { persist(); changed(true) }
    }
    fun mode(category: String) = synchronized(lock) { Rules.modeFor(policy, category) }
    fun setMode(category: String, mode: String) {
        if (category !in Rules.CATEGORY_IDS) return
        synchronized(lock) {
            val modes = policy.optJSONObject("modes") ?: JSONObject().also { policy.put("modes", it) }
            if (mode == "allow" || mode == "deny") modes.put(category, mode) else modes.remove(category)
            policy.put("updatedAt", System.currentTimeMillis())
        }
        persist(); changed(true)
    }
    fun rules(): List<String> = synchronized(lock) { policy.optJSONArray("rules")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList() }
    fun addRule(text: String) {
        val t = text.trim().take(200); if (t.isEmpty() || t in rules()) return
        synchronized(lock) {
            val list = rules().toMutableList().apply { add(t); while (size > 20) removeAt(0) }
            policy.put("rules", JSONArray(list)).put("updatedAt", System.currentTimeMillis())
        }
        persist(); changed(true)
    }
    fun removeRule(text: String) {
        synchronized(lock) { policy.put("rules", JSONArray(rules().filter { it != text })).put("updatedAt", System.currentTimeMillis()) }
        persist(); changed(true)
    }

    // ---- skills
    fun skill(name: String?) = synchronized(lock) { Rules.findByName(skills, name) }
    fun saveSkill(x: JSONObject): JSONObject {
        val s = synchronized(lock) {
            val old = x.optString("id").takeIf { it.isNotEmpty() }?.let { id -> skills.firstOrNull { it.optString("id") == id } }
                ?: skills.firstOrNull { it.optString("name").equals(x.optString("name").trim().take(60), ignoreCase = true) }
            val merged = JSONObject(old?.toString() ?: "{}").also { m -> x.keys().forEach { k -> m.put(k, x.get(k)) } }.put("id", old?.optString("id") ?: x.optString("id"))
            val s = Rules.cleanSkill(merged, System.currentTimeMillis(), uid())
            if (old != null) skills[skills.indexOf(old)] = s else skills.add(s)
            s
        }
        persist(); changed(true); return s
    }
    fun removeSkill(id: String) { synchronized(lock) { skills.removeAll { it.optString("id") == id } }; persist(); changed(true) }

    // ---- specialists
    fun specialist(name: String?): JSONObject? = synchronized(lock) { specialists.firstOrNull { it.optString("id") == name } ?: Rules.findByName(specialists, name) }
    fun saveSpecialist(x: JSONObject): JSONObject {
        val s = synchronized(lock) {
            val old = x.optString("id").takeIf { it.isNotEmpty() }?.let { id -> specialists.firstOrNull { it.optString("id") == id } }
            val merged = JSONObject(old?.toString() ?: "{}").also { m -> x.keys().forEach { k -> m.put(k, x.get(k)) } }
            val s = Rules.cleanSpecialist(merged, System.currentTimeMillis(), uid())
            if (old != null) specialists[specialists.indexOf(old)] = s else specialists.add(s)
            s
        }
        persist(); changed(true); schedule(); return s
    }
    fun removeSpecialist(id: String) { synchronized(lock) { specialists.removeAll { it.optString("id") == id } }; persist(); changed(true) }
    fun specialistNote(id: String, text: String, forget: Boolean): String {
        val t = text.trim().take(400)
        val r = synchronized(lock) {
            val sp = specialists.firstOrNull { it.optString("id") == id } ?: return@synchronized "Error: nothing to save."
            if (t.isEmpty()) return@synchronized "Error: nothing to save."
            val notes = sp.optJSONArray("notes")?.let { a -> MutableList(a.length()) { a.optString(it) } } ?: mutableListOf()
            if (forget) {
                val i = notes.indexOfFirst { it.contains(t, true) || t.contains(it, true) }
                if (i < 0) return@synchronized "No note like that."
                notes.removeAt(i)
            } else { if (t !in notes) notes.add(t); while (notes.size > 40) notes.removeAt(0) }
            sp.put("notes", JSONArray(notes)).put("updatedAt", System.currentTimeMillis())
            if (forget) "Forgotten." else "Noted for next time."
        }
        persist(); changed(true); return r
    }

    // ---- routines
    fun mine(r: JSONObject) = r.optString("device").let { it.isEmpty() || it == deviceId() }
    /** Returns the routine, or an error sentence. */
    fun saveRoutine(id: String?, goal: String?, scheduleText: String?, name: String?, specialist: String?): Pair<JSONObject?, String?> {
        val now = System.currentTimeMillis()
        val res = synchronized(lock) {
            val old = id?.let { i -> routines.firstOrNull { it.optString("id") == i } }
            if (old == null && routines.size >= Rules.MAX_ROUTINES) return@synchronized null to "You can have up to ${Rules.MAX_ROUTINES} routines. Delete one first."
            val sched = if (scheduleText != null) Rules.parseSchedule(scheduleText, now) else old?.optJSONObject("schedule")
            if (sched == null) return@synchronized null to "I couldn't read that schedule. Try something like \"every weekday at 8am\", \"every 2 hours\" or \"every Sunday evening\"."
            if (sched.has("error")) return@synchronized null to sched.optString("error")
            val g = (goal ?: old?.optString("goal") ?: "").trim().take(1000)
            if (g.isEmpty()) return@synchronized null to "Say what the routine should do."
            val r = old ?: JSONObject().put("id", uid()).put("device", deviceId()).put("enabled", true).put("createdAt", now).put("lastAt", 0).put("history", JSONArray())
            r.put("name", (name ?: r.optString("name")).trim().take(60).ifEmpty { g.take(60) })
            r.put("goal", g).put("schedule", sched).put("specialist", specialist ?: JSONObject.NULL)
            r.put("nextAt", Rules.nextRun(sched, now)).put("updatedAt", now)
            if (old == null) routines.add(r)
            r to null
        }
        if (res.first != null) { persist(); changed(true); schedule() }
        return res
    }
    fun removeRoutine(id: String) { synchronized(lock) { routines.removeAll { it.optString("id") == id } }; persist(); changed(true); schedule() }
    fun enableRoutine(id: String, on: Boolean) {
        synchronized(lock) {
            val r = routines.firstOrNull { it.optString("id") == id } ?: return
            r.put("enabled", on).put("updatedAt", System.currentTimeMillis())
            if (on) r.put("nextAt", Rules.nextRun(r.optJSONObject("schedule"), System.currentTimeMillis()))
        }
        persist(); changed(true); schedule()
    }
    /** Runs it now: a test run, or because it's due (main thread: it sends a bot). */
    fun runRoutine(id: String, test: Boolean): BotJob? {
        val r = synchronized(lock) { routines.firstOrNull { it.optString("id") == id } } ?: return null
        val sched = r.optJSONObject("schedule")
        val j = Bots.deploy(app, r.optString("goal"), "This is the routine \"${r.optString("name")}\", which runs ${Rules.describeSchedule(sched).lowercase()}.",
            specialist = r.optString("specialist").takeIf { !r.isNull("specialist") && it.isNotEmpty() }, routine = id)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val h = r.optJSONArray("history") ?: JSONArray()
            val list = h.objs().toMutableList().apply { add(JSONObject().put("at", now).put("job", j.id).put("test", test)); while (size > 20) removeAt(0) }
            r.put("history", JSONArray(list)).put("lastAt", now)
            if (!test) r.put("nextAt", Rules.nextRun(sched, now)).put("updatedAt", now)
        }
        persist(); changed(!test); schedule()
        return j
    }

    /** Fires what's due (once each, however long the phone was off), on a background thread; then sets the next alarm. */
    fun tick(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { routines.filter { it.optBoolean("enabled", true) && it.optLong("nextAt") in 1..now }.map { it to it.optLong("nextAt") } }
        for ((r, at) in due) {
            synchronized(lock) { r.put("nextAt", Rules.nextRun(r.optJSONObject("schedule"), now)) }   // moved on first, so a crash can't fire it twice
            persist()
            // only one device runs each firing: the cloud decides (offline or not linked: the device that made it)
            val run = Sync.claim(app, "routine:${r.optString("id")}:$at", 15 * 60_000L)?.mine ?: mine(r)
            if (run) {
                val latch = java.util.concurrent.CountDownLatch(1)
                main.post { try { runRoutine(r.optString("id"), false) } finally { latch.countDown() } }
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } else changed(false)
        }
        // Atlas Bot v2 triggers: schedules, new email and price levels (TrigRun)
        try { TrigRun.tick(app) } catch (e: Exception) { Log.w(TAG, "triggers", e) }
        schedule()
    }

    /** The alarm for the next routine due (exact when allowed, so it runs on time even in deep sleep). */
    fun schedule() {
        if (!::app.isInitialized) return
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 77, Intent(app, RoutineReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val routineNext = synchronized(lock) { routines.filter { it.optBoolean("enabled", true) && it.optLong("nextAt") > 0 }.minOfOrNull { it.optLong("nextAt") } }
        val trigNext = try { TrigRun.nextWake(app).takeIf { it > 0 } } catch (_: Exception) { null }
        val next = listOfNotNull(routineNext, trigNext).minOrNull()
        if (next == null) { am.cancel(pi); return }
        val at = maxOf(next, System.currentTimeMillis() + 1_000)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    // ---- sync
    private fun synced(kind: String): Map<String, JSONObject> = synchronized(lock) {
        when (kind) {
            "skill" -> skills.associateBy({ it.optString("id") }, { JSONObject(it.toString()) })
            "specialist" -> specialists.associateBy({ it.optString("id") }, { JSONObject(it.toString()) })
            "routine" -> routines.associateBy({ it.optString("id") }, { r -> JSONObject(r.toString()).also { c -> c.put("history", JSONArray(r.optJSONArray("history").objs().takeLast(5))) } })
            "botpolicy" -> if (policy.optLong("updatedAt") > 0) mapOf("me" to JSONObject(policy.toString())) else emptyMap()
            else -> emptyMap()
        }
    }
    private fun applySynced(kind: String, id: String, rec: JSONObject?) {
        synchronized(lock) {
            if (kind == "botpolicy") { if (rec != null && rec.optLong("updatedAt") >= policy.optLong("updatedAt")) policy = emptyPolicy().also { e -> rec.keys().forEach { k -> e.put(k, rec.get(k)) } } }
            else {
                val arr = when (kind) { "skill" -> skills; "routine" -> routines; "specialist" -> specialists; else -> return }
                val i = arr.indexOfFirst { it.optString("id") == id }
                when {
                    rec == null -> if (i >= 0) arr.removeAt(i)
                    i < 0 -> arr.add(rec)
                    rec.optLong("updatedAt") >= arr[i].optLong("updatedAt") -> {
                        if (kind == "routine" && mine(arr[i])) {
                            // a routine's run times belong to the device that runs it
                            val keep = arr[i]
                            rec.put("lastAt", keep.optLong("lastAt")).put("history", keep.optJSONArray("history") ?: JSONArray())
                            rec.put("nextAt", if (rec.optBoolean("enabled", true)) Rules.nextRun(rec.optJSONObject("schedule"), System.currentTimeMillis()) else keep.optLong("nextAt"))
                        }
                        arr[i] = rec
                    }
                }
            }
        }
        persist(); changed(false); if (kind == "routine" || kind == "specialist") schedule()
    }
}

/** A routine is due: check what's due on a background thread (the cloud is asked which device runs it). */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { BotExtras.tick(context.applicationContext) } catch (e: Exception) { Log.e("AtlasBotx", "routine", e) }
            finally { pending.finish() }
        }.start()
    }
}
