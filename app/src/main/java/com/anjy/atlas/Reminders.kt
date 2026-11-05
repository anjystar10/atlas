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
 * Timed entries in the Log ring like an alarm: at their time an alarm sounds and a pop-up asks whether you'll do it
 * (I'll do it / Snooze 10 min / Not doing it). Nothing else: no check-ins, no follow-ups.
 * Scheduled with the system's alarm clock, which phones (OPPO included) keep even under battery saving.
 */
object Reminders {
    private const val TAG = "AtlasAlarms"
    const val CH_ALARM = "atlas_alarm"
    const val CH_AVATAR = "atlas_avatar"
    const val ACTION_TICK = "com.anjy.atlas.TICK"
    /** An alarm that comes this late (the phone was off) is logged as missed rather than rung. */
    private const val STALE_MS = 6 * 60 * 60 * 1000L
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun fmt(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Engine.zone).format(hhmm)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // the old reminder channels go
        listOf("atlas_reminders", "atlas_quiet").forEach { try { nm.deleteNotificationChannel(it) } catch (_: Exception) {} }
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings at the time of things you asked Atlas to remind you about"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 400, 600)
            setBypassDnd(true)
        })
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
            }
            s.tasks.filter { it.status == "planned" && it.remindedAt == null && it.startAt != null && it.startAt!! > now }.minOfOrNull { it.startAt!! }
        }
        if (due.isNotEmpty()) ring(ctx, due)
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

    /** The alarm itself: the pop-up with sound, and a notification that opens it again if it's dismissed. */
    private fun ring(ctx: Context, ids: List<String>) {
        val open = Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmActivity.EXTRA_IDS, ids.toTypedArray())
        val pi = PendingIntent.getActivity(ctx, ids.first().hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == ids.first() }?.title } ?: "Reminder"
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
                AvatarService.startIfEnabled(context)
            } catch (e: Exception) { Log.e("AtlasAlarms", "boot", e) }
            finally { pending.finish() }
        }.start()
    }
}
