package com.anjy.atlas

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Turns a message's attachments into what the chosen AI service gets ([AttachRules.route]): inline parts, Gemini
 * uploads, stills from a video, text in the message, or a short note that tells Atlas how to read the rest with
 * file_read / file_search ([AttachTools]). Nothing is refused: the worst case is a note saying what the file is.
 */
object AttachSend {
    class Prepared(val media: List<Providers.Media>, val text: String)

    /** For [p]. [status] shows progress ("Uploading clip.mp4 · 40%"). [check] throws to stop (the user pressed Stop). */
    fun prepare(ctx: Context, p: Providers.Provider, items: List<Attach.Item>, status: (String) -> Unit = {}, check: () -> Unit = {}): Prepared {
        val media = mutableListOf<Providers.Media>(); val text = StringBuilder()
        for (it in items) {
            check()
            val name = it.name.ifEmpty { it.file.name }
            val r = AttachRules.route(it.kind, it.size, p.style, if (it.textFile) chars(it.textSource) else 0)
            when (r) {
                "native" -> if (it.image && (it.mime !in SAFE_IMAGES || it.size > 4 * AttachRules.MB)) {
                    // a picture the phone couldn't shrink when it was added: try once more, smaller, as JPEG
                    val j = jpegOf(it.file)
                    if (j != null) media.add(Providers.Media("image/jpeg", j, name)) else text.append(describe(it, name, "The picture couldn't be opened."))
                } else media.add(Attach.media(it))
                "upload" -> try {
                    val key = Providers.key(ctx, p.id) ?: throw GeminiFiles.Failed("no key")
                    val uri = GeminiFiles.upload(key, it.file, it.mime, name, { done, total -> status("Uploading $name · ${if (total > 0) done * 100 / total else 0}%") }, check)
                    media.add(Providers.Media(it.mime, "", name, uri))
                } catch (e: GeminiFiles.Failed) {
                    if (it.video) addFrames(it, name, media, text, "The upload to Gemini didn't work (${e.message}), so you see")
                    else text.append(describe(it, name, "It couldn't be uploaded to Gemini (${e.message})."))
                }
                "frames" -> { status("Looking through $name"); addFrames(it, name, media, text, if (p.style == "gemini") "It's over Gemini's 2 GB limit, so you see" else "${p.name} can't watch video, so you see") }
                "text" -> text.append(Attach.inlineText(it))
                "chunks" -> text.append("\n\n[File: $name (${AttachRules.size(it.size)}, about ${AttachRules.size(it.textSource.length())} of text) is too long to include. " +
                    "Read it with file_search(file, query) and file_read(file, offset, length); start with file_read(file, 0) for an overview. file = \"${it.path}\"]")
                "listing" -> text.append(listing(it, name))
                else -> text.append(describe(it, name, AttachRules.note(it.kind, r, p.name)))
            }
        }
        return Prepared(media, text.toString())
    }

    private val SAFE_IMAGES = setOf("image/jpeg", "image/png", "image/webp")

