package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atlas Bot v2 on the phone (docs/BOTS_V2.md): the mission record, open-ended plans, agent ceilings and who may say yes. */
class BotsV2Test {
    @Test fun aMissionKeepsItsV2FieldsThroughTheJournal() {
        val j = BotJob("m1", "Compare prices", "", 10, "phone-1")
        j.bot = "sp-trader"; j.role = "price checker"; j.dryRun = true; j.budgetStop = true
        j.access = Crew.narrowAccess(null, JSONObject().put("web", "write"))
        j.source = JSONObject().put("type", "trigger").put("ref", "t1").put("at", 10)
        j.costCalls = 7; j.costTokensIn = 1200; j.costTokensOut = 300
        j.intended.add("Send an email"); j.verifyStatus = "failed"; j.verifyRounds = 3; j.verifyNotes.add("Slot has no price")
        j.plan.add(PlanItem("Shops", true)); j.plan.add(PlanItem("Jumia", false, 1))
        val o = j.json()
        assertEquals(2, o.getInt("v"))
        assertEquals(7, o.getJSONObject("cost").getInt("calls"))
        val back = BotJob.from(JSONObject(o.toString()))
        assertEquals("sp-trader", back.bot); assertEquals("price checker", back.role)
        assertTrue(back.dryRun); assertTrue(back.budgetStop)
        assertEquals("write", back.access!!.optString("web")); assertEquals("off", back.access!!.optString("email"))
        assertEquals("trigger", back.source!!.optString("type"))
        assertEquals(7, back.costCalls); assertEquals(1200L, back.costTokensIn); assertEquals(300L, back.costTokensOut)
        assertEquals(listOf("Send an email"), back.intended.toList())
        assertEquals("failed", back.verifyStatus); assertEquals(3, back.verifyRounds); assertEquals(listOf("Slot has no price"), back.verifyNotes.toList())
        assertEquals(listOf(0, 1), back.plan.map { it.depth })
        assertFalse("a depth of 0 isn't written", o.getJSONArray("plan").getJSONObject(0).has("depth"))
    }

    @Test fun anOldJobLoadsWithV2Defaults() {
        val back = BotJob.from(JSONObject().put("id", "old").put("goal", "x").put("state", "queued"))
        assertNull(back.bot); assertNull(back.role); assertNull(back.access)
        assertFalse(back.dryRun); assertFalse(back.budgetStop)
        assertEquals("none", back.verifyStatus); assertEquals(0, back.costCalls)
    }

    @Test fun plansAreOpenEndedAndAgentsGoDeeper() {
        assertTrue(BotRules.MAX_PLAN >= 300)
        assertEquals(4, BotRules.MAX_DEPTH); assertEquals(12, BotRules.MAX_CHILDREN)
        val long = (0 until 120).joinToString("\n") { (if (it % 3 != 0) "  " else "") + "[ ] step $it" }
        assertEquals(120, Crew.parsePlanTree(long).size)
    }

    @Test fun onlyTheUsersOwnWordsSayYes() {
        assertTrue(Situation.consent("yes").yes)
        assertTrue(Situation.consent("👍").yes)
        assertTrue(Situation.consent("please approve it").yes)
        assertTrue(Situation.consent("ok, always allow that").always)
        assertFalse(Situation.consent("yes").always)
        assertFalse(Situation.consent("no, wait").yes)
        assertFalse(Situation.consent("don't send it").yes)
        assertFalse("a question isn't a yes", Situation.consent("what did the bot find?").yes)
        assertFalse(Situation.consent("").yes)
        // what the model saw on a page doesn't count: only what was heard from the user
        Situation.heard("summarise that page for me")
        assertFalse(Situation.consent().yes)
        Situation.heard("Yes go ahead")
        assertTrue(Situation.consent().yes)
    }
}
