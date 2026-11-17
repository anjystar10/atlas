package com.anjy.atlas

import java.util.regex.Pattern

/** Which bits of a reply become tappable: plain web addresses and phone numbers (emails use Android's own pattern). */
object LinkText {
    /** A candidate phone number: digits with the usual spaces, dots, dashes and brackets, not inside a word, path or time. */
    val PHONE: Pattern = Pattern.compile("(?<![\\w.:/@-])\\+?\\(?\\d[\\d ().-]{5,}\\d(?![\\w:/@])")
    private val DATE = Regex("^\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}$|^\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}$")
    private val BARE_SITE = Regex("^[\\w-]+(\\.[\\w-]+)*\\.(com|org|net|io|ai|dev|app|co|uk|gov|edu|me|info)(/\\S*)?$", RegexOption.IGNORE_CASE)

    /** tel: link for [n], or null when it's more likely a date, an amount or an id. */
    fun phone(n: String): String? {
        val t = n.trim()
        val digits = t.count { it.isDigit() }
        if (digits < 7 || digits > 15) return null
        if (DATE.matches(t)) return null
        if (t.count { it == '.' } >= 2 && !t.contains(' ')) return null            // 1.2.3.4, 10.00.15 (versions, addresses)
        val separated = t.any { it == ' ' || it == '-' || it == '(' || it == '+' }
        if (!separated && digits < 10) return null                                   // 1234567 alone is a number, not a phone
        return "tel:" + t.filter { it.isDigit() || it == '+' }
    }

    /** https: link for a web address in prose, or null for something that only looks like one (an email, "e.g.", file.txt). */
    fun web(w: String): String? = when {
        w.contains('@') -> null
        w.startsWith("http://", true) || w.startsWith("https://", true) -> w
        w.startsWith("www.", true) -> "https://$w"
        BARE_SITE.matches(w) -> "https://$w"
        else -> null
    }
}
