package com.anjy.atlas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * Pictures, videos and files sent with a message. Kept to sensible limits so a message stays quick to send and
 * within what the AI services accept: up to [MAX_PHOTOS] items (photos resized to at most [PHOTO_EDGE] pixels;
 * files up to [FILE_MAX_MB] MB), or one video up to [VIDEO_MAX_SECONDS] seconds and [VIDEO_MAX_MB] MB.
 * PDFs go to the AI service as they are; text files and Word documents are read into text, so they work with
 * every service. Copies live in Atlas's own storage.
 */
object Attach {
    const val MAX_PHOTOS = 4
    const val PHOTO_EDGE = 1600
    const val VIDEO_MAX_SECONDS = 60
    const val VIDEO_MAX_MB = 15
    const val FILE_MAX_MB = 10
    /** Text read from a file is cut here (about 50 pages), so one file can't swamp the conversation. */
    const val TEXT_MAX_CHARS = 120_000

    /** [name]: the file's own name (for files). A text or Word file is kept as the text read from it (mime text/plain). */
    class Item(val path: String, val mime: String, val name: String = "") {
        val video get() = mime.startsWith("video/")
        val image get() = mime.startsWith("image/")
        val pdf get() = mime == "application/pdf"
        val textFile get() = mime == "text/plain"
        val isFile get() = pdf || textFile
        val file get() = File(path)
    }

