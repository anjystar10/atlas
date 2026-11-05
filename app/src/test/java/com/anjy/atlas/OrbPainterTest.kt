package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbPainterTest {
    private fun alpha(c: Int) = c ushr 24

    @Test fun paintsAnOrbWithClearCorners() {
        val n = 150; val out = IntArray(n * n)
        for (t in listOf(0f, 7.5f, 33f)) for (e in listOf(0f, 1f)) {
            OrbPainter().paint(out, n, n, t, e, e)
            assertEquals(0, alpha(out[0])); assertEquals(0, alpha(out[n - 1])); assertEquals(0, alpha(out[n * n - 1]))
            assertEquals(255, alpha(out[(n / 2) * n + n / 2]))
            val solid = out.count { alpha(it) == 255 }
            assertTrue("orb covers a sensible share: $solid", solid in (n * n / 6)..(n * n / 2))
            // something blue is lit inside, not just a dark disc
            assertTrue(out.any { alpha(it) == 255 && (it and 0xFF) > 180 })
        }
    }

    @Test fun nonSquareViewsWork() {
        val out = IntArray(150 * 90)
        OrbPainter().paint(out, 150, 90, 2f, 0f, 0f)
        assertEquals(255, alpha(out[45 * 150 + 75]))
    }
}
