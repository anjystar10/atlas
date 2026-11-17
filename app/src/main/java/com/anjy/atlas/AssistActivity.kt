package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The "Hey Atlas" panel as a small sheet, for when Atlas may not draw over other apps (with that permission the
 * overlay [MiniPanel] is used instead): a card over the bottom of whatever you were doing, without opening the whole
 * app. It listens straight away when the wake word opened it, answers here (out loud too), and steps aside when Atlas
 * needs the screen to work in another app. Tap above it, swipe it down or press Back to put it away.
 */
class AssistActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var greet: TextView
    private lateinit var said: TextView
    private lateinit var answer: TextView
    private lateinit var steps: TextView
    private lateinit var field: EditText
    private lateinit var micBtn: FrameLayout
    private lateinit var sendBtn: FrameLayout
    private var voice: Hearing? = null
    private var listening = false
    /** The reply this panel is showing (the last message once it asked something). */
    private var asked = false
    private val onChat: () -> Unit = { show() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }

    private val BG = Palette.NIGHT.bg
    private val FG = Palette.NIGHT.fg
    private val MUTED = Palette.NIGHT.muted

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // see-through above the card, so the app underneath stays in view
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        Conversation.load(applicationContext)

        val display = font(R.font.newsreader_regular, Typeface.create("serif", Typeface.NORMAL))
        val body = font(R.font.geist_regular, Typeface.DEFAULT)
        val med = font(R.font.geist_medium, Typeface.DEFAULT)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            setOnClickListener { finish() }   // a tap above the card puts it away
        }
        val sheet = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.argb(244, Color.red(Palette.NIGHT.surface), Color.green(Palette.NIGHT.surface), Color.blue(Palette.NIGHT.surface)))
                setStroke(dp(1), Color.argb(70, Color.red(Palette.NIGHT.line), Color.green(Palette.NIGHT.line), Color.blue(Palette.NIGHT.line)))
            }
            elevation = dp(16).toFloat(); isClickable = true
            minimumHeight = (resources.displayMetrics.heightPixels * 0.36f).toInt()
        }
        // swipe the card down to put it away
        var downY = 0f
        sheet.setOnTouchListener { v, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = ev.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { v.translationY = (ev.rawY - downY).coerceAtLeast(0f); true }
                else -> { if (v.translationY > v.height * 0.25f) finish() else v.animate().translationY(0f).setDuration(160).start(); true }
            }
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.42f).toInt(), MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(10), dp(22), dp(8)) }
        orb = OrbView(this).apply { still = Prefs.reduceMotion(this@AssistActivity) }
        col.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(Color.argb(70, 255, 255, 255)) } },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply { bottomMargin = dp(8) })
        col.addView(orb, LinearLayout.LayoutParams(dp(64), dp(64)))
        val name = Store.read(this) { it.settings.name }.trim().split(" ").firstOrNull().orEmpty()
        greet = TextView(this).apply {
            text = if (name.isNotEmpty()) "Hey $name,\nWhat can I help with?" else "Hey there,\nWhat can I help with?"
            setTextColor(FG); textSize = 21f; typeface = display; gravity = Gravity.CENTER; letterSpacing = -0.005f
            setLineSpacing(0f, 1.05f)
        }
        col.addView(greet, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        said = TextView(this).apply { setTextColor(MUTED); textSize = 16f; typeface = med; gravity = Gravity.CENTER; visibility = View.GONE }
        col.addView(said, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        answer = TextView(this).apply { setTextColor(FG); textSize = 18f; typeface = body; gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); visibility = View.GONE; setTextIsSelectable(true) }
        col.addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        steps = TextView(this).apply { setTextColor(MUTED); textSize = 13f; typeface = body; gravity = Gravity.CENTER; visibility = View.GONE }
        col.addView(steps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        scroll.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(78) })
        root.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // close
        val close = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(30, 255, 255, 255)) }
            contentDescription = "Close"; isClickable = true
            addView(LineIcon(this@AssistActivity, "close").apply { color = MUTED }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { finish() }
        }
        sheet.addView(close, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { topMargin = dp(12); rightMargin = dp(12) })
        // the full app, only when asked
        val openApp = TextView(this).apply {
            text = "Open Atlas"; setTextColor(Palette.NIGHT.accentInk); textSize = 13f; typeface = med; setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { startActivity(Intent(this@AssistActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }
        }
        sheet.addView(openApp, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dp(12); leftMargin = dp(10) })

        // the ask bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(Palette.NIGHT.surface); setStroke(dp(1), withAlpha(Palette.NIGHT.accentInk, 40)) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        micBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(26, 255, 255, 255)) }
            contentDescription = "Speak"; isClickable = true
            addView(LineIcon(this@AssistActivity, "mic").apply { color = FG }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { if (listening) voice?.finish() else listen() }
        }
        field = EditText(this).apply {
            hint = "Ask anything…"; setHintTextColor(MUTED); setTextColor(FG); textSize = 15f; typeface = body
            background = null; setPadding(dp(14), dp(10), dp(8), dp(10)); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        sendBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(30).toFloat(); colors = intArrayOf(Palette.NIGHT.primaryTop, Palette.NIGHT.primaryBottom) }
            elevation = dp(6).toFloat(); outlineSpotShadowColor = Palette.NIGHT.accent; outlineAmbientShadowColor = Palette.NIGHT.accent
            contentDescription = "Send"; isClickable = true
            addView(LineIcon(this@AssistActivity, "arrow").apply { color = Palette.NIGHT.onPrimary; rotation = -90f }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { if (Conversation.busy) Conversation.stop() else submit() }
        }
        bar.addView(micBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        sheet.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(16) })

        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, 0)
            sheet.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        if (intent?.getBooleanExtra("listen", false) == true) { val w = intent.getBooleanExtra(AvatarService.EXTRA_FROM_WAKE_WORD, false); Hear.prewarm(this); root.postDelayed({ listen(afterWake = w) }, 250) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("listen", false) && !listening) listen(afterWake = intent.getBooleanExtra(AvatarService.EXTRA_FROM_WAKE_WORD, false))
    }

    override fun onResume() {
        super.onResume()
        AvatarService.setAppInFront(this, true)
        AvatarService.holdMic(this, true)
        Conversation.listen(onChat)
        show()
    }

    override fun onPause() {
        Conversation.unlisten(onChat)
        voice?.cancel(); voice = null; listening = false; orb.level = 0f
        AvatarService.setAppInFront(this, false)
        AvatarService.holdMic(this, false)
        super.onPause()
    }

    private fun listen(afterWake: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        Conversation.hush()
        voice?.cancel()
        listening = true
        greet.visibility = View.VISIBLE
        greet.text = "Listening…"
        said.visibility = View.GONE; answer.visibility = View.GONE; steps.visibility = View.GONE
        orb.energy = 1f
        voice = Hear.open(this, afterWake = afterWake,
            onPartial = { t -> said.visibility = View.VISIBLE; said.setTextColor(FG); said.text = t },
            onLevel = { l -> orb.level = l },
            onDone = { t ->
                listening = false; orb.level = 0f
                if (t.isNullOrBlank()) { greet.text = "I didn't catch that."; said.visibility = View.GONE; orb.energy = 0f; return@open }
                ask(t, spoken = true)
            },
            onError = { msg -> listening = false; orb.level = 0f; orb.energy = 0f; greet.visibility = View.VISIBLE; greet.text = msg }
        ).also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else greet.text = "Atlas needs the microphone to hear you."
    }

    private fun submit() {
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        field.setText("")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        ask(q, spoken = false)
    }

    private fun ask(q: String, spoken: Boolean) {
        asked = true
        Conversation.send(this, q, spoken)
    }

    /** Shows the latest exchange; steps aside when Atlas starts working in another app. */
    private fun show() {
        orb.energy = if (Conversation.busy || listening) 1f else 0f
        if (Conversation.busy && Conversation.usingPhone) { finish(); return }
        if (!asked || listening) return
        val msgs = Conversation.messages
        val last = msgs.lastOrNull { !it.fromUser } ?: return
        val q = msgs.lastOrNull { it.fromUser }
        greet.text = ""
        greet.visibility = View.GONE
        said.visibility = if (q != null) View.VISIBLE else View.GONE
        said.setTextColor(MUTED); said.text = q?.text ?: ""
        answer.visibility = View.VISIBLE
        answer.text = when {
            last.text.isNotBlank() -> last.text
            Conversation.busy -> "${Conversation.status ?: "Thinking"}…"
            else -> ""
        }
        steps.visibility = if (last.steps.isEmpty()) View.GONE else View.VISIBLE
        steps.text = last.steps.joinToString("\n")
        sendBtn.contentDescription = if (Conversation.busy) "Stop" else "Send"
    }
}
