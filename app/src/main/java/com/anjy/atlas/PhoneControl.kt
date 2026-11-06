package com.anjy.atlas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Atlas's hands on the phone: an accessibility service. It only acts when Atlas asks it to (on the user's request);
 * it doesn't watch or record anything in between.
 */
class AtlasAccess : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; Log.i("AtlasAccess", "connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: AtlasAccess? = null; private set

        /** Whether the user has switched Atlas on in Settings → Accessibility (even if Android hasn't connected it yet). */
        fun enabledInSettings(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, AtlasAccess::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Reads the screen as numbered elements and acts on them. Every action reports the screen that follows. */
object PhoneControl {
    private const val TAG = "AtlasPhone"
    private const val MAX_ELEMENTS = 160

    /** The elements from the latest reading, by number. */
    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()

    fun on() = AtlasAccess.instance != null

    private val OFF = "Error: phone control is off, so Atlas can't see or touch the screen. Tell the user to turn it on in Atlas → Me → Phone control."

    private fun svc(): AtlasAccess? = AtlasAccess.instance

    private fun label(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != parts.firstOrNull() }?.let { parts.add(it) }
        if (parts.isEmpty()) n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add("hint: $it") }
        return parts.joinToString(" / ").replace('\n', ' ').replace(Regex("\\s+"), " ").let { if (it.length > 140) it.take(137) + "…" else it }
    }

    /** Text inside a container with no label of its own (a list row, a card): what a person would read on it. */
    private fun innerText(n: AccessibilityNodeInfo, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        if (depth > 4 || sb.length > 80) return sb.toString()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val t = (c.text ?: c.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) { if (sb.isNotEmpty()) sb.append(" "); sb.append(t.replace('\n', ' ')) }
            else innerText(c, depth + 1, sb)
            if (sb.length > 80) break
        }
        return sb.toString().take(90)
    }

    private fun kind(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            n.isEditable -> "TextField"
            c.contains("Button") -> "Button"
            c.contains("CheckBox") || c.contains("Switch") || c.contains("Toggle") -> c
            c.contains("Image") -> "Image"
            c.contains("Text") -> "Text"
            c.contains("Recycler") || c.contains("ListView") || c.contains("ScrollView") || c.contains("Pager") -> "List"
            else -> c
        }
    }

    /** The screen as text for the model, and the numbered nodes kept for the next action. */
    fun readScreen(): String {
        val s = svc() ?: return OFF
        return try { read(s) } catch (e: Exception) { Log.w(TAG, "read failed", e); "Error: couldn't read the screen (${e.message})." }
    }

    private fun read(s: AtlasAccess): String {
        val me = s.packageName
        val dm = s.resources.displayMetrics
        val wins = try { s.windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val keyboard = wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        // the app and any dialogs or menus over it, top layer first; never Atlas's own floating bits
        val roots = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .sortedByDescending { it.layer }
            .mapNotNull { w -> w.root?.takeIf { r -> r.packageName?.toString() != me || wins.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } == 1 } }
            .ifEmpty { listOfNotNull(s.rootInActiveWindow) }
        if (roots.isEmpty()) { nodes = emptyList(); return "The screen can't be read right now (it may be locked or loading). Try wait, then read_screen." }
        val top = s.rootInActiveWindow?.packageName?.toString() ?: roots.first().packageName?.toString() ?: "?"
        val appName = try { s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(top, 0)).toString() } catch (_: Exception) { top }
        val found = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (found.size >= MAX_ELEMENTS || depth > 40) return
            if (!n.isVisibleToUser) return
            val actionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
            var lab = label(n)
            if (lab.isEmpty() && (n.isClickable || n.isLongClickable)) lab = innerText(n).let { if (it.isNotEmpty()) "($it)" else "" }
            if (lab.isNotEmpty() || n.isEditable || n.isScrollable || n.isClickable) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val flags = mutableListOf<String>()
                    if (n.isClickable) flags.add("tap")
                    if (n.isLongClickable) flags.add("hold")
                    if (n.isEditable) flags.add("editable")
                    if (n.isScrollable) flags.add("scrolls")
                    if (n.isCheckable) flags.add(if (n.isChecked) "on" else "off")
                    if (n.isSelected) flags.add("selected")
                    if (n.isFocused) flags.add("focused")
                    if (!n.isEnabled) flags.add("disabled")
                    val idTail = n.viewIdResourceName?.substringAfter(":id/")?.takeIf { lab.isEmpty() } ?: ""
                    val name = if (lab.isNotEmpty()) "\"$lab\"" else if (idTail.isNotEmpty()) "id=$idTail" else "(no label)"
                    lines.add("[${found.size}] ${kind(n)} $name${if (flags.isNotEmpty()) " (" + flags.joinToString(", ") + ")" else ""} @${r.centerX()},${r.centerY()}")
                    found.add(n)
                }
            } else if (!actionable && n.childCount == 0) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        nodes = found
        val head = "Screen: $appName ($top), ${dm.widthPixels}x${dm.heightPixels}. Keyboard ${if (keyboard) "open" else "closed"}."
        val body = if (lines.isEmpty()) "No readable elements (the app may be drawing everything itself, or still loading)." else lines.joinToString("\n")
        val more = if (found.size >= MAX_ELEMENTS) "\n(More elements below; scroll to see them.)" else ""
        return "$head\n$body$more"
    }

    private fun node(i: Int): AccessibilityNodeInfo? = nodes.getOrNull(i)?.takeIf { it.refresh() }

    /** What element [i] of the latest reading says, to check an action before it's taken (Atlas Bot); null if it's gone. */
    fun labelOf(i: Int): String? = try { node(i)?.let { n -> label(n).ifEmpty { innerText(n) } } } catch (_: Exception) { null }

    private suspend fun settle(ms: Long = 900): String { delay(ms); return readScreen() }

    suspend fun tap(i: Int, long: Boolean): Tools.Result {
        val s = svc() ?: return Tools.Result(OFF)
        val n = node(i) ?: return Tools.Result("Error: element $i isn't on the screen any more. Read the screen again and use the new numbers.")
        val what = label(n).ifEmpty { innerText(n) }.ifEmpty { "element $i" }
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        // the element itself, or the nearest container that takes the tap; a real touch at its centre as a last resort
        var t: AccessibilityNodeInfo? = n; var done = false; var hops = 0
        while (t != null && hops < 6 && !done) {
            if ((if (long) t.isLongClickable else t.isClickable) && t.performAction(action)) done = true
            else { t = t.parent; hops++ }
        }
        if (!done) {
            val r = Rect(); n.getBoundsInScreen(r)
            done = gesture(s, r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), if (long) 650 else 60)
        }
        if (!done) return Tools.Result("Error: the tap on \"$what\" didn't go through.")
        return Tools.Result("${if (long) "Held" else "Tapped"} \"$what\".\n" + settle(), "${if (long) "Held" else "Tapped"} “${what.take(40)}”", phone = true)
    }

    suspend fun type(i: Int, text: String, enter: Boolean): Tools.Result {
        val s = svc() ?: return Tools.Result(OFF)
        var f = if (i >= 0) node(i) ?: return Tools.Result("Error: element $i isn't on the screen any more. Read the screen again.") else null
        if (f != null && !f.isEditable) {
            // a tap on a search icon or a box that opens the real field: tap it, then use whatever field has focus
            f.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(500)
            f = null
        }
        if (f == null) f = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: nodes.firstOrNull { it.refresh() && it.isEditable }
        val field = f ?: return Tools.Result("Error: there's no text field to type in. Tap the box first, then type.")
        if (!field.isFocused) { field.performAction(AccessibilityNodeInfo.ACTION_CLICK); field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Tools.Result("Error: the field didn't accept typing.")
        var note = ""
        if (enter) {
            delay(250)
            field.refresh()
            note = if (field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) " and pressed enter" else " (enter didn't work; tap the send button instead)"
        }
        return Tools.Result("Typed \"${text.take(200)}\"$note.\n" + settle(), "Typed “${text.take(48)}${if (text.length > 48) "…" else ""}”", phone = true)
    }

    suspend fun scroll(dir: String, i: Int): Tools.Result {
        val s = svc() ?: return Tools.Result(OFF)
        val target = if (i >= 0) node(i) else nodes.filter { it.refresh() && it.isScrollable }.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
        var ok = false
        if (target != null) {
            val specific = when (dir) { "down" -> AccessibilityAction.ACTION_SCROLL_DOWN; "up" -> AccessibilityAction.ACTION_SCROLL_UP; "left" -> AccessibilityAction.ACTION_SCROLL_LEFT; else -> AccessibilityAction.ACTION_SCROLL_RIGHT }
            ok = target.performAction(specific.id) ||
                target.performAction(if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        if (!ok) {
            // a swipe across the middle of the screen (or of the list): content moves the way it was asked to come into view
            val dm = s.resources.displayMetrics
            val r = Rect(0, 0, dm.widthPixels, dm.heightPixels); target?.getBoundsInScreen(r)
            val cx = r.centerX().toFloat(); val cy = r.centerY().toFloat()
            val dy = r.height() * 0.3f; val dx = r.width() * 0.3f
            ok = when (dir) {
                "down" -> gesture(s, cx, cy + dy, cx, cy - dy, 350)
                "up" -> gesture(s, cx, cy - dy, cx, cy + dy, 350)
                "right" -> gesture(s, cx + dx, cy, cx - dx, cy, 300)
                else -> gesture(s, cx - dx, cy, cx + dx, cy, 300)
            }
        }
        if (!ok) return Tools.Result("Error: couldn't scroll $dir.")
        return Tools.Result("Scrolled $dir.\n" + settle(700), null, phone = true)
    }

    suspend fun key(k: String): Tools.Result {
        val s = svc() ?: return Tools.Result(OFF)
        val ok = when (k) {
            "back" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "enter" -> s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityAction.ACTION_IME_ENTER.id) == true
            else -> return Tools.Result("Error: unknown key $k.")
        }
        if (!ok) return Tools.Result("Error: the $k key didn't work${if (k == "enter") " (no text field has focus)" else ""}.")
        return Tools.Result("Pressed $k.\n" + settle(), "Pressed ${k.replaceFirstChar { it.uppercase() }}", phone = true)
    }

    private suspend fun gesture(s: AtlasAccess, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val dm = s.resources.displayMetrics
        fun cx(v: Float) = v.coerceIn(1f, dm.widthPixels - 1f)
        fun cy(v: Float) = v.coerceIn(1f, dm.heightPixels - 1f)
        val p = Path().apply { moveTo(cx(x1), cy(y1)); lineTo(cx(x2), cy(y2)) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return suspendCancellableCoroutine { cont ->
            val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
    }
}
