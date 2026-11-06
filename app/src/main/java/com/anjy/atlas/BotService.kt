package com.anjy.atlas

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps Atlas running while a bot works (Android may close an app in the background otherwise), with a quiet
 * notification saying what it's doing and Pause / Stop keys. It stops itself when no bot is working.
 * The work itself happens in [Bots]; this only holds the door open.
 */
class BotService : Service() {
    companion object {
        private const val TAG = "AtlasBotService"
        const val NOTIF_ID = 5099
        @Volatile private var running = false
        private var lastText: String? = null

        /** Starts or stops the service to match whether any bot is working, and refreshes its notification. Main thread. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            if (Bots.active()) {
                if (!running) {
                    try { app.startForegroundService(Intent(app, BotService::class.java)) }
                    catch (e: Exception) { Log.w(TAG, "can't start in the background; the bot carries on while Atlas is open", e) }
                } else update(app)
            } else if (running) app.stopService(Intent(app, BotService::class.java))
        }

        private fun build(ctx: Context): Notification {
            Bots.channels(ctx)
            val working = Bots.jobs.filter { it.state == BotRules.RUNNING || it.state == BotRules.QUEUED || it.state == BotRules.WAITING_RETRY }
            val j = working.firstOrNull { it.state == BotRules.RUNNING } ?: working.firstOrNull()
            val title = when {
                j == null -> "Atlas Bot"
                working.size > 1 -> "Atlas Bot: ${working.size} jobs"
                else -> "Atlas Bot: ${j.goal}"
            }
            val text = when {
                j == null -> "Finishing up"
                j.state == BotRules.WAITING_RETRY -> "Waiting to try again"
                j.state == BotRules.QUEUED -> "Waiting to start"
                else -> j.current ?: "Working"
            }
            val b = Notification.Builder(ctx, Bots.CH_BOT).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setOnlyAlertOnce(true)
                .setContentTitle(title.take(80)).setContentText(text.take(120))
                .setContentIntent(Bots.openIntent(ctx, if (working.size == 1) j else null, NOTIF_ID))
            if (j != null) {
                b.addAction(Notification.Action.Builder(null, "Pause", Bots.actionIntent(ctx, j, BotReceiver.ACTION_PAUSE)).build())
                b.addAction(Notification.Action.Builder(null, "Stop", Bots.actionIntent(ctx, j, BotReceiver.ACTION_STOP)).build())
            }
            return b.build()
        }

        private fun update(ctx: Context) {
            val n = build(ctx)
            val key = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + "|" + n.extras.getCharSequence(Notification.EXTRA_TEXT)
            if (key == lastText) return
            lastText = key
            try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Bots.init(this)
        // Android requires the notification within seconds of starting, even if there's nothing left to do
        try { startForeground(NOTIF_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        catch (e: Exception) { Log.w(TAG, "startForeground", e); stopSelf(); return START_NOT_STICKY }
        running = true; lastText = null
        if (!Bots.active()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() { running = false; lastText = null; super.onDestroy() }
}

/** The keys on Atlas Bot's notifications: Approve, Deny, Pause, Stop. */
class BotReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.anjy.atlas.bot.APPROVE"
        const val ACTION_DENY = "com.anjy.atlas.bot.DENY"
        const val ACTION_PAUSE = "com.anjy.atlas.bot.PAUSE"
        const val ACTION_STOP = "com.anjy.atlas.bot.STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(BotActivity.EXTRA_ID) ?: return
        Bots.init(ctx)
        when (intent.action) {
            ACTION_APPROVE -> Bots.approve(id, true)
            ACTION_DENY -> Bots.approve(id, false)
            ACTION_PAUSE -> Bots.pause(id)
            ACTION_STOP -> Bots.stop(id)
        }
    }
}
