package com.anjy.atlas

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Me → Trading on the phone (Trading.kt): connected accounts with a clear PAPER / LIVE tag, their limits (only the user
 * sets them), connect / edit / remove, the stop switch and turning trading back on. Keys stay on this phone, encrypted.
 */

/** While Atlas is on screen, a LIVE order from chat shows here and only a tap sends it. */
internal fun MainActivity.tradingAsker(): suspend (String, String, String) -> Boolean = { title, detail, button ->
    withContext(Dispatchers.Main) {
        val answer = CompletableDeferred<Boolean>()
        sheet(null, title, detail, primaryLabel = button, primaryColor = MISS, onPrimary = { answer.complete(true) }, secondaryLabel = "Don't", onSecondary = { answer.complete(false) }, locked = true)
        answer.await()
    }
}

private fun MainActivity.tbg(work: suspend () -> String?, then: (String?) -> Unit = {}) {
    scope.launch {
        val r = try { withContext(Dispatchers.IO) { work() } } catch (e: Exception) { showBanner(e.message ?: e.javaClass.simpleName, MISS); render(keepScroll = true); return@launch }
        then(r); render(keepScroll = true)
    }
}

internal fun MainActivity.tradingCard(): View {
    val c = card()
    val list = Trading.accounts(this); val killed = Trading.killed(this)
    c.addView(text(if (killed) "Stopped" else if (list.isEmpty()) "No accounts yet" else "${list.size} account${if (list.size == 1) "" else "s"}", 18f, if (killed) MISS else FG, HEAD))
    c.add(text(if (killed) "The stop switch is on: Atlas won't place any order on any account until you turn trading back on."
        else "Atlas can trade for you on Bybit (spot crypto) or a prop firm on ProjectX (TopstepX futures). PAPER accounts are practice and go ahead; every LIVE order shows you what it is and its worst case and waits for your tap. Your limits are enforced in code. Losses are possible; nothing is guaranteed. Keys stay on this phone, encrypted.", 14f, MUTED), 6)
    for (a in list) {
        val live = a.optString("mode") == "live"; val l = TradeRules.limitsFor(a)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(0, dp(12), 0, dp(4)); setOnClickListener { tradeAccountOptions(a) } }
        row.addView(text("${if (live) "LIVE" else "PAPER"} · ${a.optString("name")}", 16f, if (live) MISS else FG, MED))
        row.addView(text("${if (a.optString("broker") == "bybit") "Bybit spot" else "ProjectX futures"} · " +
            (if (a.optString("broker") == "bybit") "${TradeRules.money(l.maxOrderUsd)} per order" else "${l.maxContracts.toInt()} contract(s) per order") +
            " · ${TradeRules.money(l.maxDailyLossUsd)} daily loss · ${l.maxOrdersPerDay} trades a day", 13f, MUTED))
        c.add(row, 6)
    }
    c.add(secondary("Connect an Account") {
        sheet(null, "Which Broker?", "Bybit: spot crypto (BTCUSDT…), PAPER on Bybit Demo Trading. Prop firm: futures (NQ, ES, GC…) on ProjectX, such as TopstepX.",
            primaryLabel = "Bybit", onPrimary = { tradeConnect(null, "bybit") }, secondaryLabel = "Prop Firm", onSecondary = { tradeConnect(null, "projectx") })
    }, 14)
    if (killed) c.add(primary("Turn Trading Back On") {
        sheet(null, "Turn Trading Back On?", "Atlas will be able to place orders again, inside each account's limits (LIVE orders still ask you).", primaryLabel = "Turn It On",
            onPrimary = { Trading.resume(applicationContext); showBanner("Trading is back on", DONE); render(keepScroll = true) }, secondaryLabel = "Keep It Stopped")
    }, 8)
    else if (list.isNotEmpty()) c.add(secondary("Stop All Trading") {
        tbg({ Trading.stopAll(applicationContext, "from Me → Trading").out }) { showBanner("Trading stopped", DONE) }
    }, 8)
    return c
}