    /** A picture file as a JPEG at most 1600 px (decoded at a reduced size, so a huge picture never fills memory). */
    fun jpegOf(f: File): String? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= Attach.PHOTO_EDGE) sample *= 2
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { b ->
            val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, out); b.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Throwable) { null }

    /** Characters of text (counted exactly when small; anything past the inline limit counts as "long"). */
    fun chars(f: File): Int = if (f.length() > AttachRules.TEXT_INLINE_CHARS * 4L) Int.MAX_VALUE
        else Attach.readChars(f, AttachRules.TEXT_INLINE_CHARS + 1).length

    private fun addFrames(it: Attach.Item, name: String, media: MutableList<Providers.Media>, text: StringBuilder, why: String) {
        val shots = frames(it.file)
        if (shots.isEmpty()) { text.append(describe(it, name, "The video couldn't be opened to take stills from it.")); return }
        shots.forEach { (_, b64) -> media.add(Providers.Media("image/jpeg", b64, "")) }
        text.append("\n\n[Video: $name (${AttachRules.size(it.size)}). $why ${shots.size} stills from it, at ${shots.joinToString(", ") { clock(it.first) }}: not the motion or sound.]")
    }

    /** [AttachRules.FRAMES] stills spread through the video, at most 768 px, as JPEG (memory: one frame at a time). */
    fun frames(f: File): List<Pair<Long, String>> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val n = AttachRules.FRAMES
            (0 until n).mapNotNull { i ->
                val t = if (ms <= 0) 0L else ms * (2 * i + 1) / (2 * n)
                val b = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768) ?: return@mapNotNull null
                val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, out); b.recycle()
                t to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.let { if (ms <= 0) it.take(1) else it }
        } catch (_: Exception) { emptyList() } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    /** An archive: what's inside (up to 300 entries), and how to open one. */
    fun listing(it: Attach.Item, name: String): String = try {
        ZipFile(it.file).use { z ->
            val all = z.entries().toList().filter { e -> !e.isDirectory }
            val lines = all.take(300).joinToString("\n") { e -> "  ${e.name}  (${AttachRules.size(maxOf(e.size, 0))})" }
            "\n\n[Archive: $name (${AttachRules.size(it.size)}), ${all.size} files:\n$lines${if (all.size > 300) "\n  …and ${all.size - 300} more" else ""}\n" +
                "Open one with file_read(file, member = its path); file = \"${it.path}\"]"
        }
    } catch (e: Exception) { describe(it, name, "It looks like an archive but couldn't be opened.") }

    /** What Atlas can say about a file it can't read whole: name, type, size, why, and any readable text near the start. */
    fun describe(it: Attach.Item, name: String, why: String): String {
        val head = ByteArray(16 * 1024)
        val n = try { RandomAccessFile(it.file, "r").use { f -> f.read(head) }.coerceAtLeast(0) } catch (_: Exception) { 0 }
        val s = AttachRules.strings(head, n, max = 1500)
        return "\n\n[File: $name (${it.mime}, ${AttachRules.size(it.size)}). $why" +
            (if (s.isNotBlank()) " Readable text near its start: \"$s\"." else "") +
            " You can look further with file_read(file, offset, length); file = \"${it.path}\"]"
    }
}

/** file_read and file_search: Atlas (and bots) reading attached files part by part, whatever their size. */
object AttachTools {
    private const val READ_MAX = 60_000
    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
    private fun p(type: String, d: String) = JSONObject().put("type", type).put("description", d)

    val cap = Capabilities.Cap("attachments", declarations = { JSONArray()
        .put(fn("file_read", "Read part of a file the user attached (or a Library file): text from a byte offset, the text inside Word/Excel/PowerPoint files, a member of a zip, or for other files the readable text and hex at that offset. Use for files too long to have been included whole.",
            JSONObject().put("file", p("string", "the file path given with the attachment")).put("offset", p("integer", "byte offset to start at (0 = the start)"))
                .put("length", p("integer", "bytes to read, up to 60000 (default 20000)")).put("member", p("string", "for a zip: the path of the file inside to read")), listOf("file")))
        .put(fn("file_search", "Find the lines of an attached (or Library) file that contain all the given words, with the byte offset of each, to then read around them with file_read.",
            JSONObject().put("file", p("string", "the file path given with the attachment")).put("query", p("string", "words to look for")).put("max", p("integer", "most lines to return (default 30)")), listOf("file", "query")))
    }, run = { ctx, c -> run(ctx, c) }, forBots = true)

