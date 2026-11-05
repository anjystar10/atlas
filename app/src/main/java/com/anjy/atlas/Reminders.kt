package com.anjy.atlas

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.util.Log
import com.anjy.atlas.core.Engine
import com.anjy.atlas.core.Event
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Deterministic reminder engine glue: one exact system alarm, always set to the engine's next wake time.
 * State lives in [Store]; the alarm is rebuilt from it after every change, on boot, and on clock changes.
 */
object Reminders {
    private const val TAG = "AtlasReminders"
    const val CH_REMIND = "atlas_reminders"
    const val CH_AVATAR = "atlas_avatar"
    const val CH_QUIET = "atlas_quiet"
    const val ACTION_TICK = "com.anjy.atlas.TICK"
    const val ACTION_DONE = "com.anjy.atlas.DONE"
    const val ACTION_PROGRESS = "com.anjy.atlas.PROGRESS"
    const val EXTRA_ID = "taskId"

    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    fun fmt(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Engine.zone).format(hhmm)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Reminders and check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task starts, check-ins and follow-ups"
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Reminders during quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Delivered silently so nothing is lost while you're not to be disturbed"
        })
        nm.createNotificationChannel(NotificationChannel(CH_AVATAR, "Avatar", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the on-screen avatar running"
        })
    }

    /** Advance the engine to now, notify, and set the next alarm. Safe to call any time. */
    fun tickAndSchedule(ctx: Context) {
        val now = System.currentTimeMillis()
        val (events, wake) = Store.edit(ctx) { s -> Pair(Engine.tick(s, now), Engine.nextWake(s, now)) }
        notifyEvents(ctx, events)
        schedule(ctx, wake)
        AvatarService.refresh(ctx)
    }

    private fun schedule(ctx: Context, wake: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (wake == null) return
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake, pi)
        }
    }

    private fun notifyEvents(ctx: Context, events: List<Event>) {
        val latest = LinkedHashMap<String, Event>()
        events.forEach { latest[it.task.id] = it }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        for (e in latest.values) {
            // During quiet hours nothing is dropped: it arrives silently instead.
            val t = e.task
            val (title, body) = when (e.type) {
                "remind" -> Pair(if (e.late) "Was due at ${fmt(t.startAt ?: 0)}" else "Starting now", "${t.title} · ${t.durationMin} min")
                "checkin" -> Pair("Check-in", "How did “${t.title}” go?")
                else -> Pair("Still waiting on you", "“${t.title}” needs an answer.")
            }
            val code = t.id.hashCode()
            val open = PendingIntent.getActivity(
                ctx, code, Intent(ctx, MainActivity::class.java).putExtra("tab", "today").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = Notification.Builder(ctx, if (e.quiet) CH_QUIET else CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
            if (e.type != "remind") {
                b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Done", actionIntent(ctx, ACTION_DONE, t.id, code)).build())
                b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Still working · 15 min", actionIntent(ctx, ACTION_PROGRESS, t.id, code + 1)).build())
                b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Other answer", open).build())
            }
            try { nm.notify(code, b.build()) } catch (se: SecurityException) { Log.w(TAG, "Notification permission missing", se) }
        }
    }

    private fun actionIntent(ctx: Context, action: String, id: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Reminders.tickAndSchedule(context.applicationContext) } finally { pending.finish() }
        }.start()
    }
}

/** Rebuilds the alarm after a reboot, an app update, or a clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Engine.zone = java.time.ZoneId.systemDefault()
                Reminders.tickAndSchedule(app)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    AvatarService.startIfEnabled(app)
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Handles Done / Still working buttons on check-in notifications without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                try {
                    Store.edit(app) { s ->
                        when (intent.action) {
                            Reminders.ACTION_DONE -> Engine.respond(s, id, "done", now)
                            Reminders.ACTION_PROGRESS -> Engine.respond(s, id, "progress", now, minutes = 15)
                            else -> null
                        }
                    }
                } catch (e: Exception) { Log.w("AtlasAction", "Action failed", e) }
                app.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                Reminders.tickAndSchedule(app)
            } finally { pending.finish() }
        }.start()
    }
}
