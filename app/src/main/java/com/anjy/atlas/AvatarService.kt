package com.anjy.atlas

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/**
 * Atlas's presence while you use the phone: the little creature floating over your apps, a pill saying what
 * Atlas is doing while it works in another app (tap it to stop), and the "Hey Atlas" listener.
 * Each part is switched on and off in Atlas → Me.
 */
class AvatarService : Service() {
    private var wm: WindowManager? = null
    private var creature: View? = null
    private var creatureImg: ImageView? = null
    private val bobStart = android.os.SystemClock.uptimeMillis()
    private var pill: TextView? = null
    private var hotword: Hotword? = null
    private var micGranted = false
    private var held = false
    private val handler = Handler(Looper.getMainLooper())
    private val onChat: () -> Unit = { updatePill(); updateCreatureMood() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Reminders.channels(this)
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground", e)
            instance = null; stopSelf(); return
        }
        running = true
        wm = getSystemService(WindowManager::class.java)
        Conversation.listen(onChat)
        apply(fromUser = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CELEBRATE -> celebrate()
            ACTION_MIC -> apply(fromUser = true)
            ACTION_STOP_TASK -> Conversation.stop()
            else -> apply(fromUser = false)
        }
        return START_STICKY
    }

    private fun prefsOn(): Pair<Boolean, Boolean> = try {
        Store.read(applicationContext) { Pair(it.settings.avatarOn, it.settings.wakeWord) }
    } catch (e: Exception) { Pair(true, false) }

    /**
     * Bring everything in line with the settings. [fromUser]: Atlas is on screen or a notification was tapped, the
     * only moments Android lets an app begin using the microphone in the background.
     */
    private fun apply(fromUser: Boolean) {
        var (avatarOn, wakeOn) = prefsOn()
        if (wakeOn && Hotword.crashedLastTime(this)) {
            // the speech engine took the app down last time: switch Hey Atlas off and say so, rather than loop
            Log.e(TAG, "Hey Atlas crashed last time; switching it off")
            Hotword.clearCrash(this)
            try { Store.edit(applicationContext) { it.settings.wakeWord = false } } catch (_: Exception) {}
            Store.setFlag(this, "hotwordCrashed", true)
            wakeOn = false
        }
        if (!avatarOn && !wakeOn) { stopSelf(); return }
        // the creature
        if (avatarOn && Settings.canDrawOverlays(this)) addCreature() else removeCreature()
        creature?.visibility = if (appInFront) View.GONE else View.VISIBLE
        // the wake word
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wakeOn && micOk && Hotword.ready(this)) {
            if (!micGranted && (fromUser || appInFront)) {
                try {
                    startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    micGranted = true
                } catch (e: Exception) { Log.w(TAG, "microphone not allowed yet", e) }
            }
            if (micGranted) {
                val hw = hotword ?: Hotword(applicationContext) { wake() }.also { hotword = it }
                if (!held) hw.start() else hw.pause()
            }
        } else {
            hotword?.stop(); hotword = null
            if (micGranted) {
                micGranted = false
                try { startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (_: Exception) {}
            }
        }
        refreshNotification()
        updatePill()
    }

    private fun wake() {
        // Android lets an app with "display over other apps" open its own screen from the background
        try {
            startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("listen", true))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the panel", e)
            handler.postDelayed({ if (!held) hotword?.start() }, 1500)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val (_, wakeOn) = prefsOn()
        val text = when {
            wakeOn && micGranted && hotword?.state == "listening" -> "Say “Hey Atlas”, or tap to open."
            wakeOn && micGranted -> "Getting “Hey Atlas” ready…"
            wakeOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "“Hey Atlas” needs the microphone. Open Atlas to allow it."
            wakeOn && !Hotword.ready(this) -> "“Hey Atlas” isn't set up yet. Switch it on in Atlas → Me."
            wakeOn -> "Tap “Turn on Hey Atlas” to start listening."
            else -> "Tap to open Atlas."
        }
        val b = Notification.Builder(this, Reminders.CH_AVATAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Atlas is with you")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (wakeOn && !micGranted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Hotword.ready(this)) {
            val mic = PendingIntent.getForegroundService(this, 1, Intent(this, AvatarService::class.java).setAction(ACTION_MIC), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Turn on Hey Atlas", mic).build())
        }
        return b.build()
    }

    private fun refreshNotification() {
        try { getSystemService(android.app.NotificationManager::class.java).notify(ID, notification()) } catch (_: Exception) {}
        // the listener takes a moment to load: say so once it's actually listening
        if (hotword?.state == "loading") handler.postDelayed({ refreshNotification() }, 2000)
    }

    // ---------------------------------------------------------------- the creature

    private fun addCreature() {
        if (creature != null) return
        val d = resources.displayMetrics.density
        val size = (78 * d).toInt()
        val prefs = getSharedPreferences("avatar", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - size - (8 * d).toInt())
            y = prefs.getInt("y", (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        val frame = FrameLayout(this)
        val img = ImageView(this).apply {
            setImageResource(R.drawable.creature); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Atlas"
        }
        frame.addView(img, FrameLayout.LayoutParams(size, size))
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        frame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt()
                        try { wm?.updateViewLayout(frame, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    else startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    true
                }
                else -> false
            }
        }
        try {
            wm?.addView(frame, params)
            creature = frame; creatureImg = img
            startBob()
        } catch (e: Exception) { Log.e(TAG, "Could not add the creature", e) }
    }

    private fun removeCreature() {
        handler.removeCallbacks(bobStep)
        creature?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        creature = null; creatureImg = null
    }

    /**
     * A gentle float up and down, quicker while Atlas is working. It's on screen all day, so it moves in small steps
     * about 12 times a second (smooth enough for a slow bob) instead of redrawing at the full frame rate.
     */
    private val bobStep = object : Runnable {
        override fun run() {
            val img = creatureImg ?: return
            if (creature?.visibility == View.VISIBLE) {
                val d = resources.displayMetrics.density
                val t = (android.os.SystemClock.uptimeMillis() - bobStart) / 1000f * (2f * Math.PI.toFloat() / 2.6f)
                val busy = Conversation.busy
                img.translationY = sin(t * (if (busy) 2 else 1)) * 3f * d
                img.rotation = if (busy) sin(t * 3) * 4f else 0f
            }
            handler.postDelayed(this, 80)
        }
    }
    private fun startBob() { handler.removeCallbacks(bobStep); handler.post(bobStep) }

    private fun updateCreatureMood() { /* the bob reads Conversation.busy on every frame */ }

    private fun celebrate() {
        val v = creatureImg ?: return
        val d = resources.displayMetrics.density
        v.animate().translationY(-14 * d).scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
            v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.BounceInterpolator()).start()
        }.start()
    }

    // ---------------------------------------------------------------- the "working" pill

    private fun updatePill() {
        val show = Conversation.busy && Conversation.usingPhone && !appInFront && Settings.canDrawOverlays(this)
        if (!show) { pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; pill = null; return }
        val label = "Atlas · ${Conversation.status ?: "Working"}…   Stop"
        pill?.let { it.text = label; return }
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f
            setPadding((16 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xEE2F4BFF.toInt(), 0xEE7C5CFF.toInt())).apply { cornerRadius = 999 * d }
            elevation = 8 * d
            contentDescription = "Atlas is working. Tap to stop."
            setOnClickListener { Conversation.stop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * d).toInt() }
        try { wm?.addView(tv, params); pill = tv } catch (e: Exception) { Log.w(TAG, "pill", e) }
    }

    override fun onDestroy() {
        Conversation.unlisten(onChat)
        handler.removeCallbacksAndMessages(null)
        hotword?.stop(); hotword = null
        removeCreature()
        pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; pill = null
        running = false; instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AtlasPresence"
        private const val ID = 7
        const val ACTION_REFRESH = "com.anjy.atlas.AVATAR_REFRESH"
        const val ACTION_CELEBRATE = "com.anjy.atlas.AVATAR_CELEBRATE"
        const val ACTION_MIC = "com.anjy.atlas.AVATAR_MIC"
        const val ACTION_STOP_TASK = "com.anjy.atlas.STOP_TASK"
        @Volatile var running = false; private set
        @Volatile var appInFront = false; private set
        @Volatile private var instance: AvatarService? = null

        /** What the wake word listener is doing, for the settings screen. */
        fun hotwordState(): String = instance?.let { s -> if (!s.micGranted) "waiting for the microphone" else s.hotword?.state ?: "off" } ?: "off"

        /** Called by Atlas's screens as they come and go. While Atlas is in front it may start the microphone. */
        fun setAppInFront(ctx: Context, inFront: Boolean) {
            appInFront = inFront
            if (running) refresh(ctx) else if (inFront) startIfEnabled(ctx)
        }

        fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, AvatarService::class.java)) }
            catch (e: Exception) { Log.w(TAG, "Start refused", e) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AvatarService::class.java)); running = false }

        fun startIfEnabled(ctx: Context) {
            val (a, w) = try { Store.read(ctx) { Pair(it.settings.avatarOn, it.settings.wakeWord) } } catch (e: Exception) { Pair(true, false) }
            if ((a && canShow(ctx)) || w) start(ctx)
        }

        /** Settings changed: start, update or stop. */
        fun refresh(ctx: Context) {
            if (running) send(ctx, ACTION_REFRESH) else startIfEnabled(ctx)
        }
        fun celebrate(ctx: Context) = send(ctx, ACTION_CELEBRATE)

        /** Atlas is listening itself (or done): the wake word lets go of the microphone meanwhile. */
        fun holdMic(hold: Boolean) {
            val s = instance ?: return
            s.handler.post {
                s.held = hold
                if (hold) s.hotword?.pause() else if (s.micGranted) s.hotword?.start()
                s.refreshNotification()
            }
        }

        private fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, AvatarService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
