package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UiShapeTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun rowsFromLabelsAndTimes() {
        assertEquals(UiShape.Row("Now", "Code Atlas", UiShape.Tone.NOW), UiShape.row("Now: Code Atlas"))
        assertEquals(UiShape.Row("23:30", "stretch", UiShape.Tone.TIME), UiShape.row("23:30 stretch"))
        assertEquals(UiShape.Row("Blocked", "Fix bike", UiShape.Tone.ALERT), UiShape.row("- **Blocked:** Fix bike"))
        assertEquals(UiShape.Row("Needs your answer", "Read 20 pages", UiShape.Tone.ALERT), UiShape.row("Needs your answer: Read 20 pages"))
        assertEquals(UiShape.Row("Done today", "1", UiShape.Tone.DONE), UiShape.row("Done today: 1"))
        assertEquals("8:05", UiShape.row("• 8.05 – Gym")?.key)
    }

    @Test fun sentencesAndLinksAreNotRows() {
        assertNull(UiShape.row("Here's your day:"))
        assertNull(UiShape.row("Added stretch for today at 23:30 and an alarm will ring then."))
        assertNull(UiShape.row("https://example.com/page"))
        assertNull(UiShape.row("This sentence has far too many words before its colon: really"))
        assertNull(UiShape.row(""))
    }

    @Test fun aRunOfThreeBecomesRowsWithTheLeadLineKept() {
        val reply = "One thing needs you, the rest is on track.\nNeeds your answer: Read 20 pages\nNow: Code Atlas\n23:23 Gym\n23:30 stretch\nBlocked: Fix bike\nDone today: 1"
        val segs = UiShape.split(reply)
        assertEquals(2, segs.size)
        assertEquals(UiShape.Seg.Text("One thing needs you, the rest is on track."), segs[0])
        val rows = (segs[1] as UiShape.Seg.Rows).rows
        assertEquals(6, rows.size)
        assertEquals(listOf("Needs your answer", "Now", "23:23", "23:30", "Blocked", "Done today"), rows.map { it.key })
    }

    @Test fun shortRunsAndCodeStayAsWritten() {
        val two = "Sure.\nNow: Code Atlas\nNext: Gym\nThat's it."
        assertEquals(listOf(UiShape.Seg.Text(two)), UiShape.split(two))
        val code = "```\na: 1\nb: 2\nc: 3\n```"
        assertEquals(listOf(UiShape.Seg.Text(code)), UiShape.split(code))
        val plain = "Just a normal answer with no list."
        assertEquals(listOf(UiShape.Seg.Text(plain)), UiShape.split(plain))
    }

    @Test fun textAfterTheRowsFollowsThem() {
        val segs = UiShape.split("Lead\nA: 1\nB: 2\nC: 3\n\nWant me to move anything?")
        assertEquals(3, segs.size)
        assertEquals(UiShape.Seg.Text("Want me to move anything?"), segs[2])
    }

    @Test fun homeLineSaysWhatsNextAndWhatsWaiting() {
        val now = at(2026, 11, 18, 2, 15)
        val items = listOf(
            UiShape.Upcoming("Gym", at(2026, 11, 18, 8, 0), "planned", false),
            UiShape.Upcoming("Read 20 pages", at(2026, 11, 18, 20, 30), "planned", false),
            UiShape.Upcoming("Stretch", at(2026, 11, 17, 23, 30), "planned", true),
            UiShape.Upcoming("Old", at(2026, 11, 17, 9, 0), "done", true),
        )
        assertEquals("1 reminder waiting for you  ·  Next: Gym at 08:00  ·  2 bots working", UiShape.homeLine(items, now, zone, "2 bots working"))
        assertEquals("Next: Gym tomorrow 08:00", UiShape.homeLine(listOf(UiShape.Upcoming("Gym", at(2026, 11, 19, 8, 0), "planned", false)), now, zone, ""))
        assertTrue(UiShape.homeLine(emptyList(), now, zone, "").startsWith("Nothing scheduled"))
    }

    @Test fun savedChatTitlesAndSearch() {
        assertEquals("Remind me to stretch at 23:30", UiShape.chatTitle("  remind me to stretch   at 23:30 "))
        assertEquals("Chat", UiShape.chatTitle(null))
        assertEquals(48, UiShape.chatTitle("x".repeat(80)).length)
        assertTrue(UiShape.matches("bike FIX", "My day", "Blocked: fix bike"))
        assertFalse(UiShape.matches("bike gym", "My day", "Blocked: fix bike"))
        assertTrue(UiShape.matches("  ", "a", "b"))
    }

    @Test fun chatDates() {
        val now = at(2026, 11, 18, 2, 15)
        assertEquals("Today 01:05", UiShape.chatWhen(at(2026, 11, 18, 1, 5), now, zone))
        assertEquals("Yesterday", UiShape.chatWhen(at(2026, 11, 17, 22, 0), now, zone))
        assertEquals("Mon 16 Nov", UiShape.chatWhen(at(2026, 11, 16, 9, 0), now, zone))
    }
}
