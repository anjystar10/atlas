package com.anjy.atlas

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyRouterTest {
    private val gemini = Providers.Provider("gemini", "Gemini", "gemini", "", "", "", "")
    private val groq = Providers.Provider("groq", "Groq", "openai", "", "", "", "")

    private class FakePool(var keys: MutableList<String>, val other: Providers.Provider?, override val dontSwitch: Boolean = false) : KeyRouter.Pool {
        val rested = mutableListOf<Pair<String, Int>>()
        val restWaits = mutableListOf<Long>()
        override fun readyKey(id: String, except: String) = keys.firstOrNull { it != except && rested.none { r -> r.first == it } }
        override fun rest(id: String, key: String, code: Int, problem: String, waitMs: Long) { rested.add(key to code); restWaits.add(waitMs) }
        override fun worked(id: String, key: String) {}
        override fun fallback(except: String) = other
    }

    /** Runs the router with a call that fails with [fails] (in order) and then answers "ok". */
    private fun go(pool: FakePool, fails: List<Gemini.Failure>, waits: MutableList<Long> = mutableListOf(), usedKeys: MutableList<String> = mutableListOf()): String = runBlocking {
        var key = pool.keys.first()
        var n = 0
        KeyRouter.run(pool, gemini, { key }, { key = it }, {}, { waits.add(it) }) {
            usedKeys.add(key)
            if (n < fails.size) throw fails[n++]
            "ok"
        }
    }

    private fun busy() = Gemini.Failure("Gemini is having trouble right now (503).", code = 503)
    private fun limit() = Gemini.Failure("The Gemini limit is used up for now.", code = 429)

    @Test fun causes() {
        assertEquals(KeyRouter.Cause.PASSING, KeyRouter.cause(429, false, ""))
        assertEquals(KeyRouter.Cause.PASSING, KeyRouter.cause(503, false, ""))
        assertEquals(KeyRouter.Cause.PASSING, KeyRouter.cause(0, true, "timed out"))
        assertEquals(KeyRouter.Cause.KEY, KeyRouter.cause(401, false, ""))
        assertEquals(KeyRouter.Cause.KEY, KeyRouter.cause(402, false, ""))
        assertEquals(KeyRouter.Cause.KEY, KeyRouter.cause(400, false, "That Gemini key isn't valid."))
        assertEquals(KeyRouter.Cause.OTHER, KeyRouter.cause(400, false, "bad request: model"))
        assertEquals(KeyRouter.Cause.OTHER, KeyRouter.cause(404, false, ""))
        // a 400 about the key says so; one that only names a tool or field with "key" in it is not the key's fault
        assertEquals(KeyRouter.Cause.KEY, KeyRouter.cause(400, false, "Gemini didn't accept the request: API key expired. Please renew the API key."))
        assertEquals(KeyRouter.Cause.OTHER, KeyRouter.cause(400, false, "Groq didn't accept the request: tool call validation failed: parameters for tool press_key did not match schema"))
        assertEquals(KeyRouter.Cause.OTHER, KeyRouter.cause(400, false, "OpenAI didn't accept the request: Invalid schema for function 'search': 'keywords' is not valid"))
        assertEquals("Groq didn't accept the request: tool call validation failed: parameters for tool press_key did not match schema: invalid",
            Providers.explain("Groq", 400, "{\"error\":{\"message\":\"tool call validation failed: parameters for tool press_key did not match schema: invalid\"}}"))
        assertEquals("That Cohere key isn't valid. Check it in Me.", Providers.explain("Cohere", 400, "{\"message\":\"invalid api token\"}"))
    }

    @Test fun busyServerIsRetriedThreeTimesOnTheSameKey() {
        val used = mutableListOf<String>(); val waits = mutableListOf<Long>()
        val pool = FakePool(mutableListOf("k1", "k2"), groq)
        assertEquals("ok", go(pool, List(3) { busy() }, waits, used))
        assertEquals(listOf("k1", "k1", "k1", "k1"), used)
        assertEquals(listOf(2_000L, 5_000L, 10_000L), waits)
        assertTrue(pool.rested.isEmpty())
    }

    @Test fun aFourthLimitMovesToTheNextKey() {
        val used = mutableListOf<String>()
        val pool = FakePool(mutableListOf("k1", "k2"), groq)
        assertEquals("ok", go(pool, List(4) { limit() }, usedKeys = used))
        assertEquals(listOf("k1", "k1", "k1", "k1", "k2"), used)
        assertEquals(listOf("k1" to 429), pool.rested)
    }

    @Test fun aTurnedDownKeySwitchesAtOnce() {
        val used = mutableListOf<String>(); val waits = mutableListOf<Long>()
        val pool = FakePool(mutableListOf("k1", "k2"), groq)
        assertEquals("ok", go(pool, listOf(Gemini.Failure("denied", code = 401)), waits, used))
        assertEquals(listOf("k1", "k2"), used)
        assertTrue(waits.none { it >= 2_000 })
    }

    @Test fun noKeysLeftSwitchesServiceAfterTheRetries() {
        val pool = FakePool(mutableListOf("k1"), groq)
        try { go(pool, List(10) { limit() }); fail("should switch") } catch (s: KeyRouter.SwitchService) { assertEquals("groq", s.to.id) }
        // a busy server, retried 3 times, also moves on
        try { go(FakePool(mutableListOf("k1"), groq), List(10) { busy() }); fail("should switch") } catch (s: KeyRouter.SwitchService) { assertEquals("groq", s.to.id) }
    }

    @Test fun dontSwitchStaysAndSaysSo() {
        val waits = mutableListOf<Long>()
        val pool = FakePool(mutableListOf("k1"), groq, dontSwitch = true)
        try { go(pool, List(20) { limit() }, waits); fail("should give up") } catch (f: Gemini.Failure) {
            assertTrue(f.message!!.contains("Don't Switch Services is on"))
            assertEquals(429, f.code)
        }
        assertEquals(listOf(5_000L, 15_000L, 40_000L, 30_000L, 60_000L), waits)
        // other keys of the same service still take over
        val used = mutableListOf<String>()
        assertEquals("ok", go(FakePool(mutableListOf("k1", "k2"), groq, dontSwitch = true), List(4) { limit() }, usedKeys = used))
        assertEquals("k2", used.last())
    }

    @Test fun offlineGoesBackToTheCallerAfterRetries() {
        val used = mutableListOf<String>()
        try { go(FakePool(mutableListOf("k1", "k2"), groq), List(10) { Gemini.Failure("No internet connection.", offline = true) }, usedKeys = used); fail() }
        catch (f: Gemini.Failure) { assertTrue(f.offline) }
        assertEquals(4, used.size)
    }

    @Test fun otherErrorsAreNotRetried() {
        val used = mutableListOf<String>()
        try { go(FakePool(mutableListOf("k1"), groq), listOf(Gemini.Failure("Gemini doesn't know that model", code = 404)), usedKeys = used); fail() }
        catch (f: Gemini.Failure) { assertEquals(404, f.code) }
        assertEquals(1, used.size)
    }

    @Test fun hintsFromTheService() {
        val gem = """{"error":{"code":429,"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""
        assertEquals(37_000L, KeyRouter.retryHint(gem, null))
        assertEquals(20_000L, KeyRouter.retryHint("Rate limit reached. Please try again in 20s.", null))
        assertEquals(1_500L, KeyRouter.retryHint("Please retry in 1.5 seconds", null))
        assertEquals(20_000L, KeyRouter.retryHint("Please retry after 20 seconds.", null))
        assertEquals(90_000L, KeyRouter.retryHint("try again in 1m30s", null))
        assertEquals(500L, KeyRouter.retryHint("Please try again in 500ms.", null))
        assertEquals(120_000L, KeyRouter.retryHint("Try again in 2 minutes.", null))
        assertEquals(12_000L, KeyRouter.retryHint("Please retry later. Or try again in 12s", null))
        assertEquals(8_000L, KeyRouter.retryHint("{}", "8"))
        assertEquals(0L, KeyRouter.retryHint("Please retry later.", null))
        assertEquals(0L, KeyRouter.retryHint("retry after 20", "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(0L, KeyRouter.retryHint(null, null))
    }

    @Test fun theServicesOwnWaitIsUsed() {
        val waits = mutableListOf<Long>(); val used = mutableListOf<String>()
        val asked = Gemini.Failure("limit", code = 429, retryAfterMs = 37_000)
        assertEquals("ok", go(FakePool(mutableListOf("k1", "k2"), groq), List(3) { asked }, waits, used))
        assertEquals(listOf(37_000L, 37_000L, 37_000L), waits)
        assertEquals(listOf("k1", "k1", "k1", "k1"), used)
        // asked to wait more than a minute: not worth it, the next key takes over at once
        val waits2 = mutableListOf<Long>(); val used2 = mutableListOf<String>()
        assertEquals("ok", go(FakePool(mutableListOf("k1", "k2"), groq), listOf(Gemini.Failure("limit", code = 429, retryAfterMs = 3_600_000)), waits2, used2))
        assertEquals(listOf("k1", "k2"), used2)
        assertTrue(waits2.none { it >= 2_000 })
    }

    // ---- scan 3: Claude's 529 and OpenRouter's 408 are passing; Gemini's DAILY limit is not a one-second wait

    @Test fun overloadedAndTimedOutAreRetried() {
        assertEquals(KeyRouter.Cause.PASSING, KeyRouter.cause(529, false, "Claude is having trouble right now (529). Try again shortly."))
        assertEquals(KeyRouter.Cause.PASSING, KeyRouter.cause(408, false, ""))
        val used = mutableListOf<String>(); val waits = mutableListOf<Long>()
        assertEquals("ok", go(FakePool(mutableListOf("k1"), groq), List(2) { Gemini.Failure("Claude is having trouble right now (529).", code = 529) }, waits, used))
        assertEquals(listOf("k1", "k1", "k1"), used)
        assertEquals(listOf(2_000L, 5_000L), waits)
    }

    private val dailyBody = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"gemini-3.8-flash"},"quotaValue":"250"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}"""
    private val minuteBody = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""

    @Test fun geminisDailyLimitWaitsUntilMidnightPacific() {
        // 25 Nov 2026 10:00 UTC = 02:00 in California (PST): the next reset is 26 Nov 08:00 UTC, 09:00 in Lagos
        val now = java.time.Instant.parse("2026-11-25T10:00:00Z").toEpochMilli()
        assertEquals(22 * 3_600_000L, KeyRouter.dailyResetMs(dailyBody, now))
        assertEquals(0L, KeyRouter.dailyResetMs(minuteBody, now))
        assertEquals(0L, KeyRouter.dailyResetMs(null, now))
        // in summer (PDT, UTC-7) midnight is 07:00 UTC
        val july = java.time.Instant.parse("2026-07-01T06:00:00Z").toEpochMilli()
        assertEquals(3_600_000L, KeyRouter.dailyResetMs(dailyBody, july))
        val was = com.anjy.atlas.core.Engine.zone
        try {
            com.anjy.atlas.core.Engine.zone = java.time.ZoneId.of("Africa/Lagos")
            val said = Gemini.explain(429, dailyBody, now)
            assertTrue(said, said.startsWith("Today's free Gemini limit") && said.contains("resets at 09:00 your time"))
            assertTrue(Gemini.explain(429, minuteBody, now).contains("Wait a minute"))
        } finally { com.anjy.atlas.core.Engine.zone = was }
    }

    @Test fun aLongWaitMovesOnAtOnceAndRestsTheKeyThatLong() {
        val used = mutableListOf<String>(); val waits = mutableListOf<Long>()
        val pool = FakePool(mutableListOf("k1", "k2"), groq)
        val daily = Gemini.Failure("Today's free Gemini limit is used up for this key.", code = 429, retryAfterMs = 22 * 3_600_000L)
        assertEquals("ok", go(pool, listOf(daily), waits, used))
        assertEquals(listOf("k1", "k2"), used)
        assertTrue(waits.none { it >= 1_000 })
        assertEquals(listOf(22 * 3_600_000L), pool.restWaits)
        // Don't Switch on and no other key: no 30 s and 60 s waits for a limit that resets tomorrow
        val waits2 = mutableListOf<Long>()
        try { go(FakePool(mutableListOf("k1"), groq, dontSwitch = true), List(5) { daily }, waits2); fail("should give up") } catch (f: Gemini.Failure) {
            assertEquals(429, f.code); assertTrue(f.message!!, f.message!!.contains("Today's free Gemini limit") && !f.message!!.contains("few minutes"))
        }
        assertTrue(waits2.toString(), waits2.none { it >= 1_000 })
    }
}
