package com.anjy.atlas

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * Atlas's look in one place: colours and type, shared by every screen (and mirrored in the PC app's style.css).
 * The colours themselves live in tools/themes.json (four themes, each with a dark and a light look); ThemeData.kt and
 * the PC's CSS theme block are generated from it, with every text colour checked against WCAG AA.
 *
 * What follows describes the original indigo theme; the other themes follow the same roles:
 * Colour. Everything grows from the logo's deep indigo (hue about 245°):
 *  - backgrounds are that indigo at very low lightness, stepping up evenly for each raised layer;
 *  - the primary accent is the logo's blue-violet (a lighter tint of it, accentInk, is used for small text at night); its neighbours on the wheel (sky blue, violet) are the
 *    supporting lights, so colour sits together (an analogous scheme);
 *  - one warm colour, amber, sits across the wheel from indigo. It is the only warm thing on screen, so it is
 *    kept for what needs your eye: something waiting on you, a warning;
 *  - done (a cool green) and danger (a cool rose) lean towards blue so they don't fight the rest.
 * Every text colour clears WCAG AA (4.5:1) on the surfaces it is used on, in both looks.
 */
class Palette(
    val bg: Int, val surface: Int, val surface2: Int, val line: Int,
    val fg: Int, val muted: Int, val faint: Int,
    val accent: Int, val accentInk: Int, val blue: Int, val warm: Int, val done: Int, val danger: Int,
    /** The filled (primary) button: top and bottom of its gradient, and the text on it (clears 4.5:1 on both). */
    val primaryTop: Int, val primaryBottom: Int, val onPrimary: Int = Color.WHITE,
    /** The floating nav bar's fill. */
    val nav: Int = surface
) {
    /** The top and bottom of an always-dark screen's backdrop: the page colour with a breath of the accent above, a shade deeper below. */
    val glowTop: Int get() = blend(bg, accent, 0.07f)
    val glowBottom: Int get() = blend(bg, 0xFF000000.toInt(), 0.18f)

    companion object {
        /** The current theme's dark look. */
        val DARK get() = Themes.current.dark
        /** The current theme's light look. */
        val LIGHT get() = Themes.current.light
        /** The night look, for the screens that are always dark (alarm, focus, Atlas Bot, the assist panel). */
        val NIGHT get() = Themes.current.dark
    }
}

/** [a] moved [t] of the way to [b] (alpha from [a]). Plain bit maths, so it also runs in unit tests. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return (a ushr 24 shl 24) or (ch(a shr 16 and 255, b shr 16 and 255) shl 16) or (ch(a shr 8 and 255, b shr 8 and 255) shl 8) or ch(a and 255, b and 255)
}

/** [c] with alpha [a] (0..255). */
fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)

/** WCAG relative luminance of an opaque colour. */
fun luminance(c: Int): Double {
    fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(c shr 16 and 255) + 0.7152 * lin(c shr 8 and 255) + 0.0722 * lin(c and 255)
}

/** Text that reads on a filled [fill]: near-black or white, whichever has more contrast (bright gold and coral get dark ink). */
fun inkOn(fill: Int): Int {
    val l = luminance(fill)
    val onWhite = 1.05 / (l + 0.05); val onBlack = (l + 0.05) / (luminance(0xFF0B0B10.toInt()) + 0.05)
    return if (onBlack > onWhite) 0xFF0B0B10.toInt() else 0xFFFFFFFF.toInt()
}

/** One colour theme: a dark and a light look built on the same colour idea (see tools/themes.json). */
class Theme(val id: String, val name: String, val aliases: List<String>, val dark: Palette, val light: Palette)

/**
 * The colour themes you can pick in Me → Look (or ask Atlas: "switch to the gold theme"). The choice is per device:
 * it is kept in this device's settings and never synced. Midnight & Gold is the default.
 */
object Themes {
    const val KEY = "look_theme"
    val ALL: List<Theme> get() = ThemeData.ALL
    @Volatile var current: Theme = ThemeData.ALL.first { it.id == ThemeData.DEFAULT }
        private set

    fun byId(id: String?): Theme? = ALL.firstOrNull { it.id == id }

    /** "gold", "the cyan one", "forest & coral" → a theme, or null. */
    fun find(words: String): Theme? {
        val w = words.trim().lowercase()
        if (w.isEmpty()) return null
        return ALL.firstOrNull { it.id == w || it.name.lowercase() == w }
            ?: ALL.firstOrNull { t -> t.aliases.any { a -> w == a || Regex("\\b" + Regex.escape(a) + "\\b").containsMatchIn(w) } }
    }

    /** Read this device's choice (cheap: one preference). Called as the app starts, in every process. */
    fun load(ctx: Context) { current = byId(Store.getString(ctx, KEY)) ?: byId(ThemeData.DEFAULT)!! }

    fun set(ctx: Context, t: Theme) { Store.putString(ctx, KEY, t.id); current = t }
}

/**
 * The type scale. Newsreader (a serif made for reading) carries everything you read: titles, the greeting,
 * Atlas's replies, what you write. Geist (a quiet sans) is for the small working parts: buttons, labels, tags.
 * Geist Mono is for code only.
 */
object Type {
    // sizes in sp, one step apart by about 1.2
    const val DISPLAY = 34f
    const val TITLE = 28f
    const val HEADING = 22f
    const val SUBHEAD = 19f
    /** Reading text (serif). A serif looks smaller than a sans at the same size, so it sits one point up. */
    const val READ = 17f
    const val BODY = 15f
    const val LABEL = 13f
    const val CAPTION = 12f
    /** Line heights (multipliers): tight for big titles, open for reading. */
    const val LEAD_DISPLAY = 1.08f
    const val LEAD_READ = 1.42f
    const val LEAD_BODY = 1.3f
    /** Letter spacing (em): big serif tightens a touch; small capsule labels open a touch. */
    const val TRACK_DISPLAY = -0.015f
    const val TRACK_LABEL = 0.02f

    private val cache = HashMap<Int, Typeface>()
    private fun font(ctx: Context, id: Int, fallback: Typeface): Typeface =
        cache[id] ?: (try { ctx.resources.getFont(id) } catch (_: Throwable) { fallback }).also { cache[id] = it }

    fun serif(ctx: Context) = font(ctx, R.font.newsreader_regular, Typeface.SERIF)
    fun serifLight(ctx: Context) = font(ctx, R.font.newsreader_light, Typeface.SERIF)
    fun serifMedium(ctx: Context) = font(ctx, R.font.newsreader_medium, Typeface.create(Typeface.SERIF, Typeface.BOLD))
    fun serifSemi(ctx: Context) = font(ctx, R.font.newsreader_semibold, Typeface.create(Typeface.SERIF, Typeface.BOLD))
    fun serifItalic(ctx: Context) = font(ctx, R.font.newsreader_italic, Typeface.create(Typeface.SERIF, Typeface.ITALIC))
    fun sans(ctx: Context) = font(ctx, R.font.geist_regular, Typeface.DEFAULT)
    fun sansMedium(ctx: Context) = font(ctx, R.font.geist_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun sansSemi(ctx: Context) = font(ctx, R.font.geist_semibold, Typeface.DEFAULT_BOLD)
    fun mono(ctx: Context) = font(ctx, R.font.geist_mono, Typeface.MONOSPACE)
}
