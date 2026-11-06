package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
 * How it works: each record's last-sent fingerprint is kept here. A sync pulls what the other device changed since
 * last time and merges it in, then sends whatever changed here (or a "deleted" mark). The newest edit of a record
 * wins. Offline, nothing is lost: changes wait until the next sync.
 */
object Sync {
    private const val TAG = "AtlasSync"
    const val URL_BASE = "https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc"
    /** The project's publishable key: safe to ship (the database only allows the two sync functions). */
    private const val KEY = "sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"   // no look-alikes (0/O, 1/I/L, U/V)
    private const val CHAT_KEEP = 120
    private const val LOG_KEEP = 400

    private val main = Handler(Looper.getMainLooper())
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
        syncSoon(ctx, 0)
        return true
    }

    fun unlink(ctx: Context) { Store.putString(ctx, "sync_code", ""); File(ctx.filesDir, "sync-state.json").delete(); lastResult = null }

    private fun owner(code: String) = hex(sha("atlas-owner:$code"))
    private fun key(code: String) = SecretKeySpec(sha("atlas-key:$code"), "AES")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** AES-256-GCM: base64(iv · ciphertext · tag). The same format Atlas on the PC reads (WebCrypto). */
    internal fun encrypt(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    internal fun decrypt(code: String, payload: String): String {
        val all = Base64.decode(payload, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ when to sync

    /** Asks for a sync in a few seconds (changes made close together go in one). */
    fun syncSoon(ctx: Context, delayMs: Long = 4000) {
        if (!linked(ctx)) return
        val app = ctx.applicationContext
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({ Thread { runOnce(app) }.start() }, TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }
    private val TOKEN = Any()

    // ------------------------------------------------------------------ the sync itself

    private class Rec(val kind: String, val id: String, val json: JSONObject)

    /** Everything that syncs, as records. */
    private fun records(ctx: Context): List<Rec> {
        val all = JSONObject(Store.exportJson(ctx))
        val out = mutableListOf<Rec>()
        all.optJSONArray("tasks")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { out.add(Rec("task", it.getString("id"), it)) } }
        all.optJSONArray("memories")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { out.add(Rec("memory", it.getString("id"), it)) } }
        all.optJSONArray("log")?.let { a -> val n = a.length(); for (i in maxOf(0, n - LOG_KEEP) until n) a.getJSONObject(i).let { out.add(Rec("log", it.getString("id"), it)) } }
        all.optJSONObject("settings")?.let { st -> out.add(Rec("profile", "me", JSONObject().put("name", st.optString("name")))) }
        Conversation.exportForSync(ctx).takeLast(CHAT_KEEP).forEach { out.add(Rec("chat", it.getString("id"), it)) }
        return out
    }

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "sync-state.json")

    @Synchronized
    fun runOnce(ctx: Context): String {
        val code = code(ctx) ?: return "Not linked."
        if (running) return "Already syncing."
        running = true
        val result = try {
            val st = try { JSONObject(stateFile(ctx).readText()) } catch (_: Exception) { JSONObject() }
            var cursor = st.optLong("cursor", 0)
            var sent = st.optJSONObject("sent") ?: JSONObject()     // "kind:id" -> fingerprint of what the cloud has
            val me = Store.read(ctx) { it.deviceId }
            // This device's data was started afresh since the last sync (its file couldn't be read and a new one was
            // made): what's missing here wasn't deleted, it was lost. Take everything back from the cloud rather than
            // telling the other device to delete it all.
            if (freshStart(st.optString("device"), me)) { Log.w(TAG, "local data was re-created: restoring from the cloud"); cursor = 0; sent = JSONObject() }
            val ownerId = owner(code)

            // 1. take in what the other device changed
            var got = 0
            while (true) {
                val page = JSONArray(post("sync_pull", JSONObject().put("p_owner", ownerId).put("p_since", cursor).put("p_limit", 500)))
                if (page.length() == 0) break
                val changes = mutableListOf<Triple<String, String, JSONObject?>>()
                for (i in 0 until page.length()) {
                    val r = page.getJSONObject(i)
                    cursor = maxOf(cursor, r.getLong("rev"))
                    val kind = r.getString("kind"); val id = r.getString("id"); val k = "$kind:$id"
                    if (r.optBoolean("deleted")) { if (r.optString("device") != me) changes.add(Triple(kind, id, null)); sent.remove(k); continue }
                    val plain = try { decrypt(code, r.getString("payload")) } catch (e: Exception) { Log.w(TAG, "a record couldn't be read (another code?)"); continue }
                    sent.put(k, fp(JSONObject(plain)))
                    if (r.optString("device") != me) changes.add(Triple(kind, id, JSONObject(plain)))
                }
                if (changes.isNotEmpty()) { apply(ctx, changes); got += changes.size }
                if (page.length() < 500) break
            }

            // 2. send what changed here
            val now = System.currentTimeMillis()
            val items = JSONArray()
            val present = HashSet<String>()
            records(ctx).forEach { r ->
                val k = "${r.kind}:${r.id}"; present.add(k)
                val plain = r.json.toString(); val f = fp(r.json)
                if (sent.optString(k) != f) {
                    items.put(JSONObject().put("kind", r.kind).put("id", r.id).put("updated_ms", now).put("payload", encrypt(code, plain)).put("device", me))
                    sent.put(k, f)
                }
            }
            // records that were here before and aren't any more: deleted
            val gone = sent.keys().asSequence().filter { it !in present && !it.startsWith("chat:") && !it.startsWith("log:") }.toList()
            gone.forEach { k ->
                val (kind, id) = k.split(":", limit = 2).let { it[0] to it[1] }
                items.put(JSONObject().put("kind", kind).put("id", id).put("updated_ms", now).put("deleted", true).put("payload", "").put("device", me))
                sent.remove(k)
            }
            var put = 0
            for (chunk in (0 until items.length()).chunked(400)) {
                val arr = JSONArray(); chunk.forEach { arr.put(items.get(it)) }
                post("sync_push", JSONObject().put("p_owner", ownerId).put("p_items", arr)); put += arr.length()
            }
            stateFile(ctx).writeText(JSONObject().put("cursor", cursor).put("sent", sent).put("device", me).toString())
            lastAt = System.currentTimeMillis()
            if (got > 0) main.post { Conversation.reloadFromSync(ctx); Thread { try { Reminders.tickAndSchedule(ctx) } catch (_: Exception) {} }.start() }
            "Synced" + if (got + put > 0) " ($got in, $put out)" else ""
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            "Couldn't sync: " + (if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException) "offline" else (e.message ?: e.javaClass.simpleName))
        } finally { running = false }
        lastResult = result
        main.post { synchronized(listeners) { listeners.toList() }.forEach { try { it() } catch (_: Exception) {} } }
        return result
    }

    /** The data here has a different device id from the one the last sync saw: it was made anew since. */
    internal fun freshStart(lastDevice: String, me: String) = lastDevice.isNotEmpty() && lastDevice != me

    /** A record's fingerprint, the same whatever order its fields are in (the PC writes them in its own order). */
    private fun fp(o: JSONObject) = hex(MessageDigest.getInstance("SHA-1").digest(canonical(o).toByteArray(Charsets.UTF_8)))
    internal fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite() && Math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** Merges records from the other device into this one. */
    private fun apply(ctx: Context, changes: List<Triple<String, String, JSONObject?>>) {
        val chat = changes.filter { it.first == "chat" }
        if (chat.isNotEmpty()) main.post { Conversation.mergeFromSync(ctx, chat.map { it.second to it.third }) }
        val rest = changes.filter { it.first != "chat" }
        if (rest.isNotEmpty()) Store.merge(ctx, rest)
        ModelManager.markStale()
    }

    private fun post(fn: String, body: JSONObject): String {
        val con = URL("$URL_BASE/$fn").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 12_000; con.readTimeout = 30_000
            con.setRequestProperty("apikey", KEY); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IllegalStateException("the cloud answered $code ${text.take(160)}")
            return text
        } finally { con.disconnect() }
    }
}
