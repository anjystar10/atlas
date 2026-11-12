package com.anjy.atlas

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Atlas Bot's jobs, shown on the other device too: what each bot is doing, how far its plan is, and its report.
 * Read-only there. Each job runs only on the device that started it, so nothing is ever run twice; this is a window
 * onto it. Synced as kind "botview" (plain JSON, no secrets).
 */
object BotMirror {
    const val KIND = "botview"
    private const val REPORT_MAX = 20_000
    private fun file(ctx: Context) = File(ctx.filesDir, "bot-mirror.json")
    private val lock = Any()

    /** A job as the other device shows it. */
    fun view(j: BotJob, me: String): JSONObject = JSONObject()
        .put("id", j.id).put("device", me).put("platform", "android")
        .put("goal", j.goal.take(300)).put("state", j.state).put("label", BotRules.label(j.state))
        .put("current", (j.current ?: "").take(200))
        .put("done", j.plan.count { it.done }).put("total", j.plan.size)
        .put("report", (j.report ?: "").take(REPORT_MAX))
        .put("createdAt", j.createdAt).put("updatedAt", j.updatedAt)

    /** The other devices' jobs, newest first. */
    fun others(ctx: Context): List<JSONObject> = synchronized(lock) { read(ctx).optJSONObject("others")?.let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList() } ?: emptyList() }
        .sortedByDescending { it.optLong("createdAt") }

    /** {others: jobs from the other device, mine: this device's jobs as last sent (used while the journal isn't loaded)}. */
    private fun read(ctx: Context) = try { JSONObject(file(ctx).readText()).also { if (!it.has("others")) it.put("others", JSONObject()) } } catch (_: Exception) { JSONObject().put("others", JSONObject()).put("mine", JSONObject()) }
    private fun write(ctx: Context, o: JSONObject) { val f = file(ctx); val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() } }

    private fun list(ctx: Context): Map<String, JSONObject> {
        val me = Store.read(ctx) { it.deviceId }
        val out = LinkedHashMap<String, JSONObject>()
        val jobs = Bots.jobsForSync()
        synchronized(lock) {
            val o = read(ctx)
            if (jobs != null) {
                val mine = JSONObject()
                jobs.filter { it.device.isEmpty() || it.device == me }.forEach { j -> view(j, me).also { out[j.id] = it; mine.put(j.id, it) } }
                o.put("mine", mine); write(ctx, o)
            } else {
                // the journal isn't loaded (a background sync with Atlas closed): this device's jobs as last sent, unchanged
                o.optJSONObject("mine")?.let { m -> m.keys().forEach { id -> m.optJSONObject(id)?.let { out[id] = it } } }
            }
            // the other device's jobs stay as they came (so their absence here never reads as "deleted")
            o.optJSONObject("others")?.let { m -> m.keys().forEach { id -> if (id !in out) m.optJSONObject(id)?.let { out[id] = it } } }
        }
        return out
    }

    private fun take(ctx: Context, id: String, rec: JSONObject?) {
        val me = Store.read(ctx) { it.deviceId }
        synchronized(lock) {
            val o = read(ctx); val others = o.getJSONObject("others")
            if (rec == null) others.remove(id) else if (rec.optString("device") != me) others.put(id, rec)
            write(ctx, o)
        }
        Bots.mirrorChanged()
    }

    fun register() {
        try { Sync.register(KIND, Sync.Kind(list = { ctx -> list(ctx) }, apply = { ctx, id, rec -> take(ctx, id, rec) })) } catch (_: Exception) {}
    }
}
