package com.anjy.atlas

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.anjy.atlas.core.Engine
import kotlin.math.abs

/**
 * Keeps the ember avatar floating over every app. Tap it to open Atlas; drag to move it.
 * Turned on and off from Atlas settings. Needs the "Display over other apps" permission.
 */
class AvatarService : Service() {
    private var wm: WindowManager? = null
    private var view: FlameView? = null
    @Volatile private var celebrating = false
    private var lp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val moodPoll = object : Runnable {
        override fun run() { updateMood(); handler.postDelayed(this, 20_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Reminders.channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Reminders.CH_AVATAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Atlas is on screen")
            .setContentText("Tap to open Atlas. Turn the avatar off in Atlas settings.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e("AtlasAvatar", "Could not start foreground", e)
            stopSelf(); return
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        addAvatar()
        running = true
        handler.post(moodPoll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) { applyVisibility(); updateMood() }
        if (intent?.action == ACTION_CELEBRATE) { celebrating = true; view?.mood = "happy"; view?.celebrate(); handler.postDelayed({ celebrating = false; updateMood() }, 4000) }
        return START_STICKY
    }

    private fun addAvatar() {
        val density = resources.displayMetrics.density
        val size = (76 * density).toInt()
        val prefs = getSharedPreferences("avatar", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, (size * 1.15f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - size - (8 * density).toInt())
            y = prefs.getInt("y", (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        // Stoa's Ember, with its face; capped at 15 frames a second while it floats over other apps
        val v = FlameView(this).apply { tier = Tiers.list[2]; lit = true; face = true; arms = false; mood = "ready"; maxFps = 15 }
        v.contentDescription = "Atlas"
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt()
                        try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("tab", "chat"))
                    true
                }
                else -> false
            }
        }
        wm = getSystemService(WindowManager::class.java)
        try {
            wm?.addView(v, params)
            view = v; lp = params
            applyVisibility()
            updateMood()
        } catch (e: Exception) {
            Log.e("AtlasAvatar", "Could not add avatar", e)
            stopSelf()
        }
    }

    /** Atlas's own screens already show Ember, so the floating one steps aside while Atlas is open. */
    private fun applyVisibility() {
        view?.visibility = if (appInFront) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun updateMood() {
        val v = view ?: return
        Thread {
            val now = System.currentTimeMillis()
            val mood = try {
                Store.read(applicationContext) { s ->
                    when {
                        s.tasks.any { it.status == "awaiting" } -> "worried"
                        Engine.inQuiet(s.settings, now) -> "sleepy"
                        else -> "ready"
                    }
                }
            } catch (e: Exception) { "ready" }
            handler.post { if (!celebrating) v.mood = mood }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        view = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_REFRESH = "com.anjy.atlas.AVATAR_REFRESH"
        const val ACTION_CELEBRATE = "com.anjy.atlas.AVATAR_CELEBRATE"
        @Volatile var running = false; private set
        @Volatile var appInFront = false; private set

        /** Called by Atlas's screen as it comes and goes. */
        fun setAppInFront(ctx: Context, inFront: Boolean) {
            appInFront = inFront
            refresh(ctx)
        }

        fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canShow(ctx)) return
            try { ctx.startForegroundService(Intent(ctx, AvatarService::class.java)) }
            catch (e: Exception) { Log.w("AtlasAvatar", "Start refused", e) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AvatarService::class.java)); running = false
        }

        fun startIfEnabled(ctx: Context) {
            val on = try { Store.read(ctx) { it.settings.avatarOn } } catch (e: Exception) { true }
            if (on) start(ctx)
        }

        /** Ask a running avatar to re-check its mood. Does nothing if it is not running. */
        fun refresh(ctx: Context) = send(ctx, ACTION_REFRESH)
        fun celebrate(ctx: Context) = send(ctx, ACTION_CELEBRATE)

        private fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, AvatarService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
