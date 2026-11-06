package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {
    @Test fun exhaustedMeansSwitch() {
        assertTrue(Providers.keyExhausted(Gemini.Failure("limit", code = 429)))
        assertTrue(Providers.keyExhausted(Gemini.Failure("credit", code = 402)))
        assertTrue(Providers.keyExhausted(Gemini.Failure("denied", code = 401)))
        assertTrue(Providers.keyExhausted(Gemini.Failure("That Gemini key isn't valid.", code = 400)))
        assertFalse(Providers.keyExhausted(Gemini.Failure("bad request: model", code = 400)))
        assertFalse(Providers.keyExhausted(Gemini.Failure("busy", code = 503)))
    }

    @Test fun usableUnlessRestingOrBad() {
        val now = 1_000_000L
        assertTrue(Providers.KeyEntry("a").usable(now))
        assertFalse(Providers.KeyEntry("a", restUntil = now + 1).usable(now))
        assertFalse(Providers.KeyEntry("a", bad = true).usable(now))
        assertEquals("a", Providers.KeyEntry("a").json().getString("key"))
    }
}
