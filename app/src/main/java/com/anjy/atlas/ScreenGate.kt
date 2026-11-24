package com.anjy.atlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The screen as a last resort. Before Atlas drives another app on the user's screen it gives a heads-up the user
 * answers (bots: their approval card; the chat while the user is elsewhere: a notification with Go Ahead / Not Now).
 * While it's using the screen a small "Atlas is using your screen — Stop" banner shows, and when it's done the user
 * is put back where they were.
 */
object ScreenGate {
    /** Who's calling the screen tools: a bot job (its id) or the chat (null). Set by Bots.runTool. */
    class Who(val jobId: String?) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Who> }

    /** The tools that drive the screen (reading it is fine: it's the user's own screen and changes nothing). */
    val driving = setOf("tap", "long_press", "type_text", "scroll", "press_key", "swipe")

    private const val SESSION_MS = 60_000L   // a heads-up covers screen work until it's been quiet this long
    private const val END_AFTER_MS = 25_000L // when nothing has touched the screen this long, Atlas hands it back
    private const val CH = "atlas_screen"
    private const val NID = 7460
    private const val ACTION_STOP = "com.anjy.atlas.SCREEN_STOP"
    private const val ACTION_OK = "com.anjy.atlas.SCREEN_OK"
    private const val ACTION_NO = "com.anjy.atlas.SCREEN_NO"

    @Volatile private var lastTouch = 0L
    @Volatile private var owner: String? = null     // "chat" or a job id
    /** Who the user stopped (RouteRules.StopMark). */
    val stops = RouteRules.StopMark()
    @Volatile private var returnTo: String? = null  // the app the user was in when Atlas took the screen
    @Volatile private var operated: String? = null  // the app Atlas last drove
    @Volatile private var answer: CompletableDeferred<Boolean>? = null
    private var banner: View? = null
    private val main = Handler(Looper.getMainLooper())

    fun active(key: String): Boolean = stops.key != key && owner == key && System.currentTimeMillis() - lastTouch < SESSION_MS

    /** For bots: the heads-up to ask as an approval before this screen action, or null (no heads-up needed). */
    fun botHeadsUp(ctx: Context, jobId: String, tool: String, args: JSONObject): String? {
        if (tool == "open_app") {
            if (!args.optBoolean("use_screen")) return null   // Tools.run first points the bot at the background ways
            return "Use your screen for about 20 seconds to work in ${args.optString("name").trim().ifEmpty { "an app" }} (there's no background way). You'll see a Stop banner; Atlas puts you back where you were after."
        }
        if (tool !in driving || active(jobId)) return null
        if (!RouteRules.needsHeadsUp(fromChat = false, atlasInFront = false, sessionActive = false, phoneControlOn = PhoneControl.on())) return null   // it can only fail
        val app = currentApp(ctx)?.let { appLabel(ctx, it) } ?: "an app"
        return "Use your screen for about 20 seconds to work in $app. You'll see a Stop banner; Atlas puts you back where you were after."
    }

    /** What's available to do things in the background (for the hint). */
    fun avail(ctx: Context) = RouteRules.Avail(
        emailConnected = try { Email.account(ctx) != null } catch (_: Exception) { false },
        notificationAccess = PermissionCenter.granted(ctx, "notifications"),
        hasReplyableMessage = Messages.current().any { it.canReply },
        smsAllowed = PermissionCenter.granted(ctx, "sms"),
    )

    /**
     * Called by Tools.run before a screen tool. Returns a result to give back instead of acting (a hint toward the
     * background, the user said no, the user stopped it), or null to go ahead (and then [touched] after acting).
     */
    suspend fun before(ctx: Context, tool: String, args: JSONObject): Tools.Result? {
        val who = coroutineContext[Who]
        val key = who?.jobId ?: "chat"
        if (tool == "open_app") {
            // a bot opening an app: point it at the background ways first (the chat's open_app is the user asking to see the app)
            if (who?.jobId != null && !args.optBoolean("use_screen")) RouteRules.hint(args.optString("name"), avail(ctx))?.let { return Tools.Result(it) }
            if (who?.jobId != null) begin(ctx, key)
            return null
        }
        if (tool !in driving) return null
        // phone control is off: say so, without a heads-up or a "using your screen" banner for an action that can't happen
        if (!PhoneControl.on()) return Tools.Result(PhoneControl.OFF)
        if (who?.jobId == null && stops.blocksChat()) return Tools.Result("The user tapped Stop: Atlas may not use the screen for this now. Do it another way (background tools, a bot's browser) or ask them.")
        val inFront = PermissionCenter.atlasInFront
        if (who?.jobId == null && RouteRules.needsHeadsUp(fromChat = true, atlasInFront = inFront, sessionActive = active(key))) {
            val app = currentApp(ctx)?.let { appLabel(ctx, it) } ?: "an app"
            if (!askByNotification(ctx, "Atlas needs your screen for about 20 seconds to work in $app.")) {
                return Tools.Result("The user didn't OK Atlas using the screen (they said not now, or didn't answer within a minute). Nothing was done; tell them in one line, or do it another way.")
            }
        }
        if (!active(key)) begin(ctx, key)
        return null
    }

    /** After a screen action ran: keeps the session going and remembers which app Atlas is driving. */
    fun touched(ctx: Context) {
        lastTouch = System.currentTimeMillis()
        currentApp(ctx)?.takeIf { it != ctx.packageName }?.let { operated = it }
        main.removeCallbacks(endRunnable); main.postDelayed(endRunnable, END_AFTER_MS)
    }

    private fun begin(ctx: Context, key: String) {
        val app = ctx.applicationContext
        if (owner != key || System.currentTimeMillis() - lastTouch >= SESSION_MS) { returnTo = currentApp(app); operated = null }
        stops.cleared(key)   // (a bot only gets here after the user's own OK once it was stopped)
        owner = key; lastTouch = System.currentTimeMillis()
        appRef = app
        main.post { showBanner(app) }
        main.removeCallbacks(endRunnable); main.postDelayed(endRunnable, END_AFTER_MS)
    }

    @Volatile private var appRef: Context? = null
    private val endRunnable = Runnable { appRef?.let { end(it, putBack = true) } }

    /** Ends the session: banner away, and the user back in the app they were in (only if Atlas's app is still in front). */
    fun end(ctx: Context, putBack: Boolean) {
        main.removeCallbacks(endRunnable)
        hideBanner(ctx)
        val back = returnTo; val drove = operated
        owner = null; returnTo = null; operated = null; lastTouch = 0
        if (!putBack || back == null || drove == null || back == drove) return
        // put them back only if they're still looking at the app Atlas was driving (not if they moved on themselves)
        if (currentApp(ctx) != drove) return
        try {
            val i = if (back == ctx.packageName) Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                else ctx.packageManager.getLaunchIntentForPackage(back)
            i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
        } catch (_: Exception) {}
    }

    private fun currentApp(ctx: Context): String? = try { AtlasAccess.instance?.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
    private fun appLabel(ctx: Context, pkg: String) = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    // ---------------------------------------------------------------- the heads-up (chat, user elsewhere)

    private suspend fun askByNotification(ctx: Context, text: String): Boolean {
        val app = ctx.applicationContext
        val d = CompletableDeferred<Boolean>(); answer?.complete(false); answer = d
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Atlas using the screen", NotificationManager.IMPORTANCE_HIGH))
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(app, code, Intent(app, ScreenReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Atlas needs your screen").setContentText(text)
            .addAction(Notification.Action.Builder(null, "Go Ahead", pi(ACTION_OK, NID + 1)).build())
            .addAction(Notification.Action.Builder(null, "Not Now", pi(ACTION_NO, NID + 2)).build())
            .setTimeoutAfter(60_000).setAutoCancel(true).build()
        try { nm.notify(NID + 3, n) } catch (_: SecurityException) { return false }
        val ok = withTimeoutOrNull(60_000) { d.await() } ?: false
        try { nm.cancel(NID + 3) } catch (_: Exception) {}
        return ok
    }

    internal fun onReceive(ctx: Context, action: String?) {
        when (action) {
            ACTION_OK -> { answer?.complete(true); answer = null }
            ACTION_NO -> { answer?.complete(false); answer = null }
            ACTION_STOP -> { stops.stop(owner); end(ctx.applicationContext, putBack = false) }
        }
    }

    // ---------------------------------------------------------------- the banner

    private fun showBanner(ctx: Context) {
        // a notification always (with Stop); a small pill over the top of the screen when Atlas may draw over apps
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH + "_on", "Atlas is using the screen", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(ctx, NID + 4, Intent(ctx, ScreenReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH + "_on").setSmallIcon(R.drawable.ic_stat).setContentTitle("Atlas is using your screen")
            .setContentText("It hands it back when done. Tap Stop to take it back now.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try { nm.notify(NID, n) } catch (_: SecurityException) {}
        if (banner != null || !Settings.canDrawOverlays(ctx)) return
        try {
            val d = ctx.resources.displayMetrics.density
            val v = TextView(ctx).apply {
                text = "Atlas is using your screen  ·  Stop"; textSize = 13f; setTextColor(Palette.NIGHT.fg)
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(0xE6171A3A.toInt()); setStroke((1 * d).toInt(), Palette.NIGHT.accent) }
                setOnClickListener { stops.stop(owner); end(ctx, putBack = false) }
            }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (34 * d).toInt() }
            ctx.getSystemService(WindowManager::class.java).addView(v, lp); banner = v
        } catch (_: Exception) { banner = null }
    }

    private fun hideBanner(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NID) } catch (_: Exception) {}
        main.post { banner?.let { try { ctx.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }; banner = null }
    }
}

/** The heads-up's buttons and the banner's Stop. */
class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ScreenGate.onReceive(context, intent.action)
}
