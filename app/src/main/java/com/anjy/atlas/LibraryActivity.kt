package com.anjy.atlas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Library on the phone: everything Atlas Bot made or downloaded (documents, spreadsheets, pages, downloads), newest
 * first. Open (pages in Atlas's own viewer; the rest in the app you choose), Share, Delete. Files stay on this phone.
 */
class LibraryActivity : Activity() {
    companion object { fun intent(ctx: Context) = Intent(ctx, LibraryActivity::class.java) }

    private val BG = Palette.NIGHT.bg
    private val FG = Palette.NIGHT.fg
    private val MUTED = Palette.NIGHT.muted
    private val SURFACE = Palette.NIGHT.surface2
    private lateinit var col: LinearLayout
    private var filter = "all"
    private val serif by lazy { Type.serif(this) }
    private val med by lazy { Type.sansMedium(this) }
    private val semi by lazy { Type.sansSemi(this) }
    private val body by lazy { Type.sans(this) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val whenFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bots.init(applicationContext)
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
        root.setOnApplyWindowInsetsListener { v, ins -> val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED }
    }

    override fun onResume() { super.onResume(); render() }

    private fun text(s: CharSequence, size: Float, color: Int = FG, face: Typeface = body) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; setLineSpacing(0f, 1.25f)
    }
    private fun add(v: View, top: Int = 0) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = Finish(SURFACE, dp(20).toFloat(), true, resources.displayMetrics.density, glass = true)
    }
    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = if (primary) semi else med; gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.NIGHT.onPrimary else if (danger) Palette.NIGHT.danger else FG); setPadding(dp(10), dp(10), dp(10), dp(11))
        background = Finish(if (primary) Palette.NIGHT.primaryBottom else SURFACE, -1f, true, resources.displayMetrics.density, glass = !primary)
        isClickable = true; setOnClickListener { onTap() }; pressFx()
    }
    private fun chip(label: String, on: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; typeface = if (on) semi else med; gravity = Gravity.CENTER
        setTextColor(if (on) Palette.NIGHT.onPrimary else FG); setPadding(dp(12), dp(7), dp(12), dp(8))
        background = Finish(if (on) Palette.NIGHT.primaryBottom else SURFACE, -1f, true, resources.displayMetrics.density, glass = !on)
        isClickable = true; setOnClickListener { onTap() }; contentDescription = label + if (on) ", chosen" else ""
    }
    private val KIND = mapOf("document" to "Document", "spreadsheet" to "Spreadsheet", "page" to "Page", "file" to "File", "download" to "Download")

    private fun render() {
        col.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("‹", 30f, FG, serif).apply { setPadding(dp(4), 0, dp(14), dp(4)); contentDescription = "Back"; isClickable = true; setOnClickListener { finish() } })
        top.addView(text("Library", 26f, FG, serif), LinearLayout.LayoutParams(0, -2, 1f))
        add(top)
        add(text("Documents, spreadsheets, pages and downloads your bots made. They stay on this phone and don’t sync to your PC.", 15f, MUTED), 4)
        val all = try { BotFiles.library(this) } catch (_: Exception) { emptyList() }
        val kinds = listOf("all") + listOf("document", "spreadsheet", "page", "download", "file").filter { k -> all.any { it.kind == k } }
        if (kinds.size > 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            kinds.forEach { k -> row.addView(chip(if (k == "all") "Everything" else KIND[k] + "s", filter == k) { filter = k; render() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
            add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 14)
        }
        val shown = all.filter { filter == "all" || it.kind == filter }
        if (shown.isEmpty()) {
            add(card().apply {
                addView(text("Nothing here yet", 18f, FG, serif))
                addView(text("Ask a bot for a report, a spreadsheet or a page, like “compare the five best budget phones and make me a spreadsheet”.", 14f, MUTED),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }, 18)
            return
        }
        shown.forEach { it -> add(itemCard(it), 10) }
    }

    private fun itemCard(it: BotFiles.Item): View {
        val f = it.file
        val c = card()
        c.addView(text(it.title, 17f, FG, serif).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val meta = listOfNotNull(KIND[it.kind] ?: "File", if (f.name != it.title) f.name else null, BotFileRules.size(f.length()),
            whenFmt.format(Instant.ofEpochMilli(it.at).atZone(com.anjy.atlas.core.Engine.zone))).joinToString(" · ")
        c.addView(text(meta, 12.5f, MUTED, med).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (it.goal.isNotBlank()) c.addView(text(it.goal, 12.5f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(button("Open", primary = true) { open(f) }, LinearLayout.LayoutParams(0, -2, 1f))
        keys.addView(button("Share") { if (!BotFileProvider.share(this, f)) toast("Couldn’t share it") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        keys.addView(button("Delete", danger = true) {
            AlertDialog.Builder(this).setTitle("Delete This File?").setMessage("“${f.name}” will be deleted from this phone.")
                .setPositiveButton("Delete") { _, _ -> if (BotFiles.remove(this, f)) { toast("Deleted"); render() } else toast("Couldn’t delete it") }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        c.addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return c
    }

    private fun open(f: java.io.File) {
        if (!f.isFile) { toast("That file is gone"); render(); return }
        if (BotFileRules.ext(f.name) in setOf("html", "htm")) { startActivity(PageViewActivity.intent(this, f)); return }
        if (!BotFileProvider.open(this, f)) toast("No app on this phone opens ${BotFileRules.ext(f.name).uppercase()} files")
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
