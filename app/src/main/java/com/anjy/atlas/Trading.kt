package com.anjy.atlas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Atlas trading on the phone: the same tools, limits and stop switch as the PC (desktop/tradecore.js), with the risk
 * engine in [TradeRules]. Brokers: Bybit spot (PAPER = Bybit Demo Trading) and prop firms on the ProjectX Gateway.
 *
 * The one rule: an order only leaves Atlas from [send], reached only after [TradeRules.check] passed with fresh numbers.
 * A LIVE order also needs the user's tap (a single-use ticket for that exact order, 3 minutes), unless a plan they
 * confirmed covers it. The model never approves anything and can't lift the stop switch. Keys: Android Keystore,
 * never synced, never shown. Every action goes into the Library (Trading/trading-log.csv).
 */
object Trading {
    private const val TAG = "AtlasTrading"
    private const val FILE = "trading.json"
    private const val SECRETS = "trading-keys.enc"
    private const val ALIAS = "atlas_trading"
    private const val TICKET_MS = 3 * 60_000L
    val names = setOf("trade_accounts", "trade_account", "trade_quote", "trade_history", "trade_order", "trade_close", "trade_cancel", "trade_plan", "trade_stop_all")
    private val asking = setOf("trade_order", "trade_close", "trade_plan")

    /** [unsure]: no (readable) answer came back, so a request that was sent may still have been carried out. */
    class TradeError(msg: String, val unsure: Boolean = false) : Exception(msg)
    /** A tool's answer: [out], or [need] (the summary the user must approve) with its [ticket]. */
    class Res(val out: String? = null, val need: String? = null, val ticket: String? = null, val title: String = "Allow This?", val button: String = "Allow")

    /** Set by the screen while Atlas is open: shows the summary and returns true only on the user's tap. */
    @Volatile var asker: (suspend (title: String, detail: String, button: String) -> Boolean)? = null

    // ================================================================ storage

    private val lock = Any()
    private var data: JSONObject? = null
    private var secretsCache: JSONObject? = null

