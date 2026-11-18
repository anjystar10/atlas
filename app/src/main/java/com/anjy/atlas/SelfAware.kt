package com.anjy.atlas

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.CalendarContract
import com.anjy.atlas.core.Around
import com.anjy.atlas.core.Engine
import com.anjy.atlas.core.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Atlas aware of itself and of what's around it, on the phone.
 *
 * - Around it: time, battery, network, sound mode, headphones, what's in front, notifications, the next alarm and
 *   events, rough location, storage, screen, temperature. Only what Atlas's existing permissions already allow;
 *   calendar and location are read only for the whats_around tool, never for the brief. Nothing here is stored or
 *   sent anywhere except to the AI service in the turn that uses it.
 * - Inside it: version and changelog (Updater), abilities (the capability registry), permissions, AI keys and their
 *   health, bots, sync and the link, models, settings, recent errors.
 * - In the brief: one short "Now:" line plus alerts that matter (mentioned once), and "This Atlas" lines; the rest
 *   comes from the tools (whats_around, self_status, about_atlas), so every turn stays cheap.
 */
object SelfAware {
    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    @Volatile private var seenAlerts: List<String> = emptyList()

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** What's around right now. [full]: also calendar, rough location and notification apps (for the tool). Any thread. */
    fun facts(ctx: Context, full: Boolean = false): JSONObject {
        val f = JSONObject()
        val now = System.currentTimeMillis()
        f.put("time", timeFmt.format(Instant.ofEpochMilli(now).atZone(Engine.zone))).put("zone", Engine.zone.id)
        try {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) f.put("battery", pct).put("charging", bm.isCharging)
        } catch (_: Exception) {}
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            f.put("network", when {
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            })
            if (caps != null) f.put("metered", cm.isActiveNetworkMetered)
        } catch (_: Exception) {}
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val filter = nm.currentInterruptionFilter
            f.put("dnd", filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        } catch (_: Exception) {}
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            f.put("ringer", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            f.put("audio", when {
                outs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLE_HEADSET || it == AudioDeviceInfo.TYPE_BLE_SPEAKER } -> "bluetooth"
                outs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "headphones"
                else -> "speaker"
            })
        } catch (_: Exception) {}
        try {
            // what's in front: only when Phone Control (accessibility) is on, and not when it's Atlas itself
            val top = AtlasAccess.instance?.rootInActiveWindow?.packageName?.toString()
            if (top != null && top != ctx.packageName) f.put("foreground", label(ctx, top))
        } catch (_: Exception) {}
        try {
            // notifications: only with notification access; counts and app names, never their text here
            AtlasNotifs.instance?.let { s ->
                val list = s.activeNotifications.orEmpty().filter { !it.isOngoing && it.packageName != ctx.packageName }
                f.put("notifications", list.size)
                if (full) f.put("notifApps", JSONArray(list.map { label(ctx, it.packageName) }.distinct().take(8)))
            }
        } catch (_: Exception) {}
        try {
            ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime?.let { t ->
                if (t - now < 36 * 3_600_000L) f.put("nextAlarm", timeFmt.format(Instant.ofEpochMilli(t).atZone(Engine.zone)))
            }
        } catch (_: Exception) {}
        try { f.put("storageFreeGb", StatFs(ctx.filesDir.path).availableBytes / 1_073_741_824.0) } catch (_: Exception) {}
        try {
            val pm = ctx.getSystemService(PowerManager::class.java)
            f.put("screenOn", pm.isInteractive)
            f.put("thermal", ModelDirector.thermalWord(pm.currentThermalStatus))
        } catch (_: Exception) {}
        if (full) {
            if (granted(ctx, Manifest.permission.READ_CALENDAR)) try { f.put("nextEvents", JSONArray(nextEvents(ctx, now))) } catch (_: Exception) {}
            if (granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) try { roughLocation(ctx)?.let { f.put("location", it) } } catch (_: Exception) {}
            try { if (Sync.linked(ctx)) { Link.init(ctx); Link.laptop()?.let { f.put("other", "laptop ${it.name}: ${if (it.online) "online" else "offline, last seen ${Link.ago(it.agoMs)}"}") } } } catch (_: Exception) {}
        }
        return f
    }

    private fun label(ctx: Context, pkg: String): String = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    private fun nextEvents(ctx: Context, now: Long): List<String> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, now + 24 * 3_600_000L) }.build()
        val out = mutableListOf<String>()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 3) {
                val title = c.getString(0)?.take(60) ?: "(no title)"
                out.add(if (c.getInt(2) == 1) "$title (all day)" else "$title at ${timeFmt.format(Instant.ofEpochMilli(c.getLong(1)).atZone(Engine.zone))}")
            }
        }
        return out
    }

    /** The last known position Android already has, rounded to about 10 km. Never asks for a fresh fix. */
    @Suppress("MissingPermission")
    private fun roughLocation(ctx: Context): String? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val best = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } }.maxByOrNull { it.time } ?: return null
        val r = { d: Double -> String.format(Locale.US, "%.1f", d) }
        return "around ${r(best.latitude)}, ${r(best.longitude)} (rounded to about 10 km; ${Link.ago(System.currentTimeMillis() - best.time)})"
    }

    /** The brief's "Around you" lines (alerts marked NEW the first time). Off the main thread except for the bots' list. */
    suspend fun aroundLines(ctx: Context, markSeen: Boolean = true): List<String> {
        val f = withContext(Dispatchers.IO) { facts(ctx) }
        val (bots, trading) = withContext(Dispatchers.Main.immediate) {
            try {
                val live = Bots.visible().filter { !it.final }
                live.size to live.any { j -> j.goal.contains("trad", true) || (j.specialist?.let { id -> BotExtras.specialists.firstOrNull { it.optString("id") == id }?.let { s -> (s.optString("name") + s.optString("role")).contains("trad", true) } } ?: false) }
            } catch (_: Exception) { 0 to false }
        }
        val (lines, keys) = Around.section(f, JSONObject().put("botsRunning", bots).put("tradingRunning", trading), seenAlerts)
        if (markSeen) seenAlerts = keys
        return lines
    }

    // ------------------------------------------------------------------ inside Atlas

    suspend fun status(ctx: Context): String {
        val out = mutableListOf<String>()
        fun part(title: String, lines: List<String>) { if (lines.isNotEmpty()) { out.add("$title:"); lines.forEach { out.add("- $it") } } }
        val st = Updater.state(ctx)
        out.add(Updates.identityLine(st.version, "the phone") + " Version code ${st.versionCode}." + (st.from?.let { " Updated from $it." } ?: ""))
        Updater.latest(ctx)?.let { out.add(if (Updates.isNewer(it, st.version, st.versionCode)) "A newer release is out: ${it.version}." else "Up to date (newest release ${it.version}).") }
        part("Abilities (tool groups)", Capabilities.all().map { c -> c.id + ": " + try { val d = c.declarations(ctx); (0 until d.length()).joinToString(", ") { d.optJSONObject(it)?.optString("name").orEmpty() } } catch (_: Exception) { "?" } })
        val runtime = listOf("Calendar" to Manifest.permission.READ_CALENDAR, "Contacts" to Manifest.permission.READ_CONTACTS,
            "Location" to Manifest.permission.ACCESS_COARSE_LOCATION, "Microphone" to Manifest.permission.RECORD_AUDIO)
        val perms = PermissionCenter.ALL.map { it.title to PermissionCenter.granted(ctx, it.id) } + runtime.map { it.first to granted(ctx, it.second) } +
            listOf("Install updates" to ctx.packageManager.canRequestPackageInstalls())
        part("Permissions", listOf("on: " + perms.filter { it.second }.joinToString(", ") { it.first }.ifEmpty { "none" },
            "off: " + perms.filter { !it.second }.joinToString(", ") { it.first }.ifEmpty { "none" }))
        val now = System.currentTimeMillis()
        part("AI services and keys", Providers.all.mapNotNull { p ->
            val keys = try { Providers.keys(ctx, p.id) } catch (_: Exception) { return@mapNotNull null }
            if (keys.isEmpty()) return@mapNotNull null
            val ready = keys.count { it.usable(now) }; val bad = keys.count { it.bad }
            val resting = keys.filter { !it.bad && it.restUntil > now }
            "${p.name}: ${keys.size} key${if (keys.size == 1) "" else "s"}, $ready ready" +
                (if (resting.isNotEmpty()) ", ${resting.size} resting (out of quota or rate-limited) until ${timeFmt.format(Instant.ofEpochMilli(resting.minOf { it.restUntil }).atZone(Engine.zone))}" else "") +
                (if (bad > 0) ", $bad turned down (${keys.first { it.bad }.problem.take(60)})" else "")
        }.ifEmpty { listOf("no keys (only the offline brain)") } + if (KeyRouter.dontSwitch(ctx)) listOf("Don't Switch is on") else emptyList())
        val bots = withContext(Dispatchers.Main.immediate) { try { Bots.visible().map { Situation.BotView(it) } } catch (_: Exception) { emptyList() } }
        part("Bots", listOf("${bots.count { !it.final }} working, ${bots.count { it.final }} finished in the last week") + withContext(Dispatchers.Main.immediate) { Situation.standingLines() })
        try {
            val s = Sync.status(ctx)
            part("Sync and link", listOf(if (!s.linked) "not linked to another device" else "linked; last sync ${if (s.lastAt > 0) Link.ago(now - s.lastAt) else "not yet"}, ${s.pending} change${if (s.pending == 1) "" else "s"} waiting to send" + (s.problem?.let { "; problem: ${it.text}" } ?: "")) +
                s.others.map { "${it.name.ifEmpty { it.platform }} (${it.platform}): Atlas ${it.app.ifEmpty { "?" }}, seen ${Link.ago(now - it.seenMs - Sync.cloudSkew)}" + if (it.outdated) " (needs an update to sync everything)" else "" })
        } catch (_: Exception) {}
        part("Device and models", listOf(try { DeviceGuard.line(ctx) } catch (_: Exception) { "?" }))
        part("Settings", Self.describe(ctx).lines().map { it.removePrefix("- ") }.filter { it.isNotBlank() })
        val errors = withContext(Dispatchers.Main.immediate) {
            try { Conversation.messages.filter { it.source == "error" }.takeLast(3).map { "${Link.ago(now - it.at)}: ${it.text.take(140)}" } } catch (_: Exception) { emptyList() }
        } + bots.filter { it.final && it.error != null && now - it.updatedAt < 86_400_000L }.take(3).map { "bot “${it.goal.take(40)}”: ${it.error!!.take(120)}" }
        part("Recent errors", errors.ifEmpty { listOf("none") })
        return out.joinToString("\n")
    }

    // ------------------------------------------------------------------ the tools

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject()) = JSONObject().put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props))

    private val decls: JSONArray by lazy { JSONArray().apply {
        put(fn("about_atlas", "Your own version, what changed in each update (for the user and what you can now do), when you were updated, and which version the user's other device runs. Use it for \"what version are you\", \"what's new\", \"what can you do now\"."))
        put(fn("self_status", "A full check of yourself: abilities (tool groups), permissions on and off, AI services and keys (ready, resting, turned down), bots, sync and the link, the device and models, your settings, recent errors."))
        put(fn("whats_around", "Everything you can see around you right now: time, battery, network, sound mode, headphones, the app in front, notifications (count and apps), next alarm and calendar events, rough location (if allowed), storage, screen, temperature, the laptop."))
        put(fn("check_updates", "Look for a newer Atlas release now."))
        put(fn("update_atlas", "Download the newest Atlas in the background and open Android's installer (the user taps Install). Only when the user asks for the update."))
    } }

    val cap = Capabilities.Cap("self", { decls }, { ctx, c -> run(ctx, c) })

    private suspend fun run(ctx: Context, c: Gemini.Call): Tools.Result? = when (c.name) {
        "about_atlas" -> Tools.Result(withContext(Dispatchers.IO) { Updater.about(ctx) }, "Checked what's new in Atlas")
        "self_status" -> Tools.Result(status(ctx), "Checked myself")
        "whats_around" -> Tools.Result(Around.detail(withContext(Dispatchers.IO) { facts(ctx, full = true) }), "Looked around")
        "check_updates" -> Tools.Result(withContext(Dispatchers.IO) { Updater.check(ctx, force = true) }, "Checked for updates")
        "update_atlas" -> Tools.Result(withContext(Dispatchers.IO) { Updater.install(ctx) }, "Got the update ready")
        else -> null
    }
}
