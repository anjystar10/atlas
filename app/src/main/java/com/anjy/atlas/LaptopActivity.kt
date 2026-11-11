package com.anjy.atlas

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Your laptop's screen on the phone (Device Link). Tap to click, double-tap to double-click, long-press to right-click,
 * drag with one finger to drag, two fingers up/down to scroll. Type in the box below to type on the laptop; the keys
 * row has Enter, Backspace, Esc, Tab, Copy/Paste, Show Desktop, Lock and volume.
 * The laptop asks its owner the first time ("Let your phone control this PC?") and shows a bar with Stop while it does.
 */
class LaptopActivity : Activity() {
    private val BG = Palette.NIGHT.bg
    private val FG = Palette.NIGHT.fg
    private val MUTED = Palette.NIGHT.muted
    private val SURFACE = Palette.NIGHT.surface2
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun font(id: Int, fb: Typeface) = try { resources.getFont(id) } catch (_: Throwable) { fb }
    private val med by lazy { font(R.font.geist_medium, Typeface.DEFAULT) }
    private val serif by lazy { font(R.font.newsreader_regular, Typeface.SERIF) }

    private lateinit var img: ImageView
    private lateinit var status: TextView
    private val sid = UUID.randomUUID().toString()
    private var device: String? = null
    @Volatile private var live = false
    @Volatile private var ended = false
    private var frameW = 0; private var frameH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Link.init(applicationContext)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(12), dp(8)) }
        top.addView(TextView(this).apply { text = "Your Laptop"; textSize = 22f; typeface = serif; setTextColor(FG) })
        status = TextView(this).apply { text = "  Connecting…"; textSize = 13f; typeface = med; setTextColor(MUTED) }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(key("Done") { finish() })
        root.addView(top)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); contentDescription = "Your laptop's screen" }
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(img, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        // keys
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), 0) }
        for ((label, act) in listOf("Enter" to { k("enter") }, "⌫" to { k("backspace") }, "Esc" to { k("esc") }, "Tab" to { k("tab") },
                "Copy" to { k("ctrl+c") }, "Paste" to { k("ctrl+v") }, "Undo" to { k("ctrl+z") }, "Alt+Tab" to { k("alt+tab") }, "Desktop" to { a("show_desktop") },
                "Lock" to { a("lock") }, "Vol −" to { a("volume_down") }, "Vol +" to { a("volume_up") }, "Mute" to { a("mute") }))
            keys.addView(key(label, act), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) })
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(10)) }
        val box = EditText(this).apply {
            hint = "Type on the laptop"; setHintTextColor(MUTED); setTextColor(FG); textSize = 16f; typeface = med; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; imeOptions = EditorInfo.IME_ACTION_SEND
            background = Finish(SURFACE, -1f, true, resources.displayMetrics.density, glass = true); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val sendText = { val t = box.text.toString(); if (t.isNotEmpty()) { input(JSONObject().put("t", "text").put("text", t)); box.setText("") } }
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false }
        typeRow.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        typeRow.addView(key("Type") { sendText() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(typeRow)
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins -> val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED }
        touch()
        Thread { connect() }.start()
    }

    private fun key(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = med; setTextColor(FG); gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(11))
        background = Finish(SURFACE, -1f, true, resources.displayMetrics.density, glass = true)
        isClickable = true; setOnClickListener { onTap() }
    }
    private fun k(key: String) = input(JSONObject().put("t", "key").put("key", key))
    private fun a(act: String) = input(JSONObject().put("t", "act").put("act", act))
    private fun say(t: String) = runOnUiThread { status.text = "  $t" }

    private fun connect() {
        try {
            Link.setActive(true)
            val lap = try { Link.beat(); Link.laptop() } catch (_: Exception) { Link.laptop() }
            if (lap == null) return fail("No laptop is linked yet. Link Atlas on your PC in Me → Your Devices.")
            if (!lap.online) return fail("Your laptop isn't online (last seen ${Link.ago(lap.agoMs)}). Atlas has to be running on it.")
            device = lap.device
            say("Asking your laptop…")
            val dm = resources.displayMetrics
            Link.send(lap.device, "screen_start", JSONObject().put("sid", sid).put("maxW", maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1600)).put("q", 55))
            var r = Link.waitFor("screen_reply", sid, 15_000)
            if (r != null && !r.optBoolean("ok") && r.optString("why") == "asking") { say("Allow it on your laptop…"); r = Link.waitFor("screen_reply", sid, 90_000) }
            if (r == null) return fail("Your laptop didn't answer.")
            if (!r.optBoolean("ok")) return fail(if (r.optString("why") == "denied") "Not allowed on the laptop." else "Your laptop couldn't share its screen.")
            live = true; say("Live")
            Thread { keepAlive() }.start()
            var seq = -1L; var misses = 0
            while (!ended) {
                val f = try { Link.getFrame(lap.device, seq, 2000) } catch (_: Exception) { Thread.sleep(700); null }
                if (f != null && f.second.optString("sid") == sid) {
                    seq = f.first; misses = 0
                    val bytes = Base64.decode(f.second.optString("jpg"), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    frameW = bmp.width; frameH = bmp.height
                    runOnUiThread { if (!ended) img.setImageBitmap(bmp) }
                } else if (++misses > 12) return fail("The laptop stopped sending its screen.")
            }
        } catch (e: Exception) { fail(e.message ?: "Couldn't reach your laptop.") }
    }
    private fun keepAlive() { while (!ended) { try { Thread.sleep(5000) } catch (_: InterruptedException) { break }; device?.let { if (!ended) Link.sendAsync(it, "screen_keep", JSONObject().put("sid", sid)) } } }
    private fun fail(why: String) { live = false; say(why); runOnUiThread { android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show() } }

    private fun input(inp: JSONObject) { val d = device ?: return; if (live) Link.sendAsync(d, "input", JSONObject().put("sid", sid).put("input", inp)) }

    /** Where a touch on the view lands on the picture, as fractions (null: outside the picture). */
    private fun frac(x: Float, y: Float): Pair<Double, Double>? {
        val dr = img.drawable ?: return null
        val r = RectF(0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat()); Matrix(img.imageMatrix).mapRect(r)
        return LinkCore.toFraction(x - r.left - img.paddingLeft, y - r.top - img.paddingTop, r.width(), r.height())
    }

    private fun touch() {
        var downF: Pair<Double, Double>? = null; var dragging = false; var twoY = -1f; var scrollAcc = 0f
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { frac(e.x, e.y)?.let { input(JSONObject().put("t", "tap").put("x", it.first).put("y", it.second)) }; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { frac(e.x, e.y)?.let { input(JSONObject().put("t", "double").put("x", it.first).put("y", it.second)) }; return true }
            override fun onLongPress(e: MotionEvent) { if (!dragging) frac(e.x, e.y)?.let { input(JSONObject().put("t", "right").put("x", it.first).put("y", it.second)); img.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } }
        })
        img.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downF = frac(e.x, e.y); dragging = false; twoY = -1f; scrollAcc = 0f }
                MotionEvent.ACTION_POINTER_DOWN -> { if (e.pointerCount == 2) { twoY = (e.getY(0) + e.getY(1)) / 2; dragging = true } }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2 && twoY >= 0) {
                        val y = (e.getY(0) + e.getY(1)) / 2; scrollAcc += twoY - y; twoY = y
                        val step = dp(36).toFloat()
                        while (abs(scrollAcc) >= step) { val dir = if (scrollAcc > 0) 1 else -1; scrollAcc -= dir * step; input(JSONObject().put("t", "scroll").put("x", 0.5).put("y", 0.5).put("dy", dir * 2)) }
                    } else downF?.let { d -> frac(e.x, e.y)?.let { f -> if (abs(f.first - d.first) > 0.02 || abs(f.second - d.second) > 0.02) dragging = true } }
                }
                MotionEvent.ACTION_UP -> {
                    val d = downF; val f = frac(e.x, e.y)
                    if (dragging && twoY < 0 && d != null && f != null) input(JSONObject().put("t", "drag").put("x", d.first).put("y", d.second).put("x2", f.first).put("y2", f.second))
                    downF = null
                }
            }
            if (!dragging) gd.onTouchEvent(e)
            true
        }
    }

    override fun onDestroy() {
        ended = true; live = false
        device?.let { Link.sendAsync(it, "screen_stop", JSONObject().put("sid", sid)) }
        Link.setActive(false)
        super.onDestroy()
    }
}