    fun state(ctx: Context): JSONObject = synchronized(lock) {
        data?.let { return it }
        val f = AtomicFile(File(ctx.filesDir, FILE))
        val d = try { if (f.baseFile.exists()) JSONObject(String(f.readFully(), Charsets.UTF_8)) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (!d.has("accounts")) d.put("accounts", JSONArray())
        data = d; d
    }
    private fun save(ctx: Context, d: JSONObject) = synchronized(lock) {
        data = d
        val f = AtomicFile(File(ctx.filesDir, FILE)); val out = f.startWrite()
        try { out.write(d.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }
    private fun edit(ctx: Context, f: (JSONObject) -> Unit) = synchronized(lock) { val d = JSONObject(state(ctx).toString()); f(d); save(ctx, d) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    private fun secrets(ctx: Context): JSONObject = synchronized(lock) {
        secretsCache?.let { return it }
        val got = try {
            val f = AtomicFile(File(ctx.filesDir, SECRETS))
            if (!f.baseFile.exists()) JSONObject() else {
                val raw = f.readFully()
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
                JSONObject(String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
            }
        } catch (e: Exception) { Log.w(TAG, "keys unreadable", e); JSONObject() }
        secretsCache = got; got
    }
    private fun saveSecrets(ctx: Context, all: JSONObject) = synchronized(lock) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = c.doFinal(all.toString().toByteArray(Charsets.UTF_8))
        val f = AtomicFile(File(ctx.filesDir, SECRETS)); val out = f.startWrite()
        try { out.write(c.iv); out.write(body); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
        secretsCache = all
    }

    fun accounts(ctx: Context): List<JSONObject> = state(ctx).optJSONArray("accounts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    fun killed(ctx: Context) = state(ctx).optBoolean("killed")

    /** Adds or changes an account (from Me → Trading only). A key is only replaced when a new one is given. */
    fun saveAccount(ctx: Context, acc: JSONObject, secret: JSONObject?): String {
        val list = accounts(ctx).toMutableList()
        val was = list.firstOrNull { it.optString("id") == acc.optString("id") }
        val id = was?.optString("id") ?: ("acct-" + java.util.UUID.randomUUID().toString().take(10))
        val broker = if (acc.optString("broker") == "projectx") "projectx" else "bybit"
        val a = JSONObject().put("id", id).put("name", acc.optString("name").trim().take(60).ifEmpty { "Trading account" }).put("broker", broker)
            .put("mode", if (acc.optString("mode") == "live") "live" else "paper").put("updatedAt", System.currentTimeMillis())
        if (broker == "projectx") {
            val base = acc.optString("base").trim().ifEmpty { "https://api.topstepx.com" }
            if (!Regex("^https://[a-z0-9.-]+(:\\d+)?$", RegexOption.IGNORE_CASE).matches(base)) throw TradeError("The prop firm address must look like https://api.topstepx.com")
            a.put("base", base)
            acc.optString("remoteId").takeIf { it.isNotBlank() }?.toLongOrNull()?.let { a.put("remoteId", it) }
        }
        // becoming LIVE starts from the LIVE defaults: limits set for practice (the sheet saves every field it shows, PAPER's
        // practice-sized defaults included) don't carry over to real money
        val becomesLive = a.optString("mode") == "live" && was?.optString("mode") != "live"
        if (!becomesLive) (acc.optJSONObject("limits") ?: was?.optJSONObject("limits"))?.let { a.put("limits", it) }
        if (secret != null) { val all = JSONObject(secrets(ctx).toString()); all.put(id, secret); saveSecrets(ctx, all) }
        else if (was == null) throw TradeError("The keys are needed.")
        val i = list.indexOfFirst { it.optString("id") == id }; if (i >= 0) list[i] = a else list.add(a)
        edit(ctx) { it.put("accounts", JSONArray(list)) }
        brokers.clear()
        return id
    }
    fun removeAccount(ctx: Context, id: String) {
        edit(ctx) { d -> d.put("accounts", JSONArray(accounts(ctx).filter { it.optString("id") != id })) }
        val all = JSONObject(secrets(ctx).toString()); all.remove(id); saveSecrets(ctx, all); brokers.clear()
    }
    /** Limits are the user's alone (Me → Trading; never a tool). */
    fun setLimits(ctx: Context, id: String, limits: JSONObject) = edit(ctx) { d ->
        val list = accounts(ctx).map { if (it.optString("id") == id) JSONObject(it.toString()).put("limits", clean(limits)).put("updatedAt", System.currentTimeMillis()) else it }
        d.put("accounts", JSONArray(list))
    }.also { brokers.clear() }
    private fun clean(l: JSONObject): JSONObject = JSONObject().apply {
        fun n(k: String, lo: Double, hi: Double) { if (l.has(k)) l.optDouble(k).takeIf { it.isFinite() }?.let { put(k, it.coerceIn(lo, hi)) } }
        n("maxOrderUsd", 1.0, 1e7); n("maxContracts", 1.0, 100.0); n("maxDailyLossUsd", 1.0, 1e7); n("maxOpenPositions", 1.0, 50.0); n("maxOrdersPerDay", 1.0, 200.0)
        l.optJSONArray("symbols")?.let { a -> put("symbols", JSONArray((0 until a.length()).map { a.optString(it).trim().uppercase() }.filter { it.isNotEmpty() }.take(30))) }
        val h = l.optJSONObject("hours")
        put("hours", if (h != null && Regex("^\\d{1,2}:\\d{2}$").matches(h.optString("start")) && Regex("^\\d{1,2}:\\d{2}$").matches(h.optString("end"))) JSONObject().put("start", h.optString("start")).put("end", h.optString("end")).put("days", JSONArray()) else JSONObject.NULL)
        put("allowDerivatives", l.optBoolean("allowDerivatives"))
    }
    /** The user (never the model) turns trading back on. */
    /**
     * The user's pre-approved LIVE limits for an account: LIVE orders inside them go without a tap. Only Me → Trading
     * calls this, after the user's own tap on a warning sheet; no tool reaches it. null turns them off.
     * Returns the saved limits, or an error message.
     */
    fun setStanding(ctx: Context, accountId: String, lim: JSONObject?): Pair<JSONObject?, String?> {
        if (lim == null) {
            edit(ctx) { d -> (d.optJSONObject("standing") ?: JSONObject().also { d.put("standing", it) }).remove(accountId) }
            audit(ctx, accountId, "live", "limits", result = "cleared", note = "pre-approved LIVE limits turned off by the user"); return null to null
        }
        val a = accounts(ctx).firstOrNull { it.optString("id") == accountId } ?: return null to "No such account."
        val l = TradeRules.limitsFor(a)
        val syms = (lim.optJSONArray("symbols")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: lim.optString("symbols").split(Regex("[\\s,]+")))
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.take(10)
        if (syms.isEmpty()) return null to "List the instruments these limits cover."
        val days = lim.optDouble("days", 7.0).let { if (it.isFinite()) it else 7.0 }.coerceIn(1.0, 90.0)
        fun cap(k: String, max: Double) = lim.optDouble(k, 0.0).let { if (it.isFinite()) it else 0.0 }.coerceIn(0.0, max)
        val now = System.currentTimeMillis()
        val v = JSONObject().put("accountId", accountId).put("enabled", true).put("symbols", JSONArray(syms)).put("setAt", now).put("expiresAt", now + (days * 86_400_000).toLong())
            .put("maxOrderUsd", cap("maxOrderUsd", l.maxOrderUsd)).put("maxContracts", cap("maxContracts", l.maxContracts)).put("maxDailyLossUsd", cap("maxDailyLossUsd", l.maxDailyLossUsd))
        edit(ctx) { d -> (d.optJSONObject("standing") ?: JSONObject().also { d.put("standing", it) }).put(accountId, v) }
        audit(ctx, a.optString("name"), "live", "limits", result = "set", note = "pre-approved: ${syms.joinToString("/")}, ≤${TradeRules.money(v.optDouble("maxOrderUsd"))} or ${v.optDouble("maxContracts")} contract(s) per order, ≤${TradeRules.money(v.optDouble("maxDailyLossUsd"))} loss a day, until ${java.util.Date(v.optLong("expiresAt"))}")
        return v to null
    }
    fun standing(ctx: Context, accountId: String): JSONObject? = state(ctx).optJSONObject("standing")?.optJSONObject(accountId)?.takeIf { it.optLong("expiresAt") > System.currentTimeMillis() }

    // ---- the stop switch across devices (sync kind "tradestop"; desktop/renderer/trade.js)
    /** Registers the kind (before anything is pulled). A record from the other device can only stop trading here, never restart it. */
    fun registerSync() {
        try {
            Sync.register("tradestop", Sync.Kind(list = { ctx -> stopRecords(ctx) }, apply = { ctx, id, rec -> stopFromOther(ctx, id, rec) }, absentMeansDeleted = false))
        } catch (_: Exception) {}
    }
    private fun stopRecords(ctx: Context): Map<String, JSONObject> = try {
        val me = Store.read(ctx) { s -> s.deviceId }
        TradeRules.stopRecord(state(ctx))?.takeIf { me.isNotEmpty() }?.let { mapOf(me to it) } ?: emptyMap()
    } catch (e: Exception) { Log.w(TAG, "stop record", e); emptyMap() }
    /**
     * The other device's stop switch came on: no new orders here either, plans and pre-approved limits off, until the user
     * turns trading back on here. Open orders on this phone's accounts are left as they are.
     */
    private fun stopFromOther(ctx: Context, id: String, rec: JSONObject?) {
        if (rec == null || id == Store.read(ctx) { s -> s.deviceId }) return
        var stopped = false
        edit(ctx) { d ->
            val stop = TradeRules.stopFromOther(d, rec) ?: return@edit
            d.put("stops", TradeRules.addStop(d.optJSONArray("stops"), stop))
            if (d.optBoolean("killed")) return@edit                    // already stopped here
            d.put("killed", true).put("killedAt", System.currentTimeMillis()).put("killReason", "stopped on your other device")
                .put("plans", JSONObject()).put("standing", JSONObject()).put("stopId", stop)
            stopped = true
        }
        if (!stopped) return
        audit(ctx, "*", "", "stop_all", null, "stopped", note = "stopped on your other device")
        TradingNotify.stopped(ctx)
    }

    fun resume(ctx: Context) { edit(ctx) { it.put("killed", false).put("resumedAt", System.currentTimeMillis()) }; audit(ctx, "*", "", "resume", result = "resumed", note = "by the user") }

    // ---- the audit log, in the Library (bot/Trading/trading-log.csv)
    private val COLS = listOf("at", "account", "mode", "action", "symbol", "side", "qty", "type", "price", "stopLoss", "takeProfit", "result", "orderId", "note")
    private fun csv(v: Any?): String = (v ?: "").toString().let { if (Regex("[\",\n]").containsMatchIn(it)) "\"" + it.replace("\"", "\"\"") + "\"" else it }
    private fun audit(ctx: Context, account: String, mode: String, action: String, o: JSONObject? = null, result: String, orderId: String = "", note: String = "") {
        try {
            val dir = File(File(ctx.filesDir, "bot"), "Trading").apply { mkdirs() }
            val f = File(dir, "trading-log.csv")
            if (!f.exists()) f.writeText(COLS.joinToString(",") + "\n")
            val row = mapOf("at" to Instant.now().toString(), "account" to account, "mode" to mode, "action" to action, "symbol" to o?.optString("symbol"), "side" to o?.optString("side"),
                "qty" to o?.let { x -> x.optString("qty").ifEmpty { x.optString("notional") } }, "type" to o?.optString("type"), "price" to o?.opt("price")?.takeIf { it != JSONObject.NULL },
                "stopLoss" to o?.opt("stopLoss")?.takeIf { it != JSONObject.NULL }, "takeProfit" to o?.opt("takeProfit")?.takeIf { it != JSONObject.NULL }, "result" to result, "orderId" to orderId, "note" to note)
            f.appendText(COLS.joinToString(",") { csv(row[it]) } + "\n")
        } catch (e: Exception) { Log.w(TAG, "audit", e) }
    }

    // ================================================================ the brokers

    private class Http(val code: Int, val body: String)
    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): Http {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = method; con.connectTimeout = 15_000; con.readTimeout = 15_000
            headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
            if (body != null) { con.doOutput = true; con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = con.responseCode
            val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, text)
        } catch (e: java.io.IOException) { throw TradeError("Couldn't reach ${URL(url).host} (${e.javaClass.simpleName}).", unsure = true) }
        finally { con.disconnect() }
    }
    private fun json(h: Http, who: String): JSONObject = try { JSONObject(h.body) } catch (_: Exception) { throw TradeError("$who sent back something unreadable (HTTP ${h.code}).", unsure = true) }

    internal interface Broker {
        fun inst(symbol: String): JSONObject
        fun quote(symbol: String): JSONObject
        /** { equity, cash, positions:[{symbol,size,...}], orders:[{id,symbol,side,type,qty,price}] } */
        fun account(): JSONObject
        fun place(o: JSONObject, tag: String): Pair<String, List<String>>
        fun cancel(id: String, symbol: String)
        fun closeOrder(symbol: String): JSONObject?
        fun closeContract(contractId: String) { throw TradeError("Not supported.") }
        fun history(days: Int): List<String>
        fun remoteAccounts(): JSONArray = JSONArray()
    }

    /** Bybit V5, spot only (no margin): bybit-exchange.github.io/docs/v5 . PAPER = Demo Trading. */
    internal class Bybit(val key: String, val secret: String, val base: String) : Broker {
        private var offset = 0L
        private val insts = ConcurrentHashMap<String, JSONObject>()
        private fun call(method: String, path: String, params: Map<String, Any?>, signed: Boolean, retried: Boolean = false): JSONObject {
            val ts = (System.currentTimeMillis() + offset).toString(); val recv = "5000"
            val clean = params.filterValues { it != null && it.toString().isNotEmpty() }
            var url = base + path; var body: String? = null; val toSign: String
            if (method == "GET") {
                val q = clean.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }
                if (q.isNotEmpty()) url += "?$q"; toSign = q
            } else { body = JSONObject(clean).toString(); toSign = body }
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (signed) { headers["X-BAPI-API-KEY"] = key; headers["X-BAPI-TIMESTAMP"] = ts; headers["X-BAPI-RECV-WINDOW"] = recv; headers["X-BAPI-SIGN"] = TradeRules.hmacHex(secret, TradeRules.bybitPayload(ts, key, recv, toSign)) }
            val h = http(url, method, headers, body)
            if (h.code == 401 || h.code == 403) throw TradeError("Bybit turned down the API key (check it, its permissions and its IP restriction).")
            val j = json(h, "Bybit")
            val rc = j.optInt("retCode", -1)
            if (rc == 10002 && !retried) {
                try { val t = json(http("$base/v5/market/time", "GET", emptyMap(), null), "Bybit"); offset = t.optLong("time", System.currentTimeMillis()) - System.currentTimeMillis() } catch (_: Exception) {}
                return call(method, path, params, signed, true)
            }
            if (rc != 0) throw TradeError("Bybit said: ${j.optString("retMsg").ifEmpty { "error" }} (code $rc).")
            return j.optJSONObject("result") ?: JSONObject()
        }
        override fun inst(symbol: String): JSONObject {
            val s = symbol.uppercase(); insts[s]?.let { return it }
            val i = call("GET", "/v5/market/instruments-info", mapOf("category" to "spot", "symbol" to s), false).optJSONArray("list")?.optJSONObject(0)
                ?: throw TradeError("Bybit has no spot market called $s. Use the pair name, like BTCUSDT.")
            val lot = i.optJSONObject("lotSizeFilter") ?: JSONObject(); val pf = i.optJSONObject("priceFilter") ?: JSONObject()
            return JSONObject().put("symbol", s).put("tickSize", pf.optString("tickSize")).put("qtyStep", lot.optString("basePrecision")).put("minQty", lot.optString("minOrderQty"))
                .put("minNotional", lot.optString("minOrderAmt")).put("pointValue", 1).put("futures", false).put("tpslLimitOnly", true).also { insts[s] = it }
        }
        override fun quote(symbol: String): JSONObject {
            val t = call("GET", "/v5/market/tickers", mapOf("category" to "spot", "symbol" to symbol.uppercase()), false).optJSONArray("list")?.optJSONObject(0) ?: throw TradeError("No price for $symbol on Bybit.")
            return JSONObject().put("symbol", t.optString("symbol")).put("bid", t.optString("bid1Price").toDouble()).put("ask", t.optString("ask1Price").toDouble()).put("last", t.optString("lastPrice").toDouble())
        }
        override fun account(): JSONObject {
            val w = call("GET", "/v5/account/wallet-balance", mapOf("accountType" to "UNIFIED"), true).optJSONArray("list")?.optJSONObject(0) ?: JSONObject()
            val stable = setOf("USDT", "USDC", "USD", "DAI", "FDUSD")
            val positions = JSONArray(); var cash = 0.0
            w.optJSONArray("coin")?.let { a -> for (i in 0 until a.length()) { val c = a.getJSONObject(i); val usd = c.optString("usdValue").toDoubleOrNull() ?: 0.0
                if (c.optString("coin") in stable) cash += usd
                else if (usd > 1) positions.put(JSONObject().put("symbol", c.optString("coin") + "USDT").put("size", c.optString("walletBalance")).put("free", (c.optString("walletBalance").toDoubleOrNull() ?: 0.0) - (c.optString("locked").toDoubleOrNull() ?: 0.0)).put("usd", usd)) } }
            val orders = JSONArray()
            call("GET", "/v5/order/realtime", mapOf("category" to "spot"), true).optJSONArray("list")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                orders.put(JSONObject().put("id", o.optString("orderId")).put("symbol", o.optString("symbol")).put("side", o.optString("side").lowercase()).put("type", o.optString("orderType").lowercase()).put("qty", o.optString("qty")).put("price", o.optString("price"))) } }
            // an equity that couldn't be read is left out (optDouble: NaN, as on the PC): read as 0 it started the day at $0,
            // and the daily loss limit never stopped anything that day (NaN itself can't go in a JSONObject)
            return JSONObject().put("cash", cash).put("positions", positions).put("orders", orders)
                .also { o -> w.optString("totalEquity").toDoubleOrNull()?.takeIf { it.isFinite() }?.let { o.put("equity", it) } }
        }
        override fun place(o: JSONObject, tag: String): Pair<String, List<String>> {
            if (o.optString("type") == "stop") throw TradeError("Stop entry orders aren't supported on Bybit spot here. Use a market or limit order with a stop loss.")
            val p = linkedMapOf<String, Any?>("category" to "spot", "symbol" to o.optString("symbol"), "side" to if (o.optString("side") == "buy") "Buy" else "Sell",
                "orderType" to if (o.optString("type") == "limit") "Limit" else "Market", "qty" to o.optString("qty"), "isLeverage" to 0, "orderLinkId" to tag)
            if (o.optString("type") == "market") p["marketUnit"] = "baseCoin"
            if (o.optString("type") == "limit") { p["price"] = o.optString("price"); p["timeInForce"] = "GTC" }
            if (!o.isNull("stopLoss")) p["stopLoss"] = o.optString("stopLoss")
            if (!o.isNull("takeProfit")) p["takeProfit"] = o.optString("takeProfit")
            return call("POST", "/v5/order/create", p, true).optString("orderId") to emptyList<String>()
        }
        override fun cancel(id: String, symbol: String) { call("POST", "/v5/order/cancel", mapOf("category" to "spot", "symbol" to symbol.uppercase(), "orderId" to id), true) }
        override fun closeOrder(symbol: String): JSONObject? {
            val s = symbol.uppercase(); val pos = account().optJSONArray("positions")
            for (i in 0 until (pos?.length() ?: 0)) { val p = pos!!.getJSONObject(i); if (p.optString("symbol") == s) return JSONObject().put("symbol", s).put("side", "sell").put("qty", p.optDouble("free")).put("type", "market").put("closing", true) }
            return null
        }
        override fun history(days: Int): List<String> {
            val l = call("GET", "/v5/execution/list", mapOf("category" to "spot", "limit" to 50, "startTime" to System.currentTimeMillis() - minOf(days, 7) * 86_400_000L), true).optJSONArray("list") ?: return emptyList()
            return (0 until l.length()).map { val x = l.getJSONObject(it); "${java.util.Date(x.optString("execTime").toLongOrNull() ?: 0L)} ${x.optString("side").lowercase()} ${x.optString("execQty")} ${x.optString("symbol")} @ ${x.optString("execPrice")} fee ${x.optString("execFee")}" }
        }
    }

    /** ProjectX Gateway (TopstepX and other prop firms): gateway.docs.projectx.com . Futures. */
    internal class ProjectX(val userName: String, val apiKey: String, val base: String, val accountId: Long?) : Broker {
        private var token: String? = null; private var tokenAt = 0L
        private val contracts = ConcurrentHashMap<String, JSONObject>()
        private val TYPE = mapOf("limit" to 1, "market" to 2, "stop" to 4)
        private fun login() {
            val j = json(http("$base/api/Auth/loginKey", "POST", mapOf("Content-Type" to "application/json", "accept" to "text/plain"), JSONObject().put("userName", userName).put("apiKey", apiKey).toString()), "The prop firm")
            if (!j.optBoolean("success") || j.optString("token").isEmpty()) throw TradeError("The prop firm turned down the sign-in: ${j.optString("errorMessage").ifEmpty { "check the username (not the email) and the API key" }}.")
            token = j.optString("token"); tokenAt = System.currentTimeMillis()
        }
        class PxError(msg: String, val pxCode: Int) : Exception(msg)
        private fun call(path: String, body: JSONObject, retried: Boolean = false): JSONObject {
            if (token == null || System.currentTimeMillis() - tokenAt > 23 * 3600_000L) login()
            val h = http(base + path, "POST", mapOf("Content-Type" to "application/json", "accept" to "text/plain", "Authorization" to "Bearer $token"), body.toString())
            if (h.code == 401 && !retried) { token = null; return call(path, body, true) }
            if (h.code == 429) throw TradeError("The prop firm says too many requests; wait a little and try again.")
            val j = json(h, "The prop firm")
            if (!j.optBoolean("success", true) || j.optInt("errorCode", 0) != 0) throw PxError("The prop firm said: ${j.optString("errorMessage").ifEmpty { "error" }} (code ${j.optInt("errorCode")}).", j.optInt("errorCode"))
            return j
        }
        private fun safe(path: String, body: JSONObject): JSONObject = try { call(path, body) } catch (e: PxError) { throw TradeError(e.message ?: "error") }
        private fun acct(): Long = accountId ?: throw TradeError("No trading account is chosen for this connection yet (Me → Trading).")
        private fun contract(symbol: String): JSONObject {
            val s = symbol.uppercase(); contracts[s]?.let { return it }
            val c = if (s.startsWith("CON.")) safe("/api/Contract/searchById", JSONObject().put("contractId", s)).optJSONObject("contract")
            else safe("/api/Contract/search", JSONObject().put("searchText", s).put("live", false)).optJSONArray("contracts")?.let { a ->
                val list = (0 until a.length()).map { a.getJSONObject(it) }
                list.firstOrNull { it.optString("name").uppercase() == s } ?: list.firstOrNull { it.optBoolean("activeContract") && it.optString("name").uppercase().startsWith(s) } ?: list.firstOrNull { it.optBoolean("activeContract") }
            }
            c ?: throw TradeError("No contract called $s found. Try the root (NQ, ES, GC, MNQ) or the full name (NQZ6).")
            val tick = c.optDouble("tickSize"); val tv = c.optDouble("tickValue")
            return JSONObject().put("id", c.optString("id")).put("symbol", c.optString("name").uppercase()).put("tickSize", BigStr(tick)).put("tickValue", tv)
                .put("pointValue", tv / tick).put("futures", true).put("qtyStep", "1").put("minQty", "1").also { contracts[s] = it; contracts[it.optString("symbol")] = it }
        }
        private fun BigStr(d: Double) = java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
        override fun remoteAccounts(): JSONArray = safe("/api/Account/search", JSONObject().put("onlyActiveAccounts", true)).optJSONArray("accounts") ?: JSONArray()
        override fun inst(symbol: String) = contract(symbol)
        override fun quote(symbol: String): JSONObject {
            val c = contract(symbol); val now = System.currentTimeMillis()
            val bars = safe("/api/History/retrieveBars", JSONObject().put("contractId", c.optString("id")).put("live", false).put("startTime", Instant.ofEpochMilli(now - 20 * 60_000).toString())
                .put("endTime", Instant.ofEpochMilli(now).toString()).put("unit", 2).put("unitNumber", 1).put("limit", 20).put("includePartialBar", true)).optJSONArray("bars")
            val b = (0 until (bars?.length() ?: 0)).map { bars!!.getJSONObject(it) }.maxByOrNull { it.optString("t") } ?: throw TradeError("No recent price for ${c.optString("symbol")} (the market may be closed).")
            val px = b.optDouble("c")
            return JSONObject().put("symbol", c.optString("symbol")).put("bid", px).put("ask", px).put("last", px).put("note", "last 1-minute close")
        }
        override fun account(): JSONObject {
            val id = acct()
            val a = remoteAccounts().let { l -> (0 until l.length()).map { l.getJSONObject(it) }.firstOrNull { it.optLong("id") == id } } ?: throw TradeError("Account $id isn't active at the prop firm any more.")
            val byId = contracts.values.associate { it.optString("id") to it.optString("symbol") }
            val positions = JSONArray(); safe("/api/Position/searchOpen", JSONObject().put("accountId", id)).optJSONArray("positions")?.let { p -> for (i in 0 until p.length()) { val x = p.getJSONObject(i)
                positions.put(JSONObject().put("symbol", byId[x.optString("contractId")] ?: x.optString("contractId")).put("contractId", x.optString("contractId")).put("size", x.optInt("size")).put("type", x.optInt("type")).put("avgPrice", x.optDouble("averagePrice"))) } }
            val orders = JSONArray(); safe("/api/Order/searchOpen", JSONObject().put("accountId", id)).optJSONArray("orders")?.let { o -> for (i in 0 until o.length()) { val x = o.getJSONObject(i)
                orders.put(JSONObject().put("id", x.optString("id")).put("symbol", byId[x.optString("contractId")] ?: x.optString("contractId")).put("side", if (x.optInt("side") == 0) "buy" else "sell")
                    .put("type", TYPE.entries.firstOrNull { it.value == x.optInt("type") }?.key ?: x.optString("type")).put("qty", x.optString("size")).put("price", x.opt("limitPrice")?.takeIf { it != JSONObject.NULL } ?: x.opt("stopPrice"))) } }
            // (a balance that couldn't be read is left out: NaN can't go in a JSONObject, and closing must still work)
            return JSONObject().put("positions", positions).put("orders", orders)
                .also { o -> a.optDouble("balance").takeIf { it.isFinite() }?.let { o.put("equity", it).put("cash", it) } }
        }
        override fun place(o: JSONObject, tag: String): Pair<String, List<String>> {
            val id = acct(); val c = contract(o.optString("symbol")); val tick = c.optString("tickSize").toDouble()
            val side = if (o.optString("side") == "buy") 0 else 1
            fun body() = JSONObject().put("accountId", id).put("contractId", c.optString("id")).put("type", TYPE[o.optString("type")] ?: 2).put("side", side).put("size", o.optString("qty").toDouble().toInt())
                .put("limitPrice", if (o.optString("type") == "limit") o.optString("price").toDouble() else JSONObject.NULL).put("stopPrice", if (o.optString("type") == "stop") o.optString("stop").toDouble() else JSONObject.NULL)
                .put("trailPrice", JSONObject.NULL).put("customTag", tag)
            val ref = o.optDouble("refPrice")
            fun ticks(p: String) = maxOf(1, Math.round(kotlin.math.abs(ref - p.toDouble()) / tick).toInt())
            val withBrackets = body().apply {
                if (!o.isNull("stopLoss")) put("stopLossBracket", JSONObject().put("ticks", ticks(o.optString("stopLoss"))).put("type", 4))
                if (!o.isNull("takeProfit")) put("takeProfitBracket", JSONObject().put("ticks", ticks(o.optString("takeProfit"))).put("type", 1))
            }
            try { return call("/api/Order/place", withBrackets).optString("orderId") to emptyList<String>() }
            catch (e: PxError) { if (e.pxCode != 2 || (o.isNull("stopLoss") && o.isNull("takeProfit"))) throw TradeError(e.message ?: "error") }
            // the stop and target can only go as their own orders. That is safe once a market entry has filled; a limit or stop
            // entry may not fill for a while, and a stop or target resting without its position would open one the other way
            if (o.optString("type") != "market") throw TradeError("This prop firm account doesn't take a stop loss or target attached to an order (it's in Position Brackets mode), so a limit or stop entry can't carry its protection. Nothing was placed. Use a market order, or turn on Auto OCO Brackets in the platform's Risk Settings.")
            // brackets refused (Position Brackets mode): the entry, then a protective stop as its own order
            val entry = safe("/api/Order/place", body()).optString("orderId"); val notes = mutableListOf<String>()
            val opp = 1 - side; val size = o.optString("qty").toDouble().toInt()
            if (!o.isNull("stopLoss")) {
                try { call("/api/Order/place", JSONObject().put("accountId", id).put("contractId", c.optString("id")).put("type", 4).put("side", opp).put("size", size).put("limitPrice", JSONObject.NULL).put("stopPrice", o.optString("stopLoss").toDouble()).put("trailPrice", JSONObject.NULL).put("customTag", "$tag-sl")); notes.add("stop placed as its own order") }
                catch (e: Exception) { try { call("/api/Position/closeContract", JSONObject().put("accountId", id).put("contractId", c.optString("id"))) } catch (_: Exception) {}
                    throw TradeError("The entry filled but the protective stop was refused (${e.message}), so Atlas closed the position straight away.") }
            }
            if (!o.isNull("takeProfit")) try { call("/api/Order/place", JSONObject().put("accountId", id).put("contractId", c.optString("id")).put("type", 1).put("side", opp).put("size", size).put("limitPrice", o.optString("takeProfit").toDouble()).put("stopPrice", JSONObject.NULL).put("trailPrice", JSONObject.NULL).put("customTag", "$tag-tp")); notes.add("take profit placed as its own order") } catch (e: Exception) { notes.add("take profit could not be placed: ${e.message}") }
            return entry to notes
        }
        override fun cancel(id: String, symbol: String) { safe("/api/Order/cancel", JSONObject().put("accountId", acct()).put("orderId", id.toLong())) }
        override fun closeOrder(symbol: String): JSONObject? {
            val c = contract(symbol); val pos = account().optJSONArray("positions")
            for (i in 0 until (pos?.length() ?: 0)) { val p = pos!!.getJSONObject(i); if (p.optString("contractId") == c.optString("id")) return JSONObject().put("symbol", c.optString("symbol")).put("side", if (p.optInt("type") == 2) "buy" else "sell").put("qty", p.optInt("size")).put("type", "market").put("closing", true).put("closeContract", c.optString("id")) }
            return null
        }
        override fun closeContract(contractId: String) { safe("/api/Position/closeContract", JSONObject().put("accountId", acct()).put("contractId", contractId)) }
        override fun history(days: Int): List<String> {
            val t = safe("/api/Trade/search", JSONObject().put("accountId", acct()).put("startTimestamp", Instant.ofEpochMilli(System.currentTimeMillis() - days * 86_400_000L).toString()).put("endTimestamp", JSONObject.NULL)).optJSONArray("trades") ?: return emptyList()
            return (0 until t.length()).map { t.getJSONObject(it) }.filter { !it.optBoolean("voided") }.map { x -> "${x.optString("creationTimestamp")} ${if (x.optInt("side") == 0) "buy" else "sell"} ${x.optString("size")} ${x.optString("contractId")} @ ${x.optString("price")}${if (x.isNull("profitAndLoss")) "" else " P&L " + x.optString("profitAndLoss")}" }
        }
    }

    private val brokers = ConcurrentHashMap<String, Broker>()
    private fun broker(ctx: Context, a: JSONObject): Broker = brokers.getOrPut(a.optString("id") + ":" + a.optLong("updatedAt")) {
        val s = secrets(ctx).optJSONObject(a.optString("id")) ?: throw TradeError("The keys for ${a.optString("name")} are missing; connect it again in Me → Trading.")
        make(a, s)
    }
    private fun make(a: JSONObject, s: JSONObject): Broker = if (a.optString("broker") == "projectx")
        ProjectX(s.optString("userName"), s.optString("apiKey"), a.optString("base").ifEmpty { "https://api.topstepx.com" }, if (a.has("remoteId") && !a.isNull("remoteId")) a.optLong("remoteId") else null)
    else Bybit(s.optString("key"), s.optString("secret"), if (a.optString("mode") == "live") "https://api.bybit.com" else "https://api-demo.bybit.com")

    /** Checks a connection (Me → Trading); for ProjectX lists the firm's accounts to pick from. */
    suspend fun test(ctx: Context, acc: JSONObject, secret: JSONObject?): String = withContext(Dispatchers.IO) {
        try {
            val s = secret ?: secrets(ctx).optJSONObject(acc.optString("id")) ?: return@withContext "Error: the keys are needed."
            // a prop firm's address must be https (as saveAccount): the keys never go to a mistyped or plain-http address
            if (acc.optString("broker") == "projectx" && !Regex("^https://[a-z0-9.-]+(:\\d+)?$", RegexOption.IGNORE_CASE).matches(acc.optString("base").trim().ifEmpty { "https://api.topstepx.com" }))
                return@withContext "Error: the prop firm address must look like https://api.topstepx.com"
            val b = make(JSONObject(acc.toString()).put("base", acc.optString("base").trim()), s)
            if (acc.optString("broker") == "projectx") { val l = b.remoteAccounts(); "OK " + (0 until l.length()).joinToString("|") { val x = l.getJSONObject(it); "${x.optLong("id")}:${x.optString("name")} · ${TradeRules.money(x.optDouble("balance"))}" } }
            else "OK Works. Equity ${TradeRules.money(b.account().optDouble("equity"))}."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ================================================================ what each tool does (same as desktop/tradecore.js)

    private fun label(a: JSONObject) = "${if (a.optString("mode") == "live") "LIVE" else "PAPER"} · ${a.optString("name")}"
    private fun pick(ctx: Context, ref: String?): JSONObject {
        val list = accounts(ctx)
        if (list.isEmpty()) throw TradeError("No trading account is connected yet. The user can connect one in Me → Trading (Bybit, or a prop firm on ProjectX such as TopstepX).")
        if (ref.isNullOrBlank()) return list.singleOrNull() ?: throw TradeError("Say which account: ${list.joinToString(", ") { it.optString("name") }}.")
        val r = ref.lowercase()
        return list.firstOrNull { it.optString("id") == ref } ?: list.firstOrNull { it.optString("name").lowercase() == r }
            ?: list.filter { it.optString("name").lowercase().contains(r) }.singleOrNull()
            ?: throw TradeError("There's no trading account called \"$ref\". Connected: ${list.joinToString(", ") { it.optString("name") }}.")
    }
    private fun localNow(): Triple<String, Int, Int> { val t = LocalDateTime.now(); return Triple(t.toLocalDate().toString(), t.hour * 60 + t.minute, t.dayOfWeek.value) }
    private fun dayState(ctx: Context, a: JSONObject, equity: Double): JSONObject {
        val day = localNow().first; var out = JSONObject()
        edit(ctx) { d ->
            val days = d.optJSONObject("days") ?: JSONObject().also { d.put("days", it) }
            val cur = days.optJSONObject(a.optString("id"))
            // no equity when the day began: the first real reading that day starts it (traderules.js rollDay); NaN can't go in a JSONObject
            out = if (cur != null && cur.optString("day") == day) cur.also { if (!it.optDouble("dayStartEquity").isFinite() && equity.isFinite()) it.put("dayStartEquity", equity) }
                else JSONObject().put("day", day).put("ordersToday", 0).also { if (equity.isFinite()) it.put("dayStartEquity", equity) }
            days.put(a.optString("id"), out)
        }
        return out
    }
    private fun planFor(ctx: Context, a: JSONObject): JSONObject? = state(ctx).optJSONObject("plans")?.optJSONObject(a.optString("id"))?.takeIf { it.optLong("expiresAt") == 0L || it.optLong("expiresAt") > System.currentTimeMillis() }

    private class Assessed(val v: TradeRules.Verdict, val market: JSONObject, val b: Broker)
    private fun assess(ctx: Context, a: JSONObject, order: JSONObject): Assessed {
        val b = broker(ctx, a)
        val inst = b.inst(order.optString("symbol")); val market = b.quote(inst.optString("symbol")); val acct = b.account()
        val d = dayState(ctx, a, acct.optDouble("equity")); val (_, minute, iso) = localNow()
        val open = acct.optJSONArray("positions")?.let { p -> JSONArray((0 until p.length()).map { p.getJSONObject(it).optString("symbol") }) } ?: JSONArray()
        val st = JSONObject().put("killed", killed(ctx)).put("day", d.optString("day")).put("dayStartEquity", d.optDouble("dayStartEquity").takeIf { it.isFinite() } ?: JSONObject.NULL).put("equity", acct.optDouble("equity").takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("ordersToday", d.optInt("ordersToday")).put("openSymbols", open).put("minuteOfDay", minute).put("isoDay", iso).put("now", System.currentTimeMillis())
            .put("standing", state(ctx).optJSONObject("standing")?.optJSONObject(a.optString("id")) ?: JSONObject.NULL)
        val o = JSONObject(order.toString()).put("symbol", inst.optString("symbol"))
        return Assessed(TradeRules.check(o, inst, market, a, st, planFor(ctx, a)), market, b)
    }

    private fun send(ctx: Context, a: JSONObject, v: TradeRules.Verdict, s: Assessed, closeContract: String?): String {
        // the one place an order leaves Atlas
        // the price it fills at: a stop entry fills at its stop, so its brackets are measured from there (as check() measured the risk)
        val n = v.order!!
        val quoted = (if (n.optString("side") == "buy") s.market.optDouble("ask") else s.market.optDouble("bid")).takeIf { it > 0 } ?: s.market.optDouble("last")
        val o = JSONObject(n.toString()).put("refPrice", when (n.optString("type")) { "limit" -> n.optString("price").toDouble(); "stop" -> n.optString("stop").toDouble(); else -> quoted })
        val tag = "atlas-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + (1000..9999).random()
        fun counted() = edit(ctx) { d ->
            if (!o.optBoolean("closing")) d.optJSONObject("days")?.optJSONObject(a.optString("id"))?.let { it.put("ordersToday", it.optInt("ordersToday") + 1) }
            if (v.coveredByPlan) d.optJSONObject("plans")?.optJSONObject(a.optString("id"))?.let { it.put("used", it.optInt("used") + 1) }
        }
        val (id, notes) = try {
            if (o.optBoolean("closing") && closeContract != null) { s.b.closeContract(closeContract); "close" to emptyList<String>() } else s.b.place(o, tag)
        } catch (e: TradeError) {
            // no (readable) answer after the order went out: it may be at the broker. Counted against today's limits (fail
            // closed) and reported as unknown, so it is checked rather than sent a second time
            if (!e.unsure) throw e
            counted()
            audit(ctx, a.optString("name"), a.optString("mode"), if (o.optBoolean("closing")) "close" else "order", o, "unknown", tag, e.message ?: "")
            if (a.optString("mode") == "live") TradingNotify.unsure(ctx, v.summary)
            return "Not sure it went through: ${e.message} The order was sent, so it may have been placed. ${v.summary}\nDon't send it again: check trade_account (open orders and positions) first.${if (a.optString("mode") == "live") " This was a LIVE order with real money." else ""}"
        }
        counted()
        audit(ctx, a.optString("name"), a.optString("mode"), if (o.optBoolean("closing")) "close" else "order", o, "sent", id, notes.joinToString("; ").ifEmpty { if (v.coveredByPlan) "inside the confirmed plan" else if (v.coveredByLimits) "inside your pre-approved LIVE limits" else "" })
        TradingNotify.sent(ctx, a.optString("mode") == "live", v.summary)
        return "Sent. ${v.summary}\nOrder id: $id${if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else ""}\n${if (a.optString("mode") == "live") "This was a LIVE order with real money." else "PAPER: no real money."} Check fills with trade_account."
    }

    private class Ticket(val kind: String, val accountId: String, val payload: JSONObject, val expires: Long)
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private fun ticketFor(kind: String, a: JSONObject, payload: JSONObject): String {
        tickets.entries.removeIf { it.value.expires < System.currentTimeMillis() }
        val id = "t" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        tickets[id] = Ticket(kind, a.optString("id"), payload, System.currentTimeMillis() + TICKET_MS); return id
    }
    private fun takeTicket(ctx: Context, id: String, kind: String): Pair<Ticket, JSONObject> {
        val t = tickets.remove(id)
        if (t == null || t.kind != kind) throw TradeError("That approval has expired or was already used. Ask again.")
        if (t.expires < System.currentTimeMillis()) throw TradeError("That approval has expired (3 minutes). Check the price again and ask again.")
        val a = accounts(ctx).firstOrNull { it.optString("id") == t.accountId } ?: throw TradeError("That account was removed.")
        return t to a
    }
    private fun refused(ctx: Context, a: JSONObject, order: JSONObject, v: TradeRules.Verdict): Res {
        audit(ctx, a.optString("name"), a.optString("mode"), "refused", order, "refused", note = v.reasons.joinToString(" ") { it.code })
        return Res("Not done (${label(a)}): ${v.reasons.joinToString(" ") { it.text }}")
    }

    /** Runs a trading tool. [prepare]: only say whether it needs the user's OK (nothing is sent). [ticket]: the user approved. */
    suspend fun run(ctx: Context, name: String, args: JSONObject, prepare: Boolean = false, ticket: String? = null): Res = withContext(Dispatchers.IO) {
        try {
            if (prepare && name !in asking) return@withContext Res(out = "ok")
            when (name) {
                "trade_accounts" -> accountsList(ctx)
                "trade_account" -> accountInfo(ctx, args)
                "trade_quote" -> pick(ctx, args.optString("account")).let { a -> val b = broker(ctx, a); val i = b.inst(args.optString("symbol")); val q = b.quote(i.optString("symbol"))
                    Res("${q.optString("symbol")} (${if (a.optString("broker") == "bybit") "Bybit" else "ProjectX"}${q.optString("note").let { if (it.isNotEmpty()) ", $it" else "" }}): bid ${q.opt("bid")}, ask ${q.opt("ask")}, last ${q.opt("last")}.${if (i.optBoolean("futures")) " Tick ${i.optString("tickSize")} = $${i.optDouble("tickValue")}." else ""}") }
                "trade_history" -> pick(ctx, args.optString("account")).let { a -> val h = broker(ctx, a).history(args.optInt("days", 7).coerceIn(1, 30))
                    Res(if (h.isEmpty()) "${label(a)}: no fills in that time." else "${label(a)}, recent fills:\n" + h.take(40).joinToString("\n") { "- $it" }) }
                "trade_order" -> order(ctx, args, prepare, ticket)
                "trade_close" -> close(ctx, args, prepare, ticket)
                "trade_cancel" -> cancel(ctx, args)
                "trade_plan" -> plan(ctx, args, prepare, ticket)
                "trade_stop_all" -> stopAll(ctx, args.optString("reason"))
                else -> Res("Error: no trading tool called $name.")
            }
        } catch (e: TradeError) { Res("Error: ${e.message}") } catch (e: Exception) { Log.w(TAG, name, e); Res("Error: something went wrong (${e.message})") }
    }

    private fun order(ctx: Context, args: JSONObject, prepare: Boolean, ticket: String?): Res {
        if (ticket != null) {
            val (t, a) = takeTicket(ctx, ticket, "order")
            val s = assess(ctx, a, t.payload)
            return if (!s.v.ok) refused(ctx, a, t.payload, s.v) else Res(send(ctx, a, s.v, s, null))
        }
        val a = pick(ctx, args.optString("account"))
        val type = args.optString("type").ifEmpty { if (args.has("limit")) "limit" else "market" }
        val o = JSONObject().put("symbol", args.optString("symbol")).put("side", args.optString("side")).put("type", type)
        for ((from, to) in listOf("qty" to "qty", "notional" to "notional", "limit" to "limit", "stop" to "stop", "stop_loss" to "stopLoss", "take_profit" to "takeProfit")) if (args.has(from) && !args.isNull(from)) o.put(to, args.opt(from))
        val s = assess(ctx, a, o)
        if (prepare && !(s.v.ok && s.v.needsApproval)) return Res(out = "ok")
        if (!s.v.ok) return refused(ctx, a, o, s.v)
        if (s.v.needsApproval) {
            val n = s.v.order!!
            val frozen = JSONObject().put("symbol", n.optString("symbol")).put("side", n.optString("side")).put("type", n.optString("type")).put("qty", n.optString("qty"))
                .put("limit", n.opt("price")).put("stop", n.opt("stop")).put("stopLoss", n.opt("stopLoss")).put("takeProfit", n.opt("takeProfit"))
            return Res(need = s.v.summary, ticket = ticketFor("order", a, frozen), title = "Place This LIVE Order?", button = "Place Order")
        }
        return Res(send(ctx, a, s.v, s, null))
    }

    private fun close(ctx: Context, args: JSONObject, prepare: Boolean, ticket: String?): Res {
        if (ticket != null) {
            val (t, a) = takeTicket(ctx, ticket, "close")
            val s = assess(ctx, a, t.payload)
            return if (!s.v.ok) refused(ctx, a, t.payload, s.v) else Res(send(ctx, a, s.v, s, t.payload.optString("closeContract").ifEmpty { null }))
        }
        val a = pick(ctx, args.optString("account"))
        val co = broker(ctx, a).closeOrder(args.optString("symbol")) ?: return if (prepare) Res(out = "ok") else Res("${label(a)}: there's no open position in ${args.optString("symbol").uppercase()}.")
        val s = assess(ctx, a, co)
        if (prepare && !(s.v.ok && s.v.needsApproval)) return Res(out = "ok")
        if (!s.v.ok) return refused(ctx, a, co, s.v)
        if (s.v.needsApproval) return Res(need = s.v.summary, ticket = ticketFor("close", a, co), title = "Close This LIVE Position?", button = "Close It")
        return Res(send(ctx, a, s.v, s, co.optString("closeContract").ifEmpty { null }))
    }

    private fun plan(ctx: Context, args: JSONObject, prepare: Boolean, ticket: String?): Res {
        if (ticket != null) {
            val (t, a) = takeTicket(ctx, ticket, "plan")
            edit(ctx) { d -> (d.optJSONObject("plans") ?: JSONObject().also { d.put("plans", it) }).put(a.optString("id"), JSONObject(t.payload.toString()).put("confirmed", true).put("confirmedAt", System.currentTimeMillis())) }
            audit(ctx, a.optString("name"), a.optString("mode"), "plan", null, "confirmed", note = t.payload.optString("summary"))
            return Res("Plan confirmed for ${label(a)}. Orders inside it go ahead without asking until ${java.util.Date(t.payload.optLong("expiresAt"))} or ${t.payload.optInt("maxOrders")} orders; every account limit still applies. Losses are possible.")
        }
        val a = pick(ctx, args.optString("account")); val l = TradeRules.limitsFor(a)
        fun list(k: String) = args.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
        val summary = args.optString("summary").take(1200)
        if (summary.isBlank()) return Res("Error: describe the plan (strategy, entries and exits, risk per trade) in summary.")
        val hours = args.optDouble("hours", 24.0).let { if (it.isFinite()) it else 24.0 }.coerceIn(1.0, 24.0 * 7)
        val p = JSONObject().put("accountId", a.optString("id")).put("summary", summary).put("symbols", JSONArray(list("symbols").map { it.uppercase() }.take(10)))
            .put("sides", JSONArray(list("sides").filter { it == "buy" || it == "sell" })).put("maxOrders", args.optInt("max_orders", 1).coerceIn(1, l.maxOrdersPerDay))
            .put("maxOrderUsd", minOf(args.optDouble("max_order_usd", l.maxOrderUsd).takeIf { it.isFinite() && it > 0 } ?: l.maxOrderUsd, l.maxOrderUsd))
            .put("maxContracts", minOf(args.optDouble("max_contracts", l.maxContracts).takeIf { it.isFinite() && it > 0 } ?: l.maxContracts, l.maxContracts))
            .put("expiresAt", System.currentTimeMillis() + (hours * 3600_000).toLong()).put("used", 0).put("confirmed", false)
        val syms = list("symbols").map { it.uppercase() }; val sides = list("sides")
        val text = "Covers up to ${p.optInt("maxOrders")} LIVE order(s), each up to ${TradeRules.money(p.optDouble("maxOrderUsd"))} (spot) or ${p.optDouble("maxContracts").toInt()} contract(s) (futures).\n\n${label(a)}\n$summary\n\nUp to ${p.optInt("maxOrders")} order(s)${if (syms.isNotEmpty()) " in " + syms.joinToString(", ") else ""}${if (sides.isNotEmpty()) " (" + sides.joinToString("/") + " only)" else ""}, each at most ${TradeRules.money(p.optDouble("maxOrderUsd"))} (spot) or ${p.optDouble("maxContracts").toInt()} contract(s) (futures), for ${hours.toInt()} hours. Every account limit still applies. Losses are possible; nothing is guaranteed."
        if (a.optString("mode") != "live") {
            if (prepare) return Res(out = "ok")
            edit(ctx) { d -> (d.optJSONObject("plans") ?: JSONObject().also { d.put("plans", it) }).put(a.optString("id"), JSONObject(p.toString()).put("confirmed", true)) }
            return Res("PAPER plan saved (no OK needed for paper):\n$text")
        }
        return Res(need = text, ticket = ticketFor("plan", a, p), title = "Confirm This Trading Plan?", button = "Confirm Plan")
    }

    private fun cancel(ctx: Context, args: JSONObject): Res {
        val a = pick(ctx, args.optString("account")); val b = broker(ctx, a)
        val acct = b.account()
        val orders = acct.optJSONArray("orders")?.let { o -> (0 until o.length()).map { o.getJSONObject(it) } } ?: emptyList()
        val which = if (args.optString("order_id").isNotEmpty()) orders.filter { it.optString("id") == args.optString("order_id") } else if (args.optBoolean("all")) orders else emptyList()
        if (which.isEmpty()) return Res(if (args.optString("order_id").isNotEmpty()) "${label(a)}: no open order #${args.optString("order_id")}." else "Say which order (order_id), or all: true.")
        val positions = acct.optJSONArray("positions")
        val done = which.map { o ->
            // LIVE: the stop loss or target of an open futures position isn't cancelled here (that would leave it unprotected)
            if (a.optString("mode") == "live" && TradeRules.protectsPosition(o, positions)) "#${o.optString("id")} (not cancelled: it guards your open ${o.optString("symbol")} position; close the position with trade_close instead)"
            else try { b.cancel(o.optString("id"), o.optString("symbol")); "#${o.optString("id")}" } catch (e: Exception) { "#${o.optString("id")} (not cancelled: ${e.message})" }
        }
        audit(ctx, a.optString("name"), a.optString("mode"), "cancel", null, "sent", note = done.joinToString(" "))
        return Res("${label(a)}: cancelled ${done.joinToString(", ")}.")
    }

    /** The stop switch: no new orders anywhere, open orders cancelled. Only the user can turn trading back on. */
    fun stopAll(ctx: Context, reason: String): Res {
        // a new stop, with its own id: the other device stops too (sync kind "tradestop", TradeRules.stopFromOther)
        val stopId = "stop-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        edit(ctx) { it.put("killed", true).put("killedAt", System.currentTimeMillis()).put("killReason", reason.take(200)).put("plans", JSONObject()).put("standing", JSONObject())
            .put("stopId", stopId).put("stops", TradeRules.addStop(it.optJSONArray("stops"), stopId)) }
        try { Sync.syncSoon(ctx, 500) } catch (_: Exception) {}   // the other device hears it straight away
        val notes = mutableListOf<String>()
        for (a in accounts(ctx)) try {
            val b = broker(ctx, a); val acct = b.account(); val o = acct.optJSONArray("orders"); val positions = acct.optJSONArray("positions")
            for (i in 0 until (o?.length() ?: 0)) { val x = o!!.getJSONObject(i)
                // an open position's stop loss and target stay: stopping must never strip a position of its protection
                if (TradeRules.protectsPosition(x, positions)) { notes.add("${a.optString("name")} #${x.optString("id")} kept (it guards the open ${x.optString("symbol")} position)"); continue }
                try { b.cancel(x.optString("id"), x.optString("symbol")); notes.add("${a.optString("name")} #${x.optString("id")} cancelled") } catch (e: Exception) { notes.add("${a.optString("name")} #${x.optString("id")} NOT cancelled (${e.message})") } }
        } catch (e: Exception) { notes.add("${a.optString("name")}: couldn't reach it (${e.message})") }
        audit(ctx, "*", "", "stop_all", null, "stopped", note = (listOf(reason) + notes).filter { it.isNotBlank() }.joinToString("; "))
        TradingNotify.stopped(ctx)
        return Res("Trading is STOPPED on every account. ${if (notes.isNotEmpty()) notes.joinToString("; ") + ". " else ""}Open positions were left as they are, with their stop losses and targets (close them with trade_close if the user wants). Only the user can turn trading back on, in Me → Trading.")
    }

    private fun accountsList(ctx: Context): Res {
        val list = accounts(ctx)
        if (list.isEmpty()) return Res("No trading accounts are connected. The user can connect one in Me → Trading.")
        return Res((if (killed(ctx)) "TRADING IS STOPPED (stop switch on).\n" else "") + list.joinToString("\n") { a -> val l = TradeRules.limitsFor(a)
            "- ${a.optString("name")}: ${if (a.optString("mode") == "live") "LIVE (real money; each order asks unless a confirmed plan or the user's pre-approved limits cover it)${if (standing(ctx, a.optString("id")) != null) " [pre-approved limits on]" else ""}" else "PAPER (practice; no OK needed)"}, ${if (a.optString("broker") == "bybit") "Bybit spot" else "ProjectX futures"}. Limits: ${if (a.optString("broker") == "bybit") TradeRules.money(l.maxOrderUsd) + " per order" else "${l.maxContracts.toInt()} contract(s) per order"}, ${TradeRules.money(l.maxDailyLossUsd)} daily loss, ${l.maxOrdersPerDay} trades a day, ${l.maxOpenPositions} open positions${if (l.symbols.isNotEmpty()) ", only " + l.symbols.joinToString("/") else ""}${l.hours?.let { ", ${it.start}–${it.end}" } ?: ""}." })
    }

    private fun accountInfo(ctx: Context, args: JSONObject): Res {
        val a = pick(ctx, args.optString("account")); val acct = broker(ctx, a).account(); val eq = acct.optDouble("equity")
        val d = dayState(ctx, a, eq); val l = TradeRules.limitsFor(a); val start = d.optDouble("dayStartEquity", eq); val p = planFor(ctx, a)
        val pos = acct.optJSONArray("positions")!!; val ord = acct.optJSONArray("orders")!!
        return Res(listOf(
            "${label(a)} (${if (a.optString("broker") == "bybit") "Bybit spot" else "ProjectX futures"})${if (killed(ctx)) " — TRADING STOPPED (stop switch on)" else ""}",
            "Equity: ${TradeRules.money(eq)}. Today: ${if (eq >= start) "up " + TradeRules.money(eq - start) else "down " + TradeRules.money(start - eq)} since the day started; ${d.optInt("ordersToday")} of ${l.maxOrdersPerDay} trades used; daily loss limit ${TradeRules.money(l.maxDailyLossUsd)}.",
            "Positions: ${if (pos.length() == 0) "none" else (0 until pos.length()).joinToString("; ") { val x = pos.getJSONObject(it); "${x.optString("symbol")} ${x.optString("size")}${if (x.has("avgPrice")) " @ " + x.optString("avgPrice") else ""}${if (x.has("usd")) " (" + TradeRules.money(x.optDouble("usd")) + ")" else ""}" }}.",
            "Open orders: ${if (ord.length() == 0) "none" else (0 until ord.length()).joinToString("; ") { val x = ord.getJSONObject(it); "#${x.optString("id")} ${x.optString("side")} ${x.optString("qty")} ${x.optString("symbol")} ${x.optString("type")}" }}.",
            if (p != null && p.optBoolean("confirmed")) "Confirmed plan: ${p.optInt("used")} of ${p.optInt("maxOrders")} orders used, until ${java.util.Date(p.optLong("expiresAt"))}." else "No confirmed plan (LIVE orders each ask).",
        ).joinToString("\n"))
    }

    // ================================================================ chat, bots, the brief

    /** Chat: a LIVE order shows the confirm sheet on screen; only a tap sends it ("yes" typed in chat can't move money). */
    suspend fun runChat(ctx: Context, c: Gemini.Call): Tools.Result {
        var r = run(ctx, c.name, c.args)
        if (r.need != null) {
            val ask = asker ?: return Tools.Result("Not sent: this LIVE action needs the user's tap on screen, and Atlas isn't open on screen right now. Tell them what you'd do (${r.need}); ask them to open Atlas and ask again.")
            val yes = withTimeoutOrNull(180_000) { ask(r.title, r.need!!, r.button) } ?: false
            if (!yes) return Tools.Result("The user didn't approve it, so nothing was sent: ${r.need}", "You said no: ${r.need!!.take(60)}")
            r = run(ctx, c.name, JSONObject(), ticket = r.ticket)
        }
        return Tools.Result(r.out ?: "", stepLabel(c))
    }

    private val botTickets = ConcurrentHashMap<String, String>()
    private fun botKey(jobId: String, c: Gemini.Call) = "$jobId|${c.name}|${c.args}"
    /** Before a bot's trade runs: the summary the user must approve on the job page (money: "always allow" never covers it). */
    suspend fun needForBot(ctx: Context, jobId: String, c: Gemini.Call): String? {
        val r = run(ctx, c.name, c.args, prepare = true)
        if (r.need == null || r.ticket == null) return null
        botTickets[botKey(jobId, c)] = r.ticket; return r.need
    }
    suspend fun runForBot(ctx: Context, jobId: String, c: Gemini.Call, approved: Boolean): String {
        val t = if (approved) botTickets.remove(botKey(jobId, c)) else null
        val r = run(ctx, c.name, c.args, ticket = t)
        return if (r.need != null) "Not sent: this LIVE order needs the user's OK and the earlier approval expired. Ask again (it will wait for their tap)." else r.out ?: ""
    }

    fun stepLabel(c: Gemini.Call): String = when (c.name) {
        "trade_order" -> "Order: ${c.args.optString("side")} ${c.args.optString("symbol")}".trim()
        "trade_close" -> "Closed ${c.args.optString("symbol")}"
        "trade_cancel" -> "Cancelled an order"
        "trade_plan" -> "Proposed a trading plan"
        "trade_stop_all" -> "Stopped all trading"
        "trade_quote" -> "Checked a price"
        else -> "Checked trading"
    }

    fun brief(ctx: Context): String? {
        val list = accounts(ctx); if (list.isEmpty()) return null
        val days = state(ctx).optJSONObject("days"); val plans = state(ctx).optJSONObject("plans")
        return "Trading${if (killed(ctx)) " (STOPPED: the stop switch is on)" else ""}: " + list.joinToString("; ") { a -> val d = days?.optJSONObject(a.optString("id"))
            "${a.optString("name")} ${if (a.optString("mode") == "live") "LIVE" else "PAPER"}${if (d != null && d.optString("day") == localNow().first) ", ${d.optInt("ordersToday")}/${TradeRules.limitsFor(a).maxOrdersPerDay} trades today" else ""}${if (plans?.optJSONObject(a.optString("id"))?.optBoolean("confirmed") == true) ", plan active" else ""}" } + ". \"Stop all trading\" → trade_stop_all."
    }

    val cap: Capabilities.Cap by lazy {
        Capabilities.Cap("trading", { _ -> TradeTools.declarations }, { ctx, c -> if (c.name in names) runChat(ctx, c) else null }, { ctx -> brief(ctx) }, forBots = true)
    }
}
