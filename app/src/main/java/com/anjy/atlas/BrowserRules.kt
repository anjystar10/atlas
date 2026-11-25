package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject

/**
 * The parts of the bot's browser that don't need a phone (so they can be tested): which addresses it may open, safe
 * file names, turning what the page script reports into text for the model, and which browser actions need the
 * user's OK. [BotBrowser] does the driving.
 */
object BrowserRules {
    /** Readable page text sent to the model after reading a page, and after an action. */
    const val READ_CHARS = 6000
    const val ACTION_CHARS = 2500
    /** Interactive elements listed (the rest are reached by scrolling or reading again). */
    const val MAX_ELEMENTS = 150
    const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024

    /**
     * An address the bot may open: http(s) only. A bare "example.com/x" becomes https. Returns null for anything else
     * (javascript:, file:, intent:, content:, data:), which a web page could otherwise use to reach into the phone.
     */
    fun normalizeUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty() || u.any { it.isWhitespace() }) return null
        val lower = u.lowercase()
        if (lower.startsWith("https://")) return u.takeIf { it.length > 8 }
        // the phone only allows plain http to itself (its network security settings), so other sites go over https
        if (lower.startsWith("http://")) return u.takeIf { it.length > 7 }?.let { if (local(lower.removePrefix("http://"))) it else "https://" + it.substring(7) }
        if (Regex("^[a-z][a-z0-9+.-]*:").containsMatchIn(lower) && !Regex("^[^/:]+:\\d+(/|$)").containsMatchIn(lower)) return null
        if (!Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE).matches(u) && !lower.startsWith("localhost") && !lower.startsWith("127.0.0.1")) return null
        return if (local(lower)) "http://$u" else "https://$u"
    }

    private fun local(rest: String) = rest.startsWith("127.0.0.1") || rest.startsWith("localhost")

    /** A file name that's safe on disk: no folders, no odd characters, not empty, not too long. */
    fun safeFileName(raw: String?, fallback: String = "download"): String {
        var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').substringBefore('?').substringBefore('#')
        n = try { java.net.URLDecoder.decode(n, "UTF-8") } catch (_: Exception) { n }
        n = n.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().trimStart('.')
        if (n.isEmpty() || n.all { it == '_' || it == '.' }) n = fallback
        if (n.length > 100) { val ext = n.substringAfterLast('.', "").take(10); n = n.take(90) + if (ext.isNotEmpty()) ".$ext" else "" }
        return n
    }

    /** The file name from a Content-Disposition header, if it gives one. */
    fun nameFromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim().trim('"') }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    class Element(val n: Int, val kind: String, val label: String, val href: String?, val value: String?, val checked: Boolean?, val disabled: Boolean, val inForm: Boolean)

    fun parseElements(arr: JSONArray?): List<Element> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Element(o.optInt("n"), o.optString("kind", "element"), o.optString("label").replace(Regex("\\s+"), " ").trim().take(120),
                o.optString("href").takeIf { it.isNotBlank() }, if (o.has("value")) o.optString("value").take(80) else null,
                if (o.has("checked")) o.optBoolean("checked") else null, o.optBoolean("disabled"), o.optBoolean("inForm"))
        }
    }

    /** One line per element, the way the model sees it: [3] button “Search”. */
    fun elementLine(e: Element): String = buildString {
        append('[').append(e.n).append("] ").append(e.kind)
        if (e.label.isNotEmpty()) append(" “").append(e.label).append('”')
        if (e.value != null) append(if (e.value.isEmpty()) " (empty)" else " = “${e.value}”")
        if (e.checked != null) append(if (e.checked) " (on)" else " (off)")
        if (e.disabled) append(" (disabled)")
        e.href?.let { h -> if (!h.startsWith("javascript", true)) append(" → ").append(h.take(90)) }
    }

    /** The page as text for the model, from what the page script reported. [chars] caps the readable text. */
    fun pageText(page: JSONObject, chars: Int): String = buildString {
        append("Page: ").append(page.optString("title").ifBlank { "(no title)" }.take(150)).append('\n')
        append("URL: ").append(page.optString("url").take(300)).append('\n')
        val warn = mutableListOf<String>()
        if (page.optBoolean("password")) warn += "This page has a password field: the user must sign in themselves. Use ask_user to ask them to sign in in the bot's browser, then carry on."
        if (page.optBoolean("captcha")) warn += "This page shows a human check (CAPTCHA). Don't try to solve it: use ask_user to ask the user to complete it in the bot's browser."
        warn.forEach { append("Note: ").append(it).append('\n') }
        val els = parseElements(page.optJSONArray("elements"))
        if (els.isEmpty()) append("\nNo buttons, links or fields found on this part of the page.\n")
        else {
            append("\nWhat you can use (by number):\n")
            els.forEach { append(elementLine(it)).append('\n') }
            val more = page.optInt("more")
            if (more > 0) append("(and $more more further down: scroll, then read the page again)\n")
        }
        val text = page.optString("text").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (text.isNotEmpty()) {
            append("\nText on the page:\n").append(text.take(chars))
            if (text.length > chars) append("\n(… cut: scroll and read again for more)")
        }
    }.trim()

    /** Finds an element by number ("12", "[12]") or by its words; exact words first, then the first that contains them. */
    fun pick(target: String, els: List<Element>): Element? {
        val t = target.trim().removePrefix("[").removeSuffix("]").trim()
        t.toIntOrNull()?.let { n -> return els.firstOrNull { it.n == n } }
        if (t.isEmpty()) return null
        return els.firstOrNull { it.label.equals(t, true) } ?: els.firstOrNull { it.label.contains(t, true) }
    }

    private val riskyWords = Regex("\\b(send|post|publish|share|tweet|reply|buy|purchase|pay|payment|checkout|check out|order|place order|book|reserve|confirm|delete|remove|erase|transfer|donate|subscribe|unsubscribe|submit|sign up|signup|register|create account|join|apply|agree|accept|i agree|sign out|log out|logout|deactivate|close account|cancel subscription|report|block)\\b", RegexOption.IGNORE_CASE)

    fun risky(label: String?): Boolean = label != null && riskyWords.containsMatchIn(label)

    /**
     * Browser actions that need the user's OK, as a sentence saying what would happen; null when the bot can go ahead.
     * [label] is what the target element says; [inForm] whether it sits in a form (Enter would submit it); [site] the page's host.
     */
    fun approval(tool: String, args: JSONObject, label: String?, inForm: Boolean, site: String, signals: Signals = Signals()): String? {
        val where = if (site.isNotBlank()) " on $site" else ""
        // money first: on a payment page, or where the element or its form says buy/pay/order, every action asks
        // (a page Atlas couldn't check is said as it is, not called a payment page)
        val pay = if (signals.unread && !signals.payFields && !payPath.containsMatchIn("/" + signals.url.substringAfter("://").substringAfter('/', ""))) "a page Atlas couldn't check for payments" else "a payment page"
        if (moneyRisk(tool, signals)) return when (tool) {
            "browser_click" -> "Click “${(label ?: args.optString("target")).take(60)}” on $pay$where"
            "browser_type" -> "Type into $pay$where" + (if (args.optBoolean("submit", false)) " and submit it" else "")
            "browser_select" -> "Choose “${args.optString("option").take(40)}” on $pay$where"
            "browser_press" -> "Press ${args.optString("key").take(20)} on $pay$where"
            else -> "Sign in on $pay$where with your saved sign-in “${args.optString("entry").take(40)}”"
        }
        return when (tool) {
            "browser_click" -> if (risky(label)) "Click “${label!!.take(60)}”$where" else null
            "browser_fill_login" -> "Sign in$where with your saved sign-in “${args.optString("entry").take(40)}”"
            "browser_type" -> if (args.optBoolean("submit", false)) "Type “${args.optString("text").take(60)}” and submit$where" else null
            // "return" is pressed as Enter too (BotBrowser maps both), so it needs the same OK
            "browser_press" -> if (args.optString("key").trim().lowercase().let { it == "enter" || it == "return" } && inForm) "Press Enter, which submits the form${label?.let { " (“${it.take(40)}”)" } ?: ""}$where" else null
            else -> null
        }
    }

    // ---------------------------------------------------------------- money: actions that must always ask

    /**
     * What the page says about the action, read by BotBrowser right before it: the page address, whether it has
     * payment or card fields (cc-* autocomplete, card number, CVV/CVC, expiry, IBAN, a payment provider's frame),
     * the words of the target element beyond its visible text (aria-label, title, name, id, value), and its form
     * (the form's address and its submit buttons). A page can make its button *say* anything, so these count too.
     */
    class Signals(val url: String = "", val payFields: Boolean = false, val elementText: String = "", val formText: String = "",
        /** The page couldn't be checked (its script failed or didn't answer): money can't be ruled out, so it counts as a payment page. */
        val unread: Boolean = false)

    /** The browser actions the money rule covers. */
    val moneyTools = setOf("browser_click", "browser_type", "browser_press", "browser_select", "browser_fill_login")
    private val payPath = Regex("(check-?out|/pay(ment)?s?(\\b|/|\\?|$)|/billing|/purchase|/place-?order|/order/(confirm|review|pay)|/cart/pay|/donate|/subscribe)", RegexOption.IGNORE_CASE)
    private val moneyWords = Regex("\\b(buy|purchase|pay|payment|checkout|check ?out|place ?order|order now|complete order|confirm order|subscribe|donate|book now|card number|cvv|cvc|iban)\\b", RegexOption.IGNORE_CASE)

    /** A page where money changes hands: payment fields on it, or an address that reads like checkout/payment. */
    fun paymentPage(s: Signals): Boolean = s.payFields || s.unread || payPath.containsMatchIn("/" + s.url.substringAfter("://").substringAfter('/', ""))

    /** True when [tool] on this page must always wait for a tap: no standing rule and no "allow on this site" skips it. */
    fun moneyRisk(tool: String, s: Signals): Boolean =
        tool in moneyTools && (paymentPage(s) || moneyWords.containsMatchIn(s.elementText) || moneyWords.containsMatchIn(s.formText))

    /** The text the action's kind is judged by: everything it says, so the strongest risk wins (BotExtras.Rules.guessCategory). */
    fun riskText(label: String?, s: Signals): String =
        listOfNotNull(label, s.elementText, s.formText, if (paymentPage(s)) "payment" else null).joinToString(" ")

    /** The host of a URL, for messages ("amazon.com"). */
    fun host(url: String): String = try { java.net.URI(url).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
}
