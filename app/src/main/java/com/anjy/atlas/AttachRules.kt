package com.anjy.atlas

/**
 * What an attachment is and how Atlas reads it. Pure rules (no Android), the same as the PC's attachrules.js; both
 * are checked against tools/attach-fixtures.json.
 *
 * Every file can be attached. What the AI service gets depends on the kind and the size:
 *  - native: the file itself, inline in the message (pictures always: they are shrunk first);
 *  - upload: too big to send inline, so it goes up through Gemini's Files API (up to 2 GB; Gemini keeps it 48 hours);
 *  - frames: a video another service can't watch, or one past Gemini's limit: a few stills taken from it;
 *  - text: the text read out of it (text, code, Word, Excel, PowerPoint), put in the message;
 *  - chunks: text too long for one message: Atlas searches it and reads parts with file_search / file_read;
 *  - listing: an archive: the list of what's inside; file_read opens a readable member;
 *  - describe: nothing it can read whole: its name, size and type (and any readable text, via file_read).
 */
object AttachRules {
    const val MB = 1024L * 1024L
    /** The most sent inline (as base64, in memory). Well under the services' own limits (Gemini: 100 MB, PDFs 50 MB). */
    const val INLINE_MAX = 14 * MB
    /** Gemini Files API: up to 2 GB a file. */
    const val UPLOAD_MAX = 2048 * MB
    /** Text up to this many characters goes into the message; longer is read in parts. */
    const val TEXT_INLINE_CHARS = 120_000
    /** Stills taken from a video another service can't watch. */
    const val FRAMES = 8

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")
    private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v", "3gp", "mkv", "avi")
    private val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "amr", "weba")
    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "html", "htm", "log", "ini", "toml",
        "cfg", "conf", "env", "properties", "gradle", "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "ps1", "bat", "sql", "lua", "dart", "css", "scss", "svg", "tex", "srt",
        "vtt", "rtf", "r", "m", "scala", "pl", "vue", "svelte", "ipynb")
    private val TEXT_NAMES = setOf("dockerfile", "makefile", "readme", "license", "gemfile", "procfile")
    private val TEXT_MIME = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/csv",
        "application/x-sh", "application/sql", "application/rtf", "image/svg+xml")

    fun ext(name: String): String = name.substringAfterLast('/').let { if ('.' in it) it.substringAfterLast('.').lowercase() else "" }

    /** image, video, audio, pdf, text, docx, xlsx, pptx, zip or binary. The name's extension wins over a vague type. */
    fun kind(name: String, mime: String?): String {
        val e = ext(name); val m = (mime ?: "").lowercase()
        return when {
            e == "svg" || m == "image/svg+xml" -> "text"
            e in IMAGE_EXT || (m.startsWith("image/") && e !in TEXT_EXT) -> "image"
            e in VIDEO_EXT || m.startsWith("video/") -> "video"
            e in AUDIO_EXT || m.startsWith("audio/") -> "audio"
            e == "pdf" || m == "application/pdf" -> "pdf"
            e == "docx" || m.contains("wordprocessingml") -> "docx"
            e == "xlsx" || m.contains("spreadsheetml") -> "xlsx"
            e == "pptx" || m.contains("presentationml") -> "pptx"
            e == "zip" || m == "application/zip" || m == "application/x-zip-compressed" -> "zip"
            e in TEXT_EXT || m.startsWith("text/") || m in TEXT_MIME -> "text"
            e.isEmpty() && name.substringAfterLast('/').lowercase() in TEXT_NAMES -> "text"
            else -> "binary"
        }
    }

    fun readsAsText(kind: String) = kind == "text" || kind == "docx" || kind == "xlsx" || kind == "pptx"

    /**
     * How a [kind] file of [size] bytes reaches a service of [style] (gemini, anthropic, or openai for every
     * OpenAI-compatible one). [chars]: for text kinds, the length of the text read from it.
     */
    fun route(kind: String, size: Long, style: String, chars: Int = 0): String {
        val gemini = style == "gemini"
        return when (kind) {
            "image" -> "native"
            "video" -> if (gemini && size <= INLINE_MAX) "native" else if (gemini && size <= UPLOAD_MAX) "upload" else "frames"
            "audio" -> if (!gemini) "describe" else if (size <= INLINE_MAX) "native" else if (size <= UPLOAD_MAX) "upload" else "describe"
            "pdf" -> if (size <= INLINE_MAX) "native" else if (gemini && size <= UPLOAD_MAX) "upload" else "describe"
            "text", "docx", "xlsx", "pptx" -> if (chars <= TEXT_INLINE_CHARS) "text" else "chunks"
            "zip" -> "listing"
            else -> "describe"
        }
    }

    /** One honest line for the user about what Atlas will actually read ("" when it reads the whole thing). */
    fun note(kind: String, route: String, service: String): String = when (route) {
        "upload" -> "Large: it goes up to Gemini for this message (Gemini keeps it 48 hours)."
        "frames" -> if (service == "Gemini") "Over 2 GB: Atlas sees $FRAMES stills from it, not the motion or sound."
                    else "$service can't watch video: Atlas sees $FRAMES stills from it, not the motion or sound. Gemini can watch it whole."
        "chunks" -> "Too long to read at once: Atlas searches it and reads the parts it needs."
        "listing" -> "Atlas sees the list of files inside and can open the readable ones."
        "describe" -> when (kind) {
            "audio" -> if (service == "Gemini") "Over 2 GB: Atlas sees only its name, size and type." else "Only Gemini can listen to audio: with $service, Atlas sees only its name, size and type."
            "pdf" -> "Too big for $service to read whole: Atlas sees its name and size.${if (service != "Gemini") " Gemini can read PDFs up to 2 GB." else ""}"
            else -> "Atlas can't open this kind of file: it sees its name, size, type and any readable text inside."
        }
        else -> ""
    }

    /** "3.4 MB", "820 KB", "1.2 GB". */
    fun size(bytes: Long): String = when {
        bytes >= 1024 * MB -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * MB))
        bytes >= MB -> "%.1f MB".format(java.util.Locale.ROOT, bytes / MB.toDouble())
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }

    /**
     * Readable runs of text inside any bytes (like the "strings" tool): at least [min] printable characters in a row,
     * at most [max] characters in all. For a file Atlas can't otherwise open.
     */
    fun strings(bytes: ByteArray, len: Int = bytes.size, min: Int = 5, max: Int = 4000): String {
        val out = StringBuilder(); val run = StringBuilder()
        fun flush() { if (run.length >= min && out.length < max) { if (out.isNotEmpty()) out.append(' '); out.append(run, 0, minOf(run.length, max - out.length)) }; run.setLength(0) }
        for (i in 0 until len) { val b = bytes[i].toInt() and 0xFF; if (b in 32..126 || b == 9) run.append(b.toChar()) else flush() }
        flush()
        return out.toString()
    }

    /** The first bytes as hex, 16 to a line, for a file Atlas can't otherwise open. */
    fun hex(bytes: ByteArray, len: Int = bytes.size, max: Int = 256): String =
        (0 until minOf(len, max)).chunked(16).joinToString("\n") { row -> row.joinToString(" ") { "%02x".format(bytes[it]) } }

    /**
     * Where a long text is cut into [size]-character parts for reading: the start of each part, nudged back to the
     * last line break within the final eighth so lines aren't split.
     */
    fun chunkStarts(text: CharSequence, size: Int): List<Int> {
        require(size > 0)
        val out = mutableListOf(0); var at = 0
        while (at + size < text.length) {
            var next = at + size
            val nl = text.lastIndexOf('\n', next - 1).let { if (it >= 0) it else -1 }
            if (nl > at + size - size / 8) next = nl + 1
            out.add(next); at = next
        }
        return out
    }

    private fun CharSequence.lastIndexOf(c: Char, from: Int): Int { var i = minOf(from, length - 1); while (i >= 0) { if (this[i] == c) return i; i-- }; return -1 }
}
