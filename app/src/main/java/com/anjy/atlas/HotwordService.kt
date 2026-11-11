package com.anjy.atlas

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * "Hey Atlas", in a process of its own (":hotword"). If the speech engine crashes, only this process dies: Atlas
 * keeps running. Android only lets an app start using the microphone while it's on screen (or from a tap on its
 * notification), so a crashed listener can't restart itself in a loop. After two crashes in ten minutes it stays
 * off and says why in Atlas → Me.
 *
 * This process must not touch Atlas's stored data (that lives in the main process): it only reads its own files.
 */
class HotwordService : Service() {
    private var hotword: Hotword? = null
    private var held = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Reminders.channels(this)
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // not allowed to use the microphone right now (Atlas isn't on screen): wait to be started properly
            Log.w(TAG, "microphone not allowed yet", e)
            stopSelf(); return
        }
        val crashes = recentCrashes(this)
        if (crashes.size >= 2) {
            File(filesDir, OFF_FILE).writeText(crashes.first())
            Log.e(TAG, "speech engine crashed twice; staying off: ${crashes.first()}")
            stopSelf(); return
        }
        hotword = Hotword(applicationContext) { wake() }
        hotword?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HOLD -> { held = true; hotword?.pause() }
            ACTION_RELEASE -> { held = false; hotword?.start() }
            ACTION_STOP -> { hotword?.stop(); stopSelf() }
            else -> if (!held) hotword?.start()
        }
        return START_STICKY
    }

    private fun wake() {
        held = true
        // the floating Atlas grows and listens; without it, the panel opens
        try { startService(Intent(this, AvatarService::class.java).setAction(AvatarService.ACTION_WAKE)) }
        catch (e: Exception) {
            Log.w(TAG, "couldn't reach Atlas on screen; opening the panel", e)
            try { startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("listen", true)) }
            catch (e2: Exception) { held = false; hotword?.start() }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, Reminders.CH_AVATAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Listening for “Hey Atlas”")
            .setContentText("Heard on the phone only. Turn it off in Atlas → Me.")
            .setContentIntent(open).setOngoing(true).build()
    }

    override fun onDestroy() { hotword?.stop(); hotword = null; super.onDestroy() }

    companion object {
        private const val TAG = "AtlasHotwordProc"
        private const val ID = 8
        const val OFF_FILE = "hotword-off.txt"
        const val ACTION_HOLD = "com.anjy.atlas.HOTWORD_HOLD"
        const val ACTION_RELEASE = "com.anjy.atlas.HOTWORD_RELEASE"
        const val ACTION_STOP = "com.anjy.atlas.HOTWORD_STOP"

        /** Native crashes of the listener's process in the last ten minutes, newest first, as one-line descriptions. */
        fun recentCrashes(ctx: Context): List<String> {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val since = System.currentTimeMillis() - 10 * 60 * 1000L
            return try {
                am.getHistoricalProcessExitReasons(ctx.packageName, 0, 20)
                    .filter { it.processName.endsWith(":hotword") && it.timestamp > since &&
                        (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH) }
                    .map { "${Diagnostics.reasonName(it.reason)}: ${it.description ?: "no details"}" }
            } catch (e: Exception) { emptyList() }
        }

        /** Why Hey Atlas switched itself off, if it did. Read (and cleared) by the main process. */
        fun offReason(ctx: Context): String? = File(ctx.filesDir, OFF_FILE).takeIf { it.exists() }?.readText()
        fun clearOff(ctx: Context) { File(ctx.filesDir, OFF_FILE).delete() }

        /** Start listening. Only works while Atlas is on screen or from its notification (Android's rule). */
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, HotwordService::class.java)); maybeRunning = true } catch (e: Exception) { Log.w(TAG, "start refused", e) }
        }
        /**
         * Whether the listener might be running. Starts true (after a restart it may still be up from before), then
         * tracks our own start/stop, so the frequent "stop" calls don't each make a slow call into Android
         * (the emulator test caught one freezing the screen).
         */
        @Volatile private var maybeRunning = true
        fun stop(ctx: Context) {
            if (!maybeRunning) return
            maybeRunning = false
            try { ctx.stopService(Intent(ctx, HotwordService::class.java)) } catch (_: Exception) {}
        }
        /** Atlas is listening itself (or done): the listener lets go of the microphone meanwhile. */
        fun hold(ctx: Context, hold: Boolean) {
            try { ctx.startService(Intent(ctx, HotwordService::class.java).setAction(if (hold) ACTION_HOLD else ACTION_RELEASE)) } catch (_: Exception) {}
        }
        fun running(ctx: Context): Boolean = try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(ActivityManager::class.java).getRunningServices(50).any { it.service.className == HotwordService::class.java.name }
        } catch (_: Exception) { false }
    }
}

/** What Me → Diagnostics shows: how Atlas's processes last ended, so a crash can be reported precisely. */
object Diagnostics {
    fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by you"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too much battery/CPU"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a part it needed stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
        else -> "reason $r"
    }

    /** The last few times any Atlas process ended, newest first, plus the start of a native crash report. */
    fun report(ctx: Context, max: Int = 8): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
        val list = try { am.getHistoricalProcessExitReasons(ctx.packageName, 0, max) } catch (e: Exception) { return "Couldn't read: ${e.message}" }
        val sb = StringBuilder()
        sb.append("Orb: ").append(OrbView.gpuProblem?.let { "drawn by the processor (graphics chip: $it)" } ?: "graphics chip").append('\n')
        if (list.isEmpty()) return sb.append("No exits recorded.").toString()
        list.forEach { e ->
            val at = java.time.Instant.ofEpochMilli(e.timestamp).atZone(java.time.ZoneId.systemDefault()).format(fmt)
            val proc = e.processName.substringAfter(':', "app")
            sb.append("$at · $proc · ${reasonName(e.reason)}")
            e.description?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it.take(160)) }
            sb.append('\n')
            if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_CRASH) {
                try {
                    e.traceInputStream?.use { s ->
                        val head = String(s.readNBytes(6000), Charsets.UTF_8).lines()
                            .filter { it.contains("signal") || it.contains("Abort") || it.contains("backtrace") || it.trimStart().startsWith("#0") || it.contains("Exception") || it.contains("at ") }
                            .take(8)
                        head.forEach { sb.append("    ").append(it.trim().take(180)).append('\n') }
                    }
                } catch (_: Exception) {}
            }
        }
        return sb.toString().trim()
    }
}