    private fun run(ctx: Context, c: Gemini.Call): Tools.Result? {
        if (c.name != "file_read" && c.name != "file_search") return null
        val f = File(c.args.optString("file"))
        if (!Attach.readable(ctx, f)) return Tools.Result("Error: that isn't an attached or Library file Atlas can read.")
        val name = f.name
        return try {
            if (c.name == "file_search") Tools.Result(search(textOf(f), c.args.optString("query"), c.args.optInt("max", 30).coerceIn(1, 100)), "Searched $name")
            else Tools.Result(read(f, c.args.optLong("offset", 0).coerceAtLeast(0), c.args.optInt("length", 20_000).coerceIn(1, READ_MAX), c.args.optString("member")), "Read $name")
        } catch (e: Exception) { Tools.Result("Error: ${e.message ?: "couldn't read $name"}") }
    }

    private fun kindOf(f: File) = AttachRules.kind(f.name, null)
    private fun textOf(f: File): File = Attach.sidecar(f).takeIf { it.exists() } ?: f

    fun read(f: File, offset: Long, length: Int, member: String = ""): String {
        val kind = kindOf(f)
        if (member.isNotBlank()) {
            ZipFile(f).use { z ->
                val e = z.getEntry(member) ?: return "There's no \"$member\" in ${f.name}."
                val buf = ByteArray(length); var got = 0
                z.getInputStream(e).use { i -> i.skip(offset); while (got < length) { val n = i.read(buf, got, length - got); if (n < 0) break; got += n } }
                return if (AttachRules.readsAsText(AttachRules.kind(member, null)) || AttachRules.strings(buf, got).length > got / 2) "[$member, bytes $offset–${offset + got} of ${e.size}]\n" + String(buf, 0, got, Charsets.UTF_8)
                    else "[$member, bytes $offset–${offset + got} of ${e.size}: not text]\nReadable text: ${AttachRules.strings(buf, got)}\nHex:\n${AttachRules.hex(buf, got)}"
            }
        }
        val src = textOf(f)
        val asText = src != f || AttachRules.readsAsText(kind)
        val total = src.length()
        if (offset >= total) return "That's past the end: the ${if (asText) "text" else "file"} is $total bytes."
        // read a little extra so the part can end at a line break
        val want = if (asText) length + length / 8 else length
        val buf = ByteArray(minOf(want.toLong(), total - offset).toInt())
        RandomAccessFile(src, "r").use { it.seek(offset); it.readFully(buf) }
        if (!asText) return "[${f.name}, bytes $offset–${offset + buf.size} of $total]\nReadable text: ${AttachRules.strings(buf)}\nHex:\n${AttachRules.hex(buf)}" +
            (if (offset + buf.size < total) "\n(next: offset=${offset + buf.size})" else "")
        // skip a broken UTF-8 character at the start
        var s = 0; while (s < buf.size && s < 3 && (buf[s].toInt() and 0xC0) == 0x80) s++
        val window = String(buf, s, buf.size - s, Charsets.UTF_8)
        val cutChars = if (offset + buf.size >= total) window.length else AttachRules.chunkStarts(window, minOf(window.length, length)).getOrNull(1) ?: window.length
        val part = window.substring(0, cutChars)
        val end = offset + s + part.toByteArray(Charsets.UTF_8).size
        return "[${f.name}, bytes $offset–$end of $total]\n$part" + (if (end < total) "\n(next: offset=$end)" else "\n(end of file)")
    }

    fun search(f: File, query: String, max: Int): String {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "Say what to look for."
        val hits = StringBuilder(); var found = 0; var at = 0L; var lineNo = 0
        f.inputStream().buffered(256 * 1024).use { ins ->
            // a very long line is only matched on its first 64 KB, but its full length still counts for the offsets
            val line = java.io.ByteArrayOutputStream(); var len = 0L
            fun flush() {
                lineNo++
                val t = String(line.toByteArray(), Charsets.UTF_8)
                if (found < max && words.all { t.lowercase().contains(it) }) { found++; hits.append("line $lineNo @offset ${at}: ${t.trim().take(300)}\n") }
                at += len + 1; len = 0; line.reset()
            }
            while (true) {
                val b = ins.read(); if (b < 0) break
                if (b == '\n'.code) { flush(); if (found >= max) break } else { len++; if (line.size() < 64 * 1024) line.write(b) }
            }
            if (len > 0 && found < max) flush()
        }
        return if (found == 0) "No line of ${f.name} has all of: $query." else "Lines with \"$query\" in ${f.name}:\n$hits" + (if (found >= max) "(stopped at $max; search again with more words, or read on with file_read)" else "")
    }
}
