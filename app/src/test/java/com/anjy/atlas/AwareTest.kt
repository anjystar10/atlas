package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwareTest {
    private val bot = Pending("bot:4f2a9c11-aaaa", "Click “Place order” on shop.test", "the bot “buy socks”")
    private val lap = Pending("laptop:77aa0011", "Run a command that deletes files", "your laptop")
    private val chat = Pending("chat3", "Submit the form on example.com", "you, in this chat")

    @Test fun `a plain yes goes to the only thing waiting`() {
        assertEquals(bot, Situation.pick(listOf(bot), null))
        assertEquals(bot, Situation.pick(listOf(bot), ""))
    }

    @Test fun `with several waiting, a plain yes is unclear`() {
        assertNull(Situation.pick(listOf(bot, lap), null))
    }

    @Test fun `a ref picks exactly, or by the start of the id`() {
        assertEquals(lap, Situation.pick(listOf(bot, lap, chat), "laptop:77aa0011"))
        assertEquals(chat, Situation.pick(listOf(bot, lap, chat), "chat3"))
        assertEquals(bot, Situation.pick(listOf(bot, lap, chat), "bot:4f2a9c11"))
        // too short a prefix doesn't guess
        assertNull(Situation.pick(listOf(bot, lap, chat), "bot:4f"))
        assertNull(Situation.pick(listOf(bot, lap), "nope:zzzz9999"))
    }

    @Test fun `the brief is cut at a line end under the cap`() {
        val long = (1..400).joinToString("\n") { "- line $it with some words in it" }
        val c = Situation.cap(long, 500)
        assertTrue(c.length <= 502)
        assertTrue(c.endsWith("\n…"))
        assertTrue(c.removeSuffix("\n…").lines().all { it.startsWith("- line ") })
        assertEquals("short", Situation.cap("short", 500))
    }

    @Test fun `chat approvals are taken once`() {
        val ref = ChatAsk.add("Do a thing") { "done" }
        assertTrue(ChatAsk.list().any { it.ref == ref })
        assertEquals("Do a thing", ChatAsk.take(ref)?.what)
        assertNull(ChatAsk.take(ref))
    }
}
