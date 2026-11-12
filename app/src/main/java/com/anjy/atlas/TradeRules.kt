package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Atlas trading: the risk engine on the phone. Pure (no Android, no network) and held to the PC's answers
 * (desktop/traderules.js) by tools/trade-fixtures.json — see TradeRulesTest.
 *
 * Every order passes [check] in [Trading] right before it would be sent; an order it refuses never reaches the broker.
 * The model can't change the limits, can't lift the stop switch and can't approve a LIVE order: only the user can.
 */
object TradeRules {
    data class Limits(
        val maxOrderUsd: Double, val maxContracts: Double, val maxDailyLossUsd: Double, val maxOpenPositions: Int,
        val maxOrdersPerDay: Int, val symbols: List<String>, val hours: Hours?, val allowDerivatives: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().put("maxOrderUsd", maxOrderUsd).put("maxContracts", maxContracts)
            .put("maxDailyLossUsd", maxDailyLossUsd).put("maxOpenPositions", maxOpenPositions).put("maxOrdersPerDay", maxOrdersPerDay)
            .put("symbols", JSONArray(symbols)).put("hours", hours?.toJson() ?: JSONObject.NULL).put("allowDerivatives", allowDerivatives)
    }
    data class Hours(val start: String, val end: String, val days: List<Int>) {
        fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("days", JSONArray(days))
        companion object {
            fun from(o: JSONObject?): Hours? = o?.let { h ->
                Hours(h.optString("start"), h.optString("end"), h.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList())
            }
        }
    }

    val DEFAULT = Limits(50.0, 1.0, 25.0, 2, 2, emptyList(), null, false)
    val PAPER = DEFAULT.copy(maxOrderUsd = 1000.0, maxContracts = 2.0, maxDailyLossUsd = 1000.0, maxOpenPositions = 5, maxOrdersPerDay = 20)

    fun limitsFor(account: JSONObject): Limits {
        val base = if (account.optString("mode") == "live") DEFAULT else PAPER
        val l = account.optJSONObject("limits") ?: return base
        fun d(k: String, v: Double) = if (l.has(k) && !l.isNull(k)) l.optDouble(k, v) else v
        fun i(k: String, v: Int) = if (l.has(k) && !l.isNull(k)) l.optInt(k, v) else v
        return Limits(
            d("maxOrderUsd", base.maxOrderUsd), d("maxContracts", base.maxContracts), d("maxDailyLossUsd", base.maxDailyLossUsd),
            i("maxOpenPositions", base.maxOpenPositions), i("maxOrdersPerDay", base.maxOrdersPerDay),
            l.optJSONArray("symbols")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: base.symbols,
            if (l.has("hours")) Hours.from(l.optJSONObject("hours")) else base.hours,
            if (l.has("allowDerivatives")) l.optBoolean("allowDerivatives") else base.allowDerivatives,
        )
    }

    // ---------------------------------------------------------------- exact decimals (prices and sizes as strings)

    fun decimalsOf(step: String): Int = step.indexOf('.').let { if (it < 0) 0 else step.length - it - 1 }
    private fun stepStr(step: Any?): String = when (step) { is String -> step; is Number -> BigDecimal(step.toString()).stripTrailingZeros().toPlainString(); else -> step.toString() }

    /** value rounded DOWN to a multiple of step (sizes: never more than asked), as a plain decimal string. Same as the PC. */
    fun floorTo(value: Double, step: Any?): String? {
        val s = stepStr(step); val st = s.toDoubleOrNull() ?: return null
        if (st <= 0 || !value.isFinite()) return null
        val n = floor(value / st + 1e-9).toLong()
        return BigDecimal(s).multiply(BigDecimal(n)).setScale(decimalsOf(s), RoundingMode.HALF_UP).toPlainString()
    }
    /** value rounded to the NEAREST multiple of step (prices), as a plain decimal string. Same as the PC (Math.round). */
    fun roundTo(value: Double, step: Any?): String? {
        val s = stepStr(step); val st = s.toDoubleOrNull() ?: return null
        if (st <= 0 || !value.isFinite()) return null
        val n = Math.floor(value / st + 0.5).toLong()   // Math.round in JS: halves go up
        return BigDecimal(s).multiply(BigDecimal(n)).setScale(decimalsOf(s), RoundingMode.HALF_UP).toPlainString()
    }
    fun money(x: Double): String = "$" + String.format(Locale.US, "%,.2f", (x * 100).roundToLong() / 100.0)

