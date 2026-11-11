package com.anjy.atlas

import org.junit.Assert.*
import org.junit.Test

class SpeechTest {
    private fun run(ep: Speech.Endpointer, levels: List<Pair<Float, Int>>): Speech.Heard {
        var last = Speech.Heard.GOING
        for ((level, ms) in levels) repeat(ms / Speech.FRAME_MS) { if (last == Speech.Heard.GOING) last = ep.feed(level) }
        return last
    }

    @Test fun finishesAfterSpeechThenQuiet() {
        val ep = Speech.Endpointer()
        assertEquals(Speech.Heard.DONE, run(ep, listOf(0.003f to 400, 0.08f to 1200, 0.003f to 1500)))
        assertTrue(ep.speechMs >= 1000)
    }

    @Test fun aPauseForBreathDoesNotCutYouOff() {
        val ep = Speech.Endpointer()
        assertEquals(Speech.Heard.GOING, run(ep, listOf(0.003f to 300, 0.08f to 800, 0.003f to 900, 0.08f to 600)))
        assertEquals(Speech.Heard.DONE, run(ep, listOf(0.003f to 1400)))
    }

    @Test fun silenceIsNothing() {
        assertEquals(Speech.Heard.NOTHING, run(Speech.Endpointer(), listOf(0.002f to 7200)))
    }

    @Test fun aClickIsNotSpeech() {
        val ep = Speech.Endpointer()
        assertEquals(Speech.Heard.NOTHING, run(ep, listOf(0.003f to 500, 0.2f to 60, 0.003f to 7000)))
    }

    @Test fun aNoisyRoomStillEnds() {
        // a steady fan at 0.02, a voice well above it, then the fan again
        val ep = Speech.Endpointer()
        assertEquals(Speech.Heard.DONE, run(ep, listOf(0.02f to 600, 0.15f to 1000, 0.02f to 1600)))
    }

    @Test fun sentencesKeepAbbreviationsAndNumbers() {
        assertEquals(listOf("Dr. Ade is at 3.5 km.", "Is that fine?", "Good!"), Speech.sentences("Dr. Ade is at 3.5 km. Is that fine? Good!"))
    }

    @Test fun chunksStartShortAndLoseNothing() {
        val text = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val c = Speech.chunks(text)
        assertTrue(c.size > 2)
        assertTrue(c[0].length <= 140)
        assertTrue(c.all { it.length <= 320 })
        assertEquals(text, c.joinToString(" "))
        assertEquals(listOf("Hello."), Speech.chunks("Hello."))
        val long = "word ".repeat(200).trim()
        assertEquals(long, Speech.chunks(long).joinToString(" "))
    }

    @Test fun heardIsTidied() {
        assertEquals("", Speech.fixHeard("Thank you.", speechMs = 300))
        assertEquals("Thank you.", Speech.fixHeard("Thank you.", speechMs = 1500))
        assertEquals("", Speech.fixHeard("Atlas, Anjy", hint = "Atlas, Anjy"))
        assertEquals("Hey Atlas, what's the time", Speech.fixHeard("hey at last what's the time"))
        assertEquals("Ask Atlas to wait", Speech.fixHeard("Ask Atlus to wait"))
        assertEquals("", Speech.fixHeard("  ...  "))
    }

    @Test fun knowsWhenTheTalkIsOver() {
        listOf("That's all", "that's all, thanks", "Never mind.", "No, that's all for now", "Goodbye", "thanks, that's it").forEach { assertTrue(it, Speech.endsTalk(it)) }
        listOf("That's all I know about it, what else?", "Remind me to say goodbye to mum", "thanks").forEach { assertFalse(it, Speech.endsTalk(it)) }
    }

    @Test fun speedWords() {
        assertEquals("slow", Speech.speed("a bit slower")); assertEquals("fast", Speech.speed("Fast")); assertEquals("normal", Speech.speed(null))
        assertTrue(Speech.rate("slow") < 1f && Speech.rate("fast") > 1f)
    }

    @Test fun spokenTextReadsCleanly() {
        val s = Plain.spoken("Here's the plan:\n\n| Day | Task |\n|---|---|\n| Mon | Gym |\n\nSee [the guide](https://ex.com/a_b) or https://ex.com/x. Done?")
        assertFalse(s, s.contains("http") || s.contains("|") || s.contains("?.") || s.contains("---"))
        assertTrue(s, s.contains("the guide") && s.contains("Mon, Gym") && s.endsWith("Done?"))
    }

    @Test fun namesForTheHint() {
        assertEquals(listOf("Tunde", "Lagos"), Hear.names("Call Tunde about the Lagos trip. Then rest"))
    }

    @Test fun britishMalePhoneVoices() {
        assertTrue(Speech.britishMale("en-gb-x-rjs-local"))
        assertTrue(Speech.britishMale("en-GB-x-gbd-network"))
        assertFalse(Speech.britishMale("en-gb-x-fis-local"))
        assertFalse(Speech.britishMale("en-us-x-iom-local"))
        assertFalse(Speech.britishMale(null))
    }

    @Test fun endingATalk() {
        assertTrue(Speech.endsTalk("That's all, thanks."))
        assertTrue(Speech.endsTalk("never mind"))
        assertFalse(Speech.endsTalk("that's all I know about the trip, what else is there"))
    }

    @Test fun languageCodeForSpeechModels() {
        assertEquals("en", Hear.iso("en-GB"))
        assertEquals("yo", Hear.iso("yo"))
        assertNull(Hear.iso(null))
        assertNull(Hear.iso("1x"))
    }

    @Test fun designedVoiceIdFromAnyShape() {
        assertEquals("voice_abc123", Voice.voiceId(org.json.JSONObject("""{"id":"voice_abc123","sample_audio":{"data":"AAAA"}}""")))
        assertEquals("voice_x9", Voice.voiceId(org.json.JSONObject("""{"voice":{"name":"voices/voice_x9","display_name":"Atlas"}}""")))
        assertNull(Voice.voiceId(org.json.JSONObject("""{"error":{"message":"nope"}}""")))
    }

    @Test fun atlasVoiceDescriptionNamesNoOne() {
        val d = Voice.ATLAS_DESIGN.lowercase()
        listOf("jarvis", "bettany", "stark", "iron man", "marvel").forEach { assertFalse(it, d.contains(it)) }
    }
}
