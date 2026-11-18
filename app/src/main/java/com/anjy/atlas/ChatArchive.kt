package com.anjy.atlas

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Earlier chats, kept on this phone. "New chat" puts the current conversation here instead of throwing it away;
 * the Chats list opens one again (it becomes the conversation, and the one you were in is kept here in its place).
 * Each chat is its own file (the same shape as chat.json) plus one small index. Small and synchronous: callers
 * use it off the main thread where they can, and the lists are short.
 */
object ChatArchive {
    private const val TAG = "AtlasChats"
    /** Oldest chats beyond this are let go, so the folder can't grow without end. */
    private const val KEEP = 200

    data class Meta(val id: String, val title: String, val at: Long, val count: Int)

    private fun dir(ctx: Context) = File(ctx.filesDir, "chats").apply { mkdirs() }
    private fun index(ctx: Context) = AtomicFile(File(dir(ctx), "index.json"))
    private fun chatFile(ctx: Context, id: String) = AtomicFile(File(dir(ctx), "${id.filter { it.isLetterOrDigit() || it == '-' }}.json"))

    @Synchronized fun list(ctx: Context): List<Meta> = try {
        val f = index(ctx)
        if (!f.baseFile.exists()) emptyList() else JSONArray(String(f.readFully(), Charsets.UTF_8)).let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { Meta(it.getString("id"), it.optString("title", "Chat"), it.optLong("at"), it.optInt("count")) } }
        }.sortedByDescending { it.at }
    } catch (e: Exception) { Log.w(TAG, "index unreadable", e); emptyList() }

    private fun writeIndex(ctx: Context, metas: List<Meta>) {
        val a = JSONArray()
        metas.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("at", it.at).put("count", it.count)) }
        write(index(ctx), a.toString())
    }

    private fun write(f: AtomicFile, s: String) {
        val out = f.startWrite()
        try { out.write(s.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    /** Keeps [records] (chat.json records) as a saved chat; returns its id, or null if there was nothing to keep. */
    @Synchronized fun save(ctx: Context, records: JSONArray, firstQuestion: String?, lastAt: Long): String? {
        if (records.length() == 0) return null
        val id = java.util.UUID.randomUUID().toString()
        return try {
            write(chatFile(ctx, id), records.toString())
            val all = (list(ctx) + Meta(id, UiShape.chatTitle(firstQuestion), lastAt, records.length())).sortedByDescending { it.at }
            all.drop(KEEP).forEach { chatFile(ctx, it.id).delete() }
            writeIndex(ctx, all.take(KEEP))
            id
        } catch (e: Exception) { Log.w(TAG, "couldn't keep the chat", e); null }
    }

    @Synchronized fun records(ctx: Context, id: String): JSONArray? = try {
        val f = chatFile(ctx, id)
        if (!f.baseFile.exists()) null else JSONArray(String(f.readFully(), Charsets.UTF_8))
    } catch (e: Exception) { Log.w(TAG, "chat unreadable", e); null }

    @Synchronized fun rename(ctx: Context, id: String, title: String) {
        val t = title.trim().take(60).ifEmpty { return }
        writeIndex(ctx, list(ctx).map { if (it.id == id) it.copy(title = t) else it })
    }

    @Synchronized fun delete(ctx: Context, id: String) {
        chatFile(ctx, id).delete()
        writeIndex(ctx, list(ctx).filter { it.id != id })
    }

    /** All the words of a chat, for searching. */
    fun text(ctx: Context, id: String): String {
        val a = records(ctx, id) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) sb.append(a.optJSONObject(i)?.optString("text").orEmpty()).append('\n')
        return sb.toString()
    }

    fun search(ctx: Context, query: String): List<Meta> =
        if (query.isBlank()) list(ctx) else list(ctx).filter { UiShape.matches(query, it.title, text(ctx, it.id)) }

    /** Pictures and files the saved chats still show, so tidying attachments never deletes them. */
    fun mediaPaths(ctx: Context): Set<String> {
        val keep = HashSet<String>()
        list(ctx).forEach { m ->
            val a = records(ctx, m.id) ?: return@forEach
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONArray("media")?.let { md ->
                for (k in 0 until md.length()) md.optJSONObject(k)?.optString("path")?.takeIf { it.isNotEmpty() }?.let { keep.add(it) }
            }
        }
        return keep
    }
}
