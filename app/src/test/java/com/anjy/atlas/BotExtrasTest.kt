package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The same rules as the PC's botx.js (see desktop/test/logic.test.mjs): approvals, schedules, skills, specialists, helpers. */
class BotExtrasTest {
    private val R = BotExtras.Rules
    private val zone = ZoneId.of("Africa/Lagos")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun local(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone)
    private fun sched(o: JSONObject?): String = when (o?.optString("kind")) {
        "interval" -> "interval ${o.optInt("everyMin")}"
        "monthly" -> "monthly ${o.optInt("day")} ${o.optString("time")}"
        "daily" -> "daily ${o.optString("time")} ${o.optJSONArray("days")}"
        else -> o.toString()
    }

    @Test fun approvalKindsComeFromWhatTheActionDoes() {
        assertEquals("purchase", R.categoryOf("browser_click", JSONObject(), "Click “Place order”"))
        assertEquals("delete", R.categoryOf("browser_click", JSONObject(), "Click “Delete account”"))
        assertEquals("publish", R.categoryOf("browser_click", JSONObject(), "Click “Post”"))
        assertEquals("message", R.categoryOf("browser_type", JSONObject(), "Type “hi” into Message and send it"))
        assertEquals("command", R.categoryOf("run_command", JSONObject(), "Run this command on your PC: Stop-Process x"))
        assertEquals("delete", R.categoryOf("run_command", JSONObject(), "Run this command on your PC: Remove-Item x"))
        assertEquals("delete", R.categoryOf("write_file", JSONObject(), "Write the file C:\\a.txt"))
        assertEquals("terms", R.categoryOf(null, null, "Accept the terms and sign up"))
        assertEquals("message", R.categoryOf("open_link", JSONObject(), "Open tel:123 (starts a call, message or email)"))
    }

    @Test fun modesDefaultToAsk() {
        val p = JSONObject().put("modes", JSONObject().put("command", "allow").put("purchase", "deny").put("message", "nonsense"))
        assertEquals("allow", R.modeFor(p, "command"))
        assertEquals("deny", R.modeFor(p, "purchase"))
        assertEquals("ask", R.modeFor(p, "message"))
        assertEquals("ask", R.modeFor(JSONObject(), "delete"))
        val rules = R.rulesPrompt(p.put("rules", JSONArray(listOf("Never book anything on a Sunday"))))
        assertTrue(rules.contains("Sunday") && rules.contains("buy or pay"))
    }

    @Test fun schedulesInPlainWords() {
        val now = ms(2026, 11, 9, 7)   // Monday 07:00
        assertEquals("daily 08:00 [1,2,3,4,5]", sched(R.parseSchedule("every weekday at 8am", now, zone)))
        assertEquals("interval 120", sched(R.parseSchedule("every 2 hours", now, zone)))
        assertEquals("daily 18:00 [7]", sched(R.parseSchedule("every Sunday evening", now, zone)))
        assertEquals("monthly 1 09:30", sched(R.parseSchedule("every month on the 1st at 9:30", now, zone)))
        assertTrue(R.parseSchedule("every 2 minutes", now, zone)!!.has("error"))
        assertNull(R.parseSchedule("buy milk", now, zone))
        val s = R.parseSchedule("every weekday at 8am", now, zone)
        assertEquals(8, local(R.nextRun(s, now, zone)).hour)
        assertEquals(1, local(R.nextRun(s, ms(2026, 11, 13, 9), zone)).dayOfWeek.value)   // after Friday 8am: Monday
        assertEquals("Every weekday at 08:00", R.describeSchedule(s))
        val m = R.parseSchedule("every month on the 31st at 9am", now, zone)
        assertEquals(28, local(R.nextRun(m, ms(2027, 2, 1, 0), zone)).dayOfMonth)   // a short month uses its last day
    }

    @Test fun skillsAreFoundAndDraftedFromAJob() {
        val list = listOf(R.cleanSkill(JSONObject().put("name", "Weekly money check").put("when", "spending").put("steps", "1. Read"), 1, "s1"))
        assertEquals("s1", R.findByName(list, "weekly money")!!.optString("id"))
        assertTrue(R.skillsIndex(list).contains("Weekly money check"))
        assertTrue(R.skillText(list[0]).startsWith("Skill: Weekly money check"))
        val j = BotJob("j", "Find a gift", "for mum", 1).apply {
            plan.add(PlanItem("Search shops")); plan.add(PlanItem("Compare")); steps.add("You approved: Buy it"); report = "## Gift\n## Sources"
        }
        val d = R.draftSkill(j)
        assertTrue(d.optString("steps").startsWith("1. Search shops"))
        assertTrue(d.optString("permissions").contains("Buy it"))
        assertTrue(d.optString("output").contains("Gift"))
    }

    @Test fun specialistsOnlyGetTheirKindsOfTools() {
        val research = R.cleanSpecialist(R.DEFAULT_SPECIALISTS[0], 0, "x")
        assertTrue(R.toolAllowed(research, "web_search"))
        assertFalse(R.toolAllowed(research, "tap"))
        assertTrue("tools outside the groups stay allowed", R.toolAllowed(research, "browser_open"))
        assertTrue("no groups chosen: everything", R.toolAllowed(R.cleanSpecialist(R.DEFAULT_SPECIALISTS[2], 0, "y"), "tap"))
        assertTrue(R.specialistPrompt(research.put("notes", JSONArray(listOf("Prefers UK sources")))).contains("UK sources"))
    }

    @Test fun helpersGetAShareOfTheBudgetAndWaitWithoutAWorkingSlot() {
        val p = BotJob("p", "Big", "", 1).apply { toolCalls = 70 }
        assertEquals(8, BotRules.childBudget(p).first)
        assertEquals(30, BotRules.childBudget(BotJob("q", "x", "", 1)).first)
        val j = BotJob("p2", "x", "", 1).apply { state = BotRules.RUNNING }
        assertTrue(j.move(BotRules.WAITING_HELPERS, 2))
        assertTrue(BotRules.toStart(listOf(j), 3).isEmpty())
        assertTrue(j.move(BotRules.QUEUED, 4))
        assertEquals("1 working", BotRules.summary(listOf(j, BotJob("c", "child", "", 1).apply { parent = "p2" }), 5))
    }

    @Test fun newFieldsSurviveTheJournal() {
        val j = BotJob("j", "goal", "", 1).apply {
            inbox.add("Only under 250"); wrapUp = true; specialist = "sp-research"; parent = "p"; depth = 1; children.add("c1"); routine = "r1"; maxCalls = 20; maxMs = 60_000
            approval = Approval("Click “Pay”", "browser_click", JSONObject().put("target", "3"), "Pay", "approved", "purchase", true)
        }
        val b = BotJob.from(JSONObject(j.json().toString()))
        assertEquals(listOf("Only under 250"), b.inbox); assertTrue(b.wrapUp); assertEquals("sp-research", b.specialist); assertEquals("p", b.parent)
        assertEquals(1, b.depth); assertEquals(listOf("c1"), b.children); assertEquals("r1", b.routine); assertEquals(20, b.maxCalls); assertEquals(60_000L, b.maxMs)
        assertEquals("purchase", b.approval!!.category); assertTrue(b.approval!!.auto)
        val old = BotJob.from(JSONObject().put("id", "o").put("goal", "g").put("state", "queued"))
        assertTrue("a job saved by an older Atlas loads with defaults", old.inbox.isEmpty() && old.parent == null && old.depth == 0)
    }
}
