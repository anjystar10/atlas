package com.anjy.atlas

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Writer
import java.util.zip.ZipFile

/**
 * The text inside Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files, written to a text file. They are zips
 * of XML: each part is read with a streaming parser straight from the zip, so even a huge file is read in small
 * steps, and each part is capped (a doctored zip can unpack to gigabytes).
 */
object OfficeText {
    private const val PART_MAX = 512L * 1024 * 1024
    private const val SHARED_MAX_CHARS = 64_000_000L

    fun extract(file: File, kind: String, out: File) {
        ZipFile(file).use { z ->
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                when (kind) {
                    "docx" -> z.getEntry("word/document.xml")?.let { e -> words(capped(z.getInputStream(e)), w) }
                    "pptx" -> numbered(z, Regex("ppt/slides/slide(\\d+)\\.xml")).forEachIndexed { i, e ->
                        w.write("## Slide ${i + 1}\n"); words(capped(z.getInputStream(e)), w, textTag = "a:t", paraTag = "a:p"); w.write("\n")
                    }
                    "xlsx" -> {
                        val shared = z.getEntry("xl/sharedStrings.xml")?.let { sharedStrings(capped(z.getInputStream(it))) } ?: emptyList()
                        val names = z.getEntry("xl/workbook.xml")?.let { sheetNames(capped(z.getInputStream(it))) } ?: emptyList()
                        numbered(z, Regex("xl/worksheets/sheet(\\d+)\\.xml")).forEachIndexed { i, e ->
                            w.write("## Sheet: ${names.getOrNull(i) ?: "Sheet ${i + 1}"}\n"); cells(capped(z.getInputStream(e)), shared, w); w.write("\n")
                        }
                    }
                }
            }
        }
    }

    private fun numbered(z: ZipFile, re: Regex) = z.entries().toList().mapNotNull { e -> re.matchEntire(e.name)?.let { it.groupValues[1].toInt() to e } }.sortedBy { it.first }.map { it.second }

    private fun parser(ins: InputStream): XmlPullParser = android.util.Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(ins, "UTF-8")
    }

    /** Paragraph text: [textTag] runs, a new line at the end of each [paraTag]. */
    private fun words(ins: InputStream, w: Writer, textTag: String = "w:t", paraTag: String = "w:p") = ins.use {
        val p = parser(it); var inText = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name) { textTag -> inText = true; "w:tab" -> w.write("\t"); "w:br", "a:br" -> w.write("\n") }
                XmlPullParser.END_TAG -> when (p.name) { textTag -> inText = false; paraTag -> w.write("\n") }
                XmlPullParser.TEXT -> if (inText) w.write(p.text)
            }
        }
    }

    private fun sharedStrings(ins: InputStream): List<String> = ins.use {
        val p = parser(it); val out = ArrayList<String>(); val cur = StringBuilder(); var inT = false; var chars = 0L
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name) { "si" -> cur.setLength(0); "t" -> inT = true }
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { chars += cur.length; out.add(if (chars > SHARED_MAX_CHARS) "…" else cur.toString()) } }
                XmlPullParser.TEXT -> if (inT && chars <= SHARED_MAX_CHARS) cur.append(p.text)
            }
        }
        out
    }

    private fun sheetNames(ins: InputStream): List<String> = ins.use {
        val p = parser(it); val out = ArrayList<String>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") out.add(p.getAttributeValue(null, "name") ?: "Sheet ${out.size + 1}")
        }
        out
    }

    /** One line per row, cells apart by tabs (shared strings looked up, inline strings and values as they are). */
    private fun cells(ins: InputStream, shared: List<String>, w: Writer) = ins.use {
        val p = parser(it); var type = ""; var inV = false; var first = true; val v = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> first = true
                    "c" -> { type = p.getAttributeValue(null, "t") ?: ""; v.setLength(0) }
                    "v", "t" -> inV = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val s = if (type == "s") v.toString().trim().toIntOrNull()?.let { i -> shared.getOrNull(i) } ?: "" else v.toString()
                        if (!first) w.write("\t"); w.write(s.replace('\n', ' ').replace('\t', ' ')); first = false
                    }
                    "row" -> w.write("\n")
                }
                XmlPullParser.TEXT -> if (inV) v.append(p.text)
            }
        }
    }

    private fun capped(ins: InputStream): InputStream = object : FilterInputStream(ins) {
        var n = 0L
        private fun count(k: Int): Int { if (k > 0) { n += k; if (n > PART_MAX) throw java.io.IOException("part too large") }; return k }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }
}
