package com.anjy.atlas

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

/**
 * The bot's browser on screen: watch what Atlas Bot is doing on a website, or take over (sign in, pass a human check,
 * fix something) and hand it back. While watching, touches are held back so the bot isn't disturbed; Take Over pauses
 * the job and gives you the page. Hand Back carries the job on (and, when it was waiting for you, tells it you're done).
 */
class BotBrowserActivity : Activity() {
    companion object {
        const val EXTRA_ID = "bot_id"
        fun intent(ctx: Context, jobId: String?): Intent =
            Intent(ctx, BotBrowserActivity::class.java).putExtra(EXTRA_ID, jobId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun openIntent(ctx: Context, jobId: String, code: Int): PendingIntent =
            PendingIntent.getActivity(ctx, code + 1, intent(ctx, jobId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private val P = Palette.NIGHT
    private var jobId: String? = null
    /** True once the user has taken over (the job is paused, or was already waiting for them). */
    private var mine = false
    private var pausedByUs = false
    private lateinit var site: TextView
    private lateinit var status: TextView
    private lateinit var key: TextView
    private lateinit var holder: FrameLayout
    private lateinit var shield: View

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private val onChange: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bots.init(applicationContext)
        jobId = intent?.getStringExtra(EXTRA_ID) ?: BotBrowser.owner
        window.statusBarColor = P.bg; window.navigationBarColor = P.bg
        window.setDecorFitsSystemWindows(false)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            val v = BotBrowser.view()
            if (mine && v != null && v.canGoBack()) v.goBack() else finish()
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; setTextColor(P.fg); typeface = Type.serif(this@BotBrowserActivity)
            setPadding(dp(2), 0, dp(12), dp(4)); contentDescription = "Close"; isClickable = true; setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "Bot's Browser"; textSize = 19f; setTextColor(P.fg); typeface = Type.serifMedium(this@BotBrowserActivity) })
        site = TextView(this).apply { textSize = 13f; setTextColor(P.muted); typeface = Type.sans(this@BotBrowserActivity); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        titles.addView(site)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        key = TextView(this).apply {
            textSize = 14f; typeface = Type.sansSemi(this@BotBrowserActivity); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(11))
            background = Finish(P.primaryBottom, -1f, true, resources.displayMetrics.density, glass = false)
            isClickable = true; setOnClickListener { if (mine) handBack() else takeOver() }
            pressFx()
        }
        top.addView(key)
        status = TextView(this).apply { textSize = 13f; setTextColor(P.muted); typeface = Type.sans(this@BotBrowserActivity); setPadding(dp(16), 0, dp(16), dp(8)) }

        holder = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        // while watching, the page is shown but touches don't reach it
        shield = View(this).apply { isClickable = true; setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) status.text = "Watching. Tap Take Over to use the page yourself."; true } }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(P.bg) }
        col.addView(top); col.addView(status)
        col.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        col.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        val web = BotBrowser.attach(this)
        if (web == null) {
            holder.setBackgroundColor(P.bg)
            holder.addView(TextView(this).apply {
                text = "The bot's browser isn't open right now. It opens when a bot works on a website."
                textSize = 16f; setTextColor(P.muted); typeface = Type.serif(this@BotBrowserActivity); gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0)
            }, FrameLayout.LayoutParams(-1, -1))
            key.visibility = View.GONE
        } else {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            holder.addView(web, FrameLayout.LayoutParams(-1, -1))
            holder.addView(shield, FrameLayout.LayoutParams(-1, -1))
            // the bot was waiting for the user (a sign-in, a human check): go straight to taking over
            if (Bots.job(jobId)?.state == BotRules.AWAITING_ANSWER) takeOver()
        }
        refresh()
    }

    override fun onResume() { super.onResume(); BotBrowser.listen(onChange); Bots.listen(onChange); refresh() }
    override fun onPause() { BotBrowser.unlisten(onChange); Bots.unlisten(onChange); super.onPause() }

    override fun onDestroy() {
        // leaving without handing back: a job we paused carries on, a job waiting for the user keeps waiting
        if (pausedByUs) jobId?.let { Bots.resume(it) }
        BotBrowser.detach()
        super.onDestroy()
    }

    private fun refresh() {
        if (!::site.isInitialized) return
        site.text = BotBrowser.currentUrl()?.let { BrowserRules.host(it).ifBlank { it } } ?: ""
        val j = Bots.job(jobId)
        key.text = if (mine) "Hand Back" else "Take Over"
        status.text = when {
            mine && j?.state == BotRules.AWAITING_ANSWER -> j.question ?: "Do what's needed, then tap Hand Back."
            mine -> "You have the page. Tap Hand Back when you're done."
            j?.state == BotRules.RUNNING -> "Watching: ${j.current ?: "working"}"
            j != null -> "Watching. The bot is ${BotRules.label(j.state).lowercase()}."
            else -> "Watching."
        }
    }

    private fun takeOver() {
        val j = Bots.job(jobId)
        if (j != null && (j.state == BotRules.RUNNING || j.state == BotRules.QUEUED)) { Bots.pause(j.id); pausedByUs = true }
        mine = true
        shield.visibility = View.GONE
        refresh()
    }

    private fun handBack() {
        val j = Bots.job(jobId)
        when {
            j?.state == BotRules.AWAITING_ANSWER -> Bots.answer(j.id, "Done: I did it myself in your browser (signed in or finished the check). Read the page again and carry on.")
            pausedByUs && j != null -> { j.notes.add("The user took over your browser for a moment. Read the page again before carrying on: it may have changed."); Bots.resume(j.id) }
        }
        pausedByUs = false; mine = false
        finish()
    }
}
