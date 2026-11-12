package com.anjy.atlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * A document Atlas Bot hands over, typeset as a PDF on the phone: A4 pages, Newsreader for reading, Geist for labels,
 * the Atlas palette on white paper. The Markdown is laid out by Android's own text engine (the same spans the chat
 * uses, so lists, bold, italics, quotes and code look the same) and tables are drawn as real grids. Text stays text
 * in the PDF, so it can be searched and copied.
 */
object DocPdf {
    // A4 in PDF points (1/72 inch), with the margins of a printed report
    private const val W = 595; private const val H = 842
    private const val M_X = 56f; private const val M_TOP = 60f; private const val M_BOTTOM = 62f
    private val CW = W - 2 * M_X
    private val INK = Color.parseColor("#14152E"); private val MUTED = Color.parseColor("#4B4F7A")
    private val ACCENT = Color.parseColor("#4A32C8"); private val LINE = Color.parseColor("#D3D5F2")
    private val CODE_BG = Color.parseColor("#F3F4FC"); private val HEAD_BG = Color.parseColor("#ECEBFB")

    private class Page(val doc: PdfDocument, val number: Int) {
        val page: PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
        val canvas: Canvas get() = page.canvas
        var y = M_TOP
    }

    /** Writes the document to [out]. */
    fun write(ctx: Context, title: String, markdown: String, stamp: String, out: OutputStream) {
        val serif = Type.serif(ctx); val semi = Type.serifSemi(ctx); val medium = Type.serifMedium(ctx); val italic = Type.serifItalic(ctx)
        val sans = Type.sans(ctx); val sansSemi = Type.sansSemi(ctx); val mono = Type.mono(ctx)
        val st = Markdown.Style(serif, semi, medium, mono, INK, MUTED, ACCENT, Color.parseColor("#EEF0FB"), 1f, 11.5f, italic = italic)
        val doc = PdfDocument()
        var pages = 0
        lateinit var p: Page
        fun newPage() { if (pages > 0) finish(p, pages, sans); pages++; p = Page(doc, pages) }
        newPage()

        // the head: a small label, the title, the date, an accent rule
        p.y = draw(p, label("ATLAS BOT", sansSemi, 8f, ACCENT, letter = 0.14f), p.y) + 6f
        p.y = draw(p, layout(title, paint(medium, 24f, INK), CW, 1.12f), p.y) + 4f
        p.y = draw(p, label(stamp, sans, 8.5f, MUTED), p.y) + 10f
        p.canvas.drawRect(M_X, p.y, M_X + CW, p.y + 1.2f, Paint().apply { color = ACCENT }); p.y += 18f

        fun ensure(h: Float) { if (p.y + h > H - M_BOTTOM && p.y > M_TOP + 1f) newPage() }
        /** Lays [l] out across pages, breaking between lines. */
        fun flow(l: StaticLayout, padX: Float = 0f, bg: Int? = null, padY: Float = 0f) {
            var line = 0
            while (line < l.lineCount) {
                val room = H - M_BOTTOM - p.y - 2 * padY
                var end = line
                while (end < l.lineCount && l.getLineBottom(end) - l.getLineTop(line) <= room) end++
                if (end == line) { if (p.y > M_TOP + 1f) { newPage(); continue } else end = line + 1 }   // a line taller than a page: draw it anyway
                val top = l.getLineTop(line).toFloat(); val bottom = l.getLineBottom(end - 1).toFloat()
                if (bg != null) p.canvas.drawRoundRect(M_X, p.y, M_X + CW, p.y + bottom - top + 2 * padY, 4f, 4f, Paint().apply { color = bg })
                p.canvas.save(); p.canvas.translate(M_X + padX, p.y + padY - top); p.canvas.clipRect(0f, top, CW, bottom); l.draw(p.canvas); p.canvas.restore()
                p.y += bottom - top + 2 * padY
                line = end
                if (line < l.lineCount) newPage()
            }
        }

        for (part in BotFileRules.split(markdown)) {
            when (part) {
                is BotFileRules.Part.Table -> {
                    p.y += 4f
                    table(part.rows, sans, sansSemi, { h -> ensure(h) }, { p }, { newPage() })
                    p.y += 10f
                }
                is BotFileRules.Part.Text -> for (b in Markdown.parse(part.md, st)) {
                    when (b) {
                        is Markdown.Prose -> { val l = layout(b.text, paint(serif, 11.5f, INK), CW, 1.42f); ensure(minOf(l.height.toFloat(), 40f)); flow(l); p.y += 8f }
                        is Markdown.Code -> { val l = layout(b.text, paint(mono, 8.8f, INK), CW - 16f, 1.4f); ensure(minOf(l.height.toFloat() + 16f, 40f)); flow(l, padX = 8f, bg = CODE_BG, padY = 7f); p.y += 9f }
                    }
                }
            }
        }
        finish(p, pages, sans)
        doc.writeTo(out); doc.close()
    }

