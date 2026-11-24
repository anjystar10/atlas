package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArchiveTest {
    private fun m(id: String, at: Long) = ChatArchive.Meta(id, id, at, 2)

    @Test fun anOlderChatPutAwayWhenTheListIsFullIsKept() {
        val full = (1..5).map { m("c$it", 1000L + it) }
        // an old chat (opened again, then New Chat) sorts last: it stays, the oldest other goes
        val (kept, gone) = ChatArchive.trimmed(full, m("old", 10L), 5)
        assertEquals(5, kept.size)
        assertTrue(kept.any { it.id == "old" })
        assertEquals(listOf("c1"), gone.map { it.id })
        assertEquals(kept.sortedByDescending { it.at }, kept)
    }

    @Test fun roomLeftKeepsEverything() {
        val (kept, gone) = ChatArchive.trimmed(listOf(m("a", 5)), m("b", 9), 200)
        assertEquals(listOf("b", "a"), kept.map { it.id }); assertTrue(gone.isEmpty())
    }
}
