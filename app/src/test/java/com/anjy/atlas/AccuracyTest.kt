package com.anjy.atlas

import com.anjy.atlas.core.When
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** The phone gives exactly the PC's answers on the shared accuracy cases (tools/accuracy-fixtures.json). */
class AccuracyTest {
    private val fix: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/accuracy-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/accuracy-fixtures.json").readText())
    }

    @Test fun everyWhenCaseResolvesExactlyOrIsRefused() {
        val cases = fix.getJSONArray("when"); val failed = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val zone = ZoneId.of(c.getString("tz"))
            val r = When.resolve(c.getString("input"), c.getLong("now"), zone)
            val label = "${c.getString("input")} @ ${c.getString("nowLocal")} ${c.getString("tz")}"
            if (c.has("error")) {
                if (r !is When.Result.Bad || !r.error.lowercase().contains(c.getString("error").lowercase())) failed += "$label: expected refusal “${c.getString("error")}”, got $r"
            } else if (r !is When.Result.Ok || r.local != c.getString("local") || r.ms != c.getLong("ms")) failed += "$label: expected ${c.getString("local")} (${c.getLong("ms")}), got $r"
        }
        assertTrue("${failed.size} of ${cases.length()} wrong:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun clockWordsRepeatsAndDurations() {
        val clocks = fix.getJSONArray("clock")
        for (i in 0 until clocks.length()) {
            val row = clocks.getJSONArray(i); val input = row.getString(0); val got = When.parseClock(input)
            when (val want = row.get(1)) {
                "bad" -> assertTrue(input, got === When.BAD)
                is JSONArray -> assertArrayEquals(input, intArrayOf(want.getInt(0), want.getInt(1)), got)
                else -> assertNull(input, got)
            }
        }
        val reps = fix.getJSONArray("repeat")
        for (i in 0 until reps.length()) { val row = reps.getJSONArray(i); assertEquals(row.getString(0), if (row.isNull(1)) null else row.getString(1), When.normRepeat(row.getString(0))) }
        val mins = fix.getJSONArray("minutes")
        for (i in 0 until mins.length()) {
            val row = mins.getJSONArray(i); val input = if (row.isNull(0)) null else row.get(0)
            val got = When.normMinutes(input, row.getInt(1), row.getInt(2))
            when (val want = row.get(3)) { "NaN" -> assertEquals("$input", -1, got); JSONObject.NULL -> assertNull("$input", got); else -> assertEquals("$input", (want as Number).toInt(), got) }
        }
    }

    @Test fun readBackNamesTheStoredDay() {
        val cases = fix.getJSONArray("when")
        val c = (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.getString("input") == "tomorrow at 6pm" }
        val r = When.resolve(c.getString("input"), c.getLong("now"), ZoneId.of(c.getString("tz"))) as When.Result.Ok
        assertEquals("Fri 13 Nov 2026, 18:00", r.long); assertEquals("tomorrow", r.relative)
    }
}
