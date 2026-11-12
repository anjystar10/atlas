package com.anjy.atlas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Device Link: the live line between this phone and the user's PC (the same protocol as desktop/renderer/linkcore.js).
 *
 * Messages are end-to-end encrypted with the private link code (Sync's AES-256-GCM key) before they leave; the cloud
 * relay (link_* functions in the Atlas Supabase project) only sees ciphertext, the owner hash and device ids. Messages
 * wait there ten minutes at most; screen frames keep only the newest.
 *
 * Kinds: task / task_ack / task_update / task_approval / task_decide / task_done (a job run on the other device),
 * screen_start / screen_reply / screen_keep / screen_stop / input / input_error (remote control), ping / pong.
 *
 * When it listens: continuously (a long poll) while Atlas is open or something live is going on (a remote screen,
 * a task waiting); with "Reachable from your laptop" on, LinkService keeps it listening with Atlas closed, every few
 * seconds while the screen is on and every 30 s while it's off. Otherwise it doesn't listen at all (battery).
 */
object Link {
    private const val TAG = "AtlasLink"
    private const val URL_BASE = "https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc"
    private const val KEY = "sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj"

    class Peer(val device: String, val name: String, val platform: String, val agoMs: Long, val info: JSONObject) {
        val online get() = agoMs < LinkCore.ONLINE_MS
    }

    lateinit var app: Context; private set
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-link-send").apply { isDaemon = true } }
    @Volatile var peers: List<Peer> = emptyList(); private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var thread: Thread? = null
    @Volatile private var cursor = -1L
    @Volatile private var lastBeat = 0L
    @Volatile private var foreground = false
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val waiters = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var codeInUse: String? = null

    fun listen(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    internal fun changed() = main.post { listeners.forEach { try { it() } catch (_: Exception) {} } }

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }
    fun linked(ctx: Context) = Sync.linked(ctx)
    fun me(): String = Store.read(app) { it.deviceId }
    fun laptop(): Peer? = peers.firstOrNull { it.platform == "windows" } ?: peers.firstOrNull { it.platform != "android" }

    /** "Reachable from your laptop": keep listening with Atlas closed (LinkService). */
    fun reachable(ctx: Context) = Store.getFlag(ctx, "link_reachable", false)
    fun setReachable(ctx: Context, on: Boolean) { Store.setFlag(ctx, "link_reachable", on); if (on) LinkService.start(ctx) else LinkService.stopIfIdle(ctx); changed() }
    fun phoneName(ctx: Context): String = Store.getString(ctx, "link_name")?.takeIf { it.isNotBlank() } ?: (android.os.Build.MODEL ?: "Phone")

    /** Atlas came on screen (or left it). */
    fun setForeground(ctx: Context, on: Boolean) { init(ctx); foreground = on; if (on) { lastBeat = 0; ensure() } }
    /** Something live is going on: listen continuously until it's over. */
    fun setActive(on: Boolean) { if (on) active.incrementAndGet() else if (active.decrementAndGet() < 0) active.set(0); if (on) ensure() }
    private fun wanted() = Sync.linked(app) && (foreground || active.get() > 0 || reachable(app) || Remote.controlled())

    /** Starts the listening loop if it should run and isn't. */
    fun ensure() {
        if (!::app.isInitialized || !wanted()) return
        synchronized(this) {
            if (thread?.isAlive == true) return
            thread = Thread({ loop() }, "atlas-link").apply { isDaemon = true; start() }
        }
    }

    private fun loop() {
        var failures = 0
        while (wanted()) {
            try {
                val code = Sync.code(app) ?: break
                if (code != codeInUse) { codeInUse = code; cursor = -1; peers = emptyList(); lastBeat = 0 }
                val live = foreground || active.get() > 0 || Remote.controlled()
                if (System.currentTimeMillis() - lastBeat > if (live) 20_000 else 45_000) beat()
                if (live) pull(2000)
                else { pull(0); Thread.sleep(if (screenOn()) 5_000 else 30_000) }
                failures = 0; lastError = null
            } catch (_: InterruptedException) { break
            } catch (e: Exception) {
                failures++; lastError = e.message ?: e.javaClass.simpleName
                try { Thread.sleep(minOf(30_000L, 1000L shl minOf(failures, 5))) } catch (_: InterruptedException) { break }
            }
        }
        synchronized(this) { thread = null }
    }
    private fun screenOn() = try { app.getSystemService(android.os.PowerManager::class.java).isInteractive } catch (_: Exception) { true }

    // ---------------------------------------------------------------- the relay

    private fun rpc(fn: String, body: JSONObject, readMs: Int = 9000): String {
        val con = URL("$URL_BASE/$fn").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = readMs
            con.setRequestProperty("apikey", KEY); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IllegalStateException("the cloud answered $code ${text.take(120)}")
            return text
        } finally { con.disconnect() }
    }
    private fun code() = Sync.code(app) ?: throw IllegalStateException("not linked")
    private fun owner() = Sync.owner(code())

    /** Says this phone is here, and learns who else is. */
    fun beat(): List<Peer> {
        val c = code()
        val info = JSONObject().put("at", System.currentTimeMillis()).put("name", phoneName(app)).put("platform", "android")
            .put("caps", JSONArray(listOf("tasks", "screen", "control"))).put("reachable", reachable(app)).put("capture", Remote.canCapture())
        val rows = JSONArray(rpc("link_beat", JSONObject().put("p_owner", Sync.owner(c)).put("p_device", me()).put("p_info", Sync.encrypt(c, info.toString()))))
        val list = mutableListOf<Peer>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i); if (r.optString("device") == me()) continue
            val inf = try { JSONObject(Sync.decrypt(c, r.optString("info"))) } catch (_: Exception) { continue }   // another code: not ours
            list.add(Peer(r.optString("device"), inf.optString("name", "Device"), inf.optString("platform"), r.optLong("ago_ms"), inf))
        }
        peers = list; lastBeat = System.currentTimeMillis(); changed()
        LinkTasks.sendQueued()
        return list
    }

    private fun pull(waitMs: Int) {
        val c = code(); val own = Sync.owner(c)
        if (cursor < 0) cursor = rpc("link_head", JSONObject().put("p_owner", own)).trim().toLongOrNull() ?: 0
        val rows = JSONArray(rpc("link_pull", JSONObject().put("p_owner", own).put("p_device", me()).put("p_since", cursor).put("p_wait_ms", waitMs), readMs = waitMs + 9000))
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            cursor = maxOf(cursor, r.optLong("id"))
            val m = try { JSONObject(Sync.decrypt(c, r.optString("payload"))) } catch (_: Exception) { null }
            if (m == null || !LinkCore.accept(m, me())) continue
            val id = m.optString("id")
            if (!seen.add(id)) continue
            if (seen.size > 500) synchronized(seen) { seen.iterator().let { it.next(); it.remove() } }
            handle(m)
        }
    }

    private fun handle(m: JSONObject) {
        val k = m.optString("k")
        if (k == "ping") { sendAsync(m.optString("from"), "pong", JSONObject().put("n", m.opt("n")).put("t", m.opt("t"))); return }
        for (key in listOf(k + ":" + (m.optString("tid").ifEmpty { m.optString("sid").ifEmpty { m.opt("n")?.toString().orEmpty() } }), k))
            waiters.remove(key)?.invoke(m)
        try {
            when (k) {
                "task", "task_decide", "task_ack", "task_update", "task_approval", "task_done" -> LinkTasks.onMessage(m)
                "screen_start", "screen_keep", "screen_stop", "input" -> Remote.onMessage(m)
            }
        } catch (e: Exception) { Log.w(TAG, "handling $k", e) }
    }

    /** Sends one message (network: not on the main thread). Returns it. */
    fun send(to: String, k: String, fields: JSONObject = JSONObject()): JSONObject {
        val c = code()
        val msg = JSONObject(fields.toString()).put("v", LinkCore.VERSION).put("id", UUID.randomUUID().toString()).put("k", k)
            .put("from", me()).put("to", to).put("at", System.currentTimeMillis())
        rpc("link_send", JSONObject().put("p_owner", Sync.owner(c)).put("p_from", me()).put("p_to", to).put("p_payload", Sync.encrypt(c, msg.toString())))
        return msg
    }
    /** Sends from a background thread, in order; failures are logged (the next state change sends again). */
    fun sendAsync(to: String, k: String, fields: JSONObject = JSONObject()) { sender.execute { try { send(to, k, fields) } catch (e: Exception) { Log.w(TAG, "send $k", e) } } }

    /** Waits (blocking: a background thread) for a message of kind [k] with this tid/sid/n, up to [ms]; null if none. */
    fun waitFor(k: String, key: String?, ms: Long): JSONObject? {
        val name = if (key == null) k else "$k:$key"
        val latch = CountDownLatch(1); var got: JSONObject? = null
        waiters[name] = { m -> got = m; latch.countDown() }
        latch.await(ms, TimeUnit.MILLISECONDS)
        waiters.remove(name)
        return got
    }

    /** Round trip to [to] in ms, or null. Blocking. */
    fun ping(to: String): Long? {
        setActive(true)
        try {
            val n = (Math.random() * 1e9).toLong(); val t0 = System.currentTimeMillis()
            sendAsync(to, "ping", JSONObject().put("n", n).put("t", t0))
            return waitFor("pong", n.toString(), 8000)?.let { System.currentTimeMillis() - t0 }
        } finally { setActive(false) }
    }

    // ---------------------------------------------------------------- screen frames (the newest only)

    fun putFrame(seq: Long, frame: JSONObject) {
        val c = code()
        rpc("link_frame_put", JSONObject().put("p_owner", Sync.owner(c)).put("p_device", me()).put("p_seq", seq).put("p_payload", Sync.encrypt(c, frame.toString())))
    }
    /** The newest frame of [device] after [after] (waits up to [waitMs]); null when there's none yet. */
    fun getFrame(device: String, after: Long, waitMs: Int = 2000): Pair<Long, JSONObject>? {
        val c = code()
        val rows = JSONArray(rpc("link_frame_get", JSONObject().put("p_owner", Sync.owner(c)).put("p_device", device).put("p_after", after).put("p_wait_ms", waitMs), readMs = waitMs + 9000))
        if (rows.length() == 0) return null
        val r = rows.getJSONObject(0)
        return Pair(r.optLong("seq"), JSONObject(Sync.decrypt(c, r.optString("payload"))))
    }

    fun ago(ms: Long): String { val s = ms / 1000; return when { s < 90 -> "just now"; s < 5400 -> "${s / 60} min ago"; s < 129_600 -> "${s / 3600} h ago"; else -> "${s / 86_400} days ago" } }
}
