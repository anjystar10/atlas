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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Pictures, videos and files sent with a message. Any file can be attached, of any size the phone has room for:
 * pictures are shrunk (at most [PHOTO_EDGE] pixels) and kept with the chat; everything else is copied as it is,
 * streamed (never held whole in memory), into the Library's "uploads" folder, so Atlas's tools and bots can use it
 * later. How the AI service then reads each file (inline, uploaded, stills, text, in parts…) is [AttachRules] and
 * [AttachSend]. Word, Excel and PowerPoint files get their text read out once, into a hidden ".name.txt" beside them.
 */
object Attach {
    /** Items in one message. */
    const val MAX_ITEMS = 10
    const val PHOTO_EDGE = 1600
    /** Text read from a file is cut here when it goes into the message (longer text is read in parts). */
    const val TEXT_MAX_CHARS = AttachRules.TEXT_INLINE_CHARS
    /** Copying stops if the phone would have less than this left. */
    const val RESERVE = 200L * 1024 * 1024

    /** [name]: the file's own name. Its kind, size and text are worked out from the file (see [AttachRules]). */
    class Item(val path: String, val mime: String, val name: String = "") {
        val kind get() = AttachRules.kind(name.ifEmpty { path }, mime)
        val video get() = kind == "video"
        val image get() = kind == "image"
        val pdf get() = kind == "pdf"
        /** Text Atlas reads (a text file, or the text read out of Word, Excel or PowerPoint). */
        val textFile get() = AttachRules.readsAsText(kind)
        /** Anything that isn't a picture or a video: shown as a file tile. */
        val isFile get() = !image && !video
        val file get() = File(path)
        val size get() = file.length()
        /** Where its text is: the hidden sidecar for Office files, else the file itself. */
        val textSource: File get() = sidecar(file).takeIf { it.exists() } ?: file
    }

    fun sidecar(f: File) = File(f.parentFile, ".${f.name}.txt")

    private fun nameOf(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "file"
    } catch (_: Exception) { uri.lastPathSegment ?: "file" }

    class TooBig(msg: String) : Exception(msg)

