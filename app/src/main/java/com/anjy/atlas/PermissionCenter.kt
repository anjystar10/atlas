package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay

/**
 * One place that asks for the permissions background work needs, and lets the task wait instead of failing.
 * While Atlas is in front: a small sheet explaining why, with a button to the right page. Otherwise: one notification
 * whose tap goes straight to that page (not repeated for 10 minutes). The task keeps waiting and carries on the
 * moment the permission is on. Me → Permissions shows them all.
 */
object PermissionCenter {
    class Perm(val id: String, val title: String, val why: String)

    val ALL = listOf(
        Perm("notifications", "Read and reply to notifications", "to see new messages and reply to them in the background, like a smartwatch, without opening the app"),
        Perm("sms", "Send text messages", "to send texts in the background (Atlas still asks you before each one)"),
        Perm("accessibility", "Phone Control", "to use apps on your screen when there's no background way (Atlas gives you a heads-up first)"),
        Perm("overlay", "Display over other apps", "to show the floating Atlas and the “using your screen” banner, and to reach other apps from the background"),
        Perm("exact_alarms", "Alarms and reminders", "to ring reminders and run routines at the exact time"),
        Perm("battery", "Run in the background", "so Android doesn't pause Atlas's bots and routines while you use other apps"),
        Perm("post_notifications", "Notifications", "to tell you when a bot needs you, has finished, or needs a permission"),
    )
    fun perm(id: String) = ALL.firstOrNull { it.id == id }

    private const val PREFS = "atlas_perms"
    private const val CH = "atlas_perm_ask"
    @Volatile var atlasInFront = false   // MainActivity / other Atlas screens set this in onResume/onPause

