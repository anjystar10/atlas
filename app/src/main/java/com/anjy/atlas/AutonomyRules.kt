package com.anjy.atlas

/**
 * Atlas autonomy: when Atlas acts on its own and when it asks. Pure (no Android, no JSON, no clock) and held to the
 * PC's answers (desktop/renderer/autonomy.js) by tools/autonomy-fixtures.json — see AutonomyTest.
 *
 * The state is kept inside the bot policy (BotExtras), so it syncs between the devices; [Autonomy] reads it into a
 * [Policy]. Safety rules this never weakens: only the user's own tap or own message makes a grant; the high tier always
 * asks and no grant or level covers it; money (mustAsk) always asks; content from pages, emails or bots never approves.
 */
object AutonomyRules {
    val LEVELS = listOf("careful", "balanced", "autopilot")
    const val DEFAULT_LEVEL = "balanced"
    val LEVEL_LABELS = mapOf(
        "careful" to "Careful: asks before anything that changes things (like before)",
        "balanced" to "Balanced: small things just happen (with Undo); asks once per kind of bigger thing; always asks for money, deleting, new people and security",
        "autopilot" to "Autopilot: only asks for money, deleting, new people and security",
    )
    const val UNDO_MS = 10_000L
    val ASK_TTL_MS = mapOf("chat" to 30 * 60_000L, "bot" to 12 * 3_600_000L, "laptop" to 12 * 3_600_000L)
    const val SUGGEST_AFTER = 3

    class Kind(val tier: String, val label: String)
    /** Every kind of action that could need an OK: its tier and how it's described. The one table both devices follow. */
    val KINDS: Map<String, Kind> = linkedMapOf(
        "read" to Kind("free", "Read or look things up"),
        "draft" to Kind("free", "Write a draft"),
        "organize" to Kind("free", "Organise (mark read, move, label)"),
        "open_app" to Kind("free", "Open an app or page"),
        "note" to Kind("free", "Save a note or reminder"),
        "demo_trade" to Kind("free", "Trade on a demo or paper account"),
        "screen" to Kind("low", "Use your screen to work in an app"),
        "trash_email" to Kind("low", "Move emails to the trash"),
        "reminder_change" to Kind("low", "Cancel or delete one of your reminders"),
        "message_send" to Kind("medium", "Send a message or email to someone you have messaged before"),
        "message_reply" to Kind("medium", "Reply in a conversation you already have"),
        "publish" to Kind("medium", "Post or publish something"),
        "terms" to Kind("medium", "Accept terms or sign up"),
        "sign_in" to Kind("medium", "Sign in with a saved password"),
        "command" to Kind("medium", "Run a command that changes things"),
        "write_file" to Kind("medium", "Write or overwrite a file"),
        "calendar_change" to Kind("medium", "Change or delete a calendar event"),
        "forget_memory" to Kind("medium", "Delete a saved memory"),
        "other" to Kind("medium", "Anything else that changes things"),
        "message_new" to Kind("high", "Message someone Atlas has never sent to"),
        "purchase" to Kind("high", "Buy or pay"),
        "live_trade" to Kind("high", "A LIVE (real money) trade outside your pre-approved limits"),
        "delete" to Kind("high", "Delete or overwrite something for good"),
        "account" to Kind("high", "Change a password, permission or security setting"),
        "install" to Kind("high", "Install an app or software"),
    )
    fun tierOf(kind: String?) = (KINDS[kind] ?: KINDS.getValue("other")).tier
    fun kindLabel(kind: String?) = (KINDS[kind] ?: KINDS.getValue("other")).label

    val CATEGORY_KIND = mapOf("message" to "message_send", "publish" to "publish", "purchase" to "purchase", "delete" to "delete", "account" to "account",
        "terms" to "terms", "command" to "command", "screen" to "screen", "other" to "other")
    val KIND_CATEGORY = mapOf("message_send" to "message", "message_reply" to "message", "message_new" to "message", "publish" to "publish", "purchase" to "purchase",
        "live_trade" to "purchase", "delete" to "delete", "forget_memory" to "delete", "calendar_change" to "delete", "write_file" to "delete", "trash_email" to "delete",
        "reminder_change" to "delete", "account" to "account", "sign_in" to "account", "install" to "command", "terms" to "terms", "command" to "command",
        "screen" to "screen", "other" to "other")

    private val RISKY_SCHEMES = listOf("tel:", "sms:", "smsto:", "mailto:", "mms:", "whatsapp:")
    private val EMAIL = Regex("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")

    fun normRecipient(r: String?): String {
        val s = (r ?: "").trim().lowercase()
        if (s.isEmpty()) return ""
        EMAIL.find(s)?.let { return it.value }
        val digits = s.replace(Regex("[^\\d+]"), "")
        return if (digits.replace(Regex("\\D"), "").length >= 6) digits.replace(Regex("(?!^)\\+"), "") else s.replace(Regex("\\s+"), " ")
    }
    fun recipientsIn(to: String?): List<String> = (to ?: "").split(Regex("[,;]+")).map { p ->
        val m = Regex("<([^>]+)>").find(p); normRecipient(m?.groupValues?.get(1) ?: p)
    }.filter { it.isNotEmpty() }
    fun allKnown(recipients: List<String>, known: List<String>): Boolean {
        val set = known.map { normRecipient(it) }.toSet()
        return recipients.isNotEmpty() && recipients.all { normRecipient(it) in set }
    }

