package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTest {
    private val min = 60_000L

    @Test fun breakThatEndedWhileAwayCountsOnlyTheBreak() {
        // started at 0, a 10-minute break taken at 20 min; Atlas is next opened at 50 min
        val s = Focus.State("Homework", null, 0L, null, pausedAt = 20 * min, pausedTotal = 0L, breakUntil = 30 * min)
        val back = Focus.afterBreak(s, 50 * min)!!
        assertFalse(back.paused)
        assertEquals(10 * min, back.pausedTotal)
        assertEquals(40 * min, back.worked(50 * min))   // 20 before the break + 20 after it
    }

    @Test fun breakStillRunningOrPlainPauseIsLeftAlone() {
        val onBreak = Focus.State("x", null, 0L, null, 20 * min, 0L, 30 * min)
        assertNull(Focus.afterBreak(onBreak, 25 * min))
        val paused = Focus.State("x", null, 0L, null, 20 * min, 0L, 0L)
        assertNull(Focus.afterBreak(paused, 60 * min))
        assertEquals(20 * min, paused.worked(60 * min))
    }
}
