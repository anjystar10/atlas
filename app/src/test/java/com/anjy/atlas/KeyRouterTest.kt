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
        override fun readyKey(id: String, except: String) = keys.firstOrNull { it != except && rested.none { r -> r.first == it } }
        override fun rest(id: String, key: String, code: Int, problem: String) { rested.add(key to code) }
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
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L), waits)
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
}
