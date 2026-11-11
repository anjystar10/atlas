package com.anjy.atlas

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * "Share My AI Keys": your keys go to your other device inside the same encrypted sync (scrambled with your private
 * link code before they leave; the cloud can't read them). Off unless you turn it on, on each device.
 *
 * One record per service: {added: {key: when}, removed: {fingerprint of key: when}}. The two devices merge rather
 * than overwrite each other, so neither can lose a key: a key stays while its newest "added" is later than its newest
 * "removed". Atlas notices adds and removes by comparing the pool with what it saw last time, so the key pool itself
 * needs no changes. Keys are never written to the log.
 */
object KeyShare {
    const val KIND = "keys"
    private const val PREF_ON = "share_keys"
    private const val PREF_META = "keyshare_meta"     // {service: {added, removed, seen: [keys last seen in the pool]}}
    private const val PREF_INBOX = "keyshare_inbox"   // records from the other device while sharing is off here

    fun on(ctx: Context) = Store.getString(ctx, PREF_ON) == "1"

    /** Turns sharing on or off here. On: keys that came from the other device meanwhile are taken in, and ours go up. */
    fun set(ctx: Context, enabled: Boolean) {
        Store.putString(ctx, PREF_ON, if (enabled) "1" else "0")
        if (enabled) {
            val inbox = json(ctx, PREF_INBOX)
            inbox.keys().asSequence().toList().forEach { id -> inbox.optJSONObject(id)?.let { take(ctx, id, it) } }
            Store.putString(ctx, PREF_INBOX, "{}")
        }
        Sync.syncSoon(ctx, 500)
    }

    fun register() {
        try {
            Sync.register(KIND, Sync.Kind(list = { ctx -> if (on(ctx)) list(ctx) else emptyMap() },
                apply = { ctx, id, rec -> if (rec != null) { if (on(ctx)) take(ctx, id, rec) else stash(ctx, id, rec) } }, absentMeansDeleted = false))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the rules (as keyshare.js on the PC)

    /** A key's fingerprint, for the "removed" list (the key itself isn't kept once it's removed). */
    fun fingerprint(key: String) = MessageDigest.getInstance("SHA-256").digest("atlas-keyshare:$key".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(32)

    /** Both sides' adds and removes together: the newest time of each. */
    fun merge(a: JSONObject, b: JSONObject): JSONObject {
        fun newest(x: JSONObject?, y: JSONObject?): JSONObject {
            val o = JSONObject()
            listOfNotNull(x, y).forEach { m -> m.keys().forEach { k -> val t = m.optLong(k); if (t > o.optLong(k, Long.MIN_VALUE)) o.put(k, t) } }
            return o
        }
        return JSONObject().put("added", newest(a.optJSONObject("added"), b.optJSONObject("added")))
            .put("removed", newest(a.optJSONObject("removed"), b.optJSONObject("removed")))
    }

    /** The keys that are in: added later than they were (last) removed. */
    fun present(rec: JSONObject): List<String> {
        val added = rec.optJSONObject("added") ?: JSONObject(); val removed = rec.optJSONObject("removed") ?: JSONObject()
        return added.keys().asSequence().filter { k -> added.optLong(k) > removed.optLong(fingerprint(k), Long.MIN_VALUE) }.sorted().toList()
    }

    /**
     * Notes adds and removes since last time, by comparing the pool ([now]: its keys) with what was seen last ([seen]).
     * Returns the updated record (and the keys to remember as seen).
     */
    fun observe(rec: JSONObject, seen: List<String>, now: List<String>, at: Long): JSONObject {
        val added = rec.optJSONObject("added") ?: JSONObject(); val removed = rec.optJSONObject("removed") ?: JSONObject()
        val before = seen.toSet()
        // new here (added, or added again after a removal): stamped now
        now.forEach { k -> if (k !in before || !added.has(k)) added.put(k, at) }
        // gone from here since last time: removed now (only its fingerprint is kept, not the key)
        seen.forEach { k -> if (k !in now) { removed.put(fingerprint(k), at); added.remove(k) } }
        return JSONObject().put("added", added).put("removed", removed)
    }

    // ------------------------------------------------------------------ the pool

    private fun json(ctx: Context, pref: String) = try { JSONObject(Store.getString(ctx, pref) ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun services() = Providers.all.map { it.id }
    private fun pool(ctx: Context, id: String) = Providers.keys(ctx, id).map { it.key }

    /** Our records: one per service that has (or had) keys. */
    @Synchronized private fun list(ctx: Context): Map<String, JSONObject> {
        val meta = json(ctx, PREF_META)
        val at = System.currentTimeMillis() + Sync.cloudSkew
        val out = LinkedHashMap<String, JSONObject>()
        for (id in services()) {
            val m = meta.optJSONObject(id) ?: JSONObject()
            val seen = m.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val now = pool(ctx, id)
            if (now.isEmpty() && seen.isEmpty() && !m.has("added")) continue
            val rec = observe(m, seen, now, at)
            meta.put(id, JSONObject().put("added", rec.getJSONObject("added")).put("removed", rec.getJSONObject("removed")).put("seen", org.json.JSONArray(now)))
            out[id] = rec
        }
        Store.putString(ctx, PREF_META, meta.toString())
        return out
    }

    /** Takes in the other device's record for a service: merged with ours, and the pool made to match. */
    @Synchronized private fun take(ctx: Context, id: String, rec: JSONObject) {
        if (Providers.byId(id) == null) return
        val meta = json(ctx, PREF_META)
        val m = meta.optJSONObject(id) ?: JSONObject()
        val seen = m.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val at = System.currentTimeMillis() + Sync.cloudSkew
        // what changed here since last time counts first, then the other device's record
        val mine = observe(m, seen, pool(ctx, id), at)
        val merged = merge(mine, rec)
        val want = present(merged).toSet()
        val have = pool(ctx, id)
        have.filter { it !in want }.forEach { Providers.removeKey(ctx, id, it) }
        want.filter { it !in have }.forEach { Providers.addKey(ctx, id, it) }
        meta.put(id, JSONObject().put("added", merged.getJSONObject("added")).put("removed", merged.getJSONObject("removed"))
            .put("seen", org.json.JSONArray(pool(ctx, id))))
        Store.putString(ctx, PREF_META, meta.toString())
    }

    /** Sharing is off here: the other device's keys wait, unused, until it's turned on. */
    @Synchronized private fun stash(ctx: Context, id: String, rec: JSONObject) {
        val inbox = json(ctx, PREF_INBOX)
        inbox.put(id, inbox.optJSONObject(id)?.let { merge(it, rec) } ?: rec)
        Store.putString(ctx, PREF_INBOX, inbox.toString())
    }
}
