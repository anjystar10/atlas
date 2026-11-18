package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bot templates on the phone: the shipped list (assets/bot-templates.json, made from the PC's) makes complete standing bots. */
class BotTemplatesTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/assets/bot-templates.json").exists()) dir = dir.parentFile
        return dir ?: error("bot-templates.json not found")
    }
    private val list by lazy { BotTemplates.parse(File(root(), "app/src/main/assets/bot-templates.json").readText()) }

    @Test fun theFourEverydayBotsAreThere() {
        val ids = list.map { it.optString("id") }
        for (id in listOf("trader", "inbox", "reels", "research")) assertTrue("missing $id", id in ids)
    }

    @Test fun everyTemplateMakesACompleteBot() {
        for (t in list) {
            val b = BotTemplates.instantiate(t, null, 1_794_990_000_000L, "b-${t.optString("id")}")
            assertEquals(2, b.optInt("v")); assertEquals("b-${t.optString("id")}", b.optString("id"))
            assertEquals(t.optString("name"), b.optString("name")); assertEquals("user", b.optString("createdBy"))
            val acc = b.getJSONObject("access")
            for (id in Crew.CAP_IDS) assertTrue("${t.optString("id")}: $id", acc.optString(id) in setOf("off", "read", "write"))
            val trs = b.getJSONArray("triggers")
            for (i in 0 until trs.length()) {
                val tr = TriggerRules.cleanTrigger(trs.getJSONObject(i), i)
                assertTrue("${t.optString("id")}: ${tr.optString("type")}", TriggerRules.TYPES.containsKey(tr.optString("type")))
                if (tr.optString("type") == "schedule") assertNotEquals("the schedule is read", "On a schedule (not set yet)", TriggerRules.summary(tr))
            }
            assertTrue(BotTemplates.summary(t).startsWith("It may:"))
        }
    }

    @Test fun theTraderCanOnlyActOnTradingAndLiveOrdersAsk() {
        val t = list.first { it.optString("id") == "trader" }
        val b = BotTemplates.instantiate(t, "My Trader", 0, "x")
        assertEquals("My Trader", b.optString("name"))
        assertEquals("write", b.getJSONObject("access").optString("trading"))
        assertEquals("off", b.getJSONObject("access").optString("email"))
        assertTrue(BotTemplates.asks(t).any { it.contains("LIVE order") })
    }
}
