package com.anjy.atlas

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Me → Your Devices, the Device Link part: is the laptop online, Control Laptop, whether the laptop may control this
 * phone, "Reachable from your laptop", tasks waiting on the laptop (with Allow / Deny when one needs your OK).
 */
private val linkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

internal fun MainActivity.linkCard(): View {
    val c = card()
    c.addView(text("Control Across Devices", 18f, FG, HEAD))
    c.add(text("Drive your laptop from here, let your laptop drive this phone, or just ask Atlas: “on my laptop, …”. Everything goes encrypted with your link code.", 14f, MUTED), 6)
    val peers = Link.peers
    if (peers.isEmpty()) c.add(text("Waiting to hear from your laptop… (Atlas has to be running there, updated to this version).", 14f, MUTED), 10)
    for (p in peers) {
        c.add(text("${p.name}  ${if (p.online) "● online" else "● seen ${Link.ago(p.agoMs)}"}", 16f, if (p.online) DONE else MUTED, MED), 14)
        if (p.platform != "android") c.add(primary("Control Laptop") {
            if (!p.online) showBanner("Your laptop isn't online. Atlas has to be running on it.", MISS)
            else startActivity(Intent(this, LaptopActivity::class.java))
        }.apply { alpha = if (p.online) 1f else 0.5f }, 10)
        c.add(switchRow(null, "Let ${p.name} Control This Phone", Remote.allows(p.device)) { on -> Remote.setAllowed(p.device, on); showBanner(if (on) "Allowed" else "Not allowed any more") }, 10)
    }
    if (!Remote.canCapture()) c.add(text("For your laptop to see and drive this phone, turn on Phone Control in Me.", 13f, WARM), 8)
    c.add(switchRow(null, "Reachable From Your Laptop", Link.reachable(this)) { on -> Link.setReachable(applicationContext, on); render(keepScroll = true) }, 14)
    c.add(text("On: your laptop can reach this phone even with Atlas closed (a quiet notification stays). Off: only while Atlas is open. Uses a little battery.", 13f, MUTED), 4)
    val who = Remote.who()
    if (who.isNotEmpty()) {
        c.add(text("${who.joinToString(", ")} is controlling this phone now.", 14f, WARM, MED), 12)
        c.add(secondary("Stop Now") { Thread { Remote.stopAll("Stopped on the phone.") }.start(); render(keepScroll = true) }, 8)
    }
    val out = LinkTasks.outbox()
    for (tid in out.keys().asSequence().toList()) {
        val t = out.optJSONObject(tid) ?: continue
        val title = t.optString("title").ifEmpty { t.optString("text") }.take(90)
        val state = if (!t.optBoolean("sent")) "waiting for the laptop to come online" else when (t.optString("state")) { "awaiting_approval" -> "needs your OK: " + t.optString("approval"); "running", "sent" -> t.optString("line").ifEmpty { "working" }; else -> t.optString("state") }
        c.add(text("On your laptop: $title — $state", 13f, MUTED), 10)
        if (t.optString("state") == "awaiting_approval") {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(primary("Allow") { linkScope.launch { withContext(Dispatchers.IO) { LinkTasks.answer(tid, true) }; render(keepScroll = true) } }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(secondary("Deny") { linkScope.launch { withContext(Dispatchers.IO) { LinkTasks.answer(tid, false) }; render(keepScroll = true) } }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            c.add(row, 6)
        }
    }
    Link.lastError?.let { c.add(text("Link: $it (it keeps trying)", 12f, MUTED), 8) }
    return c
}
