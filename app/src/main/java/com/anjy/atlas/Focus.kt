package com.anjy.atlas

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.anjy.atlas.core.Engine
import com.anjy.atlas.core.LogEntry
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A focus session: "I'm doing it now". One screen, no distractions, a timer. You can pause or take a break, but
 * the only way out is the big End key. The session survives Atlas being closed (it's kept in settings) and a
 * notification brings you back to it.
 */
object Focus {
    const val CH_FOCUS = "atlas_focus"
    const val NOTIF_ID = 4343
    private const val K = "focus_"

    class State(val title: String, val taskId: String?, val startedAt: Long, val plannedMin: Int?, val pausedAt: Long, val pausedTotal: Long, val breakUntil: Long) {
        val paused get() = pausedAt > 0
        /** Time actually spent on it: pauses and breaks don't count. */
        fun worked(now: Long) = ((if (paused) pausedAt else now) - startedAt - pausedTotal).coerceAtLeast(0)
    }

    fun state(ctx: Context): State? {
        val start = Store.getString(ctx, K + "start")?.toLongOrNull() ?: return null
        val s = State(Store.getString(ctx, K + "title") ?: "Focus", Store.getString(ctx, K + "task")?.takeIf { it.isNotEmpty() }, start,
            Store.getString(ctx, K + "planned")?.toIntOrNull(), Store.getString(ctx, K + "pausedAt")?.toLongOrNull() ?: 0L,
            Store.getString(ctx, K + "pausedTotal")?.toLongOrNull() ?: 0L, Store.getString(ctx, K + "breakUntil")?.toLongOrNull() ?: 0L)
        // a break that has ended is over, even if the focus screen wasn't open to see it end: the session runs again
        // from the break's end (not from whenever the screen is next opened)
        val back = afterBreak(s, System.currentTimeMillis()) ?: return s
        put(ctx, "pausedTotal", back.pausedTotal); put(ctx, "pausedAt", 0); put(ctx, "breakUntil", 0)
        return back
    }

    /** [s] with its break over, if the break has ended by [now] (counting only the break itself as time off); else null. */
    internal fun afterBreak(s: State, now: Long): State? {
        if (!s.paused || s.breakUntil !in 1..now) return null
        return State(s.title, s.taskId, s.startedAt, s.plannedMin, 0L, s.pausedTotal + (s.breakUntil - s.pausedAt).coerceAtLeast(0), 0L)
    }
    fun active(ctx: Context) = state(ctx) != null

    private fun put(ctx: Context, k: String, v: Any?) = Store.putString(ctx, K + k, v?.toString() ?: "")

    /** Starts a session (or brings back the one running) and opens its screen. */
    fun start(ctx: Context, title: String, minutes: Int?, taskId: String?) {
        begin(ctx, title, minutes, taskId)
        ctx.startActivity(Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Records a new session (unless one is running already). */
    fun begin(ctx: Context, title: String, minutes: Int?, taskId: String?) {
        if (!active(ctx)) {
            val mins = minutes ?: taskId?.let { id -> Store.read(ctx) { s -> s.tasks.firstOrNull { it.id == id }?.durationMin } }
            put(ctx, "title", title); put(ctx, "task", taskId ?: ""); put(ctx, "planned", mins ?: "")
            put(ctx, "pausedAt", 0); put(ctx, "pausedTotal", 0); put(ctx, "breakUntil", 0)
            put(ctx, "start", System.currentTimeMillis())
        }
        taskId?.let { ctx.getSystemService(NotificationManager::class.java).cancel(it.hashCode()) }
        notifyOngoing(ctx)
    }

    fun pause(ctx: Context) { val s = state(ctx) ?: return; if (!s.paused) put(ctx, "pausedAt", System.currentTimeMillis()); notifyOngoing(ctx) }
    fun resume(ctx: Context) {
        val s = state(ctx) ?: return
        val now = System.currentTimeMillis()
        if (s.paused) { put(ctx, "pausedTotal", s.pausedTotal + (now - s.pausedAt)); put(ctx, "pausedAt", 0) }
        put(ctx, "breakUntil", 0)
        notifyOngoing(ctx)
    }
    /** A break is a pause with an end time: it resumes on its own. */
    fun takeBreak(ctx: Context, minutes: Int) { pause(ctx); put(ctx, "breakUntil", System.currentTimeMillis() + minutes * 60_000L); notifyOngoing(ctx) }

    /** Ends the session: logs how long it took, marks the reminder done. Returns the minutes worked. */
    fun end(ctx: Context): Int {
        val s = state(ctx) ?: return 0
        val now = System.currentTimeMillis()
        val mins = (s.worked(now) / 60_000L).toInt()
        Store.edit(ctx) { st ->
            s.taskId?.let { id -> if (st.tasks.any { it.id == id && it.status in Engine.OPEN }) Engine.respond(st, id, "done", now) }
            st.log.add(LogEntry(UUID.randomUUID().toString(), now, "focus", s.taskId, s.title, "focused for $mins min"))
        }
        listOf("title", "task", "planned", "pausedAt", "pausedTotal", "breakUntil", "start").forEach { Store.putString(ctx, K + it, "") }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        Thread { try { Reminders.tickAndSchedule(ctx.applicationContext) } catch (_: Exception) {} }.start()
        return mins
    }

    @Volatile private var channelReady = false

    /** Set up once per run of the app (the ongoing notification asks for it on every update). */
    fun channel(ctx: Context) {
        if (channelReady) return
        channelReady = true
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_FOCUS, "Focus sessions", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while you're in a focus session" })
    }

    /** The quiet notification that shows the session's timer and brings you back to it. */
    fun notifyOngoing(ctx: Context) {
        val s = state(ctx) ?: return
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        val b = Notification.Builder(ctx, CH_FOCUS).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setContentIntent(open)
            .setContentTitle("Doing it now: ${s.title}")
        if (s.paused) b.setContentText(if (s.breakUntil > now) "On a break until ${Reminders.fmt(s.breakUntil)}" else "Paused")
        else b.setUsesChronometer(true).setWhen(now - s.worked(now)).setShowWhen(true).setContentText("No distractions. Tap to return.")
        try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build()) } catch (_: SecurityException) {}
    }
}

class FocusActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var timer: TextView
    private lateinit var sub: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var breakBtn: TextView
    private lateinit var orb: OrbView
    private var plannedChimed = false
    /** Whether the last update showed a break, so its end can chime. */
    private var wasOnBreak = false
    private val tick = object : Runnable { override fun run() { update(); h.postDelayed(this, 500) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // from a reminder's "Doing it now": start the session for it
        if (intent?.action == Reminders.ACTION_NOW) intent.getStringExtra("id")?.let { id ->
            Focus.begin(this, intent.getStringExtra("title") ?: "Focus", null, id)
        }
        val s = Focus.state(this) ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bg = Palette.NIGHT.bg
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        // Back does nothing here: the session ends with End
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { hint() }

        val serif = font(R.font.newsreader_regular, Typeface.SERIF)
        val light = font(R.font.newsreader_light, Typeface.SERIF)
        val med = font(R.font.geist_medium, Typeface.DEFAULT)
        val semi = font(R.font.geist_semibold, Typeface.DEFAULT_BOLD)
        val body = font(R.font.geist_regular, Typeface.DEFAULT)
        val d = resources.displayMetrics.density

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        col.addView(TextView(this).apply { text = "DOING IT NOW"; setTextColor(Palette.NIGHT.faint); textSize = 12f; typeface = semi; letterSpacing = 0.18f; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = s.title; setTextColor(Palette.NIGHT.fg); textSize = 30f; typeface = serif; gravity = Gravity.CENTER; setLineSpacing(0f, 1.05f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(TextView(this).apply { text = "No distractions. Just this."; setTextColor(Palette.NIGHT.muted); textSize = Type.READ; typeface = serif; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        orb = OrbView(this).apply { energy = 0.25f; still = Prefs.reduceMotion(this@FocusActivity) }
        col.addView(orb, LinearLayout.LayoutParams(dp(150), dp(150)))
        timer = TextView(this).apply { setTextColor(Palette.NIGHT.fg); textSize = 64f; typeface = light; gravity = Gravity.CENTER; letterSpacing = -0.01f; fontFeatureSettings = "tnum"  /* digits of equal width: the time doesn't jiggle as it counts */
            fontFeatureSettings = "tnum" }
        col.addView(timer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        sub = TextView(this).apply { setTextColor(Palette.NIGHT.muted); textSize = 14f; typeface = med; gravity = Gravity.CENTER }
        col.addView(sub, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun glass(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 16f; typeface = med; gravity = Gravity.CENTER; setTextColor(Palette.NIGHT.fg)
            setPadding(dp(14), dp(15), dp(14), dp(16)); background = Finish(Palette.NIGHT.surface2, -1f, true, d, glass = true)
            isClickable = true; setOnClickListener { onTap() }; pressFx()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = glass("Pause Session") { if (Focus.state(this)?.paused == true) Focus.resume(this) else Focus.pause(this); update() }
        breakBtn = glass("Take a Break") { breakChooser() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(breakBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        val end = TextView(this).apply {
            text = "End"; textSize = 22f; typeface = semi; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(22), dp(16), dp(23)); background = Finish(Palette.NIGHT.primaryBottom, -1f, true, d)
            elevation = dp(10).toFloat(); outlineSpotShadowColor = Color.parseColor("#6E5CFF"); outlineAmbientShadowColor = Color.parseColor("#6E5CFF")
            isClickable = true; setOnClickListener { finishSession() }; pressFx()
            contentDescription = "End the session"
        }
        col.addView(end, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val root = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0B0B22"), bg, Color.parseColor("#0A0820")))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins -> val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED }
        Focus.notifyOngoing(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Doing it now" on another reminder while a session is open: this session carries on (one at a time),
        // and that reminder's notification goes
        if (intent.action == Reminders.ACTION_NOW) intent.getStringExtra("id")?.let { id -> Focus.begin(this, intent.getStringExtra("title") ?: "Focus", null, id) }
        update()
    }

    @Deprecated("Deprecated in Java") override fun onBackPressed() { hint() }
    private fun hint() { sub.text = "Press End when you're finished."; h.postDelayed({ update() }, 2500) }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private fun fmt(ms: Long): String {
        val t = ms / 1000; val hh = t / 3600; val mm = (t % 3600) / 60; val ss = t % 60
        return if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%02d:%02d".format(mm, ss)
    }

    private fun update() {
        val s = Focus.state(this) ?: run { finish(); return }
        val now = System.currentTimeMillis()
        val onBreak = s.paused && s.breakUntil > now
        if (wasOnBreak && !onBreak && !s.paused) { chime(); Focus.notifyOngoing(this) }
        wasOnBreak = onBreak
        timer.text = if (onBreak) fmt(s.breakUntil - now) else fmt(s.worked(now))
        timer.alpha = if (s.paused && !onBreak) 0.55f else 1f
        orb.energy = if (s.paused) 0f else 0.25f
        val planned = s.plannedMin?.let { it * 60_000L }
        sub.text = when {
            onBreak -> "On a break. It ends by itself."
            s.paused -> "Paused"
            planned != null && s.worked(now) >= planned -> "Planned ${s.plannedMin} min done. Keep going, or End."
            planned != null -> "of ${s.plannedMin} min"
            else -> "focused"
        }
        if (planned != null && s.worked(now) >= planned && !plannedChimed) { plannedChimed = true; chime() }
        pauseBtn.text = if (s.paused && !onBreak) "Resume" else if (onBreak) "End Break" else "Pause Session"
        if (onBreak) pauseBtn.setOnClickListener { Focus.resume(this); update() }
        else pauseBtn.setOnClickListener { if (Focus.state(this)?.paused == true) Focus.resume(this) else Focus.pause(this); update() }
        breakBtn.alpha = if (s.paused) 0.5f else 1f; breakBtn.isEnabled = !s.paused
    }

    private fun breakChooser() {
        val opts = arrayOf("5 minutes", "10 minutes", "15 minutes")
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Take a break")
            .setItems(opts) { _, i -> Focus.takeBreak(this, intArrayOf(5, 10, 15)[i]); update() }.show()
    }

    private fun chime() {
        try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(); play() } } catch (_: Exception) {}
    }

    private fun finishSession() {
        val mins = Focus.end(this)
        AvatarService.celebrate(this)
        android.widget.Toast.makeText(this, if (mins >= 1) "Session done: $mins min. Nice work." else "Session ended.", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}