    internal fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }
    /** Kept files: inside the bots' folder, so the Library lists them and tools may read them. */
    internal fun uploads(ctx: Context) = File(ctx.filesDir, "bot/uploads").apply { mkdirs() }

    /** True if [f] is something attached (or a bot file): the only files Atlas's file_read / file_search may open. */
    fun readable(ctx: Context, f: File): Boolean = try {
        val c = f.canonicalPath
        listOf(dir(ctx), File(ctx.filesDir, "bot")).any { c.startsWith(it.canonicalPath + File.separator) } && f.isFile
    } catch (_: Exception) { false }

    /**
     * Copies what the user picked, pasted or snapped into Atlas. [progress] (bytes so far, total or -1) is called as
     * it goes; cancelling the coroutine stops the copy and removes the part copied. Throws [TooBig] (a sentence for
     * the user) only when the phone hasn't the room.
     */
    suspend fun take(ctx: Context, uri: Uri, progress: (Long, Long) -> Unit = { _, _ -> }): Item {
        val cx = currentCoroutineContext()
        val cr = ctx.contentResolver
        val name = nameOf(ctx, uri)
        val type = cr.getType(uri)
        val kind = AttachRules.kind(name, type)
        if (kind == "image") {
            // decode (any format the phone reads) straight at the smaller size, and save as JPEG: small, and accepted everywhere
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val scale = minOf(1f, PHOTO_EDGE.toFloat() / maxOf(w, h))
                    d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) { null }
            if (bmp != null) {
                val out = File(dir(ctx), "${UUID.randomUUID()}.jpg")
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                return Item(out.absolutePath, "image/jpeg", name)
            }
            // a picture the phone can't decode is still attached, as a file
        }
        val total = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
        val up = uploads(ctx)
        if (total > 0 && total + RESERVE > up.usableSpace)
            throw TooBig("Not enough room on the phone for “$name” (${AttachRules.size(total)}): ${AttachRules.size((up.usableSpace - RESERVE).coerceAtLeast(0))} is free to use.")
        val safe = BrowserRules.safeFileName(name, "file")
        val out = synchronized(this) { File(up, BotFileRules.freshName(safe) { File(up, it).exists() }).also { it.createNewFile() } }
        try {
            val ins = cr.openInputStream(uri) ?: throw TooBig("Couldn't read “$name”.")
            ins.use { i -> out.outputStream().use { o -> copyStream(i, o, total, progress) { cx.ensureActive(); if (up.usableSpace < RESERVE) throw TooBig("The phone ran out of room copying “$name”.") } } }
            val mime = type?.takeIf { it != "application/octet-stream" } ?: guessMime(out.name, kind)
            if (kind == "docx" || kind == "xlsx" || kind == "pptx") try { OfficeText.extract(out, kind, sidecar(out)) } catch (e: Exception) { sidecar(out).delete() }
            BotFiles.keepUpload(ctx, out, name)
            return Item(out.absolutePath, mime, name)
        } catch (e: Throwable) { out.delete(); sidecar(out).delete(); throw e }
    }

    private fun guessMime(name: String, kind: String): String = when (kind) {
        "pdf" -> "application/pdf"; "text" -> "text/plain"; "zip" -> "application/zip"
        "video" -> when (AttachRules.ext(name)) { "webm" -> "video/webm"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"; else -> "video/mp4" }
        "audio" -> when (AttachRules.ext(name)) { "wav" -> "audio/wav"; "ogg", "oga", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "m4a", "aac" -> "audio/aac"; else -> "audio/mpeg" }
        "image" -> when (AttachRules.ext(name)) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/jpeg" }
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachRules.ext(name)) ?: "application/octet-stream"
    }

    /**
     * [ins] to [out] in 256 KB steps: [progress] after each step, [check] (throws to stop) every 8 MB and at the end.
     * Nothing is held but the one buffer, so any size copies in constant memory.
     */
    internal fun copyStream(ins: java.io.InputStream, out: java.io.OutputStream, total: Long, progress: (Long, Long) -> Unit, check: () -> Unit): Long {
        val buf = ByteArray(256 * 1024); var done = 0L; var nextCheck = 0L
        while (true) {
            if (done >= nextCheck) { check(); nextCheck = done + 8L * 1024 * 1024 }
            val n = ins.read(buf); if (n < 0) break
            out.write(buf, 0, n); done += n
            progress(done, total)
        }
        check()
        return done
    }

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

    /** A text file's contents, put into the message itself (works with every service, offline too). */
    fun inlineText(item: Item): String {
        val t = readChars(item.textSource, TEXT_MAX_CHARS + 1)
        val cut = t.length > TEXT_MAX_CHARS
        return "\n\n[File: ${item.name.ifEmpty { item.file.name }}]\n" + t.take(TEXT_MAX_CHARS) + (if (cut) "\n…(only the start is here: the file is longer)" else "") + "\n[End of ${item.name}]"
    }

    /** Up to [max] characters of [f] as UTF-8 text (bad bytes become �), read without loading the rest. */
    fun readChars(f: File, max: Int): String {
        val out = StringBuilder(); val buf = CharArray(64 * 1024)
        java.io.InputStreamReader(f.inputStream(), Charsets.UTF_8).use { r ->
            while (out.length < max) { val n = r.read(buf, 0, minOf(buf.size, max - out.length)); if (n < 0) break; out.append(buf, 0, n) }
        }
        return out.toString()
    }

    /** The bytes for the AI service. */
    fun media(item: Item): Providers.Media {
        check(item.size <= AttachRules.INLINE_MAX) { "too big to send inline" }
        return Providers.Media(item.mime, Base64.encodeToString(item.file.readBytes(), Base64.NO_WRAP), item.name)
    }

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
