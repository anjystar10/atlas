package com.anjy.atlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import com.anjy.atlas.core.Updates
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atlas knows itself on the phone: which version it is, what changed (CHANGELOG.json, bundled as an asset), when it
 * was updated, and whether a newer release is out (latest.json on the repo's "releases" branch, written by CI only
 * for release builds from main).
 *
 * - [onLaunch]: the first launch after an update posts one chat line ("I've been updated to 0.11.0 on your phone…")
 *   and keeps "Recent updates" in the brief for a few days.
 * - [check]: at most every 12 hours (and whenever asked), looks for a newer release; tells the user once per version.
 * - [install]: downloads the APK in the background, checks its SHA-256 and that it's signed with the same key as this
 *   Atlas, then opens Android's installer. The user taps Install; nothing is ever installed silently.
 */
object Updater {
    const val LATEST_URL = "https://raw.githubusercontent.com/anjystar10/atlas/releases/latest.json"
    private const val P = "atlas_self"
    private const val CH = "atlas_updates"
    private const val NOTE_ID = 7401
    private const val TAG = "AtlasUpdater"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    @Volatile private var cl: List<Updates.Entry>? = null
    fun changelog(ctx: Context): List<Updates.Entry> = cl ?: try {
        Updates.parseChangelog(ctx.assets.open("CHANGELOG.json").bufferedReader().use { it.readText() }).also { cl = it }
    } catch (e: Exception) { Log.w(TAG, "changelog", e); emptyList() }

    fun version(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    fun versionCode(ctx: Context): Int = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() } catch (_: Exception) { 0 }

