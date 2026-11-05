package com.anjy.atlas

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anjy.atlas.core.AtlasState
import com.anjy.atlas.core.Engine
import com.anjy.atlas.core.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Atlas's main screen. How it looks and moves is Stoa's own UI code (type, glass surfaces, the floating tab bar
 * with its liquid drop, page turns, pop-ups), in Atlas's blues and violets. What's on the pages is Atlas:
 * talk to it, the Log of what it keeps for you, and Me.
 */
class MainActivity : Activity() {

    // Stoa's two looks share one layout in two materials, in Atlas's blue and violet. Night: blue-black, cool white ink,
    // colour only in glowing pieces. Day: frosted glass panels on a lavender-blue page, navy-black ink, dark glass buttons.
    // BLUE is the ink of actions (white pills at night, navy-black by day); ON is the text on it. ACCENT is Atlas's violet.
    internal var dark = true
    internal var BG = Color.parseColor("#05050F")
    internal var SURFACE = Color.parseColor("#0E0F22")
    internal var SURFACE2 = Color.parseColor("#17193A")
    internal var LINE = Color.parseColor("#262A52")
    internal var FG = Color.parseColor("#EEF0FF")
    internal var MUTED = Color.parseColor("#9BA1C9")
    /** Text on an ink or coloured fill. */
    internal var ON = Color.parseColor("#06061A")
    internal var DONE = Color.parseColor("#5BD6A6")
    internal var MISS = Color.parseColor("#FF6B81")
    internal var BLUE = Color.parseColor("#EEF0FF")      // ink: actions, links, selection
    internal var ACCENT = Color.parseColor("#8B6CFF")    // Atlas's violet: highlights, check-ins, warnings
    internal var GOLD = Color.parseColor("#6FA8FF")      // the second light: sky blue
    internal var STEEL = Color.parseColor("#17193A")     // neutral tag fill
    internal var NAVBG = Color.parseColor("#15162E")
    /** By day, the fill of the quieter buttons: dark glass, like the main ones. (At night they're clear glass.) */
    internal var BTN2 = Color.parseColor("#0E0F22")

    internal fun applyPalette() {
        val mode = Store.getString(this, "theme2") ?: Store.read(this) { it.settings.theme }.takeIf { it in setOf("dark", "light", "system") } ?: "dark"
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        dark = mode == "dark" || (mode == "system" && systemDark)
        // both looks are set in full every time: switching back from light to dark (without restarting the
        // screen) must put every dark colour back, not just the ones a fresh start happens to begin with
        if (!dark) {
            // frosted glass on a lavender-blue page. SURFACE is what a pane of frosted glass (white at about 45%) looks
            // like over it, so flat fills, sheets and blends match the glass panels; SURFACE2 is thinner glass, LINE its rim.
            BG = Color.parseColor("#C5C8EC"); SURFACE = Color.parseColor(LIGHT_GLASS); SURFACE2 = Color.parseColor("#D3D5F2")
            LINE = Color.parseColor("#F3F3FD"); FG = Color.parseColor("#0A0B1F"); MUTED = Color.parseColor("#3E4170")
            ON = Color.parseColor("#FFFFFF")
            DONE = Color.parseColor("#0A6646"); MISS = Color.parseColor("#A11B35")
            BLUE = Color.parseColor("#0A0B1F"); ACCENT = Color.parseColor("#5A3FD6"); GOLD = Color.parseColor("#2448B8")
            STEEL = Color.parseColor("#E6E7F8"); NAVBG = Color.parseColor("#0A0B1F"); BTN2 = Color.parseColor("#0A0B1F")
        } else {
            BG = Color.parseColor("#05050F"); SURFACE = Color.parseColor("#0E0F22"); SURFACE2 = Color.parseColor("#17193A")
            LINE = Color.parseColor("#262A52"); FG = Color.parseColor("#EEF0FF"); MUTED = Color.parseColor("#9BA1C9")
            ON = Color.parseColor("#06061A")
            DONE = Color.parseColor("#5BD6A6"); MISS = Color.parseColor("#FF6B81")
            BLUE = Color.parseColor("#EEF0FF"); ACCENT = Color.parseColor("#8B6CFF"); GOLD = Color.parseColor("#6FA8FF")
            STEEL = Color.parseColor("#17193A"); NAVBG = Color.parseColor("#15162E"); BTN2 = Color.parseColor("#0E0F22")
        }
    }

    /** Light mode's panels: frosted glass (white at about 45%) seen over the lavender-blue page. */
    internal val LIGHT_GLASS = "#DDDFF5"
    /** Light mode's text fields: clearer glass, a shade brighter than the panels they sit on. */
    internal val LIGHT_FIELD = "#E9EAF9"

    /** A quieter button's background: glass at night, black glass by day. [radius] < 0 means a pill. */
    internal fun quietBg(radius: Float = -1f): android.graphics.drawable.Drawable = if (dark) finished(SURFACE, radius, glass = true) else finished(BTN2, radius)
    /** Words and icons on a quieter button. */
    internal fun quietInk() = if (dark) FG else Color.WHITE
    internal fun quietMuted() = if (dark) MUTED else Color.argb(210, 255, 255, 255)

    internal val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    internal val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun font(id: Int, fallback: Typeface) = try { resources.getFont(id) } catch (e: Throwable) { fallback }
    // Unbounded, wide and geometric, for numbers and titles; Geist for everything you read.
    internal val THIN by lazy { font(R.font.unbounded_200, Typeface.create("sans-serif-light", Typeface.NORMAL)) }
    internal val NUM by lazy { font(R.font.unbounded_300, Typeface.create("sans-serif-light", Typeface.NORMAL)) }
    internal val DISPLAY by lazy { font(R.font.unbounded_500, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    internal val WORDMARK by lazy { font(R.font.unbounded_700, Typeface.create("sans-serif-black", Typeface.NORMAL)) }
    /** Titles. Under 18sp a wide display face gets heavy, so small "headings" are set in Geist SemiBold (see text()). */
    internal val HEAD get() = DISPLAY
    internal val SEMI by lazy { font(R.font.geist_semibold, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    internal val MED by lazy { font(R.font.geist_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
    internal val BODY by lazy { font(R.font.geist_regular, Typeface.DEFAULT) }
    internal val PIX get() = NUM

    internal lateinit var scroll: ScrollView
    internal lateinit var aurora: Backdrop
    internal lateinit var morph: MorphView
    internal lateinit var body: LinearLayout
    internal lateinit var pageHost: FrameLayout
    internal lateinit var snap: android.widget.ImageView
    internal lateinit var glow: GlowRingView
    internal lateinit var nav: LinearLayout
    internal lateinit var confetti: ConfettiView
    internal lateinit var banner: TextView
    internal var tab = 0

    // Today screen handles for the celebration
    internal var heroFlame: FlameView? = null
    internal var heroNum: TextView? = null
    // ---------------------------------------------------------------- helpers

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    internal fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    internal fun dialogTheme() = if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** Page background: one soft blue glow from the top and a faint warm hint, like the icon, kept low. */
    internal fun wash(): android.graphics.drawable.Drawable {
        val w = resources.displayMetrics.widthPixels.toFloat()
        fun glow(col: Int, a: Int, cx: Float, cy: Float, r: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(cx, cy); gradientRadius = r
            colors = intArrayOf(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)))
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(BG),
            glow(ACCENT, if (dark) 0x18 else 0x10, 1.0f, 0.0f, w * 0.9f)
        ))
    }
    internal fun washTop() = BG

    /** A soft panel: rounded, no outline in dark mode, a whisper of light across the top. [c] is the corner radius. */
    internal fun cut(color: Int, c: Int = 20, stroke: Int? = LINE, colors: IntArray? = null, all: Boolean = false, topLine: Int? = null): GradientDrawable =
        (if (colors != null) GradientDrawable(GradientDrawable.Orientation.TL_BR, colors) else GradientDrawable().apply { setColor(color) }).apply {
            cornerRadius = dp(c).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** Button face lit from above, sitting on a darker edge: it reads as something you can press. */
    internal fun face(color: Int, radius: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(blend(Color.WHITE, color, 0.12f), color)).apply { cornerRadius = dp(radius).toFloat() }

    internal fun metal(c: Int = 14, pressed: Boolean = false): android.graphics.drawable.Drawable =
        rounded(if (pressed) soft(BLUE, BG, 0.18f) else BLUE, 999)

    /** Neutral tag. */
    internal fun steel(c: Int = 8) = cut(STEEL, c, null)

    internal fun shade(c: Int, f: Float = 0.78f) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** Raised surface: a darker edge peeking out underneath, like a physical key. */
    internal fun slab(color: Int, radius: Int, edge: Int, depth: Int, border: Int? = null): android.graphics.drawable.Drawable {
        val plain = color == SURFACE || color == SURFACE2 || color == BG
        return if (plain) StoneDrawable(color, dp(radius + 4).toFloat(), dark, resources.displayMetrics.density, border?.takeIf { dark }, page = BG)
        else rounded(color, radius + 4, border, 1)
    }

    /** Pressable raised surface: the top sinks onto its edge while pressed. */
    internal fun pressable(color: Int, radius: Int, edge: Int, border: Int? = null, depth: Int = 4) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(blend(color, FG, 0.08f), radius + 4, border, 1))
        addState(intArrayOf(), slab(color, radius, edge, depth, border))
    }

    private val symbolWords = mapOf("✕" to "Close", "🗑" to "Delete", "›" to "Next", "‹" to "Back", "✏️" to "Edit", "💡" to "How to do it", "▶" to "Play",
        "+" to "Add", "＋" to "Add", "✓" to "Selected", "⇄" to "Swap", "🌙" to "Low Day", "📏" to "Measure", "🔒" to "Locked")

    internal fun text(s: String, size: Float = 15f, color: Int = FG, face: Typeface? = null, keepEmoji: Boolean = false) = LText(this).apply {
        val asked = face ?: BODY
        // the wide display face is for titles and numbers; small "headings" read better in Geist SemiBold
        val f = when {
            asked === HEAD && size < 18f -> SEMI
            asked === NUM && size >= 44f -> THIN
            else -> asked
        }
        // The app speaks in words: emoji ("🎯 Quests", "Lock In 🔥") are dropped, now and whenever the words change. What
        // people type themselves (a question, a chat message, a food's name) is shown as typed ([keepEmoji]).
        plain = !keepEmoji
        text = L.t(s)
        textSize = size; setTextColor(color); typeface = f
        if (f === HEAD || f === NUM || f === THIN) letterSpacing = if (size >= 30f) -0.04f else -0.02f
        // a lone symbol means nothing to a screen reader: give it its word (a caller can still set its own)
        symbolWords[s.trim()]?.let { contentDescription = L.t(it) }
        setLineSpacing(0f, if (size <= 16f) 1.3f else 1.1f)
        // one-word titles and big numbers never break mid-word: they shrink to fit a narrow phone or large text
        if (!s.trim().contains(' ') && ((f === HEAD && size >= 22f) || ((f === NUM || f === THIN || f === DISPLAY) && size >= 26f))) fitLine(minOf(16f, size))
    }

    internal fun eyebrow(s: String) = text(noEmoji(L.t(s)), 13f, MUTED, SEMI).apply { letterSpacing = 0.02f; plain = true }

    /** A chart's name on the left and its latest value on the right. */
    internal fun chartHead(title: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(eyebrow(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(text(value, 15f, FG, NUM))
    }

    /** "🎯 Quests" → "Quests". Only strips a leading emoji followed by words; a lone emoji stays. */
    internal fun plainTitle(s: String): String {
        val e = firstEmoji(s) ?: return s
        val rest = s.trim().removePrefix(e).trimStart('\uFE0F', ' ')
        return if (rest.isNotEmpty() && rest.first().isLetterOrDigit()) rest else s
    }

    /** A glass panel: you see the light behind it, it catches light on top, and it leans under your finger. */
    /** A surface: a hairline panel on black at night, frosted glass by day. Calm: it doesn't lean or glow. */
    internal fun card(color: Int = SURFACE): LinearLayout = TiltLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tiltOn = false
        // by day the panel is see-through glass: no drop shadow, which would show through it as a smudge; its bright
        // rim and the light across its top are what lift it off the page
        background = StoneDrawable(color, dp(26).toFloat(), dark, resources.displayMetrics.density, page = BG)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    /** A tile lit in its own colour (steps, food, league, heart): a glowing panel at night, a tint of it by day. */
    internal fun tile(kind: Glow): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GlowDrawable(kind, dark, dp(28).toFloat(), resources.displayMetrics.density)
        if (!dark) { elevation = dp(5).toFloat(); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; if (Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = Color.argb(50, 0, 0, 0); outlineSpotShadowColor = Color.argb(80, Color.red(kind.orb), Color.green(kind.orb), Color.blue(kind.orb)) } }
        clipToOutline = true
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }
    /** Text on a tile: white over its glow at night; by day a deep shade of the tile's own colour over its lighter tint. */
    internal fun tileInk(kind: Glow) = if (dark) Color.WHITE else GlowDrawable.dayInk(kind)
    internal fun tileSub(kind: Glow) = if (dark) Color.argb(215, 255, 255, 255) else (tileInk(kind) and 0x00FFFFFF) or (205 shl 24)
    /** The empty part of a bar or ruler on a tile. */
    internal fun tileTrack(kind: Glow) = if (dark) Color.argb(56, 255, 255, 255) else (tileInk(kind) and 0x00FFFFFF) or (44 shl 24)

    /** The streak card's stage: glass, rising embers and a travelling rim of light. */
    /** By day the stage sits a shade deeper than the page. */
    internal val STREAK_BROWN = Color.parseColor("#B9BCE4")
    internal fun stage(): StageLayout = StageLayout(this, if (dark) SURFACE else STREAK_BROWN, dark, BLUE, ACCENT).apply {
        orientation = LinearLayout.VERTICAL
        still = Prefs.reduceMotion(this@MainActivity); tiltOn = !still
        if (!dark) elevation = dp(8).toFloat()
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(26).toFloat()) }
        }
        setPadding(dp(20), dp(22), dp(20), dp(22))
    }

