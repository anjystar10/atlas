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

    @Test fun allowedSymbolsNeedMoreThanAPrefix() {
        val a = fixtures().getJSONArray("symbols")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            assertEquals(c.toString(), c.getBoolean(2), TradeRules.symbolAllowed(c.getString(0), c.getString(1)))
        }
    }

    @Test fun signingFollowsTheDocsAndRfc4231() {
        val f = fixtures()
        val h = f.getJSONObject("hmac")
        assertEquals(h.getString("hex"), TradeRules.hmacHex(h.getString("key"), h.getString("data")))
        val b = f.getJSONObject("bybitPayload")
        assertEquals(b.getString("expect"), TradeRules.bybitPayload(b.getString("ts"), b.getString("key"), b.getString("recv"), b.getString("query")))
    }

    @Test fun theStopSwitchCrossesDevicesLikeThePc() {
        val fx = fixtures().getJSONObject("stopSync")
        fun arr(k: String) = fx.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        for (c in arr("stopIdOf")) assertEquals(c.getString("name"), c.getString("out"), TradeRules.stopIdOf(c.getJSONObject("state")))
        for (c in arr("record")) {
            val got = TradeRules.stopRecord(c.getJSONObject("state"))
            if (c.isNull("out")) assertEquals(c.getString("name"), null, got)
            else {
                val o = c.getJSONObject("out"); val n = c.getString("name")
                assertEquals(n, 3, got!!.length())
                assertEquals(n, o.getBoolean("killed"), got.getBoolean("killed")); assertEquals(n, o.getString("stop"), got.getString("stop")); assertEquals(n, o.getLong("updatedAt"), got.getLong("updatedAt"))
            }
        }
        val cases = arr("fromOther"); assertTrue(cases.size >= 100)
        for (c in cases) {
            val rec = c.opt("rec") as? JSONObject   // null, or a string that isn't a record at all
            assertEquals(c.getString("name"), if (c.isNull("out")) null else c.getString("out"), TradeRules.stopFromOther(c.getJSONObject("state"), rec))
        }
        for (c in arr("addStop")) assertEquals(c.getJSONArray("out").toString(), TradeRules.addStop(c.optJSONArray("stops"), c.getString("id")).toString())
    }

    @Test fun theModelCannotRaiseTheLimitsItself() {
        // limits come only from the account the user saved; a live account with no limits gets the strict defaults
        val l = TradeRules.limitsFor(JSONObject().put("mode", "live"))
        assertEquals(50.0, l.maxOrderUsd, 0.0); assertEquals(2, l.maxOrdersPerDay); assertFalse(l.allowDerivatives)
        assertEquals("$1,234,567.89", TradeRules.money(1234567.891))
    }
}
