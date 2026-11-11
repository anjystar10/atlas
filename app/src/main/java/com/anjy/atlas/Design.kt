package com.anjy.atlas

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * Atlas's look in one place: colours and type, shared by every screen (and mirrored in the PC app's style.css).
 *
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
    /** The filled (primary) button: top and bottom of its gradient; white text clears 4.5:1 on both. */
    val primaryTop: Int, val primaryBottom: Int
) {
    companion object {
        private fun c(hex: String) = Color.parseColor(hex)
        val DARK = Palette(
            bg = c("#05050F"), surface = c("#0E0F22"), surface2 = c("#17193A"), line = c("#262A52"),
            fg = c("#EEF0FF"), muted = c("#9BA1C9"), faint = c("#7C81AD"),
            accent = c("#8B6CFF"), accentInk = c("#A594FF"), blue = c("#6FA8FF"), warm = c("#F2B866"), done = c("#5FD3A4"), danger = c("#FF7A86"),
            primaryTop = c("#5C6CF0"), primaryBottom = c("#4B3BD6")
        )
        val LIGHT = Palette(
            bg = c("#C5C8EC"), surface = c("#DDDFF5"), surface2 = c("#D3D5F2"), line = c("#F3F3FD"),
            fg = c("#0A0B1F"), muted = c("#3E4170"), faint = c("#4B4F7A"),
            accent = c("#4A32C8"), accentInk = c("#4A32C8"), blue = c("#1F45B5"), warm = c("#7A4700"), done = c("#085C3E"), danger = c("#9C1A30"),
            primaryTop = c("#5C6CF0"), primaryBottom = c("#4B3BD6")
        )
        /** The night look, for the screens that are always dark (alarm, focus, Atlas Bot, the assist panel). */
        val NIGHT get() = DARK
    }
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
