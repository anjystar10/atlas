package com.anjy.atlas

/**
 * Background first: the rules (pure, unit-tested) for getting things done without taking over the user's screen.
 *
 * Order of preference for any job:
 *  1. a direct connection or capability (email, calendar, contacts, trading, MCP connections, files)
 *  2. Android doing it without showing anything (intents with "skip the UI", content providers, SMS with OK)
 *  3. Atlas's own background browser (websites, including the web versions of apps)
 *  4. a notification's own buttons (reply, mark as read), like a smartwatch does
 *  5. the screen itself, only as a last resort, and only after a heads-up the user answered
 *
 * Android glue lives in ScreenGate (the screen as a last resort), Messages (notifications) and PermissionCenter.
 */
object RouteRules {
    enum class Lane(val label: String) {
        CONNECTION("a direct connection"), INTENT("Android, without opening anything"), BROWSER("Atlas's background browser"),
        NOTIFICATION("the notification's own reply button"), SCREEN("your screen")
    }

    /** A way to do something in the background instead of driving an app on screen. */
    class Alt(val lane: Lane, val how: String)

    /** What's available right now (the caller fills it in from the phone's state). */
    class Avail(
        val emailConnected: Boolean = false,
        val notificationAccess: Boolean = false,
        val hasReplyableMessage: Boolean = false,
        val smsAllowed: Boolean = false,
    )

    private class App(val names: List<String>, val packages: List<String>, val web: String? = null, val tools: List<Pair<Lane, String>> = emptyList(), val messaging: Boolean = false)

