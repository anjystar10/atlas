package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Conversations with bots: the phone's ThreadRules give exactly the PC's answers (desktop/renderer/threadcore.js) for
 * every case in tools/threads-fixtures.json (made by desktop/test/make-thread-fixtures.mjs).
 */
class ThreadsTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/threads-fixtures.json").exists()) dir = dir.parentFile
        return dir ?: error("tools/threads-fixtures.json not found")
    }
    private val fx by lazy { JSONObject(File(root(), "tools/threads-fixtures.json").readText()) }
    private val bots by lazy { fx.getJSONArray("bots").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { b -> ThreadRules.Bot(b.getString("id"), b.getString("name")) } } } }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    private fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

    @Test fun limitsMatch() {
        val l = fx.getJSONObject("limits")
        assertEquals(l.getInt("MAX_MESSAGES"), ThreadRules.MAX_MESSAGES)
        assertEquals(l.getInt("MAX_TEXT"), ThreadRules.MAX_TEXT)
        assertEquals(l.getInt("MAX_BOT_TURNS"), ThreadRules.MAX_BOT_TURNS)
        assertEquals(l.getInt("MAX_PAIR_HOPS"), ThreadRules.MAX_PAIR_HOPS)
        assertEquals(l.getInt("MAX_PARTICIPANTS"), ThreadRules.MAX_PARTICIPANTS)
    }

    @Test fun mentionsAndStripping() {
        for (c in objs(fx.getJSONArray("mentions"))) {
            val text = c.getString("text")
            assertEquals("mentions in: $text", strings(c.getJSONArray("out")), ThreadRules.mentionsIn(text, bots))
            assertEquals("stripped: $text", c.getString("stripped"), ThreadRules.stripMentions(text, bots))
        }
    }

    private fun msgOf(o: JSONObject) = ThreadRules.cleanMessage(o)

    @Test fun decisions() {
        for (c in objs(fx.getJSONArray("decide"))) {
            val t = ThreadRules.cleanThread(c.getJSONObject("thread"))
            val d = ThreadRules.decide(t, msgOf(c.getJSONObject("msg")), bots)
            val out = c.getJSONObject("out")
            val name = c.getString("name")
            assertEquals("$name: starts", strings(out.getJSONArray("starts")), d.starts)
            assertEquals("$name: waiting", out.opt("waiting")?.takeIf { it != JSONObject.NULL }?.toString(), d.waiting)
            assertEquals("$name: hint", out.opt("hint")?.takeIf { it != JSONObject.NULL }?.toString(), d.hint)
        }
    }

    @Test fun cleaning() {
        for (c in objs(fx.getJSONArray("clean"))) {
            val want = c.getJSONObject("out")
            val got = ThreadRules.cleanThread(c.getJSONObject("in"), c.getLong("now"), c.optString("id"))
            assertEquals(want.getString("id"), got.id)
            assertEquals(want.getString("kind"), got.kind)
            assertEquals(want.getString("title"), got.title)
            assertEquals(strings(want.getJSONArray("participants")), got.participants)
            assertEquals(want.getLong("updatedAt"), got.updatedAt)
            val wm = objs(want.getJSONArray("messages"))
            assertEquals(wm.size, got.messages.size)
            wm.forEachIndexed { i, m ->
                val g = got.messages[i]
                assertEquals(m.getString("id"), g.id); assertEquals(m.getLong("at"), g.at); assertEquals(m.getString("from"), g.from)
                assertEquals(m.getString("text"), g.text); assertEquals(strings(m.getJSONArray("mentions")), g.mentions)
                assertEquals(m.opt("kind")?.takeIf { it != JSONObject.NULL }?.toString(), g.kind)
            }
        }
    }

    @Test fun transcriptsGoalsAndTurns() {
        for (c in objs(fx.getJSONArray("transcript"))) {
            val t = ThreadRules.cleanThread(c.getJSONObject("thread"))
            assertEquals(c.getString("out"), ThreadRules.transcript(t, bots, c.getInt("max")))
        }
        for (c in objs(fx.getJSONArray("goal"))) {
            assertEquals(c.getString("out"), ThreadRules.goalFrom(msgOf(c.getJSONObject("msg")), bots, c.getString("fromName")))
        }
        val turns = fx.getJSONObject("turns")
        val t = ThreadRules.cleanThread(turns.getJSONObject("thread"))
        assertEquals(turns.getInt("botTurns"), ThreadRules.botTurnsSinceUser(t))
        assertEquals(turns.getInt("pairHops"), ThreadRules.pairHops(t, "bot-reels", "sp-research"))
    }

    @Test fun directConversations() {
        val t = ThreadRules.cleanThread(JSONObject().put("id", ThreadRules.directId("bot-inbox")).put("participants", JSONArray(listOf("user", "x"))))
        assertEquals("direct", t.kind); assertEquals(listOf("user", "bot-inbox"), t.participants); assertEquals("bot-inbox", ThreadRules.directBot(t))
        val m = ThreadRules.Msg("m1", 1, "user", "anything", emptyList()); t.messages.add(m)
        assertEquals(listOf("bot-inbox"), ThreadRules.decide(t, m, bots).starts)
        // a round trip keeps fields a newer Atlas wrote
        val back = ThreadRules.cleanThread(JSONObject(t.json().put("pinned", true).toString()))
        assertEquals(true, back.json().optBoolean("pinned"))
    }
}
