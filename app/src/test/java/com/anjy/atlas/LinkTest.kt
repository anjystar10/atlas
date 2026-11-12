package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Device Link on the phone agrees with the PC (tools/link-fixtures.json was made by desktop/renderer/linkcore.js). */
class LinkTest {
    private fun fixtures(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/link-fixtures.json").exists()) dir = dir.parentFile
        return JSONObject(File(dir!!, "tools/link-fixtures.json").readText())
    }

    @Test fun decryptsWhatThePcSent() {
        val fx = fixtures()
        assertEquals(fx.getString("owner"), Sync.owner(fx.getString("code")))
        val m = JSONObject(Sync.decrypt(fx.getString("code"), fx.getString("ciphertext")))
        val want = fx.getJSONObject("message")
        for (k in want.keys()) assertEquals(k, want.get(k).toString(), m.get(k).toString())
        assertTrue(LinkCore.accept(m, want.getString("to")))
        assertFalse(LinkCore.accept(m, "someone-else"))
    }

    @Test fun roundTripsItsOwnMessages() {
        val code = "ATLS-ABCD-EFGH-JKMN-PQRS-TVWX"
        val msg = JSONObject().put("v", 1).put("id", "x").put("k", "ping").put("from", "a").put("to", "*").put("n", 5)
        assertEquals(msg.toString(), Sync.decrypt(code, Sync.encrypt(code, msg.toString())))
    }

    @Test fun inputsMatchThePc() {
        val inputs: JSONArray = fixtures().getJSONArray("inputs")
        for (i in 0 until inputs.length()) {
            val c = inputs.getJSONObject(i)
            val got = LinkCore.inputPlan(c.getJSONObject("input"), c.getInt("w"), c.getInt("h"))
            if (c.isNull("plan")) assertNull(c.toString(), got)
            else {
                val want = c.getJSONObject("plan")
                assertEquals(c.toString(), want.length(), got!!.length())
                for (k in want.keys()) assertEquals(c.toString() + " " + k, want.get(k).toString(), got.get(k).toString())
            }
        }
    }

    @Test fun mapsAndChecks() {
        assertEquals(Pair(960, 540), LinkCore.toScreen(0.5, 0.5, 1920, 1080))
        assertEquals(Pair(1919, 1079), LinkCore.toScreen(1.0, 1.0, 1920, 1080))
        assertEquals(Pair(0, 1200), LinkCore.toScreen(-3.0, Double.NaN, 1080, 2400))
        assertEquals(Pair(0.5, 0.5), LinkCore.toFraction(50f, 25f, 100f, 50f))
        assertNull(LinkCore.toFraction(120f, 25f, 100f, 50f))
        assertNull(LinkCore.inputPlan(JSONObject().put("t", "eval").put("code", "1"), 10, 10))
        assertNull(LinkCore.inputPlan(JSONObject().put("t", "key").put("key", "ctrl+alt+del; rm"), 10, 10))
        assertNull(LinkCore.inputPlan(JSONObject().put("t", "act").put("act", "factory_reset"), 10, 10))
        assertEquals(2000, LinkCore.inputPlan(JSONObject().put("t", "text").put("text", "x".repeat(5000)), 1, 1)!!.getString("text").length)
        assertTrue(LinkCore.sessionAlive(1000, 10_000)); assertFalse(LinkCore.sessionAlive(1000, 30_000))
        assertFalse(LinkCore.accept(JSONObject().put("v", 2).put("id", "a").put("k", "task"), "pc"))
    }
}