    // ---------------------------------------------------------------- time

    private fun minutesOf(hhmm: String?): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm?.trim().orEmpty())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    /** Is local time (minute of day, ISO weekday 1..7) inside the trading hours? Windows that cross midnight work too. */
    fun inHours(h: Hours?, minuteOfDay: Int, isoDay: Int): Boolean {
        if (h == null) return true
        if (h.days.isNotEmpty() && isoDay !in h.days) return false
        val s = minutesOf(h.start) ?: return true; val e = minutesOf(h.end) ?: return true
        return if (s <= e) minuteOfDay in s until e else minuteOfDay >= s || minuteOfDay < e
    }
    fun lossToday(dayStartEquity: Double?, equity: Double?): Double = if (dayStartEquity == null || equity == null) 0.0 else max(0.0, dayStartEquity - equity)

    // ---------------------------------------------------------------- the check

    class Reason(val code: String, val text: String)
    class Verdict(val ok: Boolean, val reasons: List<Reason> = emptyList(), val order: JSONObject? = null, val needsApproval: Boolean = false,
                  val coveredByPlan: Boolean = false, val estUsd: Double = 0.0, val worstCaseUsd: Double? = null, val summary: String = "")

    private fun num(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k) || o.optString(k).isEmpty()) null else o.optDouble(k).takeIf { it.isFinite() }

    /**
     * order:   { symbol, side, qty?, notional?, type, limit?, stop?, stopLoss?, takeProfit?, closing? }
     * inst:    { tickSize, qtyStep, minQty, minNotional, pointValue, integerQty, futures }
     * market:  { bid, ask, last }
     * account: { id, name, broker, mode: paper|live, limits }
     * state:   { killed, dayStartEquity, equity, ordersToday, openSymbols, minuteOfDay, isoDay, now }
     * plan:    the user's confirmed plan for this account, or null
     */
    fun check(order: JSONObject, inst: JSONObject, market: JSONObject, account: JSONObject, state: JSONObject, plan: JSONObject?): Verdict {
        val l = limitsFor(account); val live = account.optString("mode") == "live"
        val reasons = mutableListOf<Reason>(); fun no(c: String, t: String) { reasons.add(Reason(c, t)) }
        val side = order.optString("side").lowercase()
        val type = order.optString("type").ifEmpty { "market" }.lowercase()
        val sym = order.optString("symbol").uppercase()
        val closing = order.optBoolean("closing", false)
        val futures = inst.optBoolean("futures", false)
        val pv = num(inst, "pointValue") ?: 1.0

        if (side != "buy" && side != "sell") no("side", "Say buy or sell.")
        if (type !in listOf("market", "limit", "stop")) no("type", "The order type must be market, limit or stop.")
        if (state.optBoolean("killed") && !closing) no("killed", "Trading is stopped (the stop switch is on). Only the user can turn it back on, in Me → Trading.")
        if (live && futures && !l.allowDerivatives && !closing) no("derivatives_off", "This is a LIVE futures account and futures trading is off for it. The user can turn it on in Me → Trading.")
        if (!closing && l.symbols.isNotEmpty() && l.symbols.map { it.uppercase() }.none { sym == it || sym.startsWith(it) }) no("symbol", "$sym isn't on this account's allowed list (${l.symbols.joinToString(", ")}).")
        if (!closing && !inHours(l.hours, state.optInt("minuteOfDay"), state.optInt("isoDay"))) no("hours", "It's outside the trading hours set for this account (${l.hours?.start}–${l.hours?.end}).")

        val ref = when (type) {
            "limit" -> num(order, "limit") ?: Double.NaN
            "stop" -> num(order, "stop") ?: Double.NaN
            else -> (if (side == "buy") num(market, "ask") else num(market, "bid"))?.takeIf { it > 0 } ?: num(market, "last") ?: Double.NaN
        }
        if (type == "limit" && !((num(order, "limit") ?: 0.0) > 0)) no("price_needed", "A limit order needs a limit price.")
        if (type == "stop" && !((num(order, "stop") ?: 0.0) > 0)) no("price_needed", "A stop order needs a stop price.")
        if (reasons.none { it.code == "price_needed" } && !(ref > 0)) no("no_price", "There's no current price for this symbol, so the order can't be sized or checked.")
        if (reasons.isNotEmpty()) return Verdict(false, reasons)

        val qty = num(order, "qty") ?: num(order, "notional")?.let { if (futures) Double.NaN else it / ref } ?: Double.NaN
        if (!(qty > 0)) { no("size", if (futures) "Say how many contracts." else "Say how much: a quantity, or an amount in dollars."); return Verdict(false, reasons) }
        val step: Any = if (futures || inst.optBoolean("integerQty")) "1" else inst.opt("qtyStep") ?: "1"
        val q = floorTo(qty, step) ?: "0"
        val qn = q.toDouble()
        val estUsd = qn * ref * pv
        val minQty = num(inst, "minQty"); val minNotional = num(inst, "minNotional")
        if (!(qn > 0) || (minQty != null && qn < minQty - 1e-12)) no("too_small", "That's below the smallest order for $sym (${inst.optString("minQty").ifEmpty { step.toString() }}).")
        else if (!futures && minNotional != null && estUsd < minNotional - 1e-9) no("too_small", "That's below the smallest order value for $sym (${money(minNotional)}).")
        val tick = inst.opt("tickSize")
        val price = if (type == "limit") roundTo(num(order, "limit")!!, tick) else null
        val stopPx = if (type == "stop") roundTo(num(order, "stop")!!, tick) else null
        val sl = num(order, "stopLoss")?.let { roundTo(it, tick) }
        val tp = num(order, "takeProfit")?.let { roundTo(it, tick) }
        val refShown = BigDecimal(ref.toString()).stripTrailingZeros().toPlainString()
        if (sl != null && ((side == "buy" && sl.toDouble() >= ref) || (side == "sell" && sl.toDouble() <= ref))) no("stop_side", "The stop loss ($sl) is on the wrong side of the price ($refShown).")
        if (tp != null && ((side == "buy" && tp.toDouble() <= ref) || (side == "sell" && tp.toDouble() >= ref))) no("tp_side", "The take profit ($tp) is on the wrong side of the price ($refShown).")

        // worst case. With a stop: the distance to it. Spot bought with no stop: to zero (shown; the size limit bounds it). Futures, no stop: no cap.
        var worst: Double? = null
        if (sl != null) worst = abs(ref - sl.toDouble()) * qn * pv
        else if (!futures && side == "buy") worst = estUsd
        val stopRisk = if (sl != null) worst else null
        if (!closing) {
            if (futures && qn > l.maxContracts) no("contracts", "That's ${fmt(qn)} contracts; this account allows at most ${fmt(l.maxContracts)} per order.")
            if (!futures && estUsd > l.maxOrderUsd + 1e-9) no("order_size", "That's about ${money(estUsd)}; this account allows at most ${money(l.maxOrderUsd)} per order.")
            if (live && futures && sl == null) no("needs_stop", "A LIVE futures order needs a stop loss, so the most it can lose is known.")
            val open = state.optJSONArray("openSymbols")?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() } } ?: emptyList()
            if (sym !in open && open.size >= l.maxOpenPositions) no("positions", "There are already ${open.size} open positions; this account allows ${l.maxOpenPositions}.")
            if (state.optInt("ordersToday") >= l.maxOrdersPerDay) no("orders_today", "That would be more than ${l.maxOrdersPerDay} trades today, the daily limit for this account.")
            val lost = lossToday(num(state, "dayStartEquity"), num(state, "equity"))
            if (lost >= l.maxDailyLossUsd - 1e-9) no("daily_loss", "Today's loss is ${money(lost)}, at the daily limit of ${money(l.maxDailyLossUsd)}. No new trades until tomorrow.")
            else if (stopRisk != null && lost + stopRisk > l.maxDailyLossUsd + 1e-9) no("risk_exceeds", "If this goes wrong it could lose ${money(stopRisk)}; with ${money(lost)} lost today that passes the daily limit of ${money(l.maxDailyLossUsd)}. Use a smaller size or a tighter stop.")
        }
        if (reasons.isNotEmpty()) return Verdict(false, reasons)

        val norm = JSONObject().put("symbol", sym).put("side", side).put("type", type).put("qty", q)
            .put("price", price ?: JSONObject.NULL).put("stop", stopPx ?: JSONObject.NULL).put("stopLoss", sl ?: JSONObject.NULL)
            .put("takeProfit", tp ?: JSONObject.NULL).put("closing", closing)
        val covered = live && planCovers(plan, account, norm, estUsd, futures, state)
        return Verdict(true, emptyList(), norm, live && !covered, covered, estUsd, worst, summary(account, norm, estUsd, worst, futures))
    }

    private fun fmt(x: Double): String = BigDecimal(x.toString()).stripTrailingZeros().toPlainString()

    /** The user confirmed a plan: LIVE orders inside it don't ask again (they still pass every account limit). */
    fun planCovers(plan: JSONObject?, account: JSONObject, o: JSONObject, estUsd: Double, futures: Boolean, state: JSONObject): Boolean {
        if (plan == null || plan.optString("accountId") != account.optString("id") || !plan.optBoolean("confirmed")) return false
        val now = num(state, "now"); val exp = num(plan, "expiresAt")
        if (now != null && exp != null && exp > 0 && now > exp) return false
        if (plan.optInt("used") >= plan.optInt("maxOrders")) return false
        val sym = o.optString("symbol")
        plan.optJSONArray("symbols")?.let { a -> val list = (0 until a.length()).map { a.getString(it).uppercase() }; if (list.isNotEmpty() && list.none { sym == it || sym.startsWith(it) }) return false }
        plan.optJSONArray("sides")?.let { a -> val list = (0 until a.length()).map { a.getString(it) }; if (list.isNotEmpty() && o.optString("side") !in list) return false }
        return if (futures) o.optString("qty").toDouble() <= plan.optDouble("maxContracts", 0.0) else estUsd <= plan.optDouble("maxOrderUsd", 0.0) + 1e-9
    }

    fun summary(account: JSONObject, o: JSONObject, estUsd: Double, worst: Double?, futures: Boolean): String {
        val mode = if (account.optString("mode") == "live") "LIVE" else "PAPER"
        val qty = o.optString("qty")
        val what = "${o.optString("side").uppercase()} $qty${if (futures) (if (qty.toDouble() == 1.0) " contract" else " contracts") else ""} ${o.optString("symbol")}"
        val at = when (o.optString("type")) { "limit" -> "limit ${o.optString("price")}"; "stop" -> "stop ${o.optString("stop")}"; else -> "at market" }
        val parts = mutableListOf("$mode · ${account.optString("name")}: $what $at (about ${if (futures) money(estUsd) + " of contract value" else money(estUsd)}).")
        if (!o.isNull("stopLoss")) parts.add("Stop loss ${o.optString("stopLoss")}.")
        if (!o.isNull("takeProfit")) parts.add("Take profit ${o.optString("takeProfit")}.")
        parts.add(when {
            o.optBoolean("closing") -> "This closes or reduces a position."
            worst == null -> "Worst case: no stop, so the loss isn't capped."
            !o.isNull("stopLoss") -> "Worst case: about ${money(worst)} lost if the stop is hit (more if the price jumps past it)."
            else -> "Worst case: the whole ${money(worst)} if the price went to zero; there's no stop."
        })
        return parts.joinToString(" ")
    }

    // ---------------------------------------------------------------- Bybit request signing (bybit-exchange.github.io/docs/v5/guide)

    /** The string Bybit signs: timestamp + api key + recv window + (query string for GET | JSON body for POST). */
    fun bybitPayload(ts: String, apiKey: String, recvWindow: String, queryOrBody: String): String = ts + apiKey + recvWindow + queryOrBody
    fun hmacHex(key: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