    /** Main thread, once per app start (MainActivity). */
    fun onLaunch(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val p = prefs(app); val cur = version(app); if (cur.isEmpty()) return
            val hadData = try { Conversation.load(app); Conversation.messages.isNotEmpty() } catch (_: Exception) { false }
            val st = Updates.launchState(p.getString("last", null), cur, hadData)
            if (st.kind == "updated" || st.kind == "downgraded") {
                p.edit().putLong("updatedAt", System.currentTimeMillis()).putString("from", st.from).apply()
                if (st.kind == "updated") Conversation.postFromBot(app, Updates.updatedMessage(changelog(app), st.from, cur, "on your phone"), source = "Atlas")
            }
            p.edit().putString("last", cur).apply()
        } catch (e: Exception) { Log.w(TAG, "launch", e) }
        Thread { try { check(app, force = false) } catch (_: Exception) {} }.start()
    }

    fun latest(ctx: Context): Updates.Latest? = prefs(ctx).getString("latest", null)?.let { Updates.parseLatest(it) }

    fun state(ctx: Context): Updates.State {
        val p = prefs(ctx)
        val others = try { Sync.status(ctx).others.map { Updates.Other(it.platform, it.name, it.app) } } catch (_: Exception) { emptyList() }
        return Updates.State(version(ctx), versionCode(ctx), "the phone", p.getString("from", null), p.getLong("updatedAt", 0), System.currentTimeMillis(), others, latest(ctx))
    }

    fun briefLines(ctx: Context): List<String> = try { Updates.briefLines(changelog(ctx), state(ctx)) } catch (_: Exception) { emptyList() }
    fun about(ctx: Context): String = Updates.aboutText(changelog(ctx), state(ctx))

    /** Looks for a newer release (background thread). [force]: even if checked recently. Returns a sentence. */
    fun check(ctx: Context, force: Boolean): String {
        val app = ctx.applicationContext; val p = prefs(app); val now = System.currentTimeMillis()
        if (!force && !Updates.checkDue(p.getLong("checkedAt", 0), now)) return latest(app)?.let { describe(app, it) } ?: "Checked recently."
        val text = try { get(LATEST_URL) } catch (e: Exception) { return "Couldn't check for updates: ${e.message ?: "no connection"}." }
        val l = Updates.parseLatest(text) ?: return "Couldn't read the update information."
        p.edit().putLong("checkedAt", now).putString("latest", text).apply()
        val cur = version(app); val code = versionCode(app)
        if (Updates.shouldAnnounce(l, cur, code, p.getString("announced", null))) {
            p.edit().putString("announced", l.version).apply()
            val msg = Updates.availableMessage(l, cur)
            android.os.Handler(android.os.Looper.getMainLooper()).post { try { Conversation.postFromBot(app, msg, source = "Atlas") } catch (_: Exception) {} }
            notify(app, "Atlas ${l.version} is ready", "Open Atlas and say \"update Atlas\", or tap here.", openAtlas(app))
        }
        return describe(app, l)
    }
    private fun describe(ctx: Context, l: Updates.Latest) =
        if (Updates.isNewer(l, version(ctx), versionCode(ctx))) "Atlas ${l.version} is available (this phone has ${version(ctx)})." + (if (l.notes.isNotEmpty()) " New: ${l.notes.take(4).joinToString("; ")}." else "")
        else "Atlas is up to date (${version(ctx)}; newest release ${l.version})."

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("the server said ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ installing

    @Volatile var downloading = false; private set

    /**
     * Background thread. Downloads the newest release, checks it, and opens the installer (or, when Atlas isn't on
     * screen, a notification that opens it). Returns a sentence for the chat.
     */
    fun install(ctx: Context): String {
        val app = ctx.applicationContext
        check(app, force = true)
        val l = latest(app) ?: return "I couldn't find the update information (no connection?)."
        if (!Updates.isNewer(l, version(app), versionCode(app))) return "Atlas is already up to date (${version(app)})."
        if (downloading) return "The update is already downloading."
        if (!app.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startOrNotify(app, i, "Allow Atlas to install its updates", "Turn on \"Allow from this source\", then say \"update Atlas\" again.")
            return "First, Android needs your OK for Atlas to install its own updates: I've opened that setting (turn on \"Allow from this source\"), then ask me again."
        }
        downloading = true
        try {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name != "Atlas-${l.version}.apk") it.delete() }
            val f = File(dir, "Atlas-${l.version}.apk")
            val c = URL(l.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            val md = MessageDigest.getInstance("SHA-256")
            try {
                if (c.responseCode !in 200..299) return "The download failed (the server said ${c.responseCode})."
                c.inputStream.use { ins -> f.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                } }
            } finally { c.disconnect() }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (l.sha256.isNotEmpty() && sha != l.sha256) { f.delete(); return "The download didn't match what was published, so I deleted it. Try again in a bit." }
            if (!Updates.sameSigner(signers(app, null), signers(app, f))) { f.delete(); return "That download isn't signed with Atlas's own key, so I deleted it and won't install it." }
            val uri = UpdateProvider.uriFor(f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startOrNotify(app, i, "Atlas ${l.version} is ready to install", "Tap to install it.")
            return "Atlas ${l.version} is downloaded and checked. Android's installer is up: tap Install (Atlas restarts on the new version)."
        } catch (e: Exception) {
            Log.w(TAG, "install", e); return "The download failed: ${e.message ?: "no connection"}."
        } finally { downloading = false }
    }

    /** SHA-256 of the signing certificates: of this app ([apk] null) or of a downloaded APK. */
    @Suppress("DEPRECATION")
    private fun signers(ctx: Context, apk: File?): List<String> = try {
        val pm = ctx.packageManager
        val info = if (apk == null) pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                   else pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info?.signingInfo
        val sigs = when { si == null -> emptyArray(); si.hasMultipleSigners() -> si.apkContentsSigners; else -> si.signingCertificateHistory }
        (sigs ?: emptyArray()).map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    } catch (_: Exception) { emptyList() }

    private fun startOrNotify(ctx: Context, i: Intent, title: String, body: String) {
        if (PermissionCenter.atlasInFront) { try { ctx.startActivity(i); return } catch (_: Exception) {} }
        notify(ctx, title, body, PendingIntent.getActivity(ctx, NOTE_ID, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
    private fun openAtlas(ctx: Context) = PendingIntent.getActivity(ctx, NOTE_ID + 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(ctx: Context, title: String, body: String, pi: PendingIntent) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Atlas updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a new version of Atlas is ready" })
            nm.notify(NOTE_ID, Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { Log.w(TAG, "notify", e) }
    }
}

/** Hands the downloaded update to Android's installer, and nothing else (read only, one folder, not exported). */
class UpdateProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.anjy.atlas.updates"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.name)}")
    }
    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val dir = File(ctx.cacheDir, "updates").canonicalFile
        val f = File(dir, uri.lastPathSegment ?: "").canonicalFile
        if (f.parentFile != dir || !f.isFile || !f.name.endsWith(".apk")) throw FileNotFoundException("not an update")
        return f
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
