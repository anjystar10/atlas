package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
import java.util.Locale

class MainActivity : Activity() {
    // ---- palette (single dark theme, matches the ember) ----
    private val BG = 0xFF14100E.toInt(); private val SURFACE = 0xFF1D1815.toInt(); private val SURFACE2 = 0xFF27201B.toInt()
    private val LINE = 0xFF3A2F28.toInt(); private val FG = 0xFFF4EBE2.toInt(); private val MUTED = 0xFFA9998C.toInt()
    private val EMBER = 0xFFFF7A1A.toInt(); private val AMBER = 0xFFFFB347.toInt(); private val OK = 0xFF73D295.toInt()
    private val WARN = 0xFFFFC857.toInt(); private val CRIT = 0xFFFF6F61.toInt()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tab = "chat"
    private lateinit var content: FrameLayout
    private lateinit var heroStatus: TextView
    private lateinit var flame: FlameView
    private val tabButtons = LinkedHashMap<String, TextView>()
    private var tts: TextToSpeech? = null
    private var busy = false
    private var refreshJob: Job? = null

    private data class Msg(val fromUser: Boolean, var text: String, val source: String)
    companion object {
        private val chat = mutableListOf<Msg>()
        private const val REQ_MODEL = 11; private const val REQ_VOICE = 12; private const val REQ_IMPORT = 13; private const val REQ_NOTIF = 14
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.zone = ZoneId.systemDefault()
        Reminders.channels(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = BG
        tab = intent.getStringExtra("tab") ?: "chat"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }

        // Hero: ember + status line
        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(16), dp(4)) }
        flame = FlameView(this, withGlow = true, fps = 30)
        hero.addView(flame, LinearLayout.LayoutParams(dp(84), dp(96)))
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroText.addView(text("Atlas", 24f, FG, bold = true))
        heroStatus = text("", 14f, MUTED)
        heroText.addView(heroStatus)
        hero.addView(heroText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(hero)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(SURFACE); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        for ((key, label) in listOf("chat" to "Chat", "today" to "Today", "memory" to "Memory", "settings" to "Settings")) {
            val b = text(label, 14f, MUTED, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = key; render() }
            }
            tabButtons[key] = b
            nav.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        val avatarOn = Store.read(this) { it.settings.avatarOn }
        if (avatarOn) AvatarService.start(this)
        autoLoadModel()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { tab = it; render() }
    }

    override fun onResume() {
        super.onResume()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                updateHero()
                if (tab == "today") render()
                delay(30_000)
            }
        }
    }

    override fun onPause() { refreshJob?.cancel(); super.onPause() }

    override fun onDestroy() { scope.cancel(); tts?.shutdown(); super.onDestroy() }

    private fun autoLoadModel() {
        if (ModelManager.ready || ModelManager.modelName(this) == null) return
        if (ModelManager.lastLoadCrashed(this)) {
            toast("The last model load did not finish, so I skipped auto-loading. Load it from Settings.")
            return
        }
        scope.launch { ModelManager.load(applicationContext); updateHero(); if (tab == "settings") render() }
    }

    // ---------- small view helpers ----------
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun box(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun button(label: String, primary: Boolean = false, color: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(if (primary || color != null) 0xFF1B0B03.toInt() else FG)
        background = box(color ?: if (primary) EMBER else SURFACE2, 12)
        minHeight = dp(42); minimumHeight = dp(42); setPadding(dp(14), 0, dp(14), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun heading(s: String) = text(s.uppercase(Locale.ROOT), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.09f; setPadding(0, dp(18), 0, dp(6))
    }
    private fun row(vararg views: View, gap: Int = 8) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(gap) })
        }
    }
    private fun flow(views: List<View>): LinearLayout {
        // simple wrap: two per line
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v -> r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6); topMargin = dp(6) }) }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            col.addView(r)
        }
        return col
    }
    private fun input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) = EditText(this).apply {
        this.hint = hint; setText(value); setHintTextColor(0xFF7D6F64.toInt()); setTextColor(FG); textSize = 15f
        inputType = type; background = box(SURFACE, 12, LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun scroll(body: LinearLayout) = ScrollView(this).apply {
        isFillViewport = true
        addView(body.apply { setPadding(dp(16), dp(4), dp(16), dp(24)) })
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun updateHero() {
        val now = System.currentTimeMillis()
        val (msg, mood) = Store.read(this) { s ->
            val awaiting = s.tasks.count { it.status == "awaiting" }
            val active = s.tasks.firstOrNull { it.status == "active" }
            val next = s.tasks.filter { it.status == "planned" && it.startAt != null && it.startAt!! > now }.minByOrNull { it.startAt!! }
            val m = when {
                awaiting > 0 -> "I need an answer on $awaiting task${if (awaiting > 1) "s" else ""}."
                active != null -> "Now: ${active.title}"
                next != null -> "Next: ${next.title}, ${Brain.dayLabel(next.startAt!!)} ${Reminders.fmt(next.startAt!!)}"
                else -> "Nothing scheduled. Tell me what matters today."
            }
            val md = when {
                busy -> FlameView.Mood.THINKING
                awaiting > 0 -> FlameView.Mood.ALERT
                Engine.inQuiet(s.settings, now) -> FlameView.Mood.SLEEP
                active != null -> FlameView.Mood.FOCUS
                else -> FlameView.Mood.IDLE
            }
            Pair(m, md)
        }
        heroStatus.text = msg
        if (flame.mood != FlameView.Mood.HAPPY) flame.mood = mood
        tabButtons.forEach { (k, b) ->
            b.setTextColor(if (k == tab) FG else MUTED)
            b.background = if (k == tab) box(SURFACE2, 10) else null
        }
    }

    private fun celebrate() {
        flame.mood = FlameView.Mood.HAPPY; flame.burst()
        AvatarService.celebrate(this)
        scope.launch { delay(4000); flame.mood = FlameView.Mood.IDLE; updateHero() }
    }

    private fun render() {
        updateHero()
        content.removeAllViews()
        content.addView(when (tab) {
            "today" -> todayView()
            "memory" -> memoryView()
            "settings" -> settingsView()
            else -> chatView()
        })
    }

    // ================= CHAT =================
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView

    private fun chatView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(12)) }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        if (chat.isEmpty()) chat.add(Msg(false, "I'm Atlas. Tell me what to remind you about, what to remember, or say “help”." +
            if (ModelManager.ready) "" else "\nNo model is loaded yet, so only fixed commands work. Add one in Settings.", "Atlas"))
        chat.forEach { chatList.addView(bubble(it)) }
        col.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val field = input("Message Atlas").apply { imeOptions = EditorInfo.IME_ACTION_SEND; maxLines = 4 }
        val send = button("Send", primary = true) { submit(field) }
        val mic = button("🎤") { voice() }.apply { contentDescription = "Speak" }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(field); true } else false }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)) }
        bar.addView(mic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(send)
        col.addView(bar)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return col
    }

    private fun bubble(m: Msg): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (m.fromUser) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4)) }
        val tv = text(m.text, 15f, if (m.fromUser) 0xFF1B0B03.toInt() else FG).apply {
            background = box(if (m.fromUser) AMBER else SURFACE, 16)
            setPadding(dp(12), dp(9), dp(12), dp(9)); setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        wrap.addView(tv)
        if (!m.fromUser) wrap.addView(text(m.source, 11f, MUTED).apply { setPadding(dp(4), dp(2), 0, 0) })
        return wrap
    }

    private fun submit(field: EditText) {
        val q = field.text.toString().trim()
        if (q.isEmpty() || busy) return
        field.setText("")
        sendMessage(q)
    }

    private fun sendMessage(q: String) {
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
                val r = try {
                    Brain.ask(applicationContext, q) { partial -> tv.text = partial; chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }
                } catch (e: Exception) { Reply("The model failed: ${e.message}", "Atlas") }
                chat.remove(pending); chatList.removeView(pendingView)
                r
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
        if (t == null) {
            tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas") }
        } else t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas")
    }

    // ================= TODAY =================
    private fun todayView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val quick = input("Add: Gym at 6pm for 1h").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        val add = button("Add", primary = true) { quickAdd(quick) }
        quick.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { quickAdd(quick); true } else false }
        val qrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qrow.addView(quick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        qrow.addView(add)
        body.addView(qrow)
        body.addView(button("+ New task with details") { editTask(null) }.apply { background = box(BG, 12, LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

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

            if (awaiting.isNotEmpty()) {
                body.addView(heading("Atlas needs to ask you · ${awaiting.size}"))
                awaiting.forEach { body.addView(askCard(s, it)) }
            }
            fun section(title: String, list: List<Task>) {
                if (list.isEmpty()) return
                body.addView(heading("$title · ${list.size}"))
                list.forEach { body.addView(taskRow(it, clashes)) }
            }
            section("Now", active); section("Blocked", blocked); section("Later today", later)
            section("Coming up", upcoming); section("Unscheduled", unscheduled); section("Done today", done)
            if (s.tasks.none { it.status in Engine.OPEN } && done.isEmpty()) {
                body.addView(text("Nothing here yet. Type a task above, like “Read 20 pages at 21:00 for 30m”. Atlas reminds you when it starts, asks how it went when it should be finished, and follows up once if you don't answer.", 14f, MUTED).apply { setPadding(0, dp(16), 0, 0) })
            }
        }
        return scroll(body)
    }

    private fun quickAdd(field: EditText) {
        val v = field.text.toString().trim()
        if (v.isEmpty()) { editTask(null); return }
        val now = System.currentTimeMillis()
        val p = Engine.parseQuick(v, now)
        if (p.title.isBlank()) { toast("Give the task a title."); return }
        Store.edit(this) { Engine.create(it, p.title, now, p.startAt, p.durationMin ?: 30) }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        toast(if (p.startAt != null) "Added for ${Brain.dayLabel(p.startAt)} ${Reminders.fmt(p.startAt)}" else "Added to Unscheduled")
        render()
    }

    private fun promptFor(s: AtlasState, t: Task) = when (s.settings.tone) {
        "gentle" -> "How did “${t.title}” go?"
        "challenging" -> "Time is up on “${t.title}”. Did you do it?"
        else -> "“${t.title}” was due to finish. What is the status?"
    }

    private fun askCard(s: AtlasState, t: Task): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box(SURFACE, 16, WARN); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(text(promptFor(s, t), 17f, FG, bold = true))
        val meta = buildString {
            t.startAt?.let { append(Reminders.fmt(it)).append(" · ") }
            append("${t.durationMin} min")
            if (Engine.followUpsExhausted(s, t)) t.promptedAt?.let { append(" · waiting since ${Reminders.fmt(it)}") }
        }
        card.addView(text(meta, 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        val id = t.id
        card.addView(flow(listOf(
            button("Done", color = OK) { respond(id, "done") },
            button("Still working") { pickMinutes(id) },
            button("Delayed") { pickDelay(id, t) },
            button("Blocked") { askReason(id, "blocked") },
            button("Skipped") { askReason(id, "skipped") }
        )))
        return card.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }
    }

    private fun respond(id: String, kind: String, minutes: Int? = null, startAt: Long? = null, reason: String = "") {
        val now = System.currentTimeMillis()
        try {
            val (t, next) = Store.edit(this) { Engine.respond(it, id, kind, now, minutes, startAt, reason) }
            getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
            when (kind) {
                "done" -> { celebrate(); toast(if (next != null) "Done. Next one ${Brain.dayLabel(next.startAt!!)} ${Reminders.fmt(next.startAt!!)}." else "Done at ${Reminders.fmt(now)}.") }
                "progress" -> toast("Got it. I'll check again in $minutes minutes.")
                "delay" -> toast("Moved to ${Brain.dayLabel(t.startAt!!)} ${Reminders.fmt(t.startAt!!)}.")
                "blocked" -> toast("Marked blocked. It stays on Today until you unblock it.")
                "skipped" -> toast("Recorded as skipped.")
                "unblock" -> toast("Back in Unscheduled. Give it a time when you're ready.")
            }
        } catch (e: Exception) { toast(e.message ?: "That didn't work.") }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        render()
    }

    private fun pickMinutes(id: String) {
        val opts = arrayOf("10 minutes", "15 minutes", "30 minutes", "60 minutes")
        val vals = intArrayOf(10, 15, 30, 60)
        dialog().setTitle("Check again in").setItems(opts) { _, i -> respond(id, "progress", minutes = vals[i]) }.show()
    }

    private fun pickDelay(id: String, t: Task) {
        val now = System.currentTimeMillis()
        val opts = arrayOf("In 30 minutes", "In 1 hour", "Tomorrow, same time", "Pick a day and time")
        dialog().setTitle("Move it to").setItems(opts) { _, i ->
            when (i) {
                0 -> respond(id, "delay", startAt = now + 30 * Engine.MIN)
                1 -> respond(id, "delay", startAt = now + 60 * Engine.MIN)
                2 -> {
                    val base = t.startAt ?: now
                    var next = Instant.ofEpochMilli(base).atZone(Engine.zone).plusDays(1)
                    while (next.toInstant().toEpochMilli() < now) next = next.plusDays(1)
                    respond(id, "delay", startAt = next.toInstant().toEpochMilli())
                }
                else -> pickDateTime(now + 30 * Engine.MIN) { ms -> respond(id, "delay", startAt = ms) }
            }
        }.show()
    }

    private fun pickDateTime(initial: Long, done: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(initial).atZone(Engine.zone)
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                done(LocalDate.of(y, m + 1, d).atTime(h, min).atZone(Engine.zone).toInstant().toEpochMilli())
            }, z.hour, z.minute, true).show()
        }, z.year, z.monthValue - 1, z.dayOfMonth).show()
    }

    private fun askReason(id: String, kind: String) {
        val f = input(if (kind == "blocked") "What is in the way?" else "Reason (optional)")
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        dialog().setTitle(if (kind == "blocked") "Blocked" else "Skipped").setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val r = f.text.toString()
                if (kind == "blocked" && r.isBlank()) toast("Say what's blocking it, so we can deal with it.")
                else respond(id, kind, reason = r)
            }
            .setNegativeButton("Back", null).show()
    }

    private fun taskRow(t: Task, clashes: Set<String>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)); gravity = Gravity.TOP }
        val whenTxt = t.startAt?.let { at ->
            val sameDay = Instant.ofEpochMilli(at).atZone(Engine.zone).toLocalDate() == LocalDate.now(Engine.zone)
            if (sameDay) Reminders.fmt(at) else Brain.dayLabel(at).take(3) + "\n" + Reminders.fmt(at)
        } ?: "—"
        r.addView(text(whenTxt, 13f, if (t.startAt != null) AMBER else MUTED).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(t.title, 15f, if (t.status == "done") MUTED else FG, bold = true))
        val meta = mutableListOf<String>()
        if (t.startAt != null) meta.add("${t.durationMin} min")
        if (t.recurrence != "none") meta.add(t.recurrence.replaceFirstChar { it.uppercase() })
        if (t.priority == "high") meta.add("High priority")
        if (t.status == "blocked" && t.blockedReason.isNotBlank()) meta.add("Blocked: ${t.blockedReason}")
        if (t.status == "done") t.completedAt?.let { meta.add("Done ${Reminders.fmt(it)}") }
        if (t.notes.isNotBlank()) meta.add(t.notes)
        if (meta.isNotEmpty()) mid.addView(text(meta.joinToString(" · "), 13f, MUTED))
        if (t.id in clashes) mid.addView(text("Overlaps another task", 12f, WARN))
        r.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pillColor = when (t.status) { "active" -> AMBER; "awaiting" -> WARN; "blocked" -> CRIT; "done" -> OK; else -> MUTED }
        val label = mapOf("planned" to "Planned", "active" to "Now", "awaiting" to "Needs answer", "blocked" to "Blocked", "done" to "Done", "skipped" to "Skipped", "cancelled" to "Cancelled")[t.status] ?: t.status
        r.addView(text(label, 11f, pillColor, bold = true).apply { background = box(BG, 99, pillColor); setPadding(dp(8), dp(3), dp(8), dp(3)) })
        r.setOnClickListener { taskActions(t) }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(r)
        wrap.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrap
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
            dialog().setTitle("Delete “${t.title}”?").setMessage("This can't be undone.")
                .setPositiveButton("Delete") { _, _ -> Store.edit(this) { Engine.delete(it, t.id, now) }; scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render() }
                .setNegativeButton("Keep", null).show()
        })
        dialog().setTitle(t.title).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun editTask(t: Task?) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = input("What", t?.title ?: "")
        var startAt: Long? = t?.startAt
        val whenBtn = button(startAt?.let { "${Brain.dayLabel(it)} ${Reminders.fmt(it)}" } ?: "Unscheduled · tap to set") {}
        whenBtn.setOnClickListener {
            pickDateTime(startAt ?: (System.currentTimeMillis() + 60 * Engine.MIN)) { ms -> startAt = ms; whenBtn.text = "${Brain.dayLabel(ms)} ${Reminders.fmt(ms)}" }
        }
        val clearBtn = button("Clear time") { startAt = null; whenBtn.text = "Unscheduled · tap to set" }
        val dur = input("Minutes", (t?.durationMin ?: 30).toString(), InputType.TYPE_CLASS_NUMBER)
        val pri = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("normal", "high", "low")); setSelection(listOf("normal", "high", "low").indexOf(t?.priority ?: "normal").coerceAtLeast(0)) }
        val recs = Engine.RECURRENCES
        val rec = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Does not repeat", "Daily", "Weekdays", "Weekly")); setSelection(recs.indexOf(t?.recurrence ?: "none").coerceAtLeast(0)) }
        val notes = input("Notes", t?.notes ?: "")
        col.addView(title); col.addView(row(whenBtn, clearBtn).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(dur.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
        col.addView(text("Priority", 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) }); col.addView(pri)
        col.addView(text("Repeats", 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) }); col.addView(rec)
        col.addView(notes.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
        val d = dialog().setTitle(if (t == null) "New task" else "Edit task").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(if (t == null) "Add task" else "Save", null).setNegativeButton("Close", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val recurrence = recs[rec.selectedItemPosition]
                if (recurrence != "none" && startAt == null) { toast("A repeating task needs a day and start time."); return@setOnClickListener }
                val now = System.currentTimeMillis()
                try {
                    Store.edit(this) { s ->
                        if (t == null) Engine.create(s, title.text.toString(), now, startAt, dur.text.toString().toIntOrNull(), notes.text.toString(), pri.selectedItem as String, recurrence)
                        else Engine.update(s, t.id, title.text.toString(), now, startAt, dur.text.toString().toIntOrNull(), notes.text.toString(), pri.selectedItem as String, recurrence)
                    }
                    scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                    d.dismiss(); render()
                } catch (e: Exception) { toast(e.message ?: "Could not save.") }
            }
        }
        d.show()
    }

    // ================= MEMORY =================
    private fun memoryView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text("What Atlas remembers. Everything stays on this phone.", 14f, MUTED))
        val field = input("Add something to remember")
        val add = button("Save", primary = true) {
            val v = field.text.toString().trim()
            if (v.isNotEmpty()) { Store.edit(this) { Engine.remember(it, v, System.currentTimeMillis()) }; render() }
        }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        r.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        r.addView(add)
        body.addView(r)
        val mems = Store.read(this) { it.memories.toList().reversed() }
        body.addView(heading("Saved · ${mems.size}"))
        if (mems.isEmpty()) body.addView(text("Nothing saved yet. In Chat, say “remember …”.", 14f, MUTED))
        mems.forEach { m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(text(m.text, 15f, FG), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Forget") {
                dialog().setTitle("Forget this?").setMessage(m.text)
                    .setPositiveButton("Forget") { _, _ -> Store.edit(this) { Engine.forget(it, m.id, System.currentTimeMillis()) }; render() }
                    .setNegativeButton("Keep", null).show()
            })
            body.addView(row)
            body.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        body.addView(heading("Activity"))
        val log = Store.read(this) { it.log.takeLast(60).reversed().map { l -> Triple(l.at, l.type, l.title + if (l.detail.isNotBlank() && l.type in setOf("blocked", "skipped", "note", "progress")) " (${l.detail})" else "") } }
        if (log.isEmpty()) body.addView(text("Reminders, check-ins and your answers appear here.", 14f, MUTED))
        log.forEach { (at, type, title) ->
            body.addView(text("${Brain.dayLabel(at).take(3)} ${Reminders.fmt(at)}  ${type.replaceFirstChar { it.uppercase() }} · $title", 13f, MUTED).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
        return scroll(body)
    }

    // ================= SETTINGS =================
    private fun settingsView(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = Store.read(this) { it.settings }

        body.addView(heading("Model (the brain)"))
        body.addView(text(ModelManager.status, 14f, FG))
        body.addView(text("Download a GGUF chat model (1–3B, Q4_K_M) from Hugging Face in your browser, then choose the file here. It is copied into Atlas and runs offline on this phone.", 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        val progress = text("", 13f, AMBER)
        body.addView(progress)
        body.addView(flow(listOf(
            button("Choose model file", primary = true) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                startActivityForResult(i, REQ_MODEL)
            },
            button(if (ModelManager.ready) "Reload model" else "Load model") {
                progress.text = "Loading…"
                scope.launch { ModelManager.load(applicationContext); render() }
            },
            button("Remove model") {
                dialog().setTitle("Remove the model file?").setPositiveButton("Remove") { _, _ ->
                    scope.launch(Dispatchers.IO) { ModelManager.remove(applicationContext); withContext(Dispatchers.Main) { render() } }
                }.setNegativeButton("Keep", null).show()
            }
        )))
        modelProgressView = progress

        body.addView(heading("You"))
        val name = input("What Atlas calls you", st.name)
        body.addView(name)
        val tones = listOf("gentle" to "Supportive", "firm" to "Strict but reasonable", "challenging" to "Highly challenging")
        val tone = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tones.map { it.second }); setSelection(tones.indexOfFirst { it.first == st.tone }.coerceAtLeast(0)) }
        body.addView(text("Check-in tone", 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) }); body.addView(tone)

        body.addView(heading("Reminders"))
        var qs = st.quietStart; var qe = st.quietEnd
        val qsBtn = button("Quiet from $qs") {}
        val qeBtn = button("until $qe") {}
        qsBtn.setOnClickListener { val lt = Engine.hm(qs) ?: 0; TimePickerDialog(this, { _, h, m -> qs = "%02d:%02d".format(h, m); qsBtn.text = "Quiet from $qs" }, lt / 60, lt % 60, true).show() }
        qeBtn.setOnClickListener { val lt = Engine.hm(qe) ?: 0; TimePickerDialog(this, { _, h, m -> qe = "%02d:%02d".format(h, m); qeBtn.text = "until $qe" }, lt / 60, lt % 60, true).show() }
        body.addView(row(qsBtn, qeBtn))
        body.addView(text("During quiet hours Atlas stays silent and holds follow-ups until they end.", 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        val grace = input("Follow-up after (minutes)", st.graceMin.toString(), InputType.TYPE_CLASS_NUMBER)
        body.addView(text("Follow up after (minutes)", 12f, MUTED)); body.addView(grace)
        val fu = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("0 follow-ups", "1 follow-up", "2 follow-ups", "3 follow-ups")); setSelection(st.followUps.coerceIn(0, 3)) }
        body.addView(fu)
        body.addView(text("After the last follow-up a task stays marked as needing an answer. It is never marked done or skipped for you.", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        val speakBox = CheckBox(this).apply { text = "Speak replies out loud"; setTextColor(FG); isChecked = st.speakReplies }
        body.addView(speakBox)

        body.addView(button("Save settings", primary = true) {
            Store.edit(this) { s ->
                s.settings.name = name.text.toString().trim().take(40)
                s.settings.tone = tones[tone.selectedItemPosition].first
                s.settings.quietStart = qs; s.settings.quietEnd = qe
                s.settings.graceMin = (grace.text.toString().toIntOrNull() ?: 15).coerceIn(1, 240)
                s.settings.followUps = fu.selectedItemPosition
                s.settings.speakReplies = speakBox.isChecked
            }
            scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
            toast("Saved.")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        body.addView(heading("Avatar"))
        val overlayOk = AvatarService.canShow(this)
        body.addView(text(if (!overlayOk) "Needs permission to display over other apps." else if (st.avatarOn) "On. Tap it to open Atlas, drag it to move it." else "Off.", 14f, FG))
        body.addView(flow(listOf(
            button(if (!overlayOk) "Grant permission" else if (st.avatarOn) "Turn avatar off" else "Turn avatar on", primary = true) {
                if (!AvatarService.canShow(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val on = Store.edit(this) { s -> s.settings.avatarOn = !s.settings.avatarOn; s.settings.avatarOn }
                    if (on) AvatarService.start(this) else AvatarService.stop(this)
                    render()
                }
            }
        )))

        body.addView(heading("Make reminders reliable"))
        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        body.addView(text(
            "Notifications: ${if (notifOk) "allowed" else "blocked"}\n" +
                "Exact alarms: ${if (am.canScheduleExactAlarms()) "allowed" else "not allowed"}\n" +
                "Battery: ${if (unrestricted) "unrestricted" else "optimised (OPPO may delay reminders)"}", 14f, FG
        ))
        body.addView(text("On OPPO phones also open App info, then Battery, and allow background activity and auto-launch.", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        body.addView(flow(listOf(
            button("Allow notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) },
            button("Unrestrict battery") {
                @Suppress("BatteryLife")
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            button("App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            button("Test reminder in 1 min") {
                val now = System.currentTimeMillis()
                Store.edit(this) { Engine.create(it, "Atlas test reminder", now, now + 60_000, 5) }
                scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                toast("Lock the phone. A reminder should arrive in about a minute.")
            }
        )))

        body.addView(heading("Backup"))
        body.addView(text("Your data lives only on this phone. Copy a backup somewhere safe, or restore one (the web build's backups work too).", 13f, MUTED))
        body.addView(flow(listOf(
            button("Copy backup") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas backup", Store.exportJson(this)))
                toast("Backup copied. Paste it into a note or message to yourself.")
            },
            button("Restore from file") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                startActivityForResult(i, REQ_IMPORT)
            }
        )))
        body.addView(text("Atlas ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f, MUTED).apply { setPadding(0, dp(24), 0, 0) })
        return scroll(body)
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
                            val txt = if (total > 0) "Copying… ${done * 100 / total}% of ${total / 1_000_000} MB" else "Copying… ${done / 1_000_000} MB"
                            runOnUiThread { modelProgressView?.text = txt }
                        }
                        modelProgressView?.text = "Copied $name. Loading…"
                        ModelManager.load(applicationContext)
                    } catch (e: Exception) { toast(e.message ?: "Could not use that file.") }
                    render()
                }
            }
            REQ_IMPORT -> {
                val uri = data.data ?: return
                scope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) {
                            val txt = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: throw IllegalStateException("Could not open that file.")
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
