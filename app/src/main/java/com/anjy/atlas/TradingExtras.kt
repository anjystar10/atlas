package com.anjy.atlas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** The trading tools as Gemini sees them (same as desktop/renderer/trade.js TOOLS). */
object TradeTools {
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun n(d: String) = JSONObject().put("type", "number").put("description", d)
    private fun e(d: String, vararg v: String) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v.toList()))
    private fun arr(d: String, items: JSONObject) = JSONObject().put("type", "array").put("description", d).put("items", items)
    private val account get() = s("Which trading account (its name). Leave out when there's only one.")
    private fun fn(name: String, desc: String, props: JSONObject? = null, required: List<String> = emptyList()) = JSONObject().put("name", name).put("description", desc).apply {
        if (props != null && props.length() > 0) put("parameters", JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
    }

    val declarations: JSONArray by lazy {
        JSONArray().apply {
            put(fn("trade_accounts", "The user's connected trading accounts: PAPER (practice) or LIVE (real money), the broker, and each account's limits. Check this before trading."))
            put(fn("trade_account", "One account now: equity, today's gain or loss, trades used today, positions, open orders, and any confirmed plan.", JSONObject().put("account", account)))
            put(fn("trade_quote", "The current price of a symbol. Bybit: spot pairs like BTCUSDT. ProjectX (prop firm futures): NQ, MNQ, ES, MES, GC, MGC, or a full contract like NQZ6.",
                JSONObject().put("symbol", s("The symbol.")).put("account", account), listOf("symbol")))
            put(fn("trade_history", "Recent fills (trades that happened) on an account.", JSONObject().put("account", account).put("days", n("How many days back (1–30, default 7)."))))
            put(fn("trade_order", "Place an order. Atlas's risk engine checks it first against the account's limits (size, trades a day, daily loss, symbols, hours, the stop switch) and refuses what breaks them. PAPER orders go straight through. A LIVE order shows the user exactly what it is and its worst case and waits for their tap, unless a plan they confirmed (trade_plan) covers it. Spot: give qty (in the coin) or notional (dollars). Futures: qty in contracts, and LIVE futures need stop_loss. Never promise profit.",
                JSONObject().put("account", account).put("symbol", s("The symbol (BTCUSDT; NQ, ES, GC…).")).put("side", e("Buy or sell.", "buy", "sell"))
                    .put("qty", n("Quantity (coins, or contracts).")).put("notional", n("Spot only: how many dollars to spend instead of a quantity."))
                    .put("type", e("Default market.", "market", "limit", "stop")).put("limit", n("Limit price (limit orders).")).put("stop", n("Stop price (stop entry orders, futures)."))
                    .put("stop_loss", n("Stop-loss price: where to get out if it goes wrong.")).put("take_profit", n("Take-profit price.")), listOf("symbol", "side")))
            put(fn("trade_close", "Close (sell out of) a whole position. LIVE: waits for the user's tap.", JSONObject().put("account", account).put("symbol", s("The symbol to close.")), listOf("symbol")))
            put(fn("trade_cancel", "Cancel an open order (order_id), or all open orders (all: true). Cancelling never needs an OK.",
                JSONObject().put("account", account).put("order_id", s("The order id from trade_account.")).put("all", JSONObject().put("type", "boolean").put("description", "Cancel every open order."))))
            put(fn("trade_plan", "When the user says \"trade this money for me\": propose a plan first (strategy, entries and exits, risk per trade, and that losses are possible). The user confirms it once (LIVE) and then orders inside it go ahead without asking each time, within the account limits; report each fill. The plan can't raise any limit.",
                JSONObject().put("account", account).put("summary", s("The plan in plain words: what, why, entries, exits, risk per trade, and that losses are possible."))
                    .put("symbols", arr("The only symbols it may trade.", JSONObject().put("type", "string"))).put("sides", arr("Buy, sell or both.", JSONObject().put("type", "string").put("enum", JSONArray(listOf("buy", "sell")))))
                    .put("max_orders", n("How many orders at most.")).put("max_order_usd", n("Spot: the most per order, in dollars.")).put("max_contracts", n("Futures: the most contracts per order."))
                    .put("hours", n("How long the plan lasts (hours, default 24, at most a week).")), listOf("summary")))
            put(fn("trade_stop_all", "The stop switch: stop all trading on every account at once (open orders are cancelled; no new orders). Use it whenever the user says stop trading, or something looks wrong. Only the user can turn trading back on (Me → Trading).",
                JSONObject().put("reason", s("Why, in a few words."))))
        }
    }
}

/** Trading notifications, each with a Stop All Trading button. */
object TradingNotify {
    private const val CH = "atlas_trading"
    private fun post(ctx: Context, id: Int, title: String, body: String, stopButton: Boolean) {
        val app = ctx.applicationContext
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Trading", NotificationManager.IMPORTANCE_HIGH).apply { description = "Orders Atlas placed, and the stop switch" })
        val open = PendingIntent.getActivity(app, 7801, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = android.app.Notification.Builder(app, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
        if (stopButton) b.addAction(android.app.Notification.Action.Builder(null, "Stop All Trading",
            PendingIntent.getBroadcast(app, 7802, Intent(app, TradingReceiver::class.java).setAction(TradingReceiver.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        nm.notify(id, b.build())
    }
    fun sent(ctx: Context, live: Boolean, summary: String) = post(ctx, 7810 + (System.currentTimeMillis() % 50).toInt(), if (live) "LIVE order sent" else "PAPER order sent", summary, true)
    fun stopped(ctx: Context) = post(ctx, 7800, "Trading stopped", "No new orders on any account. Turn trading back on in Me → Trading.", false)
}

class TradingReceiver : BroadcastReceiver() {
    companion object { const val STOP = "com.anjy.atlas.TRADE_STOP" }
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != STOP) return
        val pending = goAsync(); val app = ctx.applicationContext
        Thread { try { Trading.stopAll(app, "from the notification") } finally { pending.finish() } }.start()
    }
}
