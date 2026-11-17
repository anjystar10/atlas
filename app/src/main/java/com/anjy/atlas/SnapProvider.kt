package com.anjy.atlas

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the camera app puts a Snap: one empty file in Atlas's cache, lent to the camera for writing through a
 * content:// address (not exported; the camera gets access to that one file only, for that one capture). Atlas then
 * takes it like any other attachment and deletes the temporary file.
 */
class SnapProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.anjy.atlas.snap"
        private fun dir(ctx: Context) = File(ctx.applicationContext.cacheDir, "snap").apply { mkdirs() }

        /** A fresh file for a photo ("jpg") or video ("mp4"), named for when it was taken. */
        fun newFile(ctx: Context, ext: String): File {
            dir(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date())
            return File(dir(ctx), "${if (ext == "mp4") "Video" else "Photo"} $stamp.$ext").also { it.delete(); it.createNewFile() }
        }
        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.name).build()
    }

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (f.parentFile?.canonicalPath != dir(ctx).canonicalPath) throw FileNotFoundException("not a snap")
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String = if (uri.lastPathSegment?.endsWith(".mp4") == true) "video/mp4" else "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(uri)
        if (!f.exists()) throw FileNotFoundException("gone")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
