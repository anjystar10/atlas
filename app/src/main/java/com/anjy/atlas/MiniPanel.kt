package com.anjy.atlas

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The "Hey Atlas" mini panel: a card over the bottom of whatever app is open (about a third of the screen), not the
 * whole app. A small orb and what Atlas is doing at the top, your words as they're heard, Atlas's reply (short, with
 * light formatting), Allow / Deny when something waits on you, and a bar to keep talking or type. Swipe it down, tap
 * outside it or press Back to put it away; the work carries on. "Open Atlas" opens the full app, and only that does.
 *
 * It's one overlay window owned by [AvatarService] (so it needs "Display over other apps"); it doesn't take the
 * keyboard from the app underneath until you tap its text field. It does take the keys from the start (so Back puts
 * it away), as a window the keyboard can't attach to (FLAG_ALT_FOCUSABLE_IM) until the field is tapped. While Atlas
 * drives another app on screen it steps aside (the "working" pill shows instead) so it never covers what Atlas is tapping.
 */
class MiniPanel(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onMic: () -> Unit,
    private val onSend: (String) -> Unit,
    private val onAnswer: (allow: Boolean) -> Unit,
    private val onOpenApp: () -> Unit,
    private val onDismiss: () -> Unit,
    /** Any touch on the panel (reading or scrolling a reply, tapping the field): it isn't idle. */
    private val onTouched: () -> Unit = {},
) {
    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).roundToInt()
    private fun font(id: Int) = try { ctx.resources.getFont(id) } catch (_: Throwable) { Typeface.DEFAULT }

    /** The panel's look follows Atlas's own (dark or light), with the same palette (Design.kt). */
    private val dark: Boolean = run {
        val mode = Store.getString(ctx, "theme2") ?: try { Store.read(ctx) { it.settings.theme } } catch (_: Exception) { "dark" }
        val systemDark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        mode != "light" && !(mode == "system" && !systemDark)
    }
    private val pal = if (dark) Palette.DARK else Palette.LIGHT
    private val body = font(R.font.geist_regular)
    private val med = font(R.font.geist_medium)
    private val semi = font(R.font.geist_semibold)

    val orb = OrbView(ctx).apply { still = Prefs.reduceMotion(ctx) }
    private val status = TextView(ctx).apply { setTextColor(pal.muted); textSize = 13f; typeface = med; letterSpacing = 0.02f }
    private val heard = TextView(ctx).apply { setTextColor(pal.muted); textSize = 15f; typeface = med; visibility = View.GONE; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.START }
    private val reply = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** The reply scrolls once it's taller than about 40% of the screen, so the panel never covers more than ~60%. */
    private val scroll = object : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec((dm.heightPixels * 0.4f).toInt(), MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; addView(reply) }
    private val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
    private val waiting = TextView(ctx).apply { setTextColor(pal.fg); textSize = 14f; typeface = body; visibility = View.GONE }
    private val field = EditText(ctx)
    private val mic = FrameLayout(ctx)
    private val root = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { onDismiss(); return true }
            return super.dispatchKeyEvent(event)
        }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_OUTSIDE) onTouched()
            return super.dispatchTouchEvent(ev)
        }
    }
    private val card = LinearLayout(ctx)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // touches outside go to the app underneath (and tell the panel to go); it gets the keys (Back closes it), but no
        // keyboard until the field is tapped: a focusable window with ALT_FOCUSABLE_IM is never the keyboard's target.
        // (A not-focusable window gets no keys at all, so Back went to the app underneath until the field was tapped.)
        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        windowAnimations = android.R.style.Animation_InputMethod
    }
    var shown = false; private set
    private var aside = false

    init { build() }

    private fun build() {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(8), dp(18), dp(14))
        card.background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
                setColor(if (dark) Color.argb(236, Color.red(pal.surface), Color.green(pal.surface), Color.blue(pal.surface)) else Color.argb(242, Color.red(pal.surface), Color.green(pal.surface), Color.blue(pal.surface)))
                setStroke(dp(1), Color.argb(if (dark) 70 else 120, Color.red(pal.line), Color.green(pal.line), Color.blue(pal.line)))
            },
            Finish(pal.surface, dp(28).toFloat(), dark, dm.density, glass = true)))
        card.elevation = dp(16).toFloat()
        card.minimumHeight = (dm.heightPixels * 0.33f).toInt()
        card.isClickable = true

        // the handle: swipe down to put the panel away
        val handle = View(ctx).apply { background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(Color.argb(if (dark) 70 else 60, Color.red(pal.fg), Color.green(pal.fg), Color.blue(pal.fg))) } }
        card.addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        // the top: the orb, what Atlas is doing, Open Atlas
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(orb, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        val open = TextView(ctx).apply {
            text = "Open Atlas"; setTextColor(pal.accentInk); textSize = 13f; typeface = semi
            setPadding(dp(12), dp(7), dp(12), dp(8)); contentDescription = "Open the Atlas app"
            background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(Color.argb(if (dark) 34 else 46, Color.red(pal.accent), Color.green(pal.accent), Color.blue(pal.accent))) }
            setOnClickListener { onOpenApp() }
        }
        top.addView(open)
        card.addView(top)
        swipeToDismiss(handle); swipeToDismiss(top)

        card.addView(heard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        // something waiting on you: what it is, and Allow / Deny
        card.addView(waiting, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        chips.addView(chip("Allow", true) { onAnswer(true) })
        chips.addView(chip("Deny", false) { onAnswer(false) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        card.addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // the bar: the mic (keep talking) and a field to type in
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(pal.surface2); setStroke(dp(1), pal.line) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        mic.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; colors = intArrayOf(pal.primaryTop, pal.primaryBottom) }
            contentDescription = "Talk"; isClickable = true
            addView(LineIcon(ctx, "mic").apply { color = Color.WHITE }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { onMic() }
        }
        field.apply {
            hint = "Ask anything…"; setHintTextColor(pal.faint); setTextColor(pal.fg); textSize = 15f; typeface = body
            background = null; setPadding(dp(12), dp(8), dp(8), dp(8)); maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
            // the keyboard only when you ask for it: the window takes focus once the field is tapped
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) wantKeyboard(); false }
        }
        val send = FrameLayout(ctx).apply {
            contentDescription = "Send"; isClickable = true
            addView(LineIcon(ctx, "arrow").apply { color = pal.fg; rotation = -90f }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { submit() }
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(42), dp(42)))
        bar.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // a tap anywhere outside the card puts it away (the tap still reaches the app underneath)
        root.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_OUTSIDE && !aside) { onDismiss(); true } else false }
        // above the navigation bar, and above the keyboard while typing
        root.setOnApplyWindowInsetsListener { v, ins ->
            val nav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            card.setPadding(dp(18), dp(8), dp(18), dp(14) + maxOf(nav, ime))
            ins
        }
    }

    private fun chip(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; typeface = semi; setPadding(dp(18), dp(8), dp(18), dp(9))
        setTextColor(if (primary) Color.WHITE else pal.fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            if (primary) colors = intArrayOf(pal.primaryTop, pal.primaryBottom) else { setColor(pal.surface2); setStroke(dp(1), pal.line) }
        }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun swipeToDismiss(v: View) {
        var downY = 0f; var dragging = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> { val dy = ev.rawY - downY; if (dy > dp(8)) dragging = true; if (dragging) card.translationY = dy.coerceAtLeast(0f); dragging }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    if (card.translationY > card.height * 0.25f) card.animate().translationY(card.height.toFloat()).setDuration(150).withEndAction { onDismiss() }.start()
                    else card.animate().translationY(0f).setDuration(160).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun submit() {
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        field.setText("")
        try { ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0) } catch (_: Exception) {}
        onSend(q)
    }

    private fun wantKeyboard() {
        if (params.flags and NO_KEYBOARD == 0) return
        params.flags = params.flags and NO_KEYBOARD.inv()
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
        field.requestFocus()
        field.post { try { ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) } catch (_: Exception) {} }
    }

    /** True while you're typing (it isn't put away by itself then). */
    val typing get() = field.hasFocus() && field.text.isNotEmpty()

    fun show() {
        if (shown) return
        try { wm.addView(root, params); shown = true } catch (e: Exception) { android.util.Log.w("AtlasPanel", "couldn't show the panel", e) }
    }

    fun close() {
        if (!shown) return
        shown = false
        try { ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0) } catch (_: Exception) {}
        try { wm.removeView(root) } catch (_: Exception) {}
    }

    /** What Atlas is doing, in a few words ("Listening…", "Thinking…"). */
    fun status(s: String) { status.text = s }

    /** Your words as they're heard (null hides them). */
    fun heard(s: String?) {
        heard.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
        heard.text = s ?: ""
    }

    /** Atlas's reply, with light formatting (bold, lists, headings); blank clears it. */
    fun reply(md: String) {
        reply.removeAllViews()
        if (md.isBlank()) return
        val st = Markdown.Style(body, semi, semi, Typeface.MONOSPACE, pal.fg, pal.muted, pal.accentInk,
            Color.argb(if (dark) 40 else 24, Color.red(pal.fg), Color.green(pal.fg), Color.blue(pal.fg)), dm.density, 16f)
        try {
            Markdown.parse(md, st).take(12).forEach { b ->
                val tv = TextView(ctx).apply {
                    setTextColor(pal.fg); textSize = 16f; typeface = body; setLineSpacing(0f, 1.22f); setTextIsSelectable(false)
                    text = when (b) { is Markdown.Prose -> b.text; is Markdown.Code -> b.text }
                    if (b is Markdown.Code) { typeface = Typeface.MONOSPACE; textSize = 13f }
                }
                reply.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        } catch (_: Exception) {
            reply.addView(TextView(ctx).apply { setTextColor(pal.fg); textSize = 16f; typeface = body; text = Plain.spoken(md) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Something waiting on your OK (null: nothing): what it is, with Allow / Deny. */
    fun waitingOn(what: String?) {
        waiting.visibility = if (what == null) View.GONE else View.VISIBLE
        chips.visibility = waiting.visibility
        waiting.text = what?.let { "Waiting for your OK: $it" } ?: ""
    }

    /** The mic button while listening (tap to send now) or not (tap to talk). */
    fun listening(on: Boolean) { mic.contentDescription = if (on) "Done talking" else "Talk"; mic.alpha = if (on) 0.85f else 1f }

    /**
     * While Atlas drives another app on screen the panel steps aside (it's hidden and lets every touch through), so it
     * never covers what Atlas is tapping; it comes back when that's done.
     */
    fun stepAside(on: Boolean) {
        if (on == aside || !shown) return
        aside = on
        // aside, it takes neither touches nor keys; back, it takes the keys again (Back) and the keyboard once the field is tapped
        // (ALT_FOCUSABLE_IM is cleared aside: with NOT_FOCUSABLE it would ask for the keyboard instead)
        params.flags = if (on) (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
            else (params.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()) or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        root.visibility = if (on) View.GONE else View.VISIBLE
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private companion object {
        /** Either flag keeps the keyboard away from the panel's field. */
        const val NO_KEYBOARD = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    }
}
