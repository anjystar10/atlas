package com.anjy.atlas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Your laptop controlling this phone (Device Link): the phone's screen goes to the laptop a few times a second, and the
 * laptop's clicks, drags and typing come back as taps, swipes and text here.
 *
 * Rules: it needs Phone Control on (the accessibility service takes the screenshots and does the touches); a laptop
 * may control this phone only after you allow it here (once per device, revocable in Me); while it does, a
 * notification says so, with Stop; it ends by itself when the laptop goes quiet for 20 s. Apps that block screenshots
 * (banking, some video apps) show black, by their own choice.
 */
object Remote {
    private const val TAG = "AtlasRemote"
    const val NOTIF = 77_001

    class Session(val sid: String, val from: String, val who: String, val maxW: Int, val q: Int) {
        @Volatile var lastKeep = System.currentTimeMillis()
        @Volatile var stopped = false
        @Volatile var realW = 0; @Volatile var realH = 0
        var seq = 0L
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val asking = ConcurrentHashMap<String, JSONObject>()   // sid → the screen_start waiting for your answer
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "atlas-remote").apply { isDaemon = true } }

    fun controlled() = sessions.isNotEmpty()
    fun canCapture() = Build.VERSION.SDK_INT >= 30 && AtlasAccess.instance != null

    private val app get() = Link.app
    private fun allowed(): MutableList<String> = try { JSONArray(Store.getString(app, "link_allow_control") ?: "[]").let { a -> MutableList(a.length()) { a.getString(it) } } } catch (_: Exception) { mutableListOf() }
    fun allows(device: String) = device in allowed()
    fun setAllowed(device: String, on: Boolean) { val a = allowed().filter { it != device }.toMutableList(); if (on) a.add(device); Store.putString(app, "link_allow_control", JSONArray(a).toString()); Link.changed() }

    fun onMessage(m: JSONObject) {
        val sid = m.optString("sid")
        when (m.optString("k")) {
            "screen_start" -> start(m)
            "screen_keep" -> sessions[sid]?.lastKeep = System.currentTimeMillis()
            "screen_stop" -> end(sid, null)
            "input" -> sessions[sid]?.let { s -> if (s.from == m.optString("from")) { s.lastKeep = System.currentTimeMillis(); worker.execute { input(s, m.optJSONObject("input")) } } }
        }
    }

    private fun start(m: JSONObject) {
        val sid = m.optString("sid"); val from = m.optString("from")
        if (sid.isEmpty() || sessions.containsKey(sid)) return
        if (!canCapture()) { Link.sendAsync(from, "screen_reply", JSONObject().put("sid", sid).put("ok", false).put("why", "no_capture")); return }
        if (!allows(from)) {
            asking[sid] = m
            Link.sendAsync(from, "screen_reply", JSONObject().put("sid", sid).put("ok", false).put("why", "asking"))
            val who = Link.peers.firstOrNull { it.device == from }?.name ?: "Your laptop"
            fun act(a: String) = PendingIntent.getBroadcast(app, (sid + a).hashCode(), Intent(app, LinkReceiver::class.java).setAction(a).putExtra("sid", sid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            LinkTasks.notify(sid.hashCode(), "$who wants to control this phone", "It will see your screen and can tap and type. A notification shows while it does, with Stop.",
                listOf("Allow" to act(LinkReceiver.CTRL_ALLOW), "Don't Allow" to act(LinkReceiver.CTRL_DENY)))
            return
        }
        begin(m)
    }

    /** Your answer to "wants to control this phone". */
    fun consent(sid: String, yes: Boolean) {
        val m = asking.remove(sid) ?: return
        app.getSystemService(android.app.NotificationManager::class.java).cancel(sid.hashCode())
        if (!yes) { Link.sendAsync(m.optString("from"), "screen_reply", JSONObject().put("sid", sid).put("ok", false).put("why", "denied")); return }
        setAllowed(m.optString("from"), true)
        begin(m)
    }

    private fun begin(m: JSONObject) {
        val sid = m.optString("sid"); val from = m.optString("from")
        val who = Link.peers.firstOrNull { it.device == from }?.name ?: "Your laptop"
        val s = Session(sid, from, who, m.optInt("maxW", 720).coerceIn(360, 1080), m.optInt("q", 50).coerceIn(30, 80))
        sessions[sid] = s
        Link.setActive(true)
        LinkService.start(app)   // the "X is controlling this phone — Stop" notification, and keeps the line open
        Link.sendAsync(from, "screen_reply", JSONObject().put("sid", sid).put("ok", true))
        worker.execute { stream(s) }
        Link.changed()
    }

    private fun stream(s: Session) {
        var lastHash = 0; var lastSent = 0L
        while (!s.stopped) {
            if (!LinkCore.sessionAlive(s.lastKeep, System.currentTimeMillis())) { end(s.sid, "The laptop went quiet, so control ended."); break }
            val t0 = System.currentTimeMillis()
            val shot = try { capture(s.maxW, s.q) } catch (e: Exception) { Log.w(TAG, "capture", e); null }
            if (shot != null) {
                s.realW = shot.optInt("realW"); s.realH = shot.optInt("realH")
                val h = shot.optString("jpg").hashCode()
                if (h != lastHash || System.currentTimeMillis() - lastSent > 3000) {
                    lastHash = h; lastSent = System.currentTimeMillis()
                    try { Link.putFrame(++s.seq, shot.put("sid", s.sid)) } catch (e: Exception) { Log.w(TAG, "frame", e); Thread.sleep(800) }
                }
            } else if (AtlasAccess.instance == null) { end(s.sid, "Phone Control was turned off."); break }
            // Android allows a screenshot about every 333 ms; at most about 3 frames a second
            Thread.sleep(maxOf(120L, 360L - (System.currentTimeMillis() - t0)))
        }
    }

    /** The screen as a JPEG at most [maxW] wide, with its real size. Blocking (a background thread). */
    private fun capture(maxW: Int, q: Int): JSONObject? {
        val svc = AtlasAccess.instance ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1); var out: JSONObject? = null
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    hb.close()
                    if (hw != null) {
                        val realW = hw.width; val realH = hw.height
                        val scale = minOf(1f, maxW.toFloat() / realW)
                        val soft = hw.copy(Bitmap.Config.ARGB_8888, false); hw.recycle()
                        val small = if (scale < 1f) Bitmap.createScaledBitmap(soft, (realW * scale).toInt(), (realH * scale).toInt(), true).also { soft.recycle() } else soft
                        val bos = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, q, bos)
                        out = JSONObject().put("w", small.width).put("h", small.height).put("realW", realW).put("realH", realH)
                            .put("jpg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                        small.recycle()
                    }
                } catch (e: Exception) { Log.w(TAG, "screenshot", e) } finally { latch.countDown() }
            }
            override fun onFailure(code: Int) { latch.countDown() }   // too soon after the last one, or a secure screen
        })
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun input(s: Session, inp: JSONObject?) {
        if (s.realW <= 0) return
        val plan = LinkCore.inputPlan(inp, s.realW, s.realH) ?: return
        val svc = AtlasAccess.instance ?: run { Link.sendAsync(s.from, "input_error", JSONObject().put("sid", s.sid).put("error", "Phone Control is off.")); return }
        val err = try { perform(svc, plan) } catch (e: Exception) { e.message ?: "failed" }
        if (err != null) Link.sendAsync(s.from, "input_error", JSONObject().put("sid", s.sid).put("error", err))
    }

    private fun stroke(svc: AtlasAccess, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): String? {
        val p = Path().apply { moveTo(x1.toFloat().coerceAtLeast(1f), y1.toFloat().coerceAtLeast(1f)); lineTo(x2.toFloat().coerceAtLeast(1f), y2.toFloat().coerceAtLeast(1f)) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        val latch = CountDownLatch(1); var ok = false
        val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return "The phone didn't take the touch."
        latch.await(ms + 2000, TimeUnit.MILLISECONDS)
        return if (ok) null else "The touch was interrupted."
    }

    /** Does one checked input. Returns an error message, or null. */
    private fun perform(svc: AtlasAccess, p: JSONObject): String? {
        val x = p.optInt("x"); val y = p.optInt("y")
        return when (p.optString("t")) {
            "tap", "right" -> stroke(svc, x, y, x, y, 40)
            "double" -> stroke(svc, x, y, x, y, 40) ?: run { Thread.sleep(90); stroke(svc, x, y, x, y, 40) }
            "long" -> stroke(svc, x, y, x, y, 700)
            "drag" -> stroke(svc, x, y, p.optInt("x2"), p.optInt("y2"), 350)
            "scroll" -> { val d = p.optInt("dy") * 120; stroke(svc, x, y, x, (y - d).coerceIn(1, s0(svc).second - 1), 300) }   // dy > 0: read further down
            "text" -> typeText(svc, p.optString("text"))
            "key" -> when (p.optString("key")) {
                "enter" -> focused(svc)?.let { n -> if (Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) null else "Enter didn't work in this box." } ?: "Tap a text box first."
                "backspace" -> focused(svc)?.let { n -> val t = n.text?.toString().orEmpty(); setText(n, t.dropLast(1)) } ?: "Tap a text box first."
                "esc" -> if (svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) null else "Back didn't work."
                else -> "That key isn't on a phone."
            }
            "act" -> when (p.optString("act")) {
                "back" -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> global(svc, AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> global(svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "lock" -> global(svc, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "volume_up", "volume_down", "mute" -> {
                    val am = app.getSystemService(AudioManager::class.java)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, when (p.optString("act")) { "volume_up" -> AudioManager.ADJUST_RAISE; "volume_down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }, AudioManager.FLAG_SHOW_UI); null
                }
                else -> "That isn't something a phone does."
            }
            else -> "Unknown input."
        }
    }
    private fun s0(svc: AtlasAccess): Pair<Int, Int> = sessions.values.firstOrNull()?.let { Pair(it.realW, it.realH) } ?: svc.resources.displayMetrics.let { Pair(it.widthPixels, it.heightPixels) }
    private fun global(svc: AtlasAccess, a: Int) = if (svc.performGlobalAction(a)) null else "The phone didn't do that."
    private fun focused(svc: AtlasAccess): AccessibilityNodeInfo? = try { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } } catch (_: Exception) { null }
    private fun setText(n: AccessibilityNodeInfo, t: String): String? =
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) })) null else "That box didn't take the text."
    private fun typeText(svc: AtlasAccess, add: String): String? {
        val n = focused(svc) ?: return "Tap a text box on the phone first."
        // a box showing its hint has no real text yet
        val now = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        return setText(n, now + add)
    }

    /** Ends one session (and tells the laptop). */
    fun end(sid: String, why: String?) {
        val s = sessions.remove(sid) ?: return
        s.stopped = true
        Link.sendAsync(s.from, "screen_stop", JSONObject().put("sid", sid).put("why", why ?: "Stopped on the phone."))
        Link.setActive(false)
        if (sessions.isEmpty()) LinkService.refresh(app)
        Link.changed()
    }
    fun stopAll(why: String) { for (sid in sessions.keys.toList()) end(sid, why) }
    fun who(): List<String> = sessions.values.map { it.who }
}
