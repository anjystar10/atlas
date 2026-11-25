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
import android.content.res.Configuration
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
        if (!goForeground()) {
            // Restarted by Android after it stopped Atlas (START_STICKY), with nothing of Atlas on screen. From the
            // background, Android 15 lets an app with "Display over other apps" become a foreground service only while
            // one of its overlay windows is visible: the orb comes back first, then Atlas goes foreground.
            if (!(settingsOn().first && Settings.canDrawOverlays(this))) { instance = null; stopSelf(); return }
            pendingForeground = true
        }
        running = true
        wm = getSystemService(WindowManager::class.java)
        Conversation.load(applicationContext)
        Conversation.listen(onChat)
        apply(fromUser = false)
        if (pendingForeground) handler.postDelayed(foregroundRetry, 500)
        handler.postDelayed(syncTick, 60_000)
    }

    /** Not a foreground service yet: waiting for the orb to be on screen (see [onCreate]). */
    private var pendingForeground = false
    private var foregroundTries = 0

    private fun goForeground(): Boolean = try {
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); true
    } catch (e: Exception) { Log.w(TAG, "Could not start foreground", e); false }

    /** Tries again once a second while the orb comes on screen (the screen may be off for a while); gives up after a minute. */
    private val foregroundRetry: Runnable = object : Runnable {
        override fun run() {
            if (!pendingForeground) return
            if (goForeground()) { pendingForeground = false; apply(fromUser = false); return }
            if (++foregroundTries < 60) handler.postDelayed(this, 1000)
            else { Log.w(TAG, "Android didn't let Atlas on screen come back; it shows again when Atlas is opened"); stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pendingForeground && goForeground()) pendingForeground = false
        when (intent?.action) {
            ACTION_CELEBRATE -> celebrate()
            ACTION_MIC -> apply(fromUser = true)
            ACTION_STOP_TASK -> Conversation.stop()
            ACTION_WAKE -> { fromWakeWord = intent?.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false) == true; wake() }
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
        if (!avatarOn && !wakeOn && panel == null) { HotwordService.stop(this); stopSelf(); return }
        if (avatarOn && Settings.canDrawOverlays(this)) addOrb() else if (panel == null) removeOrb()
        orbFrame?.visibility = if ((appInFront && !talking) || panel != null) View.GONE else View.VISIBLE
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wakeOn && micOk && HotwordFiles.ready(this)) {
            // talking back after "Hey Atlas" uses the microphone from here too
            if (!micGranted && (fromUser || appInFront)) {
                try {
                    startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    micGranted = true; pendingForeground = false
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

    /**
     * Keeps the orb where it can be seen and reached: at least half of it on screen across (it may tuck into a side),
     * all of it up and down. A drag past the edge, a spot saved with the phone turned the other way, or turning the
     * phone (a portrait spot is below the bottom of a landscape screen) could otherwise leave it off screen for good.
     */
    private fun keepOnScreen(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val half = p.width / 2
        p.x = p.x.coerceIn(-half, (dm.widthPixels - half).coerceAtLeast(-half))
        p.y = p.y.coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val f = orbFrame ?: return; val p = orbParams ?: return
        restAt = null   // (a spot from before the turn may be off the new screen; it shrinks back where it is)
        keepOnScreen(p)
        try { wm?.updateViewLayout(f, p) } catch (_: Exception) {}
        placeCaption()
    }

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
        keepOnScreen(params)
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
                        keepOnScreen(params)
                        try { wm?.updateViewLayout(frame, params) } catch (_: Exception) {}
                        placeCaption()
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
            orbFrame = frame; orbParams = params
            if (panel == null) orb = o else { floatingOrb = o; frame.visibility = View.GONE }   // the panel's orb stands in while it's open
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
                        keepOnScreen(pp)
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

    // ---------------------------------------------------------------- the mini panel

    private var panel: MiniPanel? = null
    /** A note shown in the panel's status line until something new happens ("Still listening…", "I didn't catch that."). */
    private var panelNote: String? = null
    private var ticks = 0
    /** The floating orb while the panel's own orb stands in for it. */
    private var floatingOrb: OrbView? = null

    /**
     * Opens the mini panel over the app in front (or brings it back): the conversation shows there instead of the
     * caption by the orb. False when Android doesn't let Atlas draw over other apps.
     */
    private fun openPanel(): Boolean {
        panel?.let { if (it.shown) return true }
        if (!Settings.canDrawOverlays(this)) return false
        val wmm = wm ?: return false
        val p = MiniPanel(this, wmm,
            onMic = { panelMic() },
            onSend = { t -> panelSend(t) },
            onAnswer = { yes -> panelAnswer(yes) },
            onOpenApp = { closePanel(); try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "open Atlas", e) } },
            onDismiss = { closePanel() })
        p.show()
        if (!p.shown) return false
        panel = p
        hideCaption()
        floatingOrb = orbFrame?.let { orb }
        orb = p.orb
        orbFrame?.visibility = View.GONE
        p.status("Listening…")
        panelRefresh()
        return true
    }

    /** Puts the panel away: listening stops; anything Atlas is doing carries on (the pill shows it when it's on screen). */
    private fun closePanel() {
        val p = panel ?: return
        panel = null
        handler.removeCallbacks(panelIdle)
        p.close()
        orb = floatingOrb; floatingOrb = null
        endTalk()
        orbFrame?.visibility = if (appInFront || orbParams == null) View.GONE else View.VISIBLE
        updatePill()
    }

    /** After a quiet spell with nothing going on (and you not typing), the panel goes by itself. */
    private val panelIdle = Runnable { val p = panel ?: return@Runnable; if (!talking && !Conversation.busy && !Voice.busy && !p.typing) closePanel() else armPanelIdle() }
    private fun armPanelIdle() { handler.removeCallbacks(panelIdle); if (panel != null) handler.postDelayed(panelIdle, 30_000) }

    /** The mic: done talking (send it now), cut in while Atlas speaks, or start talking again. */
    private fun panelMic() {
        when {
            voice != null && !alongside -> voice?.finish()
            Voice.busy -> { Conversation.hush(); if (!talking) startTalk() else listen() }
            else -> if (!talking) startTalk() else { voice?.cancel(); voice = null; listen() }
        }
    }

    /** Typed in the panel: sent as a written request (a reply that's shown; spoken only if Speak Replies is on). */
    private fun panelSend(t: String) {
        voice?.cancel(); voice = null; alongside = false
        panel?.heard(t)
        if (Conversation.busy) Conversation.interject(this, t, onSpoken = {}) else Conversation.send(this, t, spoken = false)
        armPanelIdle()
    }

    /** Allow / Deny in the panel: a bot's request is answered directly; the chat's own is answered in words, as you'd say it. */
    private fun panelAnswer(yes: Boolean) {
        val bot = try { Bots.visible().firstOrNull { it.state == BotRules.AWAITING_APPROVAL } } catch (_: Exception) { null }
        if (bot != null) Bots.approve(bot.id, yes)
        else Conversation.send(this, if (yes) "Yes, allow it." else "No, don't do that.", spoken = talking)
        panel?.waitingOn(null)
    }

    /** Brings the panel up to date with the conversation: status, the reply, anything waiting on you, stepping aside. */
    private fun panelRefresh() {
        val p = panel ?: return
        p.stepAside(Conversation.busy && Conversation.usingPhone)
        val bot = try { Bots.visible().firstOrNull { it.state == BotRules.AWAITING_APPROVAL } } catch (_: Exception) { null }
        p.waitingOn(bot?.approval?.what ?: ChatAsk.list().lastOrNull()?.what)
        p.status(panelNote ?: when {
            voice != null && !alongside -> "Listening…"
            Voice.speaking -> "Speaking"
            Conversation.busy -> "${Conversation.status ?: "Thinking"}…"
            voice != null -> "Working · still listening"
            else -> "Tap the mic to talk, or type"
        })
        p.listening(voice != null && !alongside)
    }

    /** Starts a spoken conversation (the panel's mic, the orb, "Hey Atlas"). */
    private fun startTalk(afterWake: Boolean = false) {
        holdMic(this, true)
        Conversation.hush()
        talking = true; turns = 0; hearFails = 0
        handler.removeCallbacks(pulse); handler.post(pulse)
        Hear.prewarm(this)
        listen(afterWake = afterWake)
    }

    /**
     * "Hey Atlas", the assist gesture or a tap on the orb: the mini panel opens over the app in front and listens.
     * Without "Display over other apps", the panel opens as a small sheet instead ([AssistActivity]); the full app
     * opens only when you ask for it.
     */
    private fun wake() {
        // "Display over other apps" was switched off while Atlas ran: Android hides its windows, so the orb left behind
        // can't be seen. It goes, and the conversation opens in the sheet instead of talking from an invisible orb.
        if (orbFrame != null && !Settings.canDrawOverlays(this)) removeOrb()
        // Over the lock screen, or with the screen off, nothing drawn over other apps can be seen (Android keeps those
        // windows under the lock screen): the sheet opens there instead, as it's made to (it shows over the lock screen
        // and turns the screen on), rather than a panel nobody can see listening and answering.
        val unseen = (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) ||
            (getSystemService(android.os.PowerManager::class.java)?.isInteractive == false)
        if (!unseen && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && openPanel()) {
            startTalk(afterWake = fromWakeWord)
            fromWakeWord = false
            return
        }
        if (unseen || orbFrame == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (unseen) closePanel()   // (one left open before the phone locked)
            try { startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("listen", true).putExtra(EXTRA_FROM_WAKE_WORD, fromWakeWord)); fromWakeWord = false }
            catch (e: Exception) { Log.w(TAG, "couldn't open the panel", e); holdMic(this, false) }
            return
        }
        holdMic(this, true)
        Conversation.hush()
        talking = true; turns = 0; hearFails = 0
        orbFrame?.visibility = View.VISIBLE
        setGrown(true)
        handler.post(pulse)
        Hear.prewarm(this)
        listen(afterWake = fromWakeWord)
        fromWakeWord = false
    }

    /** Set when "Hey Atlas" (not a tap) woke it: the wake listener hands over what was said right after the phrase. */
    private var fromWakeWord = false

    private var hearFails = 0
    /** Listening while Atlas works (it carries on; what's said is passed on to it), not waiting for an answer. */
    private var alongside = false
    /** Atlas's own voice paused the listening while it works: listen again once it has finished speaking. */
    private var resumeAfterVoice = false

    /**
     * While Atlas works on something you asked for by voice, the conversation goes on: it keeps listening (except
     * while it speaks, so it doesn't hear itself), "stop" stops the work, "that's all" ends the talk (the work goes
     * on), and anything else is passed on to it as it works.
     */
    private fun listenAlongside() {
        if (!talking || !Conversation.busy || Voice.busy || voice != null) return
        alongside = true
        orb?.energy = 1f
        voice = Hear.open(this,
            onPartial = { t -> if (!Conversation.usingPhone) say(t, who = "You") },
            onLevel = { l -> orb?.level = l },
            onDone = { t ->
                voice = null; alongside = false; orb?.level = 0f
                if (!talking) return@open
                if (t.isNullOrBlank()) { handler.postDelayed({ listenAlongside() }, 250); return@open }
                when (Speech.whileWorking(t)) {
                    Speech.WhileWorking.STOP -> { Conversation.stop(); Voice.speak(this, "Stopped."); handler.postDelayed({ listen() }, 900) }
                    Speech.WhileWorking.END_TALK -> { if (!Conversation.usingPhone) say(t, who = "You"); handler.postDelayed({ endTalk() }, 700) }
                    Speech.WhileWorking.PASS_ON -> {
                        turns++
                        if (!Conversation.usingPhone) say(t, who = "You")
                        Conversation.interject(this, t, onSpoken = { if (talking && Hear.handsFree(this)) handler.postDelayed({ listen() }, 400) })
                        handler.postDelayed({ listenAlongside() }, 300)
                    }
                }
            },
            onError = { _ ->
                voice = null; alongside = false; orb?.level = 0f
                // listening alongside is a bonus: a hiccup just means a short pause before trying again
                if (talking) handler.postDelayed({ listenAlongside() }, 2500)
            })
        voice?.start()
    }

    private fun listen(afterWake: Boolean = false) {
        if (!talking) return
        Conversation.hush()
        alongside = false; resumeAfterVoice = false
        orb?.energy = 1f
        say("Listening…", who = "You", dim = true)
        voice?.cancel()
        voice = Hear.open(this, afterWake = afterWake,
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
                Conversation.send(this, t, spoken = true, onSpoken = { if (talking && Hear.handsFree(this)) handler.postDelayed({ listen() }, 400) })
                // the conversation goes on while it works
                handler.postDelayed({ listenAlongside() }, 700)
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
        panel?.let { p ->
            val last = Conversation.messages.lastOrNull()
            if (last != null && !last.fromUser && last.text.isNotBlank()) { panelNote = null; p.reply(last.text) }
            panelRefresh(); armPanelIdle()
            return
        }
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
        if (pendingForeground) handler.postDelayed(foregroundRetry, 500)   // (and the wait to go foreground)
        voice?.cancel(); voice = null
        alongside = false; resumeAfterVoice = false
        orb?.level = 0f; orb?.energy = 0f
        hideCaption()
        if (panel == null) setGrown(false)
        holdMic(this, false)
        panelRefresh(); armPanelIdle()
    }

    private var captionWho: TextView? = null
    private var captionBody: TextView? = null

    /**
     * The caption beside the orb: a small pane of dark glass saying who's talking ("You" / "Atlas") and the words.
     * A change of speaker fades the words in, so the conversation reads smoothly.
     */
    private fun say(text: String, who: String = "Atlas", dim: Boolean = false) {
        panel?.let { p ->
            when {
                who == "You" && text == "Listening…" -> { panelNote = null; p.heard(null) }
                who == "You" && text == "Still listening…" -> panelNote = text
                who == "You" -> { panelNote = null; p.heard(text) }
                dim -> panelNote = text                      // "I didn't catch that.", a hiccup
                else -> { panelNote = null; p.reply(text) }
            }
            panelRefresh()
            return
        }
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
            val w = TextView(this).apply { setTextColor(Palette.NIGHT.muted); textSize = 11f; letterSpacing = 0.12f; isAllCaps = true
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
        private var idle = 0
        override fun run() {
            if (!talking) { orb?.level = 0f; return }
            t += 0.05f
            // A talk can't move on by itself if its answer was stopped or cut off by other speech (the "listen again"
            // never comes): when nothing is listening, thinking or speaking for a while, the talk ends.
            // listening alongside the work: Atlas's own voice pauses it (so it doesn't hear itself), then it resumes
            if (alongside && voice != null && Voice.busy) { voice?.cancel(); voice = null; alongside = false; resumeAfterVoice = true }
            else if (resumeAfterVoice && !Voice.busy && voice == null) {
                resumeAfterVoice = false
                if (Conversation.busy) handler.postDelayed({ listenAlongside() }, 400)
            }
            if (voice == null && !Conversation.busy && !Voice.busy) {
                idle += 50
                if (idle >= 6000) { endTalk(); return }
            } else idle = 0
            if (panel != null && ++ticks % 10 == 0) panelRefresh()   // twice a second
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
        panel?.close(); panel = null
        Conversation.unlisten(onChat)
        removeOrb()   // (ending a talk here re-arms the sync timer: callbacks are cleared after it)
        handler.removeCallbacksAndMessages(null)
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
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
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

        /**
         * Atlas is listening itself (or done): the wake word lets go of the microphone meanwhile. Only a running
         * listener is told: a hold sent with Hey Atlas off started its process just to stop it again.
         */
        fun holdMic(ctx: Context, hold: Boolean) { if (HotwordService.running(ctx)) HotwordService.hold(ctx, hold) }

        private fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, AvatarService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
