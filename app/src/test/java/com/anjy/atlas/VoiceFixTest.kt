package com.anjy.atlas

import org.junit.Assert.*
import org.junit.Test

/** The two reasons the voice never seemed to change: a voice remembered forever, and failures nobody saw. */
class VoiceFixTest {
    private val C = Speech.VoiceCache
    private val rev = C.rev("design one", "gemini-3.8-flash-tts")

    @Test fun aRememberedVoiceIsUsedForTheSameRevisionAndKey() {
        val e = C.entry(rev, "abc123", "voice_x", 1000)
        assertEquals(Speech.VoiceCache.Hit.Use("voice_x"), C.read(e, rev, "abc123", 5000))
    }

    @Test fun aNewDesignMakesTheVoiceAfresh() {
        val e = C.entry(rev, "abc123", "voice_x", 1000)
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read(e, C.rev("design two", "gemini-3.8-flash-tts"), "abc123", 5000))
    }

    @Test fun theOldForeverFormatIsNeverTrustedAgain() {
        // before: "abc123|voice_old" or "-" kept for good, so the voice could never change
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read("abc123|voice_old", rev, "abc123", 5000))
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read("-", rev, "abc123", 5000))
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read(null, rev, "abc123", 5000))
    }

    @Test fun anotherKeyMakesItsOwn() {
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read(C.entry(rev, "abc123", "voice_x", 1000), rev, "zzz999", 5000))
    }

    @Test fun aRefusalIsOnlyRememberedForAFewHours() {
        val e = C.entry(rev, "abc123", null, 1000)
        assertEquals(Speech.VoiceCache.Hit.Refused, C.read(e, rev, "abc123", 1000 + 60_000))
        assertEquals(Speech.VoiceCache.Hit.Miss, C.read(e, rev, "abc123", 1000 + C.RETRY_MS + 1))
    }

    @Test fun usedUpLimitsAndTurnedDownKeysTryTheNextKey() {
        assertTrue(Speech.ttsKeyProblem(429)); assertTrue(Speech.ttsKeyProblem(403)); assertTrue(Speech.ttsKeyProblem(401))
        assertFalse(Speech.ttsKeyProblem(400)); assertFalse(Speech.ttsKeyProblem(500)); assertFalse(Speech.ttsKeyProblem(0))
    }

    @Test fun aDailyLimitRestsLongerThanAPerMinuteOne() {
        val day = Speech.ttsRestMs(429, "Quota exceeded: GenerateRequestsPerDayPerProjectPerModel", null)
        val minute = Speech.ttsRestMs(429, "Quota exceeded for requests per minute", 20_000)
        assertTrue(day >= 60 * 60_000L)
        assertTrue(minute in 30_000L..10 * 60_000L)
        assertEquals(0L, Speech.ttsRestMs(400, "bad", null))
    }

    @Test fun naturalVoicesUseFewerRequests() {
        val reply = (1..12).joinToString(" ") { "This is sentence number $it of a fairly ordinary spoken reply from Atlas." }
        val before = Speech.chunks(reply).size
        val now = Speech.chunks(reply, Speech.NATURAL_FIRST, Speech.NATURAL_REST)
        assertTrue("was $before pieces, now ${now.size}", now.size < before)
        assertTrue(now.first().length <= Speech.NATURAL_FIRST)
        assertEquals(reply.replace(" ", ""), now.joinToString("").replace(" ", ""))
    }

    @Test fun theFallbackNoteSaysWhy() {
        assertTrue(Speech.fallbackNote("Gemini: limit reached").contains("Gemini: limit reached"))
        assertTrue(Speech.fallbackNote(null).contains("key"))
    }
}
