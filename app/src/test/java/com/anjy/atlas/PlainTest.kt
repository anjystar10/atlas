package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTest {
    @Test fun idsAndNotesDisappear() {
        assertEquals("Saved memory.", Plain.reply("Saved memory [3f2a9c1e-1b2c-4d5e-8f90-1234567890ab]."))
        assertEquals("Done, it's logged.", Plain.reply("Done, it's logged.\n(What I did: Logged “Gym”, today at 10:00)"))
    }

    @Test fun printedToolCallsDisappear() {
        val s = "Sure!\n```tool_code\nprint(default_api.remember(text=\"likes tea\"))\n```\nSaved."
        assertEquals("Sure!\n\nSaved.", Plain.reply(s))
        assertEquals("Ok", Plain.reply("Ok\nremember(text=\"x\")"))
        assertEquals("Ok", Plain.reply("Ok\n```json\n{\"name\": \"set_reminder\", \"args\": {}}\n```"))
    }

    @Test fun realCodeStaysWhenAskedFor() {
        val s = "Here:\n```python\nprint('hi')\n```"
        assertEquals(s, Plain.reply(s))
    }

    @Test fun memoriesArePlainSentences() {
        assertEquals("My chess goal is 1500", Plain.memory("`my chess goal is 1500`"))
        assertEquals("Chess goal. 1500", Plain.memory("{\"fact\": \"Chess goal\", \"value\": \"1500\"}"))
        assertFalse(Plain.memory("remember(text=\"x\") likes tea").contains("("))
    }

    @Test fun modelTokensDisappear() {
        assertEquals("Hello", Plain.reply("<|im_start|>Hello<|im_end|>"))
        assertEquals("Answer", Plain.reply("<think>hmm</think>Answer"))
    }

    @Test fun spokenHasNoSymbols() {
        val s = Plain.spoken("## Plan\n- **Gym** at 10\n- Read\n\n1. Call mum")
        assertFalse(s.contains("*") || s.contains("#") || s.contains("- "))
        assertTrue(s.contains("Gym at 10"))
    }
}
