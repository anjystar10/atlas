package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anjy.atlas.core.AtlasState
import com.anjy.atlas.core.Engine
import com.anjy.atlas.core.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    // Stoa's two looks. Night: true black, warm white ink, colour only in Ember. Day: frosted glass on a brown page,
    // near-black ink, black glass buttons. BLUE is the ink of actions; ON is the text on it.
    private var dark = true
    private var BG = 0; private var SURFACE = 0; private var SURFACE2 = 0; private var LINE = 0
    private var FG = 0; private var MUTED = 0; private var ON = 0; private var DONE = 0; private var MISS = 0
    private var BLUE = 0; private var ORANGE = 0; private var GOLD = 0; private var BTN2 = 0; private var FIELD = 0

    private fun applyPalette() {
        val mode = Store.read(this) { it.settings.theme }
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        dark = mode == "dark" || (mode == "system" && systemDark)
        fun c(s: String) = Color.parseColor(s)
        if (dark) {
            BG = c("#000000"); SURFACE = c("#101011"); SURFACE2 = c("#1A1A1C"); LINE = c("#262628")
            FG = c("#F3F2EE"); MUTED = c("#9C9B97"); ON = c("#050505"); DONE = c("#5BD6A6"); MISS = c("#FF6B6B")
            BLUE = c("#F3F2EE"); ORANGE = c("#FF7A2E"); GOLD = c("#F2B544"); BTN2 = c("#101011"); FIELD = c("#1A1A1C")
        } else {
            BG = c("#CEB498"); SURFACE = c("#E4D6C6"); SURFACE2 = c("#DBC9B4"); LINE = c("#F5EEE6")
            FG = c("#0B0B0C"); MUTED = c("#45403B"); ON = c("#FFFFFF"); DONE = c("#0A6646"); MISS = c("#A11B15")
            BLUE = c("#000000"); ORANGE = c("#C2410C"); GOLD = c("#7A5200"); BTN2 = c("#000000"); FIELD = c("#EFE6DB")
        }
    }

    private fun font(id: Int, fallback: Typeface) = try { resources.getFont(id) } catch (e: Throwable) { fallback }
    // Unbounded, wide and geometric, for numbers and titles; Geist for everything you read.
    private val NUM by lazy { font(R.font.unbounded_300, Typeface.create("sans-serif-light", Typeface.NORMAL)) }
    private val DISPLAY by lazy { font(R.font.unbounded_500, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    private val WORDMARK by lazy { font(R.font.unbounded_700, Typeface.create("sans-serif-black", Typeface.NORMAL)) }
    private val SEMI by lazy { font(R.font.geist_semibold, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    private val MED by lazy { font(R.font.geist_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    private val BODY by lazy { font(R.font.geist_regular, Typeface.DEFAULT) }
    private val MONO by lazy { font(R.font.geist_mono, Typeface.MONOSPACE) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tab = "chat"
    private lateinit var content: FrameLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroDate: TextView
    private lateinit var heroStatus: TextView
    private lateinit var flame: FlameView
    private lateinit var nav: FrameLayout
    private val navCells = LinkedHashMap<String, Triple<LinearLayout, NavIcon, TextView>>()
    private var imeOpen = false
    private var tts: TextToSpeech? = null
    private var busy = false
    private var refreshJob: Job? = null

    private data class Msg(val fromUser: Boolean, var text: String, val source: String)
    companion object {
        private val chat = mutableListOf<Msg>()
        private const val REQ_MODEL = 11; private const val REQ_VOICE = 12; private const val REQ_IMPORT = 13; private const val REQ_NOTIF = 14
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.zone = ZoneId.systemDefault()
        Reminders.channels(this)
        applyPalette()
        tab = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab") ?: "chat"
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        val root = FrameLayout(this)
        root.addView(Backdrop(this, BG, dark, ORANGE), FrameLayout.LayoutParams(MATCH, MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            imeOpen = insets.isVisible(WindowInsets.Type.ime())
            column.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(14)
            nav.visibility = if (imeOpen) View.GONE else View.VISIBLE
            nav.requestLayout()
            // the chat box sits above the floating tab bar, or right on the keyboard when it's open
            chatBar?.let { it.setPadding(dp(16), dp(6), dp(16), dp(if (imeOpen) 10 else 84)) }
            WindowInsets.CONSUMED
        }

        // Hero: date, a greeting in the display face, what matters now; Ember on the right
        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(10), dp(10), dp(2)) }
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroDate = text("", 13f, MUTED, SEMI)
        heroTitle = text("Atlas", 26f, FG, DISPLAY).apply { letterSpacing = -0.02f }
        heroStatus = text("", 15f, MUTED).apply { setPadding(0, dp(4), 0, 0) }
        heroText.addView(heroDate); heroText.addView(heroTitle); heroText.addView(heroStatus)
        hero.addView(heroText, LinearLayout.LayoutParams(0, WRAP, 1f))
        flame = FlameView(this, withGlow = true, fps = 30)
        hero.addView(flame, LinearLayout.LayoutParams(dp(86), dp(100)))
        column.addView(hero)

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        if (Store.read(this) { it.settings.avatarOn }) AvatarService.start(this)
        autoLoadModel()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { tab = it; render() }
    }

    override fun onResume() {
        super.onResume()
        // the avatar may have just been allowed in system settings
        if (Store.read(this) { it.settings.avatarOn } && AvatarService.canShow(this) && !AvatarService.running) AvatarService.start(this)
        if (returnedFromSystem && tab == "settings") { returnedFromSystem = false; render(keepScroll = true) }
        returnedFromSystem = false
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                updateHero()
                val sig = todaySignature()
                if (tab == "today" && currentFocus !is EditText && sig != lastTodaySig) render(keepScroll = true)
                delay(30_000)
            }
        }
    }

    override fun onPause() { refreshJob?.cancel(); returnedFromSystem = true; super.onPause() }

    /** Set when Atlas goes to the background (e.g. to grant a permission), so Me shows the new state on return. */
    private var returnedFromSystem = false
    private var lastTodaySig = ""
    /** What the Today page shows, minute by minute; it is only rebuilt when this changes. */
    private fun todaySignature(): String = Store.read(this) { s ->
        val day = LocalDate.now(Engine.zone)
        day.toString() + s.tasks.joinToString("|") { "${it.id}:${it.status}:${it.version}" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tab == "settings") render(keepScroll = true)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // "System" follows the phone's dark mode, which can change while Atlas is open
        val wasDark = dark
        applyPalette()
        if (dark != wasDark) recreate()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("tab", tab); super.onSaveInstanceState(outState) }
    override fun onDestroy() { scope.cancel(); tts?.shutdown(); super.onDestroy() }

    private fun autoLoadModel() {
        if (ModelManager.ready || ModelManager.modelName(this) == null) return
        if (ModelManager.lastLoadCrashed(this)) {
            toast("The last model load didn't finish, so it wasn't loaded again. Load it from Me.")
            return
        }
        scope.launch { ModelManager.load(applicationContext); updateHero(); if (tab == "settings") render() }
    }

    // ---------- the Stoa toolkit ----------
    private fun text(s: String, size: Float = 15f, color: Int = FG, face: Typeface? = null) = TextView(this).apply {
        val asked = face ?: BODY
        val f = if (asked === DISPLAY && size < 18f) SEMI else asked
        text = s; textSize = size; setTextColor(color); typeface = f
        if (f === DISPLAY || f === NUM || f === WORDMARK) letterSpacing = if (size >= 30f) -0.04f else -0.02f
        setLineSpacing(0f, if (size <= 16f) 1.3f else 1.1f)
    }
    private fun eyebrow(s: String) = text(s, 13f, MUTED, SEMI).apply { letterSpacing = 0.02f }

    private fun card(edge: Int? = null, pad: Int = 20): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = StoneDrawable(SURFACE, dp(26).toFloat(), dark, density, edge = edge, page = BG)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun quietBg() = if (dark) finished(SURFACE, dark, density, glass = true) else finished(BTN2, dark, density)
    private fun quietInk() = if (dark) FG else Color.WHITE

    /** The main action: a pill in ink, text in the opposite tone. */
    private fun primary(label: String, onClick: () -> Unit) = text(label, 16f, ON, MED).apply {
        gravity = Gravity.CENTER; background = finished(BLUE, dark, density)
        minHeight = dp(54); setPadding(dp(20), dp(14), dp(20), dp(14))
        pressFx()
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }
    /** A quieter action: a glass pill at night, black glass by day. */
    private fun secondary(label: String, onClick: () -> Unit) = text(label, 15f, quietInk(), MED).apply {
        gravity = Gravity.CENTER; background = quietBg()
        minHeight = dp(48); setPadding(dp(18), dp(12), dp(18), dp(12))
        pressFx()
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }
    private fun danger(label: String, onClick: () -> Unit) = text(label, 15f, Color.WHITE, MED).apply {
        gravity = Gravity.CENTER; background = finished(MISS, dark, density)
        minHeight = dp(48); setPadding(dp(18), dp(12), dp(18), dp(12))
        pressFx(); setOnClickListener { onClick() }
    }
    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 14f, if (selected) ON else FG, MED).apply {
        gravity = Gravity.CENTER
        background = if (selected) Finish(BLUE, -1f, dark, density) else Finish(SURFACE, -1f, dark, density, glass = true)
        setPadding(dp(15), dp(10), dp(15), dp(10))
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun field(hint: String, value: String = "", numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value)
        setTextColor(FG); setHintTextColor(MUTED); textSize = 16f; typeface = BODY
        background = rounded(FIELD, 16, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    /** Buttons that share a row equally, wrapping two to a line. */
    private fun buttons(views: List<View>, perRow: Int = 2): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(perRow).forEach { group ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEachIndexed { i, v -> r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8); topMargin = dp(8) }) }
            repeat(perRow - group.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
            col.addView(r)
        }
        return col
    }
    private fun hairline() = View(this).apply { setBackgroundColor(if (dark) LINE else Color.argb(40, 0, 0, 0)) }
    private fun LinearLayout.add(v: View, top: Int = 0, width: Int = MATCH) =
        addView(v, LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(top) })
    private fun page(body: LinearLayout) = ScrollView(this).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        addView(body.apply { setPadding(dp(20), dp(8), dp(20), dp(120)) })
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** A bottom sheet in Stoa's pop-up material. */
    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SheetDrawable(if (dark) SURFACE2 else SURFACE, dp(28).toFloat(), dark, density)
            setPadding(dp(22), dp(14), dp(22), dp(28))
        }
        box.addView(View(this).apply { background = rounded(MUTED, 3) }, LinearLayout.LayoutParams(dp(38), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        box.addView(text(title, 20f, FG, DISPLAY))
        build(box, d)
        val sc = ScrollView(this).apply { addView(box) }
        sc.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
            box.setPadding(dp(22), dp(14), dp(22), dp(28) + b)
            insets
        }
        d.setContentView(sc)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP); setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        return d
    }
    private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        sheet(title) { box, d ->
            options.forEachIndexed { i, o -> box.add(secondary(o) { d.dismiss(); onPick(i) }, if (i == 0) 16 else 8) }
        }
    }
    private fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        sheet(title) { box, d ->
            box.add(text(message, 15f, MUTED), 8)
            box.add(buttons(listOf(secondary("Keep") { d.dismiss() }, danger(yes) { d.dismiss(); onYes() })), 12)
        }
    }
    private fun pickerTheme() = if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    // ---------- the floating tab bar ----------
    private fun buildNav(): FrameLayout {
        val pill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (dark) { setColor(Color.parseColor("#1C1C1E")); setStroke(dp(1), Color.argb(26, 255, 255, 255)) }
                else { colors = intArrayOf(Color.argb(214, 255, 255, 255), Color.argb(170, 250, 244, 236)); orientation = GradientDrawable.Orientation.TOP_BOTTOM; setStroke(dp(1), Color.argb(235, 255, 255, 255)) }
            }
            elevation = if (dark) dp(6).toFloat() else 0f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(5)) }
        val tabs = listOf(
            Triple("chat", "Chat", R.drawable.ic_chat to R.drawable.ic_chat),
            Triple("today", "Today", R.drawable.nav_home_off to R.drawable.nav_home_on),
            Triple("memory", "Memory", R.drawable.ic_book to R.drawable.ic_book),
            Triple("settings", "Me", R.drawable.nav_me_off to R.drawable.nav_me_on)
        )
        for ((key, label, ids) in tabs) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0); isClickable = true
                contentDescription = label
                setOnClickListener { if (tab != key) { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); tab = key; render() } }
            }
            val icon = NavIcon(this, ids.first, ids.second)
            cell.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val lab = text(label, 13f, ON, SEMI).apply { includeFontPadding = false; setSingleLine(true) }
            cell.addView(lab, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            row.addView(cell, LinearLayout.LayoutParams(WRAP, dp(46)))
            navCells[key] = Triple(cell, icon, lab)
        }
        pill.addView(row)
        return pill
    }

    private fun updateNav() {
        navCells.forEach { (key, t) ->
            val (cell, icon, lab) = t
            val on = key == tab
            cell.background = if (on) Finish(BLUE, -1f, dark, density) else null
            icon.filled = on
            icon.color = if (on) ON else MUTED
            lab.visibility = if (on) View.VISIBLE else View.GONE
            lab.setTextColor(ON)
        }
    }

    // ---------- hero ----------
    private fun updateHero() {
        val now = System.currentTimeMillis()
        val z = Instant.ofEpochMilli(now).atZone(Engine.zone)
        heroDate.text = z.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        val (greet, msg, mood) = Store.read(this) { s ->
            val name = s.settings.name
            val h = z.hour
            val part = when { h < 5 -> "Late night"; h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
            val awaiting = s.tasks.count { it.status == "awaiting" }
            val active = s.tasks.firstOrNull { it.status == "active" }
            val next = s.tasks.filter { it.status == "planned" && it.startAt != null && it.startAt!! > now }.minByOrNull { it.startAt!! }
            val m = when {
                awaiting > 0 -> "I need an answer on $awaiting task${if (awaiting > 1) "s" else ""}."
                active != null -> "Now: ${active.title}"
                next != null -> "Next: ${next.title}, ${Brain.dayLabel(next.startAt!!)} ${Reminders.fmt(next.startAt!!)}"
                else -> "Nothing scheduled."
            }
            val md = when {
                busy -> FlameView.Mood.THINKING
                awaiting > 0 -> FlameView.Mood.ALERT
                Engine.inQuiet(s.settings, now) -> FlameView.Mood.SLEEP
                active != null -> FlameView.Mood.FOCUS
                else -> FlameView.Mood.IDLE
            }
            Triple(if (name.isNotBlank()) "$part, $name" else part, m, md)
        }
        heroTitle.text = greet
        heroStatus.text = msg
        if (flame.mood != FlameView.Mood.HAPPY) flame.mood = mood
        updateNav()
    }

    private fun celebrate() {
        flame.mood = FlameView.Mood.HAPPY; flame.burst()
        AvatarService.celebrate(this)
        scope.launch { delay(4000); flame.mood = FlameView.Mood.IDLE; updateHero() }
    }

    private fun render(keepScroll: Boolean = false) {
        updateHero()
        val oldY = if (keepScroll) (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        if (tab == "today") lastTodaySig = todaySignature()
        chatBar = null
        val v = when (tab) {
            "today" -> todayView()
            "memory" -> memoryView()
            "settings" -> settingsView()
            else -> chatView()
        }
        content.removeAllViews()
        content.addView(v)
        if (keepScroll && oldY > 0) (v as? ScrollView)?.post { v.scrollTo(0, oldY) }
        if (tab != "chat" && !keepScroll) {
            v.alpha = 0f; v.translationY = dp(10).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    // ================= CHAT =================
    private var chatBar: LinearLayout? = null
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView

    private fun chatView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)) }
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(chatList) }
        if (chat.isEmpty()) chat.add(Msg(false, if (ModelManager.ready) "What do you need?" else "What do you need? Say “help” to see what works without a model.", "Atlas"))
        chat.forEach { chatList.addView(bubble(it)) }
        col.addView(chatScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val fieldView = field("Message Atlas").apply { imeOptions = EditorInfo.IME_ACTION_SEND; maxLines = 4; background = rounded(FIELD, 999, LINE); setPadding(dp(18), dp(13), dp(18), dp(13)) }
        fieldView.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(fieldView); true } else false }
        val mic = FrameLayout(this).apply {
            background = quietBg(); isClickable = true; contentDescription = "Speak"
            addView(TextView(this@MainActivity).apply { text = "●"; textSize = 14f; setTextColor(ORANGE); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { voice() }
            pressFx()
        }
        val send = FrameLayout(this).apply {
            background = finished(BLUE, dark, density); isClickable = true; contentDescription = "Send"
            addView(TextView(this@MainActivity).apply { text = "↑"; textSize = 20f; typeface = SEMI; setTextColor(ON); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { submit(fieldView) }
            pressFx()
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(if (imeOpen) 10 else 84)) }
        bar.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(fieldView, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(bar)
        chatBar = bar
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return col
    }

    private fun bubble(m: Msg): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (m.fromUser) Gravity.END else Gravity.START; setPadding(0, dp(5), 0, dp(5)) }
        val tv = text(m.text, 16f, if (m.fromUser) ON else FG).apply {
            background = if (m.fromUser) Finish(BLUE, dp(22).toFloat(), dark, density) else StoneDrawable(SURFACE, dp(22).toFloat(), dark, density, page = BG)
            setPadding(dp(16), dp(11), dp(16), dp(11)); setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        wrap.addView(tv)
        if (!m.fromUser) wrap.addView(text(m.source, 12f, MUTED, MED).apply { setPadding(dp(8), dp(4), 0, 0) })
        return wrap
    }

    private fun submit(fieldView: EditText) {
        val q = fieldView.text.toString().trim()
        if (q.isEmpty() || busy) return
        fieldView.setText("")
        sendMessage(q)
    }

    private fun sendMessage(q: String) {
        if (busy) { toast("Atlas is still answering."); return }
        chat.add(Msg(true, q, "You"))
        chatList.addView(bubble(chat.last()))
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        busy = true; updateHero()
        scope.launch {
            val fixed = try { withContext(Dispatchers.IO) { Brain.route(applicationContext, q) } } catch (e: Exception) { Reply("That failed: ${e.message}", "Atlas") }
            val reply = if (fixed != null) fixed else {
                val pending = Msg(false, "…", "Local model")
                chat.add(pending)
                val pendingView = bubble(pending)
                chatList.addView(pendingView)
                val tv = (pendingView as LinearLayout).getChildAt(0) as TextView
                try {
                    Brain.ask(applicationContext, q) { partial -> tv.text = partial; chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { Reply("The model failed: ${e.message}", "Atlas")
                } finally { chat.remove(pending); (pendingView.parent as? ViewGroup)?.removeView(pendingView) }
            }
            chat.add(Msg(false, reply.text, reply.source))
            if (chat.size > 200) chat.subList(0, chat.size - 200).clear()
            chatList.addView(bubble(chat.last()))
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            busy = false; updateHero()
            if (reply.text.startsWith("Marked")) celebrate()
            speak(reply.text)
        }
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Atlas")
        try { startActivityForResult(i, REQ_VOICE) }
        catch (e: ActivityNotFoundException) { toast("No speech recognizer is installed on this phone.") }
    }

    private fun speak(s: String) {
        if (!Store.read(this) { it.settings.speakReplies }) return
        val t = tts
        if (t == null) tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas") }
        else t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas")
    }

    // ================= TODAY =================
    private fun todayView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val quick = field("Gym at 6pm for 1h").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        quick.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { quickAdd(quick); true } else false }
        val qrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qrow.addView(quick, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        qrow.addView(primary("Add") { quickAdd(quick) }.apply { minHeight = dp(50); setPadding(dp(22), dp(12), dp(22), dp(12)) })
        body.add(qrow)
        body.add(secondary("New task") { editTask(null) }, 8)

        val now = System.currentTimeMillis()
        Store.read(this) { s ->
            val today = LocalDate.now(Engine.zone)
            fun isToday(ms: Long) = Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate() == today
            val clashes = Engine.collisions(s)
            val awaiting = s.tasks.filter { it.status == "awaiting" }.sortedBy { it.promptedAt }
            val active = s.tasks.filter { it.status == "active" }.sortedBy { it.startAt }
            val blocked = s.tasks.filter { it.status == "blocked" }
            val later = s.tasks.filter { it.status == "planned" && it.startAt != null && isToday(it.startAt!!) }.sortedBy { it.startAt }
            val upcoming = s.tasks.filter { it.status == "planned" && it.startAt != null && !isToday(it.startAt!!) && it.startAt!! > now }.sortedBy { it.startAt }.take(20)
            val unscheduled = s.tasks.filter { it.status == "planned" && it.startAt == null }
            val done = s.tasks.filter { it.status == "done" && it.completedAt != null && isToday(it.completedAt!!) }

            awaiting.forEach { body.add(askCard(s, it), 18) }
            fun section(title: String, list: List<Task>) {
                if (list.isEmpty()) return
                body.add(eyebrow("$title · ${list.size}"), 26)
                val c = card(pad = 0).apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
                list.forEachIndexed { i, t ->
                    if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
                    c.addView(taskRow(t, clashes))
                }
                body.add(c, 10)
            }
            section("Now", active); section("Blocked", blocked); section("Later today", later)
            section("Coming up", upcoming); section("Unscheduled", unscheduled); section("Done today", done)
            if (s.tasks.none { it.status in Engine.OPEN } && done.isEmpty()) {
                body.add(card().apply {
                    addView(text("Nothing scheduled", 20f, FG, DISPLAY))
                    addView(text("Read 20 pages at 21:00 for 30m", 15f, MUTED, MONO).apply { setPadding(0, dp(8), 0, 0) })
                }, 22)
            }
        }
        return page(body)
    }

    private fun quickAdd(f: EditText) {
        val v = f.text.toString().trim()
        if (v.isEmpty()) { editTask(null); return }
        val now = System.currentTimeMillis()
        val p = Engine.parseQuick(v, now)
        if (p.title.isBlank()) { toast("Give the task a title."); return }
        Store.edit(this) { Engine.create(it, p.title, now, p.startAt, p.durationMin ?: 30) }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        toast(if (p.startAt != null) "Added for ${Brain.dayLabel(p.startAt)} ${Reminders.fmt(p.startAt)}." else "Added to Unscheduled.")
        render()
    }

    private fun promptFor(s: AtlasState, t: Task) = when (s.settings.tone) {
        "gentle" -> "How did “${t.title}” go?"
        "challenging" -> "Time is up on “${t.title}”. Did you do it?"
        else -> "“${t.title}” was due to finish. What's the status?"
    }

    private fun askCard(s: AtlasState, t: Task): View {
        val c = card(edge = ORANGE)
        c.addView(eyebrow("Check-in").apply { setTextColor(ORANGE) })
        c.addView(text(promptFor(s, t), 19f, FG, DISPLAY).apply { setPadding(0, dp(6), 0, 0) })
        val meta = buildString {
            t.startAt?.let { append(Reminders.fmt(it)).append(" · ") }
            append("${t.durationMin} min")
            if (Engine.followUpsExhausted(s, t)) t.promptedAt?.let { append(" · waiting since ${Reminders.fmt(it)}") }
        }
        c.addView(text(meta, 14f, MUTED, MONO).apply { setPadding(0, dp(6), 0, dp(6)) })
        val id = t.id
        c.add(primary("Done") { respond(id, "done") }, 6)
        c.addView(buttons(listOf(
            secondary("Still working") { pickMinutes(id) },
            secondary("Delayed") { pickDelay(id, t) },
            secondary("Blocked") { askReason(id, "blocked") },
            secondary("Skipped") { askReason(id, "skipped") }
        )))
        return c
    }

    private fun respond(id: String, kind: String, minutes: Int? = null, startAt: Long? = null, reason: String = "") {
        val now = System.currentTimeMillis()
        try {
            val (t, next) = Store.edit(this) { Engine.respond(it, id, kind, now, minutes, startAt, reason) }
            getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
            when (kind) {
                "done" -> { celebrate(); toast(if (next != null) "Done. Next one ${Brain.dayLabel(next.startAt!!)} ${Reminders.fmt(next.startAt!!)}." else "Done at ${Reminders.fmt(now)}.") }
                "progress" -> toast("I'll check again in $minutes minutes.")
                "delay" -> toast("Moved to ${Brain.dayLabel(t.startAt!!)} ${Reminders.fmt(t.startAt!!)}.")
                "blocked" -> toast("Marked blocked.")
                "skipped" -> toast("Recorded as skipped.")
                "unblock" -> toast("Moved to Unscheduled.")
            }
        } catch (e: Exception) { toast(e.message ?: "That didn't work.") }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        render()
    }

    private fun pickMinutes(id: String) {
        val vals = intArrayOf(10, 15, 30, 60)
        choose("Check again in", vals.map { "$it minutes" }) { i -> respond(id, "progress", minutes = vals[i]) }
    }

    private fun pickDelay(id: String, t: Task) {
        val now = System.currentTimeMillis()
        choose("Move it to", listOf("In 30 minutes", "In 1 hour", "Tomorrow, same time", "Pick a day and time")) { i ->
            when (i) {
                0 -> respond(id, "delay", startAt = now + 30 * Engine.MIN)
                1 -> respond(id, "delay", startAt = now + 60 * Engine.MIN)
                2 -> {
                    var next = Instant.ofEpochMilli(t.startAt ?: now).atZone(Engine.zone).plusDays(1)
                    while (next.toInstant().toEpochMilli() < now) next = next.plusDays(1)
                    respond(id, "delay", startAt = next.toInstant().toEpochMilli())
                }
                else -> pickDateTime(now + 30 * Engine.MIN) { ms -> respond(id, "delay", startAt = ms) }
            }
        }
    }

    private fun pickDateTime(initial: Long, done: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(initial).atZone(Engine.zone)
        DatePickerDialog(this, pickerTheme(), { _, y, m, d ->
            TimePickerDialog(this, pickerTheme(), { _, h, min ->
                done(LocalDate.of(y, m + 1, d).atTime(h, min).atZone(Engine.zone).toInstant().toEpochMilli())
            }, z.hour, z.minute, true).show()
        }, z.year, z.monthValue - 1, z.dayOfMonth).show()
    }

    private fun askReason(id: String, kind: String) {
        sheet(if (kind == "blocked") "What's in the way?" else "Skipped") { box, d ->
            val f = field(if (kind == "blocked") "The blocker" else "Reason (optional)")
            box.add(f, 14)
            box.add(primary("Save") {
                val r = f.text.toString()
                if (kind == "blocked" && r.isBlank()) toast("Say what's blocking it.")
                else { d.dismiss(); respond(id, kind, reason = r) }
            }, 12)
        }
    }

    private fun taskRow(t: Task, clashes: Set<String>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)); gravity = Gravity.CENTER_VERTICAL }
        val whenTxt = t.startAt?.let { at ->
            val sameDay = Instant.ofEpochMilli(at).atZone(Engine.zone).toLocalDate() == LocalDate.now(Engine.zone)
            if (sameDay) Reminders.fmt(at) else Instant.ofEpochMilli(at).atZone(Engine.zone).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + "\n" + Reminders.fmt(at)
        } ?: "—"
        r.addView(text(whenTxt, 15f, if (t.startAt != null) FG else MUTED, NUM), LinearLayout.LayoutParams(dp(68), WRAP))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(t.title, 16f, if (t.status == "done") MUTED else FG, MED))
        val meta = mutableListOf<String>()
        if (t.startAt != null) meta.add("${t.durationMin} min")
        if (t.recurrence != "none") meta.add(t.recurrence.replaceFirstChar { it.uppercase() })
        if (t.priority == "high") meta.add("High priority")
        if (t.status == "blocked" && t.blockedReason.isNotBlank()) meta.add(t.blockedReason)
        if (t.status == "done") t.completedAt?.let { meta.add("Done ${Reminders.fmt(it)}") }
        if (t.notes.isNotBlank()) meta.add(t.notes)
        if (meta.isNotEmpty()) mid.addView(text(meta.joinToString(" · "), 13f, MUTED))
        if (t.id in clashes) mid.addView(text("Overlaps another task", 13f, ORANGE, MED))
        r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        val (label, col) = when (t.status) {
            "active" -> "Now" to ORANGE; "awaiting" -> "Answer" to ORANGE; "blocked" -> "Blocked" to MISS
            "done" -> "Done" to DONE; "skipped" -> "Skipped" to MUTED; "cancelled" -> "Cancelled" to MUTED
            else -> "" to MUTED
        }
        if (label.isNotEmpty()) r.addView(text(label, 12f, col, SEMI).apply {
            background = Finish(SURFACE, -1f, dark, density, glass = true); setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        r.setOnClickListener { taskActions(t) }
        return r
    }

    private fun taskActions(t: Task) {
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        val now = System.currentTimeMillis()
        if (t.status == "planned") acts.add("Start now" to { Store.edit(this) { Engine.startNow(it, t.id, now) }; scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render() })
        if (t.status == "active" || t.status == "awaiting") acts.add("Mark done" to { respond(t.id, "done") })
        if (t.status == "blocked") acts.add("Unblock" to { respond(t.id, "unblock") })
        if (t.status in Engine.OPEN) {
            acts.add("Edit" to { editTask(t) })
            acts.add("Cancel task" to { Store.edit(this) { Engine.cancel(it, t.id, now) }; scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render() })
        }
        acts.add("Delete" to {
            confirm("Delete this task?", t.title, "Delete") {
                Store.edit(this) { Engine.delete(it, t.id, now) }; scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render()
            }
        })
        choose(t.title, acts.map { it.first }) { i -> acts[i].second() }
    }

    private fun editTask(t: Task?) {
        var startAt: Long? = t?.startAt
        var priority = t?.priority ?: "normal"
        var recurrence = t?.recurrence ?: "none"
        sheet(if (t == null) "New task" else "Edit task") { box, d ->
            val title = field("What", t?.title ?: "")
            box.add(title, 14)
            fun whenLabel() = startAt?.let { "${Brain.dayLabel(it).replaceFirstChar { c -> c.uppercase() }} ${Reminders.fmt(it)}" } ?: "Unscheduled"
            val whenBtn = secondary(whenLabel()) {}
            whenBtn.setOnClickListener { pickDateTime(startAt ?: (System.currentTimeMillis() + 60 * Engine.MIN)) { ms -> startAt = ms; whenBtn.text = whenLabel() } }
            val clear = secondary("Clear time") { startAt = null; whenBtn.text = whenLabel() }
            box.add(eyebrow("When"), 16)
            box.add(buttons(listOf(whenBtn, clear)), 0)
            box.add(eyebrow("Minutes"), 16)
            val dur = field("Minutes", (t?.durationMin ?: 30).toString(), numeric = true)
            box.add(dur, 8)
            box.add(eyebrow("Priority"), 16)
            val priRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun drawPri() {
                priRow.removeAllViews()
                listOf("high" to "High", "normal" to "Normal", "low" to "Low").forEach { (k, l) ->
                    priRow.addView(chip(l, priority == k) { priority = k; drawPri() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
                }
            }
            drawPri(); box.add(priRow, 8)
            box.add(eyebrow("Repeats"), 16)
            val recRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun drawRec() {
                recRow.removeAllViews()
                val opts = listOf("none" to "Never", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly")
                opts.chunked(2).forEach { pair ->
                    val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { (k, l) -> line.addView(chip(l, recurrence == k) { recurrence = k; drawRec() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8); topMargin = dp(8) }) }
                    recRow.addView(line)
                }
            }
            drawRec(); box.add(recRow, 0)
            box.add(eyebrow("Notes"), 16)
            val notes = field("Optional", t?.notes ?: "")
            box.add(notes, 8)
            box.add(primary(if (t == null) "Add task" else "Save") {
                if (recurrence != "none" && startAt == null) { toast("A repeating task needs a day and start time."); return@primary }
                val now = System.currentTimeMillis()
                try {
                    Store.edit(this) { s ->
                        if (t == null) Engine.create(s, title.text.toString(), now, startAt, dur.text.toString().toIntOrNull(), notes.text.toString(), priority, recurrence)
                        else Engine.update(s, t.id, title.text.toString(), now, startAt, dur.text.toString().toIntOrNull(), notes.text.toString(), priority, recurrence)
                    }
                    scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                    d.dismiss(); render()
                } catch (e: Exception) { toast(e.message ?: "Couldn't save.") }
            }, 20)
        }
    }

    // ================= MEMORY =================
    private fun memoryView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val f = field("Something to remember")
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(f, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        r.addView(primary("Save") {
            val v = f.text.toString().trim()
            if (v.isNotEmpty()) { Store.edit(this) { Engine.remember(it, v, System.currentTimeMillis()) }; ModelManager.markStale(); render() }
        }.apply { minHeight = dp(50); setPadding(dp(22), dp(12), dp(22), dp(12)) })
        body.add(r)

        val mems = Store.read(this) { it.memories.toList().reversed() }
        body.add(eyebrow("Saved · ${mems.size}"), 26)
        if (mems.isEmpty()) body.add(card().apply { addView(text("Nothing saved", 20f, FG, DISPLAY)) }, 10)
        else {
            val c = card(pad = 0).apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
            mems.forEachIndexed { i, m ->
                if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
                c.addView(text(m.text, 16f, FG).apply {
                    setPadding(0, dp(14), 0, dp(14))
                    setOnClickListener { confirm("Forget this?", m.text, "Forget") { Store.edit(this@MainActivity) { Engine.forget(it, m.id, System.currentTimeMillis()) }; ModelManager.markStale(); render() } }
                })
            }
            body.add(c, 10)
        }

        val log = Store.read(this) { it.log.takeLast(60).reversed().map { l -> Triple(l.at, l.type, l.title + if (l.detail.isNotBlank() && l.type in setOf("blocked", "skipped", "note", "progress")) " (${l.detail})" else "") } }
        if (log.isNotEmpty()) {
            body.add(eyebrow("Activity"), 26)
            val c = card(pad = 0).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
            val names = mapOf("created" to "Added", "edited" to "Edited", "deleted" to "Deleted", "cancelled" to "Cancelled", "started" to "Started",
                "reminded" to "Reminded", "checkin" to "Asked", "followup" to "Followed up", "done" to "Done", "progress" to "Still working",
                "delayed" to "Delayed", "unblocked" to "Unblocked", "blocked" to "Blocked", "skipped" to "Skipped", "note" to "Note",
                "remembered" to "Remembered", "forgot" to "Forgot")
            log.forEach { (at, type, title) ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
                line.addView(text(Reminders.fmt(at), 13f, MUTED, MONO), LinearLayout.LayoutParams(dp(52), WRAP))
                line.addView(text("${names[type] ?: type} · $title", 14f, FG), LinearLayout.LayoutParams(0, WRAP, 1f))
                c.addView(line)
            }
            body.add(c, 10)
        }
        return page(body)
    }

    // ================= ME (settings) =================
    private fun settingsView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = Store.read(this) { it.settings }

        // Look
        body.add(eyebrow("Look"))
        val looks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("dark" to "Night", "light" to "Day", "system" to "System").forEach { (k, l) ->
            looks.addView(chip(l, st.theme == k) {
                if (st.theme != k) { Store.edit(this) { it.settings.theme = k }; recreate() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        body.add(looks, 10)

        // Brain
        body.add(eyebrow("Brain"), 26)
        val brain = card()
        brain.addView(text(ModelManager.modelName(this) ?: "No model", 18f, FG, DISPLAY))
        brain.addView(text(ModelManager.status, 14f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        val progress = text("", 14f, ORANGE, MONO)
        brain.addView(progress)
        modelProgressView = progress
        brain.add(primary("Choose model file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MODEL)
        }, 14)
        brain.addView(buttons(listOf(
            secondary(if (ModelManager.ready) "Reload" else "Load") { progress.text = "Loading…"; scope.launch { ModelManager.load(applicationContext); render() } },
            secondary("Remove") { confirm("Remove the model?", ModelManager.modelName(this) ?: "Model file", "Remove") {
                scope.launch(Dispatchers.IO) { ModelManager.remove(applicationContext); withContext(Dispatchers.Main) { render() } }
            } }
        )))
        body.add(brain, 10)

        // You
        body.add(eyebrow("You"), 26)
        val you = card()
        val name = field("What Atlas calls you", st.name)
        you.addView(name)
        you.add(eyebrow("Check-in tone"), 16)
        var tone = st.tone
        val toneRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun drawTone() {
            toneRow.removeAllViews()
            listOf("gentle" to "Supportive", "firm" to "Strict but reasonable", "challenging" to "Highly challenging").forEach { (k, l) ->
                toneRow.addView(chip(l, tone == k) { tone = k; drawTone() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            }
        }
        drawTone(); you.addView(toneRow)

        // Reminders
        var qs = st.quietStart; var qe = st.quietEnd; var fu = st.followUps; var speakOn = st.speakReplies
        you.add(eyebrow("Quiet hours"), 18)
        val qsBtn = secondary("From $qs") {}
        val qeBtn = secondary("Until $qe") {}
        qsBtn.setOnClickListener { val m = Engine.hm(qs) ?: 0; TimePickerDialog(this, pickerTheme(), { _, h, mi -> qs = "%02d:%02d".format(h, mi); qsBtn.text = "From $qs" }, m / 60, m % 60, true).show() }
        qeBtn.setOnClickListener { val m = Engine.hm(qe) ?: 0; TimePickerDialog(this, pickerTheme(), { _, h, mi -> qe = "%02d:%02d".format(h, mi); qeBtn.text = "Until $qe" }, m / 60, m % 60, true).show() }
        you.addView(buttons(listOf(qsBtn, qeBtn)))
        you.add(eyebrow("Follow up after (minutes)"), 18)
        val grace = field("15", st.graceMin.toString(), numeric = true)
        you.add(grace, 8)
        you.add(eyebrow("Follow-ups"), 18)
        val fuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawFu() { fuRow.removeAllViews(); (0..3).forEach { n -> fuRow.addView(chip("$n", fu == n) { fu = n; drawFu() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }) } }
        drawFu(); you.add(fuRow, 8)
        you.add(eyebrow("Speak replies"), 18)
        val spRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawSp() { spRow.removeAllViews(); listOf(false to "Off", true to "On").forEach { (v, l) -> spRow.addView(chip(l, speakOn == v) { speakOn = v; drawSp() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }) } }
        drawSp(); you.add(spRow, 8)
        you.add(primary("Save") {
            Store.edit(this) { s ->
                s.settings.name = name.text.toString().trim().take(40)
                s.settings.tone = tone
                s.settings.quietStart = qs; s.settings.quietEnd = qe
                s.settings.graceMin = (grace.text.toString().toIntOrNull() ?: 15).coerceIn(1, 240)
                s.settings.followUps = fu
                s.settings.speakReplies = speakOn
            }
            scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
            toast("Saved."); updateHero()
        }, 20)
        body.add(you, 10)

        // Avatar
        body.add(eyebrow("Avatar"), 26)
        val av = card()
        val overlayOk = AvatarService.canShow(this)
        av.addView(text(if (!overlayOk) "Needs permission" else if (st.avatarOn) "On screen" else "Off", 18f, FG, DISPLAY))
        av.add(if (!overlayOk) primary("Grant permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else secondary(if (st.avatarOn) "Turn off" else "Turn on") {
            val on = Store.edit(this) { s -> s.settings.avatarOn = !s.settings.avatarOn; s.settings.avatarOn }
            if (on) AvatarService.start(this) else AvatarService.stop(this)
            render()
        }, 14)
        body.add(av, 10)

        // Reliability
        body.add(eyebrow("Reminders"), 26)
        val rel = card()
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun status(label: String, ok: Boolean, okText: String, badText: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
            addView(text(label, 15f, FG), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(text(if (ok) okText else badText, 15f, if (ok) DONE else ORANGE, MED))
        }
        rel.addView(status("Notifications", notifOk, "Allowed", "Blocked"))
        rel.addView(status("Exact alarms", am.canScheduleExactAlarms(), "Allowed", "Not allowed"))
        rel.addView(status("Battery", pm.isIgnoringBatteryOptimizations(packageName), "Unrestricted", "Optimised"))
        rel.add(buttons(listOf(
            secondary("Notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) },
            secondary("Battery") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            secondary("App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            secondary("Test in 1 min") {
                val now = System.currentTimeMillis()
                Store.edit(this) { Engine.create(it, "Atlas test reminder", now, now + 60_000, 5) }
                scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                toast("Lock the phone. It should arrive in about a minute.")
            }
        )), 6)
        body.add(rel, 10)

        // Backup
        body.add(eyebrow("Backup"), 26)
        val bk = card()
        bk.addView(buttons(listOf(
            secondary("Copy backup") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas backup", Store.exportJson(this)))
                toast("Backup copied.")
            },
            secondary("Restore") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
            }
        )))
        body.add(bk, 10)
        body.add(text("Atlas ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f, MUTED, MONO).apply { gravity = Gravity.CENTER }, 26)
        return page(body)
    }

    private var modelProgressView: TextView? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_VOICE -> {
                val said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                tab = "chat"; render(); sendMessage(said)
            }
            REQ_MODEL -> {
                val uri = data.data ?: return
                scope.launch {
                    try {
                        val name = ModelManager.import(applicationContext, uri) { done, total ->
                            val txt = if (total > 0) "Copying ${done * 100 / total}% of ${total / 1_000_000} MB" else "Copying ${done / 1_000_000} MB"
                            runOnUiThread { modelProgressView?.text = txt }
                        }
                        modelProgressView?.text = "Copied $name. Loading…"
                        ModelManager.load(applicationContext)
                    } catch (e: Exception) { toast(e.message ?: "Couldn't use that file.") }
                    render()
                }
            }
            REQ_IMPORT -> {
                val uri = data.data ?: return
                scope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) {
                            val txt = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: throw IllegalStateException("Couldn't open that file.")
                            Store.importJson(applicationContext, txt)
                        }
                        withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                        toast("Restored $n tasks.")
                    } catch (e: Exception) { toast("Restore failed: ${e.message}") }
                    render()
                }
            }
        }
    }
}
