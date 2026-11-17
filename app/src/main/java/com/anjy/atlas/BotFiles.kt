package com.anjy.atlas

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Atlas Bot makes on the phone: its job folder (the same one its browser downloads into), documents (a typeset
 * PDF plus the Markdown), spreadsheets (a real Excel file plus a CSV), interactive pages (one .html file that opens in
 * Atlas's own viewer, with no internet), and the Library that lists them all. Files stay on the phone (they don't sync).
 * Every name a bot gives becomes a plain file name inside its own job folder, so it can't touch anything else.
 */
object BotFiles {
    private const val TAG = "AtlasBotFiles"
    val names = setOf("workspace_list", "workspace_read", "workspace_write", "make_document", "make_spreadsheet", "make_page")

    class Out(val text: String, val step: String, val made: String? = null)

    fun dir(ctx: Context, jobId: String): File = BotBrowser.jobDir(ctx, jobId)
    private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "bot")

    // ---------------------------------------------------------------- the tools' descriptions (same as the PC's)

    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun arr(d: String, items: JSONObject) = JSONObject().put("type", "array").put("description", d).put("items", items)
    private val rowsOf get() = arr("The rows: each row is a list of cells, in the same order as the columns. A cell starting with = is a formula, e.g. \"=B2*C2\".", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
    private val colsOf get() = arr("The column headings", JSONObject().put("type", "string"))
    private fun fn(name: String, desc: String, props: JSONObject? = null, required: List<String> = emptyList()) = JSONObject().put("name", name).put("description", desc).apply {
        if (props != null) put("parameters", JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
    }
    val declarations: List<JSONObject> by lazy {
        listOf(
            fn("workspace_list", "List the files in this job's folder (what you made, wrote or downloaded)."),
            fn("workspace_read", "Read a text file from this job's folder (txt, md, csv, json, html). Long files come in parts.",
                JSONObject().put("name", str("The file's name")).put("from", JSONObject().put("type", "number").put("description", "Start at this character (for the next part of a long file)")), listOf("name")),
            fn("workspace_write", "Write a text file in this job's folder (notes, a CSV, JSON, a draft). Set append to add to the end instead of replacing.",
                JSONObject().put("name", str("The file name, e.g. notes.md")).put("text", str("What to write")).put("append", JSONObject().put("type", "boolean").put("description", "Add to the end of the file")), listOf("name", "text")),
            fn("make_document", "Make a finished document for the user: a neatly typeset PDF (with the Markdown saved beside it). Use it for reports, briefs, letters, plans, summaries.",
                JSONObject().put("title", str("The title")).put("markdown", str("The whole document in Markdown: headings, paragraphs, lists, tables, links")), listOf("title", "markdown")),
            fn("make_spreadsheet", "Make a real Excel spreadsheet (.xlsx, with a .csv beside it): bold frozen header, sized columns; numbers stay numbers and cells starting with = are formulas. One sheet with columns + rows, or several sheets.",
                JSONObject().put("title", str("The file title")).put("columns", colsOf).put("rows", rowsOf)
                    .put("sheets", arr("For several sheets instead: each with a name, columns and rows", JSONObject().put("type", "object").put("properties", JSONObject().put("name", str("The sheet name")).put("columns", colsOf).put("rows", rowsOf)))), listOf("title")),
            fn("make_page", "Make an interactive page the user opens in Atlas: text, tables they can sort and filter, and bar, line or pie charts. Give the content; Atlas draws the page.",
                JSONObject().put("title", str("The title")).put("intro", str("Text at the top, in simple Markdown (headings, bullets, **bold**, links)"))
                    .put("tables", arr("Tables", JSONObject().put("type", "object").put("properties", JSONObject().put("title", str("Its heading")).put("columns", colsOf).put("rows", rowsOf))))
                    .put("charts", arr("Charts", JSONObject().put("type", "object").put("properties", JSONObject().put("title", str("Its heading"))
                        .put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("bar", "line", "pie"))))
                        .put("labels", arr("The labels along the bottom (or the pie slices)", JSONObject().put("type", "string")))
                        .put("series", arr("One or more series of numbers, one per label", JSONObject().put("type", "object").put("properties", JSONObject().put("name", str("Its name")).put("values", arr("The numbers", JSONObject().put("type", "number"))))))))), listOf("title")),
        )
    }

    fun label(name: String, args: JSONObject): String = when (name) {
        "workspace_list" -> "Looking at its files"
        "workspace_read" -> "Reading ${args.optString("name").take(40)}"
        "workspace_write" -> "Writing ${args.optString("name").take(40)}"
        "make_document" -> "Making a document: ${args.optString("title").take(50)}"
        "make_spreadsheet" -> "Making a spreadsheet: ${args.optString("title").take(50)}"
        "make_page" -> "Making a page: ${args.optString("title").take(50)}"
        else -> "Working with files"
    }

    // ---------------------------------------------------------------- running them

    private val stampFmt = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH)

    suspend fun run(ctx: Context, jobId: String, goal: String, name: String, args: JSONObject): Out = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val dir = dir(app, jobId)
        fun fresh(n: String) = File(dir, BotFileRules.freshName(n) { File(dir, it).exists() })
        when (name) {
            "workspace_list" -> {
                val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
                if (files.isEmpty()) Out("This job's folder is empty. Files you make, write or download go here.", "Looked at its files")
                else Out("Files in this job's folder:\n" + files.joinToString("\n") { "- ${it.name} (${BotFileRules.size(it.length())})" }, "Looked at its files")
            }
            "workspace_read" -> {
                val n = BotFileRules.fileName(args.optString("name")); val f = File(dir, n)
                when {
                    !f.isFile -> Out("There's no file called \"$n\" in this job's folder. Use workspace_list to see what's there.", "Looked for $n")
                    !BotFileRules.isText(n) -> Out("\"$n\" isn't a text file, so it can't be read here.", "Looked at $n")
                    f.length() > 8L * 1_048_576 -> Out("\"$n\" is too big to read on the phone (${BotFileRules.size(f.length())}).", "Looked at $n")
                    else -> {
                        val text = f.readText(); val from = args.optInt("from", 0).coerceIn(0, text.length)
                        val end = minOf(text.length, from + BotFileRules.MAX_READ)
                        val more = if (text.length > end) "\n\n[${text.length - end} more characters: read again with from=$end]" else ""
                        Out(text.substring(from, end) + more, "Read $n")
                    }
                }
            }
            "workspace_write" -> {
                val n = BotFileRules.fileName(args.optString("name"))
                val text = args.optString("text")
                when {
                    !BotFileRules.isText(n) -> Out("Only text files can be written this way (.txt, .md, .csv, .json, .html…). For a PDF use make_document; for Excel use make_spreadsheet.", "Couldn't write $n")
                    text.toByteArray().size > BotFileRules.MAX_WRITE -> Out("That is too long for one file (1 MB at most). Split it.", "Couldn't write $n")
                    else -> {
                        dir.mkdirs(); val f = File(dir, n); val existed = f.exists()
                        if (args.optBoolean("append") && existed) f.appendText(text) else f.writeText(text)
                        remember(app, f, n, "file", jobId, goal)
                        Out("${if (existed) (if (args.optBoolean("append")) "Added to" else "Replaced") else "Wrote"} $n in this job's folder.", "Wrote $n")
                    }
                }
            }
            "make_document" -> {
                val title = args.optString("title").ifBlank { "Document" }.take(160)
                val md = args.optString("markdown").ifBlank { args.optString("text") }
                if (md.isBlank()) return@withContext Out("The document is empty: give it the text in Markdown.", "Couldn't make the document")
                dir.mkdirs()
                val pf = fresh(BotFileRules.fileName(title, "pdf").substringBeforeLast('.') + ".pdf")
                val stamp = stampFmt.format(Instant.now().atZone(com.anjy.atlas.core.Engine.zone))
                try { pf.outputStream().use { DocPdf.write(app, title, md, stamp, it) } }
                catch (e: Exception) { pf.delete(); Log.w(TAG, "pdf", e); return@withContext Out("Couldn't make the PDF (${e.message ?: e.javaClass.simpleName}).", "Couldn't make the document") }
                val mf = File(dir, pf.name.removeSuffix(".pdf") + ".md"); mf.writeText("# $title\n\n$md\n")
                remember(app, pf, title, "document", jobId, goal)
                Out("Made the document \"${pf.name}\" (PDF, ${BotFileRules.size(pf.length())}), with the text also saved as ${mf.name}.", "Made a document: ${title.take(60)}", pf.name)
            }
            "make_spreadsheet" -> {
                val title = args.optString("title").ifBlank { "Spreadsheet" }.take(160)
                val book = BotFileRules.sheetsFrom(title, args)
                if (book.none { it.second.isNotEmpty() }) return@withContext Out("The spreadsheet is empty: give it columns and rows.", "Couldn't make the spreadsheet")
                dir.mkdirs()
                val xf = fresh(BotFileRules.fileName(title, "xlsx").substringBeforeLast('.') + ".xlsx")
                xf.writeBytes(BotFileRules.xlsx(book))
                val cf = File(dir, xf.name.removeSuffix(".xlsx") + ".csv"); cf.writeText("﻿" + BotFileRules.toCsv(book.first().second))
                remember(app, xf, title, "spreadsheet", jobId, goal)
                val rows = book.sumOf { maxOf(0, it.second.size - 1) }
                Out("Made the spreadsheet \"${xf.name}\" (Excel, ${book.size} sheet${if (book.size == 1) "" else "s"}, $rows rows), plus ${cf.name} for the first sheet.", "Made a spreadsheet: ${title.take(60)}", xf.name)
            }
            "make_page" -> {
                val title = args.optString("title").ifBlank { "Page" }.take(160)
                val raw = args.optString("html")
                val html = if (raw.isNotBlank()) {
                    if (raw.toByteArray().size > BotFileRules.MAX_PAGE) return@withContext Out("That page is too big (2 MB at most).", "Couldn't make the page")
                    raw
                } else {
                    val intro = args.optString("intro").ifBlank { args.optString("text") }
                    val tables = args.optJSONArray("tables"); val charts = args.optJSONArray("charts")
                    if (intro.isBlank() && (tables?.length() ?: 0) == 0 && (charts?.length() ?: 0) == 0) return@withContext Out("The page is empty: give it some text, tables or charts.", "Couldn't make the page")
                    BotFileRules.pageHtml(template(app), BotFileRules.cleanSpec(title, intro, tables, charts))
                }
                dir.mkdirs()
                val f = fresh(BotFileRules.fileName(title, "html").substringBeforeLast('.') + ".html")
                f.writeText(html)
                remember(app, f, title, "page", jobId, goal)
                Out("Made the page \"${f.name}\". It opens in Atlas's viewer from Bots › Library.", "Made a page: ${title.take(60)}", f.name)
            }
            else -> Out("Error: there is no such file tool.", "Working")
        }
    }

    @Volatile private var tpl: String? = null
    private fun template(ctx: Context): String = tpl ?: ctx.assets.open("page.html").bufferedReader().use { it.readText() }.also { tpl = it }

    // ---------------------------------------------------------------- the Library's index: what bots made, for which job

    private val lock = Any()
    private fun indexFile(ctx: Context) = File(root(ctx), "library.json")
    private fun readIndex(ctx: Context): JSONArray = try { JSONArray(indexFile(ctx).readText()) } catch (_: Exception) { JSONArray() }
    private fun writeIndex(ctx: Context, a: JSONArray) {
        val f = indexFile(ctx); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(f)
    }
    private fun remember(ctx: Context, f: File, title: String, kind: String, job: String, goal: String) = synchronized(lock) {
        val old = readIndex(ctx); val out = JSONArray()
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("file") != f.path) out.put(o) }
        out.put(JSONObject().put("file", f.path).put("title", title).put("kind", kind).put("job", job).put("goal", goal.take(300)).put("at", System.currentTimeMillis()))
        while (out.length() > 2000) out.remove(0)
        writeIndex(ctx, out)
    }

    // >>> attachments: files sent in the chat are kept in the Library (bot/uploads), so tools and bots can use them
    fun keepUpload(ctx: Context, f: File, name: String) = remember(ctx, f, name, "file", "chat", "Sent in the chat")
    // <<< attachments

    /** The files this job delivered (documents, spreadsheets, pages), by name, for its report. */
    fun madeBy(ctx: Context, jobId: String): List<String> = synchronized(lock) {
        val a = readIndex(ctx)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("job") == jobId && it.optString("kind") != "file" && File(it.optString("file")).isFile }
            .map { File(it.optString("file")).name }
    }

    class Item(val file: File, val title: String, val kind: String, val goal: String, val at: Long, val made: Boolean)

    /** Everything in the bots' folders, newest first, with what made it where known. A document's .md and a spreadsheet's .csv sit with them. */
    fun library(ctx: Context): List<Item> {
        val idx = synchronized(lock) { readIndex(ctx) }.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("file") } }
        val jobs = Bots.visible().associateBy { BrowserRules.safeFileName(it.id, "job") }
        val out = mutableListOf<Item>()
        root(ctx).listFiles()?.filter { it.isDirectory }?.forEach { d ->
            d.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { f ->
                val i = idx[f.path]; val e = BotFileRules.ext(f.name)
                val twin = (e == "md" && File(d, f.name.removeSuffix(".md") + ".pdf").exists()) || (e == "csv" && File(d, f.name.removeSuffix(".csv") + ".xlsx").exists())
                if (twin && i == null) return@forEach
                val kind = i?.optString("kind") ?: when (e) { "pdf" -> "document"; "xlsx", "csv" -> "spreadsheet"; "html", "htm" -> "page"; else -> "download" }
                out.add(Item(f, i?.optString("title") ?: f.name, kind, i?.optString("goal") ?: (jobs[d.name]?.goal ?: ""), i?.optLong("at") ?: f.lastModified(), i != null))
            }
        }
        return out.sortedByDescending { it.at }
    }

    /** True if [f] is a file inside the bots' folders (so the Library may open, share or delete it). */
    fun inside(ctx: Context, f: File): Boolean = try {
        val r = root(ctx).canonicalPath + File.separator; val c = f.canonicalPath
        c.startsWith(r) && c != indexFile(ctx).canonicalPath && f.isFile
    } catch (_: Exception) { false }

    fun remove(ctx: Context, f: File): Boolean {
        if (!inside(ctx, f)) return false
        val ok = f.delete()
        synchronized(lock) {
            val old = readIndex(ctx); val out = JSONArray()
            for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("file") != f.path) out.put(o) }
            writeIndex(ctx, out)
        }
        return ok
    }
}
