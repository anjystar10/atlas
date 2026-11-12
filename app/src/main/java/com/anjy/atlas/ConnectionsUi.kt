package com.anjy.atlas

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Me → Connections on the phone: MCP servers on the internet and web APIs Atlas and Atlas Bot can use (Connections.kt).
 * Add one (address and token, or a pasted setup), see its status and tools, reconnect, choose who may use it and how
 * much to ask, remove it. Tokens stay on this phone, encrypted; they don't sync.
 */

private fun MainActivity.bg(work: () -> Unit, then: () -> Unit = {}) {
    scope.launch {
        val err = withContext(Dispatchers.IO) { try { work(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName } }
        if (err != null) showBanner(err, MISS) else then()
        render(keepScroll = true)
    }
}

internal fun MainActivity.connectionsCard(): View {
    val c = card()
    val servers = Connections.servers(this); val apis = Connections.apis(this)
    val n = servers.size + apis.size
    c.addView(text(if (n == 0) "None yet" else "$n connection${if (n == 1) "" else "s"}", 18f, FG, HEAD))
    c.add(text("Connect outside services so Atlas and Atlas Bot can use them: MCP servers on the internet (the standard many apps offer) and plain web APIs. Anything that changes something asks you first. Tokens stay on this phone, encrypted; they don't sync. (Servers that run as a program on a computer work in Atlas on your PC.)", 14f, MUTED), 6)
    for (s in servers) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(0, dp(12), 0, dp(4)); setOnClickListener { serverOptions(s) } }
        val word = when (s.status) { "connected" -> "Connected"; "connecting" -> "Connecting…"; "error" -> "Problem"; else -> if (s.tools.isNotEmpty()) "Ready" else "Not checked yet" }
        row.addView(text("${s.name} · $word", 16f, if (s.status == "error") MISS else FG, MED))
        row.addView(text(s.url, 13f, MUTED, MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        row.addView(text(if (s.status == "error") s.error else if (s.tools.isEmpty()) "No tools yet" else "${s.tools.size} tool${if (s.tools.size == 1) "" else "s"}: " + s.tools.take(6).joinToString(", ") { it.name } + if (s.tools.size > 6) "…" else "",
            13f, if (s.status == "error") MISS else MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        c.add(row, 6)
    }
    for (a in apis) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(0, dp(12), 0, dp(4)); setOnClickListener { apiOptions(a) } }
        row.addView(text("${a.name} · Web API", 16f, FG, MED))
        row.addView(text(a.base, 13f, MUTED, MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        if (a.description.isNotEmpty()) row.addView(text(a.description, 13f, MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        c.add(row, 6)
    }
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    buttons.addView(secondary("Add a Server") { addServer() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(secondary("Paste a Setup") { pasteSetup() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    c.add(buttons, 14)
    c.add(secondary("Add a Web API") { addApi() }, 8)
    if (servers.isNotEmpty()) c.add(secondary("Check All Again") { scope.launch { Connections.refresh(applicationContext, force = true); render(keepScroll = true) } }, 8)
    return c
}

private fun MainActivity.addServer() {
    var name = ""; var url = ""; var token = ""
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("Name, e.g. notion", name) { name = it })
    col.add(field("Address, e.g. https://example.com/mcp", url) { url = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 8)
    col.add(field("Token (optional; sent as Authorization: Bearer …)", token) { token = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, 8)
    sheet(null, "Add an MCP Server", "An MCP server on the internet: its address, and its token if it needs one.", content = col, primaryLabel = "Add", onPrimary = {
        val n = name.trim(); val u = url.trim(); val t = token.trim()
        bg({ Connections.saveServer(applicationContext, n, u, if (t.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $t")); Connections.connect(applicationContext, n) },
            { showBanner("Added $n", DONE) })
    }, secondaryLabel = "Cancel")
}

private fun MainActivity.pasteSetup() {
    var json = ""
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("{ \"mcpServers\": { \"name\": { \"url\": \"https://…\" } } }", json, multi = true) { json = it })
    sheet(null, "Paste a Setup", "The JSON block a service gives you for apps like this one (\"mcpServers\").", content = col, primaryLabel = "Add", onPrimary = {
        val cfgs = try { McpWire.parseConfig(json) } catch (e: IllegalArgumentException) { showBanner(e.message ?: "Couldn't read that.", MISS); return@sheet }
        val programs = cfgs.filter { it.url == null }.map { it.name }
        val web = cfgs.filter { it.url != null }
        bg({ web.forEach { Connections.saveServer(applicationContext, it.name, it.url!!, it.headers); Connections.connect(applicationContext, it.name) } }, {
            showBanner(listOfNotNull(if (web.isNotEmpty()) "Added ${web.joinToString(", ") { it.name }}" else null,
                if (programs.isNotEmpty()) "${programs.joinToString(", ")} run as a program on a computer: add ${if (programs.size == 1) "it" else "them"} in Atlas on your PC" else null).joinToString(". "),
                if (web.isEmpty()) MISS else DONE)
        })
    }, secondaryLabel = "Cancel")
}

private fun MainActivity.addApi() {
    var name = ""; var base = ""; var auth = ""; var desc = ""
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("Name, e.g. myserver", name) { name = it })
    col.add(field("Address, e.g. https://api.example.com/v1", base) { base = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 8)
    col.add(field("Authorization header (optional), e.g. Bearer abc123", auth) { auth = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, 8)
    col.add(field("What it is and its main paths, so Atlas knows how to use it", desc, multi = true) { desc = it }, 8)
    sheet(null, "Add a Web API", "Atlas can then call it. Reading (GET) goes ahead; anything that changes things asks you first.", content = col, primaryLabel = "Add", onPrimary = {
        val n = name.trim(); val b = base.trim(); val h = auth.trim(); val d = desc.trim()
        bg({ Connections.saveApi(applicationContext, n, b, d, if (h.isEmpty()) emptyMap() else mapOf("Authorization" to h)) }, { showBanner("Added $n", DONE) })
    }, secondaryLabel = "Cancel")
}

private fun MainActivity.serverOptions(s: Connections.Server) {
    var trust = s.trust; var chat = s.chat; var bots = s.bots
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(text("Before using its tools (tap to change)", 13f, MUTED))
    val labels = listOf("reads" to "Ask Unless Read-Only", "ask" to "Always Ask", "all" to "Never Ask")
    lateinit var pick: android.widget.TextView
    pick = secondary(labels.first { it.first == trust }.second) {
        trust = labels[(labels.indexOfFirst { it.first == trust } + 1) % labels.size].first
        pick.text = labels.first { it.first == trust }.second
    }
    col.add(pick, 6)
    col.add(switchRow(null, "Atlas May Use It in Chat", chat) { chat = it }, 12)
    col.add(switchRow(null, "Atlas Bot May Use It", bots) { bots = it }, 6)
    Connections.headerNames(this, s.name).takeIf { it.isNotEmpty() }?.let { col.add(text("Stored: ${it.joinToString(", ")} (values hidden)", 13f, MUTED), 10) }
    if (s.tools.isNotEmpty()) col.add(text(s.tools.joinToString("\n") { "• ${it.name}${if (it.readOnly) " (read-only)" else ""}" }, 13f, MUTED).apply { maxLines = 12; ellipsize = android.text.TextUtils.TruncateAt.END }, 10)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(secondary("Check Again") { keepSheet = false; bg({ Connections.connect(applicationContext, s.name) }) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(secondary("Remove") { keepSheet = false; bg({ Connections.remove(applicationContext, s.name) }, { showBanner("Removed ${s.name}") }) },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    col.add(row, 14)
    sheet(null, s.name, s.url, content = col, primaryLabel = "Save", onPrimary = {
        val t = trust; val c = chat; val b = bots
        bg({ Connections.setServer(applicationContext, s.name, t, c, b) })
    }, secondaryLabel = "Close")
}

private fun MainActivity.apiOptions(a: Connections.Api) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (a.description.isNotEmpty()) col.addView(text(a.description, 14f, MUTED))
    Connections.headerNames(this, a.name).takeIf { it.isNotEmpty() }?.let { col.add(text("Stored: ${it.joinToString(", ")} (values hidden)", 13f, MUTED), 8) }
    col.add(text("Reading (GET) goes ahead; anything else asks you first.", 13f, MUTED), 8)
    sheet(null, a.name, a.base, content = col, primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
        bg({ Connections.remove(applicationContext, a.name) }, { showBanner("Removed ${a.name}") })
    }, secondaryLabel = "Close")
}

/** While Atlas is on screen, chat asks "Allow this?" here before a connection changes anything. */
internal fun MainActivity.connectionsAsker(): suspend (String) -> Boolean = { need ->
    withContext(Dispatchers.Main) {
        val answer = CompletableDeferred<Boolean>()
        sheet(null, "Allow This?", need, primaryLabel = "Allow", onPrimary = { answer.complete(true) }, secondaryLabel = "Don't", onSecondary = { answer.complete(false) }, locked = true)
        answer.await()
    }
}

/** Checks the connections again when Atlas opens (each at most every 10 minutes), off the main thread. */
internal fun MainActivity.refreshConnections() { scope.launch { try { Connections.refresh(applicationContext) } catch (_: Exception) {} } }
