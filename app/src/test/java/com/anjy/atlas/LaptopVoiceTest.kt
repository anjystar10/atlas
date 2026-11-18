package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LaptopVoiceTest {
    @Test fun laptopAdvertisesVoice() {
        assertTrue(LaptopVoice.canSpeak(JSONObject().put("caps", JSONArray(listOf("tasks", "screen", "control", "voice")))))
        assertFalse(LaptopVoice.canSpeak(JSONObject().put("caps", JSONArray(listOf("tasks", "screen")))))
        assertFalse(LaptopVoice.canSpeak(JSONObject()))
        assertFalse(LaptopVoice.canSpeak(null))
    }

    @Test fun atlasVoiceFirstWheneverItRuns() {
        val l = LaptopVoice.ID
        assertEquals(l, LaptopVoice.choose(listOf(l, "gemini"), null, false))
        assertEquals(l, LaptopVoice.choose(listOf(l, "gemini"), "gemini", false))     // picked before the laptop could speak
        assertEquals("gemini", LaptopVoice.choose(listOf(l, "gemini"), "gemini", true)) // picked while it could: respected
        assertEquals(l, LaptopVoice.choose(listOf(l, "gemini"), l, true))
        assertEquals("gemini", LaptopVoice.choose(listOf("gemini", "openai"), l, false)) // laptop away: as before
        assertEquals("openai", LaptopVoice.choose(listOf("gemini", "openai"), "openai", false))
        assertNull(LaptopVoice.choose(emptyList(), l, false))                            // the phone's own voice
    }

    @Test fun decodesTheLaptopsReply() {
        val pcm = byteArrayOf(1, 0, -1, 127, 0, -128)
        val (rate, got) = LaptopVoice.decode(JSONObject().put("rate", 24000).put("pcm", java.util.Base64.getEncoder().encodeToString(pcm)))
        assertEquals(24000, rate); assertArrayEquals(pcm, got)
    }

    @Test fun failuresSayWhy() {
        fun msg(o: JSONObject?) = try { LaptopVoice.decode(o); fail("should throw"); "" } catch (e: IllegalStateException) { e.message ?: "" }
        assertEquals("the laptop didn't answer in time", msg(null))
        assertEquals("Atlas Voice is not running on the laptop", msg(JSONObject().put("error", "Atlas Voice is not running on the laptop")))
        assertEquals("the laptop sent no audio", msg(JSONObject().put("rate", 24000)))
        assertEquals("the laptop sent no audio", msg(JSONObject().put("rate", 3).put("pcm", "AAAA")))
    }

    @Test fun languageByName() {
        assertEquals("English", LaptopVoice.languageName("en"))
        assertEquals("French", LaptopVoice.languageName("fr-FR"))
        assertEquals("Spanish", LaptopVoice.languageName("spanish"))
        assertEquals("English", LaptopVoice.languageName("yo"))
        assertEquals("English", LaptopVoice.languageName(null))
    }

    @Test fun laptopVoiceIsFirstInTheChain() {
        assertEquals(LaptopVoice.ID, Voice.ENGINES.first())
        assertEquals("Atlas Voice (laptop)", Voice.engineName(LaptopVoice.ID))
    }
}
