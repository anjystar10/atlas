package com.anjy.atlas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EngineTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val MIN = Engine.MIN
    private fun at(s: String): Long = LocalDateTime.parse(s).atZone(lagos).toInstant().toEpochMilli()

    @Before fun setUp() { Engine.zone = lagos }

    private fun state(qs: String = "23:00", qe: String = "06:00") =
        AtlasState().also { it.settings.quietStart = qs; it.settings.quietEnd = qe }

    @Test fun remindCheckinFollowupCap() {
        val s = state()
        val now = at("2026-11-04T10:00:00")
        val t = Engine.create(s, "Read", now, now + 10 * MIN, 30)
        assertEquals(0, Engine.tick(s, now).size)
        assertEquals(now + 10 * MIN, Engine.nextWake(s, now))
        assertEquals(listOf("remind"), Engine.tick(s, now + 10 * MIN).map { it.type })
        assertEquals("active", t.status)
        assertEquals(0, Engine.tick(s, now + 39 * MIN).size)
        assertEquals(listOf("checkin"), Engine.tick(s, now + 40 * MIN).map { it.type })
        assertEquals("awaiting", t.status)
        assertEquals(now + 55 * MIN, Engine.nextWake(s, now + 40 * MIN))
        assertEquals(0, Engine.tick(s, now + 54 * MIN).size)
        assertEquals(listOf("followup"), Engine.tick(s, now + 55 * MIN).map { it.type })
        var m = 56L
        while (m < 600) { assertEquals(0, Engine.tick(s, now + m * MIN).size); m += 5 }
        assertEquals("awaiting", t.status)
        assertTrue(Engine.followUpsExhausted(s, t))
        assertNull(Engine.nextWake(s, now + 600 * MIN))
    }

    @Test fun catchUpAfterClosed() {
        val s = state()
        val now = at("2026-11-04T09:00:00")
        Engine.create(s, "X", now - 5 * MIN, now, 30)
        val ev = Engine.tick(s, now + 180 * MIN)
        assertEquals(listOf("remind", "checkin"), ev.map { it.type })
        assertTrue(ev[0].late)
        assertEquals(0, Engine.tick(s, now + 180 * MIN).size)
    }

    @Test fun quietHoursDeferFollowups() {
        val s = state("22:00", "07:00")
        val now = at("2026-11-04T21:00:00")
        val t = Engine.create(s, "Q", now, now, 50)
        Engine.tick(s, now); Engine.tick(s, now + 55 * MIN)
        assertEquals("awaiting", t.status)
        assertEquals(0, Engine.tick(s, now + 75 * MIN).size)
        assertEquals(at("2026-11-05T07:00:00"), Engine.nextWake(s, now + 75 * MIN))
        assertEquals(listOf("followup"), Engine.tick(s, at("2026-11-05T07:01:00")).map { it.type })
    }

    @Test fun quietWrap() {
        val st = Settings(quietStart = "22:30", quietEnd = "06:30")
        assertTrue(Engine.inQuiet(st, at("2026-11-04T23:00:00")))
        assertTrue(Engine.inQuiet(st, at("2026-11-04T06:29:00")))
        assertTrue(!Engine.inQuiet(st, at("2026-11-04T06:30:00")))
        assertTrue(Engine.inQuiet(Settings(quietStart = "13:00", quietEnd = "14:00"), at("2026-11-04T13:30:00")))
        assertTrue(!Engine.inQuiet(Settings(quietStart = "13:00", quietEnd = "13:00"), at("2026-11-04T13:00:00")))
    }

    @Test fun responses() {
        val s = state()
        val now = at("2026-11-04T10:00:00")
        val a = Engine.create(s, "A", now, now, 30, recurrence = "daily")
        Engine.tick(s, now); Engine.tick(s, now + 30 * MIN)
        Engine.respond(s, a.id, "progress", now + 31 * MIN, minutes = 15)
        assertEquals("active", a.status)
        assertEquals(listOf("checkin"), Engine.tick(s, now + 46 * MIN).map { it.type })
        val (_, next) = Engine.respond(s, a.id, "done", now + 47 * MIN)
        assertEquals("done", a.status)
        assertEquals(now + 47 * MIN, a.completedAt)
        assertEquals(at("2026-11-05T10:00:00"), next!!.startAt)
        try { Engine.respond(s, next.id, "delay", now, startAt = now - 10 * MIN); fail() } catch (e: AtlasException) {}
        Engine.respond(s, next.id, "delay", now, startAt = at("2026-11-05T12:00:00"))
        assertEquals("planned", next.status); assertNull(next.remindedAt)
        Engine.respond(s, next.id, "blocked", now, reason = "no internet")
        assertEquals("blocked", next.status)
        assertEquals(0, Engine.tick(s, at("2026-11-06T12:00:00")).size)
        Engine.respond(s, next.id, "skipped", now, reason = "sick")
        assertEquals("skipped", next.status)
    }

    @Test fun recurrence() {
        val fri = at("2026-11-06T08:00:00")
        assertEquals(at("2026-11-09T08:00:00"), Engine.nextOcc(fri, "weekdays"))
        val s = state()
        val t0 = at("2026-11-01T08:00:00")
        val a = Engine.create(s, "R", t0, t0, 20, recurrence = "daily")
        val (_, next) = Engine.respond(s, a.id, "done", at("2026-11-04T09:00:00"))
        assertEquals(at("2026-11-05T08:00:00"), next!!.startAt)
        assertTrue(s.log.any { it.type == "note" && it.detail.startsWith("3 past") })
    }

    @Test fun aRepeatingReminderLeftUnansweredStillComesRoundAgain() {
        val s = state()
        val t0 = at("2026-11-04T08:00:00")
        val pill = Engine.create(s, "Pill", t0 - 60 * MIN, t0, 5, recurrence = "daily")
        // it rings (Reminders): the next one is made right away, whether or not it's ever answered
        pill.remindedAt = t0
        val next = Engine.continueSeries(s, pill, t0)
        assertEquals(at("2026-11-05T08:00:00"), next!!.startAt)
        assertEquals(pill.id, next.seriesId)
        assertNull(Engine.continueSeries(s, pill, t0 + MIN))
        // answered later: no second copy of tomorrow's
        val (_, again) = Engine.respond(s, pill.id, "done", t0 + 30 * MIN)
        assertNull(again)
        assertEquals(1, s.tasks.count { it.seriesId == pill.id })
        // a one-off doesn't repeat
        val once = Engine.create(s, "Call", t0, t0, 10)
        assertNull(Engine.continueSeries(s, once, t0))
    }

    @Test fun delayDoesNotDriftASeries() {
        val s = state()
        val t0 = at("2026-11-04T07:00:00")
        val gym = Engine.create(s, "Gym", t0 - 60 * MIN, t0, 60, recurrence = "daily")
        Engine.tick(s, t0)
        Engine.respond(s, gym.id, "delay", t0 + 5 * MIN, startAt = at("2026-11-04T09:00:00"))
        val (_, next) = Engine.respond(s, gym.id, "done", at("2026-11-04T10:05:00"))
        assertEquals(at("2026-11-05T07:00:00"), next!!.startAt)
        assertEquals(at("2026-11-05T07:00:00"), next.seriesAt)
        // editing the time on purpose does move the series
        Engine.update(s, next.id, "Gym", t0, at("2026-11-05T18:00:00"), 60, "", "normal", "daily")
        val (_, after) = Engine.respond(s, next.id, "done", at("2026-11-05T19:00:00"))
        assertEquals(at("2026-11-06T18:00:00"), after!!.startAt)
    }

    @Test fun bareHourMeansTheNextOne() {
        assertEquals(at("2026-11-04T18:00:00"), Engine.parseQuick("Gym at 6", at("2026-11-04T15:00:00")).startAt)
        assertEquals(at("2026-11-05T06:00:00"), Engine.parseQuick("Gym at 6", at("2026-11-04T20:40:00")).startAt)
        assertEquals(at("2026-11-04T06:00:00"), Engine.parseQuick("Gym at 6", at("2026-11-04T05:00:00")).startAt)
        assertEquals(at("2026-11-04T21:00:00"), Engine.parseQuick("Journal tonight at 9", at("2026-11-04T10:00:00")).startAt)
        assertEquals(at("2026-11-05T06:00:00"), Engine.parseQuick("Run tomorrow at 6", at("2026-11-04T10:00:00")).startAt)
        assertEquals(at("2026-11-04T18:00:00"), Engine.parseQuick("Gym today at 6", at("2026-11-04T10:00:00")).startAt)
        assertEquals(at("2026-11-04T12:30:00"), Engine.parseQuick("Lunch at 12:30", at("2026-11-04T10:00:00")).startAt)
    }

    @Test fun editAndCollisions() {
        val s = state()
        val now = at("2026-11-04T10:00:00")
        val a = Engine.create(s, "A", now, now + 60 * MIN, 60)
        val b = Engine.create(s, "B", now, now + 90 * MIN, 30)
        val c = Engine.create(s, "C", now, now + 120 * MIN, 30)
        val ids = Engine.collisions(s)
        assertTrue(a.id in ids && b.id in ids && c.id !in ids)
        Engine.update(s, b.id, "B2", now, now + 200 * MIN, 30, "", "normal", "none")
        assertEquals(0, Engine.collisions(s).size)
        assertEquals(2, b.version)
        try { Engine.create(s, "  ", now); fail() } catch (e: AtlasException) {}
    }

    @Test fun startNowUnscheduled() {
        val s = state()
        val now = at("2026-11-04T10:00:00")
        val a = Engine.create(s, "U", now, null, 25, recurrence = "daily")
        assertEquals("none", a.recurrence)
        Engine.startNow(s, a.id, now)
        assertEquals(listOf("checkin"), Engine.tick(s, now + 25 * MIN).map { it.type })
    }

    @Test fun parseQuick() {
        val now = at("2026-11-04T20:40:00")
        var p = Engine.parseQuick("Gym at 6pm for 1h", now)
        assertEquals("Gym", p.title); assertEquals(at("2026-11-05T18:00:00"), p.startAt); assertEquals(60, p.durationMin)
        p = Engine.parseQuick("Call mum tomorrow at 9:30", now)
        assertEquals("Call mum", p.title); assertEquals(at("2026-11-05T09:30:00"), p.startAt)
        p = Engine.parseQuick("Read 20 pages at 21:00 for 30 min", now)
        assertEquals("Read 20 pages", p.title); assertEquals(at("2026-11-04T21:00:00"), p.startAt); assertEquals(30, p.durationMin)
        assertEquals(at("2026-11-05T00:00:00"), Engine.parseQuick("Pray at 12am", now).startAt)
        assertEquals(at("2026-11-04T12:00:00"), Engine.parseQuick("Lunch at 12pm", at("2026-11-04T08:00:00")).startAt)
        p = Engine.parseQuick("Look at 25 things", now)
        assertNull(p.startAt); assertEquals("Look at 25 things", p.title)
        p = Engine.parseQuick("Buy milk", now)
        assertNull(p.startAt); assertNull(p.durationMin)
        p = Engine.parseQuick("Plan week tomorrow", now)
        assertEquals("Plan week tomorrow", p.title); assertNull(p.startAt)
        assertEquals(at("2026-11-04T20:00:00"), Engine.parseQuick("Journal tonight", at("2026-11-04T10:00:00")).startAt)
    }

    @Test fun memoryAndToolTimes() {
        val s = state()
        val now = at("2026-11-04T10:00:00")
        val m = Engine.remember(s, "My chess rating is 1200", now)
        assertEquals(1, Engine.searchMemories(s, "what is my chess rating").size)
        Engine.forget(s, m.id, now)
        assertEquals(0, s.memories.size)
        assertEquals(at("2026-11-05T09:30:00"), Engine.resolveDayTime("tomorrow", "09:30", now))
        assertEquals(at("2026-11-10T07:00:00"), Engine.resolveDayTime("2026-11-10", "7:00", now))
        assertNull(Engine.resolveDayTime("today", "25:00", now))
        assertNull(Engine.resolveDayTime("someday", "10:00", now))
    }
}
