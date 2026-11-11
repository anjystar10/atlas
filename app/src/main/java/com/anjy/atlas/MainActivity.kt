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
    /** The one warm colour (amber): only for what needs your eye. */
    internal var WARM = Color.parseColor("#F2B866")
    /** The accent for small text and links (a lighter tint at night, so it reads on dark glass). */
    internal var ACCENT_INK = Color.parseColor("#A594FF")
    /** Quieter than MUTED: the smallest notes. */
    internal var FAINT = Color.parseColor("#7C81AD")
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
        // every colour comes from the one palette (Design.kt); both looks are set in full every time, so switching
        // back from light to dark (without restarting the screen) puts every colour back
        val p = if (dark) Palette.DARK else Palette.LIGHT
        BG = p.bg; SURFACE = p.surface; SURFACE2 = p.surface2; LINE = p.line; FG = p.fg; MUTED = p.muted; FAINT = p.faint
        DONE = p.done; MISS = p.danger; ACCENT = p.accent; ACCENT_INK = p.accentInk; GOLD = p.blue; WARM = p.warm
        if (!dark) {
            // by day: frosted glass panels on a lavender-blue page, navy-black ink, dark glass buttons
            ON = Color.WHITE; BLUE = p.fg
            STEEL = Color.parseColor("#E6E7F8"); NAVBG = p.fg; BTN2 = p.fg
        } else {
            ON = Color.parseColor("#06061A"); BLUE = p.fg
            STEEL = p.surface2; NAVBG = Color.parseColor("#15162E"); BTN2 = p.surface
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
    // Type (see Design.kt): Newsreader, a serif made for reading, for titles, the greeting, numbers, Atlas's
    // replies and what you write; Geist for the small working parts (buttons, labels, tags).
    internal val THIN by lazy { Type.serifLight(this) }
    internal val NUM by lazy { Type.serifLight(this) }
    internal val DISPLAY by lazy { Type.serif(this) }
    internal val WORDMARK by lazy { Type.serifMedium(this) }
    /** Reading text: Atlas's replies, your messages, what you type. */
    internal val READ by lazy { Type.serif(this) }
    internal val READ_BOLD by lazy { Type.serifSemi(this) }
    internal val READ_ITALIC by lazy { Type.serifItalic(this) }
    /** Titles. Small "headings" (under 18sp) are set in Geist SemiBold (see text()). */
    internal val HEAD get() = DISPLAY
    internal val SEMI by lazy { Type.sansSemi(this) }
    internal val MED by lazy { Type.sansMedium(this) }
    internal val BODY by lazy { Type.sans(this) }
    internal val MONO by lazy { Type.mono(this) }
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
        if (f === HEAD || f === NUM || f === THIN) letterSpacing = if (size >= 30f) -0.015f else -0.005f
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
            if (multi) {
                // a writing box: starts at two lines, grows to six, then scrolls inside, the text kept within its edges
                minLines = 2; maxLines = 6; gravity = Gravity.TOP or Gravity.START
                isVerticalScrollBarEnabled = true; setHorizontallyScrolling(false)
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
    internal val order: List<Int> get() = listOf(0, 1, 3, 2)   // Atlas, Log, Bots, Me (ids stay: 2 is Me)
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
    private var voiceInput: Hearing? = null
    /** Which service the Brain card in Me is showing. */
    private var brainShown: String? = null

    companion object {
        private const val REQ_MODEL = 11; private const val REQ_IMPORT = 13; private const val REQ_NOTIF = 14; private const val REQ_MIC = 15; private const val REQ_MIC_WAKE = 16; private const val REQ_MEDIA = 17
        const val AI_STUDIO_KEYS = "https://aistudio.google.com/apikey"
    }

    private val density get() = resources.displayMetrics.density
    private fun tabFor(name: String?) = when (name) {
        "atlas", "chat", "today", "home" -> 0; "log", "memory" -> 1; "settings", "me" -> 2; "bots", "bot" -> 3; else -> null
    }

    /** Back closes the topmost overlay first, then returns to Atlas, then leaves the app. */
    internal fun handleBack() {
        when {
            backStack.isNotEmpty() -> backStack.removeAt(backStack.size - 1)()
            tab == 1 && logFull -> { logFull = false; render() }
            tab != 0 -> switchTab(0, -1)
            else -> finish()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private val onChat: () -> Unit = { chatChanged() }
    /** A sync finished: the Log and Me show what came in. */
    private val onSync: () -> Unit = { if (tab != 0) render(keepScroll = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.zone = ZoneId.systemDefault()
        Reminders.channels(this)
        Conversation.load(applicationContext)
        Bots.init(applicationContext)
        Link.init(applicationContext); LinkTasks.watchJobs()   // >>> device link: report on jobs the laptop sent
        if (Link.reachable(this)) LinkService.start(this)
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

    // >>> device link: Me follows the laptop coming online, tasks and control (not while something's being typed)
    private val onLink: () -> Unit = { if (tab == 2 && currentFocus !is android.widget.EditText) render(keepScroll = true) }

    override fun onResume() {
        super.onResume()
        AvatarService.setAppInFront(this, true)
        Conversation.listen(onChat)
        Bots.listen(onBots)
        Sync.listen(onSync); Sync.syncSoon(this, 500); Sync.setForeground(this, true)
        if (Sync.linked(this)) SyncJob.schedule(this)
        Link.listen(onLink); Link.setForeground(this, true)   // >>> device link: listening while Atlas is open
        resumedNow = true
        Connections.asker = connectionsAsker(); refreshConnections()   // >>> connections: chat asks here; tools checked again
        if (!Prefs.reduceMotion(this)) Tilt.start(this)
        if (::aurora.isInitialized) aurora.resume()
        if (returnedFromSystem && tab != 0) render(keepScroll = true)
        else if (tab == 0) chatChanged()
        returnedFromSystem = false
        ticker.removeCallbacks(tick); ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick); returnedFromSystem = true
        resumedNow = false
        Connections.asker = null   // >>> connections: nothing on screen to ask with
        Conversation.unlisten(onChat); Bots.unlisten(onBots); Sync.unlisten(onSync); Sync.setForeground(this, false)
        Link.unlisten(onLink); Link.setForeground(this, false)   // >>> device link
        stopVoice()
        AvatarService.setAppInFront(this, false)
        Tilt.stop(); if (::aurora.isInitialized) aurora.pause()
        super.onPause()
    }

    override fun onDestroy() { scope.cancel(); memoPlayer?.release(); memoPlayer = null; super.onDestroy() }

    /** Every 30 seconds: move the engine on, and redraw the Log if something on it changed. */
    private val tick = object : Runnable {
        override fun run() {
            scope.launch {
                withContext(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                val sig = logSignature()
                if (sig != lastLogSig && currentFocus !is EditText && !switching) {
                    lastLogSig = sig
                    if (tab == 1) render(keepScroll = true)
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
            3 -> renderBots()
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
            Conversation.online(this) -> "${Conversation.provider(this)?.name} · $phone"
            ModelManager.ready -> "Offline model · ${ModelManager.modelName(this) ?: ""}".trimEnd(' ', '·')
            else -> "No brain set up yet · add an API key in Me"
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
        botStrip()


        if (msgs.isEmpty()) {
            val orb = OrbView(this).apply { still = Prefs.reduceMotion(this@MainActivity); isClickable = true; contentDescription = "Talk to Atlas"; setOnClickListener { voice() } }
            homeOrb = orb
            body.add(orb, 14, dp(184), dp(184))
            (orb.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            body.add(text(if (name.isNotEmpty()) "Hey $name,\nWhat can I help with?" else "Hey there,\nWhat can I help with?", 29f, FG, DISPLAY).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.05f)
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
            if (!Conversation.online(this)) body.add(text("To think properly Atlas needs an API key (Gemini's is free): Me → Brain.", 13f, MUTED).apply { gravity = Gravity.CENTER }, 18)
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

    // ---------------------------------------------------------------- Atlas Bot

    private var botChip: TextView? = null
    private fun botLine(): String = BotRules.summary(Bots.jobs, System.currentTimeMillis())

    /** A small glass strip under the top bar while bots are working (or recently done): tap to see them. */
    private fun botStrip() {
        botChip = null
        val line = botLine()
        if (line.isEmpty()) return
        val chip = text("Atlas Bot  ·  $line  ›", 13f, FG, MED, keepEmoji = true).apply {
            background = finished(SURFACE, -1f, glass = true); setPadding(dp(14), dp(8), dp(14), dp(9)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; isClickable = true; contentDescription = "Atlas Bot: $line"
            setOnClickListener { startActivity(Intent(this@MainActivity, BotActivity::class.java)) }
            pressFx()
        }
        botChip = chip
        body.add(chip, 10, WRAP)
    }

    /** A bot moved on: update the strip in place, or redraw the Atlas tab when it appears or goes. */
    private val onBots: () -> Unit = {
        if (tab == 0 && ::composer.isInitialized) {
            val line = botLine()
            val chip = botChip
            if ((chip == null) != line.isEmpty()) render(keepScroll = true)
            else chip?.let { it.text = "Atlas Bot  ·  $line  ›"; it.contentDescription = "Atlas Bot: $line" }
        }
        // the Bots tab follows along, unless you're in the middle of typing a job
        if (tab == 3 && botDraft.isBlank()) render(keepScroll = true)
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
        // Atlas may have switched the theme itself (change_setting): follow it
        Store.getString(this, "theme2")?.let { want -> if ((want == "dark" && !dark) || (want == "light" && dark)) { relook(); return } }
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

    /** How replies are set: Geist for reading, the serif for big headings, the violet for list markers and links. */
    private fun mdStyle() = Markdown.Style(READ, READ_BOLD, WORDMARK, MONO, FG, MUTED, ACCENT_INK,
        if (dark) Color.argb(40, 255, 255, 255) else Color.argb(26, 10, 11, 31), density, Type.READ, italic = READ_ITALIC)

    /**
     * One message. Yours: a compact glass bubble on the right. Atlas's: neat full-width text (no box, so long
     * answers get the whole width), with headings, lists and code laid out properly.
     */
    private fun bubble(m: Conversation.Msg, last: Boolean): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (m.fromUser) Gravity.END else Gravity.START }
        val working = !m.fromUser && last && Conversation.busy
        val copy = { _: View -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas", m.text)); showBanner("Copied"); true }
        if (m.fromUser) {
            if (m.media.isNotEmpty()) wrap.addView(mediaStrip(m.media, dp(84)), LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })
            wrap.addView(text(m.text, Type.READ, FG, READ, keepEmoji = true).apply {
                setLineSpacing(0f, 1.3f)
                background = Finish(SURFACE, dp(20).toFloat(), dark, density, glass = true)
                setPadding(dp(15), dp(10), dp(15), dp(11))
                maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
                setOnLongClickListener(copy)
            })
            return wrap
        }
        if (m.text.isBlank()) {
            wrap.addView(text(if (working) "${Conversation.status ?: "Thinking"}…" else "…", 15f, MUTED, MED).apply { setPadding(dp(2), dp(4), 0, dp(4)) })
        } else {
            val st = mdStyle()
            Markdown.parse(m.text, st).forEachIndexed { i, block ->
                val v: View = when (block) {
                    is Markdown.Prose -> TextView(this).apply {
                        text = block.text; setTextColor(if (m.source == "error") MISS else FG); textSize = Type.READ; typeface = READ
                        setLineSpacing(0f, Type.LEAD_READ); linksClickable = true; movementMethod = android.text.method.LinkMovementMethod.getInstance()
                        setOnLongClickListener(copy)
                    }
                    is Markdown.Code -> codeBox(block)
                }
                wrap.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = dp(10) })
            }
        }
        // pictures Atlas made, full width, with Save and Share
        m.media.filter { it.image && it.file.exists() }.forEach { item -> wrap.addView(madePicture(item), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }) }
        // a voice memo from a bot: a play key with its length
        m.media.filter { it.mime.startsWith("audio/") && it.file.exists() }.forEach { item -> wrap.addView(voiceMemo(item), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) }) }
        // what Atlas did, in one quiet line (tap for all of it)
        if (m.steps.isNotEmpty()) {
            val shown = if (m.steps.size <= 3) m.steps.joinToString(" · ") else m.steps.take(2).joinToString(" · ") + " · and ${m.steps.size - 2} more"
            wrap.addView(text(shown, 13f, MUTED, keepEmoji = true).apply { setPadding(0, dp(8), 0, 0); isClickable = true
                setOnClickListener { sheet(null, "What Atlas Did", m.steps.joinToString("\n") { "· $it" }, primaryLabel = "Close") } })
        }
        val tag = when {
            working && m.text.isNotBlank() -> "${Conversation.status ?: "Working"}…"
            m.source == "error" || m.source == "Atlas" -> ""
            else -> m.source
        }
        if (tag.isNotEmpty()) wrap.addView(text(tag, 12f, MUTED, MED).apply { setPadding(0, dp(6), 0, 0); alpha = 0.8f })
        return wrap
    }

    private var memoPlayer: android.media.MediaPlayer? = null
    private var memoPlaying: String? = null

    /** A voice memo: a glass pill with Play / Stop and its length; one plays at a time. */
    private fun voiceMemo(item: Attach.Item): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Finish(SURFACE, dp(22).toFloat(), dark, density, glass = true); setPadding(dp(16), dp(10), dp(18), dp(10))
            isClickable = true; contentDescription = "Play the voice memo"
        }
        val label = text(if (memoPlaying == item.path) "■  Stop" else "▶  Play Voice Memo", 15f, ACCENT, SEMI)
        val len = text("", 13f, MUTED).apply { setPadding(dp(10), 0, 0, 0) }
        pill.addView(label); pill.addView(len)
        scope.launch {
            val ms = withContext(Dispatchers.IO) { try { android.media.MediaMetadataRetriever().run { setDataSource(item.path); val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(); release(); d } } catch (_: Exception) { null } }
            if (ms != null) len.text = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        }
        pill.setOnClickListener {
            val was = memoPlaying
            memoPlayer?.let { p -> try { p.stop() } catch (_: Exception) {}; p.release() }
            memoPlayer = null; memoPlaying = null
            if (was != item.path) {
                Voice.hush()
                try {
                    memoPlayer = android.media.MediaPlayer().apply {
                        setDataSource(item.path)
                        setOnCompletionListener { mp -> mp.release(); if (memoPlayer === mp) { memoPlayer = null; memoPlaying = null; render(keepScroll = true) } }
                        prepare(); start()
                    }
                    memoPlaying = item.path
                } catch (e: Exception) { memoPlayer = null; showBanner("Couldn't play it") }
            }
            render(keepScroll = true)
        }
        return pill
    }

    /** A picture Atlas made: shown whole, rounded, with Save (to Pictures/Atlas) and Share under it. */
    private fun madePicture(item: Attach.Item): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val img = android.widget.ImageView(this).apply {
            adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(SURFACE2)
            maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt(); minimumHeight = dp(160)
            clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat()) } }
            contentDescription = "Picture Atlas made"
        }
        box.addView(img, LinearLayout.LayoutParams(MATCH, WRAP))
        scope.launch { val b = withContext(Dispatchers.IO) { Attach.thumb(item, 1280) }; if (b != null) { img.minimumHeight = 0; img.setImageBitmap(b) } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(text("Save", 13f, ACCENT, SEMI).apply { isClickable = true; setPadding(0, dp(4), dp(18), dp(4))
            setOnClickListener { scope.launch { val ok = withContext(Dispatchers.IO) { ImageGen.saveToGallery(applicationContext, item.file, item.mime) != null }; showBanner(if (ok) "Saved to Pictures/Atlas" else "Couldn't save it") } } })
        row.addView(text("Share", 13f, ACCENT, SEMI).apply { isClickable = true; setPadding(0, dp(4), dp(18), dp(4))
            setOnClickListener { scope.launch { val ok = withContext(Dispatchers.IO) { ImageGen.saveToGallery(applicationContext, item.file, item.mime) }
                if (ok == null) showBanner("Couldn't share it") else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, ok).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share picture")) } } })
        box.addView(row)
        return box
    }

    /** A code block: its own box, monospaced, scrolling sideways, with a Copy key. */
    private fun codeBox(c: Markdown.Code): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) Color.argb(46, 255, 255, 255) else Color.argb(30, 10, 11, 31), 14, LINE)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(c.lang.ifBlank { "code" }, 12f, MUTED, MED), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(text("Copy", 12f, ACCENT, SEMI).apply { isClickable = true; setPadding(dp(8), dp(2), 0, dp(2))
            setOnClickListener { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", c.text)); showBanner("Copied") } })
        box.addView(head)
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(this).apply { text = c.text; typeface = MONO; textSize = 13.5f; setTextColor(FG); setLineSpacing(0f, 1.25f); setTextIsSelectable(true) })
        box.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        return box
    }

    /** Pictures, videos and files as small tiles ([onRemove] adds a remove key to each, for ones waiting to be sent). */
    private fun mediaStrip(items: List<Attach.Item>, size: Int, onRemove: ((Attach.Item) -> Unit)? = null): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, it ->
            val cell = FrameLayout(this).apply { clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) } } }
            if (it.isFile) {
                // a file: its type and name on a glass tile
                val f = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = Finish(SURFACE, dp(14).toFloat(), dark, density, glass = true) }
                f.addView(text(if (it.pdf) "PDF" else it.name.substringAfterLast('.', "TXT").uppercase().take(4), 12f, ACCENT, SEMI).apply { gravity = Gravity.CENTER })
                f.addView(text(it.name, 11f, FG).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                cell.addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                val img = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SURFACE2) }
                cell.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
                scope.launch { val b = withContext(Dispatchers.IO) { Attach.thumb(it, size) }; if (b != null) img.setImageBitmap(b) }
                if (it.video) cell.addView(text("▶", 18f, Color.WHITE).apply { setShadowLayer(8f, 0f, 0f, Color.BLACK) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }
            if (onRemove != null) cell.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(170, 0, 0, 0)) }
                addView(LineIcon(this@MainActivity, "close").apply { color = Color.WHITE }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
                isClickable = true; contentDescription = "Remove"; setOnClickListener { _ -> onRemove(it) }
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            row.addView(cell, LinearLayout.LayoutParams(size, size).apply { if (i > 0) leftMargin = dp(6) })
        }
        return row
    }

    // ---------------------------------------------------------------- the ask bar

    /** Pictures, a video or files waiting to be sent with the next message. */
    private val pending = mutableListOf<Attach.Item>()
    private var tray: LinearLayout? = null

    private fun buildComposer(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; elevation = dp(8).toFloat() }
        val t = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(dp(4), 0, 0, dp(8)) }
        tray = t
        outer.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val attach = FrameLayout(this).apply {
            background = quietBg(); isClickable = true; contentDescription = "Add a picture, video or file"
            addView(LineIcon(this@MainActivity, "plus").apply { color = quietInk() }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { choose("Add to Your Message", listOf("Photos & Videos", "Files (PDF, Word, text)")) { i -> if (i == 0) pickMedia() else pickFiles() } }
            pressFx()
        }
        composerField = EditText(this).apply {
            hint = "Ask anything…"; setTextColor(FG); setHintTextColor(MUTED); textSize = Type.READ; typeface = READ
            background = rounded(if (dark) SURFACE2 else Color.parseColor(LIGHT_FIELD), 999, LINE)
            setPadding(dp(18), dp(13), dp(18), dp(13)); maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
            // pasted (or keyboard-inserted) pictures, videos and files are attached
            setOnReceiveContentListener(arrayOf("image/*", "video/*", "application/pdf", "text/*")) { _, payload ->
                // items that are files get attached; plain pasted text is left for the box to take as usual
                val clip = payload.clip
                var tookAll = true
                val uris = mutableListOf<Uri>()
                for (i in 0 until clip.itemCount) { val u = clip.getItemAt(i).uri; if (u != null) uris.add(u) else tookAll = false }
                if (uris.isNotEmpty()) addMedia(uris)
                if (tookAll) null else payload
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateSend() }
            })
        }
        val icon = LineIcon(this@MainActivity, "mic").apply { color = Color.WHITE; tag = "mic" }
        sendIcon = icon
        val send = FrameLayout(this).apply {
            // Atlas's key: a glowing drop of blue and violet. Nothing typed: talk. Something to send: send. Working: stop.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(34).toFloat()
                colors = intArrayOf(Palette.DARK.primaryTop, Palette.DARK.primaryBottom)
            }
            elevation = dp(8).toFloat(); outlineSpotShadowColor = Color.parseColor("#5B6CFF"); outlineAmbientShadowColor = Color.parseColor("#5B6CFF")
            isClickable = true; contentDescription = "Speak"
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { when (sendIcon?.tag) { "stop" -> Conversation.stop(); "arrow" -> submit(); else -> voice() } }
            pressFx()
        }
        bar.addView(attach, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(composerField, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        outer.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        showTray()
        return outer
    }

    private fun roomLeft(): Boolean {
        if (pending.any { it.video } || pending.size >= Attach.MAX_PHOTOS) { showBanner("That's the limit for one message.", MUTED); return false }
        return true
    }

    private fun pickMedia() {
        if (!roomLeft()) return
        try {
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, (Attach.MAX_PHOTOS - pending.size).coerceAtLeast(2)), REQ_MEDIA)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_MEDIA)
        }
    }

    private fun pickFiles() {
        if (!roomLeft()) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "application/json", "application/xml", "application/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_MEDIA)
    }

    /**
     * Takes picked or pasted pictures, videos and files, within the limits. One at a time, in order: copying takes a
     * moment, and the limits are checked against what's already been taken (several at once would all pass the check).
     */
    private fun addMedia(uris: List<Uri>) {
        scope.launch {
            for (uri in uris) {
                try {
                    val isVideo = contentResolver.getType(uri)?.startsWith("video/") == true
                    if (isVideo && pending.isNotEmpty()) { showBanner("A video goes on its own. Send the others first.", MUTED); continue }
                    if (pending.any { it.video } || pending.size >= Attach.MAX_PHOTOS) { showBanner("Up to ${Attach.MAX_PHOTOS} per message.", MUTED); break }
                    val item = withContext(Dispatchers.IO) { Attach.take(applicationContext, uri) }
                    pending.add(item); showTray(); updateSend()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Attach.TooBig) { showBanner(e.message ?: "Too big.", MUTED)
                } catch (e: Exception) { showBanner("Couldn't add that.", MUTED) }
            }
        }
    }

    private fun showTray() {
        val t = tray ?: return
        t.removeAllViews()
        if (pending.isEmpty()) { t.visibility = View.GONE; return }
        t.visibility = View.VISIBLE
        t.addView(mediaStrip(pending.toList(), dp(64)) { item -> pending.remove(item); item.file.delete(); showTray(); updateSend() })
        if (pending.any { it.video }) t.addView(text("Video: up to ${Attach.VIDEO_MAX_SECONDS}s · Gemini", 12f, MUTED).apply { setPadding(dp(10), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(WRAP, dp(64)))
    }

    /** The round key: stop while Atlas works, send when there's something to send, else talk. */
    private fun updateSend() {
        val old = sendIcon ?: return
        val parent = old.parent as? FrameLayout ?: return
        val typed = ::composerField.isInitialized && composerField.text.isNotBlank()
        val want = when { Conversation.busy -> "stop"; typed || pending.isNotEmpty() -> "arrow"; else -> "mic" }
        if (old.tag == want) return
        parent.removeView(old)
        val icon = LineIcon(this, want).apply { color = Color.WHITE; rotation = if (want == "arrow") -90f else 0f; tag = want }
        parent.addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        parent.contentDescription = when (want) { "stop" -> "Stop"; "arrow" -> "Send"; else -> "Speak" }
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
        if (q.isEmpty() && pending.isEmpty()) return
        val media = pending.toList(); pending.clear(); showTray()
        composerField.setText("")
        sendMessage(q, media = media)
    }

    private fun sendMessage(q: String, spoken: Boolean = false, media: List<Attach.Item> = emptyList()) {
        if (tab != 0) { tab = 0; render() }
        // a spoken question keeps the conversation going: once Atlas has finished answering aloud, it listens again
        // (not if you cut the answer off, left the app, or switched Keep Listening off)
        val again: (() -> Unit)? = if (spoken && Hear.handsFree(this)) ({
            body.postDelayed({ if (resumedNow && tab == 0 && voiceInput == null && !Conversation.busy && !Voice.busy && talkTurns < 30) voice(followUp = true) }, 450)
        }) else null
        Conversation.send(this, q, spoken, onSpoken = again, media = media)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- listening (Atlas's own, no Google pop-up)

    private var closeVoiceSheet: (() -> Unit)? = null

    private var talkTurns = 0
    private var resumedNow = false

    /** Listens. [followUp]: Atlas has just answered aloud and is listening for more; silence then simply ends the talk. */
    private fun voice(followUp: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        if (voiceInput != null) { voiceInput?.finish(); return }
        if (!followUp) talkTurns = 0
        Conversation.hush()
        AvatarService.holdMic(this, true)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val orb = OrbView(this).apply { energy = 1f; still = Prefs.reduceMotion(this@MainActivity) }
        col.addView(orb, LinearLayout.LayoutParams(dp(170), dp(170)))
        val heard = text(if (followUp) "Listening… say “that's all” when you're done" else "Listening…", 18f, MUTED, MED, keepEmoji = true).apply { gravity = Gravity.CENTER; minLines = 2 }
        col.add(heard, 8)
        var got: String? = null
        val vi = Hear.open(this,
            onPartial = { t -> heard.text = t; heard.setTextColor(FG); got = t },
            onLevel = { l -> orb.level = l },
            onDone = { t ->
                stopVoice()
                when {
                    t.isNullOrBlank() -> if (!followUp) showBanner("I didn't catch that.", MUTED)   // quiet after an answer: the talk is over
                    followUp && Speech.endsTalk(t) -> Unit                                          // "that's all": nothing is sent
                    else -> { talkTurns++; sendMessage(t, spoken = true) }
                }
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
        AvatarService.holdMic(this, false)
        homeOrb?.energy = if (Conversation.busy) 1f else 0f
    }

    // ================================================================ LOG
    // What Atlas keeps: reminders and things to remember. "Remember Something" on top, then the latest
    // [LOG_RECENT] entries; everything under Full History.
    private val LOG_RECENT = 4

    /** Showing every entry rather than the latest few. */
    private var logFull = false

    private class Entry(val at: Long, val task: Task?, val memory: com.anjy.atlas.core.Memory?)

    private fun entries(s: AtlasState): List<Entry> =
        (s.tasks.map { Entry(it.createdAt, it, null) } + s.memories.map { Entry(it.createdAt, null, it) }).sortedByDescending { it.at }

    private fun logged(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(Engine.zone)
        return if (d.toLocalDate() == LocalDate.now(Engine.zone)) "Today ${Reminders.fmt(ms)}" else d.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()))
    }

    private fun entryRow(e: Entry): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)); gravity = Gravity.CENTER_VERTICAL; isClickable = true }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = e.task; val m = e.memory
        if (t != null) {
            mid.addView(text(Plain.memory(t.title), 16f, if (t.status in setOf("done", "skipped", "cancelled")) MUTED else FG, MED, keepEmoji = true))
            val meta = mutableListOf<String>()
            t.startAt?.let { meta.add("⏰ ${whenLabel(it)}") }
            if (t.recurrence != "none") meta.add(t.recurrence.replaceFirstChar { it.uppercase() })
            if (t.notes.isNotBlank()) meta.add(Plain.memory(t.notes))
            meta.add("Logged ${logged(t.createdAt)}")
            mid.addView(text(meta.joinToString(" · "), 13f, MUTED, keepEmoji = true))
        } else if (m != null) {
            mid.addView(text(Plain.memory(m.text), 16f, FG, keepEmoji = true))
            mid.addView(text("Remembered · ${logged(m.createdAt)}", 13f, MUTED))
        }
        r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        val (label, colr) = when {
            m != null -> "Memory" to GOLD
            t == null -> "" to MUTED
            t.status == "done" -> "Doing it" to DONE
            t.status == "skipped" -> "Not doing" to MUTED
            t.status == "cancelled" -> "Cancelled" to MUTED
            t.status == "planned" && t.startAt != null && t.remindedAt == null -> "Upcoming" to ACCENT
            t.status == "planned" && t.startAt != null -> "Rang" to MUTED
            else -> "Note" to MUTED
        }
        if (label.isNotEmpty()) r.addView(text(label, 12f, colr, SEMI).apply {
            background = Finish(SURFACE, -1f, dark, density, glass = true); setPadding(dp(10), dp(4), dp(10), dp(5))
        })
        r.setOnClickListener { if (t != null) taskActions(t) else if (m != null) forgetSheet(m) }
        return r
    }

    private fun forgetSheet(m: com.anjy.atlas.core.Memory) {
        sheet(null, "Forget This?", m.text, primaryLabel = "Forget", primaryColor = MISS, onPrimary = {
            Store.edit(this@MainActivity) { Engine.forget(it, m.id, System.currentTimeMillis()) }; ModelManager.markStale(); render(keepScroll = true)
        }, secondaryLabel = "Keep")
    }

    private fun taskActions(t: Task) {
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        val now = System.currentTimeMillis()
        val reschedule = { scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }; render(keepScroll = true) }
        acts.add("Edit" to { body.post { editTask(t) } })
        if (t.status == "planned") acts.add("Cancel It" to { Store.edit(this) { Engine.cancel(it, t.id, now) }; reschedule() })
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
        var recurrence = t?.recurrence ?: "none"
        var notes = t?.notes ?: ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.add(field("What", title) { title = it })
        col.add(eyebrow("Alarm"), 16)
        val whenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var whenBtn: TextView
        whenBtn = secondary(whenLabel(startAt)) {
            dateTimeSheet("When", startAt ?: (System.currentTimeMillis() + 60 * Engine.MIN)) { ms -> startAt = ms; whenBtn.text = whenLabel(ms) }
        }
        whenRow.addView(whenBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        whenRow.addView(secondary("No Alarm") { startAt = null; whenBtn.text = whenLabel(null) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        col.add(whenRow, 8)
        col.add(eyebrow("Repeats"), 16)
        col.add(chipGroup(listOf("none" to "Never", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly"), { it == recurrence }) { recurrence = it }, 8)
        col.add(eyebrow("Notes"), 16)
        col.add(field("Optional", notes, multi = true) { notes = it }, 8)
        sheet(null, if (t == null) "New Reminder" else "Edit Reminder", null, content = col, primaryLabel = if (t == null) "Add" else "Save", onPrimary = {
            if (recurrence != "none" && startAt == null) { keepSheet = true; showBanner("A repeating reminder needs a day and time.", MUTED); return@sheet }
            if (startAt != null && startAt!! < System.currentTimeMillis() - Engine.MIN && t == null) { keepSheet = true; showBanner("That time has passed.", MUTED); return@sheet }
            val now = System.currentTimeMillis()
            try {
                Store.edit(this) { s ->
                    if (t == null) Engine.create(s, title, now, startAt, 15, notes, "normal", recurrence)
                    else Engine.update(s, t.id, title, now, startAt, t.durationMin, notes, t.priority, recurrence).also { if (startAt != null && startAt!! > now) { it.status = "planned"; it.remindedAt = null } }
                }
                scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
                render(keepScroll = true)
            } catch (e: Exception) { keepSheet = true; showBanner(e.message ?: "Couldn't save.", MISS) }
        }, secondaryLabel = "Cancel")
    }

    private fun renderLog() {
        val s = snapshot()
        val all = entries(s)
        if (logFull) {
            pageTitle("Full History", roundIcon("close", "Back to the Log") { logFull = false; render() }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) })
            body.add(text("Everything Atlas has logged, newest first (${all.size}).", 15f, MUTED), 6)
        } else {
            pageTitle("Log", roundIcon("plus", "New reminder") { editTask(null) }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) })
            body.add(text("What Atlas is keeping for you. Reminders ring like an alarm at their time.", 15f, MUTED), 6)
        }
        if (!logFull) {
            // Remember Something first: the field gets the whole width, the Save key sits under it
            body.add(eyebrow("Remember Something"), 20)
            val mc = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            var v = ""
            lateinit var save: TextView
            val f = field("Something to remember", "", multi = true) { v = it; save.alpha = if (it.isBlank()) 0.5f else 1f }
            mc.addView(f, LinearLayout.LayoutParams(MATCH, WRAP))
            save = primary("Save to Memory") {
                val t = Plain.memory(v)
                if (t.isNotEmpty()) { Store.edit(this) { Engine.remember(it, t, System.currentTimeMillis()) }; ModelManager.markStale(); showBanner("Saved"); render(keepScroll = true) }
            }.apply { alpha = 0.5f }
            mc.addView(save, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            body.add(mc, 8)
            if (all.isEmpty()) {
                // nothing yet: the box above still works (it used to be hidden until something was logged)
                body.add(card().apply {
                    addView(text("Nothing logged yet", 18f, FG, HEAD))
                    add(text("Tell Atlas “remind me to pick up my sister Thursday at 4” or “remember my locker code is on the fridge”, and it lands here.", 15f, MUTED), 6)
                }, 22)
                return
            }
            body.add(eyebrow("Latest"), 22)
        } else if (all.isEmpty()) return
        val shown = if (logFull) all else all.take(LOG_RECENT)
        val c = card().apply { setPadding(dp(18), dp(4), dp(18), dp(4)) }
        shown.forEachIndexed { i, e ->
            if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
            c.addView(entryRow(e))
        }
        body.add(c, if (logFull) 16 else 8)
        if (!logFull) body.add(secondary(if (all.size > LOG_RECENT) "Full History (${all.size})" else "Full History") { logFull = true; render() }, 12)
    }

    // ================================================================ THIS DEVICE

    /** What this phone has right now, and what Atlas will (and won't) run on it because of that. */
    private fun deviceCard() {
        val d = try { DeviceProfiler.read(this) } catch (e: Exception) { return }
        fun gb(mb: Long) = if (mb < 0) "?" else "%.1f GB".format(mb / 1024.0)
        body.add(eyebrow("This Device"), 16)
        val c = card()
        c.addView(text(d.model.ifBlank { "This phone" }, 18f, FG, HEAD, keepEmoji = true))
        val chip = listOfNotNull(d.chip.ifBlank { null }, if (d.cores > 0) "${d.cores} cores" + (if (d.bigCores in 1 until d.cores) " (${d.bigCores} fast)" else "") else null).joinToString(" · ")
        val lines = listOfNotNull(
            "Memory: ${gb(d.availRamMb)} free of ${gb(d.totalRamMb)}",
            if (chip.isNotEmpty()) "Chip: $chip" else null,
            if (d.graphics.isNotBlank()) "Graphics: ${d.graphics}" else null,
            "Heat: ${ModelDirector.thermalWord(d.thermal)}",
            "Battery: " + (if (d.batteryPct < 0) "unknown" else "${d.batteryPct}%") + (if (d.charging) ", charging" else "") + (if (d.powerSave) ", Battery Saver on" else ""),
            if (d.freeStorageMb >= 0) "Storage: ${gb(d.freeStorageMb)} free" else null)
        c.add(text(lines.joinToString("\n"), 14f, MUTED), 6)
        ModelDirector.holdingBack(d)?.let { c.add(text(it, 14f, WARM, MED), 10) }

        val name = ModelManager.modelName(this)
        if (name == null) c.add(text("No offline model yet. When you add one, Atlas checks it fits this phone's free memory before every load.", 14f, MUTED), 10)
        else {
            val f = ModelManager.modelFile(this)
            val spec = DeviceGuard.cachedSpec(this, f, name)
            if (spec == null) {
                // first time for this file: read what it needs in the background, then show it
                c.add(text("Offline model: checking what $name needs…", 14f, MUTED, keepEmoji = true), 10)
                scope.launch(Dispatchers.IO) { DeviceGuard.spec(applicationContext, f, name); withContext(Dispatchers.Main) { if (tab == 2) render(keepScroll = true) } }
            } else {
                val rec = DeviceGuard.record(this, spec)
                val v = ModelDirector.decide(d, spec, rec, DeviceGuard.force(this))
                c.add(text("Offline model", 15f, FG, MED), 12)
                c.add(text((if (v.allowed) "Will run here. " else "Held back. ") + v.reason, 14f, if (v.allowed) MUTED else WARM, keepEmoji = true), 4)
                c.add(text("Needs about ${gb(v.peakMb)} at its peak (the file, plus memory for an 8,192-token conversation, plus working space)." +
                    (if (!spec.known) " The file didn't state its layout, so this is an estimate." else ""), 13f, MUTED), 4)
                if (rec != null && rec.tokensPerSec > 0) c.add(text("Last ran here at about ${"%.0f".format(rec.tokensPerSec)} tokens a second" +
                    (if (rec.firstTokenMs > 0) ", first word after ${"%.1f".format(rec.firstTokenMs / 1000.0)} s" else "") +
                    (if (rec.loadMs > 0) ", loaded in ${"%.0f".format(rec.loadMs / 1000.0)} s" else "") + ".", 13f, MUTED), 4)
                if (rec != null && rec.died > 0) c.add(text("It has stopped ${rec.died} time${if (rec.died == 1) "" else "s"} on this phone.", 13f, MUTED), 4)
                c.add(switchRow(null, "Run It Anyway", DeviceGuard.force(this)) { on -> DeviceGuard.setForce(this, on); render(keepScroll = true) }, 12)
                c.add(text("On: Atlas loads the model even when memory is tight or Battery Saver is on. It still won't when the phone is hot or the model can't fit at all. The model runs apart from Atlas, so the worst case is the model stopping, not Atlas.", 13f, MUTED), 6)
            }
        }
        c.add(text("The offline model is put away after 10 minutes unused, or when Android says memory is short, and loads again when needed. These checks stay on this phone.", 13f, MUTED), 10)
        body.add(c, 8)
    }

    // ================================================================ BOTS

    private var botDraft = ""
    /** The Bots tab: send a bot, and every job with where it stands. A job opens on its own page. */
    private fun renderBots() {
        pageTitle("Bots")
        body.add(text("Jobs Atlas sent a bot to do. They keep working when you leave, and each report lands in your chat.", 15f, MUTED), 6)
        val sc = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        lateinit var send: TextView
        val f = field("Send a bot to… (e.g. find the three best budget laptops for coding, with sources)", botDraft, multi = true) { botDraft = it; send.alpha = if (it.isBlank()) 0.5f else 1f }
        sc.addView(f, LinearLayout.LayoutParams(MATCH, WRAP))
        send = primary("Send a Bot") {
            val goal = botDraft.trim()
            if (goal.isNotEmpty()) { botDraft = ""; Bots.deploy(applicationContext, goal, ""); showBanner("Bot sent"); render(keepScroll = true) }
        }.apply { alpha = if (botDraft.isBlank()) 0.5f else 1f }
        sc.addView(send, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        body.add(sc, 20)
        // >>> library: everything bots made (LibraryActivity)
        body.add(card().apply {
            isClickable = true; pressFx(); contentDescription = "Library: documents, spreadsheets, pages and downloads your bots made"
            setOnClickListener { startActivity(LibraryActivity.intent(this@MainActivity)) }
            addView(text("Library  ›", 17f, FG, HEAD))
            add(text("Documents, spreadsheets, pages and downloads your bots made", 13f, MUTED, MED), 4)
        }, 12)
        // <<< library
        // routines, skills and the specialist team (BotExtrasActivity)
        val ex = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Routines" to "routines", "Skills" to "skills", "Team" to "team").forEachIndexed { i, (l, pg) ->
            ex.addView(secondary(l) { startActivity(BotExtrasActivity.intent(this, pg)) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        body.add(ex, 12)
        val all = Bots.visible()
        if (all.isEmpty()) {
            body.add(card().apply {
                addView(text("No bots yet", 18f, FG, HEAD))
                add(text("Ask Atlas for something bigger, like “research the best phone under $300 for photos and send me a report”, or say “send a bot to…”.", 15f, MUTED), 6)
            }, 22)
            return
        }
        body.add(eyebrow("Jobs"), 22)
        all.forEach { j ->
            val c = card().apply {
                isClickable = true; pressFx()
                setOnClickListener { startActivity(Intent(this@MainActivity, BotActivity::class.java).putExtra(BotActivity.EXTRA_ID, j.id)) }
            }
            c.addView(text(j.goal, 17f, FG, HEAD).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            val line = listOfNotNull(BotRules.label(j.state), if (j.state == BotRules.RUNNING) j.current else null,
                if (j.plan.isNotEmpty()) "plan ${j.plan.count { it.done }}/${j.plan.size}" else null).joinToString(" · ")
            c.add(text(line, 13f, MUTED, MED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, 6)
            body.add(c, 10)
        }
    }

    // ================================================================ ME

    private fun renderMe() {
        val st = Store.read(this) { it.settings }
        pageTitle("Me", ThemeSwitch(this, dark, MED).apply { onChange = { d -> swapTheme(d, this) } })

        // Brain: any AI service, with the user's own key
        body.add(eyebrow("Brain"), 16)
        val brain = card()
        val active = Providers.active(this)
        val shown = Providers.byId(brainShown) ?: active ?: Providers.all.first()
        brainShown = shown.id
        brain.addView(text(active?.let { "${it.name} · ${Providers.model(this, it)}" } ?: "Not set up", 20f, FG, HEAD))
        brain.add(text(if (active != null) "Atlas thinks with ${active.name}. Add as many keys as you like: when one runs out, Atlas switches to the next and carries on${if (KeyRouter.dontSwitch(this)) "." else ", then to another service."}"
            else "Paste a key from any AI service below. Atlas needs one to think properly and to use your phone.", 14f, if (active != null) DONE else MUTED), 4)
        var keyDraft = ""
        val kf = field("Paste an API key (${shown.keyHint})", "") { keyDraft = it }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true)
        }
        brain.add(kf, 14)
        brain.add(eyebrow("Service"), 12)
        brain.add(chipGroup(Providers.all.map { it.id to it.name }, { it == shown.id }) { id ->
            brainShown = id; render(keepScroll = true)
        }, 8)
        val kr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kr.addView(primary("Add Key") {
            val k = keyDraft.trim()
            if (k.length < 16) { showBanner("That doesn't look like a whole key.", MUTED); return@primary }
            // a recognisable key picks its own service; DeepSeek and custom keys look like OpenAI's, so the chip decides
            val id = Providers.guess(k)?.takeIf { g -> !(g == "openai" && shown.id in setOf("deepseek", "custom", "openrouter", "groq", "xai")) } ?: shown.id
            Providers.setKey(this, id, k); Providers.choose(this, id); brainShown = id
            showBanner("Key added to ${Providers.byId(id)?.name}. Testing it…"); testKey(k, id)
            render(keepScroll = true)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (shown.keyUrl.isNotEmpty()) kr.addView(secondary("Get a Key") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown.keyUrl))) } catch (_: Exception) { showBanner("Open ${shown.keyUrl} in your browser.", MUTED) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        brain.add(kr, 10)
        if (shown.id == "custom") brain.add(settingRow("Address", Providers.baseUrl(this, shown).ifEmpty { "Not set" }) {
            var v = Providers.baseUrl(this, shown)
            sheet(null, "Service Address", "The OpenAI-compatible address, ending in /v1 (for example https://api.example.com/v1).", content = field("https://…/v1", v) { v = it }, primaryLabel = "Save", onPrimary = {
                Store.putString(this, "base_custom", v.trim()); render(keepScroll = true)
            }, secondaryLabel = "Cancel")
        }, 10)
        brain.add(settingRow("Model", Providers.model(this, shown).ifEmpty { "Not set" }) {
            var m = Providers.model(this, shown)
            sheet(null, "${shown.name} Model", if (shown.defaultModel.isNotEmpty()) "Leave it as ${shown.defaultModel} unless you want another of ${shown.name}'s models." else "The model's name, as your service writes it.",
                content = field("Model name", m) { m = it }, primaryLabel = "Save", onPrimary = {
                    Providers.setModel(this, shown.id, m.trim().ifEmpty { shown.defaultModel }); render(keepScroll = true)
                }, secondaryLabel = "Cancel")
        }, 10)
        // the service's keys: each with its state; tap one to wake it or remove it
        val pool = Providers.keys(this, shown.id)
        if (pool.isNotEmpty()) {
            brain.add(eyebrow("${shown.name} Keys (${pool.size})"), 14)
            val kc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val now = System.currentTimeMillis()
            pool.forEachIndexed { i, e ->
                if (i > 0) kc.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); isClickable = true }
                val masked = if (e.key.length > 10) e.key.take(4) + "••••" + e.key.takeLast(4) else "••••"
                val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                words.addView(text(masked, 15f, FG, MONO))
                val state = when {
                    e.bad -> "Turned down" + if (e.problem.isNotBlank()) ": ${e.problem.take(70)}" else ""
                    e.restUntil > now -> "Ran out · back at ${Reminders.fmt(e.restUntil)}"
                    i == pool.indexOfFirst { it.usable(now) } && active?.id == shown.id -> "In use"
                    else -> "Ready"
                }
                words.addView(text(state, 12f, when { e.bad -> MISS; e.restUntil > now -> MUTED; else -> DONE }))
                row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.setOnClickListener {
                    choose(masked, listOf("Try It Again Now", "Remove This Key")) { pick ->
                        if (pick == 0) { Providers.wakeKey(this, shown.id, e.key); render(keepScroll = true) }
                        else { Providers.removeKey(this, shown.id, e.key); render(keepScroll = true) }
                    }
                }
                kc.addView(row)
            }
            brain.add(kc, 4)
            val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (active?.id != shown.id) tr.addView(secondary("Use ${shown.name}") { Providers.choose(this, shown.id); render(keepScroll = true) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            tr.addView(secondary("Test") { testKey(Providers.key(this, shown.id), shown.id) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            brain.add(tr, 8)
        }
        brain.add(switchRow(null, "Don't Switch Services", KeyRouter.dontSwitch(this)) { on -> KeyRouter.setDontSwitch(this, on); render(keepScroll = true) }, 14)
        brain.add(text(if (KeyRouter.dontSwitch(this)) "Atlas stays on ${active?.name ?: "the service you chose"}, even when it runs out or there's no internet. Your other ${active?.name ?: ""} keys can still take over.".replace("  ", " ")
            else "Off: when a service keeps failing (Atlas tries it 3 times first) or all its keys run out, Atlas moves to another service with a key and carries on.", 13f, MUTED), 6)
        brain.add(text("Keys stay on this phone (not in backups). When Atlas thinks online, what you say goes to that service; free tiers may use it to improve their products.", 13f, MUTED), 12)
        body.add(brain, 8)

        // Offline model
        body.add(eyebrow("Offline Brain"), 16)
        val off = card()
        off.addView(text(ModelManager.modelName(this) ?: "No Offline Model", 18f, FG, HEAD, keepEmoji = true))
        off.add(text((if (active != null) "Used only when there's no internet. It runs separately, so it can't close Atlas. " else "Runs separately, so it can't close Atlas. ") + ModelManager.status, 14f, MUTED), 4)
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
            if (ModelManager.ready) br.addView(secondary("Unload") { ModelManager.unload(this); render(keepScroll = true) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            br.addView(secondary("Remove") {
                sheet(null, "Remove the Model?", ModelManager.modelName(this) ?: "Model file", primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
                    scope.launch(Dispatchers.IO) { ModelManager.remove(applicationContext); withContext(Dispatchers.Main) { render(keepScroll = true) } }
                }, secondaryLabel = "Keep")
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            off.add(br, 8)
        }
        body.add(off, 8)
        deviceCard()

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
        val downloading = HotwordFiles.downloadState
        vc.addView(switchRow(null, "“Hey Atlas” (beta)", st.wakeWord && micOk && HotwordFiles.ready(this), enabled = downloading == null) { on ->
            if (!on) { Store.edit(this) { it.settings.wakeWord = false }; AvatarService.refresh(this); body.postDelayed({ if (tab == 2) render(keepScroll = true) }, 600); return@switchRow }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WAKE); return@switchRow
            }
            enableWake()
        })
        val hwState = AvatarService.hotwordState(this)
        val crash = Store.getString(this, "hotwordCrash")?.takeIf { it.isNotBlank() }
        vc.add(text(when {
            downloading != null -> "Getting the Hey Atlas model (${HotwordFiles.MODEL_MB} MB, once): $downloading…"
            !st.wakeWord && crash != null -> "Hey Atlas switched itself off because its speech engine crashed twice on this phone (Atlas itself was fine). Details are under Diagnostics. You can switch it on to try again."
            st.wakeWord && hwState == "listening" -> "Listening for “Hey Atlas”. Say it clearly; it's in beta and can miss. Atlas on screen grows and listens; when you stop talking it shrinks back. Android shows its green microphone dot while it listens, and it uses some battery."
            st.wakeWord && !AvatarService.canShow(this) -> "Atlas also needs “Display over other apps” (Atlas On Screen below) to answer over other apps."
            st.wakeWord -> "Starting… If it stays like this, tap “Start listening” on Atlas's notification."
            else -> "Say “Hey Atlas” from anywhere to ask something without opening the app. It's heard on the phone only; nothing is recorded or sent. Switching it on downloads a ${HotwordFiles.MODEL_MB} MB speech model once."
        }, 13f, MUTED), 8)
        vc.add(switchRow(null, "Speak Replies", st.speakReplies) { on -> Store.edit(this) { it.settings.speakReplies = on } }, 8)
        vc.add(text("Spoken questions always get a spoken answer.", 13f, MUTED), 6)
        vc.add(switchRow(null, "Natural Voice", Voice.natural(this)) { on -> Store.setFlag(this, "voice_natural", on); render(keepScroll = true) }, 10)
        val eng = Voice.engine(this)
        vc.add(text(when {
            !Voice.natural(this) -> "Using the phone's own voice."
            eng == "gemini" -> "A human-sounding voice from Gemini (uses your Gemini key)."
            eng == "openai" -> "A human-sounding voice from OpenAI (uses your OpenAI key)."
            else -> "Natural voices come from Gemini or OpenAI: add one of their keys. Until then Atlas uses the phone's best voice."
        } + (Voice.lastSpoke?.let { "\nLast reply was spoken by $it." } ?: "")
          + (Voice.lastProblem?.let { "\nThe natural voice failed last time ($it), so the phone's voice stood in." } ?: ""), 13f, MUTED), 6)
        if (eng != null) {
            // each voice in its own row: who it is, what it's like; tap to choose and hear it
            val list = if (eng == "gemini") Voice.geminiChoices else Voice.openAiChoices
            val chosen = Voice.current(this, eng).id
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            list.forEachIndexed { i, c ->
                if (i > 0) rows.addView(hairline(), LinearLayout.LayoutParams(MATCH, dp(1)))
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11)); isClickable = true }
                val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                words.addView(text(c.label, 16f, FG, if (c.id == chosen) SEMI else MED))
                words.addView(text(c.about, 13f, MUTED))
                r.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(text(if (c.id == chosen) "Chosen · Hear It" else "Hear It", 12f, if (c.id == chosen) ACCENT else MUTED, SEMI).apply {
                    background = Finish(SURFACE, -1f, dark, density, glass = true); setPadding(dp(12), dp(5), dp(12), dp(6))
                })
                r.setOnClickListener { Voice.preview(this, eng, c.id) { if (tab == 2) render(keepScroll = true) }; render(keepScroll = true) }
                rows.addView(r)
            }
            vc.add(rows, 8)
        }
        // how fast Atlas talks, how it hears, and whether a spoken chat keeps going
        vc.add(hairline(), 10, MATCH, dp(1))
        vc.add(text("Speaking Speed", 16f, FG, MED), 12)
        vc.add(chipGroup(listOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast"), { it == Voice.speed(this) }) { Voice.setSpeed(this, it); render(keepScroll = true) }, 8)
        vc.add(switchRow(null, "Keep Listening", Hear.handsFree(this)) { on -> Hear.setHandsFree(this, on) }, 14)
        vc.add(text("After Atlas answers a spoken question it listens again, so you can just keep talking. Say “that's all”, stay quiet, or tap Cancel to stop.", 13f, MUTED), 6)
        vc.add(text("Hearing", 16f, FG, MED), 14)
        vc.add(chipGroup(listOf("auto" to "Sharpest", "phone" to "Phone Only"), { it == Hear.mode(this) }) { Hear.setMode(this, it); render(keepScroll = true) }, 8)
        vc.add(text("Now: ${Hear.describe(this)}.", 13f, MUTED), 6)
        vc.add(text("Language You Speak", 16f, FG, MED), 14)
        vc.add(chipGroup(Hear.LANGUAGES, { it == (Hear.language(this) ?: "auto") }) { Hear.setLanguage(this, it); render(keepScroll = true) }, 8)
        vc.add(text("Telling Atlas the language makes it hear names and accents better. “Any” lets it work it out.", 13f, MUTED), 6)
        body.add(vc, 8)

        // >>> connections: MCP servers and web APIs (ConnectionsUi.kt)
        body.add(eyebrow("Connections"), 16)
        body.add(connectionsCard(), 8)
        // <<< connections

        // sign-ins Atlas Bot may use (VaultUi.kt)
        body.add(eyebrow("Vault"), 16)
        body.add(vaultCard(), 8)

        // what bots may do without asking (BotExtrasActivity)
        body.add(eyebrow("Atlas Bot"), 16)
        val bp = card()
        bp.addView(settingRow("Bot Permissions", "Ask, always or never, per kind of action") { startActivity(BotExtrasActivity.intent(this, "permissions")) })
        body.add(bp, 8)

        // Phone and PC: one Atlas
        body.add(eyebrow("Your Devices"), 16)
        val dv = card()
        val code = Sync.code(this)
        if (code == null) {
            dv.addView(text("Not linked", 18f, FG, HEAD))
            dv.add(text("Link Atlas on your PC and they share everything: reminders, memories, the Log and your conversation. It's encrypted with a private code before it leaves either device.", 14f, MUTED), 6)
            dv.add(primary("Link a New Device") {
                val c = Sync.newCode(); Sync.link(this, c)
                showBanner("Linked. Enter this code on your PC.", DONE); render(keepScroll = true)
            }, 14)
            dv.add(secondary("I Have a Code") {
                var typed = ""
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(field("ATLS-XXXX-XXXX-XXXX-XXXX-XXXX", "") { typed = it })
                sheet(null, "Enter Your Link Code", "From Atlas on your other device: Me → Your Devices.", content = col, primaryLabel = "Link", onPrimary = {
                    if (Sync.link(this, typed)) { showBanner("Linked. Syncing…", DONE); render(keepScroll = true) }
                    else { keepSheet = true; showBanner("That code doesn't look right. Check it and try again.", MISS) }
                }, secondaryLabel = "Cancel")
            }, 8)
        } else {
            dv.addView(text("Linked", 18f, FG, HEAD))
            dv.add(text(Sync.lastResult?.let { r -> "$r" + if (Sync.lastAt > 0) " · ${logged(Sync.lastAt)}" else "" } ?: "Syncing…", 14f, MUTED), 4)
            dv.add(text("Your link code (keep it private; type it into Atlas on your PC):", 13f, MUTED), 12)
            dv.add(text(code, 17f, FG, MONO).apply {
                background = rounded(if (dark) SURFACE2 else Color.parseColor(LIGHT_FIELD), 14, LINE); setPadding(dp(14), dp(12), dp(14), dp(12)); gravity = Gravity.CENTER
                setTextIsSelectable(true)
            }, 6)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(secondary("Copy Code") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas link code", code)); showBanner("Copied") }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(secondary("Sync Now") { scope.launch(Dispatchers.IO) { Sync.runOnce(applicationContext) } ; showBanner("Syncing…") }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            dv.add(row, 12)
            dv.add(secondary("Unlink This Phone") {
                sheet(null, "Unlink This Phone?", "It stops syncing. Everything stays on this phone; the PC keeps its copy.", primaryLabel = "Unlink", primaryColor = MISS, onPrimary = {
                    Sync.unlink(this); render(keepScroll = true)
                }, secondaryLabel = "Cancel")
            }, 8)
        }
        body.add(dv, 8)
        if (code != null) body.add(linkCard(), 8)   // >>> device link (LinkUi.kt): control the laptop, be controlled

        // Atlas on screen
        body.add(eyebrow("Atlas On Screen"), 16)
        val av = card()
        if (!AvatarService.canShow(this)) {
            av.addView(text("Atlas can float over your other apps. Tap it, or say “Hey Atlas”, and it grows and listens. Android needs your permission first.", 15f, MUTED))
            av.add(primary("Allow Display Over Apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, 14)
        } else {
            av.addView(switchRow(null, "Atlas On Screen", st.avatarOn) { on ->
                Store.edit(this) { it.settings.avatarOn = on }
                if (on || st.wakeWord) AvatarService.refresh(this) else AvatarService.stop(this)
            })
            av.add(text("Drag it anywhere. Tap it to talk; tap again to stop.", 13f, MUTED), 8)
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
        you.add(switchRow(null, "Reduce Motion", Prefs.reduceMotion(this)) { on ->
            Store.setFlag(this, "reduceMotion", on); aurora.still = on; render(keepScroll = true)
        }, 8)
        body.add(you, 8)

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
        rel.add(secondary("Test an Alarm in 1 Minute") {
            val now = System.currentTimeMillis()
            Store.edit(this) { Engine.create(it, "Atlas test alarm", now, now + 60_000, 5) }
            scope.launch(Dispatchers.IO) { Reminders.tickAndSchedule(applicationContext) }
            showBanner("Lock the phone. It should ring in a minute.")
        }, 14)
        body.add(rel, 8)

        // Diagnostics: how Atlas's parts last stopped, to report a crash exactly
        body.add(eyebrow("Diagnostics"), 16)
        val dg = card()
        val report = Diagnostics.report(this)
        val extra = listOfNotNull(Store.getString(this, "hotwordCrash")?.takeIf { it.isNotBlank() }?.let { "Hey Atlas switched off after: $it" }, ModelLink.died)
        dg.addView(text((extra + report).joinToString("\n"), 12f, MUTED, keepEmoji = true).apply { setTextIsSelectable(true) })
        dg.add(secondary("Copy Report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas diagnostics",
                "Atlas ${packageManager.getPackageInfo(packageName, 0).versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" + (extra + report).joinToString("\n")))
            showBanner("Report copied. Paste it to whoever is fixing Atlas.")
        }, 10)
        body.add(dg, 8)

        // Backup
        body.add(eyebrow("Backup"), 16)
        val bk = card()
        bk.addView(text("Your Log lives only on this phone. The backup doesn't include your keys.", 15f, MUTED))
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
        Store.putString(this, "hotwordCrash", "")
        if (HotwordFiles.ready(this)) {
            Store.edit(this) { it.settings.wakeWord = true }; AvatarService.refresh(this)
            body.postDelayed({ if (tab == 2) render(keepScroll = true) }, 1500)
            return
        }
        if (HotwordFiles.downloadState != null) return
        var shown = -1
        scope.launch {
            render(keepScroll = true)
            val err = withContext(Dispatchers.IO) {
                try {
                    HotwordFiles.download(applicationContext) { pct -> if (pct / 5 != shown / 5) { shown = pct; runOnUiThread { if (tab == 2) render(keepScroll = true) } } }
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
    /** Tries a key ([key], or the service's current one) with a tiny question; a key that fails is marked so in the list. */
    private fun testKey(key: String? = null, serviceId: String? = null) {
        val p = Providers.byId(serviceId) ?: Providers.active(this) ?: return
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                val sess = try { Providers.session(applicationContext, p, emptyList(), "Are you there? Answer in three words.") } catch (e: Gemini.Failure) { return@withContext e.message ?: "${p.name} failed." }
                if (key != null) sess.key = key
                try {
                    val t = sess.call("You are Atlas, a friendly assistant. Reply briefly.")
                    Providers.worked(applicationContext, p.id, sess.key)
                    "${p.name} works: “${t.text.ifBlank { "(used a tool)" }.take(40)}”"
                } catch (e: Gemini.Failure) {
                    if (Providers.keyExhausted(e)) Providers.rest(applicationContext, p.id, sess.key, e.code, e.message ?: "")
                    e.message ?: "${p.name} failed."
                }
                catch (e: Exception) { "${p.name} failed: ${e.message}" }
            }
            showBanner(msg, if (msg.contains(" works: ")) DONE else MISS)
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
            REQ_MEDIA -> {
                val clip = data.clipData
                if (clip != null) addMedia((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri })
                else data.data?.let { addMedia(listOf(it)) }
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
                            // a backup is small; a wrong pick (a video, say) must not be read whole into memory
                            val txt = contentResolver.openInputStream(uri)?.use { String(Attach.readCapped(it, 50L * 1024 * 1024) { "That file is too big to be an Atlas backup." }, Charsets.UTF_8) } ?: throw IllegalStateException("Couldn't open that file.")
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
