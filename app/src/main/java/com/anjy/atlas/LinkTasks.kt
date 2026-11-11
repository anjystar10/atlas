package com.anjy.atlas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/**
 * Device Link tasks on the phone:
 * - "On my laptop, …" (the on_laptop tool): sent to the PC, which runs it as an Atlas Bot job with its own tools.
 *   Progress and approvals come back here (an approval shows as a notification with Allow / Deny); the report lands
 *   in this chat. If the laptop is offline the task waits here (a day at most) and goes when it's back.
 * - A task from the PC runs here as an Atlas Bot job with phone control; its approvals also go to the PC, and the
 *   first answer (there or here) counts. A task is claimed in the cloud first, so it runs once.
 */
object LinkTasks {
    private const val CH = "atlas_link"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val app get() = Link.app

    // ---------------------------------------------------------------- stored lists (small JSON in prefs)

    private fun obj(key: String) = try { JSONObject(Store.getString(app, key) ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun save(key: String, o: JSONObject) { Store.putString(app, key, o.toString()); Link.changed() }

    /** Tasks sent to the laptop: tid → {text, at, state, line, sent, title, approval}. */
    fun outbox(): JSONObject = obj("link_out")

    // ---------------------------------------------------------------- sending ("on my laptop")

    /** Sends [text] to the laptop as a job there. Network: call off the main thread. Returns a sentence for Atlas. */
    fun sendTask(text: String): String {
        if (!Sync.linked(app)) return "Error: this phone isn't linked to a PC yet (Me → Your Devices)."
        Link.init(app)
        val tid = UUID.randomUUID().toString()
        val o = outbox()
        o.put(tid, JSONObject().put("text", text.take(4000)).put("at", System.currentTimeMillis()).put("state", "queued").put("sent", false))
        save("link_out", o)
        val lap = try { Link.beat(); Link.laptop() } catch (_: Exception) { Link.laptop() }
        if (lap != null && lap.online) { sendOne(tid); return "Sent to your laptop. It will do it there and its report will come into this chat." }
        return "Your laptop isn't online right now" + (lap?.let { " (last seen ${Link.ago(it.agoMs)})" } ?: "") + ". I'll send it as soon as it is (Atlas has to be running on it)."
    }

    private fun sendOne(tid: String) {
        val o = outbox(); val t = o.optJSONObject(tid) ?: return
        if (t.optBoolean("sent")) return
        val lap = Link.laptop() ?: return
        Link.send(lap.device, "task", JSONObject().put("tid", tid).put("text", t.optString("text")).put("fromName", Link.phoneName(app)))
        t.put("sent", true).put("state", "sent").put("to", lap.device); o.put(tid, t); save("link_out", o)
        Link.setActive(true); main.postDelayed({ Link.setActive(false) }, 120_000)
    }

    /** Called after each check-in: send what was waiting for the laptop to come online. Background thread. */
    fun sendQueued() {
        val lap = Link.laptop() ?: return
        if (!lap.online) return
        val o = outbox()
        for (tid in o.keys().asSequence().toList()) {
            val t = o.optJSONObject(tid) ?: continue
            if (t.optBoolean("sent")) continue
            if (System.currentTimeMillis() - t.optLong("at") > 24 * 3_600_000L) { o.remove(tid); save("link_out", o); continue }
            try { sendOne(tid) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- messages

    fun onMessage(m: JSONObject) {
        when (m.optString("k")) {
            "task" -> taskIn(m)
            "task_decide" -> main.post { decideIn(m) }
            else -> outUpdate(m)
        }
    }

    private fun outUpdate(m: JSONObject) {
        val tid = m.optString("tid"); val o = outbox(); val t = o.optJSONObject(tid) ?: return
        when (m.optString("k")) {
            "task_ack" -> t.put("state", "running").put("title", m.optString("title").ifEmpty { t.optString("text") })
            "task_update" -> t.put("state", m.optString("state")).put("line", m.optString("line"))
            "task_approval" -> { t.put("state", "awaiting_approval").put("approval", m.optString("what")).put("from", m.optString("from")); notifyApproval(tid, m.optString("what")) }
            "task_done" -> {
                o.remove(tid); save("link_out", o)
                val title = t.optString("title").ifEmpty { t.optString("text") }
                main.post { Conversation.postFromBot(app, "**From your laptop:** $title\n\n${m.optString("report").ifEmpty { if (m.optBoolean("ok")) "Done." else "It couldn't finish." }}") }
                notify(tid.hashCode(), if (m.optBoolean("ok")) "Your laptop finished" else "Your laptop couldn't finish", title.take(120), null)
                return
            }
        }
        o.put(tid, t); save("link_out", o)
    }

    /** The user's answer to a laptop job's approval (from the notification or Me). Background-safe. */
    fun answer(tid: String, allow: Boolean) {
        val o = outbox(); val t = o.optJSONObject(tid) ?: return
        if (t.optString("state") != "awaiting_approval") return
        val to = t.optString("from").ifEmpty { t.optString("to") }
        t.put("state", if (allow) "running" else "denied").remove("approval"); o.put(tid, t); save("link_out", o)
        Link.sendAsync(to, "task_decide", JSONObject().put("tid", tid).put("allow", allow))
        app.getSystemService(NotificationManager::class.java).cancel(tid.hashCode())
    }

    // ---------------------------------------------------------------- tasks from the laptop (run here)

    private fun doneTids(): MutableList<String> = try { org.json.JSONArray(Store.getString(app, "link_tids") ?: "[]").let { a -> MutableList(a.length()) { a.getString(it) } } } catch (_: Exception) { mutableListOf() }
    private fun remoteJobs(): JSONObject = obj("link_remote_jobs")

    private fun taskIn(m: JSONObject) {
        val tid = m.optString("tid"); if (tid.isEmpty()) return
        val done = doneTids(); if (tid in done) return
        done.add(tid); Store.putString(app, "link_tids", org.json.JSONArray(done.takeLast(200)).toString())
        val c = Sync.claim(app, "linktask:$tid", 3_600_000L)
        if (c != null && !c.mine) return
        val text = m.optString("text").take(4000)
        main.post {
            Bots.init(app)
            val j = Bots.deploy(app, text.lineSequence().first().take(300),
                "Sent from ${m.optString("fromName").ifEmpty { "the laptop" }} through the Device Link: the user is at their laptop, not holding this phone. Do the whole job here with the phone's tools (apps, phone control, the browser). Then report exactly what you did, with evidence (what the screen showed, confirmation text).\n\nThe request: $text")
            j.posted = true   // the laptop posts the report in its chat (it syncs here), so it isn't posted twice
            val rj = remoteJobs(); rj.put(j.id, JSONObject().put("tid", tid).put("from", m.optString("from")).put("last", "")); save("link_remote_jobs", rj)
            Link.sendAsync(m.optString("from"), "task_ack", JSONObject().put("tid", tid).put("jobId", j.id).put("title", j.goal))
            watchJobs()
            Link.setActive(true); main.postDelayed({ Link.setActive(false) }, 60_000)
        }
    }

    private fun decideIn(m: JSONObject) {
        val rj = remoteJobs()
        val id = rj.keys().asSequence().firstOrNull { rj.optJSONObject(it)?.optString("tid") == m.optString("tid") } ?: return
        val j = Bots.job(id) ?: return
        if (j.state == BotRules.AWAITING_APPROVAL) Bots.approve(id, m.optBoolean("allow"))   // first answer wins (here or there)
    }

    private var watching = false
    private val onBots: () -> Unit = { report() }
    /** Starts reporting the laptop's jobs (also after a restart, from Link.init via the app). Main thread. */
    fun watchJobs() { if (watching) return; watching = true; Bots.listen(onBots); report() }

    private fun report() {
        val rj = remoteJobs(); var dirty = false
        for (id in rj.keys().asSequence().toList()) {
            val r = rj.optJSONObject(id) ?: continue
            val j = Bots.job(id)
            if (j == null) { rj.remove(id); dirty = true; continue }
            val a = j.approval
            val line = if (j.state == BotRules.AWAITING_APPROVAL && a != null) "approval:" + a.what else j.state + ":" + (j.current ?: "")
            if (line == r.optString("last")) continue
            r.put("last", line); dirty = true
            val to = r.optString("from"); val tid = r.optString("tid")
            when {
                j.state == BotRules.AWAITING_APPROVAL && a != null && a.decision == null ->
                    Link.sendAsync(to, "task_approval", JSONObject().put("tid", tid).put("what", a.what).put("category", a.category ?: ""))
                j.state in BotRules.FINAL -> {
                    val ok = j.state == BotRules.COMPLETED
                    Link.sendAsync(to, "task_done", JSONObject().put("tid", tid).put("ok", ok).put("state", j.state)
                        .put("report", if (ok) j.report ?: "Done." else j.error ?: if (j.state == BotRules.CANCELLED) "It was stopped." else "It couldn't finish."))
                    rj.remove(id)
                }
                else -> Link.sendAsync(to, "task_update", JSONObject().put("tid", tid).put("state", j.state).put("line", j.current ?: ""))
            }
        }
        if (dirty) save("link_remote_jobs", rj)
    }

    // ---------------------------------------------------------------- notifications

    private fun channel() {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Your devices", NotificationManager.IMPORTANCE_HIGH).apply { description = "Your laptop doing things for you, and asking for your OK" })
    }
    private fun notifyApproval(tid: String, what: String) {
        fun act(allow: Boolean) = PendingIntent.getBroadcast(app, (tid + allow).hashCode(),
            Intent(app, LinkReceiver::class.java).setAction(if (allow) LinkReceiver.ALLOW else LinkReceiver.DENY).putExtra("tid", tid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(tid.hashCode(), "Your laptop needs your OK", what, listOf("Allow" to act(true), "Deny" to act(false)))
    }
    internal fun notify(id: Int, title: String, body: String, actions: List<Pair<String, PendingIntent>>?, ongoing: Boolean = false) {
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        channel()
        val open = PendingIntent.getActivity(app, 7701, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = android.app.Notification.Builder(app, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(!ongoing).setOngoing(ongoing)
        actions?.forEach { (label, pi) -> b.addAction(android.app.Notification.Action.Builder(null, label, pi).build()) }
        app.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}

/** Allow / Deny on a laptop job's approval, and Allow / Don't Allow for the laptop controlling this phone. */
class LinkReceiver : BroadcastReceiver() {
    companion object { const val ALLOW = "com.anjy.atlas.LINK_ALLOW"; const val DENY = "com.anjy.atlas.LINK_DENY"; const val CTRL_ALLOW = "com.anjy.atlas.LINK_CTRL_ALLOW"; const val CTRL_DENY = "com.anjy.atlas.LINK_CTRL_DENY"; const val STOP = "com.anjy.atlas.LINK_STOP" }
    override fun onReceive(ctx: Context, intent: Intent) {
        Link.init(ctx)
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ALLOW, DENY -> LinkTasks.answer(intent.getStringExtra("tid") ?: "", intent.action == ALLOW)
                    CTRL_ALLOW, CTRL_DENY -> Remote.consent(intent.getStringExtra("sid") ?: "", intent.action == CTRL_ALLOW)
                    STOP -> Remote.stopAll("Stopped on the phone.")
                }
            } finally { pending.finish() }
        }.start()
    }
}
