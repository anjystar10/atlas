package com.anjy.atlas

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets another app open or receive one of Atlas Bot's files (a PDF in a PDF reader, a spreadsheet in Sheets, a share
 * to WhatsApp) without making anything public: the provider isn't exported, and each Open or Share grants read access
 * to that one file only, for as long as the other app needs it. Only files inside the bots' folders can be served.
 */
class BotFileProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.anjy.atlas.botfiles"
        private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "bot")

        fun uriFor(ctx: Context, f: File): Uri? {
            val r = root(ctx).canonicalFile; val c = f.canonicalFile
            if (!c.path.startsWith(r.path + File.separator)) return null
            val rel = c.path.substring(r.path.length + 1).split(File.separator).joinToString("/") { Uri.encode(it) }
            return Uri.parse("content://$AUTHORITY/$rel")
        }
        fun mime(name: String): String = when (BotFileRules.ext(name)) {
            "pdf" -> "application/pdf"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "csv" -> "text/csv"
            "md" -> "text/markdown"; "txt", "log" -> "text/plain"; "html", "htm" -> "text/html"; "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(BotFileRules.ext(name)) ?: "application/octet-stream"
        }
        /** Opens [f] in the app the user picked for that kind of file. False if none can. */
        fun open(ctx: Context, f: File): Boolean {
            val u = uriFor(ctx, f) ?: return false
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { ctx.startActivity(Intent.createChooser(i, "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
        }
        fun share(ctx: Context, f: File): Boolean {
            val u = uriFor(ctx, f) ?: return false
            val i = Intent(Intent.ACTION_SEND).setType(mime(f.name)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return try { ctx.startActivity(Intent.createChooser(i, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
        }
    }

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val r = root(ctx).canonicalFile
        val f = File(r, uri.pathSegments.joinToString(File.separator)).canonicalFile
        if (!f.path.startsWith(r.path + File.separator) || !f.isFile || f.name == "library.json") throw FileNotFoundException("not a bot file")
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String = mime(uri.lastPathSegment ?: "")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
