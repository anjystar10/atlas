package com.anjy.atlas.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The phone gives exactly the PC's answers on the shared cases (tools/around-fixtures.json, tools/updates-fixtures.json). */
class SelfAwareTest {
    private fun tool(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/$name").exists()) dir = dir.parentFile
        return File(dir!!, "tools/$name")
    }
    private val around by lazy { JSONObject(tool("around-fixtures.json").readText()) }
    private val upd by lazy { JSONObject(tool("updates-fixtures.json").readText()) }
    private val cl by lazy { Updates.parseChangelog(tool("updates-changelog-fixture.json").readText()) }
    private fun nul(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k)

    @Test fun nowLineAndAlertsMatchThePc() {
        val cases = around.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            assertEquals(name, c.getString("nowLine"), Around.nowLine(c.getJSONObject("facts")))
            val want = c.getJSONArray("alerts")
            val got = Around.alerts(c.getJSONObject("facts"), c.getJSONObject("ctx"))
            assertEquals(name, want.length(), got.size)
            for (j in got.indices) {
                assertEquals(name, want.getJSONObject(j).getString("key"), got[j].key)
                assertEquals(name, want.getJSONObject(j).getString("text"), got[j].text)
            }
        }
    }

    @Test fun markNewMatchesThePc() {
        val c = around.getJSONArray("markNew").getJSONObject(0)
        val list = c.getJSONArray("list").let { a -> (0 until a.length()).map { Around.Alert(a.getJSONObject(it).getString("key"), a.getJSONObject(it).getString("text")) } }
        val seen = c.getJSONArray("seen").let { a -> (0 until a.length()).map { a.getString(it) } }
        val (lines, keys) = Around.markNew(list, seen)
        val out = c.getJSONObject("out")
        assertEquals(out.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }, lines)
        assertEquals(out.getJSONArray("seen").let { a -> (0 until a.length()).map { a.getString(it) } }, keys)
    }

    @Test fun theBriefSectionStaysShort() {
        val f = JSONObject().put("time", "Wednesday 18 November 2026, 02:30").put("zone", "Africa/Lagos").put("battery", 4).put("charging", false)
            .put("network", "none").put("dnd", true).put("audio", "bluetooth").put("foreground", "A very long application name that goes on and on and on")
            .put("notifications", 42).put("storageFreeGb", 0.2).put("thermal", "very hot").put("cpu", 99).put("ram", 97).put("gpu", 95)
        val (lines, keys) = Around.section(f, JSONObject().put("botsRunning", 3).put("tradingRunning", true), emptyList())
        assertTrue(lines.joinToString("\n").length <= Around.SECTION_MAX)
        assertTrue(lines.first().startsWith("Now: "))
        assertTrue(lines.any { it.contains("Battery at 4%") && it.contains("trading bot") })
        assertEquals(listOf("battery-low", "offline", "storage", "thermal", "load"), keys)
        // told once: the second time the same alerts aren't marked NEW
        val (again, _) = Around.section(f, JSONObject().put("botsRunning", 3).put("tradingRunning", true), keys)
        assertFalse(again.any { it.contains("NEW") })
    }

    @Test fun detailNamesWhatItKnows() {
        val d = Around.detail(JSONObject().put("battery", 50).put("charging", true).put("notifications", 2).put("notifApps", JSONArray().put("WhatsApp").put("Gmail")).put("nextAlarm", "07:00"))
        assertEquals("Battery: 50%, charging\nNotifications: 2 (WhatsApp, Gmail)\nNext alarm: 07:00", d)
        assertEquals("Nothing known about the surroundings right now.", Around.detail(JSONObject()))
    }

    @Test fun versionsCompareLikeThePc() {
        val a = upd.getJSONArray("compare")
        for (i in 0 until a.length()) { val c = a.getJSONObject(i); assertEquals(c.toString(), c.getInt("r"), Updates.compareVersions(c.getString("a"), c.getString("b"))) }
    }

    @Test fun launchStatesMatchThePc() {
        val a = upd.getJSONArray("launch")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val got = Updates.launchState(nul(c, "last"), c.getString("current"), c.getBoolean("hadData"))
            assertEquals(c.toString(), c.getJSONObject("out").getString("kind"), got.kind)
            assertEquals(c.toString(), nul(c.getJSONObject("out"), "from"), got.from)
        }
    }

    @Test fun updatedMessagesMatchThePc() {
        val a = upd.getJSONArray("messages")
        for (i in 0 until a.length()) { val c = a.getJSONObject(i); assertEquals(c.getString("out"), Updates.updatedMessage(cl, nul(c, "from"), c.getString("to"), c.getString("where"))) }
    }

    @Test fun newerMatchesThePc() {
        val a = upd.getJSONArray("newer")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val l = c.getJSONObject("latest"); val ins = c.getJSONObject("installed")
            val latest = Updates.Latest(l.getString("version"), l.getInt("versionCode"), "https://x", "", 0, emptyList(), "")
            assertEquals(c.toString(), c.getBoolean("r"), Updates.isNewer(latest, ins.getString("version"), ins.getInt("versionCode")))
        }
    }

    @Test fun latestJsonMatchesThePc() {
        val a = upd.getJSONArray("latest")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val got = Updates.parseLatest(c.getString("in"))
            if (c.isNull("out")) { assertNull(c.getString("in"), got); continue }
            val o = c.getJSONObject("out")
            assertEquals(o.getString("version"), got!!.version); assertEquals(o.getInt("versionCode"), got.versionCode)
            assertEquals(o.getString("url"), got.url); assertEquals(o.getString("sha256"), got.sha256); assertEquals(o.getLong("size"), got.size)
        }
    }

    @Test fun briefLinesMatchThePc() {
        val a = upd.getJSONArray("brief")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val s = c.getJSONObject("s")
            val others = s.getJSONArray("others").let { o -> (0 until o.length()).map { val x = o.getJSONObject(it); Updates.Other(x.getString("platform"), x.getString("name"), x.getString("app")) } }
            val latest = if (s.isNull("latest")) null else s.getJSONObject("latest").let { Updates.Latest(it.getString("version"), it.getInt("versionCode"), it.getString("url"), "", 0, emptyList(), "") }
            val st = Updates.State(s.getString("version"), s.getInt("versionCode"), s.getString("device"), nul(s, "from"), s.getLong("updatedAt"), s.getLong("now"), others, latest)
            val want = c.getJSONArray("out").let { o -> (0 until o.length()).map { o.getString(it) } }
            assertEquals(want, Updates.briefLines(cl, st))
            assertTrue(Updates.briefLines(cl, st).joinToString("\n").length <= Updates.BRIEF_MAX + 2)
        }
    }

    @Test fun theRealChangelogIsSound() {
        val real = Updates.parseChangelog(tool("../CHANGELOG.json").readText())
        assertTrue(real.size >= 4)
        // newest first, version codes rising with versions, every entry has notes and abilities
        for (k in 1 until real.size) assertTrue(Updates.compareVersions(real[k - 1].version, real[k].version) > 0 && real[k - 1].versionCode > real[k].versionCode)
        real.forEach { assertTrue(it.version, it.notes.isNotEmpty() && it.abilities.isNotEmpty()) }
        // the app's own version is in it
        val gradle = tool("../app/build.gradle.kts").readText()
        val v = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt()
        val e = Updates.entryFor(real, v)
        assertTrue("CHANGELOG.json has no entry for $v", e != null)
        assertEquals(code, e!!.versionCode)
    }

    @Test fun signerCheck() {
        assertTrue(Updates.sameSigner(listOf("2B:C2:56:59"), listOf("2bc25659")))
        assertFalse(Updates.sameSigner(listOf("2B:C2:56:59"), listOf("deadbeef")))
        assertFalse(Updates.sameSigner(emptyList(), emptyList()))
    }
}
