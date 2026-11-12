package com.anjy.atlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps the Device Link listening while Atlas is closed: when "Reachable from your laptop" is on, or while the laptop
 * is controlling this phone. Its notification says which, and has Stop while the phone is being controlled.
 */
class LinkService : Service() {
    companion object {
        private const val CH = "atlas_link_live"
        private const val ID = 77_002
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) } catch (e: Exception) { Log.w("AtlasLink", "service not started", e) }
        }
        /** Updates the notification, or stops when there's nothing to keep listening for. */
        fun refresh(ctx: Context) { if (Link.reachable(ctx) || Remote.controlled()) start(ctx) else ctx.stopService(Intent(ctx, LinkService::class.java)) }
        fun stopIfIdle(ctx: Context) = refresh(ctx)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Link.init(applicationContext)
        Bots.init(applicationContext); LinkTasks.watchJobs()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Device link", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows while your laptop can reach or is controlling this phone" })
        val who = Remote.who()
        val open = PendingIntent.getActivity(this, 7702, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
        if (who.isNotEmpty()) {
            val stop = PendingIntent.getBroadcast(this, 7703, Intent(this, LinkReceiver::class.java).setAction(LinkReceiver.STOP), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle("${who.joinToString(", ")} is controlling this phone").setContentText("Tap Stop to end it now.")
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setColorized(true).setColor(Palette.NIGHT.warm)
        } else b.setContentTitle("Reachable from your laptop").setContentText("Your laptop can send Atlas tasks and ask to control this phone. Turn off in Me → Your Devices.")
        try { startForeground(ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        catch (e: Exception) { Log.w("AtlasLink", "startForeground", e); stopSelf(); return START_NOT_STICKY }
        if (!Link.reachable(this) && !Remote.controlled()) { stopSelf(); return START_NOT_STICKY }
        Link.ensure()
        return START_STICKY
    }
}
