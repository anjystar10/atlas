package com.anjy.atlas

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini's Files API, for attachments too big to send inline (up to 2 GB a file; Gemini keeps them 48 hours, and a
 * file is only visible to the key's own project). The upload is the documented resumable protocol: a start request
 * that returns an upload address, then the bytes streamed from disk in one finalising request, so memory use stays
 * flat whatever the size. Videos (and some other files) are processed before use, so we wait until ACTIVE.
 */
object GeminiFiles {
    private const val BASE = "https://generativelanguage.googleapis.com"
    /** Re-sending the same file with the same key within a day reuses the upload. */
    private val done = ConcurrentHashMap<String, Pair<String, Long>>()

    class Failed(msg: String) : Exception(msg)

    /** Uploads [f] and returns its file uri for a file_data part. [progress]: bytes sent, total. [check] throws to stop. */
    fun upload(key: String, f: File, mime: String, name: String, progress: (Long, Long) -> Unit = { _, _ -> }, check: () -> Unit = {}): String {
        val id = "${key.hashCode()}|${f.path}|${f.length()}|${f.lastModified()}"
        done[id]?.let { (uri, at) -> if (System.currentTimeMillis() - at < 24 * 3600_000L) return uri }
        val size = f.length()
        if (size > AttachRules.UPLOAD_MAX) throw Failed("Over Gemini's 2 GB limit.")
        // 1. start: say what's coming, get the address to send it to
        val start = (URL("$BASE/upload/v1beta/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            setRequestProperty("Content-Type", "application/json")
        }
        val to = try {
            start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", name.take(120))).toString().toByteArray()) }
            if (start.responseCode !in 200..299) throw Failed("Gemini wouldn't take the upload (${start.responseCode}): ${err(start)}")
            start.getHeaderField("x-goog-upload-url") ?: throw Failed("Gemini gave no upload address.")
        } finally { start.disconnect() }
        // 2. the bytes, streamed from disk, finishing the upload
        val put = (URL(to).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 300_000
            setFixedLengthStreamingMode(size)
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }
        val body = try {
            put.outputStream.use { o -> f.inputStream().use { i -> Attach.copyStream(i, o, size, progress, check) } }
            if (put.responseCode !in 200..299) throw Failed("The upload to Gemini failed (${put.responseCode}): ${err(put)}")
            put.inputStream.bufferedReader().use { it.readText() }
        } finally { put.disconnect() }
        var file = JSONObject(body).optJSONObject("file") ?: throw Failed("Gemini didn't say where the file went.")
        val uri = file.optString("uri").ifEmpty { throw Failed("Gemini didn't say where the file went.") }
        // 3. wait until it's ready (videos take a while to be processed)
        val until = System.currentTimeMillis() + 10 * 60_000L
        while (file.optString("state", "ACTIVE") == "PROCESSING") {
            if (System.currentTimeMillis() > until) throw Failed("Gemini is still processing the file after 10 minutes.")
            check(); Thread.sleep(3000)
            val g = (URL("$BASE/v1beta/${file.optString("name")}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("x-goog-api-key", key)
            }
            try { if (g.responseCode in 200..299) file = JSONObject(g.inputStream.bufferedReader().use { it.readText() }) } finally { g.disconnect() }
        }
        if (file.optString("state") == "FAILED") throw Failed("Gemini couldn't process the file.")
        done[id] = uri to System.currentTimeMillis()
        return uri
    }

    private fun err(c: HttpURLConnection) = try { c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) ?: "" } catch (_: Exception) { "" }
}