    private fun finish(p: Page, n: Int, sans: Typeface) {
        val paint = paint(sans, 8f, MUTED).apply { textAlign = Paint.Align.CENTER }
        p.canvas.drawText("$n", W / 2f, H - 30f, paint)
        p.doc.finishPage(p.page)
    }

    /** Paint for text in points (density 1: a PDF page is measured in points). */
    private fun paint(face: Typeface, size: Float, color: Int, letter: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = size; this.color = color; density = 1f; letterSpacing = letter
    }
    /** Draws [l] at the left margin at [y]; returns the y below it. */
    private fun draw(p: Page, l: StaticLayout, y: Float): Float { p.canvas.save(); p.canvas.translate(M_X, y); l.draw(p.canvas); p.canvas.restore(); return y + l.height }
    /** A short label laid out across the text width. */
    private fun label(s: String, face: Typeface, size: Float, color: Int, letter: Float = 0f): StaticLayout = layout(s, paint(face, size, color, letter), CW, 1.2f)

    private fun layout(s: CharSequence, paint: TextPaint, width: Float, lead: Float): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, lead).setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build()

    // ---------------------------------------------------------------- tables

    /** A real grid: header row tinted and bold, cells wrapped, rows kept whole (a new page starts before a row that doesn't fit). */
    private fun table(rows: List<List<String>>, sans: Typeface, semi: Typeface, ensure: (Float) -> Unit, page: () -> Page, newPage: () -> Unit) {
        if (rows.isEmpty()) return
        val n = rows.maxOf { it.size }
        // column widths by content, every column at least a fair share
        val want = (0 until n).map { c -> rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 }.coerceIn(4, 40).toFloat() }
        val total = want.sum(); val widths = want.map { maxOf(CW * it / total, CW / n * 0.5f) }.let { w -> val s = w.sum(); w.map { it * CW / s } }
        val pad = 5f
        val border = Paint().apply { color = LINE; strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val fill = Paint().apply { color = HEAD_BG }
        rows.forEachIndexed { ri, r ->
            val paint = paint(if (ri == 0) semi else sans, 9f, INK)
            val cells = (0 until n).map { c -> layout(clean(r.getOrNull(c) ?: ""), paint, widths[c] - 2 * pad, 1.3f) }
            val h = cells.maxOf { it.height } + 2 * pad
            val room = H - M_TOP - M_BOTTOM
            if (h > room) { tallRow(cells, widths, pad, ri == 0, border, fill, sans, page, newPage); return@forEachIndexed }
            ensure(h)
            val p = page()
            var x = M_X
            if (ri == 0) p.canvas.drawRect(M_X, p.y, M_X + CW, p.y + h, fill)
            cells.forEachIndexed { c, l ->
                p.canvas.drawRect(x, p.y, x + widths[c], p.y + h, border)
                p.canvas.save(); p.canvas.translate(x + pad, p.y + pad); p.canvas.clipRect(0f, 0f, widths[c], h); l.draw(p.canvas); p.canvas.restore()
                x += widths[c]
            }
            p.y += h
        }
    }
    /**
     * A row taller than a whole page: drawn in slices across pages, each slice ending on a line of its tallest cell,
     * with "continued" under each slice that goes on, so nothing runs off the bottom of the paper.
     */
    private fun tallRow(cells: List<StaticLayout>, widths: List<Float>, pad: Float, head: Boolean, border: Paint, fill: Paint, sans: Typeface, page: () -> Page, newPage: () -> Unit) {
        val tall = cells.maxByOrNull { it.height } ?: return
        val total = tall.height.toFloat()
        val mark = paint(sans, 7.5f, MUTED).apply { textAlign = Paint.Align.RIGHT }
        var from = 0f
        while (from < total) {
            var p = page()
            var avail = H - M_BOTTOM - p.y - 2 * pad - 12f
            if (avail < 40f) { newPage(); p = page(); avail = H - M_BOTTOM - p.y - 2 * pad - 12f }
            // end the slice on a line boundary of the tallest cell (at least one line, so it always moves on)
            var to = from
            for (i in 0 until tall.lineCount) { val b = tall.getLineBottom(i).toFloat(); if (b <= from) continue; if (b - from <= avail || to == from) to = b else break }
            if (to <= from) to = minOf(total, from + avail)
            val sliceH = to - from + 2 * pad
            if (head) p.canvas.drawRect(M_X, p.y, M_X + CW, p.y + sliceH, fill)
            var x = M_X
            cells.forEachIndexed { c, l ->
                p.canvas.drawRect(x, p.y, x + widths[c], p.y + sliceH, border)
                p.canvas.save(); p.canvas.clipRect(x, p.y, x + widths[c], p.y + sliceH)
                p.canvas.translate(x + pad, p.y + pad - from); l.draw(p.canvas); p.canvas.restore()
                x += widths[c]
            }
            p.y += sliceH
            from = to
            if (from < total) { p.canvas.drawText("continued", M_X + CW, p.y + 9f, mark); newPage() }
        }
    }

    /** Table cells keep their words, without Markdown marks. */
    private fun clean(s: String) = SpannableStringBuilder(s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1").replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1"))
}
