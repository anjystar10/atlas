package com.anjy.atlas

/**
 * Email, the plain part (the phone's copy of the PC's mailtext.js): which servers an address uses, HTML → readable
 * text, leaving out the quoted history under a reply, quoting for a reply, reading a search like "from:anna is:unread",
 * email ids, and what went wrong in plain words. No Android or network here: tools/mail-fixtures.json holds the cases
 * both apps must answer the same way (MailTextTest).
 */
object MailText {

    // ---------------------------------------------------------------- which servers

    class Server(val host: String, val port: Int, val secure: Boolean)
    class Preset(val id: String, val name: String, val imap: Server?, val smtp: Server?, val webmail: String?, val help: String, val known: Boolean)

    private class P(val id: String, val name: String, val domains: List<String>, val imap: Server? = null, val smtp: Server? = null, val webmail: String? = null, val help: String)
    private val PROVIDERS = listOf(
        P("gmail", "Gmail", listOf("gmail.com", "googlemail.com"), Server("imap.gmail.com", 993, true), Server("smtp.gmail.com", 465, true),
            help = "Gmail needs an app password, not your normal password: turn on 2-Step Verification, then make one at https://myaccount.google.com/apppasswords and paste its 16 letters here."),
        P("yahoo", "Yahoo Mail", listOf("yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.fr"), Server("imap.mail.yahoo.com", 993, true), Server("smtp.mail.yahoo.com", 465, true),
            help = "Yahoo needs an app password: Yahoo Account Security → Generate app password, then paste it here."),
        P("icloud", "iCloud Mail", listOf("icloud.com", "me.com", "mac.com"), Server("imap.mail.me.com", 993, true), Server("smtp.mail.me.com", 587, false),
            help = "iCloud needs an app-specific password: account.apple.com → Sign-In and Security → App-Specific Passwords."),
        P("aol", "AOL Mail", listOf("aol.com"), Server("imap.aol.com", 993, true), Server("smtp.aol.com", 465, true),
            help = "AOL needs an app password: Account Security → Generate app password."),
        P("zoho", "Zoho Mail", listOf("zoho.com", "zohomail.com"), Server("imap.zoho.com", 993, true), Server("smtp.zoho.com", 465, true),
            help = "With two-factor sign-in on, Zoho needs an app-specific password (Zoho Accounts → Security → App Passwords)."),
        P("fastmail", "Fastmail", listOf("fastmail.com", "fastmail.fm"), Server("imap.fastmail.com", 993, true), Server("smtp.fastmail.com", 465, true),
            help = "Fastmail needs an app password: Settings → Privacy & Security → App passwords."),
        P("gmx", "GMX", listOf("gmx.com", "gmx.net", "gmx.de"), Server("imap.gmx.com", 993, true), Server("mail.gmx.com", 587, false),
            help = "In GMX settings, allow access through POP3 and IMAP first."),
        P("outlook", "Outlook.com", listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.co.uk", "hotmail.co.uk"), webmail = "https://outlook.live.com/mail/",
            help = "Outlook.com stopped letting apps sign in with a password (September 2024), so Atlas reads it in its own browser instead: you sign in there once."),
        P("proton", "Proton Mail", listOf("proton.me", "protonmail.com", "pm.me"), webmail = "https://mail.proton.me/",
            help = "Proton Mail only allows other apps through its paid Bridge app, so Atlas reads it in its own browser instead: you sign in there once."),
    )

    fun domainOf(email: String?): String = Regex("@([^@\\s]+)$").find((email ?: "").trim().lowercase())?.groupValues?.get(1) ?: ""

    fun presetFor(email: String?): Preset {
        val d = domainOf(email)
        val p = PROVIDERS.firstOrNull { d in it.domains }
        if (p != null) return Preset(p.id, p.name, p.imap, p.smtp, p.webmail, p.help, true)
        return Preset("other", d.ifEmpty { "Email" }, Server(if (d.isNotEmpty()) "imap.$d" else "", 993, true), Server(if (d.isNotEmpty()) "smtp.$d" else "", 465, true), null,
            "If your provider uses two-step sign-in, it probably needs an app password rather than your normal one.", false)
    }

    private val emailRe = Regex("^[^\\s@<>(),;:\"]+@[^\\s@<>(),;:\"]+\\.[^\\s@<>(),;:\"]+$")
    fun validEmail(s: String?) = emailRe.matches((s ?: "").trim())