    /** One entrance per page: the first sections fade in and rise a little, one after another. */
    internal fun enterPage() {
        if (Prefs.reduceMotion(this)) return
        var i = 0
        for (k in 0 until body.childCount) {
            val v = body.getChildAt(k)
            if (i > 5) break
            v.alpha = 0f; v.translationY = dp(12).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            i++
        }
    }

    internal fun LinearLayout.add(v: View, top: Int = 0, width: Int = MATCH, height: Int = WRAP) {
        // Top-level sections get extra air so the page reads in clear blocks, not a wall.
        val gap = if (this === body && top >= 12) (top * 1.6f).roundToInt() else top
        addView(v, LinearLayout.LayoutParams(width, height).apply { topMargin = dp(gap) })
    }

    /** The main action: a raised blue key that presses down. */
    internal fun sunrise(radius: Int): android.graphics.drawable.Drawable = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), metal(radius, pressed = true)); addState(intArrayOf(), metal(radius))
    }

    /** The main action: a pill in ink (or a status colour), text in the opposite tone. */
    internal fun primary(label: String, colorAsked: Int = BLUE, onClick: () -> Unit) = LText(this).apply {
        // green buttons don't fit the look: a "done"-coloured button is drawn in ink like every other main action
        val color = if (colorAsked == DONE) BLUE else colorAsked
        plain = true
        text = L.t(label); textSize = 16f; typeface = MED; gravity = Gravity.CENTER
        val plain = color == SURFACE2 || color == SURFACE || color == STEEL
        setTextColor(when { plain -> quietInk(); color == BLUE -> ON; dark && (color == DONE || color == GOLD) -> Color.parseColor("#050505"); else -> Color.WHITE })
        background = if (plain) quietBg() else finished(color)
        minHeight = dp(56)
        setPadding(dp(20), dp(15), dp(20), dp(15))
        pressFx()
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }

    /** A quieter action: a glass pill at night, black glass by day. */
    internal fun secondary(label: String, onClick: () -> Unit) = LText(this).apply {
        plain = true
        text = L.t(label); textSize = 15f; typeface = MED; gravity = Gravity.CENTER
        setTextColor(quietInk())
        background = quietBg()
        minHeight = dp(50)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        pressFx()
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }

    internal fun field(hint: String, value: String, numeric: Boolean = false, multi: Boolean = false, decimal: Boolean = false, onChange: (String) -> Unit) =
        EditText(this).apply {
            this.hint = L.t(hint); setText(value)
            setTextColor(FG); setHintTextColor(MUTED); textSize = 16f; typeface = BODY
            background = rounded(if (dark) SURFACE2 else Color.parseColor(LIGHT_FIELD), 16, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = when {
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                numeric -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            })
        }

    internal fun chip(label: String, selected: Boolean, color: Int = BLUE) = LText(this).apply {
        plain = true
        text = L.t(label); textSize = 14f; typeface = MED; gravity = Gravity.CENTER
        setTextColor(if (selected) (if (color == BLUE) ON else Color.WHITE) else FG)
        background = if (selected) Finish(color, -1f, dark, resources.displayMetrics.density) else Finish(SURFACE, -1f, dark, resources.displayMetrics.density, glass = true)
        setPadding(dp(15), dp(10), dp(15), dp(10))
    }

    /** Chips that wrap onto new lines by their real width, so labels never squash into a column. */
    internal fun chipGroup(options: List<Pair<String, String>>, isOn: (String) -> Boolean, onTap: (String) -> Unit): FlowLayout {
        val box = FlowLayout(this, dp(8), dp(10))
        fun build() {
            box.removeAllViews()
            options.forEach { (key, label) ->
                val c = chip(label, isOn(key))
                c.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap(key); build() }
                box.addView(c)
            }
        }
        build()
        return box
    }

    internal fun haptic() {
        if (!Prefs.haptics(this)) return
        val v = window.decorView
        if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A short note at the bottom with an Undo, gone after five seconds. */
    internal fun undoBanner(msg: String, undo: () -> Unit) {
        val root = confetti.parent as ViewGroup
        root.findViewWithTag<View>("undoBar")?.let { root.removeView(it) }
        val bar = LinearLayout(this).apply {
            tag = "undoBar"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Finish(if (dark) Color.parseColor("#22244A") else Color.parseColor("#0A0B1F"), -1f, dark, resources.displayMetrics.density)
            setPadding(dp(20), dp(10), dp(8), dp(10)); alpha = 0f; translationY = dp(14).toFloat()
        }
        bar.addView(text(noEmoji(L.t(msg)), 14f, Color.WHITE, MED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gone = Runnable { bar.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(bar) }.start() }
        bar.addView(text("Undo", 14f, Color.parseColor("#B9A6FF"), SEMI).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { bar.removeCallbacks(gone); root.removeView(bar); undo() }
        })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(92) })
        bar.animate().alpha(1f).translationY(0f).setDuration(140).start()
        bar.postDelayed(gone, 5000)
    }

    internal fun showBanner(msg: String, asked: Int = BLUE) {
        // always above whatever is open (a pop-up, the workout), so a message is never hidden behind it
        banner.bringToFront(); banner.translationZ = dp(60).toFloat()
        // good news is said in ink, not green; the message itself is plain words, no picture in front
        val color = if (asked == DONE) BLUE else asked
        banner.text = noEmoji(L.t(msg))
        // the same glass finish as the buttons, so a message looks like part of the app
        banner.background = Finish(color, -1f, dark, resources.displayMetrics.density)
        banner.setTextColor(if (color == BLUE) ON else if (dark && (color == DONE || color == GOLD || color == MUTED)) Color.parseColor("#050505") else Color.WHITE)
        banner.animate().cancel()
        // quick in, a moment to read, quick out
        banner.alpha = 0f; banner.translationY = -dp(12).toFloat()
        banner.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(140).withEndAction {
            banner.animate().alpha(0f).setStartDelay(2400).setDuration(220).start()
        }.start()
    }

    /** A short message in the app's own pill (not the phone's grey toast). */
    internal fun toast(s: String) = showBanner(s, BLUE)
    /** Tabs in the order they appear (and swipe): Atlas, Log, Me. */
    internal val order: List<Int> get() = listOf(0, 1, 2)
    internal fun pos(id: Int) = order.indexOf(id)

    /** The nav: a floating pill of five icons; the current page sits in a light circle. */
    internal var navKey = ""
    internal var navPill: LinearLayout? = null
    internal var navGo: View? = null
    internal var navBlob: LiquidBlob? = null
    internal var navShown = -1
    internal val navItems = mutableListOf<NavCell>()
    internal fun renderNav() = renderNavBar()

    /**
     * Page changes, tapped or swiped, turn the page over like a card: the page you're leaving turns away on its
     * upright middle line until it's edge-on, and the new page turns in from the other side (460 ms). A swipe turns
     * the page a little with your finger first; let go past a quarter of the way, or flick, and it turns over;
     * otherwise it settles back. With Reduce Motion on, the pages simply cross-fade.
     */
    internal var switching = false
    private var swipeFrom = -1
    private var slideAnim: (() -> Unit)? = null

    /**
     * Our own frame clock for page transitions. Android's animators obey the phone's "animation scale"
     * (battery saver and some phones turn it off, which made changes jump straight to the end);
     * this runs on real time, one step per screen frame. Returns a cancel function.
     */
    internal fun tween(from: Float, to: Float, dur: Long, interp: android.animation.TimeInterpolator, update: (Float) -> Unit, end: () -> Unit): () -> Unit {
        // the clock starts once the first frame has been drawn, not when asked: drawing a long page for the first
        // time can take a moment, and the change should still play in full rather than jump ahead
        var t0 = -1L
        var first = true
        var cancelled = false
        val step = object : Runnable {
            override fun run() {
                if (cancelled) return
                if (first) { first = false; update(from + (to - from) * interp.getInterpolation(0f)); pageHost.postOnAnimation(this); return }
                if (t0 < 0) t0 = android.os.SystemClock.uptimeMillis()
                val k = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / dur.coerceAtLeast(1)).coerceIn(0f, 1f)
                update(from + (to - from) * interp.getInterpolation(k))
                if (k < 1f) pageHost.postOnAnimation(this) else end()
            }
        }
        pageHost.postOnAnimation(step)
        return { cancelled = true }
    }

    /** The visible part of the page as a picture (the background behind it is shared, so it isn't included). */
    private fun pageBitmap(): android.graphics.Bitmap? {
        val w = pageHost.width; val h = pageHost.height
        if (w == 0 || h == 0) return null
        val bmp = try { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null } ?: return null
        val cv = android.graphics.Canvas(bmp)
        cv.translate(0f, -scroll.scrollY.toFloat())
        body.draw(cv)
        return bmp
    }

    /** A far-off camera, so a page turning in 3D keeps a gentle perspective. */
    private fun cameraFor(v: View) { v.cameraDistance = 9000f * resources.displayMetrics.density }

    internal fun dragPage(dx: Float, y: Float = 0f) {
        if (switching) return
        val w = pageHost.width.toFloat()
        if (swipeFrom == -1) {
            if (!pageHost.isAttachedToWindow || w == 0f) return
            swipeFrom = tab
            cameraFor(scroll)
        }
        val dir = if (dx < 0) 1 else -1
        val edge = pos(swipeFrom) + dir !in order.indices
        // the page turns a little with the finger; at the first and last tab it only gives a bit, like a rubber band
        if (Prefs.reduceMotion(this)) { body.translationX = dx * (if (edge) 0.12f else 0.2f); return }
        scroll.rotationY = if (edge) (dx / w * 18f).coerceIn(-8f, 8f) else (dx / w * 70f).coerceIn(-40f, 40f)
    }

    internal fun releasePage(dx: Float, vx: Float, x: Float, y: Float) {
        if (switching || swipeFrom == -1) return
        val w = pageHost.width.toFloat().coerceAtLeast(1f)
        val dir = if (dx < 0) 1 else -1
        val target = pos(swipeFrom) + dir
        val flung = kotlin.math.abs(vx) > 700 * resources.displayMetrics.density && (vx < 0) == (dx < 0)
        swipeFrom = -1
        if (target in order.indices && (kotlin.math.abs(dx) > w * 0.25f || flung)) {
            val to = order[target]
            haptic()
            slideTo(to, dir)
        } else settleBack()
    }

    private fun settleBack() {
        val fromR = scroll.rotationY; val fromX = body.translationX
        if (fromR == 0f && fromX == 0f) return
        switching = true
        slideAnim = tween(0f, 1f, 240, android.view.animation.DecelerateInterpolator(1.6f), { p ->
            scroll.rotationY = fromR * (1 - p); body.translationX = fromX * (1 - p)
        }) { scroll.rotationY = 0f; body.translationX = 0f; switching = false; slideAnim = null }
    }

    /** Nav taps slide to the tapped tab. */
    internal fun switchTab(to: Int, dir: Int) {
        if (to !in order || to == tab || switching || swipeFrom != -1) return
        slideTo(to, if (dir == 0) (if (pos(to) > pos(tab)) 1 else -1) else dir)
    }

    internal fun morphTo(to: Int, dir: Int, @Suppress("UNUSED_PARAMETER") ox: Float, @Suppress("UNUSED_PARAMETER") oy: Float) = slideTo(to, dir)

    internal fun slideTo(to: Int, dir: Int) {
        slideAnim?.invoke(); slideAnim = null
        // forward (to the right in the nav) turns the page to the left: its right edge comes towards you
        val g = if (dir < 0) -1f else 1f
        val startR = scroll.rotationY
        val old = if (pageHost.isAttachedToWindow && pageHost.width > 0) pageBitmap() else null
        scroll.rotationY = 0f; scroll.scaleX = 1f; scroll.scaleY = 1f; scroll.alpha = 1f
        body.translationX = 0f; body.alpha = 1f
        switching = old != null
        tab = to; render(); scroll.scrollTo(0, 0)
        if (old == null) { switching = false; return }
        val reduce = Prefs.reduceMotion(this)
        snap.setImageBitmap(old); snap.visibility = View.VISIBLE
        snap.translationX = 0f; snap.alpha = 1f; snap.scaleX = 1f; snap.scaleY = 1f; snap.rotationY = startR
        snap.bringToFront()
        fun done() {
            snap.visibility = View.GONE; snap.setImageBitmap(null); snap.clearColorFilter(); snap.rotationY = 0f; snap.scaleX = 1f; snap.scaleY = 1f; snap.alpha = 1f
            old.recycle()
            scroll.rotationY = 0f; scroll.scaleX = 1f; scroll.scaleY = 1f; scroll.alpha = 1f
            scroll.setLayerType(View.LAYER_TYPE_NONE, null)
            switching = false; slideAnim = null
        }
        if (reduce) {
            scroll.alpha = 0f
            slideAnim = tween(0f, 1f, 160, android.view.animation.LinearInterpolator(), { p -> snap.alpha = 1f - p; scroll.alpha = p }) { done() }
            return
        }
        cameraFor(snap); cameraFor(scroll)
        // the page darkens a little as it turns edge-on, as a card does when it turns away from the light
        val shade = android.graphics.Paint()
        fun dim(deg: Float): Int = (kotlin.math.sin(Math.toRadians(kotlin.math.abs(deg).toDouble())) * 120).toInt().coerceIn(0, 255)
        scroll.alpha = 0f
        scroll.setLayerType(View.LAYER_TYPE_HARDWARE, shade)
        slideAnim = tween(0f, 1f, 380, android.view.animation.LinearInterpolator(), { p ->
            val depth = 1f - 0.07f * kotlin.math.sin(Math.PI * p).toFloat()
            if (p < 0.5f) {
                val k = p / 0.5f
                val r = startR + (-90f * g - startR) * k * k
                snap.rotationY = r; snap.scaleX = depth; snap.scaleY = depth
                snap.setColorFilter(android.graphics.Color.argb(dim(r), 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
            } else {
                if (snap.visibility == View.VISIBLE) { snap.visibility = View.INVISIBLE; scroll.alpha = 1f }
                val k = (p - 0.5f) / 0.5f
                val r = 90f * g * (1f - k) * (1f - k) * (1f - k)
                scroll.rotationY = r; scroll.scaleX = depth; scroll.scaleY = depth
                val a = dim(r)
                shade.colorFilter = if (a == 0) null else android.graphics.PorterDuffColorFilter(android.graphics.Color.argb(a, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
                scroll.setLayerPaint(shade)
            }
        }) { done() }
    }

    // ---------------------------------------------------------------- sheets (replace stock dialogs)

    /** Where the last tap landed (screen coordinates): a pop-up opens out of the button that was tapped. */
    internal var lastTapX = -1f
    internal var lastTapY = -1f
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_UP) { lastTapX = ev.rawX; lastTapY = ev.rawY }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * A pop-up in the app's own style: a floating card over a dimmed page. It grows out of the button that opened it
     * and settles with a little spring; closing, it shrinks back and fades. Returns a function that closes it.
     */
    internal fun sheet(icon: String?, title: String, message: String?, content: View? = null,
                      primaryLabel: String? = "OK", primaryColor: Int = BLUE, onPrimary: () -> Unit = {},
                      secondaryLabel: String? = null, onSecondary: () -> Unit = {}, locked: Boolean = false): () -> Unit {
        val root = confetti.parent as ViewGroup
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(if (dark) 170 else 110, 0, 0, 0)); isClickable = true; alpha = 0f }
        val d = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SheetDrawable(SURFACE, dp(30).toFloat(), dark, d)
            elevation = dp(24).toFloat(); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setPadding(dp(22), dp(10), dp(22), dp(20)); isClickable = true
        }
        var closed = false
        var closeRef: () -> Unit = {}
        val close: () -> Unit = {
            if (!closed) {
                closed = true
                backStack.remove(closeRef)
                scrim.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(scrim) }.start()
                panel.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).translationY(dp(12).toFloat()).setDuration(140)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
            }
        }
        // a locked sheet can't be waved away: tapping outside does nothing and Back keeps it up
        var hold: () -> Unit = {}
        hold = { backStack.add(hold) }
        closeRef = if (locked) hold else close; backStack.add(closeRef)
        if (!locked) scrim.setOnClickListener { close() }
        panel.addView(View(this).apply { background = rounded(if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0), 999) },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // a pop-up is laid out like a page: its title, its words and its buttons (the icon a caller passes isn't drawn)
        head.addView(text(title, 20f, FG, HEAD), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!locked) head.addView(FrameLayout(this).apply {
            background = quietBg(); contentDescription = L.t("Close"); isClickable = true
            addView(LineIcon(this@MainActivity, "close").apply { color = quietMuted() }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(10) })
        panel.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        if (message != null) panel.addView(text(noEmoji(L.t(message)), 15f, MUTED), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        if (content != null) panel.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        if (primaryLabel != null) panel.addView(primary(primaryLabel, primaryColor) { keepSheet = false; onPrimary(); if (!keepSheet) close() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
        if (secondaryLabel != null) panel.addView(text(secondaryLabel, 15f, MUTED, MED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(2)); setOnClickListener { close(); onSecondary() }
        })
        // a tall card (or one pushed up by the keyboard) scrolls instead of running off the top of the screen
        val holder = QuickScroll(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false }
        holder.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP).apply { val g = dp(10); setMargins(g, dp(12), g, g) })
        scrim.addView(holder, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { topMargin = dp(12) })
        root.addView(scrim, root.indexOfChild(confetti), FrameLayout.LayoutParams(MATCH, MATCH))
        scrim.animate().alpha(1f).setDuration(140).start()
        panel.alpha = 0f
        panel.post {
            // grow out of the tapped point (or the bottom middle when there wasn't one)
            val loc = IntArray(2); panel.getLocationOnScreen(loc)
            panel.pivotX = if (lastTapX >= 0) (lastTapX - loc[0]).coerceIn(0f, panel.width.toFloat()) else panel.width / 2f
            panel.pivotY = if (lastTapY >= 0) (lastTapY - loc[1]).coerceIn(0f, panel.height.toFloat()) else panel.height.toFloat()
            panel.scaleX = 0.9f; panel.scaleY = 0.9f; panel.translationY = dp(16).toFloat()
            if (Prefs.reduceMotion(this)) { panel.scaleX = 1f; panel.scaleY = 1f; panel.translationY = 0f; panel.alpha = 1f }
            else panel.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        }
        return close
    }

    /** The first whole emoji in [s] (keeps multi-part emoji like ❤️‍🩹 or skin tones intact), or null if it's letters/digits. */
    internal fun firstEmoji(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next()
        val g = t.substring(0, if (end == java.text.BreakIterator.DONE) t.length else end)
        val cp = g.codePointAt(0)
        if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) return null
        val type = Character.getType(cp)
        val symbol = type == Character.OTHER_SYMBOL.toInt() || type == Character.MATH_SYMBOL.toInt() || cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2190..0x21FF
        return if (symbol) g else null
    }

    internal fun doneEmoji() = "🔥"
    /**
     * A new look without restarting the screen: the colours are swapped, everything that holds a colour of its own
     * (background, bars, nav, banner, page-turn layer) is rebuilt, and the page is drawn again where it was.
     */
    internal fun relook() {
        applyPalette()
        window.statusBarColor = washTop(); window.navigationBarColor = BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        }
        val root = confetti.parent as ViewGroup
        root.setBackgroundColor(BG)
        val i = root.indexOfChild(aurora)
        root.removeView(aurora)
        aurora = Backdrop(this, BG, dark, ACCENT).apply { still = Prefs.reduceMotion(this@MainActivity); scrollRef = scroll }
        root.addView(aurora, i.coerceAtLeast(0), FrameLayout.LayoutParams(MATCH, MATCH))
        val mi = pageHost.indexOfChild(morph)
        pageHost.removeView(morph)
        morph = MorphView(this, BG).apply { visibility = View.GONE; ring = BLUE; warm = ACCENT }
        pageHost.addView(morph, mi.coerceAtLeast(0), FrameLayout.LayoutParams(MATCH, MATCH))
        banner.setTextColor(ON); banner.background = rounded(BLUE, 999)
        navKey = ""
        restyleComposer()
        render(keepScroll = true)
    }
    /** A colour washed onto a surface: in the light look it is laid on more strongly, so it doesn't fade to pastel. */
    internal fun soft(a: Int, b: Int, t: Float): Int = blend(a, b, if (dark) t else (t * 2.2f).coerceAtMost(1f))

    /** Coloured words on a [soft] wash of the same colour: a shade deeper by day, so they still stand out on it. */
    internal fun onWash(tone: Int): Int = if (dark) tone else shade(tone, 0.74f)

    internal fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
        (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
        (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt()
    )

    // ================================================================ Atlas
    // Tabs: 0 Atlas (talk to it), 1 Log (what it keeps for you), 2 Me (same order as the bar).

    internal val backStack = mutableListOf<() -> Unit>()
    /** Set inside a sheet's primary action to keep the sheet open (e.g. input didn't validate). */
    internal var keepSheet = false
    internal val liveAnims = mutableListOf<android.animation.Animator>()
    internal val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var imeOpen = false
    private lateinit var composer: LinearLayout
    private lateinit var composerField: EditText
    private var sendIcon: LineIcon? = null
    private lateinit var fade: View
    private var lastLogSig = ""
    private var returnedFromSystem = false
    private var modelProgressView: TextView? = null
    /** Atlas tab handles, updated in place as the conversation moves. */
    private var chatList: LinearLayout? = null
    private var homeOrb: OrbView? = null
    private var statusLine: TextView? = null
    private var shownCount = -1
    private var voiceInput: VoiceInput? = null

    companion object {
        private const val REQ_MODEL = 11; private const val REQ_IMPORT = 13; private const val REQ_NOTIF = 14; private const val REQ_MIC = 15; private const val REQ_MIC_WAKE = 16
        const val AI_STUDIO_KEYS = "https://aistudio.google.com/apikey"
    }

    private val density get() = resources.displayMetrics.density
    private fun tabFor(name: String?) = when (name) {
        "atlas", "chat", "today", "home" -> 0; "log", "memory" -> 1; "settings", "me" -> 2; else -> null
    }

    /** Back closes the topmost overlay first, then returns to Atlas, then leaves the app. */
    internal fun handleBack() {
        when {
            backStack.isNotEmpty() -> backStack.removeAt(backStack.size - 1)()
            tab != 0 -> switchTab(0, -1)
            else -> finish()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private val onChat: () -> Unit = { chatChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.zone = ZoneId.systemDefault()
        Reminders.channels(this)
        Conversation.load(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        applyPalette()
        L.init(this)
        tab = savedInstanceState?.getInt("tab") ?: tabFor(intent?.getStringExtra("tab")) ?: 0
        window.statusBarColor = washTop()
        window.navigationBarColor = BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))

        // Anything laid over the page after the nav (sheets, banners) must sit above the nav. (Stoa)
        val root = object : FrameLayout(this) {
            override fun onViewAdded(child: View) {
                super.onViewAdded(child)
                if (::nav.isInitialized && child !== nav && indexOfChild(child) > indexOfChild(nav) && indexOfChild(nav) >= 0)
                    child.translationZ = maxOf(child.translationZ, nav.elevation + dp(2))
            }
        }.apply { setBackgroundColor(BG) }
        aurora = Backdrop(this, BG, dark, ACCENT).apply { still = Prefs.reduceMotion(this@MainActivity) }
        root.addView(aurora, FrameLayout.LayoutParams(MATCH, MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = SwipeScrollView(this, object : SwipeScrollView.Listener {
            override fun onDrag(dx: Float, y: Float) = dragPage(dx, y)
            override fun onRelease(dx: Float, velocityX: Float, x: Float, y: Float) = releasePage(dx, velocityX, x, y)
        }).apply { isVerticalScrollBarEnabled = false; background = null }
        aurora.scrollRef = scroll
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (aurora.still) aurora.invalidate() }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val g = gutterDp()
            setPadding(dp(g), dp(24), dp(g), dp(112))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(14).toFloat()
        }
        pageHost = FrameLayout(this)
        snap = android.widget.ImageView(this).apply { visibility = View.GONE; scaleType = android.widget.ImageView.ScaleType.FIT_XY }
        glow = GlowRingView(this).apply { visibility = View.GONE; isClickable = false }
        pageHost.addView(snap, FrameLayout.LayoutParams(MATCH, MATCH))
        pageHost.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        pageHost.addView(glow, FrameLayout.LayoutParams(MATCH, MATCH))
        morph = MorphView(this, BG).apply { visibility = View.GONE; ring = BLUE; warm = ACCENT }
        pageHost.addView(morph, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(pageHost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        // the page fades out under the ask bar and the tab bar, so words never run into them
        fade = View(this).apply { isClickable = false; background = fadeDrawable() }
        root.addView(fade, FrameLayout.LayoutParams(MATCH, dp(190), Gravity.BOTTOM))
        // Atlas: the ask bar floats over the page above the tab bar, on the Atlas tab
        composer = buildComposer()
        root.addView(composer, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { val g = dp(gutterDp()); leftMargin = g; rightMargin = g; bottomMargin = dp(86) })
        root.addView(nav, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })

        confetti = ConfettiView(this).apply { isClickable = false }
        root.addView(confetti, FrameLayout.LayoutParams(MATCH, MATCH))
        banner = TextView(this).apply {
            setTextColor(ON); textSize = 15f; typeface = MED; gravity = Gravity.CENTER
            setPadding(dp(22), dp(13), dp(22), dp(13)); alpha = 0f
            background = rounded(BLUE, 999)
        }
        root.addView(banner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(60) })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 29) { root.isForceDarkAllowed = false; window.decorView.isForceDarkAllowed = false }
        // Edge-to-edge: draw behind the system bars, then pad the content by their size, and by the keyboard's when it's open.
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val open = ins.isVisible(android.view.WindowInsets.Type.ime())
            if (open != imeOpen) { imeOpen = open; placeComposer() }
            android.view.WindowInsets.CONSUMED
        }
        // the bar icons' colour can only be set once the window has its content
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        AvatarService.startIfEnabled(this)
        autoLoadModel()
        render()
    }

    private fun fadeDrawable() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(0, Color.red(BG), Color.green(BG), Color.blue(BG)), Color.argb(235, Color.red(BG), Color.green(BG), Color.blue(BG)), BG))

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", tab); super.onSaveInstanceState(outState) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFor(intent.getStringExtra("tab"))?.let { if (it != tab) { tab = it; render() } }
    }

    override fun onResume() {
        super.onResume()
        AvatarService.setAppInFront(this, true)
        Conversation.listen(onChat)
        if (!Prefs.reduceMotion(this)) Tilt.start(this)
        if (::aurora.isInitialized) aurora.resume()
        if (returnedFromSystem && tab != 0) render(keepScroll = true)
        else if (tab == 0) chatChanged()
        returnedFromSystem = false
        ticker.removeCallbacks(tick); ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick); returnedFromSystem = true
        Conversation.unlisten(onChat)
        stopVoice()
        AvatarService.setAppInFront(this, false)
        Tilt.stop(); if (::aurora.isInitialized) aurora.pause()
        super.onPause()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Every 30 seconds: move the engine on, and redraw the Log if something on it changed. */
    private val tick = object : Runnable {
        override fun run() {
            scope.launch {
                withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                val sig = logSignature()
                if (sig != lastLogSig && currentFocus !is EditText && !switching) {
                    lastLogSig = sig
                    if (tab == 1 || (tab == 0 && Store.read(this@MainActivity) { s -> s.tasks.any { it.status == "awaiting" } })) render(keepScroll = true)
                }
            }
            ticker.postDelayed(this, 30_000)
        }
    }

    private fun logSignature(): String = Store.read(this) { s ->
        LocalDate.now(Engine.zone).toString() + s.tasks.joinToString("|") { "${it.id}:${it.status}:${it.version}" } + "#" + s.memories.size
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (ok) voice() else showBanner("Atlas needs the microphone to hear you.", MUTED)
            REQ_MIC_WAKE -> {
                if (ok) enableWake() else showBanner("“Hey Atlas” needs the microphone.", MUTED)
                if (tab == 2) render(keepScroll = true)
            }
            else -> if (tab == 2) render(keepScroll = true)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // "System" follows the phone's dark mode: swap the look in place (Stoa)
        val wasDark = dark
        applyPalette()
        if (dark != wasDark) relook()
    }

    /** With a Gemini key, the offline model is loaded only when it's needed (it takes a lot of memory). */
    private fun autoLoadModel() {
        if (Conversation.online(this) || ModelManager.ready || ModelManager.modelName(this) == null) return
        if (ModelManager.lastLoadCrashed(this)) { showBanner("The last model load didn't finish. Load it from Me.", MUTED); return }
        scope.launch { ModelManager.load(applicationContext); if (tab == 2) render(keepScroll = true) }
    }

    internal fun render(keepScroll: Boolean = false) {
        liveAnims.forEach { it.cancel() }; liveAnims.clear()
        val y = scroll.scrollY
        renderNav()
        body.removeAllViews()
        body.clipToPadding = true
        heroFlame = null; heroNum = null
        chatList = null; homeOrb = null; statusLine = null; shownCount = -1
        window.statusBarColor = washTop()
        placeComposer()
        body.setPadding(body.paddingLeft, dp(24), body.paddingRight, dp(if (tab == 0) 200 else 120))
        when (tab) {
            0 -> renderAtlas()
            1 -> renderLog()
            else -> renderMe()
        }
        lastLogSig = logSignature()
        if (!keepScroll && !switching) enterPage()
        if (!Prefs.haptics(this)) { fun off(v: View) { v.isHapticFeedbackEnabled = false; if (v is ViewGroup) for (i in 0 until v.childCount) off(v.getChildAt(i)) }; off(body); off(nav) }
        scroll.post { if (tab == 0 && !keepScroll && Conversation.messages.isNotEmpty()) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0, if (keepScroll) y else 0) }
    }

    /** The round button beside the bar: talk to Atlas. */
    internal fun talk() {
        if (tab != 0) switchTab(0, -1)
        voice()
    }

    // ---------------------------------------------------------------- shared pieces

    private fun pageTitle(title: String, extra: View? = null) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 26f, FG, HEAD), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (extra != null) top.addView(extra)
        body.add(top)
    }

    private fun hairline() = View(this).apply { setBackgroundColor(if (dark) Color.argb(22, 255, 255, 255) else Color.argb(30, 0, 0, 0)) }

    /** A row on a settings card: the label on the left and its value in ink on the right (Stoa's setting row). */
    private fun settingRow(label: String, value: String, onTap: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = pressable(SURFACE2, 12, LINE); setPadding(dp(14), dp(12), dp(14), dp(15))
        addView(text(label, 15f, FG, MED), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(text(value, 14f, BLUE, HEAD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = (resources.displayMetrics.widthPixels * 0.42).toInt() })
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
    }

    /** A small round icon button (Stoa's quiet glass). */
    private fun roundIcon(kind: String, desc: String, onTap: () -> Unit) = FrameLayout(this).apply {
        background = quietBg(); isClickable = true; contentDescription = desc
        addView(LineIcon(this@MainActivity, kind).apply { color = quietInk() }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { onTap() }
        pressFx()
    }

    private fun dayLabel(ms: Long) = Brain.dayLabel(ms).replaceFirstChar { it.uppercase() }
    private fun whenLabel(ms: Long?) = ms?.let { "${dayLabel(it)} ${Reminders.fmt(it)}" } ?: "No time yet"

    /** Pick a day from the next week, then a time (Stoa's time sheet). */
    private fun dateTimeSheet(title: String, initial: Long, onPick: (Long) -> Unit) {
        val start = Instant.ofEpochMilli(initial).atZone(Engine.zone)
        val today = LocalDate.now(Engine.zone)
        var day = start.toLocalDate().takeIf { !it.isBefore(today) } ?: today
        val days = (0..13).map { today.plusDays(it.toLong()) }
        val opts = days.map { d ->
            d.toString() to when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) }
        }
        val chips = chipGroup(opts, { it == day.toString() }) { day = LocalDate.parse(it) }
        sheet(null, title, null, content = chips, primaryLabel = "Choose Time", onPrimary = {
            body.post {
                timeSheet(title, start.hour, start.minute) { h, m ->
                    onPick(day.atTime(h, m).atZone(Engine.zone).toInstant().toEpochMilli())
                }
            }
        }, secondaryLabel = "Cancel")
    }

    private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var close: () -> Unit = {}
        options.forEachIndexed { i, o -> col.add(secondary(o) { close(); onPick(i) }, if (i == 0) 0 else 8) }
        close = sheet(null, title, null, content = col, primaryLabel = null, secondaryLabel = "Cancel")
    }

    private fun celebrate() {
        AvatarService.celebrate(this)
        haptic()
    }

    /** A copy of the lists to draw from, so a reminder firing meanwhile can't change them mid-draw. */
    private fun snapshot(): AtlasState = Store.read(this) { s ->
        AtlasState(s.deviceId).also { c -> c.tasks.addAll(s.tasks); c.log.addAll(s.log); c.memories.addAll(s.memories); c.settings = s.settings }
    }

    /** After a look change the ask bar is rebuilt in the new colours. */
    internal fun restyleComposer() {
        if (!::composer.isInitialized) return
        val root = composer.parent as? ViewGroup ?: return
        val i = root.indexOfChild(composer); val lp = composer.layoutParams
        val draft = composerField.text.toString()
        root.removeView(composer)
        composer = buildComposer(); composerField.setText(draft)
        root.addView(composer, i, lp)
        fade.background = fadeDrawable()
        placeComposer()
    }

    // ================================================================ ATLAS (talk)

    private fun brainLine(): String {
        val phone = if (PhoneControl.on()) "phone control on" else "phone control off"
        return when {
            Conversation.online(this) -> "Gemini · $phone"
            ModelManager.ready -> "Offline model · ${ModelManager.modelName(this) ?: ""}".trimEnd(' ', '·')
            else -> "No brain set up yet · add a Gemini key in Me"
        }
    }

    private fun renderAtlas() {
        val s = snapshot()
        val msgs = Conversation.messages
        val name = s.settings.name.trim().split(" ").firstOrNull().orEmpty()

        // top: the wordmark, what Atlas thinks with, the look switch
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("Atlas", 17f, FG, DISPLAY).apply { letterSpacing = -0.01f })
        val st = text(brainLine(), 13f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        statusLine = st
        names.addView(st, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        bar.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (msgs.isNotEmpty()) bar.addView(roundIcon("plus", "New chat") {
            sheet(null, "Start a New Chat?", "This clears the conversation on this phone. Your Log stays as it is.", primaryLabel = "New Chat", onPrimary = {
                Conversation.clear(applicationContext); render()
            }, secondaryLabel = "Cancel")
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
        bar.addView(ThemeSwitch(this, dark, MED).apply { onChange = { d -> swapTheme(d, this) }; k = 0.78f })
        body.add(bar)

        // anything waiting on an answer from you comes first
        s.tasks.filter { it.status == "awaiting" }.sortedBy { it.promptedAt }.forEach { body.add(askCard(s.settings, it), 14) }

        if (msgs.isEmpty()) {
            val orb = OrbView(this).apply { still = Prefs.reduceMotion(this@MainActivity); isClickable = true; contentDescription = "Talk to Atlas"; setOnClickListener { voice() } }
            homeOrb = orb
            body.add(orb, 14, dp(184), dp(184))
            (orb.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            body.add(text(if (name.isNotEmpty()) "Hey $name,\nWhat can I help with?" else "Hey there,\nWhat can I help with?", 24f, FG, NUM).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f)
            }, 6)
            val ideas = listOf("Remind me to call mum tomorrow at 6pm", "Open ChatGPT and ask it how to find diamonds in Minecraft", "What can you do?")
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ideas.forEachIndexed { i, idea ->
                col.add(text(idea, 14f, FG, MED, keepEmoji = true).apply {
                    background = finished(SURFACE, -1f, glass = true); setPadding(dp(16), dp(11), dp(16), dp(12)); gravity = Gravity.CENTER
                    isClickable = true; setOnClickListener { composerField.setText(idea); composerField.setSelection(idea.length); composerField.requestFocus() }
                }, if (i == 0) 0 else 8)
            }
            body.add(col, 20)
            if (!Conversation.online(this)) body.add(text("To think properly Atlas needs a free Gemini key: Me → Brain.", 13f, MUTED).apply { gravity = Gravity.CENTER }, 18)
            return
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val orb = OrbView(this).apply { still = Prefs.reduceMotion(this@MainActivity); energy = if (Conversation.busy) 1f else 0f; isClickable = true; contentDescription = "Talk to Atlas"; setOnClickListener { voice() } }
        homeOrb = orb
        head.addView(orb, LinearLayout.LayoutParams(dp(64), dp(64)))
        body.add(head, 10)
        (head.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatList = list
        body.add(list, 4)
        fillChat(list)
    }

    /** Draws the conversation into [list]. */
    private fun fillChat(list: LinearLayout) {
        list.removeAllViews()
        val msgs = Conversation.messages
        msgs.forEachIndexed { i, m -> list.add(bubble(m, i == msgs.size - 1), if (i == 0) 0 else 10) }
        shownCount = msgs.size
    }

    /** The conversation moved on: update the Atlas tab in place (or draw it, if it just began). */
    private fun chatChanged() {
        if (!::composer.isInitialized) return
        updateSend()
        if (tab != 0) return
        statusLine?.text = brainLine()
        homeOrb?.energy = if (Conversation.busy || voiceInput != null) 1f else 0f
        val list = chatList
        if (list == null) { if (Conversation.messages.isNotEmpty() || shownCount > 0) render(keepScroll = true).also { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }; return }
        if (Conversation.messages.isEmpty()) { render(); return }
        val atBottom = scroll.scrollY + scroll.height >= body.height - dp(260)
        fillChat(list)
        if (atBottom || Conversation.busy) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (!Conversation.busy && Conversation.messages.lastOrNull()?.steps?.any { it.startsWith("Marked") } == true) celebrate()
    }

    private fun bubble(m: Conversation.Msg, last: Boolean): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (m.fromUser) Gravity.END else Gravity.START }
        val working = !m.fromUser && last && Conversation.busy
        val shown = when {
            m.text.isNotBlank() -> m.text
            working -> "${Conversation.status ?: "Thinking"}…"
            else -> "…"
        }
        val tv = text(shown, 16f, if (m.fromUser) ON else if (m.source == "error") MISS else FG, keepEmoji = true).apply {
            background = if (m.fromUser) Finish(BLUE, dp(22).toFloat(), dark, density) else StoneDrawable(SURFACE, dp(22).toFloat(), dark, density, page = BG)
            setPadding(dp(16), dp(11), dp(16), dp(12))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            setOnLongClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas", m.text)); showBanner("Copied"); true
            }
        }
        wrap.addView(tv)
        if (!m.fromUser) {
            m.steps.forEach { s -> wrap.addView(text("· $s", 13f, MUTED, keepEmoji = true).apply { setPadding(dp(10), dp(4), dp(8), 0) }) }
            val tag = when {
                working && m.text.isNotBlank() -> "${Conversation.status ?: "Working"}…"
                m.source == "error" -> ""
                else -> m.source
            }
            if (tag.isNotEmpty()) wrap.addView(text(tag, 12f, MUTED, MED).apply { setPadding(dp(10), dp(4), 0, 0) })
        }
        return wrap
    }

    // ---------------------------------------------------------------- the ask bar

    private fun buildComposer(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; elevation = dp(8).toFloat() }
        val mic = FrameLayout(this).apply {
            background = quietBg(); isClickable = true; contentDescription = "Speak"
            addView(LineIcon(this@MainActivity, "mic").apply { color = quietInk() }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { voice() }
            pressFx()
        }
        composerField = EditText(this).apply {
            hint = "Ask anything…"; setTextColor(FG); setHintTextColor(MUTED); textSize = 16f; typeface = BODY
            background = rounded(if (dark) SURFACE2 else Color.parseColor(LIGHT_FIELD), 999, LINE)
            setPadding(dp(18), dp(13), dp(18), dp(13)); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        val icon = LineIcon(this@MainActivity, if (Conversation.busy) "stop" else "arrow").apply { color = Color.WHITE; rotation = if (Conversation.busy) 0f else -90f; tag = if (Conversation.busy) "stop" else "arrow" }
        sendIcon = icon
        val send = FrameLayout(this).apply {
            // Atlas's send key: a glowing drop of blue and violet
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(34).toFloat()
                colors = intArrayOf(Color.parseColor("#6E8BFF"), Color.parseColor("#4B3BD6"))
            }
            elevation = dp(8).toFloat(); outlineSpotShadowColor = Color.parseColor("#5B6CFF"); outlineAmbientShadowColor = Color.parseColor("#5B6CFF")
            isClickable = true; contentDescription = if (Conversation.busy) "Stop" else "Send"
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { if (Conversation.busy) Conversation.stop() else submit() }
            pressFx()
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(composerField, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    /** The send key turns into a stop key while Atlas is working. */
    private fun updateSend() {
        val busy = Conversation.busy
        val old = sendIcon ?: return
        val parent = old.parent as? FrameLayout ?: return
        val want = if (busy) "stop" else "arrow"
        if (old.tag == want) return
        parent.removeView(old)
        val icon = LineIcon(this, want).apply { color = Color.WHITE; rotation = if (busy) 0f else -90f; tag = want }
        parent.addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        parent.contentDescription = if (busy) "Stop" else "Send"
        sendIcon = icon
    }

    /** The ask bar sits above the tab bar; with the keyboard open the tab bar steps aside and the bar rests on the keyboard. */
    private fun placeComposer() {
        if (!::composer.isInitialized) return
        composer.visibility = if (tab == 0) View.VISIBLE else View.GONE
        fade.visibility = if (tab == 0) View.VISIBLE else View.GONE
        nav.visibility = if (imeOpen && tab == 0) View.GONE else View.VISIBLE
        (composer.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = dp(if (imeOpen) 10 else 86); composer.layoutParams = it }
    }

    private fun submit() {
        val q = composerField.text.toString().trim()
        if (q.isEmpty()) return
        composerField.setText("")
        sendMessage(q)
    }

    private fun sendMessage(q: String, spoken: Boolean = false) {
        if (tab != 0) { tab = 0; render() }
        Conversation.send(this, q, spoken)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- listening (Atlas's own, no Google pop-up)

    private var closeVoiceSheet: (() -> Unit)? = null

    private fun voice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        if (voiceInput != null) { voiceInput?.finish(); return }
        Conversation.hush()
        AvatarService.holdMic(true)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val orb = OrbView(this).apply { energy = 1f; still = Prefs.reduceMotion(this@MainActivity) }
        col.addView(orb, LinearLayout.LayoutParams(dp(170), dp(170)))
        val heard = text("Listening…", 18f, MUTED, MED, keepEmoji = true).apply { gravity = Gravity.CENTER; minLines = 2 }
        col.add(heard, 8)
        var got: String? = null
        val vi = VoiceInput(this,
            onPartial = { t -> heard.text = t; heard.setTextColor(FG); got = t },
            onLevel = { l -> orb.level = l },
            onDone = { t ->
                stopVoice()
                if (t.isNullOrBlank()) showBanner("I didn't catch that.", MUTED) else sendMessage(t, spoken = true)
            },
            onError = { msg -> stopVoice(); showBanner(msg, MUTED) })
        voiceInput = vi
        homeOrb?.energy = 1f
        closeVoiceSheet = sheet(null, "Talk to Atlas", null, content = col, primaryLabel = "Done", onPrimary = {
            keepSheet = true; heard.text = got ?: "One moment…"; vi.finish()
        }, secondaryLabel = "Cancel", onSecondary = { stopVoice() })
        vi.start()
    }

    private fun stopVoice() {
        voiceInput?.cancel(); voiceInput = null
        closeVoiceSheet?.invoke(); closeVoiceSheet = null
        AvatarService.holdMic(false)
        homeOrb?.energy = if (Conversation.busy) 1f else 0f
    }

    // ================================================================ LOG

    private fun promptFor(st: com.anjy.atlas.core.Settings, t: Task) = when (st.tone) {
        "gentle" -> "How did “${t.title}” go?"
        "challenging" -> "Time is up on “${t.title}”. Did you do it?"
        else -> "“${t.title}” was due to finish. What's the status?"
    }

    private fun askCard(st: com.anjy.atlas.core.Settings, t: Task): View {
        val c = card()
        c.background = StoneDrawable(SURFACE, dp(26).toFloat(), dark, density, edge = if (dark) ACCENT else null, page = BG)
        c.addView(eyebrow("Check-In").apply { setTextColor(ACCENT) })
        c.add(text(promptFor(st, t), 20f, FG, HEAD), 6)
        val meta = buildString {
            t.startAt?.let { append(Reminders.fmt(it)).append(" · ") }
            append("${t.durationMin} min")
            if (t.status == "awaiting" && t.followUpsSent >= st.followUps.coerceIn(0, 3)) t.promptedAt?.let { append(" · waiting since ${Reminders.fmt(it)}") }
        }
        c.add(text(meta, 13f, MUTED), 4)
        val id = t.id
        c.add(primary("Done") { respond(id, "done") }, 16)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(listOf("Still Working" to { pickMinutes(id) }, "Delayed" to { pickDelay(id, t) }),
            listOf("Blocked" to { askReason(id, "blocked") }, "Skipped" to { askReason(id, "skipped") })).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, act) -> r.addView(secondary(label) { act() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
            grid.add(r, 8)
        }
        c.addView(grid)
        return c
    }

    private fun respond(id: String, kind: String, minutes: Int? = null, startAt: Long? = null, reason: String = "") {
        val now = System.currentTimeMillis()
        try {
            val (t, next) = Store.edit(this) { Engine.respond(it, id, kind, now, minutes, startAt, reason) }
            getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
            when (kind) {
                "done" -> { celebrate(); showBanner(if (next != null) "Done. Next one ${whenLabel(next.startAt)}" else "Done at ${Reminders.fmt(now)}", DONE) }
                "progress" -> showBanner("I'll check again in $minutes minutes")
                "delay" -> showBanner("Moved to ${whenLabel(t.startAt)}")
                "blocked" -> showBanner("Marked blocked", MUTED)
                "skipped" -> showBanner("Recorded as skipped", MUTED)
                "unblock" -> showBanner("Moved to No Time Yet")
            }
        } catch (e: Exception) { showBanner(e.message ?: "That didn't work.", MISS) }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        render(keepScroll = true)
    }

    private fun pickMinutes(id: String) {
        val vals = listOf(10, 15, 30, 60)
        choose("Check Again In", vals.map { "$it minutes" }) { i -> respond(id, "progress", minutes = vals[i]) }
    }

    private fun pickDelay(id: String, t: Task) {
        val now = System.currentTimeMillis()
        choose("Move It To", listOf("In 30 Minutes", "In 1 Hour", "Tomorrow, Same Time", "Pick a Day and Time")) { i ->
            when (i) {
                0 -> respond(id, "delay", startAt = now + 30 * Engine.MIN)
                1 -> respond(id, "delay", startAt = now + 60 * Engine.MIN)
                2 -> {
                    var nx = Instant.ofEpochMilli(t.startAt ?: now).atZone(Engine.zone).plusDays(1)
                    while (nx.toInstant().toEpochMilli() < now) nx = nx.plusDays(1)
                    respond(id, "delay", startAt = nx.toInstant().toEpochMilli())
                }
                else -> body.post { dateTimeSheet("New Time", now + 30 * Engine.MIN) { ms -> respond(id, "delay", startAt = ms) } }
            }
        }
    }

    private fun askReason(id: String, kind: String) {
        var r = ""
        val f = field(if (kind == "blocked") "The blocker" else "Reason (optional)", "") { r = it }
        sheet(null, if (kind == "blocked") "What's in the Way?" else "Skipped", null, content = f, primaryLabel = "Save", onPrimary = {
            if (kind == "blocked" && r.isBlank()) { keepSheet = true; showBanner("Say what's blocking it.", MUTED) }
            else respond(id, kind, reason = r)
        }, secondaryLabel = "Cancel")
    }

    private fun taskRow(t: Task, clashes: Set<String>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)); gravity = Gravity.CENTER_VERTICAL; isClickable = true }
        val whenTxt = t.startAt?.let { at ->
            val sameDay = Instant.ofEpochMilli(at).atZone(Engine.zone).toLocalDate() == LocalDate.now(Engine.zone)
            if (sameDay) Reminders.fmt(at) else Instant.ofEpochMilli(at).atZone(Engine.zone).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) + "\n" + Reminders.fmt(at)
        } ?: "—"
        r.addView(text(whenTxt, 15f, if (t.startAt != null) FG else MUTED, NUM), LinearLayout.LayoutParams(dp(76), WRAP))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(t.title, 16f, if (t.status == "done") MUTED else FG, MED, keepEmoji = true))
        val meta = mutableListOf<String>()
        if (t.startAt != null) meta.add("${t.durationMin} min")
        if (t.recurrence != "none") meta.add(t.recurrence.replaceFirstChar { it.uppercase() })
        if (t.priority == "high") meta.add("High priority")
        if (t.status == "blocked" && t.blockedReason.isNotBlank()) meta.add(t.blockedReason)
        if (t.status == "done") t.completedAt?.let { meta.add("Done ${Reminders.fmt(it)}") }
        if (t.notes.isNotBlank()) meta.add(t.notes)
        if (meta.isNotEmpty()) mid.addView(text(meta.joinToString(" · "), 13f, MUTED, keepEmoji = true))
        if (t.id in clashes) mid.addView(text("Overlaps another reminder", 13f, ACCENT, MED))
        r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        val (label, colr) = when (t.status) {
            "active" -> "Now" to ACCENT; "awaiting" -> "Answer" to ACCENT; "blocked" -> "Blocked" to MISS
            "done" -> "Done" to DONE; "skipped" -> "Skipped" to MUTED; "cancelled" -> "Cancelled" to MUTED
            else -> "" to MUTED
        }
        if (label.isNotEmpty()) r.addView(text(label, 12f, colr, SEMI).apply {
            background = Finish(SURFACE, -1f, dark, density, glass = true); setPadding(dp(10), dp(4), dp(10), dp(5))
        })
        r.setOnClickListener { taskActions(t) }
        return r
    }

    private fun taskActions(t: Task) {
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        val now = System.currentTimeMillis()
        val reschedule = { scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render(keepScroll = true) }
        if (t.status == "planned") acts.add("Start Now" to { Store.edit(this) { Engine.startNow(it, t.id, now) }; reschedule() })
        if (t.status in Engine.OPEN) acts.add("Mark Done" to { respond(t.id, "done") })
        if (t.status == "blocked") acts.add("Unblock" to { respond(t.id, "unblock") })
        if (t.status in Engine.OPEN) {
            acts.add("Edit" to { body.post { editTask(t) } })
            acts.add("Cancel It" to { Store.edit(this) { Engine.cancel(it, t.id, now) }; reschedule() })
        }
        acts.add("Delete" to {
            body.post {
                sheet(null, "Delete This?", t.title, primaryLabel = "Delete", primaryColor = MISS, onPrimary = {
                    Store.edit(this) { Engine.delete(it, t.id, now) }; reschedule()
                }, secondaryLabel = "Keep")
            }
        })
        choose(t.title, acts.map { it.first }) { i -> acts[i].second() }
    }

    private fun editTask(t: Task?) {
        var title = t?.title ?: ""
        var startAt: Long? = t?.startAt
        var minutes = t?.durationMin ?: 30
        var priority = t?.priority ?: "normal"
        var recurrence = t?.recurrence ?: "none"
        var notes = t?.notes ?: ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.add(field("What", title) { title = it })
        col.add(eyebrow("When"), 16)
        val whenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var whenBtn: TextView
        whenBtn = secondary(whenLabel(startAt)) {
            dateTimeSheet("When", startAt ?: (System.currentTimeMillis() + 60 * Engine.MIN)) { ms -> startAt = ms; whenBtn.text = whenLabel(ms) }
        }
        whenRow.addView(whenBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        whenRow.addView(secondary("Clear") { startAt = null; whenBtn.text = whenLabel(null) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        col.add(whenRow, 8)
        col.add(eyebrow("Minutes"), 16)
        col.add(chipGroup(listOf(10, 15, 30, 45, 60, 90, 120).map { "$it" to "$it" }, { it == "$minutes" }) { minutes = it.toInt() }, 8)
        col.add(eyebrow("Priority"), 16)
        col.add(chipGroup(listOf("high" to "High", "normal" to "Normal", "low" to "Low"), { it == priority }) { priority = it }, 8)
        col.add(eyebrow("Repeats"), 16)
        col.add(chipGroup(listOf("none" to "Never", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly"), { it == recurrence }) { recurrence = it }, 8)
        col.add(eyebrow("Notes"), 16)
        col.add(field("Optional", notes, multi = true) { notes = it }, 8)
        sheet(null, if (t == null) "New Reminder" else "Edit Reminder", null, content = col, primaryLabel = if (t == null) "Add" else "Save", onPrimary = {
            if (recurrence != "none" && startAt == null) { keepSheet = true; showBanner("A repeating reminder needs a day and time.", MUTED); return@sheet }
            val now = System.currentTimeMillis()
            try {
                Store.edit(this) { s ->
                    if (t == null) Engine.create(s, title, now, startAt, minutes, notes, priority, recurrence)
                    else Engine.update(s, t.id, title, now, startAt, minutes, notes, priority, recurrence)
                }
                scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                render(keepScroll = true)
            } catch (e: Exception) { keepSheet = true; showBanner(e.message ?: "Couldn't save.", MISS) }
        }, secondaryLabel = "Cancel")
    }

    private fun renderLog() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(Engine.zone)
        fun isToday(ms: Long?) = ms != null && Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate() == today
        val s = snapshot()
        val st = s.settings
        pageTitle("Log", roundIcon("plus", "New reminder") { editTask(null) }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) })
        body.add(text("What Atlas is keeping for you: reminders, and things to remember.", 15f, MUTED), 6)

        val awaiting = s.tasks.filter { it.status == "awaiting" }.sortedBy { it.promptedAt }
        val active = s.tasks.filter { it.status == "active" }.sortedBy { it.startAt }
        val blocked = s.tasks.filter { it.status == "blocked" }
        val todayList = s.tasks.filter { it.status == "planned" && isToday(it.startAt) }.sortedBy { it.startAt }
        val upcoming = s.tasks.filter { it.status == "planned" && it.startAt != null && !isToday(it.startAt) && it.startAt!! > now }.sortedBy { it.startAt }.take(40)
        val overdue = s.tasks.filter { it.status == "planned" && it.startAt != null && !isToday(it.startAt) && it.startAt!! <= now }.sortedBy { it.startAt }
        val noTime = s.tasks.filter { it.status == "planned" && it.startAt == null }
        val done = s.tasks.filter { it.status == "done" && it.completedAt != null }.sortedByDescending { it.completedAt }.take(10)
        val clashes = Engine.collisions(s)

        awaiting.forEach { body.add(askCard(st, it), 14) }
        fun section(title: String, list: List<Task>) {
            if (list.isEmpty()) return
            body.add(eyebrow("$title · ${list.size}"), 16)
            val c = card().apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
            list.forEachIndexed { i, t ->
                if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
                c.addView(taskRow(t, clashes))
            }
            body.add(c, 8)
        }
        section("Now", active); section("Today", todayList); section("Coming Up", upcoming); section("Missed", overdue)
        section("No Time Yet", noTime); section("Blocked", blocked)
        if (awaiting.isEmpty() && active.isEmpty() && todayList.isEmpty() && upcoming.isEmpty() && overdue.isEmpty() && noTime.isEmpty() && blocked.isEmpty()) {
            body.add(card().apply {
                addView(text("No reminders", 18f, FG, HEAD))
                add(text("Tell Atlas “remind me to pick up my sister next Thursday at 4” and it lands here.", 15f, MUTED), 6)
            }, 16)
        }

        // things to remember
        val mems = s.memories.toList().reversed()
        body.add(eyebrow("Remembered · ${mems.size}"), 20)
        val mc = card().apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
        var v = ""
        val f = field("Something to remember", "") { v = it }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(f, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        r.addView(primary("Save") {
            val t = v.trim()
            if (t.isNotEmpty()) { Store.edit(this) { Engine.remember(it, t, System.currentTimeMillis()) }; ModelManager.markStale(); showBanner("Saved"); render(keepScroll = true) }
        }.apply { minHeight = dp(52); setPadding(dp(22), dp(13), dp(22), dp(13)) })
        mc.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
        mems.forEach { m ->
            mc.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
            mc.addView(text(m.text, 16f, FG, keepEmoji = true).apply {
                setPadding(0, dp(14), 0, dp(14)); isClickable = true
                setOnClickListener {
                    sheet(null, "Forget This?", m.text, primaryLabel = "Forget", primaryColor = MISS, onPrimary = {
                        Store.edit(this@MainActivity) { Engine.forget(it, m.id, System.currentTimeMillis()) }; ModelManager.markStale(); render(keepScroll = true)
                    }, secondaryLabel = "Keep")
                }
            })
        }
        body.add(mc, 8)

        section("Done Lately", done)

        val log = s.log.takeLast(40).reversed().map { l -> Triple(l.at, l.type, l.title + if (l.detail.isNotBlank() && l.type in setOf("blocked", "skipped", "note", "progress")) " (${l.detail})" else "") }
        if (log.isNotEmpty()) {
            body.add(eyebrow("Activity"), 20)
            val a = card().apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
            val names = mapOf("created" to "Added", "edited" to "Edited", "deleted" to "Deleted", "cancelled" to "Cancelled", "started" to "Started",
                "reminded" to "Reminded", "checkin" to "Asked", "followup" to "Followed up", "done" to "Done", "progress" to "Still working",
                "delayed" to "Delayed", "unblocked" to "Unblocked", "blocked" to "Blocked", "skipped" to "Skipped", "note" to "Note",
                "remembered" to "Remembered", "forgot" to "Forgot")
            log.forEach { (at, type, title) ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
                val sameDay = Instant.ofEpochMilli(at).atZone(Engine.zone).toLocalDate() == today
                line.addView(text(if (sameDay) Reminders.fmt(at) else Instant.ofEpochMilli(at).atZone(Engine.zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), 13f, MUTED, NUM), LinearLayout.LayoutParams(dp(64), WRAP))
                line.addView(text("${names[type] ?: type} · $title", 14f, FG, keepEmoji = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                a.addView(line)
            }
            body.add(a, 8)
        }
    }

    // ================================================================ ME

    private fun renderMe() {
        val st = Store.read(this) { it.settings }
        pageTitle("Me", ThemeSwitch(this, dark, MED).apply { onChange = { d -> swapTheme(d, this) } })

        // Brain: Gemini
        body.add(eyebrow("Brain"), 16)
        val brain = card()
        val key = Conversation.key(this)
        brain.addView(text("Gemini", 20f, FG, HEAD))
        brain.add(text(if (key != null) "Connected · ${Conversation.model(this)}" else "Not set up. Atlas needs a Gemini key to think properly and to use your phone.", 14f, if (key != null) DONE else MUTED), 4)
        var keyDraft = ""
        val kf = field(if (key != null) "Paste a new key to replace it" else "Paste your Gemini API key", "") { keyDraft = it }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true)
        }
        brain.add(kf, 14)
        val kr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kr.addView(primary("Save Key") {
            val k = keyDraft.trim()
            if (k.length < 20) { showBanner("That doesn't look like a whole key.", MUTED); return@primary }
            Store.putString(this, "gemini_key", k); showBanner("Key saved. Testing it…"); testKey()
            render(keepScroll = true)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        kr.addView(secondary("Get a Free Key") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_KEYS))) } catch (_: Exception) { showBanner("Open $AI_STUDIO_KEYS in your browser.", MUTED) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        brain.add(kr, 8)
        if (key != null) {
            brain.add(settingRow("Model", Conversation.model(this)) {
                var m = Conversation.model(this)
                sheet(null, "Gemini Model", "Leave it as ${Gemini.DEFAULT_MODEL} unless you know you want another.", content = field("Model name", m) { m = it }, primaryLabel = "Save", onPrimary = {
                    Store.putString(this, "gemini_model", m.trim().ifEmpty { Gemini.DEFAULT_MODEL }); render(keepScroll = true)
                }, secondaryLabel = "Cancel")
            }, 10)
            val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tr.addView(secondary("Test") { testKey() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            tr.addView(secondary("Remove Key") {
                sheet(null, "Remove the Gemini Key?", "Atlas will fall back to fixed commands and the offline model.", primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
                    Store.putString(this, "gemini_key", ""); render(keepScroll = true)
                }, secondaryLabel = "Keep")
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            brain.add(tr, 8)
        }
        brain.add(text("Get the key at aistudio.google.com (sign in with Google, “Create API key”), copy it, paste it here. When Atlas thinks online, what you say goes to Google; on the free tier Google may use it to improve its products.", 13f, MUTED), 12)
        body.add(brain, 8)

        // Offline model
        body.add(eyebrow("Offline Brain"), 16)
        val off = card()
        off.addView(text(ModelManager.modelName(this) ?: "No Offline Model", 18f, FG, HEAD, keepEmoji = true))
        off.add(text(if (key != null) "Used only when there's no internet. ${ModelManager.status}" else ModelManager.status, 14f, MUTED), 4)
        val progress = text("", 14f, ACCENT, MED, keepEmoji = true)
        off.add(progress, 4)
        modelProgressView = progress
        off.add(secondary("Choose Model File") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MODEL)
        }, 12)
        if (ModelManager.modelName(this) != null) {
            val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            br.addView(secondary(if (ModelManager.ready) "Reload" else "Load") {
                progress.text = "Loading…"; scope.launch { ModelManager.load(applicationContext); render(keepScroll = true) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (ModelManager.ready) br.addView(secondary("Unload") { ModelManager.unload(); render(keepScroll = true) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            br.addView(secondary("Remove") {
                sheet(null, "Remove the Model?", ModelManager.modelName(this) ?: "Model file", primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
                    scope.launch(Dispatchers.IO) { ModelManager.remove(applicationContext); withContext(Dispatchers.Main) { render(keepScroll = true) } }
                }, secondaryLabel = "Keep")
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            off.add(br, 8)
        }
        body.add(off, 8)

        // Phone control
        body.add(eyebrow("Phone Control"), 16)
        val pc = card()
        val connected = PhoneControl.on()
        val switched = AtlasAccess.enabledInSettings(this)
        pc.addView(text(when { connected -> "On"; switched -> "Switched on, waiting for Android"; else -> "Off" }, 18f, if (connected) DONE else FG, HEAD))
        pc.add(text("Lets Atlas open apps, read the screen, tap, type and scroll for you, like a person would. It only acts when you ask it to.", 14f, MUTED), 4)
        if (!connected) {
            pc.add(primary("Turn On in Settings") {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }, 14)
            pc.add(text("In Accessibility, find Atlas (sometimes under “Installed apps” or “Downloaded apps”) and switch it on. If Android says it's a restricted setting: open App Info below, tap ⋮ at the top right, choose “Allow restricted settings”, then try again.", 13f, MUTED), 10)
            pc.add(secondary("App Info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, 10)
        } else pc.add(secondary("Accessibility Settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 12)
        body.add(pc, 8)

        // Voice
        body.add(eyebrow("Voice"), 16)
        val vc = card()
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val downloading = Hotword.downloadState
        vc.addView(switchRow(null, "“Hey Atlas” (beta)", st.wakeWord && micOk && Hotword.ready(this), enabled = downloading == null) { on ->
            if (!on) { Store.edit(this) { it.settings.wakeWord = false }; AvatarService.refresh(this); body.postDelayed({ if (tab == 2) render(keepScroll = true) }, 600); return@switchRow }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WAKE); return@switchRow
            }
            enableWake()
        })
        val hwState = AvatarService.hotwordState()
        vc.add(text(when {
            downloading != null -> "Getting the Hey Atlas model (${Hotword.MODEL_MB} MB, once): $downloading…"
            !st.wakeWord && Store.getFlag(this, "hotwordCrashed") -> "Hey Atlas was switched off because its speech engine crashed on this phone. You can switch it on to try again; if it crashes again it switches itself off."
            st.wakeWord && hwState == "listening" -> "Listening for “Hey Atlas”. Say it clearly; it's in beta and can miss. Android shows its green microphone dot while it listens, and it uses some battery. It works while the phone is unlocked."
            st.wakeWord && !AvatarService.canShow(this) -> "To open its panel from other apps, Atlas also needs “Display over other apps” (Creature below)."
            st.wakeWord -> "Starting… ($hwState)"
            else -> "Say “Hey Atlas” from anywhere to ask something without opening the app. It's heard on the phone only; nothing is recorded or sent. Switching it on downloads a ${Hotword.MODEL_MB} MB speech model once."
        }, 13f, MUTED), 8)
        vc.add(switchRow(null, "Speak Replies", st.speakReplies) { on -> Store.edit(this) { it.settings.speakReplies = on } }, 8)
        vc.add(text("Spoken questions always get a spoken answer.", 13f, MUTED), 6)
        body.add(vc, 8)

        // Creature
        body.add(eyebrow("Creature"), 16)
        val av = card()
        if (!AvatarService.canShow(this)) {
            av.addView(text("Your creature can float over your other apps. Tap it to ask Atlas something. Android needs your permission first.", 15f, MUTED))
            av.add(primary("Allow Display Over Apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, 14)
        } else {
            av.addView(switchRow(null, "Creature On Screen", st.avatarOn) { on ->
                Store.edit(this) { it.settings.avatarOn = on }
                if (on || st.wakeWord) AvatarService.refresh(this) else AvatarService.stop(this)
            })
            av.add(text("Drag it anywhere. Tap it to ask Atlas something.", 13f, MUTED), 8)
        }
        body.add(av, 8)

        // You
        body.add(eyebrow("You"), 16)
        val you = card()
        you.add(settingRow("Name", st.name.ifBlank { "Not set" }) {
            var v = st.name
            sheet(null, "Your Name", "Atlas greets you by it.", content = field("Name", v) { v = it }, primaryLabel = "Save", onPrimary = {
                Store.edit(this) { it.settings.name = v.trim().take(40) }; ModelManager.markStale(); render(keepScroll = true)
            }, secondaryLabel = "Cancel")
        })
        val tones = listOf("gentle" to "Supportive", "firm" to "Firm", "challenging" to "Challenging")
        you.add(settingRow("Check-In Tone", tones.firstOrNull { it.first == st.tone }?.second ?: "Firm") {
            val i = tones.indexOfFirst { it.first == st.tone }
            Store.edit(this) { it.settings.tone = tones[(i + 1) % tones.size].first }; render(keepScroll = true)
        }, 8)
        you.add(switchRow(null, "Reduce Motion", Prefs.reduceMotion(this)) { on ->
            Store.setFlag(this, "reduceMotion", on); aurora.still = on; render(keepScroll = true)
        }, 8)
        body.add(you, 8)

        // Reminders
        body.add(eyebrow("Reminders"), 16)
        val rem = card()
        fun hm(v: String) = Engine.hm(v) ?: 0
        rem.add(settingRow("Quiet From", st.quietStart) {
            timeSheet("Quiet From", hm(st.quietStart) / 60, hm(st.quietStart) % 60) { h, m -> saveSetting { it.quietStart = "%02d:%02d".format(Locale.US, h, m) } }
        })
        rem.add(settingRow("Quiet Until", st.quietEnd) {
            timeSheet("Quiet Until", hm(st.quietEnd) / 60, hm(st.quietEnd) % 60) { h, m -> saveSetting { it.quietEnd = "%02d:%02d".format(Locale.US, h, m) } }
        }, 8)
        val graces = listOf(5, 10, 15, 30, 60)
        rem.add(settingRow("Follow Up After", "${st.graceMin} min") {
            saveSetting { it.graceMin = graces[(graces.indexOf(st.graceMin).coerceAtLeast(0) + 1) % graces.size] }
        }, 8)
        rem.add(settingRow("Follow-Ups", "${st.followUps}") { saveSetting { it.followUps = (st.followUps + 1) % 4 } }, 8)
        rem.add(text("After the last follow-up a reminder stays marked as needing an answer. It's never marked done or skipped for you.", 13f, MUTED), 10)
        body.add(rem, 8)

        // Reliability
        body.add(eyebrow("Make Atlas Reliable"), 16)
        val rel = card()
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        rel.add(settingRow("Notifications", if (notifOk) "Allowed" else "Blocked") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) })
        rel.add(settingRow("Microphone", if (micOk) "Allowed" else "Not allowed") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WAKE + 100) }, 8)
        rel.add(settingRow("Exact Alarms", if (am.canScheduleExactAlarms()) "Allowed" else "Not allowed") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 8)
        rel.add(settingRow("Battery", if (pm.isIgnoringBatteryOptimizations(packageName)) "Unrestricted" else "Optimised") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, 8)
        rel.add(settingRow("App Info", "Open") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, 8)
        rel.add(text("On OPPO phones, also allow background activity and auto-launch in App Info → Battery, or Android may stop Atlas in the background.", 13f, MUTED), 10)
        rel.add(secondary("Test a Reminder in 1 Minute") {
            val now = System.currentTimeMillis()
            Store.edit(this) { Engine.create(it, "Atlas test reminder", now, now + 60_000, 5) }
            scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
            showBanner("Lock the phone. It should arrive in a minute.")
        }, 14)
        body.add(rel, 8)

        // Backup
        body.add(eyebrow("Backup"), 16)
        val bk = card()
        bk.addView(text("Your Log lives only on this phone. The backup doesn't include your Gemini key.", 15f, MUTED))
        val bkr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bkr.addView(secondary("Copy Backup") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas backup", Store.exportJson(this)))
            showBanner("Backup copied")
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bkr.addView(secondary("Restore") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        bk.add(bkr, 14)
        body.add(bk, 8)
        body.add(text("Atlas ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f, MUTED).apply { gravity = Gravity.CENTER }, 16)
    }

    /** Switch "Hey Atlas" on: fetch its model first if this phone doesn't have it yet. */
    private fun enableWake() {
        Store.setFlag(this, "hotwordCrashed", false)
        if (Hotword.ready(this)) {
            Store.edit(this) { it.settings.wakeWord = true }; AvatarService.refresh(this)
            body.postDelayed({ if (tab == 2) render(keepScroll = true) }, 1500)
            return
        }
        if (Hotword.downloadState != null) return
        var shown = -1
        scope.launch {
            render(keepScroll = true)
            val err = withContext(Dispatchers.IO) {
                try {
                    Hotword.download(applicationContext) { pct -> if (pct / 5 != shown / 5) { shown = pct; runOnUiThread { if (tab == 2) render(keepScroll = true) } } }
                    null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) showBanner("Couldn't get the Hey Atlas model: $err", MISS)
            else { Store.edit(this@MainActivity) { it.settings.wakeWord = true }; AvatarService.refresh(this@MainActivity); showBanner("Hey Atlas is ready", DONE) }
            if (tab == 2) render(keepScroll = true)
            body.postDelayed({ if (tab == 2) render(keepScroll = true) }, 1500)
        }
    }

    /** One tiny request, to say whether the key and model work. */
    private fun testKey() {
        val k = Conversation.key(this) ?: return
        val model = Conversation.model(this)
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val req = Gemini.request("Reply with exactly: ready", listOf(Gemini.userText("Are you there?")), Tools.declarations, model)
                    val t = Gemini.call(k, model, req, Conversation.endpoint(applicationContext))
                    "Gemini works: “${t.text.take(40)}”"
                } catch (e: Gemini.Failure) { e.message ?: "Gemini failed." }
            }
            showBanner(msg, if (msg.startsWith("Gemini works")) DONE else MISS)
            if (tab == 2) render(keepScroll = true)
        }
    }

    private fun saveSetting(change: (com.anjy.atlas.core.Settings) -> Unit) {
        Store.edit(this) { change(it.settings) }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        render(keepScroll = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
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
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) {
                        // stays on screen: the message says what the file really is
                        modelProgressView?.text = e.message ?: "Couldn't use that file."
                        showBanner("That file can't be used", MISS)
                        return@launch
                    }
                    render(keepScroll = true)
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
                        showBanner("Restored $n reminders", DONE)
                    } catch (e: Exception) { showBanner("Restore failed: ${e.message}", MISS) }
                    render(keepScroll = true)
                }
            }
        }
    }
}
