package com.anjy.atlas

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.anjy.atlas.core.AtlasState
import com.anjy.atlas.core.LogEntry
import com.anjy.atlas.core.Memory
import com.anjy.atlas.core.Settings
import com.anjy.atlas.core.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** On-device storage. One JSON document written atomically, so a crash mid-write never corrupts data. */
object Store {
    private const val TAG = "AtlasStore"
    private const val SCHEMA = 1
    private val lock = Any()
    @Volatile private var cached: AtlasState? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "atlas-state.json"))

    /** Run [block] with exclusive access to the state, then save it. */
    fun <T> edit(ctx: Context, block: (AtlasState) -> T): T = synchronized(lock) {
        val s = load(ctx)
        val r = block(s)
        save(ctx, s)
        r
    }

    fun <T> read(ctx: Context, block: (AtlasState) -> T): T = synchronized(lock) { block(load(ctx)) }

    private fun load(ctx: Context): AtlasState {
        cached?.let { return it }
        val f = file(ctx)
        val s = try {
            if (f.baseFile.exists()) fromJson(JSONObject(String(f.readFully(), Charsets.UTF_8))) else AtlasState()
        } catch (e: Exception) {
            Log.e(TAG, "State unreadable, keeping a copy aside", e)
            try { f.baseFile.copyTo(File(ctx.filesDir, "atlas-state.unreadable-${System.currentTimeMillis()}.json"), true) } catch (_: Exception) {}
            AtlasState()
        }
        cached = s
        return s
    }

    private fun save(ctx: Context, s: AtlasState) {
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(toJson(s).toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    // ---- small settings, the way Stoa's UI code reads and writes them ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("atlas_ui", Context.MODE_PRIVATE)
    fun getString(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)
    fun putString(ctx: Context, key: String, value: String) { prefs(ctx).edit().putString(key, value).apply() }
    fun getFlag(ctx: Context, key: String, default: Boolean = false): Boolean = prefs(ctx).getBoolean(key, default)
    fun setFlag(ctx: Context, key: String, value: Boolean = true) { prefs(ctx).edit().putBoolean(key, value).apply() }

    fun exportJson(ctx: Context): String = read(ctx) { toJson(it).toString(2) }

    fun importJson(ctx: Context, text: String): Int = synchronized(lock) {
        val s = fromJson(JSONObject(text))
        cached = s
        save(ctx, s)
        s.tasks.size
    }

    // ---- JSON mapping ----
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun toJson(s: AtlasState): JSONObject {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("deviceId", s.deviceId)
        val st = s.settings
        o.put("settings", JSONObject().apply {
            put("name", st.name); put("tone", st.tone); put("quietStart", st.quietStart); put("quietEnd", st.quietEnd)
            put("graceMin", st.graceMin); put("followUps", st.followUps); put("sound", st.sound)
            put("avatarOn", st.avatarOn); put("speakReplies", st.speakReplies); put("theme", st.theme)
        })
        o.put("tasks", JSONArray().apply {
            s.tasks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("notes", t.notes); put("priority", t.priority)
                    put("startAt", t.startAt ?: JSONObject.NULL); put("durationMin", t.durationMin)
                    put("recurrence", t.recurrence); put("status", t.status); put("createdAt", t.createdAt)
                    put("updatedAt", t.updatedAt); put("version", t.version); put("deviceId", t.deviceId)
                    put("completedAt", t.completedAt ?: JSONObject.NULL); put("blockedReason", t.blockedReason)
                    put("skipReason", t.skipReason); put("seriesId", t.seriesId ?: JSONObject.NULL); put("seriesAt", t.seriesAt ?: JSONObject.NULL)
                    put("remindedAt", t.remindedAt ?: JSONObject.NULL); put("nextCheckAt", t.nextCheckAt ?: JSONObject.NULL)
                    put("promptedAt", t.promptedAt ?: JSONObject.NULL); put("followUpsSent", t.followUpsSent)
                })
            }
        })
        o.put("log", JSONArray().apply {
            s.log.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id); put("at", l.at); put("type", l.type); put("taskId", l.taskId ?: JSONObject.NULL)
                    put("title", l.title); put("detail", l.detail)
                })
            }
        })
        o.put("memories", JSONArray().apply {
            s.memories.forEach { m -> put(JSONObject().apply { put("id", m.id); put("text", m.text); put("createdAt", m.createdAt) }) }
        })
        return o
    }

    private fun fromJson(o: JSONObject): AtlasState {
        if (!o.has("tasks")) throw IllegalArgumentException("This is not an Atlas backup.")
        val s = AtlasState(o.optString("deviceId").ifEmpty { java.util.UUID.randomUUID().toString() })
        o.optJSONObject("settings")?.let { j ->
            s.settings = Settings(
                name = j.optString("name", ""), tone = j.optString("tone", "firm"),
                quietStart = j.optString("quietStart", "22:30"), quietEnd = j.optString("quietEnd", "06:30"),
                graceMin = j.optInt("graceMin", 15), followUps = j.optInt("followUps", 1),
                sound = j.optBoolean("sound", true), avatarOn = j.optBoolean("avatarOn", true),
                speakReplies = j.optBoolean("speakReplies", false),
                theme = j.optString("theme", "dark")
            )
        }
        val ts = o.getJSONArray("tasks")
        for (i in 0 until ts.length()) {
            val j = ts.getJSONObject(i)
            val title = j.optString("title").trim()
            if (title.isEmpty() || !j.has("id")) continue
            // Accept both this app's format and the web build's backup format (nested "rem" object).
            val rem = j.optJSONObject("rem")
            s.tasks.add(Task(
                id = j.getString("id"), title = title, notes = j.optString("notes", ""),
                priority = j.optString("priority", "normal"), startAt = j.optLongOrNull("startAt"),
                durationMin = j.optInt("durationMin", 30), recurrence = j.optString("recurrence", "none"),
                status = j.optString("status", "planned"), createdAt = j.optLong("createdAt", 0),
                updatedAt = j.optLong("updatedAt", 0), version = j.optInt("version", 1), deviceId = j.optString("deviceId", ""),
                completedAt = j.optLongOrNull("completedAt"), blockedReason = j.optString("blockedReason", ""),
                skipReason = j.optString("skipReason", ""), seriesId = j.optStringOrNull("seriesId"), seriesAt = j.optLongOrNull("seriesAt"),
                remindedAt = j.optLongOrNull("remindedAt") ?: rem?.optLongOrNull("remindedAt"),
                nextCheckAt = j.optLongOrNull("nextCheckAt") ?: rem?.optLongOrNull("nextCheckAt"),
                promptedAt = j.optLongOrNull("promptedAt") ?: rem?.optLongOrNull("promptedAt"),
                followUpsSent = if (j.has("followUpsSent")) j.optInt("followUpsSent") else rem?.optInt("followUps", 0) ?: 0
            ))
        }
        o.optJSONArray("log")?.let { a ->
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                s.log.add(LogEntry(j.optString("id"), j.optLong("at"), j.optString("type"), j.optStringOrNull("taskId"), j.optString("title"), j.optString("detail")))
            }
        }
        o.optJSONArray("memories")?.let { a ->
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val text = j.optString("text").trim()
                if (text.isNotEmpty()) s.memories.add(Memory(j.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }, text, j.optLong("createdAt")))
            }
        }
        return s
    }
}
