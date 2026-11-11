package com.anjy.atlas

import org.json.JSONObject

/**
 * The rules of syncing, kept apart from files and the network so they can be tested (the PC's sync.js follows the
 * very same rules: desktop/test/logic.test.mjs runs the same cases).
 *
 * Words: "sent" is what the cloud is known to hold of ours ("kind:id" → fingerprint). "dirty" is what changed here
 * and hasn't been sent yet ("kind:id" → {at: when it was changed, fp, del}). The time of the EDIT travels with a
 * record, so the newest edit wins even if it was made offline and sent hours later.
 */
object SyncPlan {
    /** Tasks and memories that are no longer here were deleted. (Chat and Log entries only when Atlas says so: see [Sync.noteDeleted].) */
    val IMPLICIT_DELETES = setOf("task", "memory")
    /** Only the newest part of these is kept in sync; older entries just fall out of view. */
    val WINDOWED = setOf("chat", "log")

    /** Does this device's unsent edit beat the one from the cloud? Newer wins; a dead heat goes to the higher device id (the cloud uses the same rule). */
    fun mineWins(myAt: Long, me: String, theirAt: Long, theirDevice: String) = myAt > theirAt || (myAt == theirAt && me > theirDevice)

    /** When a record was last edited, by its own account (0 if it doesn't say). */
    fun ownTime(json: JSONObject) = maxOf(json.optLong("updatedAt"), json.optLong("at"), json.optLong("createdAt"))

    class Local(val fp: String, val ownTime: Long)

    /**
     * Notes what changed here since the last sync, before talking to the cloud.
     * [local]: every record in the synced window. [skip]: kinds that couldn't be read just now (left exactly as they are).
     * [gone]: deletions Atlas recorded itself ("kind:id" → when). [extraImplicit]: more kinds whose absence means deleted.
     * [first]: nothing has been synced yet (a new link): records keep their own times, so an old copy here can't beat a newer one there.
     */
    fun stamp(sent: JSONObject, dirty: JSONObject, local: Map<String, Local>, gone: Map<String, Long>, now: Long, first: Boolean,
              skip: Set<String> = emptySet(), extraImplicit: Set<String> = emptySet()) {
        fun kind(k: String) = k.substringBefore(':')
        local.forEach { (k, l) ->
            if (sent.optString(k) == l.fp) { dirty.remove(k); return@forEach }
            val d = dirty.optJSONObject(k)
            if (d == null || d.optBoolean("del") || d.optString("fp") != l.fp)
                dirty.put(k, JSONObject().put("at", if (first) maxOf(1L, l.ownTime) else now).put("fp", l.fp))
        }
        gone.forEach { (k, at) ->
            if (k in local || kind(k) in skip) return@forEach
            if (sent.has(k) || (dirty.has(k) && !dirty.getJSONObject(k).optBoolean("del"))) dirty.put(k, JSONObject().put("at", at).put("del", true))
        }
        val implicit = IMPLICIT_DELETES + extraImplicit
        for (k in sent.keys().asSequence().toList()) {
            if (k in local || kind(k) in skip) continue
            val d = dirty.optJSONObject(k)
            if (d != null && d.optBoolean("del")) continue
            if (kind(k) in implicit) dirty.put(k, JSONObject().put("at", now).put("del", true))
            else sent.remove(k)                       // scrolled out of the synced window: forgotten here, not deleted there
        }
        // an edit noted earlier for a record that has since gone from here (and wasn't deleted on purpose): nothing to send
        for (k in dirty.keys().asSequence().toList()) {
            if (k in local || kind(k) in skip) continue
            if (!dirty.getJSONObject(k).optBoolean("del")) dirty.remove(k)
        }
    }
}
