package com.anjy.atlas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.URLSpan

/**
 * Turns Atlas's lightly formatted replies into neat text: headings, bullet and numbered lists, bold, italics,
 * quotes, inline code and links. Code blocks come back as separate blocks (shown in their own box).
 * Anything it doesn't recognise is shown as written, minus stray symbols.
 */
object Markdown {
    sealed class Block
    class Prose(val text: CharSequence) : Block()
    class Code(val text: String, val lang: String) : Block()

    class Style(
        val body: Typeface, val bold: Typeface, val heading: Typeface, val mono: Typeface,
        val ink: Int, val muted: Int, val accent: Int, val codeBg: Int, val density: Float, val textSizeSp: Float,
        /** A true italic of the body face; without one the body face is slanted. */
        val italic: Typeface? = null
    )

    private class Face(val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(p: TextPaint) { p.typeface = face }
        override fun updateMeasureState(p: TextPaint) { p.typeface = face }
    }

    /** A marker drawn in the margin ("•" or "3."), with the wrapped lines indented under the text. */
    private class Marker(private val mark: String, private val width: Int, private val color: Int, private val face: Typeface) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = width
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
                                       text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
            val oldC = p.color; val oldF = p.typeface; val oldA = p.textAlign
            p.color = color; p.typeface = face; p.textAlign = Paint.Align.RIGHT
            c.drawText(mark, (x + dir * (width - width * 0.28f)), baseline.toFloat(), p)
            p.color = oldC; p.typeface = oldF; p.textAlign = oldA
        }
    }

    /** A thin bar in the margin for quotes. */
    private class QuoteBar(private val color: Int, private val gap: Int, private val stripe: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = stripe + gap
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
                                       text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            val s = p.style; val col = p.color
            p.style = Paint.Style.FILL; p.color = color
            c.drawRect(x.toFloat(), top.toFloat(), (x + dir * stripe).toFloat(), bottom.toFloat(), p)
            p.style = s; p.color = col
        }
    }

    /** Extra space above a line (paragraph and heading rhythm). */
    private class SpaceAbove(private val px: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if ((text as? Spanned)?.getSpanStart(this) == start) { fm.ascent -= px; fm.top -= px }
        }
    }

    private val FENCE = Regex("""^\s*```\s*([\w+-]*)\s*$""")
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*•+]\s+(.*)$""")
    private val NUMBER = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(src: String, st: Style): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var sb = SpannableStringBuilder()
        var i = 0
        var gapNext = false
        fun flush() { if (sb.isNotEmpty()) { trimEnd(sb); blocks.add(Prose(sb)) }; sb = SpannableStringBuilder(); gapNext = false }
        val dp = st.density
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                // a code block, up to the closing fence (or the end)
                val lang = fence.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && !FENCE.matches(lines[i])) { code.append(lines[i]).append('\n'); i++ }
                i++
                flush()
                blocks.add(Code(code.toString().trimEnd('\n'), lang))
                continue
            }
            if (line.isBlank()) { if (sb.isNotEmpty()) gapNext = true; i++; continue }
            if (TABLE_SEP.matches(line)) { i++; continue }
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            val gap = if (start > 0 && gapNext) (10 * dp).toInt() else 0
            gapNext = false
            val h = HEADING.matchEntire(line)
            val b = BULLET.matchEntire(line)
            val n = NUMBER.matchEntire(line)
            val q = QUOTE.matchEntire(line)
            when {
                RULE.matches(line) -> { sb.append(" "); sb.setSpan(ForegroundColorSpan(st.muted), start, sb.length, 0) }
                h != null -> {
                    val level = h.groupValues[1].length
                    inline(sb, h.groupValues[2].trim().trimEnd('#').trim(), st)
                    val size = when (level) { 1 -> st.textSizeSp + 6f; 2 -> st.textSizeSp + 3f; else -> st.textSizeSp + 1f }
                    sb.setSpan(AbsoluteSizeSpan(size.toInt(), true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(Face(if (level <= 2) st.heading else st.bold), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (start > 0) sb.setSpan(SpaceAbove((14 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                b != null -> {
                    val depth = (b.groupValues[1].length / 2).coerceAtMost(3)
                    inline(sb, b.groupValues[2], st)
                    sb.setSpan(LeadingMarginSpan.Standard((depth * 18 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(Marker(if (depth == 0) "•" else "◦", (20 * dp).toInt(), st.accent, st.bold), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (gap > 0) sb.setSpan(SpaceAbove(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    else if (start > 0) sb.setSpan(SpaceAbove((4 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                n != null -> {
                    val depth = (n.groupValues[1].length / 2).coerceAtMost(3)
                    inline(sb, n.groupValues[3], st)
                    sb.setSpan(LeadingMarginSpan.Standard((depth * 18 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(Marker("${n.groupValues[2]}.", (26 * dp).toInt(), st.accent, st.bold), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (gap > 0) sb.setSpan(SpaceAbove(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    else if (start > 0) sb.setSpan(SpaceAbove((4 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                q != null -> {
                    inline(sb, q.groupValues[1], st)
                    sb.setSpan(QuoteBar(st.accent, (12 * dp).toInt(), (3 * dp).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(st.muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (gap > 0) sb.setSpan(SpaceAbove(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.trimStart().startsWith("|") -> {
                    // a table row: cells separated by a dot, the header row bold
                    val cells = line.trim().trim('|').split("|").map { it.trim() }
                    inline(sb, cells.joinToString("  ·  "), st)
                    val nextIsSep = i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])
                    if (nextIsSep) sb.setSpan(Face(st.bold), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (gap > 0) sb.setSpan(SpaceAbove(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    inline(sb, line.trim(), st)
                    if (gap > 0) sb.setSpan(SpaceAbove(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            i++
        }
        flush()
        return blocks
    }

    private fun trimEnd(sb: SpannableStringBuilder) { while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length) }

    private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?!\w)""")

    /** Bold, italics, inline code and links within one line. */
    private fun inline(sb: SpannableStringBuilder, s: String, st: Style) {
        var last = 0
        for (m in INLINE.findAll(s)) {
            sb.append(s, last, m.range.first)
            val start = sb.length
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> { inline(sb, g[1].ifEmpty { g[2] }, st); sb.setSpan(Face(st.bold), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[3].isNotEmpty() -> {
                    sb.append(g[3])
                    sb.setSpan(Face(st.mono), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(st.codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[4].isNotEmpty() -> {
                    sb.append(g[4])
                    sb.setSpan(URLSpan(g[5]), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(st.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    sb.append(g[6].ifEmpty { g[7] })
                    sb.setSpan(st.italic?.let { Face(it) } ?: android.text.style.StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
    }
}
