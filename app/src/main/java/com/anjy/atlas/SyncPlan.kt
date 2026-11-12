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

    // ------------------------------------------------------------------ the pull position

    /**
     * Two devices sending at the same moment can have their records numbered out of order: a record numbered 100 may
     * become visible just after one numbered 101 was already pulled. So the position kept is the newest one that is at
     * least this old; each pull looks back over the last few seconds again (records already taken in are skipped).
     */
    const val OVERLAP_MS = 30_000L

    /**
     * Where to pull from next time. [recent]: pulls made lately as (when, highest number seen). Positions older than
     * [OVERLAP_MS] become the kept [cursor]; a position "from the future" (the clock was moved back) is kept as well.
     */
    fun advance(cursor: Long, recent: List<Pair<Long, Long>>, now: Long): Pair<Long, List<Pair<Long, Long>>> {
        var c = cursor
        val keep = mutableListOf<Pair<Long, Long>>()
        for ((t, rev) in recent) {
            if (t <= now - OVERLAP_MS || t > now + 60_000) c = maxOf(c, rev) else keep.add(t to rev)
        }
        return c to keep.takeLast(20)
    }

    /** A record already taken in at this number or later (the overlap pulls it again): skip it. */
    fun alreadyTaken(seenRev: Long, rev: Long) = rev > 0 && seenRev >= rev

    // ------------------------------------------------------------------ sending

    /** The cloud refuses a single record longer than 1,500,000 characters; a little room is kept. */
    const val MAX_RECORD = 1_400_000
    const val MAX_BATCH = 400
    const val MAX_BATCH_CHARS = 1_800_000

    /** Splits records of these sizes (encrypted length) into sends: at most [MAX_BATCH] records and about [MAX_BATCH_CHARS] characters each. */
    fun batches(sizes: List<Int>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>(); var chars = 0L
        sizes.forEachIndexed { i, n ->
            if (cur.isNotEmpty() && (cur.size >= MAX_BATCH || chars + n > MAX_BATCH_CHARS)) { out.add(cur); cur = mutableListOf(); chars = 0 }
            cur.add(i); chars += n
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    // ------------------------------------------------------------------ problems, in plain words

    class Problem(val kind: String, val text: String, val fix: String?)

    /** What went wrong, from the cloud's answer (status 0: no answer at all). */
    fun problem(status: Int, body: String): Problem {
        val b = body.lowercase()
        return when {
            status == 0 -> Problem("offline", "Offline. Changes wait here and go up as soon as you're back online.", null)
            status == 540 || "paused" in b || ("restore" in b && "project" in b) -> Problem("paused",
                "The sync cloud is asleep: Supabase pauses free projects after a week without use.",
                "Open supabase.com, sign in, open the project “Atlas” and choose Restore project. Then tap Sync Now.")
            status == 401 || status == 403 -> Problem("refused", "The sync cloud turned Atlas away.", "Update Atlas on both devices.")
            status == 404 || "could not find the function" in b -> Problem("outdated",
                "The sync cloud doesn't have something this version of Atlas needs.", "Update Atlas on both devices.")
            status in 500..599 -> Problem("cloud", "The sync cloud is having trouble right now. Atlas keeps trying by itself.", null)
            else -> Problem("other", "Couldn't sync (the cloud answered $status).", null)
        }
    }

    // ------------------------------------------------------------------ settings that are the same on both devices

    /**
     * What is the same on both devices: your name and how reminders behave. The look, speaking aloud, the floating
     * orb and Hey Atlas stay each device's own. A name that isn't set is left out, so it can never blank the other's.
     */
    fun profileOf(settings: JSONObject): JSONObject {
        val o = JSONObject()
        settings.optString("name").trim().takeIf { it.isNotEmpty() }?.let { o.put("name", it) }
        o.put("tone", settings.optString("tone", "firm")); o.put("quietStart", settings.optString("quietStart", "22:30"))
        o.put("quietEnd", settings.optString("quietEnd", "06:30")); o.put("graceMin", settings.optInt("graceMin", 15))
        o.put("followUps", settings.optInt("followUps", 1))
        return o
    }

    /** The fields of a profile from the other device that may be taken in (an older Atlas sends only the name). */
    fun profileFields(rec: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        fun str(k: String) = rec.opt(k) as? String
        fun int(k: String) = when (val v = rec.opt(k)) { is Int -> v; is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(); else -> null }
        str("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }?.let { out["name"] = it }
        str("tone")?.takeIf { it.isNotBlank() && it.length <= 20 }?.let { out["tone"] = it }
        val hm = Regex("^\\d{1,2}:\\d{2}$")
        str("quietStart")?.takeIf { hm.matches(it) }?.let { out["quietStart"] = it }
        str("quietEnd")?.takeIf { hm.matches(it) }?.let { out["quietEnd"] = it }
        int("graceMin")?.takeIf { it in 0..240 }?.let { out["graceMin"] = it }
        int("followUps")?.takeIf { it in 0..10 }?.let { out["followUps"] = it }
        return out
    }

    // ------------------------------------------------------------------ the devices

    /** The sync rules this version follows; a device on a lower number is told to update. */
    const val PROTOCOL = 3
    /** How often a device says it's still here (not on every sync: that would send a record every time). */
    const val PRESENCE_EVERY_MS = 10 * 60_000L
    fun presenceDue(lastSaid: Long, now: Long) = lastSaid <= 0 || now - lastSaid >= PRESENCE_EVERY_MS || now < lastSaid
}

