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
 * I'll do it, Snooze 10 min, or Not doing it; the answer goes in the Log. It rings for two minutes at most.
 */
class AlarmActivity : Activity() {
    companion object {
        const val EXTRA_IDS = "ids"
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
        val bg = Color.parseColor("#04040C")
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        queue.addAll(intent?.getStringArrayExtra(EXTRA_IDS)?.toList() ?: emptyList())

        val display = font(R.font.unbounded_500, Typeface.DEFAULT_BOLD)
        val body = font(R.font.geist_regular, Typeface.DEFAULT)
        val med = font(R.font.geist_medium, Typeface.DEFAULT)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(40), dp(28), dp(32)) }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(OrbView(this).apply { energy = 1f }, LinearLayout.LayoutParams(dp(190), dp(190)))
        whenView = TextView(this).apply { setTextColor(Color.parseColor("#9BA1C9")); textSize = 16f; typeface = med; gravity = Gravity.CENTER }
        col.addView(whenView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        titleView = TextView(this).apply { setTextColor(Color.parseColor("#EEF0FF")); textSize = 28f; typeface = display; gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }
        col.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(TextView(this).apply { text = "Will you do it?"; setTextColor(Color.parseColor("#C9CDF0")); textSize = 18f; typeface = body; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        moreView = TextView(this).apply { setTextColor(Color.parseColor("#9BA1C9")); textSize = 13f; typeface = body; gravity = Gravity.CENTER }
        col.addView(moreView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun button(label: String, primary: Boolean, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 17f; typeface = med; gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#EEF0FF"))
            setPadding(dp(16), dp(16), dp(16), dp(17))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (primary) { orientation = GradientDrawable.Orientation.LEFT_RIGHT; colors = intArrayOf(Color.parseColor("#3D5BFF"), Color.parseColor("#7C5CFF")) }
                else { setColor(Color.parseColor("#14163A")); setStroke(dp(1), Color.parseColor("#2A2E66")) }
            }
            isClickable = true; setOnClickListener { onTap() }
        }
        col.addView(button("I'll do it", true) { answer("done") }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Snooze 10 min", false) { answer("snooze") }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Not doing it", false) { answer("skip") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        if (!showNext()) { finish(); return }
        startRinging()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringArrayExtra(EXTRA_IDS)?.forEach { if (it !in queue) queue.addLast(it) }
        if (titleView.text.isNullOrEmpty()) showNext()
        startRinging()
    }

    /** Shows the next reminder still waiting; false when there's none. */
    private fun showNext(): Boolean {
        while (queue.isNotEmpty()) {
            val id = queue.first()
            val t = Store.read(this) { s -> s.tasks.firstOrNull { it.id == id }?.let { Triple(it.title, it.startAt, it.status) } }
            if (t == null || t.third != "planned") { queue.removeFirst(); continue }
            titleView.text = t.first
            whenView.text = t.second?.let { "${Brain.dayLabel(it).replaceFirstChar { c -> c.uppercase() }} · ${Reminders.fmt(it)}" } ?: ""
            moreView.text = if (queue.size > 1) "${queue.size - 1} more after this" else ""
            return true
        }
        return false
    }

    private fun answer(kind: String) {
        val id = queue.removeFirstOrNull() ?: return finish()
        val now = System.currentTimeMillis()
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