    /** What [kindFor] needs to know beyond the tool and its arguments. */
    class Ctx(val known: List<String> = emptyList(), val replyThread: Boolean = false, val to: String? = null, val live: Boolean = false,
              val money: Boolean = false, val what: String = "")

    /** The kind of an action from the tool, its arguments ([args]: name → text) and what it says. Same as the PC. */
    fun kindFor(tool: String?, args: Map<String, String> = emptyMap(), category: String? = null, ctx: Ctx = Ctx()): String {
        when (tool) {
            "email_send" -> return if (ctx.replyThread) "message_reply" else if (allKnown(recipientsIn(ctx.to?.takeIf { it.isNotEmpty() } ?: args["to"]), ctx.known)) "message_send" else "message_new"
            "send_sms" -> return if (allKnown(recipientsIn(args["to"]), ctx.known)) "message_send" else "message_new"
            "reply_message" -> return "message_reply"
            "email_delete" -> return "trash_email"
            "email_archive", "email_mark", "email_move", "mark_read" -> return "organize"
            "email_draft" -> return "draft"
            "forget" -> return "forget_memory"
            "update_reminder" -> return if (args["action"] in listOf("delete", "cancel")) "reminder_change" else "note"
            "calendar_change" -> return "calendar_change"
            "run_command" -> return "command"
            "write_file" -> return "write_file"
            "browser_fill_login" -> return if (ctx.money) "purchase" else "sign_in"
            "open_link" -> return if (RISKY_SCHEMES.any { (args["url"] ?: "").trim().lowercase().startsWith(it) }) "message_send" else "open_app"
            "trade_order", "trade_close", "trade_plan" -> return if (ctx.live) "live_trade" else "demo_trade"
        }
        if (ctx.money) return "purchase"
        if (category != null && CATEGORY_KIND.containsKey(category)) {
            if (category == "command" && Regex("\\binstall\\b", RegexOption.IGNORE_CASE).containsMatchIn(ctx.what)) return "install"
            return CATEGORY_KIND.getValue(category)
        }
        return "other"
    }

    data class Grant(val kind: String, val scope: String, val botId: String? = null, val missionId: String? = null, val day: String? = null, val at: Long = 0)
    data class Policy(val level: String? = null, val botLevels: Map<String, String> = emptyMap(), val grants: List<Grant> = emptyList(),
                      val modes: Map<String, String> = emptyMap(), val recipients: List<String> = emptyList(), val suggested: List<String> = emptyList())
    data class Decision(val act: String, val tier: String, val kind: String, val why: String)

    private fun rank(lv: String?) = LEVELS.indexOf(lv).coerceAtLeast(0)
    fun levelOf(p: Policy?): String = p?.level?.takeIf { it in LEVELS } ?: DEFAULT_LEVEL
    /** The level a bot works at: its own choice, but never bolder than the global level. */
    fun effectiveLevel(p: Policy?, botId: String?): String {
        val g = levelOf(p)
        val b = if (botId != null) p?.botLevels?.get(botId) else null
        return if (b != null && b in LEVELS && rank(b) < rank(g)) b else g
    }

    fun grantCovers(g: Grant?, kind: String, botId: String? = null, missionId: String? = null, day: String? = null): Boolean {
        if (g == null || g.kind != kind) return false
        return when (g.scope) {
            "always" -> true
            "today" -> day != null && g.day == day
            "bot" -> botId != null && g.botId == botId
            "mission" -> missionId != null && g.missionId == missionId
            else -> false
        }
    }
    fun coveringGrant(p: Policy?, kind: String, botId: String? = null, missionId: String? = null, day: String? = null): Grant? =
        p?.grants?.firstOrNull { grantCovers(it, kind, botId, missionId, day) }

    /** What to do with one action: go (just do it), undo (do it after [UNDO_MS] unless Undo is tapped), ask, or deny. Same as the PC. */
    fun decide(p: Policy?, kind: String, mustAsk: Boolean = false, botId: String? = null, missionId: String? = null, day: String? = null, planKinds: List<String>? = null): Decision {
        val k = if (KINDS.containsKey(kind)) kind else "other"
        val tier = if (mustAsk) "high" else tierOf(k)
        val cat = KIND_CATEGORY[k]
        val mode = if (cat != null) p?.modes?.get(cat) else null
        if (mode == "deny" && tier != "free") return Decision("deny", tier, k, "you never allow this kind")
        if (tier == "free") return Decision("go", tier, k, "nothing to approve")
        if (tier == "high") return Decision("ask", tier, k, if (mustAsk) "money is involved" else "it always needs your OK")
        val level = effectiveLevel(p, botId)
        val granted = coveringGrant(p, k, botId, missionId, day) != null || mode == "allow" || (planKinds?.contains(k) == true)
        if (level == "careful") return if (granted) Decision("go", tier, k, "you allowed this kind") else Decision("ask", tier, k, "Careful mode asks")
        if (tier == "low") return Decision(if (level == "autopilot") "go" else "undo", tier, k, "a small, private action")
        if (granted) return Decision("undo", tier, k, "you allowed this kind")
        if (level == "autopilot") return Decision("undo", tier, k, "Autopilot")
        return Decision("ask", tier, k, "first time for this kind")
    }