    /** "Anna <a@x.com>, b@y.com" → ["Anna <a@x.com>", "b@y.com"]; null when one of them isn't an address. */
    fun splitAddresses(s: String?): List<String>? {
        val parts = (s ?: "").split(Regex("[,;]\\s*(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")).map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) { val m = Regex("<([^>]+)>\\s*$").find(p); if (!validEmail(m?.groupValues?.get(1) ?: p)) return null }
        return parts
    }

    /** "Anna Smith <a@x.org>"; a name with a comma or the like is quoted so it stays one address. */
    fun addr(name: String?, address: String?): String {
        val n = (name ?: "").trim(); val a = address ?: ""
        if (n.isEmpty()) return a
        return if (Regex("[,;<>\"@()]").containsMatchIn(n)) "\"${n.replace(Regex("[\"\\\\]"), "")}\" <$a>" else "$n <$a>"
    }

    // ---------------------------------------------------------------- HTML → text

    private val ENT = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·", "euro" to "€", "pound" to "£",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä", "aring" to "å", "oacute" to "ó", "ograve" to "ò",
        "ocirc" to "ô", "ouml" to "ö", "oslash" to "ø", "iacute" to "í", "igrave" to "ì", "iuml" to "ï", "uacute" to "ú", "ugrave" to "ù", "uuml" to "ü", "ntilde" to "ñ", "ccedil" to "ç",
        "szlig" to "ß", "Eacute" to "É", "Agrave" to "À", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü")

    fun decodeEntities(s: String): String = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        if (e[0] == '#') {
            val n = (if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.substring(2).toLongOrNull(16) else e.substring(1).toLongOrNull()) ?: 0
            if (n in 1..0x10FFFF) String(Character.toChars(n.toInt())) else ""
        } else ENT[e] ?: ENT[e.lowercase()] ?: m.value
    }

    private val I = setOf(RegexOption.IGNORE_CASE)
    fun htmlToText(html: String?): String {
        var s = html ?: ""
        s = s.replace(Regex("<!--[\\s\\S]*?-->"), "").replace(Regex("<(script|style|head|title)\\b[\\s\\S]*?</\\1\\s*>", I), "")
        s = Regex("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a\\s*>", I).replace(s) { m ->
            val t = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim(); val u = decodeEntities(m.groupValues[1].trim())
            when { t.isEmpty() -> ""; Regex("^mailto:", I).containsMatchIn(u) || t == u || !Regex("^https?:", I).containsMatchIn(u) -> t; else -> "$t ($u)" }
        }
        s = s.replace(Regex("<br\\s*/?>", I), "\n").replace(Regex("<li\\b[^>]*>", I), "\n• ")
            .replace(Regex("</(p|div|h[1-6]|tr|table|ul|ol|blockquote)\\s*>", I), "\n").replace(Regex("<(p|div|h[1-6]|tr|table|ul|ol|blockquote)\\b[^>]*>", I), "\n")
            .replace(Regex("</t[dh]\\s*>", I), "\t")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s).replace('\u00a0', ' ').replace("\r", "")
        s = s.split("\n").joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
        return s.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---------------------------------------------------------------- the quoted history under a reply

    class Stripped(val text: String, val hidden: Boolean)
    private val QUOTE_HEADS = listOf(
        Regex("^On .{4,200}(wrote|écrit|schrieb|escribió):\\s*$", I),
        Regex("^-{2,}\\s*(Original Message|Forwarded message|Mensaje original|Message d'origine)\\s*-{2,}\\s*$", I),
        Regex("^_{5,}\\s*$"),
        Regex("^From:\\s.+$", I),
    )
    fun stripQuoted(text: String?): Stripped {
        val all = (text ?: "")
        val lines = all.replace("\r", "").split("\n")
        var cut = -1
        loop@ for (i in lines.indices) {
            val l = lines[i].trim()
            if (QUOTE_HEADS.any { it.containsMatchIn(l) }) {
                if (Regex("^From:", I).containsMatchIn(l) && lines.subList(i + 1, minOf(lines.size, i + 5)).none { Regex("^(Sent|Date|To|Subject):", I).containsMatchIn(it.trim()) }) continue
                cut = i; break
            }
            if (Regex("^On .{4,120}$", I).matches(l) && i + 1 < lines.size && Regex("(wrote|écrit|schrieb|escribió):\\s*$", I).containsMatchIn(lines[i + 1].trim())) { cut = i; break }
            if (l.startsWith(">")) {
                var j = i
                while (j < lines.size && (lines[j].trim().startsWith(">") || lines[j].isBlank())) j++
                if (j >= lines.size || lines.subList(j, lines.size).all { it.isBlank() }) { cut = i; break@loop }
            }
        }
        if (cut < 0) return Stripped(all.trim(), false)
        val head = lines.subList(0, cut).joinToString("\n").trim()
        if (head.isEmpty()) return Stripped(all.trim(), false)
        return Stripped(head, true)
    }

    /** The body of a reply: what they write, then "On <date>, <name> wrote:" and the original, each line with "> ". */
    fun quoteForReply(body: String?, dateMs: Long?, from: String?, original: String?): String {
        val d = if (dateMs != null) java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm 'GMT'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(dateMs)) else "an earlier date"
        val who = from?.takeIf { it.isNotEmpty() } ?: "they"
        val quoted = (original ?: "").replace("\r", "").trim().split("\n").joinToString("\n") { "> $it" }
        return "${(body ?: "").trim()}\n\nOn $d, $who wrote:\n$quoted\n"
    }
    fun replySubject(s: String?): String { val t = (s ?: "").trim(); return if (Regex("^(re|aw|sv|antw)\\s*:", I).containsMatchIn(t)) t else "Re: ${t.ifEmpty { "(no subject)" }}" }

    // ---------------------------------------------------------------- searches

    class Query(val from: String? = null, val to: String? = null, val cc: String? = null, val subject: String? = null, val unread: Boolean? = null, val starred: Boolean? = null,
                val since: String? = null, val before: String? = null, val text: String? = null)
    fun parseQuery(q: String?): Query {
        var from: String? = null; var to: String? = null; var cc: String? = null; var subject: String? = null; var unread: Boolean? = null; var starred: Boolean? = null
        var since: String? = null; var before: String? = null; val rest = mutableListOf<String>()
        val date = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$")
        for (m in Regex("(\\w+):(\"([^\"]*)\"|\\S+)|\"([^\"]*)\"|(\\S+)").findAll(q ?: "")) {
            val g = m.groups
            if (g[1] != null) {
                val k = g[1]!!.value.lowercase(); val v = g[3]?.value ?: g[2]!!.value
                when {
                    k == "from" -> from = v; k == "to" -> to = v; k == "cc" -> cc = v; k == "subject" -> subject = v
                    k == "is" && v.equals("unread", true) -> unread = true
                    k == "is" && v.equals("read", true) -> unread = false
                    k == "is" && (v.equals("starred", true) || v.equals("flagged", true)) -> starred = true
                    (k == "after" || k == "since" || k == "newer") && date.matches(v) -> since = v.replace('/', '-')
                    (k == "before" || k == "older") && date.matches(v) -> before = v.replace('/', '-')
                    else -> rest.add(m.value)
                }
            } else rest.add(g[4]?.value ?: g[5]!!.value)
        }
        return Query(from, to, cc, subject, unread, starred, since, before, if (rest.isEmpty()) null else rest.joinToString(" "))
    }

    // ---------------------------------------------------------------- ids: "<folder>#<uid>"

    class Id(val folder: String, val uid: Long)
    fun makeId(folder: String, uid: Long) = "$folder#$uid"
    fun parseId(id: String?): Id? {
        val s = (id ?: "").trim(); val i = s.lastIndexOf('#')
        if (i <= 0) return null
        val uid = s.substring(i + 1).toLongOrNull() ?: return null
        return if (uid > 0) Id(s.substring(0, i), uid) else null
    }
    fun parseIds(v: String?): List<Id> = (v ?: "").split(Regex("[\\s,]+")).mapNotNull { parseId(it) }

    // ---------------------------------------------------------------- what went wrong

    fun friendlyError(e: Throwable?, preset: Preset?): String {
        var msg = ""; var t: Throwable? = e; var n = 0
        while (t != null && n++ < 5) { msg += " " + (t.message ?: t.javaClass.simpleName); t = t.cause }
        val name = e?.javaClass?.simpleName ?: ""
        msg = msg.trim()
        if (name == "AuthenticationFailedException" || Regex("AUTHENTICATIONFAILED|Invalid credentials|authentication failed|LOGIN failed|Application-specific password|535|Username and Password not accepted|AUTHENTICATE failed", I).containsMatchIn(msg))
            return "${preset?.name ?: "The mail server"} turned down the password. ${preset?.help ?: "Check the address and password."}"
        if (Regex("UnknownHost|Unable to resolve host|No address associated", I).containsMatchIn(msg + name)) return "Couldn't find the mail server. Check the server name, or your internet connection."
        if (Regex("ConnectException|SocketTimeout|timed? ?out|Connection refused|Connection reset|Network is unreachable", I).containsMatchIn(msg + name)) return "Couldn't reach the mail server. Check your internet connection (and the server name and port)."
        if (Regex("certificate|SSLHandshake|CertPath", I).containsMatchIn(msg + name)) return "The mail server's security certificate didn't check out, so Atlas didn't connect."
        if (Regex("Mailbox doesn't exist|NONEXISTENT|Unknown Mailbox|no such mailbox|folder not found", I).containsMatchIn(msg)) return "That folder doesn't exist on the mail server."
        return if (msg.isNotEmpty()) "The mail server said: ${msg.take(200)}" else "Something went wrong with the mail server."
    }
}
