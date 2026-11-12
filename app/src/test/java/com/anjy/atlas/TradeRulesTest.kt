package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The phone's risk engine gives the same answers as the PC's (tools/trade-fixtures.json was made by desktop/traderules.js). */
class TradeRulesTest {
    private fun fixtures(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/trade-fixtures.json").exists()) dir = dir.parentFile
        return JSONObject(File(dir!!, "tools/trade-fixtures.json").readText())
    }

    @Test fun everyCaseMatchesThePc() {
        val cases = fixtures().getJSONArray("cases")
        assertTrue(cases.length() >= 25)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val name = c.getString("name"); val e = c.getJSONObject("expect")
            val plan = if (c.isNull("plan")) null else c.getJSONObject("plan")
            val v = TradeRules.check(c.getJSONObject("order"), c.getJSONObject("inst"), c.getJSONObject("market"), c.getJSONObject("account"), c.getJSONObject("state"), plan)
            assertEquals(name, e.getBoolean("ok"), v.ok)
            if (!v.ok) {
                val codes = e.getJSONArray("codes").let { a -> (0 until a.length()).map { a.getString(it) } }
                assertEquals(name, codes, v.reasons.map { it.code })
                continue
            }
            val eo = e.getJSONObject("order"); val o = v.order!!
            for (k in listOf("symbol", "side", "type", "qty", "price", "stop", "stopLoss", "takeProfit")) assertEquals("$name: $k", if (eo.isNull(k)) "null" else eo.getString(k), if (o.isNull(k)) "null" else o.getString(k))
            assertEquals("$name: closing", eo.getBoolean("closing"), o.getBoolean("closing"))
            assertEquals("$name: asks", e.getBoolean("needsApproval"), v.needsApproval)
            assertEquals("$name: est", e.getDouble("estUsd"), Math.round(v.estUsd * 100) / 100.0, 0.001)
            if (e.isNull("worstCaseUsd")) assertEquals(name, null, v.worstCaseUsd) else assertEquals("$name: worst", e.getDouble("worstCaseUsd"), Math.round(v.worstCaseUsd!! * 100) / 100.0, 0.001)
            assertEquals("$name: summary", e.getString("summary"), v.summary)
        }
    }

    @Test fun roundingMatches() {
        val r = fixtures().getJSONArray("rounding")
        for (i in 0 until r.length()) {
            val c = r.getJSONObject(i)
            assertEquals(c.toString(), c.getString("floor"), TradeRules.floorTo(c.getString("value").toDouble(), c.getString("step")))
            assertEquals(c.toString(), c.getString("round"), TradeRules.roundTo(c.getString("value").toDouble(), c.getString("step")))
        }
    }

    @Test fun hoursMatch() {
        val h = fixtures().getJSONArray("hours")
        for (i in 0 until h.length()) {
            val c = h.getJSONArray(i)
            val hours = if (c.isNull(0)) null else TradeRules.Hours.from(c.getJSONObject(0))
            assertEquals(c.toString(), c.getBoolean(3), TradeRules.inHours(hours, c.getInt(1), c.getInt(2)))
        }
    }

    @Test fun signingFollowsTheDocsAndRfc4231() {
        val f = fixtures()
        val h = f.getJSONObject("hmac")
        assertEquals(h.getString("hex"), TradeRules.hmacHex(h.getString("key"), h.getString("data")))
        val b = f.getJSONObject("bybitPayload")
        assertEquals(b.getString("expect"), TradeRules.bybitPayload(b.getString("ts"), b.getString("key"), b.getString("recv"), b.getString("query")))
    }

    @Test fun theModelCannotRaiseTheLimitsItself() {
        // limits come only from the account the user saved; a live account with no limits gets the strict defaults
        val l = TradeRules.limitsFor(JSONObject().put("mode", "live"))
        assertEquals(50.0, l.maxOrderUsd, 0.0); assertEquals(2, l.maxOrdersPerDay); assertFalse(l.allowDerivatives)
        assertEquals("$1,234,567.89", TradeRules.money(1234567.891))
    }
}
