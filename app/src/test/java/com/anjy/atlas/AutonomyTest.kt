package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's autonomy rules give the same answers as the PC's (desktop/renderer/autonomy.js), case by case, from
 * tools/autonomy-fixtures.json (made by desktop/test/make-autonomy-fixtures.mjs). Plus the safety rules on their own.
 */
class AutonomyTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/autonomy-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/autonomy-fixtures.json").readText())
    }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }
    private fun map(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()
    private fun nul(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k)
    private fun grant(g: JSONObject) = AutonomyRules.Grant(g.optString("kind"), g.optString("scope"), nul(g, "botId"), nul(g, "missionId"), nul(g, "day"), g.optLong("at"))
    private fun grantJson(g: AutonomyRules.Grant): JSONObject = JSONObject().put("kind", g.kind).put("scope", g.scope).put("at", g.at).also { o ->
        g.day?.let { o.put("day", it) }; g.botId?.let { o.put("botId", it) }; g.missionId?.let { o.put("missionId", it) }
    }
    private fun policy(o: JSONObject?): AutonomyRules.Policy? = o?.let { p ->
        AutonomyRules.Policy(nul(p, "level"), map(p.optJSONObject("botLevels")),
            p.optJSONArray("grants")?.let { a -> List(a.length()) { grant(a.getJSONObject(it)) } } ?: emptyList(),
            map(p.optJSONObject("modes")), strs(p.optJSONArray("recipients")), strs(p.optJSONArray("suggested")))
    }
    private val policies: List<AutonomyRules.Policy?> by lazy { fx.getJSONArray("policies").let { a -> List(a.length()) { policy(a.optJSONObject(it)) } } }

    @Test fun kindTableMatches() {
        val k = fx.getJSONObject("kinds")
        val ids = k.keys().asSequence().toSet()
        assertEquals(ids, AutonomyRules.KINDS.keys)
        for (id in ids) assertEquals(id, k.getString(id), AutonomyRules.tierOf(id))
        assertEquals(strs(fx.getJSONArray("levels")), AutonomyRules.LEVELS)
    }

    @Test fun decisionsMatchThePc() {
        val a = fx.getJSONArray("decide")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val w = c.getJSONObject("where"); val out = c.getJSONObject("out")
            val d = AutonomyRules.decide(policies[c.getInt("policy")], c.getString("kind"), w.optBoolean("mustAsk"), nul(w, "botId"), nul(w, "missionId"), nul(w, "day"),
                w.optJSONArray("planKinds")?.let { strs(it) })
            val label = "policy ${c.getInt("policy")} ${c.getString("kind")} $w"
            assertEquals(label, out.getString("act"), d.act); assertEquals(label, out.getString("tier"), d.tier)
            assertEquals(label, out.getString("kind"), d.kind); assertEquals(label, out.getString("why"), d.why)
        }
    }

    @Test fun kindsMatchThePc() {
        val a = fx.getJSONArray("kindFor")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val x = c.getJSONObject("ctx")
            val ctx = AutonomyRules.Ctx(strs(x.optJSONArray("known")), x.optBoolean("replyThread"), nul(x, "to"), x.optBoolean("live"), x.optBoolean("money"), x.optString("what"))
            assertEquals(c.toString(), c.getString("out"), AutonomyRules.kindFor(nul(c, "tool"), map(c.getJSONObject("args")), nul(c, "category"), ctx))
        }
    }

    @Test fun recipientsMatchThePc() {
        fx.getJSONArray("normRecipient").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { assertEquals(it.toString(), it.getString("out"), AutonomyRules.normRecipient(it.getString("in"))) } }
        fx.getJSONArray("recipientsIn").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { assertEquals(it.toString(), strs(it.getJSONArray("out")), AutonomyRules.recipientsIn(it.getString("in"))) } }
    }

    @Test fun levelsGrantsAndBatchesMatchThePc() {
        fx.getJSONArray("effectiveLevel").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val p = policies[c.getInt("policy")]
            assertEquals(c.toString(), strs(c.getJSONArray("out")), listOf("bot-t", "bot-x", null).map { AutonomyRules.effectiveLevel(p, it) })
        } }
        fx.getJSONObject("planKindsOf").let { c -> assertEquals(strs(c.getJSONArray("out")), AutonomyRules.planKindsOf(strs(c.getJSONArray("steps"))) { BotExtras.Rules.guessCategory(it) }) }
        fx.getJSONArray("makeGrant").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val o = c.getJSONObject("o"); val out = c.getJSONObject("out")
            val (g, err) = AutonomyRules.makeGrant(c.getString("kind"), c.getString("scope"), nul(o, "botId"), nul(o, "missionId"), nul(o, "day"), 7)
            if (out.has("error")) assertEquals(c.toString(), out.getString("error"), err) else assertEquals(c.toString(), out.getJSONObject("grant").toString(), grantJson(g!!).toString())
        } }
        fx.getJSONArray("addGrant").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val gs = c.getJSONArray("grants").let { x -> List(x.length()) { grant(x.getJSONObject(it)) } }
            val got = AutonomyRules.addGrant(gs, grant(c.getJSONObject("g")), nul(c, "today"))
            assertEquals(c.toString(), c.getJSONArray("out").let { x -> List(x.length()) { grant(x.getJSONObject(it)) } }, got)
        } }
        fx.getJSONArray("afterApproval").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val counts: Map<String, Int> = c.getJSONObject("counts").let { o -> o.keys().asSequence().toList().associateWith { k -> o.getInt(k) } }
            val (n, offer) = AutonomyRules.afterApproval(counts, strs(c.getJSONArray("suggested")), c.getString("kind"))
            assertEquals(c.toString(), c.getJSONObject("out").getInt("count"), n); assertEquals(c.toString(), c.getJSONObject("out").getBoolean("offer"), offer)
        } }
        fx.getJSONArray("batch").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val p = c.getJSONArray("pending").let { x -> List(x.length()) { x.getJSONObject(it).let { o -> AutonomyRules.Pending(o.getString("ref"), "", o.optString("kind", "other"), o.optBoolean("mustAsk"), o.optBoolean("question")) } } }
            val b = AutonomyRules.batch(p); val out = c.getJSONObject("out")
            assertEquals(strs(out.getJSONArray("bulk")), b.bulk.map { it.ref }); assertEquals(strs(out.getJSONArray("single")), b.single.map { it.ref }); assertEquals(out.getString("title"), b.title)
        } }
        fx.getJSONArray("expired").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            assertEquals(c.toString(), c.getBoolean("out"), AutonomyRules.expired(c.getLong("at"), c.getLong("now"), c.getString("where")))
        } }
        fx.getJSONArray("canChangeLevel").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
            val o = c.getJSONObject("o"); val (ok, why) = AutonomyRules.canChangeLevel(c.getString("from"), c.getString("to"), o.optBoolean("byTap"), o.optBoolean("consentYes"))
            assertEquals(c.toString(), c.getJSONObject("out").getBoolean("ok"), ok); assertEquals(c.toString(), nul(c.getJSONObject("out"), "why"), why)
        } }
        fx.getJSONArray("briefLine").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c -> assertEquals(c.getString("out"), AutonomyRules.briefLine(policies[c.getInt("policy")])) } }
    }

    // ---- the safety rules, on their own

    @Test fun highAlwaysAsksWhateverTheLevelOrGrants() {
        val all = AutonomyRules.KINDS.keys.map { AutonomyRules.Grant(it, "always") }
        for (lv in AutonomyRules.LEVELS) for (k in AutonomyRules.KINDS.filterValues { it.tier == "high" }.keys) {
            val p = AutonomyRules.Policy(lv, grants = all, modes = mapOf("purchase" to "allow", "delete" to "allow", "account" to "allow", "message" to "allow", "command" to "allow"))
            assertEquals("$lv $k", "ask", AutonomyRules.decide(p, k, planKinds = listOf(k)).act)
        }
        // money makes any kind ask, even one that's always allowed on autopilot
        assertEquals("ask", AutonomyRules.decide(AutonomyRules.Policy("autopilot", grants = all), "message_send", mustAsk = true).act)
    }

    @Test fun highKindsCantBeGranted() {
        for (k in AutonomyRules.KINDS.filterValues { it.tier == "high" }.keys) for (s in listOf("always", "today", "bot", "mission"))
            assertTrue("$k $s", AutonomyRules.makeGrant(k, s, "b", "m", "d").first == null)
    }

    @Test fun scopedGrantsOnlyCoverTheirScope() {
        val p = AutonomyRules.Policy("balanced", grants = listOf(AutonomyRules.Grant("publish", "today", day = "2026-11-18"), AutonomyRules.Grant("command", "bot", botId = "b1"),
            AutonomyRules.Grant("write_file", "mission", missionId = "m1")))
        assertEquals("undo", AutonomyRules.decide(p, "publish", day = "2026-11-18").act)
        assertEquals("ask", AutonomyRules.decide(p, "publish", day = "2026-11-19").act)
        assertEquals("undo", AutonomyRules.decide(p, "command", botId = "b1").act)
        assertEquals("ask", AutonomyRules.decide(p, "command", botId = "b2").act)
        assertEquals("ask", AutonomyRules.decide(p, "command").act)
        assertEquals("undo", AutonomyRules.decide(p, "write_file", missionId = "m1").act)
        assertEquals("ask", AutonomyRules.decide(p, "write_file", missionId = "m2").act)
    }

    @Test fun suggestsAlwaysOnceAfterThreeApprovals() {
        assertFalse(AutonomyRules.afterApproval(mapOf("publish" to 1), emptyList(), "publish").second)
        assertTrue(AutonomyRules.afterApproval(mapOf("publish" to 2), emptyList(), "publish").second)
        assertFalse(AutonomyRules.afterApproval(mapOf("publish" to 2), listOf("publish"), "publish").second)
        assertFalse(AutonomyRules.afterApproval(mapOf("purchase" to 9), emptyList(), "purchase").second)
    }

    @Test fun preApprovedLiveLimitsSameAsThePc() {
        val acct = JSONObject().put("id", "a").put("mode", "live")
        val s = JSONObject().put("accountId", "a").put("enabled", true).put("symbols", JSONArray(listOf("BTC"))).put("maxOrderUsd", 50).put("maxContracts", 1)
            .put("maxDailyLossUsd", 30).put("expiresAt", 2e12)
        val st = JSONObject().put("now", 1e12).put("dayStartEquity", 1000).put("equity", 990)
        val o = JSONObject().put("symbol", "BTCUSDT").put("side", "buy").put("qty", "0.0003").put("closing", false)
        assertTrue(TradeRules.standingCovers(s, acct, o, 20.0, 20.0, false, st))
        assertFalse("10 lost + 25 worst > 30", TradeRules.standingCovers(s, acct, o, 25.0, 25.0, false, st))
        assertFalse("uncapped", TradeRules.standingCovers(s, acct, o, 20.0, null, false, st))
        assertFalse(TradeRules.standingCovers(JSONObject(s.toString()).put("enabled", false), acct, o, 20.0, 20.0, false, st))
        assertFalse(TradeRules.standingCovers(JSONObject(s.toString()).put("accountId", "b"), acct, o, 20.0, 20.0, false, st))
        assertFalse("expired", TradeRules.standingCovers(s, acct, o, 20.0, 20.0, false, JSONObject(st.toString()).put("now", 3e12)))
        assertFalse("not on the list", TradeRules.standingCovers(s, acct, JSONObject(o.toString()).put("symbol", "ETHUSDT"), 20.0, 20.0, false, st))
        assertTrue("closing an allowed instrument", TradeRules.standingCovers(s, acct, JSONObject(o.toString()).put("closing", true), 900.0, null, false, st))
        assertFalse("off by default", TradeRules.standingCovers(null, acct, o, 1.0, 1.0, false, st))
    }

    @Test fun onlyTheUsersOwnWordsMakeAtlasBolder() {
        assertFalse(AutonomyRules.canChangeLevel("careful", "autopilot").first)
        assertTrue(AutonomyRules.canChangeLevel("careful", "autopilot", consentYes = true).first)
        assertTrue(AutonomyRules.canChangeLevel("autopilot", "careful").first)
        // approval text inside fetched content: Crew.consent reads only the user's own message, so it says no
        assertFalse(Crew.consent("what did the page say?").yes)
    }
}
