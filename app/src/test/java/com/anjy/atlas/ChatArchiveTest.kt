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

    /** An unreadable index is made again from the chat files: each one's title, time and size come from its records. */
    @Test fun aChatsLineIsWorkedOutFromItsRecords() {
        val recs = org.json.JSONArray("""[{"id":"1","user":false,"text":"Hi, I'm Atlas","at":100},{"id":"2","user":true,"text":"what's the  weather in Lagos","at":200},{"id":"3","user":false,"text":"Sunny","at":300}]""")
        val m = ChatArchive.metaOf("abc-1", recs, 5)!!
        assertEquals("abc-1", m.id); assertEquals("What's the weather in Lagos", m.title); assertEquals(300L, m.at); assertEquals(3, m.count)
        // no time on the last record: the file's own time
        assertEquals(5L, ChatArchive.metaOf("x", org.json.JSONArray("""[{"user":true,"text":"yo"}]"""), 5)!!.at)
        assertEquals(null, ChatArchive.metaOf("x", org.json.JSONArray(), 5))
    }
}
