package com.anjy.atlas

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

/**
 * Atlas Bot's page. Without a job: every job, newest first, and a box to send a new one. With a job ([EXTRA_ID]):
 * its goal, where it stands, its plan with ticks, what it did, and the report when it's done, with the keys the
 * moment calls for (Pause, Resume, Stop, Approve, Deny, an answer box).
 */
class BotActivity : Activity() {
    companion object { const val EXTRA_ID = "bot_id" }

    private val BG = Palette.NIGHT.bg
    private val FG = Palette.NIGHT.fg
    private val MUTED = Palette.NIGHT.muted
    private val ACCENT = Palette.NIGHT.accentInk
    private val GOOD = Palette.NIGHT.done
    private val WARN = Palette.NIGHT.warm
    private val BAD = Palette.NIGHT.danger
    private val SURFACE = Palette.NIGHT.surface2

    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private var jobId: String? = null
    private var lastSig = ""
    /** What's typed in the answer or new-job box, kept when the page redraws. */
    private var draft = ""

    private val serif by lazy { font(R.font.newsreader_regular, Typeface.SERIF) }
    private val med by lazy { font(R.font.geist_medium, Typeface.DEFAULT) }
    private val semi by lazy { font(R.font.geist_semibold, Typeface.DEFAULT_BOLD) }
    private val body by lazy { font(R.font.geist_regular, Typeface.DEFAULT) }
    private val mono by lazy { font(R.font.geist_mono, Typeface.MONOSPACE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }

    private val onBots: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bots.init(applicationContext)
        jobId = intent?.getStringExtra(EXTRA_ID)
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(32)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val root = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.NIGHT.glowTop, BG, Palette.NIGHT.glowBottom))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        jobId = intent.getStringExtra(EXTRA_ID); draft = ""; render()
    }

    override fun onResume() { super.onResume(); Bots.listen(onBots); refresh() }
    override fun onPause() { Bots.unlisten(onBots); super.onPause() }

    // ---------------------------------------------------------------- drawing

    private fun sig(): String = Bots.jobs.joinToString("|") { "${it.id}:${it.state}:${it.current}:${it.steps.size}:${it.plan.count { p -> p.done }}/${it.plan.size}:${it.report?.length}" }

    private fun refresh() { val s = sig(); if (s != lastSig) render() }

    private fun render() {
        lastSig = sig()
        // keep what's being typed
        (col.findViewWithTag<EditText>("draft"))?.let { draft = it.text.toString() }
        val hadFocus = currentFocus is EditText
        col.removeAllViews()
        val j = Bots.job(jobId)
        if (jobId != null && j == null) { jobId = null }
        if (j == null) list() else detail(j)
        if (hadFocus) col.findViewWithTag<EditText>("draft")?.let { it.requestFocus(); it.setSelection(it.text.length) }
    }

    private fun text(s: CharSequence, size: Float, color: Int = FG, face: Typeface = body) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; setLineSpacing(0f, 1.25f)
    }

    private fun add(v: View, top: Int = 0) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = Finish(SURFACE, dp(20).toFloat(), true, resources.displayMetrics.density, glass = true)
    }

    private fun button(label: String, primary: Boolean = false, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = if (primary) semi else med; gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.NIGHT.onPrimary else FG); setPadding(dp(14), dp(13), dp(14), dp(14))
        background = Finish(if (primary) Palette.NIGHT.primaryBottom else SURFACE, -1f, true, resources.displayMetrics.density, glass = !primary)
        isClickable = true; setOnClickListener { onTap() }; pressFx()
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10) }) }
    }

    private fun header(title: String) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("‹", 30f, FG, serif).apply { setPadding(dp(4), 0, dp(14), dp(4)); contentDescription = "Back"; isClickable = true; setOnClickListener { finish() } })
        top.addView(text(title, 26f, FG, serif), LinearLayout.LayoutParams(0, -2, 1f))
        add(top)
    }

    private fun stateColor(s: String) = when (s) {
        BotRules.COMPLETED -> GOOD
        BotRules.FAILED -> BAD
        in BotRules.WAITING_ON_USER -> WARN
        BotRules.CANCELLED, BotRules.PAUSED -> MUTED
        else -> ACCENT
    }

    private fun input(hint: String, onSend: (String) -> Unit): View {
        val field = EditText(this).apply {
            tag = "draft"; setText(draft); this.hint = hint; setHintTextColor(MUTED); setTextColor(FG); textSize = 15f; typeface = body
            background = null; setPadding(0, dp(4), 0, dp(4)); minLines = 2; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val c = card()
        c.addView(field, LinearLayout.LayoutParams(-1, -2))
        c.addView(button("Send", primary = true) {
            val t = field.text.toString().trim()
            if (t.isNotEmpty()) { draft = ""; field.setText(""); onSend(t) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return c
    }

    // ---------------------------------------------------------------- every job

    private fun list() {
        header("Atlas Bot")
        add(text("Jobs Atlas sent a bot to do. They keep working when you leave, and each report lands in your chat.", 15f, MUTED), 4)
        add(input("Send a bot to… (e.g. find the three best budget laptops for coding, with sources)") { goal ->
            Bots.deploy(applicationContext, goal, "")
            render()
        }, 16)
        // routines, skills, the team (BotExtrasActivity)
        add(row(button("Routines") { startActivity(BotExtrasActivity.intent(this, "routines")) }, button("Skills") { startActivity(BotExtrasActivity.intent(this, "skills")) },
            button("Team") { startActivity(BotExtrasActivity.intent(this, "team")) }), 12)
        val all = Bots.visible()
        if (all.isEmpty()) {
            add(card().apply {
                addView(text("No bots yet", 18f, FG, serif))
                addView(text("Ask Atlas for something bigger, like “research the best phone under $300 for photos and send me a report”, or say “send a bot to…”.", 14f, MUTED),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }, 16)
            return
        }
        all.forEach { j ->
            val c = card().apply { isClickable = true; setOnClickListener { open(j) }; pressFx() }
            c.addView(text(j.goal, 17f, FG, serif).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            val line = listOfNotNull(BotRules.label(j.state), if (j.state == BotRules.RUNNING) j.current else null,
                if (j.plan.isNotEmpty()) "plan ${j.plan.count { it.done }}/${j.plan.size}" else null).joinToString(" · ")
            c.addView(text(line, 13f, stateColor(j.state), med).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            add(c, 12)
        }
    }

    private fun open(j: BotJob) {
        startActivity(Intent(this, BotActivity::class.java).putExtra(EXTRA_ID, j.id))
    }

    // ---------------------------------------------------------------- one job

    private fun detail(j: BotJob) {
        header("Atlas Bot")
        add(text(j.goal, 24f, FG, serif), 8)
        if (j.details.isNotBlank()) add(text(j.details, 14f, MUTED).apply { maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END }, 6)
        val now = when (j.state) {
            BotRules.RUNNING -> "${BotRules.label(j.state)}: ${j.current ?: "Thinking"}"
            BotRules.WAITING_RETRY -> "${BotRules.label(j.state)} at ${Reminders.fmt(j.retryAt)}${j.error?.let { " ($it)" } ?: ""}"
            BotRules.FAILED -> "${BotRules.label(j.state)}: ${j.error ?: ""}"
            else -> BotRules.label(j.state)
        }
        add(text(now, 14f, stateColor(j.state), med), 10)
        add(text("${j.toolCalls} steps · ${j.workMs / 60_000} min${j.provider?.let { " · $it" } ?: ""}", 12f, MUTED), 2)

        // a heavy job: the laptop would do it better
        j.suggestPc?.let { why ->
            if (!j.final) add(card().apply {
                addView(text("This is a big one", 13f, ACCENT, semi))
                addView(text("Open Atlas on your laptop and I can do it there. ($why)", 15f, FG), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }, 14)
        }

        // what it needs from you
        when (j.state) {
            BotRules.AWAITING_APPROVAL -> j.approval?.let { a ->
                add(card().apply {
                    addView(text("It wants to:", 13f, WARN, semi))
                    addView(text(a.what, 17f, FG, serif), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    // Allow once, or always allow this kind of action (the standing choice in Me → Bot Permissions)
                    val kind = a.category?.let { BotExtras.Rules.label(it) }
                    if (kind != null) addView(text("Kind: $kind", 12f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                    addView(row(button("Deny") { Bots.approve(j.id, false) }, button("Allow Once", primary = true) { Bots.approve(j.id, true) }),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                    // only where "always" can really be given (never money or the always-ask kinds: Bots.approve and AutonomyRules.makeGrant refuse them),
                    // named by the kind the grant is made for, so the promise is true
                    val ak = if (a.mustAsk) null else try { a.kind ?: Autonomy.kindFor(applicationContext, a.tool, a.args, a.category, a.what) } catch (_: Exception) { null }
                    if (ak != null && AutonomyRules.tierOf(ak).let { it == "low" || it == "medium" }) addView(button("Always Allow This Kind") {
                        android.app.AlertDialog.Builder(this@BotActivity).setTitle("Always Allow This Kind?")
                            .setMessage("Bots won't ask again before: ${AutonomyRules.kindLabel(ak).lowercase()}. You can change this in Me → Permissions.")
                            .setPositiveButton("Always Allow") { _, _ -> Bots.approve(j.id, true, always = true) }.setNegativeButton("Cancel", null).show()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }, 14)
            }
            BotRules.AWAITING_ANSWER -> {
                add(card().apply {
                    addView(text("It asks:", 13f, WARN, semi))
                    addView(text(j.question ?: "", 17f, FG, serif), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, 14)
                add(input("Your answer") { Bots.answer(j.id, it) }, 10)
            }
            BotRules.NEEDS_REVIEW -> add(card().apply {
                addView(text("Atlas was closed while the bot was doing: ${j.pending ?: "a step"}. It may or may not have happened. Check, then carry on or stop.", 15f, FG))
                addView(row(button("Stop") { Bots.stop(j.id) }, button("Carry On", primary = true) { Bots.carryOn(j.id) }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }, 14)
        }

        // the keys for where it is
        when {
            j.state == BotRules.PAUSED -> add(row(button("Stop") { Bots.stop(j.id) }, button("Resume", primary = true) { Bots.resume(j.id) }), 14)
            !j.final && j.state !in BotRules.WAITING_ON_USER -> add(row(button("Pause") { Bots.pause(j.id) }, button("Stop") { Bots.stop(j.id) }), 14)
            j.state == BotRules.AWAITING_APPROVAL || j.state == BotRules.AWAITING_ANSWER -> add(row(button("Pause") { Bots.pause(j.id) }, button("Stop") { Bots.stop(j.id) }), 10)
        }

        // talk to it while it works, or ask it to wrap up (not while it waits on an answer: that box is above)
        if (!j.final && j.state != BotRules.AWAITING_ANSWER) {
            add(input("Tell the bot something (it reads it at its next step)") { t -> if (Bots.steer(j.id, t)) android.widget.Toast.makeText(this, "Sent: it reads it at its next step.", android.widget.Toast.LENGTH_SHORT).show() }, 12)
            add(button(if (j.wrapUp) "Wrapping Up…" else "Wrap Up Now") { if (!j.wrapUp) Bots.wrapUp(j.id) }, 8)
        }

        // its helpers (specialist bots it handed parts to)
        val kids = Bots.helpers(j.id)
        if (kids.isNotEmpty()) {
            add(text("HELPERS", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 22)
            kids.forEach { k ->
                val who = k.specialist?.let { BotExtras.specialist(it)?.optString("name") }
                add(card().apply {
                    isClickable = true; setOnClickListener { open(k) }; pressFx()
                    addView(text(k.goal, 15f, FG, serif).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(listOfNotNull(who, BotRules.label(k.state)).joinToString(" · "), 12f, stateColor(k.state), med), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, 8)
            }
        }

        // the bot's web browser: watch it work, or take over (sign in, pass a human check)
        if (BotBrowser.live(j.id)) {
            val waiting = j.state == BotRules.AWAITING_ANSWER
            add(row(button(if (waiting) "Open the Browser" else "Watch the Browser", primary = waiting) {
                startActivity(BotBrowserActivity.intent(this, j.id))
            }), 12)
        }

        // files the bot made or downloaded: kept with the job; open, share, or save to the phone's Downloads
        val got = BotBrowser.files(this, j.id)
        if (got.isNotEmpty()) {
            add(text("FILES", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 22)
            got.forEach { f ->
                add(card().apply {
                    addView(text(f.name, 15f, FG, med).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    addView(text("${(f.length() / 1024).coerceAtLeast(1)} KB", 12f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                    addView(LinearLayout(this@BotActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(button("Open", primary = true) {
                            if (BotFileRules.ext(f.name) in setOf("html", "htm")) startActivity(PageViewActivity.intent(this@BotActivity, f))
                            else if (!BotFileProvider.open(this@BotActivity, f)) android.widget.Toast.makeText(this@BotActivity, "No app on this phone opens that kind of file", android.widget.Toast.LENGTH_SHORT).show()
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(button("Share") { BotFileProvider.share(this@BotActivity, f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                    addView(button("Save to Downloads") {
                        // a download can be up to 50 MB: copy it off the main thread, or the screen freezes ("isn't responding")
                        val app = applicationContext
                        Thread {
                            val where = BotBrowser.saveToDownloads(app, f)
                            runOnUiThread { android.widget.Toast.makeText(app, where?.let { "Saved to $it" } ?: "Couldn't save it", android.widget.Toast.LENGTH_SHORT).show() }
                        }.start()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }, 8)
            }
        }

        // the report
        j.report?.let { r ->
            add(text("REPORT", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 22)
            val st = Markdown.Style(serif, Type.serifSemi(this), Type.serifMedium(this), mono, FG, MUTED, ACCENT, Color.argb(40, 255, 255, 255), resources.displayMetrics.density, Type.READ, italic = Type.serifItalic(this))
            Markdown.parse(r, st).forEach { b ->
                when (b) {
                    is Markdown.Prose -> add(text(b.text, Type.READ, FG, serif).apply { setLineSpacing(0f, Type.LEAD_READ); setTextIsSelectable(true)
                        movementMethod = android.text.method.LinkMovementMethod.getInstance() }, 8)
                    is Markdown.Code -> add(text(b.text, 13.5f, FG, mono).apply { setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = Finish(SURFACE, dp(12).toFloat(), true, resources.displayMetrics.density, glass = true); setTextIsSelectable(true) }, 8)
                }
            }
        }

        // the plan
        if (j.plan.isNotEmpty()) {
            add(text("PLAN", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 22)
            j.plan.forEach { p ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(text(if (p.done) "✓" else "○", 15f, if (p.done) GOOD else MUTED, semi), LinearLayout.LayoutParams(dp(26), -2))
                r.addView(text(p.text, 15f, if (p.done) MUTED else FG), LinearLayout.LayoutParams(0, -2, 1f))
                add(r, 8)
            }
        }

        // what it did
        if (j.steps.isNotEmpty()) {
            add(text("WHAT IT DID", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 22)
            val shown = j.steps.takeLast(15)
            if (j.steps.size > shown.size) add(text("and ${j.steps.size - shown.size} earlier steps", 13f, MUTED), 6)
            shown.forEach { add(text("· $it", 14f, MUTED), 6) }
        }

        // a finished job can become a skill: a first draft from what it did, to edit
        if (j.state == BotRules.COMPLETED && j.parent == null) add(button("Save as a Skill") {
            startActivity(BotExtrasActivity.intent(this, "skills").putExtra(BotExtrasActivity.EXTRA_SKILL, BotExtras.Rules.draftSkill(j).toString()))
        }, 22)

        if (j.final) add(button("Remove From List") { Bots.remove(j.id); finish() }, 24)
    }
}
