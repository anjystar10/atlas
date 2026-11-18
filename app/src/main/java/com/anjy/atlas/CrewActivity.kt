package com.anjy.atlas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The Bots tab's pages, built like Grok Bot's (the list itself is on the Bots tab in MainActivity):
 *  - a CONVERSATION ([MODE_CHAT]): a bot's own chat ("dm-<botId>", the main way to work with it) or a group chat.
 *    You talk to it; its progress, questions, approval cards and reports arrive inline. Your message always comes
 *    first and can redirect what it's doing. Stop is in the header while it works.
 *  - a bot's DETAILS ([MODE_INFO]): its screen (watch live and take over), routines and triggers, skills, budget and
 *    usage, what it remembers (read here; changed by telling it), its work (missions and their agents) and settings.
 *  - NEW BOT ([MODE_NEW_BOT]): one tap from a ready-made bot (BotTemplates), or describe your own.
 *  - NEW GROUP ([MODE_NEW_GROUP]): you and 2–6 bots in one conversation.
 * Always the night look (like the bot's job page): glass panels, the orb, Newsreader for what bots say, Geist for the rest.
 */
class CrewActivity : Activity() {
    companion object {
        const val MODE_CHAT = "chat"; const val MODE_INFO = "info"; const val MODE_NEW_BOT = "newbot"; const val MODE_NEW_GROUP = "newgroup"
        private const val EXTRA_MODE = "mode"; private const val EXTRA_ID = "id"
        /** A bot's own conversation. */
        fun bot(ctx: Context, botId: String) = Intent(ctx, CrewActivity::class.java).putExtra(EXTRA_MODE, MODE_CHAT).putExtra(EXTRA_ID, ThreadRules.directId(botId))
        /** A conversation by its thread id (a group chat, or "dm-<botId>"). */
        fun thread(ctx: Context, threadId: String) = Intent(ctx, CrewActivity::class.java).putExtra(EXTRA_MODE, MODE_CHAT).putExtra(EXTRA_ID, threadId)
        fun info(ctx: Context, botId: String) = Intent(ctx, CrewActivity::class.java).putExtra(EXTRA_MODE, MODE_INFO).putExtra(EXTRA_ID, botId)
        fun newBot(ctx: Context) = Intent(ctx, CrewActivity::class.java).putExtra(EXTRA_MODE, MODE_NEW_BOT)
        fun newGroup(ctx: Context) = Intent(ctx, CrewActivity::class.java).putExtra(EXTRA_MODE, MODE_NEW_GROUP)

        private val WAITING = BotRules.WAITING_ON_USER
        private val RUNNING = setOf(BotRules.RUNNING, BotRules.QUEUED, BotRules.WAITING_RETRY, BotRules.WAITING_HELPERS)

        /** Where a bot stands: (label, tone) with tone live | warn | idle | off. Shared with the Bots tab's list. */
        fun statusOf(botId: String): Pair<String, String> {
            val b = BotExtras.specialist(botId)
            if (b?.optString("state") == "paused") return "Paused" to "off"
            val ms = Threads.missionsOf(botId)
            if (ms.any { it.state in WAITING }) return "Waiting on you" to "warn"
            if (ms.any { it.state in RUNNING }) return "Working" to "live"
            if (!Crew.budgetCheck(b?.optJSONObject("budget"), Bots.usageOf(botId)).optBoolean("ok", true)) return "Budget used today" to "warn"
            return "Idle" to "idle"
        }
    }

    private val P get() = Palette.NIGHT
    private val BG get() = P.bg
    private val FG get() = P.fg
    private val MUTED get() = P.muted
    private val FAINT get() = P.faint
    private val ACCENT get() = P.accentInk
    private val WARN get() = P.warm
    private val GOOD get() = P.done
    private val BAD get() = P.danger
    private val SURFACE get() = P.surface2

    private val serif by lazy { font(R.font.newsreader_regular, Typeface.SERIF) }
    private val med by lazy { font(R.font.geist_medium, Typeface.DEFAULT) }
    private val semi by lazy { font(R.font.geist_semibold, Typeface.DEFAULT_BOLD) }
    private val body by lazy { font(R.font.geist_regular, Typeface.DEFAULT) }
    private val mono by lazy { font(R.font.geist_mono, Typeface.MONOSPACE) }
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val density get() = resources.displayMetrics.density

    private var mode = MODE_CHAT
    private var id: String? = null
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var footer: LinearLayout
    private var input: EditText? = null
    private var draft = ""
    private var fresh = false
    private var lastSig = ""
    // new bot / new group / add trigger drafts
    private var nbName = ""; private var nbRole = ""; private var nbInstr = ""; private var nbIcon = ""
    private var ngTitle = ""; private val ngBots = linkedSetOf<String>()
    private var addingTrigger = false; private var trType = "schedule"; private val trFields = HashMap<String, String>()

    private val onChange: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bots.init(applicationContext); Threads.init(applicationContext)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CHAT
        id = intent?.getStringExtra(EXTRA_ID)
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(24)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); isFillViewport = true }
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(P.glowTop, BG, P.glowBottom))
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
        render()
    }

    override fun onResume() { super.onResume(); Bots.listen(onChange); Threads.listen(onChange); BotExtras.listen(onChange); refresh() }
    override fun onPause() { Bots.unlisten(onChange); Threads.unlisten(onChange); BotExtras.unlisten(onChange); super.onPause() }

    private fun go(i: Intent) { startActivity(i) }

    // ---------------------------------------------------------------- pieces

    private fun text(s: CharSequence, size: Float, color: Int = FG, face: Typeface = body) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; setLineSpacing(0f, 1.25f)
    }
    private fun add(v: View, top: Int = 0) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun eyebrow(s: String) = text(s.uppercase(), 12f, MUTED, semi).apply { letterSpacing = 0.12f }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = Finish(SURFACE, dp(22).toFloat(), true, density, glass = true)
    }
    private fun button(label: String, kind: String = "quiet", onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = if (kind == "primary") semi else med; gravity = Gravity.CENTER
        setTextColor(when (kind) { "primary" -> P.onPrimary; "danger" -> BAD; else -> FG })
        setPadding(dp(16), dp(11), dp(16), dp(12)); minHeight = dp(44)
        background = Finish(if (kind == "primary") P.primaryBottom else SURFACE, -1f, true, density, glass = kind != "primary")
        isClickable = true; setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }; pressFx()
    }
    private fun chip(label: String, on: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = med; gravity = Gravity.CENTER
        setTextColor(if (on) P.onPrimary else FG); setPadding(dp(14), dp(8), dp(14), dp(9)); minHeight = dp(40)
        background = Finish(if (on) P.primaryBottom else SURFACE, -1f, true, density, glass = !on)
        isClickable = true; setOnClickListener { onTap() }; pressFx()
        contentDescription = label + if (on) ", selected" else ""
    }
    private fun wrapRow(views: List<View>): View {
        // chips that wrap onto more lines on a narrow phone
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var used = 0
        val avail = resources.displayMetrics.widthPixels - dp(84)
        for (v in views) {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = v.measuredWidth + dp(8)
            if (used > 0 && used + w > avail) { box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }); row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; used = 0 }
            row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }); used += w
        }
        if (row.childCount > 0) box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return box
    }
    private fun field(hint: String, value: String, lines: Int = 1, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 16f; typeface = body; setTextColor(FG); setHintTextColor(FAINT)
        background = Finish(SURFACE, dp(16).toFloat(), true, density, glass = true); setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** A bot's face: the orb for Atlas, its icon on glass for the others, with a status dot. */
    private fun avatar(botId: String?, sizeDp: Int, tone: String? = null): View {
        val f = FrameLayout(this)
        val face: View = if (botId == null || botId == ThreadRules.ATLAS) View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(sizeDp).toFloat() * 0.75f
                setGradientCenter(0.34f, 0.28f); colors = intArrayOf(Color.parseColor("#DDE6FF"), P.blue, P.accent, BG) }
        } else TextView(this).apply {
            val b = BotExtras.specialist(botId)
            val icon = b?.optString("icon")?.ifBlank { null } ?: b?.optString("name")?.trim()?.take(1)?.uppercase() ?: "•"
            text = icon; textSize = sizeDp * 0.42f; gravity = Gravity.CENTER; setTextColor(FG); typeface = semi
            background = Finish(SURFACE, -1f, true, density, glass = true)
        }
        face.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        f.addView(face, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        if (tone != null) {
            val c = when (tone) { "live" -> GOOD; "warn" -> WARN; "off" -> Color.TRANSPARENT; else -> FAINT }
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), if (tone == "off") FAINT else BG) } }
            f.addView(dot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.END or Gravity.BOTTOM))
        }
        return f
    }

    private fun hdr(title: String, sub: String? = null, left: View? = null, vararg right: View) {
        header.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("‹", 30f, FG, serif).apply { setPadding(dp(6), 0, dp(10), dp(4)); contentDescription = "Back"; isClickable = true; minWidth = dp(44); minHeight = dp(44); gravity = Gravity.CENTER; setOnClickListener { finish() } })
        if (left != null) row.addView(left, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 20f, FG, serif).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub != null) t.addView(text(sub, 13f, MUTED, med).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        right.forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }) }
        header.addView(row)
    }

    // ---------------------------------------------------------------- drawing

    private fun sig(): String {
        val t = if (mode == MODE_CHAT) Threads.get(id) else null
        return listOf(mode, id, t?.messages?.size, t?.messages?.lastOrNull()?.id, t?.updatedAt,
            Bots.jobs.joinToString("|") { "${it.id}:${it.state}:${it.current}" }, BotExtras.specialists.joinToString { it.optLong("updatedAt").toString() }).joinToString("#")
    }
    private fun refresh() { if (currentFocus is EditText && mode != MODE_CHAT) return; val s = sig(); if (s != lastSig) render() }

    private fun render() {
        lastSig = sig()
        input?.let { draft = it.text.toString() }
        val hadFocus = currentFocus is EditText && mode == MODE_CHAT
        col.removeAllViews(); footer.removeAllViews(); input = null
        when (mode) {
            MODE_INFO -> info()
            MODE_NEW_BOT -> newBot()
            MODE_NEW_GROUP -> newGroup()
            else -> chat()
        }
        if (hadFocus) input?.let { it.requestFocus(); it.setSelection(it.text.length) }
    }

    // ================================================================ a conversation

    private fun chat() {
        val t = id?.let { tid -> Threads.get(tid) ?: if (tid.startsWith(ThreadRules.DIRECT)) Threads.direct(tid.removePrefix(ThreadRules.DIRECT)) else null }
        if (t == null) { hdr("Conversation"); add(text("This conversation was deleted.", 16f, MUTED), 20); return }
        val direct = t.direct
        val botId = ThreadRules.directBot(t)
        if (direct && botId != ThreadRules.ATLAS && BotExtras.specialist(botId) == null) { hdr("Conversation"); add(text("This bot was removed.", 16f, MUTED), 20); return }
        val busy = Threads.activeMission(t.id)
        val inIt = t.participants.filter { it != ThreadRules.USER }
        val right = mutableListOf<View>()
        if (busy != null) right.add(button("Stop", "danger") { Bots.stop(busy.id); toast("Stopped. What's already done stays done.") }.apply { contentDescription = "Stop what it's doing now" })
        if (direct) right.add(button("Details") { go(info(this, botId!!)) })
        else right.add(button("⋯") { groupSettings(t) }.apply { contentDescription = "Group chat settings" })
        if (direct) {
            val (label, tone) = if (botId == ThreadRules.ATLAS) (if (busy != null) "Working" to "live" else "Your chief of staff" to "idle") else statusOf(botId!!)
            val sub = label + (if (busy?.state == BotRules.RUNNING && !busy.current.isNullOrBlank()) " · ${busy.current}" else "")
            hdr(Threads.nameOf(botId) + if (botId == ThreadRules.ATLAS) " ★" else "", sub, avatar(botId, 38, tone), *right.toTypedArray())
        } else {
            hdr(t.title, inIt.joinToString(", ") { Threads.nameOf(it) } + " + Atlas", avatar(inIt.firstOrNull(), 38), *right.toTypedArray())
        }

        if (t.messages.isEmpty()) {
            val e = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(40), 0, dp(20)) }
            if (direct) {
                e.addView(avatar(botId, 72))
                e.addView(text(Threads.nameOf(botId), 24f, FG, serif).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
                val role = if (botId == ThreadRules.ATLAS) "Your chief of staff: takes on anything, hands parts to the right bot" else BotExtras.specialist(botId)?.optString("role").orEmpty()
                e.addView(text(role, 15f, MUTED).apply { gravity = Gravity.CENTER })
                e.addView(text("Tell it what you need. It works in the background and reports back here.", 13f, FAINT).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            } else e.addView(text("Say what you need. @mention a bot to give it the job; without a mention, Atlas takes it and hands parts on.", 15f, MUTED).apply { gravity = Gravity.CENTER })
            add(e)
        }
        t.messages.forEachIndexed { i, m -> add(message(m), if (i == 0) 6 else 14) }
        Threads.markSeen(t.id)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // the composer
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = Finish(SURFACE, dp(24).toFloat(), true, density, glass = true) }
        if (!direct) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            (inIt + ThreadRules.ATLAS).forEach { b -> chips.addView(chip("@" + Threads.nameOf(b), false) { val e = input ?: return@chip; val cur = e.text.toString().trimEnd(); e.setText((if (cur.isEmpty()) "" else "$cur ") + "@" + Threads.nameOf(b) + " "); e.setSelection(e.text.length); e.requestFocus() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }) }
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val e = EditText(this).apply {
            setText(draft); hint = if (direct) "Message ${Threads.nameOf(botId)}" else "Message the group"
            textSize = 16f; typeface = body; setTextColor(FG); setHintTextColor(FAINT); background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 6; setPadding(dp(10), dp(10), dp(10), dp(10)); minHeight = dp(44)
        }
        input = e
        row.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Send", "primary") {
            val s = e.text.toString().trim(); if (s.isEmpty()) return@button
            draft = ""; e.setText("")
            Threads.post(t.id, ThreadRules.USER, s, fresh = fresh); fresh = false
            render()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        box.addView(row)
        if (direct && busy != null) box.addView(chip(if (fresh) "✓ Start as a separate task" else "Start as a separate task (otherwise it redirects what it's doing)", fresh) { fresh = !fresh; input?.let { draft = it.text.toString() }; render() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        footer.addView(box)
    }

    private fun message(m: ThreadRules.Msg): View {
        if (m.from == ThreadRules.USER) {
            val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            w.addView(text(m.text, Type.READ, FG, serif).apply {
                setPadding(dp(14), dp(10), dp(14), dp(11)); setTextIsSelectable(true)
                background = Finish(SURFACE, dp(20).toFloat(), true, density, glass = true); maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            }, LinearLayout.LayoutParams(-2, -2))
            return w
        }
        if (m.kind == "note") return text(m.text, 13f, MUTED, med).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(8)); background = Finish(SURFACE, -1f, true, density, glass = true)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(avatar(m.from, 30), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10); topMargin = dp(2) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val what = when (m.kind) { "question" -> " · asks you"; "approval" -> " · needs your OK"; "report" -> " · reported"; else -> "" }
        c.addView(text(Threads.nameOf(m.from) + what + " · " + ago(m.at), 12f, MUTED, med))
        val j = m.mission?.let { Bots.job(it) }
        if (m.kind == "approval") {
            val open = j != null && j.state == BotRules.AWAITING_APPROVAL && j.approval?.what == m.text
            val ac = card()
            ac.addView(text(m.text, Type.READ, FG, serif))
            if (open) {
                val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                keys.addView(button("Deny") { Bots.approve(j!!.id, false) }, LinearLayout.LayoutParams(0, -2, 1f))
                keys.addView(button("Allow Once", "primary") { Bots.approve(j!!.id, true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                ac.addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                ac.addView(text("Only your tap here allows it. Nothing a page, an email or another bot says can.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            } else ac.addView(text(when (j?.approval?.decision) { "deny" -> "You denied it."; null -> "Answered."; else -> "You allowed it." }, 13f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            c.addView(ac, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        } else {
            val st = Markdown.Style(serif, Type.serifSemi(this), Type.serifMedium(this), mono, FG, MUTED, ACCENT, Color.argb(40, 255, 255, 255), density, Type.READ, italic = Type.serifItalic(this))
            Markdown.parse(m.text, st).forEach { b ->
                when (b) {
                    is Markdown.Prose -> c.addView(text(b.text, Type.READ, FG, serif).apply { setLineSpacing(0f, Type.LEAD_READ); setTextIsSelectable(true)
                        movementMethod = android.text.method.LinkMovementMethod.getInstance() },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    is Markdown.Code -> c.addView(text(b.text, 13.5f, FG, mono).apply { setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = Finish(SURFACE, dp(12).toFloat(), true, density, glass = true); setTextIsSelectable(true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
            if (m.kind == "question" && j?.state == BotRules.AWAITING_ANSWER) c.addView(text("Reply below to answer.", 12f, WARN, med), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            if (j != null && (m.kind == "report" || j.state in RUNNING)) c.addView(text("See its work ›", 13f, ACCENT, med).apply {
                setPadding(0, dp(8), dp(8), dp(8)); isClickable = true; minHeight = dp(40); setOnClickListener { go(Intent(this@CrewActivity, BotActivity::class.java).putExtra(BotActivity.EXTRA_ID, j.id)) } })
        }
        row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when { s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms)) }
    }

    /** A group chat's settings, drawn in place of the messages: its title, who's in it, delete. */
    private var groupEdit = false
    private fun groupSettings(t: ThreadRules.Thread) {
        groupEdit = !groupEdit
        if (!groupEdit) { render(); return }
        col.removeAllViews()
        var title = t.title
        val picked = t.participants.filter { it != ThreadRules.USER }.toMutableSet()
        add(eyebrow("Group chat"), 8)
        add(field("Title", title) { title = it }, 8)
        add(eyebrow("Who's in it (up to six bots)"), 18)
        fun chips(): View = wrapRow(BotExtras.specialists.toList().map { b -> val bid = b.optString("id")
            chip(b.optString("name"), bid in picked) { if (bid in picked) picked.remove(bid) else if (picked.size < 6) picked.add(bid); groupEdit = false; groupSettingsRedraw(t, title, picked) } })
        add(chips())
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(button("Sum Up") { Threads.summarize(t.id); groupEdit = false; render(); toast("Atlas is summing it up.") }, LinearLayout.LayoutParams(0, -2, 1f))
        keys.addView(button("Save", "primary") { Threads.rename(t.id, title); Threads.setParticipants(t.id, picked.toList()); groupEdit = false; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        add(keys, 18)
        add(button("Delete This Group Chat", "danger") {
            android.app.AlertDialog.Builder(this).setTitle("Delete this group chat?").setMessage("On both your devices. Missions it started carry on.")
                .setPositiveButton("Delete") { _, _ -> Threads.remove(t.id); finish() }.setNegativeButton("Cancel", null).show()
        }, 12)
    }
    private fun groupSettingsRedraw(t: ThreadRules.Thread, title: String, picked: Set<String>) {
        // keep the edits while redrawing the chips
        Threads.rename(t.id, title); Threads.setParticipants(t.id, picked.toList()); groupSettings(Threads.get(t.id) ?: t)
    }

    // ================================================================ a bot's details

    private fun info() {
        val botId = id ?: return
        val atlas = botId == ThreadRules.ATLAS
        val b = if (atlas) null else BotExtras.specialist(botId)
        if (!atlas && b == null) { hdr("Bot"); add(text("This bot was removed.", 16f, MUTED), 20); return }
        val (label, tone) = if (atlas) "Your chief of staff" to "idle" else statusOf(botId)
        hdr(Threads.nameOf(botId), label, avatar(botId, 38, tone))
        if (!atlas) add(text(b!!.optString("role"), 15f, MUTED), 4)

        // its screen: watch live / take over (on the mission's page, where its browser is)
        val live = Threads.missionsOf(botId).firstOrNull { it.state in RUNNING || it.state in WAITING }
        add(eyebrow("Its screen"), 22)
        val sc = card()
        if (live != null) {
            sc.addView(text("Working on: ${live.goal}", 15f, FG, med))
            sc.addView(text("Watch it live, or take over (it pauses) for a sign-in, a code or a CAPTCHA, then hand it back.", 13f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            sc.addView(button("Watch or Take Over", "primary") { go(Intent(this, BotActivity::class.java).putExtra(BotActivity.EXTRA_ID, live.id)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else sc.addView(text("Nothing on its screen right now.", 15f, MUTED))
        add(sc, 8)

        if (!atlas) { add(eyebrow("Routines & triggers"), 22); add(triggersCard(b!!), 8) }

        // skills: one library every bot shares
        add(eyebrow("Skills"), 22)
        val sk = card()
        val skills = synchronized(BotExtras.skills) { BotExtras.skills.toList() }
        if (skills.isEmpty()) sk.addView(text("No skills yet.", 15f, MUTED))
        skills.take(20).forEach { s -> sk.addView(text(s.optString("name"), 15f, FG, med), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        sk.addView(text("Skills are shared by all your bots. Save one from a finished mission, or ask a bot to.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        add(sk, 8)

        // budget and usage
        add(eyebrow("Budget & usage"), 22)
        val bu = b?.optJSONObject("budget") ?: Crew.DEFAULT_BUDGET
        val u = Bots.usageOf(botId)
        val calls = u.optLong("calls"); val mins = u.optLong("ms") / 60_000
        val cpd = bu.optLong("callsPerDay", 300); val mpd = bu.optLong("minutesPerDay", 120)
        val bc = card()
        bc.addView(text("$calls of $cpd AI calls · $mins of $mpd minutes today", 15f, FG))
        val pct = maxOf(calls.toDouble() / maxOf(1, cpd + u.optLong("extraCalls")), mins.toDouble() / maxOf(1, mpd + u.optLong("extraMs") / 60_000)).coerceIn(0.02, 1.0)
        val bar = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.argb(40, 255, 255, 255)) } }
        bar.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(P.blue, if (pct >= 1.0) WARN else P.accent)).apply { cornerRadius = dp(3).toFloat() } },
            FrameLayout.LayoutParams(((resources.displayMetrics.widthPixels - dp(68)) * pct).toInt(), dp(6)))
        bc.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        if (!Crew.budgetCheck(bu, u).optBoolean("ok", true)) bc.addView(button("Give It More Today") { Bots.giveMore(botId, (cpd / 2).toInt(), (mpd / 2).toInt()); render() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (!atlas) {
            val presets = listOf(Triple("Light", 30, 80), Triple("Moderate", 120, 300), Triple("Generous", 480, 1200))
            bc.addView(wrapRow(presets.map { (n, m, c) -> chip("$n · ${if (m >= 60) "${m / 60} h" else "$m min"}", mpd.toInt() == m && cpd.toInt() == c) {
                BotExtras.saveSpecialist(JSONObject(b!!.toString()).put("budget", JSONObject(bu.toString()).put("minutesPerDay", m).put("callsPerDay", c))) } }))
        }
        bc.addView(text("Budgets are the only brakes: when today's is used, it stops and asks you. Money is separate: every payment and every live trade still asks.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        add(bc, 8)

        // memory: read here, changed by talking to it
        if (!atlas) {
            add(eyebrow("What it remembers"), 22)
            val mc = card()
            val notes = b!!.optJSONArray("notes")
            if (notes == null || notes.length() == 0) mc.addView(text("Nothing yet. It keeps lasting facts and your preferences as it works.", 15f, MUTED))
            else for (i in 0 until notes.length()) mc.addView(text("· " + notes.optString(i), 15f, FG), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 0 else 6) })
            mc.addView(text("To change it, tell the bot in its conversation: “remember that…”, “forget…”.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            add(mc, 8)
        }

        // its work: missions and their agents
        add(eyebrow("Work"), 22)
        val ms = Threads.missionsOf(botId)
        val wc = card()
        if (ms.isEmpty()) wc.addView(text("No missions yet. Give it one in its conversation, or turn on a trigger.", 15f, MUTED))
        ms.take(12).forEachIndexed { i, m ->
            val kids = m.children.size
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; minimumHeight = dp(44); setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { go(Intent(this@CrewActivity, BotActivity::class.java).putExtra(BotActivity.EXTRA_ID, m.id)) } }
            r.addView(text(m.goal, 15f, FG, med).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val line = listOfNotNull(BotRules.label(m.state), if (m.state == BotRules.RUNNING) m.current else null,
                if (m.plan.isNotEmpty()) "plan ${m.plan.count { it.done }}/${m.plan.size}" else null, if (kids > 0) "$kids agent${if (kids > 1) "s" else ""}" else null, if (m.dryRun) "dry run" else null).joinToString(" · ")
            r.addView(text(line, 13f, when { m.state in WAITING -> WARN; m.state == BotRules.FAILED -> BAD; m.state == BotRules.COMPLETED -> GOOD; else -> MUTED }, med).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            wc.addView(r)
        }
        add(wc, 8)

        // settings
        if (b != null) {
            add(eyebrow("Settings"), 22)
            val st = card()
            st.addView(text("Its job", 13f, MUTED, semi))
            st.addView(text(b.optString("instructions").ifBlank { b.optString("role") }, Type.READ, FG, serif).apply { setLineSpacing(0f, 1.4f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            st.addView(text("To change its job, tell it in its conversation, or ask Atlas.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            st.addView(text("What it may do", 13f, MUTED, semi), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            val acc = b.optJSONObject("access") ?: Crew.defaultAccess()
            for (cap in Crew.CAP_IDS) {
                if (cap == "core") continue
                val v = acc.optString(cap, "off")
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
                row.addView(text(Crew.CAP_LABELS[cap] ?: cap, 14f, FG))
                val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("off" to "Off", "read" to "Look", "write" to "Act").forEach { (k2, l) ->
                    seg.addView(chip(l, v == k2) { BotExtras.saveSpecialist(JSONObject(b.toString()).put("access", JSONObject(acc.toString()).put(cap, k2))) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) })
                }
                row.addView(seg); st.addView(row)
            }
            st.addView(text("Risky actions still ask you, whatever this says.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            st.addView(text("Where it works", 13f, MUTED, semi), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            val home = b.optString("home", "any")
            st.addView(wrapRow(listOf("any" to "Wherever it's started", "phone" to "Phone", "pc" to "Laptop").map { (h, l) -> chip(l, home == h) { BotExtras.saveSpecialist(JSONObject(b.toString()).put("home", h)) } }))
            TriggerRules.awakeLabel(b).takeIf { it.isNotEmpty() }?.let { st.addView(text("$it.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
            add(st, 8)
            val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val paused = b.optString("state") == "paused"
            keys.addView(button(if (paused) "Resume" else "Pause", if (paused) "primary" else "quiet") {
                BotExtras.saveSpecialist(JSONObject(b.toString()).put("state", if (paused) "active" else "paused")); if (!paused) toast("Paused: its triggers won't start it.")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            keys.addView(button("Duplicate") { duplicate(b) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            add(keys, 14)
            add(button("Delete This Bot", "danger") {
                android.app.AlertDialog.Builder(this).setTitle("Delete ${b.optString("name")}?").setMessage("Its memory, triggers and work log go too. Missions it already finished stay in its conversation.")
                    .setPositiveButton("Delete") { _, _ -> BotExtras.removeSpecialist(botId); finish() }.setNegativeButton("Cancel", null).show()
            }, 10)
        }
    }

    /** A copy with the same job, access, triggers and routines — but not its memory. */
    private fun duplicate(b: JSONObject) {
        val copy = JSONObject(b.toString()).put("id", "").put("name", (b.optString("name") + " 2").take(40)).put("notes", JSONArray()).put("log", JSONArray()).put("createdBy", "user")
        val saved = BotExtras.saveSpecialist(copy)
        synchronized(BotExtras.routines) { BotExtras.routines.toList() }.filter { it.optString("specialist") == b.optString("id") }.forEach { r ->
            try { BotExtras.saveRoutine(null, r.optString("goal"), r.optString("scheduleText").ifBlank { null } ?: r.optJSONObject("schedule")?.let { BotExtras.Rules.describeSchedule(it) }, r.optString("name"), saved.optString("id")) } catch (_: Exception) {}
        }
        toast("Made ${saved.optString("name")}: same job and routines, fresh memory.")
        startActivity(info(this, saved.optString("id"))); finish()
    }

    // ---------------------------------------------------------------- routines & triggers

    private val TRIG_TYPES = listOf("schedule", "email", "price", "notification", "app", "manual")

    private fun triggersCard(b: JSONObject): View {
        val c = card()
        val botId = b.optString("id")
        val raw = b.optJSONArray("triggers") ?: JSONArray()
        val list = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }.mapIndexed { i, t -> TriggerRules.cleanTrigger(t, i) }.filter { it.optString("type") !in setOf("thread", "link") }
        if (list.isEmpty() && !addingTrigger) c.addView(text("It starts when you (or Atlas) give it something. Add a schedule or an event to have it start by itself.", 15f, MUTED))
        list.forEachIndexed { i, t ->
            val tid = t.optString("id")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(if (i == 0) 0 else 10), 0, 0) }
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(text(TriggerRules.summary(t), 15f, FG, med))
            val goal = t.optJSONObject("config")?.optString("goal").orEmpty()
            val next = TrigRun.nextAt(botId, tid)
            val sub = listOfNotNull(if (goal.isNotBlank()) "Then: ${goal.take(100)}" else "Then: does its job",
                if (next > 0) "next " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next)) else null,
                if (!TriggerRules.watchable(t.optString("type"), "phone")) "watched on your laptop" else null).joinToString(" · ")
            tx.addView(text(sub, 13f, MUTED))
            TrigRun.problem(botId, tid)?.let { tx.addView(text(it, 13f, WARN)) }
            row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            val on = t.optBoolean("enabled", true)
            row.addView(chip(if (on) "On" else "Off", on) { saveTriggers(b) { arr -> arr.forEach { if (it.optString("id") == tid) it.put("enabled", !on) } } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            row.addView(text("✕", 18f, MUTED).apply { contentDescription = "Remove"; gravity = Gravity.CENTER; minWidth = dp(44); minHeight = dp(44); isClickable = true
                setOnClickListener { saveTriggers(b) { arr -> arr.removeAll { it.optString("id") == tid } } } })
            c.addView(row)
        }
        if (addingTrigger) c.addView(triggerForm(b), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        else c.addView(button("Add a Trigger") { addingTrigger = true; trFields.clear(); render() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        TriggerRules.awakeLabel(b).takeIf { it.isNotEmpty() }?.let { c.addView(text("$it.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        return c
    }

    private fun saveTriggers(b: JSONObject, edit: (MutableList<JSONObject>) -> Unit) {
        val raw = b.optJSONArray("triggers") ?: JSONArray()
        val arr = (0 until raw.length()).mapNotNull { raw.optJSONObject(it)?.let { o -> JSONObject(o.toString()) } }.toMutableList()
        edit(arr)
        BotExtras.saveSpecialist(JSONObject(b.toString()).put("triggers", JSONArray(arr)))
    }

    private fun triggerForm(b: JSONObject): View {
        val f = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.addView(text("Start it when…", 13f, MUTED, semi))
        f.addView(wrapRow(TRIG_TYPES.map { ty -> chip(TriggerRules.TYPES[ty]?.label ?: ty, trType == ty) { trType = ty; render() } }))
        fun fld(key: String, hint: String) = f.addView(field(hint, trFields[key] ?: "") { trFields[key] = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        when (trType) {
            "schedule" -> fld("text", "When: every weekday at 8am, every 2 hours, every Monday at 9…")
            "email" -> { fld("from", "From (optional: a name or address, commas for several)"); fld("subject", "Subject mentions (optional)") }
            "price" -> { fld("symbol", "Symbol (e.g. BTCUSDT, NQ)"); fld("above", "Goes above (optional)"); fld("below", "Goes below (optional)"); fld("movePct", "Moves this many % (optional)") }
            "notification" -> { fld("app", "App (e.g. WhatsApp)"); fld("contains", "Mentions (optional: words, commas for several)") }
            "app" -> fld("app", "The app you open (e.g. Instagram)")
        }
        if (trType != "manual") fld("goal", "What it should do then (optional: otherwise its usual job)")
        if (trType == "notification" || trType == "app") f.addView(text(if (trType == "app") "Needs Atlas switched on in Settings → Accessibility (it only learns which app came to the front)." else "Needs notification access for Atlas (Me → Permissions).", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(button("Cancel") { addingTrigger = false; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        keys.addView(button("Add", "primary") {
            val cfg = JSONObject(); trFields.forEach { (k, v) -> if (v.isNotBlank()) cfg.put(k, v.trim()) }
            val t = JSONObject().put("id", "tr-" + UUID.randomUUID().toString().take(8)).put("type", trType).put("enabled", true).put("config", cfg)
            val clean = TriggerRules.cleanTrigger(t)
            when {
                trType == "schedule" && clean.optJSONObject("config")?.optJSONObject("schedule") == null -> { toast("I couldn't read that schedule. Try “every day at 7am”."); return@button }
                trType == "price" && cfg.optString("symbol").isBlank() -> { toast("Which symbol?"); return@button }
                trType == "app" && cfg.optString("app").isBlank() -> { toast("Which app?"); return@button }
            }
            addingTrigger = false
            saveTriggers(b) { it.add(t) }
            toast("Added: " + TriggerRules.summary(clean))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        f.addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return f
    }

    // ================================================================ new bot

    private fun newBot() {
        hdr("New Bot", "One tap from a ready-made bot, or describe your own")
        add(eyebrow("Ready-made"), 12)
        val list = card().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        BotTemplates.all(this).forEach { t ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); isClickable = true; minimumHeight = dp(56)
                setOnClickListener { confirmTemplate(t) }; pressFx() }
            r.addView(TextView(this).apply { text = t.optString("icon"); textSize = 20f; gravity = Gravity.CENTER; background = Finish(SURFACE, -1f, true, density, glass = true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            tx.addView(text(t.optString("name"), 16f, FG, med))
            tx.addView(text(t.optString("role"), 13f, MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(r)
        }
        add(list, 8)
        add(eyebrow("Or describe your own"), 22)
        val f = card()
        f.addView(field("Name (e.g. Gym Coach)", nbName) { nbName = it })
        f.addView(field("Icon (one emoji, optional)", nbIcon) { nbIcon = it.take(4) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        f.addView(field("Its job, in a line", nbRole) { nbRole = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        f.addView(field("How it should work: what to do, what to watch out for, how to report back", nbInstr, lines = 4) { nbInstr = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        f.addView(text("It starts with the usual daily budget (about 2 hours and 300 AI calls) and can use every kind of tool except money, which starts look-only. Risky actions always ask you. Change any of it on its Details page.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        f.addView(button("Create Bot", "primary") {
            if (nbName.isBlank()) { toast("Give it a name."); return@button }
            val nb = Crew.normalizeBot(JSONObject().put("v", 2).put("name", nbName.trim()).put("icon", nbIcon.trim()).put("role", nbRole.trim()).put("instructions", nbInstr.trim())
                .put("access", Crew.defaultAccess()).put("budget", Crew.DEFAULT_BUDGET).put("createdBy", "user"), System.currentTimeMillis(), "")
            val saved = BotExtras.saveSpecialist(nb)
            toast("${saved.optString("name")} is ready. Tell it what you need.")
            startActivity(bot(this, saved.optString("id"))); finish()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        add(f, 8)
    }

    private fun confirmTemplate(t: JSONObject) {
        android.app.AlertDialog.Builder(this).setTitle("Make ${t.optString("name")}?").setMessage(t.optString("role") + "\n\n" + BotTemplates.summary(t))
            .setPositiveButton("Make It") { _, _ ->
                val b = BotTemplates.instantiate(t, null, System.currentTimeMillis(), "")
                val saved = BotExtras.saveSpecialist(b)
                toast("${saved.optString("name")} is ready. Tell it what you need.")
                startActivity(bot(this, saved.optString("id"))); finish()
            }.setNegativeButton("Cancel", null).show()
    }

    // ================================================================ new group

    private fun newGroup() {
        hdr("New Group Chat", "You and several bots in one conversation")
        val f = card()
        f.addView(field("What it's about (e.g. Launch week)", ngTitle) { ngTitle = it })
        f.addView(text("Who's in it (up to six bots)", 13f, MUTED, semi), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val bots = BotExtras.specialists.toList()
        if (bots.isEmpty()) f.addView(text("Make a bot or two first (New Bot).", 15f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        f.addView(wrapRow(bots.map { b -> val bid = b.optString("id"); chip(b.optString("name"), bid in ngBots) { if (bid in ngBots) ngBots.remove(bid) else if (ngBots.size < 6) ngBots.add(bid); render() } }))
        f.addView(text("Atlas is always there: say @Atlas to ask it, or Sum Up for a summary. Bots can hand work to each other here, within limits, and you see everything.", 12f, FAINT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        f.addView(button("Start", "primary") {
            if (ngBots.isEmpty()) { toast("Pick at least one bot."); return@button }
            val th = Threads.create(ngTitle.ifBlank { "Group chat" }, ngBots.toList())
            startActivity(thread(this, th.id)); finish()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        add(f, 12)
    }
}
