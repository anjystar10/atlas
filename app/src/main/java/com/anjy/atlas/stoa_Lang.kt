package com.anjy.atlas

import android.content.Context
import android.widget.TextView
import org.json.JSONObject

/**
 * Translations: every piece of UI text passes through L.t(). English is the source; other languages
 * are looked up from res/raw/tr_xx.json. Anything without a translation (numbers, names, your own
 * exercises, some longer generated sentences) shows in English.
 *
 * A sentence with numbers in it is found by its shape: "12 days kept" looks up "# days kept", and each # in
 * the translation takes the next number (or #1, #2… pick them by position when a language puts them in
 * another order).
 */
object L {
    val languages = listOf("en" to "English", "zh" to "中文 (简体)", "es" to "Español", "fr" to "Français", "pt" to "Português", "hi" to "हिन्दी", "de" to "Deutsch")
    @Volatile var lang = "en"; private set
    @Volatile private var dict: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun init(c: Context) {
        val saved = Store.getString(c, "lang")
        val sys = java.util.Locale.getDefault().language
        load(c, saved ?: (if (languages.any { it.first == sys }) sys else "en"))
    }

    /** For what runs while the app itself is closed (a reminder, the widget, the step counter): loads the language once. */
    fun ensure(c: Context) { if (!loaded) try { init(c.applicationContext ?: c) } catch (e: Exception) { } }

    fun load(c: Context, code: String) {
        lang = code
        // by id, not by name: the app's id (com.kinganjy.stoa) isn't the package its resources were built under, so a
        // lookup by name found nothing and every language quietly showed English
        // Atlas ships in English only for now: no translation tables
        dict = emptyMap()
        loaded = true
    }

    /** For the rule tests: a dictionary without the app's resources. */
    fun useDict(m: Map<String, String>, code: String = "xx") { dict = m; lang = code; loaded = true }

    private val NUM = Regex("\\d+(?:[.,]\\d+)*")
    private val SLOT = Regex("#(\\d?)")

    fun t(s: String): String {
        val d = dict
        if (d.isEmpty()) return s
        d[s]?.let { return it }
        if (s.none { it.isDigit() }) return s
        // the same words with other numbers: look up its shape, then put the numbers back
        val nums = NUM.findAll(s).map { it.value }.toList()
        val tr = d[NUM.replace(s, "#")] ?: return s
        var next = 0
        return SLOT.replace(tr) { m ->
            val at = m.groupValues[1].toIntOrNull()
            (if (at != null) nums.getOrNull(at - 1) else nums.getOrNull(next++)) ?: m.value
        }
    }

    /**
     * A word whose English has two meanings ("Home" the tab and "Home" the place you train): the translation is found by
     * [key], and English shows [english].
     */
    fun tc(key: String, english: String): String = dict[key] ?: english

    /** The language's own names for days and months. */
    fun locale(): java.util.Locale = java.util.Locale.forLanguageTag(lang)

    /** "Ada", "Ada and Tobi", "Ada, Tobi and Ife" in the app's language. */
    fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(t(", ")) + t(" and ") + items.last()
    }

    /** A translated pattern with words put in ("%s's workout"): [key] is looked up, then filled with [args]. */
    fun f(key: String, vararg args: Any?): String =
        // numbers in the app's own style (1,234), whatever the phone's language
        try { String.format(java.util.Locale.US, t(key), *args) } catch (e: Exception) { try { String.format(java.util.Locale.US, key, *args) } catch (x: Exception) { key } }
}

/** Emoji and pictographs, but not the check and cross marks (✓ ✕ ✗), arrows, dots or shapes the app uses as type. */
fun isEmoji(cp: Int): Boolean = when (cp) {
    in 0x2713..0x2718 -> false
    in 0x1F000..0x1FAFF, in 0x2600..0x27BF, in 0x2B00..0x2BFF, in 0x231A..0x231B, in 0x23E9..0x23FA, in 0xE0020..0xE007F -> true
    0x203C, 0x2049, 0x2139, 0x24C2, 0x3030, 0x303D, 0x3297, 0x3299 -> true
    else -> false
}

/**
 * Words without their emoji ("🔥  Lock In Today" is "Lock In Today", "Perfect 💯" is "Perfect"), spaces tidied. The
 * joins and selectors that only hold an emoji together go with it. Text that is nothing but an emoji stays as it is,
 * so a label is never left empty.
 */
fun noEmoji(s: String): String {
    if (s.none { it.code >= 0x2000 }) return s
    val sb = StringBuilder(s.length)
    var i = 0; var lastEmoji = false; var dropped = false
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val drop = isEmoji(cp) || cp == 0xFE0F || cp == 0x20E3 || (cp == 0x200D && lastEmoji)
        if (drop) dropped = true else sb.appendCodePoint(cp)
        if (cp != 0xFE0F && cp != 0x200D) lastEmoji = isEmoji(cp)
        i += Character.charCount(cp)
    }
    // untouched text keeps its own spacing (French puts a space before ? and !)
    if (!dropped) return s
    val out = sb.toString().replace(Regex("[ \\t]{2,}"), " ").replace(Regex("^[\\s·]+|[\\s·]+$"), "").replace(Regex(" +([.,!?:;])$"), "$1")
    return if (out.isBlank()) s else out
}

/**
 * A TextView whose words follow the app's language even when they change later (a status line, a counter, a
 * toggle's label). Plain text is looked up; styled text is left as it is. A [plain] one (titles, buttons, chips)
 * also drops emoji, now and whenever its words change.
 */
internal class LText(c: Context) : TextView(c) {
    var plain = false
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(if (text is String) L.t(text).let { if (plain) noEmoji(it) else it } else text, type)
    }

    // words that act as a button (no background of their own to show a press) dim while held, so a tap answers at once
    private var restAlpha = -1f
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!isClickable || background != null) return
        if (pressed) { if (restAlpha < 0f) { restAlpha = alpha; alpha = restAlpha * 0.5f } }
        else if (restAlpha >= 0f) { alpha = restAlpha; restAlpha = -1f }
    }
}
