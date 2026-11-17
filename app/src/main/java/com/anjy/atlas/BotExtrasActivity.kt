package com.anjy.atlas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.anjy.atlas.core.Engine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Atlas Bot's extras on the phone, one page each ([EXTRA_PAGE]): routines (bots on a schedule), skills (how the user
 * likes a kind of job done), the specialist team, and the bot permissions (ask / always / never per kind of action,
 * plus standing rules). Editing happens in simple dialogs. The same data as the PC's Bots page, synced.
 */
class BotExtrasActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "page"
        /** A skill draft (JSON) to open the editor with, from a finished job. */
        const val EXTRA_SKILL = "skill"
        fun intent(ctx: Context, page: String) = Intent(ctx, BotExtrasActivity::class.java).putExtra(EXTRA_PAGE, page)
    }

    private val BG = Palette.NIGHT.bg
    private val FG = Palette.NIGHT.fg
    private val MUTED = Palette.NIGHT.muted
    private val ACCENT = Palette.NIGHT.accentInk
    private val WARN = Palette.NIGHT.warm
    private val SURFACE = Palette.NIGHT.surface2

    private lateinit var col: LinearLayout
    private var page = "routines"
    private val serif by lazy { font(R.font.newsreader_regular, Typeface.SERIF) }
    private val med by lazy { font(R.font.geist_medium, Typeface.DEFAULT) }
    private val semi by lazy { font(R.font.geist_semibold, Typeface.DEFAULT_BOLD) }
    private val body by lazy { font(R.font.geist_regular, Typeface.DEFAULT) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }
    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bots.init(applicationContext)
        page = intent?.getStringExtra(EXTRA_PAGE) ?: "routines"
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
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
        intent?.getStringExtra(EXTRA_SKILL)?.let { try { editSkill(JSONObject(it)) } catch (_: Exception) {} }
    }

    override fun onResume() { super.onResume(); BotExtras.listen(onChange) }
    override fun onPause() { BotExtras.unlisten(onChange); super.onPause() }

    // ---------------------------------------------------------------- pieces

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
        setTextColor(if (primary) Palette.NIGHT.onPrimary else FG); setPadding(dp(14), dp(12), dp(14), dp(13))
        background = Finish(if (primary) Palette.NIGHT.primaryBottom else SURFACE, -1f, true, resources.displayMetrics.density, glass = !primary)
        isClickable = true; setOnClickListener { onTap() }; pressFx()
    }
    private fun chip(label: String, on: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; typeface = if (on) semi else med; gravity = Gravity.CENTER
        setTextColor(if (on) Palette.NIGHT.onPrimary else FG); setPadding(dp(12), dp(7), dp(12), dp(8))
        background = Finish(if (on) Palette.NIGHT.primaryBottom else SURFACE, -1f, true, resources.displayMetrics.density, glass = !on)
        isClickable = true; setOnClickListener { onTap() }; contentDescription = label + if (on) ", chosen" else ""
    }
    private fun header(title: String, intro: String) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("‹", 30f, FG, serif).apply { setPadding(dp(4), 0, dp(14), dp(4)); contentDescription = "Back"; isClickable = true; setOnClickListener { finish() } })
        top.addView(text(title, 26f, FG, serif), LinearLayout.LayoutParams(0, -2, 1f))
        add(top)
        add(text(intro, 15f, MUTED), 4)
    }
    private fun item(title: String, sub: String, onTap: (() -> Unit)?, vararg keys: View): View {
        val c = card()
        c.addView(text(title, 17f, FG, serif).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) c.addView(text(sub, 13f, MUTED, med), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (keys.isNotEmpty()) c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (onTap != null) { c.isClickable = true; c.setOnClickListener { onTap() }; c.pressFx() }
        return c
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private val whenFmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH)
    private fun whenOf(ms: Long) = if (ms <= 0) "—" else java.time.Instant.ofEpochMilli(ms).atZone(Engine.zone).format(whenFmt)

    /** A dialog form: labelled fields; [save] returns an error sentence, or null when saved. */
    private fun form(title: String, fields: List<Triple<String, String, Int>>, extra: View? = null, delete: (() -> Unit)? = null, save: (List<String>) -> String?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val edits = fields.map { (label, value, lines) ->
            box.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(10), 0, dp(2)) })
            EditText(this).apply {
                setText(value); minLines = lines; maxLines = maxOf(lines, 8); textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            }.also { box.addView(it) }
        }
        extra?.let { box.addView(it) }
        val d = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .apply { if (delete != null) setNeutralButton("Delete") { _, _ -> delete() } }.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = save(edits.map { it.text.toString().trim() })
                if (err == null) d.dismiss() else toast(err)
            }
        }
        d.show()
    }
    private fun confirm(title: String, what: String, yes: String, go: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(what).setPositiveButton(yes) { _, _ -> go() }.setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- pages

    private fun render() {
        col.removeAllViews()
        when (page) {
            "skills" -> skills()
            "team" -> team()
            "permissions" -> permissions()
            else -> routines()
        }
    }

    private fun routines() {
        header("Routines", "Bots on a schedule, like “every weekday at 8am, summarise what's new in AI”. Only one of your devices runs each one.")
        val all = BotExtras.routines.toList()
        if (all.isEmpty()) add(card().apply { addView(text("None yet. Add one here, or tell Atlas “every Sunday evening, plan my week”.", 15f, MUTED)) }, 16)
        all.forEach { r ->
            val on = r.optBoolean("enabled", true)
            val sub = BotExtras.Rules.describeSchedule(r.optJSONObject("schedule")) + (if (on) " · next ${whenOf(r.optLong("nextAt"))}" else " · paused") +
                (if (r.optLong("lastAt") > 0) " · last ${whenOf(r.optLong("lastAt"))}" else "") + (if (BotExtras.mine(r)) "" else " · made on your other device")
            add(item(r.optString("name"), sub, null,
                button("Test Run") { BotExtras.runRoutine(r.optString("id"), true); toast("Sent a bot to try it now.") },
                button(if (on) "Pause" else "Resume") { BotExtras.enableRoutine(r.optString("id"), !on) },
                button("Edit") { editRoutine(r) }), 12)
        }
        add(button("Add a Routine", primary = true) { editRoutine(null) }, 16)
    }

    private fun specialistPicker(chosen: String?): Pair<View, () -> String?> {
        val team = BotExtras.specialists.toList()
        val names = listOf("Atlas Bot (no specialist)") + team.map { it.optString("name") }
        var pick = team.indexOfFirst { it.optString("id") == chosen } + 1
        val label = TextView(this).apply { textSize = 15f; setPadding(0, dp(14), 0, dp(4)); text = "Send: ${names[pick]}  ›" }
        label.setOnClickListener { AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, i -> pick = i; label.text = "Send: ${names[i]}  ›" }.show() }
        return label to { if (pick == 0) null else team[pick - 1].optString("id") }
    }

    private fun editRoutine(r: JSONObject?) {
        val (picker, picked) = specialistPicker(r?.optString("specialist")?.takeIf { !r.isNull("specialist") })
        form(if (r == null) "New Routine" else "Edit Routine", listOf(
            Triple("What the bot should do each time", r?.optString("goal") ?: "", 2),
            Triple("When, in plain words (every weekday at 8am · every 2 hours · every Sunday evening)", r?.let { BotExtras.Rules.describeSchedule(it.optJSONObject("schedule")) } ?: "", 1),
            Triple("Name (optional)", r?.optString("name") ?: "", 1)),
            extra = picker,
            delete = r?.let { { confirm("Delete This Routine?", it.optString("name"), "Delete") { BotExtras.removeRoutine(it.optString("id")) } } }) { v ->
            val (saved, err) = BotExtras.saveRoutine(r?.optString("id"), v[0], v[1], v[2].ifEmpty { null }, picked())
            if (saved != null) toast("Next run: ${whenOf(saved.optLong("nextAt"))}")
            err
        }
    }

    private fun skills() {
        header("Skills", "How you like a kind of job done. Bots load the right one when they need it. Teach one here, tell Atlas “when I ask for X, do it like this…”, or save one from a finished bot.")
        val all = BotExtras.skills.toList()
        if (all.isEmpty()) add(card().apply { addView(text("None yet.", 15f, MUTED)) }, 16)
        all.forEach { s -> add(item(s.optString("name"), s.optString("when"), { editSkill(s) }), 12) }
        add(button("Teach a Skill", primary = true) { editSkill(null) }, 16)
    }

    private fun editSkill(s: JSONObject?) {
        val existing = s?.optString("id")?.takeIf { it.isNotEmpty() }?.let { id -> BotExtras.skills.any { it.optString("id") == id } } == true
        fun v(k: String) = s?.optString(k) ?: ""
        form(if (existing) "Edit Skill" else "New Skill", listOf(
            Triple("Name", v("name"), 1), Triple("When to use it", v("when"), 2), Triple("Steps", v("steps"), 5),
            Triple("What it needs first (optional)", v("inputs"), 2), Triple("Checks before finishing (optional)", v("checks"), 2),
            Triple("What the result should look like (optional)", v("output"), 2), Triple("Actions that need your OK (optional)", v("permissions"), 2)),
            delete = if (existing) ({ confirm("Delete This Skill?", v("name"), "Delete") { BotExtras.removeSkill(v("id")) } }) else null) { f ->
            if (f[0].isEmpty() || f[2].isEmpty()) return@form "A skill needs a name and its steps."
            BotExtras.saveSkill(JSONObject().put("id", if (existing) v("id") else "").put("name", f[0]).put("when", f[1]).put("steps", f[2]).put("inputs", f[3])
                .put("checks", f[4]).put("output", f[5]).put("permissions", f[6]))
            toast("Skill saved"); null
        }
    }

    private fun team() {
        header("Your Team", "Specialist bots. Atlas and your bots hand them the parts that fit, and each keeps its own notes on how you like things.")
        BotExtras.specialists.toList().forEach { s ->
            val n = s.optJSONArray("notes")?.length() ?: 0
            add(item(s.optString("name"), s.optString("role") + if (n > 0) " · $n note${if (n == 1) "" else "s"}" else "", { editSpecialist(s) }), 12)
        }
        add(button("Add a Specialist", primary = true) { editSpecialist(null) }, 16)
    }

    private fun editSpecialist(s: JSONObject?) {
        val groups = s?.optJSONArray("groups")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        val boxes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        boxes.addView(TextView(this).apply { text = "What it may use (none ticked: everything)"; textSize = 13f })
        val checks = BotExtras.Rules.TOOL_GROUPS.map { g -> CheckBox(this).apply { text = g.label; isChecked = g.id in groups; tag = g.id }.also { boxes.addView(it) } }
        val notes = s?.optJSONArray("notes")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        if (notes.isNotEmpty()) boxes.addView(TextView(this).apply { text = "Its notes:\n" + notes.joinToString("\n") { "· $it" }; textSize = 13f; setPadding(0, dp(10), 0, 0) })
        form(s?.optString("name") ?: "New Specialist", listOf(
            Triple("Name", s?.optString("name") ?: "", 1), Triple("What it's for", s?.optString("role") ?: "", 1), Triple("Standing instructions", s?.optString("instructions") ?: "", 3)),
            extra = boxes,
            delete = s?.let { { confirm("Remove This Specialist?", it.optString("name"), "Remove") { BotExtras.removeSpecialist(it.optString("id")) } } }) { f ->
            if (f[0].isEmpty()) return@form "Give it a name."
            BotExtras.saveSpecialist(JSONObject().put("id", s?.optString("id") ?: "").put("name", f[0]).put("role", f[1]).put("instructions", f[2])
                .put("groups", JSONArray(checks.filter { it.isChecked }.map { it.tag as String })))
            null
        }
    }

    private fun permissions() {
        header("Bot Permissions", "What bots must ask you before doing. “Always” lets them go ahead; “Never” stops them trying.")
        BotExtras.Rules.CATEGORIES.forEach { cat ->
            val m = BotExtras.mode(cat.id)
            val c = card()
            c.addView(text(cat.label, 16f, FG, serif))
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("ask" to "Ask", "allow" to "Always", "deny" to "Never").forEachIndexed { i, (k, l) ->
                    addView(chip(l, m == k) { BotExtras.setMode(cat.id, k) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            add(c, 10)
        }
        add(text("STANDING RULES", 12f, MUTED, semi).apply { letterSpacing = 0.14f }, 24)
        add(text("In your words. Every bot follows them.", 14f, MUTED), 4)
        BotExtras.rules().forEach { r -> add(item(r, "", null, button("Remove") { BotExtras.removeRule(r) }), 10) }
        add(button("Add a Rule", primary = true) {
            form("New Rule", listOf(Triple("e.g. Never book anything on a Sunday", "", 2))) { f -> if (f[0].isEmpty()) "Write the rule." else { BotExtras.addRule(f[0]); null } }
        }, 14)
        add(text("Bots also ask before anything risky that isn't covered here.", 13f, WARN), 14)
    }
}