private fun MainActivity.tradeConnect(was: JSONObject?, newBroker: String = "bybit") {
    var name = was?.optString("name") ?: ""; val broker = was?.optString("broker") ?: newBroker; var mode = was?.optString("mode") ?: "paper"
    var k1 = ""; var k2 = ""; var base = was?.optString("base") ?: "https://api.topstepx.com"; var remote = if (was?.has("remoteId") == true) was.optLong("remoteId").toString() else ""
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("Name, e.g. Bybit demo or Topstep practice", name) { name = it })
    col.add(text(if (broker == "bybit") "PAPER uses Bybit Demo Trading (keys made in Bybit → Demo Trading → API). LIVE uses your real Bybit account: give the key Read and Spot Trade only, no withdrawals." else "Your prop firm's ProjectX username (not the email) and API key (TopstepX: Settings → API). PAPER for practice accounts, LIVE for evaluation or funded ones.", 13f, MUTED), 10)
    col.add(field(if (broker == "bybit") "API key" else "Username", "") { k1 = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }, 8)
    col.add(field(if (broker == "bybit") "API secret" else "API key", "") { k2 = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, 8)
    if (was != null) col.add(text("Leave the keys empty to keep the stored ones.", 13f, MUTED), 6)
    if (broker == "projectx") {
        col.add(field("Address, e.g. https://api.topstepx.com", base) { base = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 8)
        col.add(field("Account id (tap Check to list them)", remote, numeric = true) { remote = it }, 8)
    }
    col.add(chipGroup(listOf("paper" to "PAPER (Practice)", "live" to "LIVE (Real Money)"), { it == mode }) { mode = it }, 12)
    fun acc() = JSONObject().put("id", was?.optString("id") ?: "").put("name", name).put("broker", broker).put("mode", mode).put("base", base).put("remoteId", remote)
    fun secret(): JSONObject? = if (k1.isBlank() && k2.isBlank()) null else if (broker == "bybit") JSONObject().put("key", k1.trim()).put("secret", k2.trim()) else JSONObject().put("userName", k1.trim()).put("apiKey", k2.trim())
    col.add(secondary("Check") {
        scope.launch { val r = Trading.test(applicationContext, acc(), secret()); showBanner(r.removePrefix("OK ").replace("|", "\n"), if (r.startsWith("OK")) DONE else MISS) }
    }, 12)
    sheet(null, if (was == null) "Connect a Trading Account" else was.optString("name"), null, content = col, primaryLabel = "Save", onPrimary = {
        val a = acc(); val s = secret()
        val becomesLive = mode == "live" && was?.optString("mode") != "live"
        val doSave = { tbg({ Trading.saveAccount(applicationContext, a, s); null }) { showBanner(if (mode == "live") "Saved as LIVE" else "Saved as PAPER", DONE) } }
        if (becomesLive) sheet(null, "Make It LIVE?", "Orders on “${name.ifBlank { "this account" }}” will use real money. Each LIVE order shows you what it is and its worst case and waits for your tap (or a plan you confirmed). Its limits start at the LIVE defaults (${TradeRules.money(TradeRules.DEFAULT.maxOrderUsd)} or ${TradeRules.DEFAULT.maxContracts.toInt()} contract an order, ${TradeRules.money(TradeRules.DEFAULT.maxDailyLossUsd)} loss a day, ${TradeRules.DEFAULT.maxOrdersPerDay} trades a day); change them in its limits. Losses are possible.",
            primaryLabel = "Make It LIVE", primaryColor = MISS, onPrimary = { doSave() }, secondaryLabel = "Keep It Paper")
        else doSave()
    }, secondaryLabel = "Cancel")
}

private fun MainActivity.tradeAccountOptions(a: JSONObject) {
    val l = TradeRules.limitsFor(a); val futures = a.optString("broker") == "projectx"
    var order = l.maxOrderUsd.toString(); var contracts = l.maxContracts.toInt().toString(); var loss = l.maxDailyLossUsd.toString(); var perDay = l.maxOrdersPerDay.toString()
    var open = l.maxOpenPositions.toString(); var symbols = l.symbols.joinToString(", "); var start = l.hours?.start ?: ""; var end = l.hours?.end ?: ""; var deriv = l.allowDerivatives
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(text("Your limits. Atlas can't change them, and refuses any order that breaks them.", 13f, MUTED))
    if (futures) col.add(field("Most contracts per order", contracts, numeric = true) { contracts = it }, 8) else col.add(field("Most dollars per order", order, decimal = true) { order = it }, 8)
    col.add(field("Most loss in a day (dollars)", loss, decimal = true) { loss = it }, 8)
    col.add(field("Most trades a day", perDay, numeric = true) { perDay = it }, 8)
    col.add(field("Most open positions", open, numeric = true) { open = it }, 8)
    col.add(field("Only these symbols (empty: any), e.g. BTCUSDT, MNQ", symbols) { symbols = it }, 8)
    col.add(field("Trading from (HH:MM, empty: any time)", start) { start = it }, 8)
    col.add(field("Trading until (HH:MM)", end) { end = it }, 8)
    if (futures) col.add(switchRow(null, "Allow Futures in LIVE", deriv) { deriv = it }, 10)
    var closeIt: () -> Unit = {}
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(secondary("Keys and Mode") { closeIt(); tradeConnect(a) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(secondary("Remove") { closeIt(); tbg({ Trading.removeAccount(applicationContext, a.optString("id")); null }) { showBanner("Removed ${a.optString("name")}") } },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    col.add(row, 14)
    // >>> autonomy: LIVE orders without a tap, inside limits the user sets here (off unless they do)
    if (a.optString("mode") == "live") {
        val st = Trading.standing(applicationContext, a.optString("id"))
        col.add(secondary(if (st != null) "Pre-Approved LIVE Trades: On" else "Pre-Approve LIVE Trades…") { closeIt(); tradeStanding(a) }, 10)
    }
    // <<< autonomy
    closeIt = sheet(null, "${if (a.optString("mode") == "live") "LIVE" else "PAPER"} · ${a.optString("name")}", null, content = col, primaryLabel = "Save Limits", onPrimary = {
        val lim = JSONObject()
        order.toDoubleOrNull()?.let { lim.put("maxOrderUsd", it) }; contracts.toDoubleOrNull()?.let { lim.put("maxContracts", it) }; loss.toDoubleOrNull()?.let { lim.put("maxDailyLossUsd", it) }
        perDay.toDoubleOrNull()?.let { lim.put("maxOrdersPerDay", it) }; open.toDoubleOrNull()?.let { lim.put("maxOpenPositions", it) }
        lim.put("symbols", JSONArray(symbols.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }))
        if (start.isNotBlank() && end.isNotBlank()) lim.put("hours", JSONObject().put("start", start.trim()).put("end", end.trim()))
        lim.put("allowDerivatives", deriv)
        tbg({ Trading.setLimits(applicationContext, a.optString("id"), lim); null }) { showBanner("Limits saved", DONE) }
    }, secondaryLabel = "Close")
}

/**
 * >>> autonomy: pre-approved LIVE trading. Orders inside these limits go without a tap; anything outside still asks.
 * Only this sheet sets them (no tool can), after a second, red confirmation. The stop switch turns them off.
 */
private fun MainActivity.tradeStanding(a: JSONObject) {
    val st = Trading.standing(applicationContext, a.optString("id")); val futures = a.optString("broker") == "projectx"; val l = TradeRules.limitsFor(a)
    var symbols = st?.optJSONArray("symbols")?.let { x -> (0 until x.length()).joinToString(", ") { x.optString(it) } } ?: ""
    var order = (st?.optDouble("maxOrderUsd") ?: minOf(25.0, l.maxOrderUsd)).toString(); var contracts = (st?.optDouble("maxContracts") ?: 1.0).toInt().toString()
    var loss = (st?.optDouble("maxDailyLossUsd") ?: minOf(20.0, l.maxDailyLossUsd)).toString(); var days = "7"
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(text("LIVE orders inside these limits go ahead without asking you each time. Anything bigger, on another instrument, past today's loss limit or after the end date still asks. Your account limits always apply. This is real money.", 13f, MUTED))
    col.add(field("Instruments (required), e.g. BTCUSDT, MNQ", symbols) { symbols = it }, 8)
    if (futures) col.add(field("Most contracts per order (account allows ${l.maxContracts.toInt()})", contracts, numeric = true) { contracts = it }, 8)
    else col.add(field("Most dollars per order (account allows ${TradeRules.money(l.maxOrderUsd)})", order, decimal = true) { order = it }, 8)
    col.add(field("Most loss in a day (dollars)", loss, decimal = true) { loss = it }, 8)
    col.add(field("For how many days (1–90)", days, numeric = true) { days = it }, 8)
    st?.let { col.addView(text("On until ${java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(it.optLong("expiresAt")))}.", 13f, MUTED)) }
    val lim = { JSONObject().put("symbols", symbols).put("maxOrderUsd", order.toDoubleOrNull() ?: 0.0).put("maxContracts", contracts.toDoubleOrNull() ?: 0.0)
        .put("maxDailyLossUsd", loss.toDoubleOrNull() ?: 0.0).put("days", days.toDoubleOrNull() ?: 7.0) }
    sheet(null, "Pre-Approve LIVE Trades", null, content = col, primaryLabel = "Review", onPrimary = {
        val x = lim()
        sheet(null, "Trade Real Money Without a Tap?", "Atlas may place LIVE orders on ${a.optString("name")} in ${symbols.ifBlank { "(no instruments)" }}, up to ${if (futures) "$contracts contract(s)" else TradeRules.money(x.optDouble("maxOrderUsd"))} each, losing at most ${TradeRules.money(x.optDouble("maxDailyLossUsd"))} in a day, for ${x.optDouble("days").toInt()} days. Losses are possible.",
            primaryLabel = "Pre-Approve", primaryColor = MISS, onPrimary = {
                tbg({ Trading.setStanding(applicationContext, a.optString("id"), x).second }) { err -> showBanner(err ?: "LIVE trades inside your limits won't ask now", if (err == null) DONE else MISS) }
            }, secondaryLabel = "Cancel")
    }, secondaryLabel = if (st != null) "Turn Off" else "Cancel", onSecondary = { if (st != null) tbg({ Trading.setStanding(applicationContext, a.optString("id"), null); null }) { showBanner("LIVE trades ask every time again", DONE) } })
}
