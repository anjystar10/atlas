package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Named bots, their faces and the Bots tab's History: the phone's BotFace gives exactly the PC's answers
 * (desktop/renderer/botface.js) for every case in tools/botface-fixtures.json (made by desktop/test/make-botface-fixtures.mjs).
 */
class BotFaceTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/botface-fixtures.json").exists()) dir = dir.parentFile
        return dir ?: error("tools/botface-fixtures.json not found")
    }
    private val fx by lazy { JSONObject(File(root(), "tools/botface-fixtures.json").readText()) }
    private fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    /** A JSON value as plain, order-free Kotlin values (the JVM's org.json has no similar()). */
    private fun norm(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().map { it to norm(v.opt(it)) }.toMap(java.util.TreeMap())
        is JSONArray -> (0 until v.length()).map { norm(v.opt(it)) }
        is Number -> v.toDouble()
        JSONObject.NULL -> null
        else -> v
    }

    @Test fun hashMatches() {
        for (c in objs(fx.getJSONArray("hash"))) assertEquals("hash of ${c.getString("s")}", c.getLong("out"), BotFace.hash(c.getString("s")))
    }

    @Test fun namesFacesAndMigrationMatch() {
        for (c in objs(fx.getJSONArray("bots"))) {
            val b = c.getJSONObject("in"); val who = b.optString("name")
            assertEquals("motif of $who", c.getString("motif"), BotFace.motifOf(b))
            val f = BotFace.faceOf(b); val want = c.getJSONObject("face")
            assertEquals("shape of $who", want.getString("shape"), f.shape)
            assertEquals("colour of $who", want.getInt("color"), f.color)
            assertEquals("label of $who", c.getString("label"), BotFace.label(b))
            assertEquals("subtitle of $who", c.getString("subtitle"), BotFace.subtitle(b))
            val m = BotFace.migrate(b)!!
            assertEquals("changed: $who", c.getBoolean("changed"), m !== b)
            assertEquals("migrated $who", norm(c.getJSONObject("migrated")), norm(m))
            val mf = BotFace.faceOf(m); val wm = c.getJSONObject("migratedFace")
            assertEquals(wm.getString("shape"), mf.shape); assertEquals(wm.getInt("color"), mf.color); assertEquals(wm.getString("motif"), mf.motif)
            assertEquals("label after: $who", c.getString("migratedLabel"), BotFace.label(m))
            assertEquals("persona of $who", c.getString("persona"), BotFace.personaPrompt(m))
        }
    }

    @Test fun historyMatches() {
        val jobs = objs(fx.getJSONArray("jobs"))
        for (c in objs(fx.getJSONArray("history"))) {
            val o = c.getJSONObject("opts")
            val h = BotFace.historyGroups(jobs, o.optString("bot").ifEmpty { null }, o.optString("query").ifEmpty { null }, o.optInt("tzMin"), o.getLong("now"))
            val want = c.getJSONObject("out")
            assertEquals("waiting $o", want.getInt("waiting"), h.waiting)
            assertEquals("total $o", want.getInt("total"), h.total)
            val wg = objs(want.getJSONArray("groups"))
            assertEquals("groups $o", wg.map { it.getString("day") + "|" + it.getString("label") }, h.groups.map { it.day + "|" + it.label })
            assertEquals("items $o", wg.map { g -> objs(g.getJSONArray("items")).map { it.getString("id") + "@" + it.getString("bot") } }, h.groups.map { g -> g.items.map { it.id + "@" + it.bot } })
        }
    }

    @Test fun defaultsAreNamedTeammates() {
        val names = BotExtras.Rules.DEFAULT_SPECIALISTS.map { it.optString("name") }
        assertEquals(listOf("Nova", "Iris", "Ted", "Mara"), names)
        val ted = BotExtras.Rules.cleanSpecialist(BotExtras.Rules.DEFAULT_SPECIALISTS[2], 0, "x")
        assertEquals("Developer", ted.optString("title"))
        assertTrue(BotExtras.Rules.specialistPrompt(ted).contains("Your name is Ted, the user's developer."))
        assertEquals("Ted", BotExtras.Rules.findByName(BotExtras.Rules.DEFAULT_SPECIALISTS, "Developer Bot")!!.optString("name"))
        assertEquals("Nova", BotExtras.Rules.findByName(BotExtras.Rules.DEFAULT_SPECIALISTS, "the researcher")!!.optString("name"))
    }
}
