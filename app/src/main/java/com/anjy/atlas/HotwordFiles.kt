package com.anjy.atlas

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The "Hey Atlas" model's files: whether it's downloaded, and downloading it. Kept apart from [Hotword] on purpose:
 * this has nothing to do with the speech engine itself, so checking it never loads the engine's (heavy, native)
 * classes on the main thread.
 */
object HotwordFiles {
    const val VERSION = "small-en-us-0.15"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    const val MODEL_MB = 41

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-$VERSION")

    /** Whether the wake word model has been downloaded and unpacked. */
    fun ready(ctx: Context): Boolean = File(dir(ctx), ".complete").exists()

    /** Set while a download is running: "Downloading 40%" and so on, for the settings screen. */
    @Volatile var downloadState: String? = null; private set

    /**
     * Downloads, checks and unpacks the model. Blocking network work: call from a background thread.
     * [progress] gets 0..100. Throws with a plain message when it can't.
     */
    @Synchronized fun download(ctx: Context, progress: (Int) -> Unit) {
        if (ready(ctx)) return
        val zip = File(ctx.cacheDir, "vosk-model.zip")
        try {
            downloadState = "Downloading 0%"
            var last = -1
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val con = URL(MODEL_URL).openConnection() as HttpURLConnection
                    con.connectTimeout = 20_000; con.readTimeout = 60_000
                    try {
                        if (con.responseCode !in 200..299) throw IllegalStateException("the download server answered ${con.responseCode}")
                        val total = con.contentLengthLong
                        val md = MessageDigest.getInstance("SHA-256")
                        con.inputStream.use { i -> zip.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16); var done = 0L
                            while (true) {
                                val n = i.read(buf); if (n < 0) break
                                o.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != last && pct >= 0) { last = pct; downloadState = "Downloading $pct%"; progress(pct) }
                            }
                        } }
                        val sha = md.digest().joinToString("") { "%02x".format(it) }
                        if (sha != MODEL_SHA256) throw IllegalStateException("the downloaded file didn't match (it may have been cut off)")
                    } finally { con.disconnect() }
                    break
                } catch (e: Exception) {
                    if (attempt >= 3) throw e
                    Thread.sleep(3000L * attempt)
                }
            }
            downloadState = "Unpacking"
            val target = dir(ctx)
            target.deleteRecursively(); target.mkdirs()
            val root = target.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    // the zip has one top folder (vosk-model-small-en-us-0.15/); its contents go straight into target
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(target, rel)
                    if (!out.canonicalPath.startsWith(root)) throw IllegalStateException("unsafe path in the model file")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it, 1 shl 16) } }
                }
            }
            File(target, ".complete").writeText(VERSION)
        } finally {
            zip.delete()
            downloadState = null
        }
    }
}
