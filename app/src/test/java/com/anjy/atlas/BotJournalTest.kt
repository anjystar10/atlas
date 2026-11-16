package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotJournalTest {
    private fun job(id: String, at: Long, state: String = BotRules.QUEUED) = BotJob(id, "goal $id", "", at).also { it.state = state }

    @Test fun movesFollowTheRules() {
        val j = job("a", 1)
        assertTrue(j.move(BotRules.RUNNING, 2))
        assertTrue(j.move(BotRules.AWAITING_APPROVAL, 3))
        assertFalse("can't jump from waiting for an OK straight to done", j.move(BotRules.COMPLETED, 4))
        assertTrue(j.move(BotRules.QUEUED, 5))
        assertTrue(j.move(BotRules.RUNNING, 6))
        assertTrue(j.move(BotRules.COMPLETED, 7))
        assertFalse("a finished job stays finished", j.move(BotRules.RUNNING, 8))
        assertFalse(j.move(BotRules.CANCELLED, 8))
        assertEquals(listOf(BotRules.RUNNING, BotRules.AWAITING_APPROVAL, BotRules.QUEUED, BotRules.RUNNING, BotRules.COMPLETED), j.history.map { it.second })
        assertEquals(7L, j.updatedAt)
    }

    @Test fun pausedOnlyGoesBackToTheQueueOrStops() {
        val j = job("a", 1, BotRules.PAUSED)
        assertFalse(j.move(BotRules.RUNNING, 2))
        assertTrue(j.move(BotRules.QUEUED, 2))
        val k = job("b", 1, BotRules.PAUSED)
        assertTrue(k.move(BotRules.CANCELLED, 2))
    }

    @Test fun recoveryNeverReplaysAnUncertainAction() {
        assertEquals(BotRules.NEEDS_REVIEW, BotRules.recover(BotRules.RUNNING, actionPending = true))
        assertEquals(BotRules.QUEUED, BotRules.recover(BotRules.RUNNING, actionPending = false))
        for (s in BotRules.ALL.filter { it != BotRules.RUNNING }) assertEquals(s, BotRules.recover(s, actionPending = true))
    }

    @Test fun atMostTwoAtOnceOldestFirst() {
        val jobs = listOf(job("c", 30), job("a", 10), job("b", 20), job("d", 40, BotRules.PAUSED))
        assertEquals(listOf("a", "b"), BotRules.toStart(jobs, now = 100).map { it.id })
        val one = jobs + job("r", 5, BotRules.RUNNING)
        assertEquals(listOf("a"), BotRules.toStart(one, now = 100).map { it.id })
        val retry = listOf(job("w", 1, BotRules.WAITING_RETRY).also { it.retryAt = 500 })
        assertTrue(BotRules.toStart(retry, now = 100).isEmpty())
        assertEquals(listOf("w"), BotRules.toStart(retry, now = 600).map { it.id })
    }

    @Test fun retryWaitsGrowAndStop() {
        assertEquals(60_000L, BotRules.retryDelay(1))
        assertEquals(120_000L, BotRules.retryDelay(2))
        assertEquals(16 * 60_000L, BotRules.retryDelay(5))
        assertEquals(16 * 60_000L, BotRules.retryDelay(9))
    }

    @Test fun consequentialPhoneActionsNeedAnOk() {
        val none = JSONObject()
        assertNotNull(BotRules.phoneApproval("tap", JSONObject().put("element", 3), "Send"))
        assertNotNull(BotRules.phoneApproval("tap", none, "Place order"))
        assertNull(BotRules.phoneApproval("tap", none, "Search"))
        assertNull(BotRules.phoneApproval("tap", none, null))
        assertNotNull(BotRules.phoneApproval("type_text", JSONObject().put("text", "hi").put("press_enter", true), null))
        assertNull(BotRules.phoneApproval("type_text", JSONObject().put("text", "hi"), null))
        assertNotNull(BotRules.phoneApproval("open_link", JSONObject().put("url", "tel:123"), null))
        assertNull(BotRules.phoneApproval("open_link", JSONObject().put("url", "https://example.com"), null))
        assertNotNull(BotRules.phoneApproval("forget", none, null))
        assertNotNull(BotRules.phoneApproval("update_reminder", JSONObject().put("action", "delete"), null))
        assertNull(BotRules.phoneApproval("update_reminder", JSONObject().put("action", "done"), null))
        assertNull(BotRules.phoneApproval("web_search", none, null))
        assertNull(BotRules.pcApproval("run_command", JSONObject().put("command", "Get-ChildItem C:\\Users")))
        assertNotNull(BotRules.pcApproval("run_command", JSONObject().put("command", "Remove-Item C:\\x")))
        assertNotNull(BotRules.pcApproval("run_command", JSONObject().put("command", "Get-ChildItem | Remove-Item")))
    }

    @Test fun journalRoundTrips() {
        val j = job("a", 1)
        j.move(BotRules.RUNNING, 2)
        j.plan.add(PlanItem("Search", true)); j.plan.add(PlanItem("Compare"))
        j.notes.add("Phone X costs 299"); j.sources.add("https://example.com"); j.step("Searched"); j.addRecent("result")
        j.toolCalls = 4; j.workMs = 1234; j.provider = "Gemini"; j.pending = "Tapping"
        j.approval = Approval("Tap “Send”", "tap", JSONObject().put("element", 3), "Send")
        j.question = "Which colour?"; j.device = "phone-1"; j.suggestPc = "Many pages"
        val back = BotJob.parseList(BotJob.listJson(listOf(j))).single()
        assertEquals(j.json().toString(), back.json().toString())
        assertEquals("Tapping", back.pending)
        assertEquals(3, back.approval!!.args!!.getInt("element"))
        assertNull(back.answer)
        assertTrue(back.plan[0].done)
        assertEquals("phone-1", back.device); assertEquals("Many pages", back.suggestPc)
        // ready to sync later: plain JSON with updated_ms and the owning device, and nothing secret
        assertEquals(j.updatedAt, j.json().getLong("updated_ms"))
        assertFalse(j.json().toString().contains("key"))
    }

    @Test fun summaryForTheStrip() {
        val now = 10_000_000L
        val jobs = listOf(job("a", 1, BotRules.RUNNING), job("b", 1, BotRules.AWAITING_APPROVAL), job("c", 1, BotRules.COMPLETED).also { it.updatedAt = now - 1000 })
        assertEquals("1 working · 1 needs you · 1 done", BotRules.summary(jobs, now))
        assertEquals("", BotRules.summary(emptyList(), now))
    }

    // 0.10.1: bots keep going until the job is done
    @Test fun botsKeepGoingUntilDone() {
        assertTrue("plans can be long", BotRules.MAX_PLAN >= 50)
        assertFalse("a bot that did work and then talks hasn't finished", BotRules.textEndsJob(0, false))
        assertFalse(BotRules.textEndsJob(1, false))
        assertTrue(BotRules.textEndsJob(2, false))
        assertTrue(BotRules.textEndsJob(0, true))
        assertTrue(BotRules.remindOpenSteps(2, false, false))
        assertFalse("only once", BotRules.remindOpenSteps(2, true, false))
        assertFalse(BotRules.remindOpenSteps(0, false, false))
        assertFalse(BotRules.remindOpenSteps(3, false, true))
        assertTrue(BotRules.MAX_TOOL_CALLS >= 300 && BotRules.MAX_WORK_MS >= 120 * 60_000L)
        val j = BotJob("x", "g", "", 1L)
        j.planNudged = true
        assertTrue("survives a restart", BotJob.from(j.json()).planNudged)
    }
}
