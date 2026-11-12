package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Atlas Bot v2's model and rules (Crew.kt) give exactly the PC's answers (desktop/renderer/crew.js): the same cases run in
 * desktop/test/logic.test.mjs, from tools/bots2-fixtures.json (made by running the PC's code).
 */
class CrewTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/bots2-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/bots2-fixtures.json").readText())
    }
    private fun cases(k: String): List<JSONObject> = fx.getJSONArray(k).let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun same(expected: Any?, actual: Any?, msg: String) {
        val ok = when {
            expected is JSONObject && actual is JSONObject -> expected.similar(actual)
            expected is JSONArray && actual is JSONArray -> expected.similar(actual)
            else -> expected == actual
        }
        assertTrue("$msg\nexpected: $expected\nactual:   $actual", ok)
    }
    private fun obj(o: JSONObject, k: String): JSONObject? = o.optJSONObject(k)

    @Test fun capabilities() {
        for (c in cases("capabilityOf")) {
            val u = Crew.capabilityOf(c.getString("tool"))
            same(c.getJSONObject("out"), JSONObject().put("cap", u.cap).put("write", u.write), c.getString("tool"))
        }
        same(fx.getJSONObject("defaultAccess"), Crew.defaultAccess(), "defaultAccess")
    }

    @Test fun botsNormaliseLikeThePc() {
        for (c in cases("normalizeBot")) same(c.getJSONObject("out"), Crew.normalizeBot(c.getJSONObject("in"), c.getLong("now"), c.getString("id")), c.getString("id"))
    }

    @Test fun access() {
        val bots = cases("normalizeBot").map { it.getJSONObject("out") }
        for (c in cases("allows")) {
            val got = Crew.allows(bots[c.getInt("botIndex")], c.getString("tool"), { _, _ -> true }, obj(c, "access"))
            assertEquals("${c.getInt("botIndex")} ${c.getString("tool")}", c.getBoolean("out"), got)
        }
        for (c in cases("narrowAccess")) same(c.getJSONObject("out"), Crew.narrowAccess(obj(c, "parent"), obj(c, "child")), c.toString())
    }

    @Test fun budgets() {
        for (c in cases("budgetCheck")) same(c.getJSONObject("out"), Crew.budgetCheck(obj(c, "budget"), c.getJSONObject("u")), c.toString())
        for (c in cases("moneyAllowed")) assertEquals(c.toString(), c.getBoolean("out"), Crew.moneyAllowed(obj(c, "budget"), c.getJSONObject("u"), c.getDouble("amount")))
        for (c in cases("agentSlice")) same(c.getJSONObject("out"), Crew.agentSlice(c.getLong("callsLeft"), c.getLong("msLeft")), c.toString())
        for (c in cases("usageToday")) same(c.getJSONObject("out"), Crew.usageToday(obj(c, "u"), c.getString("day")), c.toString())
        for (c in cases("dayOf")) assertEquals(c.toString(), c.getString("out"), Crew.dayOf(c.getLong("ms"), c.getInt("offsetMin")))
    }

    @Test fun plansHaveNoCap() {
        for (c in cases("parsePlanTree")) same(c.getJSONArray("out"), Crew.planJson(Crew.parsePlanTree(c.getString("text"))), c.getString("text").take(40))
        val long = (0 until 120).joinToString("\n") { (if (it % 3 != 0) "  " else "") + "[ ] step $it" }
        assertEquals(120, Crew.parsePlanTree(long).size)
    }

    @Test fun onlyTheUsersOwnWordsAuthorize() {
        for (c in cases("userSaid")) assertEquals(c.getString("text"), c.opt("out").let { if (it == JSONObject.NULL) null else it }, Crew.userSaid(c.getString("text")))
    }

    @Test fun dryRunAndMissions() {
        for (c in cases("dryRunSkips")) assertEquals(c.getString("tool"), c.getBoolean("out"), Crew.dryRunSkips(c.getString("tool"), c.getBoolean("needsApproval")))
        for (c in cases("missionDefaults")) same(c.getJSONObject("out"), Crew.missionDefaults(JSONObject(c.getJSONObject("in").toString())), c.getJSONObject("in").optString("id"))
    }
}