    private val apps = listOf(
        App(listOf("whatsapp", "whatsapp business"), listOf("com.whatsapp", "com.whatsapp.w4b"), "https://web.whatsapp.com", messaging = true),
        App(listOf("telegram"), listOf("org.telegram.messenger", "org.telegram.messenger.web"), "https://web.telegram.org", messaging = true),
        App(listOf("messenger", "facebook messenger"), listOf("com.facebook.orca"), "https://www.messenger.com", messaging = true),
        App(listOf("signal"), listOf("org.thoughtcrime.securesms"), null, messaging = true),
        App(listOf("messages", "sms", "google messages", "text messages"), listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"), "https://messages.google.com/web",
            tools = listOf(Lane.INTENT to "send_sms (with the user's OK each time)"), messaging = true),
        App(listOf("instagram"), listOf("com.instagram.android"), "https://www.instagram.com", messaging = true),
        App(listOf("x", "twitter"), listOf("com.twitter.android"), "https://x.com", messaging = true),
        App(listOf("facebook"), listOf("com.facebook.katana"), "https://www.facebook.com"),
        App(listOf("tiktok"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com"),
        App(listOf("linkedin"), listOf("com.linkedin.android"), "https://www.linkedin.com", messaging = true),
        App(listOf("discord"), listOf("com.discord"), "https://discord.com/app", messaging = true),
        App(listOf("slack"), listOf("com.Slack"), "https://app.slack.com", messaging = true),
        App(listOf("reddit"), listOf("com.reddit.frontpage"), "https://www.reddit.com"),
        App(listOf("youtube"), listOf("com.google.android.youtube"), "https://www.youtube.com"),
        App(listOf("gmail", "email", "mail", "outlook"), listOf("com.google.android.gm", "com.microsoft.office.outlook"), "https://mail.google.com",
            tools = listOf(Lane.CONNECTION to "the email tools (email_list, email_read, email_draft, email_send)")),
        App(listOf("calendar", "google calendar"), listOf("com.google.android.calendar", "com.samsung.android.calendar"), null,
            tools = listOf(Lane.CONNECTION to "calendar_events / calendar_add / calendar_change")),
        App(listOf("contacts", "phone book"), listOf("com.google.android.contacts", "com.samsung.android.contacts"), null,
            tools = listOf(Lane.CONNECTION to "find_contact")),
        App(listOf("clock", "alarm", "timer"), listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.coloros.alarmclock"), null,
            tools = listOf(Lane.INTENT to "phone_alarm / phone_timer (set without opening the Clock)")),
        App(listOf("chrome", "browser", "google"), listOf("com.android.chrome"), "any website", tools = listOf(Lane.BROWSER to "web_search / read_page, or a bot with its own browser")),
        App(listOf("maps", "google maps"), listOf("com.google.android.apps.maps"), "https://www.google.com/maps"),
        App(listOf("drive", "google drive"), listOf("com.google.android.apps.docs"), "https://drive.google.com"),
        App(listOf("amazon"), listOf("com.amazon.mShop.android.shopping"), "https://www.amazon.com"),
        App(listOf("bybit"), listOf("com.bybit.app"), null, tools = listOf(Lane.CONNECTION to "the trade_* tools")),
    )

    private fun find(appOrPackage: String): App? {
        val q = appOrPackage.trim().lowercase()
        if (q.isEmpty()) return null
        return apps.firstOrNull { a -> a.packages.any { it.equals(q, ignoreCase = true) } || a.names.any { it == q } }
            ?: apps.firstOrNull { a -> a.names.any { n -> q.contains(n) && n.length >= 4 } }
    }

    /** The background ways to do things in [appOrPackage], best first; empty when the screen is the only way known. */
    fun alternatives(appOrPackage: String, avail: Avail): List<Alt> {
        val app = find(appOrPackage) ?: return emptyList()
        val out = mutableListOf<Alt>()
        for ((lane, how) in app.tools) {
            if (lane == Lane.CONNECTION && how.startsWith("the email") && !avail.emailConnected) continue
            out.add(Alt(lane, how))
        }
        if (app.web != null) out.add(Alt(Lane.BROWSER, if (app.web == "any website") "Atlas's background browser (web_search / read_page; a bot with browser_* tools to act on sites)"
            else "its website ${app.web} in a bot's background browser (the user signs in there once)"))
        if (app.messaging) out.add(Alt(Lane.NOTIFICATION,
            if (avail.notificationAccess) "messages_recent + reply_message (replies from the notification, like a smartwatch${if (avail.hasReplyableMessage) "; there are messages waiting" else ""})"
            else "reply_message from the notification (needs Atlas's notification access; asking for it is automatic)"))
        return out.sortedBy { it.lane.ordinal }
    }

    /**
     * Whether Atlas must give a heads-up before driving the screen: always, unless the user is in Atlas and asked for it
     * themselves. Never while phone control is off ([phoneControlOn]): nothing could be done, so the user isn't asked
     * for their screen and shown a "using your screen" banner for nothing.
     */
    fun needsHeadsUp(fromChat: Boolean, atlasInFront: Boolean, sessionActive: Boolean, phoneControlOn: Boolean = true): Boolean =
        phoneControlOn && !sessionActive && !(fromChat && atlasInFront)

    /**
     * Who the user stopped with the screen's Stop (the chat or a bot job). Kept apart from the session's owner, which
     * ends with the session: before, Stop cleared the owner too, so "stopped" never matched and the next action took
     * the screen again (a bot with "always allow" for the screen carried straight on).
     */
    class StopMark {
        @Volatile var key: String? = null; private set
        fun stop(owner: String?) { if (owner != null) key = owner }
        /** The chat may not use the screen again for this request. */
        fun blocksChat() = key == "chat"
        /** A stopped bot must ask by name before it uses the screen again; no standing rule covers it. */
        fun mustAsk(jobId: String) = key == jobId
        /** The user OK'd the screen again (a bot's approval tap, or a new chat request). */
        fun cleared(k: String) { if (key == k) key = null }
    }

    /** One line telling the model what to do instead of taking the screen (null when there's nothing better). */
    fun hint(appOrPackage: String, avail: Avail): String? {
        val alts = alternatives(appOrPackage, avail).ifEmpty { return null }
        return "Background first: instead of driving ${appOrPackage.trim()} on the user's screen, use " +
            alts.joinToString("; or ") { it.how } +
            ". Only if none of these can do it, call open_app again with use_screen=true (the user gets a heads-up first)."
    }
}

/** Picking a notification's buttons (pure: works on plain descriptions of Android's notification actions). */
object NotifRules {
    /** One button on a notification. [semantic] is Notification.Action.SEMANTIC_ACTION_* (0 = none). */
    class Btn(val title: String, val semantic: Int, val freeFormInput: Boolean)

    const val SEMANTIC_REPLY = 1          // Notification.Action.SEMANTIC_ACTION_REPLY
    const val SEMANTIC_MARK_AS_READ = 2   // Notification.Action.SEMANTIC_ACTION_MARK_AS_READ

    private val replyWords = Regex("\\b(reply|respond|answer|répondre|responder|antworten)\\b", RegexOption.IGNORE_CASE)
    private val readWords = Regex("\\b(mark (as )?read|read|seen|lu|leído|gelesen)\\b", RegexOption.IGNORE_CASE)

    /** The index of the button that sends typed text back to the app, or -1. */
    fun replyIndex(btns: List<Btn>): Int {
        btns.indexOfFirst { it.semantic == SEMANTIC_REPLY && it.freeFormInput }.let { if (it >= 0) return it }
        btns.indexOfFirst { it.freeFormInput && replyWords.containsMatchIn(it.title) }.let { if (it >= 0) return it }
        return btns.indexOfFirst { it.freeFormInput }
    }

    /** The index of the "mark as read" button, or -1. */
    fun markReadIndex(btns: List<Btn>): Int {
        btns.indexOfFirst { it.semantic == SEMANTIC_MARK_AS_READ }.let { if (it >= 0) return it }
        return btns.indexOfFirst { !it.freeFormInput && readWords.containsMatchIn(it.title) }
    }

    /** Whether a notification looks like a message someone sent (worth listing): a messaging style or a reply box. */
    fun isMessage(category: String?, hasMessagingStyle: Boolean, btns: List<Btn>): Boolean =
        hasMessagingStyle || category == "msg" || replyIndex(btns) >= 0

    /** A short id for a notification key the model can quote back (stable for the same key). */
    fun shortId(key: String): String = "n" + Integer.toHexString(key.hashCode() and 0x7fffffff).take(7)
}

/**
 * Asking for a permission without spamming (pure). The task waits while the user decides; the ask itself is an
 * in-app sheet when Atlas is in front, otherwise one notification with a one-tap link to the right settings page.
 */
object PermFlow {
    enum class Step { PROCEED, ASK_ON_SCREEN, ASK_BY_NOTIFICATION, WAIT }

    /** Don't ask again by notification within this long of the last ask or "not now". */
    const val QUIET_MS = 10 * 60_000L

    fun next(granted: Boolean, atlasInFront: Boolean, lastAskedAt: Long, declinedAt: Long, now: Long): Step = when {
        granted -> Step.PROCEED
        atlasInFront -> Step.ASK_ON_SCREEN   // they're looking at Atlas: a sheet is fine even if asked recently
        now - maxOf(lastAskedAt, declinedAt) < QUIET_MS -> Step.WAIT
        else -> Step.ASK_BY_NOTIFICATION
    }

    /** How long a task waits for the user to allow something: bots wait longer than a live chat turn. */
    fun waitMs(forBot: Boolean) = if (forBot) 10 * 60_000L else 75_000L
}
