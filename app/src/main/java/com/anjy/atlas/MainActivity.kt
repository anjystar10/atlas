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
 * Atlas's one screen. Everything about how it looks and moves is Stoa's own UI code, carried over as it is
 * (palette, type, glass surfaces, the floating tab bar with its liquid drop, page turns, pop-ups, Ember).
 * What's on the pages is Atlas: Today, Chat, Memory and Me.
 */
class MainActivity : Activity() {

    // Stoa's two looks share one layout in two materials. Night: true black, warm white ink, colour only in
    // glowing tiles and Ember. Day: frosted glass panels on a brown page, near-black ink, black glass buttons.
    // BLUE is the ink of actions (white pills at night, black by day); ON is the text on it.
    internal var dark = true
    internal var BG = Color.parseColor("#000000")
    internal var SURFACE = Color.parseColor("#101011")
    internal var SURFACE2 = Color.parseColor("#1A1A1C")
    internal var LINE = Color.parseColor("#262628")
    internal var FG = Color.parseColor("#F3F2EE")
    internal var MUTED = Color.parseColor("#9C9B97")
    /** Text on an ink or coloured fill. */
    internal var ON = Color.parseColor("#050505")
    internal var DONE = Color.parseColor("#5BD6A6")
    internal var MISS = Color.parseColor("#FF6B6B")
    internal var BLUE = Color.parseColor("#F3F2EE")      // ink: actions, links, selection
    internal var ORANGE = Color.parseColor("#FF7A2E")    // Ember, streak, warnings
    internal var GOLD = Color.parseColor("#F2B544")      // XP and levels
    internal var STEEL = Color.parseColor("#1A1A1C")     // neutral tag fill
    internal var NAVBG = Color.parseColor("#1E1E20")
    /** By day, the fill of the quieter buttons: black glass, like the main ones. (At night they're clear glass.) */
    internal var BTN2 = Color.parseColor("#77726C")

    internal fun applyPalette() {
        val mode = Store.getString(this, "theme2") ?: Store.read(this) { it.settings.theme }.takeIf { it in setOf("dark", "light", "system") } ?: "dark"
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        dark = mode == "dark" || (mode == "system" && systemDark)
        // both looks are set in full every time: switching back from light to dark (without restarting the
        // screen) must put every dark colour back, not just the ones a fresh start happens to begin with
        if (!dark) {
            // frosted glass on a brown page (#C6A787 made 5% lighter: HSL lightness 65.3% to 70.3%), no grey anywhere.
            // SURFACE is what a pane of frosted glass (white at about 45%) looks like over the brown, so flat fills, sheets
            // and blends match the glass panels; SURFACE2 is thinner glass, LINE the white rim of the glass.
            // Near-black ink and black glass buttons; quieter text, warnings, done marks, gold and ember read on both.
            BG = Color.parseColor("#CEB498"); SURFACE = Color.parseColor(LIGHT_GLASS); SURFACE2 = Color.parseColor("#DBC9B4")
            LINE = Color.parseColor("#F5EEE6"); FG = Color.parseColor("#0B0B0C"); MUTED = Color.parseColor("#45403B")
            ON = Color.parseColor("#FFFFFF")
            DONE = Color.parseColor("#0A6646"); MISS = Color.parseColor("#A11B15")
            BLUE = Color.parseColor("#000000"); ORANGE = Color.parseColor("#C2410C"); GOLD = Color.parseColor("#7A5200")
            STEEL = Color.parseColor("#EFE6DB"); NAVBG = Color.parseColor("#000000"); BTN2 = Color.parseColor("#000000")
        } else {
            BG = Color.parseColor("#000000"); SURFACE = Color.parseColor("#101011"); SURFACE2 = Color.parseColor("#1A1A1C")
            LINE = Color.parseColor("#262628"); FG = Color.parseColor("#F3F2EE"); MUTED = Color.parseColor("#9C9B97")
            ON = Color.parseColor("#050505")
            DONE = Color.parseColor("#5BD6A6"); MISS = Color.parseColor("#FF6B6B")
            BLUE = Color.parseColor("#F3F2EE"); ORANGE = Color.parseColor("#FF7A2E"); GOLD = Color.parseColor("#F2B544")
            STEEL = Color.parseColor("#1A1A1C"); NAVBG = Color.parseColor("#1E1E20"); BTN2 = Color.parseColor("#101011")
        }
    }

