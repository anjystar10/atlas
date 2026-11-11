package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The plain rules behind Atlas Bot's files on the phone (no Android in here, so they're tested on the computer):
 * safe file names, the interactive page's data, CSV, and a real Excel file (.xlsx is a zip of small XML files).
 * The PC does the same in desktop/botfiles.js and desktop/sheets.js; the page template is the same file on both.
 */
object BotFileRules {
    const val MAX_READ = 20_000
    const val MAX_WRITE = 1_048_576
    const val MAX_PAGE = 2 * 1_048_576
    const val MAX_ROWS = 20_000
    val TEXT_EXT = setOf("txt", "md", "csv", "json", "html", "htm", "xml", "log", "tsv", "yaml", "yml")

    /** A bot's file name → a plain name with no folders, keeping a sensible extension ("../../x.txt" → "x.txt"). */
    fun fileName(name: String?, fallbackExt: String = "txt"): String {
        val raw = (name ?: "").replace('\\', '/').substringAfterLast('/')
        val dot = raw.lastIndexOf('.')
        val ext = (if (dot > 0) raw.substring(dot + 1) else "").lowercase().filter { it.isLetterOrDigit() }.take(9)
        val stemRaw = if (dot > 0) raw.substring(0, dot) else raw
        val stem = stemRaw.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim()
            .trim('.', ' ').take(50).trim().ifEmpty { "file" }
        return "$stem.${ext.ifEmpty { fallbackExt }}"
    }
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isText(name: String) = ext(name) in TEXT_EXT

