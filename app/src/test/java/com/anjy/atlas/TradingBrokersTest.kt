package com.anjy.atlas

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder

/** The phone's broker clients against a local stand-in for Bybit V5 and the ProjectX Gateway (same shapes as desktop/test/fixtures/brokers.js). */
class TradingBrokersTest {
    private lateinit var srv: ServerSocket
    /** One request as the stand-in sees it, and how it answers. */
    private class Ex(val requestMethod: String, val requestURI: URI, val headers: Map<String, String>, val body: String) {
        var code = 200; var out = ""
    }
    private lateinit var base: String
    private val orders = mutableListOf<JSONObject>()
    private var bracketsMode = true
    private var pxPositions = "[]"

    @Before fun start() {
        srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val sock = try { srv.accept() } catch (_: Exception) { break }
                sock.use { so ->
                    val inp = so.getInputStream().buffered()
                    fun line(): String { val b = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
                    val first = line().split(" "); if (first.size < 2) return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(":").trim().lowercase()] = l.substringAfter(":").trim() }
                    val n = headers["content-length"]?.toIntOrNull() ?: 0
                    val raw = ByteArray(n); var got = 0; while (got < n) { val r = inp.read(raw, got, n - got); if (r < 0) break; got += r }
                    val ex = Ex(first[0], URI(first[1]), headers, String(raw, Charsets.UTF_8))
                    handle(ex)
                    val b = ex.out.toByteArray()
                    so.getOutputStream().apply { write("HTTP/1.1 ${ex.code} X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
                }
            }
        }.apply { isDaemon = true }.start()
        base = "http://127.0.0.1:${srv.localPort}"
    }
    @After fun stop() { srv.close() }

    private fun handle(ex: Ex) {
        run {
            val body = ex.body
            val path = ex.requestURI.path; val query = ex.requestURI.rawQuery ?: ""
            fun send(code: Int, s: String) { ex.code = code; ex.out = s }
            fun ok(result: String) = send(200, """{"retCode":0,"retMsg":"OK","result":$result}""")
            if (path.startsWith("/v5/")) {
                if (!path.startsWith("/v5/market/")) {
                    val h = ex.headers
                    val payload = h["x-bapi-timestamp"] + h["x-bapi-api-key"] + h["x-bapi-recv-window"] + (if (ex.requestMethod == "GET") query else body)
                    if (h["x-bapi-api-key"] != "k1" || h["x-bapi-sign"] != TradeRules.hmacHex("s1", payload)) return@run send(200, """{"retCode":10004,"retMsg":"error sign!","result":{}}""")
                }
                val q = query.split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                when (path) {
                    "/v5/market/instruments-info" -> if (q["symbol"] != "BTCUSDT") ok("""{"list":[]}""") else
                        ok("""{"list":[{"symbol":"BTCUSDT","lotSizeFilter":{"basePrecision":"0.000001","minOrderQty":"0.000048","minOrderAmt":"1"},"priceFilter":{"tickSize":"0.01"}}]}""")
                    "/v5/market/tickers" -> ok("""{"list":[{"symbol":"BTCUSDT","bid1Price":"66999.9","ask1Price":"67000.1","lastPrice":"67000"}]}""")
                    "/v5/account/wallet-balance" -> ok("""{"list":[{"totalEquity":"634","coin":[{"coin":"BTC","walletBalance":"0.002","locked":"0","usdValue":"134"},{"coin":"USDT","walletBalance":"500","locked":"0","usdValue":"500"}]}]}""")
                    "/v5/order/realtime" -> ok("""{"list":[]}""")
                    "/v5/order/create" -> { orders.add(JSONObject(body)); ok("""{"orderId":"by1"}""") }
                    else -> send(404, "{}")
                }
                return@run
            }
            val b = if (body.isEmpty()) JSONObject() else JSONObject(body)
            if (path == "/api/Auth/loginKey") return@run send(200, if (b.optString("apiKey") == "pk1") """{"token":"tok","success":true,"errorCode":0}""" else """{"token":null,"success":false,"errorCode":3,"errorMessage":"bad key"}""")
            if (ex.headers["authorization"] != "Bearer tok") return@run send(401, "{}")
            val nq = """{"id":"CON.F.US.ENQ.Z26","name":"NQZ6","tickSize":0.25,"tickValue":5,"activeContract":true}"""
            when (path) {
                "/api/Account/search" -> send(200, """{"accounts":[{"id":465,"name":"PRAC-V2-1","balance":50000,"canTrade":true}],"success":true,"errorCode":0}""")
                "/api/Contract/search" -> send(200, """{"contracts":[$nq],"success":true,"errorCode":0}""")
                "/api/Position/searchOpen" -> send(200, """{"positions":$pxPositions,"success":true,"errorCode":0}""")
                "/api/Order/searchOpen" -> send(200, """{"orders":[],"success":true,"errorCode":0}""")
                "/api/Order/place" -> {
                    if ((b.has("stopLossBracket") || b.has("takeProfitBracket")) && !bracketsMode) return@run send(200, """{"orderId":null,"success":false,"errorCode":2,"errorMessage":"Brackets need Auto OCO"}""")
                    orders.add(b); send(200, """{"orderId":${9000 + orders.size},"success":true,"errorCode":0}""")
                }
                else -> send(404, """{"success":false,"errorCode":404}""")
            }
        }
    }

    @Test fun bybitSignsReadsAndPlacesPlainSpot() {
        val b = Trading.Bybit("k1", "s1", base)
        val inst = b.inst("btcusdt")
        assertEquals("0.000001", inst.optString("qtyStep")); assertEquals("0.01", inst.optString("tickSize")); assertEquals(false, inst.optBoolean("futures"))
        assertEquals(67000.1, b.quote("BTCUSDT").optDouble("ask"), 1e-9)
        val acct = b.account()
        assertEquals(634.0, acct.optDouble("equity"), 1e-9); assertEquals(500.0, acct.optDouble("cash"), 1e-9)
        assertEquals("BTCUSDT", acct.getJSONArray("positions").getJSONObject(0).optString("symbol"))
        val (id, _) = b.place(JSONObject().put("symbol", "BTCUSDT").put("side", "buy").put("type", "market").put("qty", "0.000746").put("stopLoss", JSONObject.NULL).put("takeProfit", JSONObject.NULL), "atlas-t1")
        assertEquals("by1", id)
        val o = orders.single()
        assertEquals("spot", o.optString("category")); assertEquals("Buy", o.optString("side")); assertEquals("Market", o.optString("orderType"))
        assertEquals("0.000746", o.optString("qty")); assertEquals("baseCoin", o.optString("marketUnit")); assertEquals(0, o.optInt("isLeverage", -1))
        try { Trading.Bybit("k1", "wrong", base).account(); fail("a bad secret must fail") } catch (e: Trading.TradeError) { assertTrue(e.message!!.contains("error sign")) }
        try { b.inst("NOPEUSDT"); fail() } catch (e: Trading.TradeError) { assertTrue(e.message!!.contains("no spot market")) }
    }

    @Test fun projectXBracketsInTicksThenStopFallback() {
        val p = Trading.ProjectX("jane", "pk1", base, 465)
        val inst = p.inst("NQ")
        assertEquals("NQZ6", inst.optString("symbol")); assertEquals(20.0, inst.optDouble("pointValue"), 1e-9)
        val o = JSONObject().put("symbol", "NQZ6").put("side", "buy").put("type", "market").put("qty", "1").put("refPrice", 21000.0).put("stopLoss", "20990").put("takeProfit", "21020")
        p.place(o, "atlas-t2")
        val first = orders.last()
        assertEquals(2, first.optInt("type")); assertEquals(0, first.optInt("side"))
        assertEquals(40, first.getJSONObject("stopLossBracket").optInt("ticks")); assertEquals(80, first.getJSONObject("takeProfitBracket").optInt("ticks"))
        bracketsMode = false; orders.clear()
        val (_, notes) = p.place(JSONObject(o.toString()).put("side", "sell").put("stopLoss", "21010").put("takeProfit", JSONObject.NULL), "atlas-t3")
        assertEquals(2, orders.size)
        assertEquals(4, orders[1].optInt("type")); assertEquals(0, orders[1].optInt("side")); assertEquals(21010.0, orders[1].optDouble("stopPrice"), 1e-9)
        assertTrue(notes.any { it.contains("stop placed") })
        pxPositions = """[{"contractId":"CON.F.US.ENQ.Z26","size":1,"type":2,"averagePrice":21000}]"""
        assertEquals("buy", p.closeOrder("NQ")!!.optString("side"))   // closing a short buys
        try { Trading.ProjectX("jane", "bad", base, 465).inst("NQ"); fail() } catch (e: Trading.TradeError) { assertTrue(e.message!!.contains("turned down")) }
    }

    // ---- scan 3: with brackets refused, a limit or stop entry isn't placed with its stop resting on its own
    @Test fun projectXRefusesAnUnprotectableEntryAndBybitFlagsLimitOnlyTpSl() {
        val p = Trading.ProjectX("jane", "pk1", base, 465)
        bracketsMode = false; orders.clear()
        val o = JSONObject().put("symbol", "NQZ6").put("side", "buy").put("type", "limit").put("qty", "1").put("price", "20990").put("refPrice", 20990.0).put("stopLoss", "20980").put("takeProfit", JSONObject.NULL)
        try { p.place(o, "atlas-t4"); fail("must refuse") } catch (e: Trading.TradeError) { assertTrue(e.message!!.contains("Nothing was placed")); assertTrue(!e.unsure) }
        assertTrue(orders.isEmpty())
        assertEquals(true, Trading.Bybit("k1", "s1", base).inst("BTCUSDT").optBoolean("tpslLimitOnly"))
        // no answer at all is "unsure" (the order may have gone out), never a plain refusal
        try { Trading.Bybit("k1", "s1", "http://127.0.0.1:1").place(JSONObject().put("symbol", "BTCUSDT").put("side", "buy").put("type", "market").put("qty", "0.001").put("stopLoss", JSONObject.NULL).put("takeProfit", JSONObject.NULL), "atlas-t5"); fail() }
        catch (e: Trading.TradeError) { assertTrue(e.unsure) }
    }
}