    /** Light mode's panels: frosted glass (white at about 45%) seen over the brown page. */
    internal val LIGHT_GLASS = "#E4D6C6"
    /** Light mode's text fields: clearer glass, a shade brighter than the panels they sit on. */
    internal val LIGHT_FIELD = "#EFE6DB"

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
            glow(ORANGE, if (dark) 0x18 else 0x10, 1.0f, 0.0f, w * 0.9f)
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
    /** By day the streak sits on brown, a shade deeper than the page (#C6A787, the page's brown before it was lightened). */
    internal val STREAK_BROWN = Color.parseColor("#C6A787")
    internal fun stage(): StageLayout = StageLayout(this, if (dark) SURFACE else STREAK_BROWN, dark, BLUE, ORANGE).apply {
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
            background = Finish(if (dark) Color.parseColor("#2A2A2D") else Color.BLACK, -1f, dark, resources.displayMetrics.density)
            setPadding(dp(20), dp(10), dp(8), dp(10)); alpha = 0f; translationY = dp(14).toFloat()
        }
        bar.addView(text(noEmoji(L.t(msg)), 14f, Color.WHITE, MED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gone = Runnable { bar.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(bar) }.start() }
        bar.addView(text("Undo", 14f, Color.parseColor("#FF9A5C"), SEMI).apply {
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
    /** Tab ids in the order they appear (and swipe): Today, Food, Plan, History. */
    /** Tabs in the order they appear (and swipe). Social (5) sits before Profile unless it's switched off. */
    internal val order: List<Int> get() = listOf(0, 1, 2, 3)
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
        aurora = Backdrop(this, BG, dark, ORANGE).apply { still = Prefs.reduceMotion(this@MainActivity); scrollRef = scroll }
        root.addView(aurora, i.coerceAtLeast(0), FrameLayout.LayoutParams(MATCH, MATCH))
        val mi = pageHost.indexOfChild(morph)
        pageHost.removeView(morph)
        morph = MorphView(this, BG).apply { visibility = View.GONE; ring = BLUE; warm = ORANGE }
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
    // Tabs: 0 Today, 1 Chat, 2 Memory, 3 Me (same order as the bar).

    internal val backStack = mutableListOf<() -> Unit>()
    /** Set inside a sheet's primary action to keep the sheet open (e.g. input didn't validate). */
    internal var keepSheet = false
    internal val liveAnims = mutableListOf<android.animation.Animator>()
    internal val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var busy = false
    private var imeOpen = false
    private lateinit var composer: LinearLayout
    private lateinit var composerField: EditText
    private var lastTodaySig = ""
    private var returnedFromSystem = false
    private var modelProgressView: TextView? = null

    private data class Msg(val fromUser: Boolean, var text: String, val source: String)
    companion object {
        private val chat = mutableListOf<Msg>()
        private const val REQ_MODEL = 11; private const val REQ_VOICE = 12; private const val REQ_IMPORT = 13; private const val REQ_NOTIF = 14
    }

    private val density get() = resources.displayMetrics.density
    private fun tabFor(name: String?) = when (name) { "chat" -> 1; "memory" -> 2; "settings", "me" -> 3; "today" -> 0; else -> null }

    /** Back closes the topmost overlay first, then returns to Today, then leaves the app. */
    internal fun handleBack() {
        when {
            backStack.isNotEmpty() -> backStack.removeAt(backStack.size - 1)()
            tab != 0 -> switchTab(0, -1)
            else -> finish()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.zone = ZoneId.systemDefault()
        Reminders.channels(this)
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
        aurora = Backdrop(this, BG, dark, ORANGE).apply { still = Prefs.reduceMotion(this@MainActivity) }
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
        morph = MorphView(this, BG).apply { visibility = View.GONE; ring = BLUE; warm = ORANGE }
        pageHost.addView(morph, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(pageHost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        // Atlas: the chat box floats over the page above the bar, on the Chat tab only
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
        if (Store.read(this) { it.settings.avatarOn }) AvatarService.start(this)
        autoLoadModel()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", tab); super.onSaveInstanceState(outState) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFor(intent.getStringExtra("tab"))?.let { if (it != tab) { tab = it; render() } }
    }

    override fun onResume() {
        super.onResume()
        AvatarService.setAppInFront(this, true)
        if (!Prefs.reduceMotion(this)) Tilt.start(this)
        if (::aurora.isInitialized) aurora.resume()
        // the avatar may have just been allowed in system settings
        if (Store.read(this) { it.settings.avatarOn } && AvatarService.canShow(this) && !AvatarService.running) AvatarService.start(this)
        if (returnedFromSystem && tab == 3) render(keepScroll = true)
        returnedFromSystem = false
        ticker.removeCallbacks(tick); ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick); returnedFromSystem = true
        AvatarService.setAppInFront(this, false)
        Tilt.stop(); if (::aurora.isInitialized) aurora.pause()
        super.onPause()
    }

    override fun onDestroy() { scope.cancel(); tts?.shutdown(); super.onDestroy() }

    /** Every 30 seconds: move the engine on, and redraw Today if something on it changed. */
    private val tick = object : Runnable {
        override fun run() {
            scope.launch {
                withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                if (tab == 0 && currentFocus !is EditText && todaySignature() != lastTodaySig && !switching) render(keepScroll = true)
            }
            ticker.postDelayed(this, 30_000)
        }
    }

    private fun todaySignature(): String = Store.read(this) { s ->
        LocalDate.now(Engine.zone).toString() + s.tasks.joinToString("|") { "${it.id}:${it.status}:${it.version}" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tab == 3) render(keepScroll = true)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // "System" follows the phone's dark mode: swap the look in place (Stoa)
        val wasDark = dark
        applyPalette()
        if (dark != wasDark) relook()
    }

    private fun autoLoadModel() {
        if (ModelManager.ready || ModelManager.modelName(this) == null) return
        if (ModelManager.lastLoadCrashed(this)) { showBanner("The last model load didn't finish. Load it from Me.", MUTED); return }
        scope.launch { ModelManager.load(applicationContext); if (tab == 3 || tab == 1) render(keepScroll = true) }
    }

    internal fun render(keepScroll: Boolean = false) {
        liveAnims.forEach { it.cancel() }; liveAnims.clear()
        val y = scroll.scrollY
        renderNav()
        body.removeAllViews()
        body.clipToPadding = true
        heroFlame = null; heroNum = null
        window.statusBarColor = washTop()
        placeComposer()
        body.setPadding(body.paddingLeft, dp(24), body.paddingRight, dp(if (tab == 1) 190 else 112))
        when (tab) {
            0 -> renderToday()
            1 -> renderChat()
            2 -> renderMemory()
            else -> renderMe()
        }
        if (tab == 0) lastTodaySig = todaySignature()
        if (!keepScroll && !switching) enterPage()
        if (!Prefs.haptics(this)) { fun off(v: View) { v.isHapticFeedbackEnabled = false; if (v is ViewGroup) for (i in 0 until v.childCount) off(v.getChildAt(i)) }; off(body); off(nav) }
        scroll.post { if (tab == 1 && !keepScroll) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0, if (keepScroll) y else 0) }
    }

    /** The round button beside the bar: talk to Atlas. */
    internal fun talk() {
        if (tab != 1) switchTab(1, 1)
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
        addView(text(value, 14f, BLUE, HEAD))
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
    }

    private fun dayLabel(ms: Long) = Brain.dayLabel(ms).replaceFirstChar { it.uppercase() }
    private fun whenLabel(ms: Long?) = ms?.let { "${dayLabel(it)} ${Reminders.fmt(it)}" } ?: "Unscheduled"

    /** Pick a day from the next week, then a time (Stoa's time sheet). */
    private fun dateTimeSheet(title: String, initial: Long, onPick: (Long) -> Unit) {
        val start = Instant.ofEpochMilli(initial).atZone(Engine.zone)
        val today = LocalDate.now(Engine.zone)
        var day = start.toLocalDate().takeIf { !it.isBefore(today) } ?: today
        val days = (0..7).map { today.plusDays(it.toLong()) }
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
        heroFlame?.celebrate()
        AvatarService.celebrate(this)
        haptic()
    }

    // ================================================================ TODAY

    /** Ember's mood from the day: Stoa's moods, read from Atlas's tasks. */
    private fun mood(s: AtlasState, now: Long): String {
        val today = LocalDate.now(Engine.zone)
        fun isToday(ms: Long?) = ms != null && Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate() == today
        val open = s.tasks.count { it.status in Engine.OPEN && (it.startAt == null || isToday(it.startAt)) }
        return when {
            s.tasks.any { it.status == "awaiting" } -> "worried"
            Engine.inQuiet(s.settings, now) -> "sleepy"
            open == 0 && s.tasks.any { it.status == "done" && isToday(it.completedAt) } -> "happy"
            else -> "ready"
        }
    }

    /** The last 14 days as Stoa's colonnade: all done is a full column, part done is slimmer, a skip is broken. */
    private fun days14(s: AtlasState): List<ColonnadeView.Day> {
        val today = LocalDate.now(Engine.zone)
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate()
        val first = s.log.minOfOrNull { it.at }?.let { day(it) } ?: today
        val done = s.log.filter { it.type == "done" }.groupingBy { day(it.at) }.eachCount()
        val missed = s.log.filter { it.type == "skipped" }.groupingBy { day(it.at) }.eachCount()
        return (13 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val k = done[d] ?: 0; val m = missed[d] ?: 0
            when {
                d.isBefore(first) -> ColonnadeView.Day.FUTURE
                d == today && k == 0 -> ColonnadeView.Day.OPEN
                k > 0 && m == 0 -> ColonnadeView.Day.KEPT
                k > 0 -> ColonnadeView.Day.LOW
                m > 0 -> ColonnadeView.Day.MISSED
                else -> ColonnadeView.Day.REST
            }
        }
    }

    private fun renderToday() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(Engine.zone)
        fun isToday(ms: Long?) = ms != null && Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate() == today
        val s = snapshot()
        val st = s.settings
        val awaiting = s.tasks.filter { it.status == "awaiting" }.sortedBy { it.promptedAt }
        val active = s.tasks.filter { it.status == "active" }.sortedBy { it.startAt }
        val blocked = s.tasks.filter { it.status == "blocked" }
        val later = s.tasks.filter { it.status == "planned" && isToday(it.startAt) }.sortedBy { it.startAt }
        val upcoming = s.tasks.filter { it.status == "planned" && it.startAt != null && !isToday(it.startAt) && it.startAt!! > now }.sortedBy { it.startAt }.take(20)
        val unscheduled = s.tasks.filter { it.status == "planned" && it.startAt == null }
        val doneToday = s.tasks.filter { it.status == "done" && isToday(it.completedAt) }
        val leftToday = awaiting.size + active.size + later.size
        val clashes = Engine.collisions(s)

        // Hero, laid out as Stoa's: the wordmark and date, the look switch and a ring; the number with Ember; the colonnade
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("Atlas", 17f, FG, DISPLAY).apply { letterSpacing = -0.01f })
        names.addView(text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", L.locale())), 13f, MUTED), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        bar.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(ThemeSwitch(this, dark, MED).apply { onChange = { d -> swapTheme(d, this) }; k = 0.78f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val total = doneToday.size + leftToday
        val ring = FrameLayout(this).apply { contentDescription = "${doneToday.size} of $total done today" }
        ring.addView(RingView(this, if (dark) Color.argb(40, 255, 255, 255) else Color.argb(30, 0, 0, 0), GOLD, FG, MUTED).apply {
            showLabel = false; passMark = 0f; setProgress(if (total > 0) doneToday.size.toFloat() / total else 0f, animate = false)
        }, FrameLayout.LayoutParams(dp(46), dp(46)))
        ring.addView(text("${doneToday.size}", 15f, FG, DISPLAY).apply { gravity = Gravity.CENTER; includeFontPadding = false }, FrameLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(ring)
        hero.addView(bar)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val allDone = leftToday == 0 && doneToday.isNotEmpty()
        val num = text(leftToday.toString(), 112f, if (leftToday > 0 || allDone) FG else MUTED, THIN).apply {
            includeFontPadding = false; letterSpacing = -0.06f
            if (allDone) gradientText(Color.parseColor("#FFD27A"), ORANGE)
            fitLine(48f)
        }
        heroNum = num
        col.addView(num)
        col.addView(text(when { allDone -> "all done today"; leftToday == 1 -> "task left today"; else -> "tasks left today" }, 16f, FG, MED).apply { setPadding(0, dp(8), 0, 0) })
        val next = (active.firstOrNull() ?: later.firstOrNull())
        col.addView(text(when {
            awaiting.isNotEmpty() -> "I need an answer on ${awaiting.size}"
            active.isNotEmpty() -> "Now: ${active.first().title}"
            next != null -> "Next: ${next.title} at ${Reminders.fmt(next.startAt!!)}"
            upcoming.isNotEmpty() -> "Next: ${upcoming.first().title}, ${whenLabel(upcoming.first().startAt)}"
            else -> "Nothing scheduled"
        }, 13f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val flame = FlameView(this).apply {
            tier = Tiers.list[2]; lit = true; dayTint = false
            face = true; shades = false; arms = true
            mood = mood(s, now)
            isClickable = true; contentDescription = "Ember"
            setOnClickListener { react(); showBanner(heroLine(s, now)) }
        }
        heroFlame = flame
        row.addView(flame, LinearLayout.LayoutParams(dp(96), dp(118)).apply { bottomMargin = dp(4) })
        hero.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        val days = days14(s)
        val colonnade = ColonnadeView(this, if (dark) Color.parseColor("#ECEAE4") else Color.parseColor("#141413"),
            if (dark) Color.parseColor("#8F8D87") else Color.parseColor("#57493B"), ORANGE, dark).apply {
            this.days = days
            contentDescription = "The last 14 days: ${days.count { it == ColonnadeView.Day.KEPT }} all done, ${days.count { it == ColonnadeView.Day.LOW }} partly done, ${days.count { it == ColonnadeView.Day.MISSED }} skipped"
            setOnClickListener { switchTab(2, 1) }
        }
        hero.addView(colonnade, LinearLayout.LayoutParams(MATCH, dp(74)).apply { topMargin = dp(18) })
        val lab = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lab.addView(text(today.minusDays(13).format(DateTimeFormatter.ofPattern("d MMM", L.locale())), 11f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        lab.addView(text("Today", 11f, if (allDone) MUTED else ORANGE))
        hero.addView(lab, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        body.add(hero)

        // quick add
        var quick = ""
        val qf = field("Gym at 6pm for 1h", "") { quick = it }.apply { imeOptions = EditorInfo.IME_ACTION_DONE; setSingleLine(true) }
        qf.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { quickAdd(quick); true } else false }
        val qrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qrow.addView(qf, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        qrow.addView(primary("Add") { quickAdd(quick) }.apply { minHeight = dp(52); setPadding(dp(22), dp(13), dp(22), dp(13)) })
        body.add(qrow, 22)
        body.add(secondary("New Task") { editTask(null) }, 8)

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
        section("Now", active); section("Blocked", blocked); section("Later Today", later)
        section("Coming Up", upcoming); section("Unscheduled", unscheduled); section("Done Today", doneToday)
    }

    /** A copy of the lists to draw from, so a reminder firing meanwhile can't change them mid-draw. */
    private fun snapshot(): AtlasState = Store.read(this) { s ->
        AtlasState(s.deviceId).also { c -> c.tasks.addAll(s.tasks); c.log.addAll(s.log); c.memories.addAll(s.memories); c.settings = s.settings }
    }

    /** After a look change the chat box is rebuilt in the new colours. */
    internal fun restyleComposer() {
        if (!::composer.isInitialized) return
        val root = composer.parent as? ViewGroup ?: return
        val i = root.indexOfChild(composer); val lp = composer.layoutParams
        val draft = composerField.text.toString()
        root.removeView(composer)
        composer = buildComposer(); composerField.setText(draft)
        root.addView(composer, i, lp)
        placeComposer()
    }

    private fun heroLine(s: AtlasState, now: Long): String = when (mood(s, now)) {
        "worried" -> "Something needs your answer."
        "sleepy" -> "Quiet hours. I'll keep watch."
        "happy" -> "Everything for today is done."
        else -> "Ready when you are."
    }

    private fun quickAdd(v: String) {
        val t = v.trim()
        if (t.isEmpty()) { editTask(null); return }
        val now = System.currentTimeMillis()
        val p = Engine.parseQuick(t, now)
        if (p.title.isBlank()) { showBanner("Give the task a title.", MUTED); return }
        Store.edit(this) { Engine.create(it, p.title, now, p.startAt, p.durationMin ?: 30) }
        scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
        showBanner(if (p.startAt != null) "Added for ${whenLabel(p.startAt)}" else "Added to Unscheduled")
        render(keepScroll = true)
    }

    private fun promptFor(st: com.anjy.atlas.core.Settings, t: Task) = when (st.tone) {
        "gentle" -> "How did “${t.title}” go?"
        "challenging" -> "Time is up on “${t.title}”. Did you do it?"
        else -> "“${t.title}” was due to finish. What's the status?"
    }

    private fun askCard(st: com.anjy.atlas.core.Settings, t: Task): View {
        val c = card()
        c.background = StoneDrawable(SURFACE, dp(26).toFloat(), dark, density, edge = if (dark) ORANGE else null, page = BG)
        c.addView(eyebrow("Check-In").apply { setTextColor(ORANGE) })
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
                "unblock" -> showBanner("Moved to Unscheduled")
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
            if (sameDay) Reminders.fmt(at) else Instant.ofEpochMilli(at).atZone(Engine.zone).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + "\n" + Reminders.fmt(at)
        } ?: "—"
        r.addView(text(whenTxt, 15f, if (t.startAt != null) FG else MUTED, NUM), LinearLayout.LayoutParams(dp(70), WRAP))
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
        if (t.id in clashes) mid.addView(text("Overlaps another task", 13f, ORANGE, MED))
        r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        val (label, colr) = when (t.status) {
            "active" -> "Now" to ORANGE; "awaiting" -> "Answer" to ORANGE; "blocked" -> "Blocked" to MISS
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
        if (t.status == "active" || t.status == "awaiting") acts.add("Mark Done" to { respond(t.id, "done") })
        if (t.status == "blocked") acts.add("Unblock" to { respond(t.id, "unblock") })
        if (t.status in Engine.OPEN) {
            acts.add("Edit" to { body.post { editTask(t) } })
            acts.add("Cancel Task" to { Store.edit(this) { Engine.cancel(it, t.id, now) }; reschedule() })
        }
        acts.add("Delete" to {
            body.post {
                sheet(null, "Delete This Task?", t.title, primaryLabel = "Delete", primaryColor = MISS, onPrimary = {
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
            dateTimeSheetOver(startAt ?: (System.currentTimeMillis() + 60 * Engine.MIN)) { ms -> startAt = ms; whenBtn.text = whenLabel(ms) }
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
        sheet(null, if (t == null) "New Task" else "Edit Task", null, content = col, primaryLabel = if (t == null) "Add Task" else "Save", onPrimary = {
            if (recurrence != "none" && startAt == null) { keepSheet = true; showBanner("A repeating task needs a day and time.", MUTED); return@sheet }
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

    /** The day-and-time picker opened from inside another sheet: it stacks on top of it. */
    private fun dateTimeSheetOver(initial: Long, onPick: (Long) -> Unit) = dateTimeSheet("When", initial, onPick)

    // ================================================================ CHAT

    private fun buildComposer(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; elevation = dp(8).toFloat() }
        val mic = FrameLayout(this).apply {
            background = quietBg(); isClickable = true; contentDescription = "Speak"
            addView(LineIcon(this@MainActivity, "mic").apply { color = quietInk() }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { voice() }
            pressFx()
        }
        composerField = EditText(this).apply {
            hint = "Message Atlas"; setTextColor(FG); setHintTextColor(MUTED); textSize = 16f; typeface = BODY
            background = rounded(if (dark) SURFACE2 else Color.parseColor(LIGHT_FIELD), 999, LINE)
            setPadding(dp(18), dp(13), dp(18), dp(13)); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        val send = FrameLayout(this).apply {
            background = finished(BLUE); isClickable = true; contentDescription = "Send"
            addView(LineIcon(this@MainActivity, "arrow").apply { color = ON; rotation = -90f }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { submit() }
            pressFx()
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(composerField, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    /** The chat box sits above the bar; with the keyboard open the bar steps aside and the box rests on the keyboard. */
    private fun placeComposer() {
        if (!::composer.isInitialized) return
        composer.visibility = if (tab == 1) View.VISIBLE else View.GONE
        nav.visibility = if (imeOpen && tab == 1) View.GONE else View.VISIBLE
        (composer.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = dp(if (imeOpen) 10 else 86); composer.layoutParams = it }
    }

    private fun renderChat() {
        val status = text(if (ModelManager.ready) (ModelManager.modelName(this) ?: "Model ready") else "No model loaded", 13f, MUTED)
        pageTitle("Chat")
        body.add(status, 2)
        if (chat.isEmpty()) chat.add(Msg(false, if (ModelManager.ready) "What do you need?" else "What do you need? Say “help” to see what works without a model.", "Atlas"))
        chat.forEach { body.add(bubble(it), 10) }
    }

    private fun bubble(m: Msg): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (m.fromUser) Gravity.END else Gravity.START }
        val tv = text(m.text, 16f, if (m.fromUser) ON else FG, keepEmoji = true).apply {
            background = if (m.fromUser) Finish(BLUE, dp(22).toFloat(), dark, density) else StoneDrawable(SURFACE, dp(22).toFloat(), dark, density, page = BG)
            setPadding(dp(16), dp(11), dp(16), dp(12)); setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        wrap.addView(tv)
        if (!m.fromUser) wrap.addView(text(m.source, 12f, MUTED, MED).apply { setPadding(dp(8), dp(4), 0, 0) })
        return wrap
    }

    private fun submit() {
        val q = composerField.text.toString().trim()
        if (q.isEmpty() || busy) return
        composerField.setText("")
        sendMessage(q)
    }

    private fun sendMessage(q: String) {
        if (busy) { showBanner("Atlas is still answering.", MUTED); return }
        if (tab != 1) { tab = 1; render() }
        chat.add(Msg(true, q, "You"))
        body.add(bubble(chat.last()), 10)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        busy = true; heroFlame?.mood = "ready"
        scope.launch {
            val fixed = try { withContext(Dispatchers.IO) { Brain.route(applicationContext, q) } } catch (e: Exception) { Reply("That failed: ${e.message}", "Atlas") }
            val reply = if (fixed != null) fixed else {
                val pending = Msg(false, "…", "Local model")
                chat.add(pending)
                val pendingView = bubble(pending)
                body.add(pendingView, 10)
                val tv = (pendingView as LinearLayout).getChildAt(0) as TextView
                try {
                    Brain.ask(applicationContext, q) { partial -> tv.text = partial; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { Reply("The model failed: ${e.message}", "Atlas")
                } finally { chat.remove(pending); (pendingView.parent as? ViewGroup)?.removeView(pendingView) }
            }
            chat.add(Msg(false, reply.text, reply.source))
            if (chat.size > 200) chat.subList(0, chat.size - 200).clear()
            if (tab == 1) { body.add(bubble(chat.last()), 10); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
            busy = false
            if (reply.text.startsWith("Marked")) celebrate()
            speak(reply.text)
        }
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Atlas")
        try { startActivityForResult(i, REQ_VOICE) }
        catch (e: ActivityNotFoundException) { showBanner("No speech recognizer is installed on this phone.", MISS) }
    }

    private fun speak(s: String) {
        if (!Store.read(this) { it.settings.speakReplies }) return
        val t = tts
        if (t == null) tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas") }
        else t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "atlas")
    }

    // ================================================================ MEMORY

    private fun renderMemory() {
        pageTitle("Memory")
        body.add(text("What Atlas remembers. It stays on this phone.", 15f, MUTED), 6)
        var v = ""
        val f = field("Something to remember", "") { v = it }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(f, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        r.addView(primary("Save") {
            val t = v.trim()
            if (t.isNotEmpty()) { Store.edit(this) { Engine.remember(it, t, System.currentTimeMillis()) }; ModelManager.markStale(); showBanner("Saved"); render(keepScroll = true) }
        }.apply { minHeight = dp(52); setPadding(dp(22), dp(13), dp(22), dp(13)) })
        body.add(r, 16)

        val mems = Store.read(this) { it.memories.toList().reversed() }
        body.add(eyebrow("Saved · ${mems.size}"), 16)
        val c = card().apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
        if (mems.isEmpty()) c.addView(text("Nothing saved yet. In Chat, say “remember …”.", 15f, MUTED).apply { setPadding(0, dp(14), 0, dp(14)) })
        mems.forEachIndexed { i, m ->
            if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
            c.addView(text(m.text, 16f, FG, keepEmoji = true).apply {
                setPadding(0, dp(14), 0, dp(14)); isClickable = true
                setOnClickListener {
                    sheet(null, "Forget This?", m.text, primaryLabel = "Forget", primaryColor = MISS, onPrimary = {
                        Store.edit(this@MainActivity) { Engine.forget(it, m.id, System.currentTimeMillis()) }; ModelManager.markStale(); render(keepScroll = true)
                    }, secondaryLabel = "Keep")
                }
            })
        }
        body.add(c, 8)

        val log = Store.read(this) { it.log.takeLast(60).reversed().map { l -> Triple(l.at, l.type, l.title + if (l.detail.isNotBlank() && l.type in setOf("blocked", "skipped", "note", "progress")) " (${l.detail})" else "") } }
        if (log.isNotEmpty()) {
            body.add(eyebrow("Activity"), 16)
            val a = card().apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
            val names = mapOf("created" to "Added", "edited" to "Edited", "deleted" to "Deleted", "cancelled" to "Cancelled", "started" to "Started",
                "reminded" to "Reminded", "checkin" to "Asked", "followup" to "Followed up", "done" to "Done", "progress" to "Still working",
                "delayed" to "Delayed", "unblocked" to "Unblocked", "blocked" to "Blocked", "skipped" to "Skipped", "note" to "Note",
                "remembered" to "Remembered", "forgot" to "Forgot")
            log.forEach { (at, type, title) ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
                line.addView(text(Reminders.fmt(at), 13f, MUTED, NUM), LinearLayout.LayoutParams(dp(58), WRAP))
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

        // Brain
        body.add(eyebrow("Brain"), 16)
        val brain = card()
        brain.addView(text(ModelManager.modelName(this) ?: "No Model", 20f, FG, HEAD, keepEmoji = true))
        brain.add(text(ModelManager.status, 14f, MUTED), 4)
        val progress = text("", 14f, ORANGE, MED, keepEmoji = true)
        brain.add(progress, 4)
        modelProgressView = progress
        brain.add(primary("Choose Model File") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MODEL)
        }, 16)
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        br.addView(secondary(if (ModelManager.ready) "Reload" else "Load") {
            progress.text = "Loading…"; scope.launch { ModelManager.load(applicationContext); render(keepScroll = true) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        br.addView(secondary("Remove") {
            sheet(null, "Remove the Model?", ModelManager.modelName(this) ?: "Model file", primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
                scope.launch(Dispatchers.IO) { ModelManager.remove(applicationContext); withContext(Dispatchers.Main) { render(keepScroll = true) } }
            }, secondaryLabel = "Keep")
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        brain.add(br, 8)
        body.add(brain, 8)

        // You
        body.add(eyebrow("You"), 16)
        val you = card()
        you.add(settingRow("Name", st.name.ifBlank { "Not set" }) {
            var v = st.name
            sheet(null, "Your Name", null, content = field("Name", v) { v = it }, primaryLabel = "Save", onPrimary = {
                Store.edit(this) { it.settings.name = v.trim().take(40) }; ModelManager.markStale(); render(keepScroll = true)
            }, secondaryLabel = "Cancel")
        })
        val tones = listOf("gentle" to "Supportive", "firm" to "Firm", "challenging" to "Challenging")
        you.add(settingRow("Check-In Tone", tones.firstOrNull { it.first == st.tone }?.second ?: "Firm") {
            val i = tones.indexOfFirst { it.first == st.tone }
            Store.edit(this) { it.settings.tone = tones[(i + 1) % tones.size].first }; render(keepScroll = true)
        }, 8)
        you.add(switchRow(null, "Speak Replies", st.speakReplies) { on -> Store.edit(this) { it.settings.speakReplies = on } }, 8)
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
        rem.add(text("After the last follow-up a task stays marked as needing an answer. It's never marked done or skipped for you.", 13f, MUTED), 10)
        body.add(rem, 8)

        // Avatar
        body.add(eyebrow("Avatar"), 16)
        val av = card()
        val overlayOk = AvatarService.canShow(this)
        if (!overlayOk) {
            av.addView(text("Ember can float over your other apps. Android needs your permission first.", 15f, MUTED))
            av.add(primary("Allow Display Over Apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, 14)
        } else av.addView(switchRow(null, "Ember On Screen", st.avatarOn) { on ->
            Store.edit(this) { it.settings.avatarOn = on }
            if (on) AvatarService.start(this) else AvatarService.stop(this)
        })
        body.add(av, 8)

        // Reliability
        body.add(eyebrow("Make Reminders Reliable"), 16)
        val rel = card()
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(android.app.AlarmManager::class.java)
        val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        rel.add(settingRow("Notifications", if (notifOk) "Allowed" else "Blocked") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) })
        rel.add(settingRow("Exact Alarms", if (am.canScheduleExactAlarms()) "Allowed" else "Not allowed") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 8)
        rel.add(settingRow("Battery", if (pm.isIgnoringBatteryOptimizations(packageName)) "Unrestricted" else "Optimised") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, 8)
        rel.add(settingRow("App Info", "Open") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, 8)
        rel.add(text("On OPPO phones, also allow background activity and auto-launch in App Info → Battery.", 13f, MUTED), 10)
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
        bk.addView(text("Your data lives only on this phone.", 15f, MUTED))
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
            REQ_VOICE -> {
                val said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                sendMessage(said)
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
                        showBanner("Restored $n tasks", DONE)
                    } catch (e: Exception) { showBanner("Restore failed: ${e.message}", MISS) }
                    render(keepScroll = true)
                }
            }
        }
    }
}