    private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/csv",
        "application/x-sh", "application/sql", "application/rtf")
    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "html", "htm", "log", "ini", "toml",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "sql", "lua", "dart", "css")

    private fun nameOf(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "file"
    } catch (_: Exception) { uri.lastPathSegment ?: "file" }

    class TooBig(msg: String) : Exception(msg)

    internal fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }

    /** Copies what the user picked or pasted into Atlas. Throws [TooBig] (with a sentence for the user) past the limits. */
    fun take(ctx: Context, uri: Uri): Item {
        val cr = ctx.contentResolver
        val name = nameOf(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = cr.getType(uri) ?: when (ext) { "pdf" -> "application/pdf"; "jpg", "jpeg", "png", "webp", "heic" -> "image/jpeg"; else -> "application/octet-stream" }
        val isText = mime.startsWith("text/") || mime in TEXT_TYPES || ext in TEXT_EXT
        val isWord = ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        if (mime == "application/pdf" || isText || isWord) {
            val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > FILE_MAX_MB * 1024L * 1024L) throw TooBig("“$name” is ${size / (1024 * 1024)} MB; files can be up to $FILE_MAX_MB MB.")
            if (mime == "application/pdf") {
                val out = File(dir(ctx), "${UUID.randomUUID()}.pdf")
                cr.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: throw TooBig("Couldn't read “$name”.")
                return Item(out.absolutePath, "application/pdf", name)
            }
            // read with a cap: some apps don't say how big a file is, and reading a huge one whole would run Atlas out of memory
            val raw = cr.openInputStream(uri)?.use { readCapped(it, FILE_MAX_MB * 1024L * 1024L) { "“$name” is over $FILE_MAX_MB MB; files can be up to $FILE_MAX_MB MB." } }
                ?: throw TooBig("Couldn't read “$name”.")
            var text = if (isWord) wordText(raw) else String(raw, Charsets.UTF_8)
            if (text.isBlank()) throw TooBig("“$name” has no text I can read.")
            if (text.length > TEXT_MAX_CHARS) text = text.take(TEXT_MAX_CHARS) + "\n…(the rest of the file was cut: it's very long)"
            val out = File(dir(ctx), "${UUID.randomUUID()}.txt")
            out.writeText(text)
            return Item(out.absolutePath, "text/plain", name)
        }
        if (!mime.startsWith("image/") && !mime.startsWith("video/"))
            throw TooBig("Atlas can take pictures, videos, PDFs, text files and Word documents. “$name” is none of those.")
        return if (mime.startsWith("video/")) {
            val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > VIDEO_MAX_MB * 1024L * 1024L) throw TooBig("That video is ${size / (1024 * 1024)} MB; the limit is $VIDEO_MAX_MB MB. Trim it or send a shorter clip.")
            val secs = try {
                MediaMetadataRetriever().run { setDataSource(ctx, uri); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); d / 1000 }
            } catch (_: Exception) { 0L }
            if (secs > VIDEO_MAX_SECONDS) throw TooBig("That video is ${secs}s long; the limit is $VIDEO_MAX_SECONDS seconds.")
            val out = File(dir(ctx), "${UUID.randomUUID()}.mp4")
            try { cr.openInputStream(uri)?.use { i -> out.outputStream().use { copyCapped(i, it, VIDEO_MAX_MB * 1024L * 1024L) { "That video is over $VIDEO_MAX_MB MB; the limit is $VIDEO_MAX_MB MB. Trim it or send a shorter clip." } } } ?: throw TooBig("Couldn't read that video.") }
            catch (e: TooBig) { out.delete(); throw e }
            Item(out.absolutePath, if (mime == "video/mp4" || mime == "video/webm" || mime == "video/quicktime") mime else "video/mp4")
        } else if (mime.startsWith("image/")) {
            // decode (any format the phone reads), shrink, and save as JPEG: small, and accepted everywhere
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val scale = minOf(1f, PHOTO_EDGE.toFloat() / maxOf(w, h))
                    d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) { throw TooBig("Couldn't open that picture.") }
            val out = File(dir(ctx), "${UUID.randomUUID()}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            Item(out.absolutePath, "image/jpeg")
        } else throw TooBig("Atlas can take pictures and videos.")
    }

    private const val WORD_XML_MAX = 40L * 1024 * 1024

    /** Everything from [ins], or [TooBig] with [why] once it passes [max] bytes (nothing past the cap is held). */
    internal fun readCapped(ins: java.io.InputStream, max: Long, why: () -> String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyCapped(ins, out, max, why)
        return out.toByteArray()
    }

    internal fun copyCapped(ins: java.io.InputStream, out: java.io.OutputStream, max: Long, why: () -> String) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = ins.read(buf); if (n < 0) break
            total += n
            if (total > max) throw TooBig(why())
            out.write(buf, 0, n)
        }
    }

    /** The words in a Word (.docx) file: its paragraphs, read from the document inside the zip. */
    private fun wordText(bytes: ByteArray): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    // capped: a Word file is a zip, and a doctored one can unpack to gigabytes
                    val xml = String(readCapped(z, WORD_XML_MAX) { "That Word document is too large to read." }, Charsets.UTF_8)
                    return xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                        .lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
                }
            }
        }
        return ""
    }

    /** A text file's contents, put into the message itself (works with every service, offline too). */
    fun inlineText(item: Item): String = "\n\n[File: ${item.name}]\n" + item.file.readText() + "\n[End of ${item.name}]"

    /** The bytes for the AI service. */
    fun media(item: Item): Providers.Media = Providers.Media(item.mime, Base64.encodeToString(item.file.readBytes(), Base64.NO_WRAP), item.name)

    /** A small picture to show in the chat (a video's first frame). */
    fun thumb(item: Item, edge: Int): Bitmap? = try {
        if (item.isFile) null
        else if (item.video) MediaMetadataRetriever().run {
            setDataSource(item.path); val f = getFrameAtTime(0); release()
            f?.let { scaleDown(it, edge) }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= edge) sample *= 2
            BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { scaleDown(it, edge) }
        }
    } catch (_: Exception) { null }

    private fun scaleDown(b: Bitmap, edge: Int): Bitmap {
        val s = edge.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /**
     * Old attachments no message refers to any more are deleted. Recent ones are kept even then: they may be waiting
     * in the message box, not sent yet (a reply finishing meanwhile saves the chat and would delete them).
     */
    fun prune(ctx: Context, keep: Set<String>, now: Long = System.currentTimeMillis()) {
        dir(ctx).listFiles()?.forEach { if (it.absolutePath !in keep && now - it.lastModified() > PENDING_KEEP_MS) it.delete() }
    }
    /** How long an attachment not (yet) in any message is kept. */
    const val PENDING_KEEP_MS = 6 * 60 * 60 * 1000L
}