    /** The kinds a plan's steps need (approving the plan approves these for that mission; high kinds never). */
    fun planKindsOf(steps: List<String>, guess: ((String) -> String?)?): List<String> {
        val out = mutableListOf<String>()
        for (text in steps) {
            val k = kindFor(null, emptyMap(), guess?.invoke(text), Ctx(what = text))
            // only named kinds: a step that says nothing specific ("other") never pre-approves anything
            if (k != "other" && (tierOf(k) == "medium" || tierOf(k) == "low") && k !in out) out.add(k)
        }
        return out
    }

    /** A new grant (or the reason it can't be made). High kinds can't be granted. */
    fun makeGrant(kind: String, scope: String, botId: String? = null, missionId: String? = null, day: String? = null, at: Long = 0): Pair<Grant?, String?> {
        if (!KINDS.containsKey(kind)) return null to "unknown kind"
        if (tierOf(kind) == "high") return null to "“${kindLabel(kind)}” always asks; it can't be allowed in advance."
        if (tierOf(kind) == "free") return null to "That never needs an OK."
        if (scope == "today" && day == null) return null to "no day"
        if (scope == "bot" && botId == null) return null to "no bot"
        if (scope == "mission" && missionId == null) return null to "no mission"
        if (scope !in listOf("always", "today", "bot", "mission")) return null to "unknown scope"
        return Grant(kind, scope, if (scope == "bot") botId else null, if (scope == "mission") missionId else null, if (scope == "today") day else null, at) to null
    }
    /** The grants with this one added (replacing the same kind + scope + target), old "today" grants dropped, capped at 100. */
    fun addGrant(grants: List<Grant>, g: Grant, today: String? = null): List<Grant> {
        fun same(x: Grant) = x.kind == g.kind && x.scope == g.scope && x.botId == g.botId && x.missionId == g.missionId
        val out = grants.filter { !same(it) && !(it.scope == "today" && today != null && it.day != today) } + g
        return out.takeLast(100)
    }
    fun grantText(g: Grant, botName: (String?) -> String = { it ?: "" }): String {
        val what = kindLabel(g.kind)
        return when (g.scope) {
            "always" -> "$what: always allowed"
            "today" -> "$what: allowed today (${g.day})"
            "bot" -> "$what: allowed for ${botName(g.botId)}"
            "mission" -> "$what: allowed for one mission"
            else -> what
        }
    }

    /** After the user approves a kind: the new count, and whether to offer "Always allow this?" now (once per kind). */
    fun afterApproval(counts: Map<String, Int>, suggested: List<String>, kind: String): Pair<Int, Boolean> {
        val n = (counts[kind] ?: 0) + 1
        return n to (n >= SUGGEST_AFTER && tierOf(kind) == "medium" && kind !in suggested)
    }

    class Pending(val ref: String, val what: String, val kind: String = "other", val mustAsk: Boolean = false, val question: Boolean = false)
    class Batch(val bulk: List<Pending>, val single: List<Pending>, val title: String)
    /** Pending approvals into one batch: those "Approve all" may cover, and those that still need their own tap. */
    fun batch(pending: List<Pending>): Batch {
        val bulk = pending.filter { !it.question && !it.mustAsk && tierOf(it.kind) != "high" }
        val single = pending.filter { it !in bulk }
        return Batch(bulk, single, if (pending.size == 1) "1 thing needs your OK" else "${pending.size} things need your OK")
    }

    fun expired(askedAt: Long, now: Long, where: String = "bot"): Boolean {
        val ttl = ASK_TTL_MS[where] ?: ASK_TTL_MS.getValue("bot")
        return askedAt > 0 && now - askedAt > ttl
    }

    /** Making Atlas ask LESS from chat needs the user's own message to say so; a tap in Me always may; more careful always may. */
    fun canChangeLevel(from: String, to: String, byTap: Boolean = false, consentYes: Boolean = false): Pair<Boolean, String?> {
        if (to !in LEVELS) return false to "unknown level"
        if (byTap || rank(to) <= rank(from)) return true to null
        return if (consentYes) true to null else false to "only the user's own words can make Atlas ask less"
    }

    fun briefLine(p: Policy?): String {
        val n = p?.grants?.size ?: 0
        return "Autonomy: ${levelOf(p)}${if (n > 0) " ($n standing permission${if (n == 1) "" else "s"})" else ""}. Small private actions just happen with Undo; money, deleting, new people and security always ask."
    }
}