    /** "Report.pdf", then "Report (2).pdf", ... : the first name [exists] says is free. */
    fun freshName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name; val ext = if (dot > 0) name.substring(dot) else ""
        var k = 2
        while (exists("$stem ($k)$ext")) k++
        return "$stem ($k)$ext"
    }

    fun size(n: Long): String = when { n < 1024 -> "$n B"; n < 1_048_576 -> "${n / 1024} KB"; else -> String.format(java.util.Locale.US, "%.1f MB", n / 1_048_576.0) }

    // ---------------------------------------------------------------- the page

    /** Only the parts the template knows, with sane sizes (same as cleanSpec in desktop/botfiles.js). */
    fun cleanSpec(title: String, intro: String, tables: JSONArray?, charts: JSONArray?): JSONObject {
        fun str(v: Any?, n: Int) = (if (v == null || v == JSONObject.NULL) "" else v.toString()).take(n)
        val sections = JSONArray()
        if (tables != null) for (i in 0 until minOf(tables.length(), 12)) {
            val t = tables.optJSONObject(i) ?: continue
            val cols = JSONArray(); t.optJSONArray("columns")?.let { for (c in 0 until minOf(it.length(), 40)) cols.put(str(it.opt(c), 120)) }
            val rows = JSONArray()
            t.optJSONArray("rows")?.let { rs -> for (r in 0 until minOf(rs.length(), 5000)) {
                val row = rs.optJSONArray(r) ?: continue
                val out = JSONArray(); for (c in 0 until minOf(row.length(), 40)) { val v = row.opt(c); out.put(if (v is Number) v else str(v, 1000)) }
                rows.put(out)
            } }
            sections.put(JSONObject().put("type", "table").put("title", str(t.opt("title"), 200)).put("columns", cols).put("rows", rows))
        }
        if (charts != null) for (i in 0 until minOf(charts.length(), 12)) {
            val c = charts.optJSONObject(i) ?: continue
            val labels = JSONArray(); c.optJSONArray("labels")?.let { for (k in 0 until minOf(it.length(), 200)) labels.put(str(it.opt(k), 80)) }
            val series = JSONArray()
            c.optJSONArray("series")?.let { ss -> for (k in 0 until minOf(ss.length(), 8)) {
                val s = ss.optJSONObject(k) ?: continue
                val vals = JSONArray(); s.optJSONArray("values")?.let { v -> for (x in 0 until minOf(v.length(), 200)) { val d = v.optDouble(x, 0.0); vals.put(if (d.isFinite()) d else 0.0) } }
                series.put(JSONObject().put("name", str(s.opt("name"), 80)).put("values", vals))
            } }
            val kind = (c.optString("type").ifEmpty { c.optString("kind") }).let { if (it in setOf("bar", "line", "pie")) it else "bar" }
            sections.put(JSONObject().put("type", "chart").put("title", str(c.opt("title"), 200)).put("kind", kind).put("labels", labels).put("series", series))
        }
        return JSONObject().put("title", title.take(200)).put("intro", intro.take(20_000)).put("sections", sections)
    }

    /** The template with the page's data put in. "<" is escaped, so nothing in the data can end the script early. */
    fun pageHtml(template: String, spec: JSONObject): String {
        val data = spec.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val title = spec.optString("title").ifEmpty { "Atlas Page" }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return template.replace("/*ATLAS_PAGE_DATA*/null", data).replace("<title>Atlas Page</title>", "<title>$title</title>")
    }

    // ---------------------------------------------------------------- CSV

    fun parseCsv(text: String): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var any = false; var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i += 2; continue }; quoted = false; i++; continue }
                cell.append(c); i++; continue
            }
            when {
                c == '"' && cell.isEmpty() -> { quoted = true; any = true }
                c == ',' -> { row.add(cell.toString()); cell.clear(); any = true }
                c == '\r' -> {}
                c == '\n' -> { row.add(cell.toString()); rows.add(row); row = mutableListOf(); cell.clear(); any = false }
                else -> { cell.append(c); any = true }
            }
            i++
        }
        if (any || cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Rows → CSV text (Excel-friendly: CRLF, quotes where needed). */
    fun toCsv(rows: List<List<String>>): String = rows.joinToString("\r\n") { r ->
        r.joinToString(",") { v -> if (v.any { it == '"' || it == ',' || it == '\r' || it == '\n' } || (v.isNotEmpty() && (v.first().isWhitespace() || v.last().isWhitespace()))) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    } + "\r\n"

    // ---------------------------------------------------------------- Excel

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
    fun col(n: Int): String { var k = n + 1; val sb = StringBuilder(); while (k > 0) { val r = (k - 1) % 26; sb.insert(0, ('A' + r)); k = (k - 1) / 26 }; return sb.toString() }
    private val NUMBER = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    fun isNumber(t: String) = NUMBER.matches(t) && t.length < 16 && !Regex("^-?0\\d").containsMatchIn(t)
    private fun sheetName(s: String, i: Int) = s.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31).ifEmpty { "Sheet${i + 1}" }

    fun sheetXml(rows: List<List<String>>): String {
        val widths = mutableListOf<Int>()
        val body = StringBuilder()
        rows.forEachIndexed { ri, r ->
            body.append("<row r=\"${ri + 1}\">")
            r.forEachIndexed { ci, v ->
                val t = v; val ref = col(ci) + (ri + 1)
                while (widths.size <= ci) widths.add(8)
                widths[ci] = minOf(60, maxOf(widths[ci], t.length + 2))
                val style = if (ri == 0) " s=\"1\"" else ""
                when {
                    t.isEmpty() -> {}
                    t.startsWith("=") && t.length > 1 -> body.append("<c r=\"$ref\"$style><f>${x(t.substring(1))}</f></c>")
                    isNumber(t.trim()) -> body.append("<c r=\"$ref\"$style><v>${t.trim()}</v></c>")
                    else -> body.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${x(t)}</t></is></c>")
                }
            }
            body.append("</row>")
        }
        val cols = if (widths.isEmpty()) "" else "<cols>" + widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("") + "</cols>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>$cols<sheetData>$body</sheetData></worksheet>"
    }

    /** Sheets (name to rows, the first row the bold header) → the .xlsx file. */
    fun xlsx(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        val names = mutableListOf<String>(); val seen = HashSet<String>()
        sheets.forEachIndexed { i, (n, _) -> var s = sheetName(n, i); var k = 2; val base = s; while (s.lowercase() in seen) s = base.take(28) + " " + k++; seen.add(s.lowercase()); names.add(s) }
        val files = linkedMapOf<String, String>()
        files["[Content_Types].xml"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            names.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>"
        files["_rels/.rels"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        files["xl/workbook.xml"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${x(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>"
        files["xl/_rels/workbook.xml.rels"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            names.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${names.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        files["xl/styles.xml"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>"
        sheets.forEachIndexed { i, (_, rows) -> files["xl/worksheets/sheet${i + 1}.xml"] = sheetXml(rows) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    /** The bot's sheets (one from columns + rows, or several) → name to rows with the header first. Values become text. */
    fun sheetsFrom(title: String, args: JSONObject): List<Pair<String, List<List<String>>>> {
        fun rowsOf(a: JSONArray?): List<List<String>> {
            if (a == null) return emptyList()
            val out = mutableListOf<List<String>>()
            for (r in 0 until minOf(a.length(), MAX_ROWS)) {
                val v = a.opt(r)
                out.add(when (v) {
                    is JSONArray -> (0 until v.length()).map { cell(v.opt(it)) }
                    is JSONObject -> v.keys().asSequence().map { cell(v.opt(it)) }.toList()
                    else -> listOf(cell(v))
                })
            }
            return out
        }
        val list = args.optJSONArray("sheets")
        val src = if (list != null && list.length() > 0) (0 until minOf(list.length(), 20)).mapNotNull { list.optJSONObject(it) }
            else listOf(JSONObject().put("name", args.optString("sheet").ifEmpty { title }).put("columns", args.optJSONArray("columns")).put("rows", args.optJSONArray("rows")))
        return src.map { s ->
            val cols = s.optJSONArray("columns")?.let { c -> (0 until c.length()).map { cell(c.opt(it)) } } ?: emptyList()
            val rows = if (s.optJSONArray("rows") != null) rowsOf(s.optJSONArray("rows")) else s.optString("csv").takeIf { it.isNotEmpty() }?.let { parseCsv(it).take(MAX_ROWS) } ?: emptyList()
            s.optString("name").ifEmpty { title } to (if (cols.isNotEmpty()) listOf(cols) + rows else rows)
        }
    }
    private fun cell(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** The line added to a report when the bot made files: where to find them. */
    fun footer(made: List<String>): String {
        if (made.isEmpty()) return ""
        return "\n\n**Files:** ${made.take(12).joinToString(", ")}${if (made.size > 12) " and ${made.size - 12} more" else ""} (open them in Bots › Library)."
    }

    // ---------------------------------------------------------------- splitting tables from the rest

    sealed class Part { class Text(val md: String) : Part(); class Table(val rows: List<List<String>>) : Part() }

    /** The Markdown in order, with its tables (| a | b | lines with a --- separator) taken out to be drawn as grids. */
    fun split(md: String): List<Part> {
        val out = mutableListOf<Part>(); val text = StringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0; var fenced = false
        while (i < lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced && isRow(l) && i + 1 < lines.size && isSeparator(lines[i + 1])) {
                if (text.isNotBlank()) out.add(Part.Text(text.toString())); text.clear()
                val rows = mutableListOf(cells(l)); i += 2
                while (i < lines.size && isRow(lines[i])) { rows.add(cells(lines[i])); i++ }
                out.add(Part.Table(rows)); continue
            }
            text.append(l).append('\n'); i++
        }
        if (text.isNotBlank()) out.add(Part.Text(text.toString()))
        return out
    }
    private fun isRow(l: String) = l.trim().let { it.startsWith("|") && it.count { c -> c == '|' } >= 2 }
    private fun isSeparator(l: String) = l.trim().let { it.startsWith("|") && it.replace(Regex("[|:\\-\\s]"), "").isEmpty() && it.contains("-") }
    private fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
}
