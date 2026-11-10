package com.anjy.atlas

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.widget.LinearLayout
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Atlas on screen, over your other apps: the orb. Say "Hey Atlas" (or tap it) and it grows, listens, answers out loud
 * and keeps listening for a follow-up; when the conversation goes quiet it shrinks back. While Atlas works in another
 * app, a pill says what it's doing (tap it to stop). Switched on and off in Atlas → Me.
 */
class AvatarService : Service() {
    private var wm: WindowManager? = null
    private var orbFrame: FrameLayout? = null
    private var orb: OrbView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var caption: LinearLayout? = null
    private var pill: TextView? = null
    private var micGranted = false
    private val handler = Handler(Looper.getMainLooper())
    private val onChat: () -> Unit = { updatePill(); conversationMoved() }

    // the spoken conversation in place
    private var talking = false
    private var voice: Hearing? = null
    private var turns = 0
    private var grow: ValueAnimator? = null
    /** Where the small orb sat before growing, so it returns to the same spot. */
    private var restAt: Pair<Int, Int>? = null

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
        Conversation.load(applicationContext)
        Conversation.listen(onChat)
        apply(fromUser = false)
        handler.postDelayed(syncTick, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CELEBRATE -> celebrate()
            ACTION_MIC -> apply(fromUser = true)
            ACTION_STOP_TASK -> Conversation.stop()
            ACTION_WAKE -> wake()
            else -> apply(fromUser = false)
        }
        return START_STICKY
    }

    private fun settingsOn(): Pair<Boolean, Boolean> = try {
        Store.read(applicationContext) { Pair(it.settings.avatarOn, it.settings.wakeWord) }
    } catch (e: Exception) { Pair(true, false) }

    /**
     * Bring everything in line with the settings. [fromUser]: Atlas is on screen or a notification was tapped, the only
     * moments Android lets an app begin using the microphone in the background.
     */
    private fun apply(fromUser: Boolean) {
        var (avatarOn, wakeOn) = settingsOn()
        HotwordService.offReason(this)?.let { why ->
            // the listener crashed twice and switched itself off: record it in the settings and say so in Me
            Log.e(TAG, "Hey Atlas switched itself off: $why")
            try { Store.edit(applicationContext) { it.settings.wakeWord = false } } catch (_: Exception) {}
            Store.putString(this, "hotwordCrash", why)
            HotwordService.clearOff(this)
            wakeOn = false
        }
        if (!avatarOn && !wakeOn) { HotwordService.stop(this); stopSelf(); return }
        if (avatarOn && Settings.canDrawOverlays(this)) addOrb() else removeOrb()
        orbFrame?.visibility = if (appInFront && !talking) View.GONE else View.VISIBLE
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wakeOn && micOk && HotwordFiles.ready(this)) {
            // talking back after "Hey Atlas" uses the microphone from here too
            if (!micGranted && (fromUser || appInFront)) {
                try {
                    startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    micGranted = true
                } catch (e: Exception) { Log.w(TAG, "microphone not allowed yet", e) }
            }
            if (fromUser || appInFront) HotwordService.start(this)
        } else {
            HotwordService.stop(this)
            if (micGranted) {
                micGranted = false
                try { startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (_: Exception) {}
            }
        }
        refreshNotification()
        updatePill()
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val (_, wakeOn) = settingsOn()
        val canWake = wakeOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && HotwordFiles.ready(this)
        val b = Notification.Builder(this, Reminders.CH_AVATAR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Atlas is with you")
            .setContentText(if (canWake) "Say “Hey Atlas”, or tap Atlas on screen." else "Tap Atlas on screen to ask something.")
            .setContentIntent(open)
            .setOngoing(true)
        if (canWake) {
            // after a restart Android won't let Atlas start the microphone by itself: one tap here does it
            val mic = PendingIntent.getForegroundService(this, 1, Intent(this, AvatarService::class.java).setAction(ACTION_MIC), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Start listening", mic).build())
        }
        return b.build()
    }

    private fun refreshNotification() {
        try { getSystemService(android.app.NotificationManager::class.java).notify(ID, notification()) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- the orb

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val small get() = dp(84)
    private val big get() = dp(140)

    private fun addOrb() {
        if (orbFrame != null) return
        val prefs = getSharedPreferences("avatar", MODE_PRIVATE)
        val size = small
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - size - dp(6))
            y = prefs.getInt("y", (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        val frame = FrameLayout(this)
        val o = OrbView(this).apply { contentDescription = "Atlas"; still = Prefs.reduceMotion(this@AvatarService) }
        frame.addView(o, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        frame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt(); restAt = null
                        try { wm?.updateViewLayout(frame, params) } catch (_: Exception) {}
                        placeCaption()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // remember where the small orb sits
                        val off = (params.width - small) / 2
                        prefs.edit().putInt("x", params.x + off).putInt("y", params.y + off).apply()
                    } else if (talking) {
                        when {
                            Voice.busy -> { Conversation.hush(); listen() }          // cut in: Atlas stops (even before its voice has started) and listens
                            voice != null -> voice?.finish()                         // done talking: send it now
                            else -> endTalk()
                        }
                    } else wake()
                    true
                }
                else -> false
            }
        }
        try {
            wm?.addView(frame, params)
            orbFrame = frame; orb = o; orbParams = params
        } catch (e: Exception) { Log.e(TAG, "Could not show Atlas on screen", e) }
    }

    private fun removeOrb() {
        endTalk()
        orbFrame?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        orbFrame = null; orb = null; orbParams = null
    }

    /** Grows to the listening size around its own centre (or back down). */
    private fun setGrown(on: Boolean) {
        val frame = orbFrame ?: return; val p = orbParams ?: return; val o = orb ?: return
        grow?.cancel()
        if (on) {
            if (p.width != big) restAt = p.x to p.y
            val d = (big - p.width) / 2
            p.width = big; p.height = big; p.x -= d; p.y -= d
            // grown, it stays fully on screen (it may sit at an edge when small)
            val dm = resources.displayMetrics
            p.x = p.x.coerceIn(0, (dm.widthPixels - big).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (dm.heightPixels - big).coerceAtLeast(0))
            try { wm?.updateViewLayout(frame, p) } catch (_: Exception) {}
            o.scaleX = small.toFloat() / big; o.scaleY = o.scaleX
            grow = ValueAnimator.ofFloat(o.scaleX, 1f).apply {
                duration = 380; interpolator = DecelerateInterpolator()
                addUpdateListener { a -> val v = a.animatedValue as Float; o.scaleX = v; o.scaleY = v }
                start()
            }
        } else {
            grow = ValueAnimator.ofFloat(o.scaleX, small.toFloat() / p.width).apply {
                duration = 320; interpolator = DecelerateInterpolator()
                addUpdateListener { a -> val v = a.animatedValue as Float; o.scaleX = v; o.scaleY = v }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val pp = orbParams ?: return
                        if (talking || pp.width == small) return
                        val d = (pp.width - small) / 2
                        pp.width = small; pp.height = small
                        val back = restAt; restAt = null
                        if (back != null) { pp.x = back.first; pp.y = back.second } else { pp.x += d; pp.y += d }
                        o.scaleX = 1f; o.scaleY = 1f
                        try { wm?.updateViewLayout(orbFrame, pp) } catch (_: Exception) {}
                        orbFrame?.visibility = if (appInFront) View.GONE else View.VISIBLE
                    }
                })
                start()
            }
        }
    }

    private fun celebrate() {
        val o = orb ?: return
        o.animate().scaleX(o.scaleX * 1.15f).scaleY(o.scaleY * 1.15f).setDuration(160).withEndAction {
            o.animate().scaleX(if (talking) 1f else 1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.BounceInterpolator()).start()
        }.start()
    }

    // ---------------------------------------------------------------- talking in place

    /** "Hey Atlas" (or a tap): grow and listen. Without Atlas on screen, the panel opens instead. */
    private fun wake() {
        if (orbFrame == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try { startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("listen", true)) }
            catch (e: Exception) { Log.w(TAG, "couldn't open the panel", e); HotwordService.hold(this, false) }
            return
        }
        HotwordService.hold(this, true)
        Conversation.hush()
        talking = true; turns = 0; hearFails = 0
        orbFrame?.visibility = View.VISIBLE
        setGrown(true)
        handler.post(pulse)
        listen()
    }

    private var hearFails = 0

    private fun listen() {
        if (!talking) return
        Conversation.hush()
        orb?.energy = 1f
        say("Listening…", who = "You", dim = true)
        voice?.cancel()
        voice = Hear.open(this,
            onPartial = { t -> say(t, who = "You") },
            onLevel = { l -> orb?.level = l },
            onDone = { t ->
                voice = null; orb?.level = 0f
                if (t.isNullOrBlank()) {
                    // silence: after a question it just means the conversation is over
                    if (turns == 0) { say("I didn't catch that.", dim = true); handler.postDelayed({ endTalk() }, 1400) } else endTalk()
                    return@open
                }
                // "that's all", "never mind", "goodbye": the conversation is over, nothing is sent
                if (turns > 0 && Speech.endsTalk(t)) { say(t, who = "You"); handler.postDelayed({ endTalk() }, 700); return@open }
                turns++; hearFails = 0
                say(t, who = "You")
                // listen again once Atlas has finished speaking (a short gap, so the tail of its own voice isn't heard)
                Conversation.send(this, t, spoken = true, onSpoken = { if (talking) handler.postDelayed({ listen() }, 400) })
            },
            onError = { msg ->
                voice = null; orb?.level = 0f; say(msg, dim = true)
                // one hiccup doesn't end the conversation: listen once more, then give up
                if (++hearFails <= 1) handler.postDelayed({ listen() }, 1800) else handler.postDelayed({ endTalk() }, 2200)
            })
        voice?.start()
    }

    /** While Atlas thinks and answers, the caption shows its reply as it comes. */
    private fun conversationMoved() {
        if (!talking) return
        if (Conversation.busy && Conversation.usingPhone) { hideCaption(); return }
        val last = Conversation.messages.lastOrNull() ?: return
        if (!last.fromUser) say(last.text.ifBlank { "${Conversation.status ?: "Thinking"}…" }, who = "Atlas", dim = last.text.isBlank())
    }

    private fun endTalk() {
        if (!talking) return
        talking = false
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(syncTick, 10 * 60_000L)   // (the line above also cleared the sync timer)
        voice?.cancel(); voice = null
        orb?.level = 0f; orb?.energy = 0f
        hideCaption()
        setGrown(false)
        HotwordService.hold(this, false)
    }

    private var captionWho: TextView? = null
    private var captionBody: TextView? = null

    /**
     * The caption beside the orb: a small pane of dark glass saying who's talking ("You" / "Atlas") and the words.
     * A change of speaker fades the words in, so the conversation reads smoothly.
     */
    private fun say(text: String, who: String = "Atlas", dim: Boolean = false) {
        // your words as heard; Atlas's without formatting symbols
        val shown = if (who == "You" || text.trimEnd().endsWith("…")) text.trim() else Plain.spoken(text)
        val card = caption ?: run {
            val d = resources.displayMetrics.density
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(11))
                background = Finish(Color.parseColor("#101230"), dp(18).toFloat(), true, d, glass = true).let { glass ->
                    android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#D80B0C24")) }, glass))
                }
                elevation = dp(6).toFloat(); alpha = 0f
            }
            val w = TextView(this).apply { setTextColor(Color.parseColor("#9AA0D8")); textSize = 11f; letterSpacing = 0.12f; isAllCaps = true
                try { typeface = resources.getFont(R.font.geist_semibold) } catch (_: Throwable) {} }
            val body = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f); maxWidth = dp(250)
                try { typeface = resources.getFont(R.font.geist_regular) } catch (_: Throwable) {} }
            box.addView(w); box.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            try { wm?.addView(box, p); caption = box; captionWho = w; captionBody = body; box.animate().alpha(1f).setDuration(180).start() }
            catch (e: Exception) { Log.w(TAG, "caption", e) }
            box
        }
        val body = captionBody ?: return
        val whoView = captionWho
        val newSpeaker = whoView?.text?.toString()?.equals(who, true) == false
        whoView?.text = who
        body.text = shown
        body.alpha = if (dim) 0.75f else 1f
        if (newSpeaker) { body.alpha = 0f; body.animate().alpha(if (dim) 0.75f else 1f).setDuration(200).start() }
        card.visibility = View.VISIBLE
        placeCaption()
    }

    /** Beside the orb, on whichever side has room, and kept on screen. */
    private fun placeCaption() {
        val c = caption ?: return; val p = orbParams ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        val sw = resources.displayMetrics.widthPixels
        c.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val w = c.measuredWidth; val h = c.measuredHeight
        val left = p.x + p.width / 2 > sw / 2
        lp.x = (if (left) p.x - w - dp(4) else p.x + p.width + dp(4)).coerceIn(dp(4), sw - w - dp(4))
        lp.y = (p.y + p.height / 2 - h / 2).coerceAtLeast(dp(24))
        try { wm?.updateViewLayout(c, lp) } catch (_: Exception) {}
    }

    /** Fades the caption away, then removes it. */
    private fun hideCaption() {
        val c = caption ?: return
        caption = null; captionWho = null; captionBody = null
        c.animate().alpha(0f).setDuration(160).withEndAction { try { wm?.removeView(c) } catch (_: Exception) {} }.start()
    }

    /** While Atlas is on screen it keeps the phone and the PC in step every ten minutes. */
    private val syncTick: Runnable = object : Runnable {
        override fun run() { Sync.syncSoon(this@AvatarService, 0); handler.postDelayed(this, 10 * 60_000L) }
    }

    /** While Atlas speaks, the orb pulses gently with its voice (and settles when it stops). */
    private val pulse = object : Runnable {
        private var t = 0f
        override fun run() {
            if (!talking) { orb?.level = 0f; return }
            t += 0.05f
            if (Voice.speaking) {
                val beat = (0.5f + 0.5f * sin(t * 9.0f)) * (0.6f + 0.4f * sin(t * 2.3f + 1f))
                orb?.level = 0.12f + 0.38f * beat
            } else if (voice == null) orb?.level = (orb?.level ?: 0f) * 0.85f
            handler.postDelayed(this, 50)
        }
    }

    // ---------------------------------------------------------------- the "working" pill

    private fun updatePill() {
        val show = Conversation.busy && Conversation.usingPhone && !appInFront && Settings.canDrawOverlays(this)
        orb?.energy = if (Conversation.busy || talking) 1f else 0f
        if (!show) { pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; pill = null; return }
        val label = "Atlas · ${Conversation.status ?: "Working"}…   Stop"
        pill?.let { it.text = label; return }
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(16), dp(9), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xEE2F4BFF.toInt(), 0xEE7C5CFF.toInt())).apply { cornerRadius = dp(999).toFloat() }
            elevation = dp(8).toFloat()
            contentDescription = "Atlas is working. Tap to stop."
            setOnClickListener { Conversation.stop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(36) }
        try { wm?.addView(tv, params); pill = tv } catch (e: Exception) { Log.w(TAG, "pill", e) }
    }

    override fun onDestroy() {
        Conversation.unlisten(onChat)
        handler.removeCallbacksAndMessages(null)
        removeOrb()
        hideCaption()
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
        const val ACTION_WAKE = "com.anjy.atlas.WAKE"
        @Volatile var running = false; private set
        @Volatile var appInFront = false; private set
        @Volatile private var instance: AvatarService? = null

        /** What the wake word listener is doing, for the settings screen. */
        fun hotwordState(ctx: Context): String = if (HotwordService.running(ctx)) "listening" else "off"

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

        fun stop(ctx: Context) { HotwordService.stop(ctx); ctx.stopService(Intent(ctx, AvatarService::class.java)); running = false }

        fun startIfEnabled(ctx: Context) {
            val (a, w) = try { Store.read(ctx) { Pair(it.settings.avatarOn, it.settings.wakeWord) } } catch (e: Exception) { Pair(true, false) }
            if ((a && canShow(ctx)) || w) start(ctx)
        }

        /** Settings changed: start, update or stop. */
        fun refresh(ctx: Context) { if (running) send(ctx, ACTION_REFRESH) else startIfEnabled(ctx) }
        fun celebrate(ctx: Context) = send(ctx, ACTION_CELEBRATE)

        /** Atlas is listening itself (or done): the wake word lets go of the microphone meanwhile. */
        fun holdMic(ctx: Context, hold: Boolean) { if (HotwordService.running(ctx)) HotwordService.hold(ctx, hold) }

        private fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, AvatarService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
