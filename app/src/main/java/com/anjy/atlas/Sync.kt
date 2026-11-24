package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps Atlas on the phone and Atlas on the PC the same: reminders, memories, the Log, the conversation and your
 * name. The devices share a private link code; everything is encrypted with it on the device before it leaves, so
 * the cloud (King Anjy's own Supabase project) only ever holds scrambled text it can't read.
 *
 * How it works (the rules are in [SyncPlan]): each record's last-sent fingerprint is kept here. A sync first notes
 * what changed here and when, then pulls what the other device changed and merges it in unless this device's own
 * unsent edit is newer, then sends what changed here (or a "deleted" mark) with the time of the edit. The newest
 * edit of a record wins, whenever it gets sent. Offline, nothing is lost: changes wait, and Atlas retries by itself.
 */
object Sync {
    private const val TAG = "AtlasSync"
    const val URL_BASE = "https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc"
    /** The project's publishable key: safe to ship (the database only allows the two sync functions). */
    private const val KEY = "sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"   // no look-alikes (0/O, 1/I/L, U/V)
    private const val CHAT_KEEP = 120
    private const val LOG_KEEP = 400

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile var lastResult: String? = null; private set
    @Volatile var lastAt: Long = 0; private set
    @Volatile private var running = false
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { if (l !in listeners) listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    // ------------------------------------------------------------------ the link code

    fun code(ctx: Context): String? = Store.getString(ctx, "sync_code")?.takeIf { it.isNotBlank() }
    fun linked(ctx: Context) = code(ctx) != null

    /** A new private code, "ATLS-XXXX-XXXX-XXXX-XXXX-XXXX" (about 100 bits: unguessable). */
    fun newCode(): String {
        val r = SecureRandom()
        return "ATLS-" + (1..5).joinToString("-") { (1..4).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("") }
    }

    /** Turns typed codes into one form: capitals, no spaces, look-alike letters fixed. */
    fun normalize(code: String): String? {
        val c = code.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val body = c.removePrefix("ATLS")
        if (body.length != 20 || body.any { it !in ALPHABET }) return null
        return "ATLS-" + body.chunked(4).joinToString("-")
    }

    /** Starts syncing with a code: a new one (this device starts the link) or one typed from the other device. */
    fun link(ctx: Context, code: String): Boolean {
        val c = normalize(code) ?: return false
        Store.putString(ctx, "sync_code", c)
        File(ctx.filesDir, "sync-state.json").delete()   // fresh start: everything here is new to the cloud
        goneFile(ctx).delete()
        problem = null; others = emptyList(); loadedFor = null
        SyncJob.schedule(ctx)
        syncSoon(ctx, 0)
        return true
    }

    fun unlink(ctx: Context) {
        Store.putString(ctx, "sync_code", ""); File(ctx.filesDir, "sync-state.json").delete(); lastResult = null; SyncJob.cancel(ctx)
        problem = null; others = emptyList(); pendingCount = 0; tooBigCount = 0; parkedCount = 0; loadedFor = null
    }

    internal fun owner(code: String) = hex(sha("atlas-owner:$code"))
    private fun key(code: String) = SecretKeySpec(sha("atlas-key:$code"), "AES")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** AES-256-GCM: base64(iv · ciphertext · tag). The same format Atlas on the PC reads (WebCrypto). */
    internal fun encrypt(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return java.util.Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    internal fun decrypt(code: String, payload: String): String {
        val all = java.util.Base64.getDecoder().decode(payload)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ when to sync

    /** Asks for a sync in a few seconds (changes made close together go in one). */
    fun syncSoon(ctx: Context, delayMs: Long = 1500) {
        if (!linked(ctx)) return
        val app = ctx.applicationContext
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({ Thread { runOnce(app) }.start() }, TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }
    private val TOKEN = Any()

    /** While Atlas is open: look for the other device's changes every 20 seconds (a tiny request). */
    fun setForeground(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        main.removeCallbacksAndMessages(POLL)
        if (!on) return
        watchNetwork(app)
        val tick = object : Runnable {
            override fun run() {
                if (linked(app) && System.currentTimeMillis() - lastTry > 15_000) Thread { runOnce(app) }.start()
                main.postAtTime(this, POLL, android.os.SystemClock.uptimeMillis() + FOREGROUND_POLL_MS)
            }
        }
        main.postAtTime(tick, POLL, android.os.SystemClock.uptimeMillis() + FOREGROUND_POLL_MS)
    }
    private val POLL = Any()
    private const val FOREGROUND_POLL_MS = 20_000L
    @Volatile private var lastTry = 0L
    @Volatile private var failures = 0
    @Volatile private var watching = false

    /** When the internet comes back, send what was waiting. */
    fun watchNetwork(ctx: Context) {
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { if (failures > 0 || pendingOut) syncSoon(app, 1500) }
            })
        } catch (e: Exception) { watching = false; Log.w(TAG, "can't watch the network", e) }
    }
    /** Something changed here that the cloud doesn't have yet. */
    @Volatile var pendingOut = false; private set

    // ------------------------------------------------------------------ deletions Atlas records itself, more kinds

    private fun goneFile(ctx: Context) = File(ctx.filesDir, "sync-gone.json")
    private val goneLock = Any()

    /**
     * Chat messages (New Chat) and Log entries are only deleted on the other device when Atlas says they were
     * deleted on purpose, here. (Their absence alone proves nothing: old ones simply fall out of the synced part.)
     */
    fun noteDeleted(ctx: Context, kind: String, ids: Collection<String>) {
        if (ids.isEmpty() || !linked(ctx)) return
        synchronized(goneLock) {
            val o = try { JSONObject(goneFile(ctx).readText()) } catch (_: Exception) { JSONObject() }
            val now = System.currentTimeMillis()
            ids.forEach { o.put("$kind:$it", now) }
            writeAtomic(goneFile(ctx), o.toString())
        }
        syncSoon(ctx)
    }
    private fun readGone(ctx: Context): Map<String, Long> = synchronized(goneLock) {
        try { JSONObject(goneFile(ctx).readText()).let { o -> o.keys().asSequence().associateWith { o.optLong(it) } } } catch (_: Exception) { emptyMap() }
    }
    private fun dropGone(ctx: Context, keys: Collection<String>) = synchronized(goneLock) {
        if (keys.isEmpty()) return@synchronized
        val o = try { JSONObject(goneFile(ctx).readText()) } catch (_: Exception) { return@synchronized }
        keys.forEach { o.remove(it) }
        if (o.length() == 0) goneFile(ctx).delete() else writeAtomic(goneFile(ctx), o.toString())
    }
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /**
     * Another kind of record that syncs (say "job" for Atlas Bot's jobs, later). [list] gives every record as
     * id → JSON (null: can't be read just now, leave it be); [apply] takes in one from the other device (null: deleted).
     * Records must be plain JSON with no secrets; a field "updatedAt", "updated_ms" or "at" is read as the edit time.
     */
    class Kind(val list: (Context) -> Map<String, JSONObject>?, val apply: (Context, String, JSONObject?) -> Unit, val absentMeansDeleted: Boolean = true)
    /** Called by a kind's apply when it kept its own, newer copy ("kind:id"): that copy goes back up, so both devices end with it. */
    private val keptMine = java.util.Collections.synchronizedSet(HashSet<String>())
    fun keptMine(key: String) { keptMine.add(key) }
    private val kinds = java.util.concurrent.ConcurrentHashMap<String, Kind>()
    fun register(kind: String, k: Kind) { require(kind !in BUILT_IN); kinds[kind] = k }
    /** Kinds every Atlas of this version knows. "device" is each device saying it's there (and which Atlas it runs). */
    private val BUILT_IN = setOf("task", "memory", "log", "profile", "chat", "device")
    private fun known(kind: String) = kind in BUILT_IN || kinds.containsKey(kind)

    // ------------------------------------------------------------------ what Atlas can show about syncing

    /** How syncing stands, for Me → Your Devices. */
    class Status(val linked: Boolean, val running: Boolean, val lastAt: Long, val pending: Int, val problem: SyncPlan.Problem?,
                 val others: List<Device>, val tooBig: Int, val waitingKinds: Int)
    /** Another device on the same link, as it last said. */
    class Device(val id: String, val platform: String, val name: String, val app: String, val protocol: Int, val seenMs: Long) {
        val outdated get() = protocol < SyncPlan.PROTOCOL
    }
    @Volatile var problem: SyncPlan.Problem? = null; private set
    /** The cloud's clock minus this phone's (to say how long ago the other device was seen, in the cloud's time). */
    @Volatile var cloudSkew = 0L; private set
    @Volatile private var pendingCount = 0
    @Volatile private var others: List<Device> = emptyList()
    @Volatile private var tooBigCount = 0
    @Volatile private var parkedCount = 0
    @Volatile private var loadedFor: String? = null
    fun status(ctx: Context): Status {
        if (loadedFor == null) try {
            // after a restart: what the last sync knew
            val st = JSONObject(stateFile(ctx).readText())
            others = devicesOf(st.optJSONObject("devices"), Store.read(ctx) { it.deviceId })
            pendingCount = st.optJSONObject("dirty")?.length() ?: 0
            tooBigCount = st.optJSONObject("tooBig")?.length() ?: 0
            parkedCount = st.optJSONObject("parked")?.length() ?: 0
            if (lastAt == 0L) lastAt = st.optLong("lastAt")
            loadedFor = "x"
        } catch (_: Exception) {}
        return Status(linked(ctx), running, lastAt, pendingCount, problem, others, tooBigCount, parkedCount)
    }
    private fun devicesOf(o: JSONObject?, me: String): List<Device> = o?.keys()?.asSequence()?.filter { it != me }?.mapNotNull { id ->
        o.optJSONObject(id)?.let { j -> Device(id, j.optString("platform"), j.optString("name"), j.optString("app"), j.optInt("proto", 2), j.optLong("said")) }
    }?.sortedByDescending { it.seenMs }?.toList() ?: emptyList()

    // ------------------------------------------------------------------ the sync itself

    private class Rec(val kind: String, val id: String, val json: JSONObject)

    /** Everything that syncs, as records; and the kinds that couldn't be read just now (those are left untouched). */
    private fun records(ctx: Context, presence: JSONObject? = null): Pair<List<Rec>, Set<String>> {
        val all = JSONObject(Store.exportJson(ctx))
        val out = mutableListOf<Rec>(); val skip = mutableSetOf<String>()
        all.optJSONArray("tasks")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { out.add(Rec("task", it.getString("id"), it)) } }
        all.optJSONArray("memories")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { out.add(Rec("memory", it.getString("id"), it)) } }
        all.optJSONArray("log")?.let { a -> val n = a.length(); for (i in maxOf(0, n - LOG_KEEP) until n) a.getJSONObject(i).let { out.add(Rec("log", it.getString("id"), it)) } }
        // your name and how reminders behave (a name that isn't set here is left out: it must never blank the other device's)
        all.optJSONObject("settings")?.let { SyncPlan.profileOf(it) }?.takeUnless { SyncPlan.untouchedProfile(it) }?.let { out.add(Rec("profile", "me", it)) }
        presence?.let { out.add(Rec("device", Store.read(ctx) { s -> s.deviceId }, it)) }
        val chat = Conversation.exportForSync(ctx)
        if (chat == null) skip.add("chat") else chat.takeLast(CHAT_KEEP).forEach { out.add(Rec("chat", it.getString("id"), it)) }
        kinds.forEach { (kind, k) ->
            val m = try { k.list(ctx) } catch (e: Exception) { Log.w(TAG, "$kind unreadable", e); null }
            if (m == null) skip.add(kind) else m.forEach { (id, j) -> out.add(Rec(kind, id, j)) }
        }
        return out to skip
    }

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "sync-state.json")
    private fun ownTime(j: JSONObject) = maxOf(SyncPlan.ownTime(j), j.optLong("updated_ms"))

    /** The cloud's answer when it isn't a success (status 0: no answer at all: offline, timed out). */
    class CloudError(val status: Int, val body: String, cause: Throwable? = null) : Exception("the cloud answered $status ${body.take(160)}", cause)

    @Synchronized
    fun runOnce(ctx: Context): String {
        val code = code(ctx) ?: return "Not linked."
        // skills, routines, the bot team and approval rules sync too: their kinds must be known before anything is pulled
        try { BotExtras.init(ctx) } catch (e: Exception) { Log.w(TAG, "bot extras", e) }
        KeyShare.register()                      // "Share My AI Keys" (does nothing unless it's on)
        BotMirror.register()                     // each device's bot jobs, shown (read-only) on the other
        Trading.registerSync()                   // the trading stop switch: a stop on either device stops both
        if (running) return "Already syncing."
        running = true
        lastTry = System.currentTimeMillis()
        val result = try {
            val st = try { JSONObject(stateFile(ctx).readText()) } catch (_: Exception) { JSONObject() }
            var first = !st.has("cursor")
            var cursor = st.optLong("cursor", 0)
            var recent = (st.optJSONArray("recent") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optLong(0) to it.optLong(1) } } }
            var seen = st.optJSONObject("seen") ?: JSONObject()       // "kind:id" -> number it was taken in at (the overlap pulls it again)
            var parked = st.optJSONObject("parked") ?: JSONObject()   // records this Atlas can't take in yet (a newer kind, or it failed): kept, tried again
            var sent = st.optJSONObject("sent") ?: JSONObject()     // "kind:id" -> fingerprint of what the cloud has of ours
            var dirty = st.optJSONObject("dirty") ?: JSONObject()   // "kind:id" -> {at, fp, del}: changed here, not sent yet
            val devices = st.optJSONObject("devices") ?: JSONObject()  // id -> what each device last said about itself
            val tooBig = st.optJSONObject("tooBig") ?: JSONObject()    // records too big for the cloud: not sent (and said so)
            val me = Store.read(ctx) { it.deviceId }
            // This device's data was started afresh since the last sync (its file couldn't be read and a new one was
            // made): what's missing here wasn't deleted, it was lost. Take everything back from the cloud rather than
            // telling the other device to delete it all.
            if (freshStart(st.optString("device"), me)) {
                Log.w(TAG, "local data was re-created: restoring from the cloud")
                cursor = 0; sent = JSONObject(); dirty = JSONObject(); first = true; recent = emptyList(); seen = JSONObject(); parked = JSONObject()
            }
            val ownerId = owner(code)
            // how far this phone's clock is from the cloud's: edit times are kept in cloud time, so a phone whose clock
            // runs fast can't win every disagreement. Learnt from each send, and asked afresh when the clock was changed.
            var skew = st.optLong("skew", 0)
            val base = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            if (!st.has("skew") || Math.abs(base - st.optLong("base", base)) > 5_000) {
                skew = post("sync_time", JSONObject()).trim().toLong() - System.currentTimeMillis()
            }
            cloudSkew = skew
            var said = st.optLong("said", 0)
            fun saveState() {
                val rec = JSONArray(); recent.forEach { (t, r) -> rec.put(JSONArray().put(t).put(r)) }
                writeAtomic(stateFile(ctx), JSONObject().put("v", 3).put("cursor", cursor).put("recent", rec).put("seen", seen).put("parked", parked)
                    .put("sent", sent).put("dirty", dirty).put("device", me).put("skew", skew).put("base", base).put("said", said)
                    .put("devices", devices).put("tooBig", tooBig).put("lastAt", lastAt).toString())
            }

            // 1. note what changed here, with the time it changed, before anything from the cloud is taken in
            val now = System.currentTimeMillis() + skew
            if (SyncPlan.presenceDue(said, now)) said = now
            val presence = JSONObject().put("platform", "android").put("name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
                .put("app", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
                .put("proto", SyncPlan.PROTOCOL).put("said", said)
            val (recs, skip) = records(ctx, presence)
            val byKey = recs.associateBy { "${it.kind}:${it.id}" }
            val gone = readGone(ctx)
            // deletions were noted by this phone's clock: in cloud time, like every other edit (a slow clock's New Chat must not lose to the message)
            val goneAt = gone.mapValues { minOf(now, it.value + skew) }
            SyncPlan.stamp(sent, dirty, byKey.mapValues { SyncPlan.Local(fp(it.value.json), ownTime(it.value.json)) }, goneAt, now, first, skip,
                kinds.filterValues { it.absentMeansDeleted }.keys)
            devices.put(me, presence)
            if (!first) saveState()           // an edit made offline keeps the time it was noticed, however long sending takes
            pendingOut = dirty.length() > 0

            // 2. take in what the other device changed, unless this device's own unsent edit is newer
            var got = 0
            var blocked = false
            /** Takes in rows from the cloud (pulled, or parked earlier). [replay]: parked rows, already counted as seen. */
            fun takeIn(rows: List<JSONObject>, replay: Boolean) {
                val changes = mutableListOf<Triple<String, String, JSONObject?>>()
                val revs = HashMap<String, Long>(); val rowOf = HashMap<String, JSONObject>()
                for (r in rows) {
                    val kind = r.getString("kind"); val id = r.getString("id"); val k = "$kind:$id"; val rev = r.optLong("rev")
                    if (!replay && SyncPlan.alreadyTaken(seen.optLong(k, -1), rev)) continue
                    if (!known(kind) || kind in skip) {
                        if (kind in skip) { blocked = true; break }        // can't merge this kind right now: stop here, take it next time
                        // a kind this version doesn't know (made by a newer Atlas): kept, and taken in once Atlas knows it
                        val old = parked.optJSONObject(k)
                        if (old == null || old.optLong("rev") <= rev) parked.put(k, r)
                        seen.put(k, rev); continue
                    }
                    if (!replay) parked.remove(k)                            // a newer copy came: the parked one is old news
                    val del = r.optBoolean("deleted"); val from = r.optString("device")
                    val plain = if (del) null else try { JSONObject(decrypt(code, r.getString("payload"))) } catch (e: Exception) { Log.w(TAG, "a record couldn't be read (another code?)"); seen.put(k, rev); continue }
                    seen.put(k, rev)
                    if (kind == "device") { if (id != me) { if (plain == null) devices.remove(id) else devices.put(id, plain) }; if (!del) sent.put(k, fp(plain!!)); continue }
                    val d0 = dirty.optJSONObject(k)
                    // the cloud already holds exactly what's here (a re-link, or our own record coming back): nothing to send or take
                    if (!del && d0 != null && !d0.optBoolean("del") && d0.optString("fp") == fp(plain!!)) { sent.put(k, d0.optString("fp")); dirty.remove(k); continue }
                    if (from == me) { if (d0 == null) { if (del) sent.remove(k) else sent.put(k, fp(plain!!)) }; continue }   // our own, coming back
                    val d = dirty.optJSONObject(k)
                    if (d != null && SyncPlan.mineWins(d.optLong("at"), me, r.optLong("updated_ms"), from)) continue   // ours is newer: it goes up below
                    dirty.remove(k)
                    if (del) sent.remove(k) else sent.put(k, fp(plain!!))
                    changes.add(Triple(kind, id, plain)); revs[k] = rev; rowOf[k] = r
                }
                if (changes.isNotEmpty()) {
                    keptMine.clear()
                    val failed = apply(ctx, changes)
                    // a kind kept its own copy (newer by its edit time): not taken in, so send ours back rather than drift apart
                    val kept = synchronized(keptMine) { keptMine.toSet().also { keptMine.clear() } }
                    kept.forEach { k -> sent.remove(k); byKey[k]?.let { dirty.put(k, JSONObject().put("at", now).put("fp", fp(it.json))) } }
                    // a record of a known kind that couldn't be taken in just now: kept and tried again next time, not lost
                    failed.forEach { k -> rowOf[k]?.let { parked.put(k, it.put("retry", true)) }; sent.remove(k) }
                    got += changes.size - failed.size
                    // this device may write a record its own way (field order, defaults): that is not an edit to send back
                    val mine = records(ctx, presence).first.associateBy { "${it.kind}:${it.id}" }
                    changes.forEach { (kind, id, rec) -> val k = "$kind:$id"; if (rec != null && k !in failed && k !in kept) mine[k]?.let {
                        // the other device has no name set and this one has: that's news for it, not a way of writing the record (it goes up)
                        if (kind == "profile" && it.json.has("name") && !rec.has("name")) return@let
                        sent.put(k, fp(it.json))
                    } }
                }
            }
            // parked records this Atlas can take in now (it learnt the kind, or a merge that failed may work now)
            val ready = parked.keys().asSequence().toList().filter { k -> known(k.substringBefore(':')) && k.substringBefore(':') !in skip }
            if (ready.isNotEmpty()) {
                val rows = ready.mapNotNull { parked.optJSONObject(it) }.sortedBy { it.optLong("rev") }
                ready.forEach { parked.remove(it) }
                takeIn(rows, replay = true)
            }
            var pos = cursor; var top = cursor
            while (!blocked) {
                val page = JSONArray(post("sync_pull", JSONObject().put("p_owner", ownerId).put("p_since", pos).put("p_limit", 500)))
                if (page.length() == 0) break
                val rows = (0 until page.length()).map { page.getJSONObject(it) }
                takeIn(rows, replay = false)
                if (blocked) break
                pos = rows.maxOf { it.getLong("rev") }; top = maxOf(top, pos)
                saveState()
                if (page.length() < 500) break
            }
            // where to pull from next time: a little behind the newest position, in case two sends were numbered out of order
            if (!blocked) {
                val wall = System.currentTimeMillis()
                val (c, rest) = SyncPlan.advance(cursor, recent + (wall to top), wall)
                cursor = c; recent = rest
                for (k in seen.keys().asSequence().toList()) if (seen.optLong(k) <= cursor) seen.remove(k)
            }
            // never keep more than a few thousand parked records (the oldest go)
            if (parked.length() > 3000) parked.keys().asSequence().toList().sortedBy { parked.optJSONObject(it)?.optLong("rev") ?: 0 }.take(parked.length() - 3000).forEach { parked.remove(it) }

            // 3. send what changed here, each with the time of its edit, in sends the cloud accepts
            val items = mutableListOf<Pair<String, JSONObject>>()
            for (k in dirty.keys().asSequence().toList()) {
                val d = dirty.getJSONObject(k); val kind = k.substringBefore(':'); val id = k.substringAfter(':')
                if (d.optBoolean("del")) { items.add(k to JSONObject().put("kind", kind).put("id", id).put("updated_ms", d.optLong("at")).put("deleted", true).put("payload", "").put("device", me)); continue }
                val r = byKey[k] ?: continue
                var payload = encrypt(code, r.json.toString())
                if (payload.length > SyncPlan.MAX_RECORD && kind == "chat") {
                    // a huge message: the other device gets it cut short (the whole of it stays here)
                    val cut = JSONObject(r.json.toString()); cut.put("text", cut.optString("text").take(600_000) + "\n\n… (cut short: too long to sync)")
                    payload = encrypt(code, cut.toString())
                }
                if (payload.length > SyncPlan.MAX_RECORD) {
                    // too big for the cloud: not sent (and not tried again until it changes); Me → Your Devices says so
                    Log.w(TAG, "a $kind record is too big to sync"); tooBig.put(k, d.optString("fp")); sent.put(k, d.optString("fp")); dirty.remove(k); continue
                }
                tooBig.remove(k)
                items.add(k to JSONObject().put("kind", kind).put("id", id).put("updated_ms", d.optLong("at")).put("payload", payload).put("device", me))
            }
            var put = 0
            var again = false
            for (batch in SyncPlan.batches(items.map { it.second.optString("payload").length + 200 })) {
                val arr = JSONArray(); batch.forEach { arr.put(items[it].second) }
                val res = JSONObject(post("sync_push2", JSONObject().put("p_owner", ownerId).put("p_items", arr)))
                res.optLong("now").takeIf { it > 0 }?.let { skew = it - System.currentTimeMillis(); cloudSkew = skew }
                val rejected = HashSet<String>()
                res.optJSONArray("rejected")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { rejected.add(it.optString("kind") + ":" + it.optString("id")) } }
                batch.forEach { i ->
                    val k = items[i].first; val d = dirty.optJSONObject(k) ?: return@forEach
                    // the cloud already had a newer edit (made on the other device a moment ago): ours is dropped, theirs comes in next
                    if (k in rejected) { dirty.remove(k); again = true; return@forEach }
                    if (d.optBoolean("del")) sent.remove(k) else sent.put(k, d.optString("fp")); dirty.remove(k)
                }
                put += arr.length()
                saveState()
            }
            for (k in tooBig.keys().asSequence().toList()) if (!byKey.containsKey(k)) tooBig.remove(k)
            lastAt = System.currentTimeMillis()
            saveState()
            dropGone(ctx, gone.keys.filter { !dirty.has(it) && it.substringBefore(':') !in skip })
            pendingOut = dirty.length() > 0; pendingCount = dirty.length()
            others = devicesOf(devices, me); tooBigCount = tooBig.length(); parkedCount = parked.length(); loadedFor = "x"
            failures = 0; problem = null
            if (again) syncSoon(ctx, 1500)
            if (put > 0) try { Link.pingSync() } catch (_: Exception) {}   // the other device fetches them now (when the live link is up)
            if (got > 0) main.post { Conversation.reloadFromSync(ctx); Thread { try { Reminders.tickAndSchedule(ctx) } catch (_: Exception) {} }.start() }
            "Synced" + if (got + put > 0) " ($got in, $put out)" else ""
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            val p = when (e) {
                is CloudError -> SyncPlan.problem(e.status, e.body)
                is java.io.IOException -> SyncPlan.problem(0, "")
                else -> SyncPlan.Problem("other", "Couldn't sync: " + (e.message ?: e.javaClass.simpleName), null)
            }
            problem = p
            // try again by itself: after 30 s, then 2, 5 and 15 minutes (and at once when the internet comes back);
            // a sleeping cloud isn't asked again so often (it won't wake by itself)
            failures++
            val waits = if (p.kind == "paused") longArrayOf(900_000, 1_800_000, 3_600_000, 3_600_000) else longArrayOf(30_000, 120_000, 300_000, 900_000)
            main.removeCallbacksAndMessages(TOKEN)
            main.postAtTime({ Thread { runOnce(ctx.applicationContext) }.start() }, TOKEN, android.os.SystemClock.uptimeMillis() + waits[minOf(failures, 4) - 1])
            if (p.kind == "offline") "Couldn't sync: offline" else p.text
        } finally { running = false }
        lastResult = result
        main.post { synchronized(listeners) { listeners.toList() }.forEach { try { it() } catch (_: Exception) {} } }
        return result
    }

    /**
     * One device at a time: asks the cloud to hold [id] (say an Atlas Bot job) for this device for [leaseMs]
     * (at most an hour; 0 lets it go). Atomic in the cloud, so two devices asking at once can't both get it.
     * Returns who holds it now (null: nobody), or null on failure (offline, not linked). Network: not on the main thread.
     */
    class Claim(val holder: String?, val mine: Boolean, val untilMs: Long)
    fun claim(ctx: Context, id: String, leaseMs: Long): Claim? {
        val code = code(ctx) ?: return null
        val me = Store.read(ctx) { it.deviceId }
        return try {
            val r = JSONObject(post("sync_claim", JSONObject().put("p_owner", owner(code)).put("p_id", id).put("p_device", me).put("p_lease_ms", leaseMs)))
            Claim(r.optString("holder").takeIf { !r.isNull("holder") && it.isNotEmpty() }, r.optBoolean("mine"), r.optLong("until_ms"))
        } catch (e: Exception) { Log.w(TAG, "claim failed", e); null }
    }

    /** The data here has a different device id from the one the last sync saw: it was made anew since. */
    internal fun freshStart(lastDevice: String, me: String) = lastDevice.isNotEmpty() && lastDevice != me

    /** A record's fingerprint, the same whatever order its fields are in (the PC writes them in its own order). */
    private fun fp(o: JSONObject) = hex(MessageDigest.getInstance("SHA-1").digest(canonical(o).toByteArray(Charsets.UTF_8)))
    internal fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> quote(k) + ":" + canonical(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> quote(v)
        null, JSONObject.NULL -> "null"
        is Int, is Long, is Short, is Byte -> v.toString()
        is Number -> v.toDouble().let { d -> if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) d.toLong().toString() else d.toString() }
        else -> v.toString()
    }
    /** A string written exactly as JavaScript's JSON.stringify writes it (org.json escapes more), so fingerprints match the PC's. */
    internal fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\b' -> b.append("\\b")
                c == '\u000C' -> b.append("\\f")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { b.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> b.append("\\u%04x".format(c.code))   // a lone half of a pair
                else -> b.append(c)
            }
            i++
        }
        return b.append('"').toString()
    }

    /** Merges records from the other device into this one (and waits until they're in). Returns the ones that couldn't be taken in. */
    private fun apply(ctx: Context, changes: List<Triple<String, String, JSONObject?>>): Set<String> {
        val failed = HashSet<String>()
        val chat = changes.filter { it.first == "chat" }
        if (chat.isNotEmpty()) {
            val done = java.util.concurrent.CountDownLatch(1)
            main.post { try { Conversation.mergeFromSync(ctx, chat.map { it.second to it.third }) } catch (e: Exception) { Log.w(TAG, "chat merge", e) } finally { done.countDown() } }
            // not taken in (the app is stuck): stop here, so these records are pulled again next time rather than lost
            if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("the chat was busy")
        }
        val extra = changes.filter { it.first in kinds.keys }
        extra.forEach { (kind, id, rec) -> try { kinds[kind]?.apply?.invoke(ctx, id, rec) } catch (e: Exception) { Log.w(TAG, "$kind merge", e); failed.add("$kind:$id") } }
        val rest = changes.filter { it.first != "chat" && it.first !in kinds.keys }
        if (rest.isNotEmpty()) Store.merge(ctx, rest)
        ModelManager.markStale()
        return failed
    }

    private fun post(fn: String, body: JSONObject): String {
        val con = URL("$URL_BASE/$fn").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 12_000; con.readTimeout = 30_000
            con.setRequestProperty("apikey", KEY); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw CloudError(code, text)
            return text
        } finally { con.disconnect() }
    }
}
