package com.anjy.atlas

import android.app.Activity
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anjy.atlas.core.Engine
import kotlin.math.roundToInt

/**
 * The alarm: rings, and asks whether you'll do it. Opens over the lock screen.
 * Doing it now (opens a focus session), I'll do it, Snooze 10 min, or Not doing it; the answer goes in the Log.
 * It rings for two minutes at most. Opened from a lighter reminder's notification it's quiet ([EXTRA_QUIET]).
 */
class AlarmActivity : Activity() {
    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_QUIET = "quiet"
        const val NOTIF_ID = 4242
        private const val RING_MS = 2 * 60 * 1000L
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private lateinit var titleView: TextView
    private lateinit var whenView: TextView
    private lateinit var moreView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val bg = Palette.NIGHT.bg
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        queue.addAll(intent?.getStringArrayExtra(EXTRA_IDS)?.toList() ?: emptyList())

        val display = font(R.font.newsreader_regular, Typeface.create("serif", Typeface.NORMAL))
        val body = font(R.font.geist_regular, Typeface.DEFAULT)
        val med = font(R.font.geist_medium, Typeface.DEFAULT)
        val semi = font(R.font.geist_semibold, Typeface.DEFAULT_BOLD)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(32), dp(22), dp(26)) }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(OrbView(this).apply { energy = 1f; still = Prefs.reduceMotion(this@AlarmActivity) }, LinearLayout.LayoutParams(dp(176), dp(176)))

        // the question, on a pane of glass
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = Finish(Palette.NIGHT.surface2, dp(28).toFloat(), true, d, glass = true)
        }
        whenView = TextView(this).apply { setTextColor(Palette.NIGHT.muted); textSize = 12f; typeface = semi; gravity = Gravity.CENTER; letterSpacing = 0.16f; isAllCaps = true }
        pane.addView(whenView, LinearLayout.LayoutParams(-1, -2))
        titleView = TextView(this).apply { setTextColor(Palette.NIGHT.fg); textSize = 30f; typeface = display; gravity = Gravity.CENTER; setLineSpacing(0f, 1.04f) }
        pane.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pane.addView(TextView(this).apply { text = "Will you do it?"; setTextColor(Palette.NIGHT.muted); textSize = Type.READ; typeface = display; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        moreView = TextView(this).apply { setTextColor(Palette.NIGHT.faint); textSize = 13f; typeface = body; gravity = Gravity.CENTER }
        pane.addView(moreView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(pane, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun button(label: String, primary: Boolean, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = if (primary) 17f else 15f; typeface = if (primary) semi else med; gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else Palette.NIGHT.fg)
            setPadding(dp(14), dp(if (primary) 17 else 14), dp(14), dp(if (primary) 18 else 15))
            background = if (primary) Finish(Palette.NIGHT.primaryBottom, -1f, true, d) else Finish(Palette.NIGHT.surface2, -1f, true, d, glass = true)
            if (primary) { elevation = dp(10).toFloat(); outlineSpotShadowColor = Color.parseColor("#6E5CFF"); outlineAmbientShadowColor = Color.parseColor("#6E5CFF") }
            isClickable = true; setOnClickListener { onTap() }; pressFx()
        }
        col.addView(button("Doing It Now", true) { answer("now") }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("I'll Do It", false) { answer("done") }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Snooze 10m", false) { answer("snooze") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        row.addView(button("Not Doing It", false) { answer("skip") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val root = FrameLayout(this).apply {
            // a deep night with a soft violet glow behind the orb
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0A0A20"), bg, Color.parseColor("#08061A"))),
                GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(320).toFloat(); setGradientCenter(0.5f, 0.36f)
                    colors = intArrayOf(Color.argb(70, 80, 70, 255), Color.argb(0, 80, 70, 255)) }))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        quiet = intent?.getBooleanExtra(EXTRA_QUIET, false) == true
        if (!showNext()) { finish(); return }
        if (!quiet) startRinging()
    }

    private var quiet = false

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringArrayExtra(EXTRA_IDS)?.forEach { if (it !in queue) queue.addLast(it) }
        if (titleView.text.isNullOrEmpty()) showNext()
        if (!intent.getBooleanExtra(EXTRA_QUIET, false)) startRinging()
    }

    /** Shows the next reminder still waiting; false when there's none. */
    private fun showNext(): Boolean {
        while (queue.isNotEmpty()) {
            val id = queue.first()
            val t = Store.read(this) { s -> s.tasks.firstOrNull { it.id == id }?.let { Triple(it.title, it.startAt, it.status) } }
            if (t == null || t.third != "planned") { queue.removeFirst(); continue }
            titleView.text = Plain.memory(t.first)
            whenView.text = t.second?.let { "${Brain.dayLabel(it).replaceFirstChar { c -> c.uppercase() }} · ${Reminders.fmt(it)}" } ?: ""
            moreView.text = if (queue.size > 1) "${queue.size - 1} more after this" else ""
            return true
        }
        return false
    }

    private fun answer(kind: String) {
        val id = queue.removeFirstOrNull() ?: return finish()
        val now = System.currentTimeMillis()
        if (kind == "now") {
            // a focus session for it; it's marked done when the session ends
            stopRinging()
            Focus.start(this, titleView.text.toString(), null, id)
            if (!showNext()) finish()
            return
        }
        Thread {
            try {
                Store.edit(applicationContext) { s ->
                    when (kind) {
                        "done" -> { Engine.respond(s, id, "done", now); Unit }
                        "snooze" -> { Engine.respond(s, id, "delay", now, startAt = now + 10 * Engine.MIN); Unit }
                        else -> { Engine.respond(s, id, "skipped", now, reason = "Said not doing it"); Unit }
                    }
                }
                Reminders.tickAndSchedule(applicationContext)
            } catch (e: Exception) { android.util.Log.w("AtlasAlarms", "answer", e) }
        }.start()
        if (kind == "done") AvatarService.celebrate(this)
        if (!showNext()) { stopRinging(); finish() }
    }

    private fun startRinging() {
        if (player != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmActivity, uri); isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { android.util.Log.w("AtlasAlarms", "no alarm sound", e) }
        try {
            vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) {}
        handler.postDelayed({ stopRinging() }, RING_MS)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (!Store.read(this) { it.settings.sound }) try { player?.setVolume(0f, 0f) } catch (_: Exception) {}
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    override fun onDestroy() { stopRinging(); super.onDestroy() }
}