    fun granted(ctx: Context, id: String): Boolean = when (id) {
        "notifications" -> try { ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(ctx, AtlasNotifs::class.java)) } catch (_: Exception) { false }
        "sms" -> ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        "accessibility" -> PhoneControl.on()
        "overlay" -> Settings.canDrawOverlays(ctx)
        "exact_alarms" -> try { ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() } catch (_: Exception) { true }
        "battery" -> try { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) } catch (_: Exception) { false }
        "post_notifications" -> ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else -> false
    }

    /** The page (or Android's own question) that turns [id] on. Runtime permissions go through PermissionActivity. */
    fun settingsIntent(ctx: Context, id: String): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (id) {
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, AtlasNotifs::class.java).flattenToString())
            "sms" -> Intent(ctx, PermissionActivity::class.java).putExtra(PermissionActivity.EXTRA_PERMS, arrayOf(Manifest.permission.SEND_SMS))
                .putExtra(PermissionActivity.EXTRA_WHY, "send text messages")
            "post_notifications" -> Intent(ctx, PermissionActivity::class.java).putExtra(PermissionActivity.EXTRA_PERMS, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                .putExtra(PermissionActivity.EXTRA_WHY, "show notifications")
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "exact_alarms" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the page for [id] now (from a button the user tapped). */
    fun open(ctx: Context, id: String) {
        try { ctx.startActivity(settingsIntent(ctx, id)) }
        catch (_: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    }

    internal fun declined(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("declined_$id", System.currentTimeMillis()).apply()

    /**
     * Makes sure [id] is on: asks (sheet or notification, per PermFlow) and waits up to [waitMs] for the user to turn
     * it on. True when it's on. Never blocks the main thread (call from a coroutine).
     */
    suspend fun ensure(ctx: Context, id: String, waitMs: Long): Boolean {
        if (granted(ctx, id)) return true
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        when (PermFlow.next(false, atlasInFront, p.getLong("asked_$id", 0), p.getLong("declined_$id", 0), now)) {
            PermFlow.Step.PROCEED -> return true
            PermFlow.Step.ASK_ON_SCREEN -> { p.edit().putLong("asked_$id", now).apply()
                try { app.startActivity(Intent(app, PermissionAskActivity::class.java).putExtra(PermissionAskActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
            PermFlow.Step.ASK_BY_NOTIFICATION -> { p.edit().putLong("asked_$id", now).apply(); notifyAsk(app, id) }
            PermFlow.Step.WAIT -> {}
        }
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            delay(1000)
            if (granted(app, id)) { cancelAsk(app, id); return true }
        }
        return false
    }

    /** What to tell the model when [id] still isn't on after waiting. */
    fun stillNeeded(id: String): String {
        val pm = perm(id)
        return "Atlas still isn't allowed to ${pm?.title?.lowercase() ?: id} (needed ${pm?.why ?: ""}). The user has been asked by notification; " +
            "tell them in one line and carry on with what you can, or try again after they allow it (Me → Permissions)."
    }

    private fun nid(id: String) = 7400 + (ALL.indexOfFirst { it.id == id }.coerceAtLeast(0))

    private fun notifyAsk(ctx: Context, id: String) {
        val pm = perm(id) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Permission requests", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, nid(id), settingsIntent(ctx, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Atlas needs your OK: ${pm.title}")
            .setContentText("Tap to allow it ${pm.why}.")
            .setStyle(android.app.Notification.BigTextStyle().bigText("Tap to allow it ${pm.why}. What Atlas was doing waits and carries on as soon as it's allowed."))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(nid(id), n) } catch (_: SecurityException) {}
    }
    private fun cancelAsk(ctx: Context, id: String) = try { ctx.getSystemService(NotificationManager::class.java).cancel(nid(id)) } catch (_: Exception) {}
}

/** The in-app sheet: why Atlas needs a permission, with one button to the page that turns it on. */
class PermissionAskActivity : Activity() {
    private val P = Palette.NIGHT
    companion object { const val EXTRA_ID = "perm_id" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val pm = PermissionCenter.perm(id) ?: return finish()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(20)); setBackgroundColor(P.surface)
        }
        box.addView(TextView(this).apply { text = pm.title; textSize = 22f; setTextColor(P.fg); typeface = Type.serifMedium(this@PermissionAskActivity) })
        box.addView(TextView(this).apply { text = "Atlas needs this ${pm.why}. What it was doing waits until you decide."; textSize = 16f; setTextColor(P.muted)
            setPadding(0, dp(10), 0, dp(18)); typeface = Type.serif(this@PermissionAskActivity) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(TextView(this).apply { text = "Not now"; textSize = 15f; setTextColor(P.muted); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { PermissionCenter.declined(this@PermissionAskActivity, id); finish() } })
        row.addView(TextView(this).apply { text = "Allow"; textSize = 15f; setTextColor(P.accentInk); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { PermissionCenter.open(this@PermissionAskActivity, id); finish() } })
        box.addView(row)
        setContentView(box)
        window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** Me → Permissions: every permission background work can use, whether it's on, and a button to turn it on. */
class PermissionsActivity : Activity() {
    private val P = Palette.NIGHT
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val scroll = android.widget.ScrollView(this).apply { setBackgroundColor(P.bg); isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        window?.statusBarColor = P.bg
    }
    override fun onResume() { super.onResume(); PermissionCenter.atlasInFront = true; render() }
    override fun onPause() { PermissionCenter.atlasInFront = false; super.onPause() }

    private fun render() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Permissions"; textSize = 30f; setTextColor(P.fg); typeface = Type.serif(this@PermissionsActivity) })
        // >>> autonomy: how freely Atlas acts, standing permissions and what it did on its own (AutonomyUi below)
        AutonomyUi.render(this, list, P) { render() }
        list.addView(TextView(this).apply { text = "Phone Access"; textSize = 22f; setTextColor(P.fg); typeface = Type.serif(this@PermissionsActivity); setPadding(0, dp(18), 0, 0) })
        // <<< autonomy
        list.addView(TextView(this).apply { text = "What lets Atlas work in the background while you use your phone. Atlas asks when a job needs one; you can turn them on here too."
            textSize = 16f; setTextColor(P.muted); setPadding(0, dp(8), 0, dp(18)); typeface = Type.serif(this@PermissionsActivity) })
        for (p in PermissionCenter.ALL) {
            val on = PermissionCenter.granted(this, p.id)
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(P.surface); setStroke(dp(1), P.line) } }
            card.addView(TextView(this).apply { text = p.title; textSize = 17f; setTextColor(P.fg); typeface = Type.serifMedium(this@PermissionsActivity) })
            card.addView(TextView(this).apply { text = "Used ${p.why}."; textSize = 14f; setTextColor(P.muted); setPadding(0, dp(4), 0, dp(8)) })
            card.addView(TextView(this).apply {
                text = if (on) "On" else "Turn On ›"; textSize = 14f; setTextColor(if (on) P.done else P.accentInk)
                if (!on) setOnClickListener { PermissionCenter.open(this@PermissionsActivity, p.id) }
            })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }
}

/**
 * >>> autonomy: Me → Permissions, top section. Every change here is the user's own tap. Same glass cards as the rest
 * of the page (Palette tokens), so it follows the theme.
 */
object AutonomyUi {
    fun render(a: Activity, list: LinearLayout, P: Palette, again: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun card() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(P.surface); setStroke(dp(1), P.line) } }
        fun lp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        BotExtras.init(a)
        list.addView(TextView(a).apply { text = "How Freely Atlas Acts"; textSize = 22f; setTextColor(P.fg); typeface = Type.serif(a); setPadding(0, dp(14), 0, 0) })
        list.addView(TextView(a).apply { text = "Money, deleting, messaging someone new and security changes always ask, whatever you pick."
            textSize = 15f; setTextColor(P.muted); setPadding(0, dp(6), 0, dp(12)); typeface = Type.serif(a) })
        val now = Autonomy.level(a)
        for (lv in AutonomyRules.LEVELS) {
            val c = card()
            val on = lv == now
            c.addView(TextView(a).apply { text = (if (on) "● " else "○ ") + lv.replaceFirstChar { it.uppercase() } + if (lv == AutonomyRules.DEFAULT_LEVEL) " (recommended)" else ""
                textSize = 17f; setTextColor(if (on) P.accentInk else P.fg); typeface = Type.serifMedium(a) })
            c.addView(TextView(a).apply { text = AutonomyRules.LEVEL_LABELS[lv]?.substringAfter(": ") ?: ""; textSize = 14f; setTextColor(P.muted); setPadding(0, dp(4), 0, 0) })
            c.minimumHeight = dp(44)
            c.setOnClickListener { Autonomy.setLevel(a, lv, byTap = true); again() }
            list.addView(c, lp())
        }
        val grants = Autonomy.grants(a)
        if (grants.isNotEmpty()) {
            list.addView(TextView(a).apply { text = "Allowed Without Asking"; textSize = 17f; setTextColor(P.fg); typeface = Type.serifMedium(a); setPadding(0, dp(10), 0, dp(6)) })
            grants.forEachIndexed { i, g ->
                val c = card()
                c.addView(TextView(a).apply { text = AutonomyRules.grantText(g) { id -> BotExtras.specialists.toList().firstOrNull { it.optString("id") == id }?.optString("name") ?: "a bot" }; textSize = 15f; setTextColor(P.fg) })
                c.addView(TextView(a).apply { text = "Ask Again ›"; textSize = 14f; setTextColor(P.accentInk); setPadding(0, dp(8), 0, 0); minHeight = dp(44); gravity = android.view.Gravity.CENTER_VERTICAL
                    setOnClickListener { Autonomy.revoke(a, i); again() } })
                list.addView(c, lp())
            }
        }
        val recent = Autonomy.recent(a).takeLast(8).reversed()
        if (recent.isNotEmpty()) {
            list.addView(TextView(a).apply { text = "Done Without Asking"; textSize = 17f; setTextColor(P.fg); typeface = Type.serifMedium(a); setPadding(0, dp(10), 0, dp(6)) })
            val c = card()
            val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
            recent.forEach { r -> c.addView(TextView(a).apply { text = "${fmt.format(java.util.Date(r.optLong("at")))} · ${r.optString("what")}"; textSize = 14f; setTextColor(P.muted); setPadding(0, dp(3), 0, dp(3)) }) }
            list.addView(c, lp())
        }
    }
}
