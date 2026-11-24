package com.anjy.atlas

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import com.anjy.atlas.core.Engine
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Timed entries in the Log, at their time: important ones ring an alarm with a pop-up asking whether you'll do it
 * (Doing it now / I'll do it / Snooze / Not doing it); lighter ones send a notification with a sound and the same
 * choices as buttons. Nothing else: no check-ins, no follow-ups.
 * Scheduled with the system's alarm clock, which phones (OPPO included) keep even under battery saving.
 */
object Reminders {
    private const val TAG = "AtlasAlarms"
    const val CH_ALARM = "atlas_alarm"
    const val CH_AVATAR = "atlas_avatar"
    const val CH_PING = "atlas_ping"
    const val ACTION_NOW = "com.anjy.atlas.PING_NOW"
    const val ACTION_SNOOZE = "com.anjy.atlas.PING_SNOOZE"
    const val ACTION_SKIP = "com.anjy.atlas.PING_SKIP"
    const val ACTION_DONE = "com.anjy.atlas.PING_DONE"
    const val ACTION_TICK = "com.anjy.atlas.TICK"
    /** An alarm that comes this late (the phone was off) is logged as missed rather than rung. */
    private const val STALE_MS = 6 * 60 * 60 * 1000L
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun fmt(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Engine.zone).format(hhmm)

    @Volatile private var channelsReady = false

    /**
     * The notification channels, set up once per run of the app. Every call here is a round trip to Android, and
     * services call this on the main thread as they start, so repeating it each time froze the screen on a busy phone.
     */
    fun channels(ctx: Context) {
        if (channelsReady) return
        synchronized(this) { if (channelsReady) return; makeChannels(ctx); channelsReady = true }
    }

    private fun makeChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // the old reminder channels go (once: a delete is a slow call into Android, and they only need removing once)
        val prefs = ctx.getSharedPreferences("atlas_channels", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("old_gone", false)) {
            listOf("atlas_reminders", "atlas_quiet").forEach { try { nm.deleteNotificationChannel(it) } catch (_: Exception) {} }
            prefs.edit().putBoolean("old_gone", true).apply()
        }
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings at the time of things you asked Atlas to remind you about"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 400, 600)
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_PING, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A notification with a sound for lighter reminders"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
        Focus.channel(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_AVATAR, "Atlas on screen", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Atlas floats on screen or listens for “Hey Atlas”"
        })
    }

    /** Ring anything that's due now, then set the system alarm for the next one. Safe to call any time, off the main thread. */
    fun tickAndSchedule(ctx: Context) {
        val now = System.currentTimeMillis()
        val due = mutableListOf<String>()
        val next = Store.edit(ctx) { s ->
            s.tasks.filter { it.status == "planned" && it.remindedAt == null && it.startAt != null && it.startAt!! <= now + 30_000 }.forEach { t ->
                t.remindedAt = now
                if (now - t.startAt!! <= STALE_MS) due.add(t.id)
                else s.log.add(com.anjy.atlas.core.LogEntry(java.util.UUID.randomUUID().toString(), now, "note", t.id, t.title, "missed: the phone was off at its time"))
                // a repeating one comes round again even if this one is never answered (before, only Done or Skip made the next)
                Engine.continueSeries(s, t, now)
            }
            s.tasks.filter { it.status == "planned" && it.remindedAt == null && it.startAt != null && it.startAt!! > now }.minOfOrNull { it.startAt!! }
        }
        if (due.isNotEmpty()) {
            val important = Store.read(ctx) { s -> due.filter { id -> s.tasks.firstOrNull { it.id == id }?.priority == "high" } }
            if (important.isNotEmpty()) ring(ctx, important)
            (due - important.toSet()).forEach { ping(ctx, it) }
        }
        schedule(ctx, next)
    }

    private fun schedule(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) { am.cancel(pi); return }
        try {
            val show = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("tab", "log"), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarms not allowed; using an inexact one", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** A lighter reminder: a notification with a sound, and the same choices as the alarm as buttons. */
    private fun ping(ctx: Context, id: String) {
        val t = Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == id }?.let { it.title to it.durationMin } } ?: return
        val title = Plain.memory(t.first)
        val code = id.hashCode()
        fun act(action: String) = PendingIntent.getBroadcast(ctx, code + action.hashCode(), Intent(ctx, PingReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // "Doing it now" opens the focus screen straight from the notification (Android doesn't let a receiver open screens)
        val now = PendingIntent.getActivity(ctx, code + 1, Intent(ctx, FocusActivity::class.java).setAction(ACTION_NOW).putExtra("id", id).putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, code + 2, Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmActivity.EXTRA_IDS, arrayOf(id)).putExtra(AlarmActivity.EXTRA_QUIET, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_PING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText("It's time. Will you do it?")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Doing it now", now).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", act(ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "Skip", act(ACTION_SKIP)).build())
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(code, n) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
    }

    /** The alarm itself: the pop-up with sound, and a notification that opens it again if it's dismissed. */
    private fun ring(ctx: Context, ids: List<String>) {
        val open = Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmActivity.EXTRA_IDS, ids.toTypedArray())
        val pi = PendingIntent.getActivity(ctx, ids.first().hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = Plain.memory(Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == ids.first() }?.title } ?: "Reminder")
        val n = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (ids.size > 1) "and ${ids.size - 1} more. Will you do it?" else "It's time. Will you do it?")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(AlarmActivity.NOTIF_ID, n) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
        // with "display over other apps" Atlas may open its pop-up straight away
        try { ctx.startActivity(open) } catch (e: Exception) { Log.w(TAG, "couldn't open the alarm directly; the notification will", e) }
    }
}

/** The buttons on a reminder notification. */
class PingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                Store.edit(context.applicationContext) { s ->
                    if (s.tasks.none { it.id == id && it.status in Engine.OPEN }) return@edit
                    when (intent.action) {
                        Reminders.ACTION_SNOOZE -> Engine.respond(s, id, "delay", now, startAt = now + 10 * Engine.MIN)
                        Reminders.ACTION_SKIP -> Engine.respond(s, id, "skipped", now, reason = "Skipped from the notification")
                        Reminders.ACTION_DONE -> Engine.respond(s, id, "done", now)
                        else -> {}
                    }
                }
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                Reminders.tickAndSchedule(context.applicationContext)
            } catch (e: Exception) { Log.e("AtlasAlarms", "reminder button", e) }
            finally { pending.finish() }
        }.start()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Reminders.tickAndSchedule(context.applicationContext) } catch (e: Exception) { Log.e("AtlasAlarms", "tick failed", e) }
            finally { pending.finish() }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Engine.zone = java.time.ZoneId.systemDefault()
                Reminders.channels(context)
                Reminders.tickAndSchedule(context.applicationContext)
                BotExtras.tick(context.applicationContext)   // routines: set the next alarm, and run any missed while off
                AvatarService.startIfEnabled(context)
                if (Link.reachable(context)) LinkService.start(context)   // >>> device link: reachable from the laptop after a restart
                // Bots v2: jobs that were running when the phone switched off pick up again (a half-done step is marked
                // "needs a check", never repeated blindly). Jobs live on the main thread, so load them there.
                val app = context.applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { Bots.init(app); BotService.sync(app) } catch (e: Exception) { Log.e("AtlasBots", "boot resume", e) }
                }
            } catch (e: Exception) { Log.e("AtlasAlarms", "boot", e) }
            finally { pending.finish() }
        }.start()
    }
}
