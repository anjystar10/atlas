package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Attachments: the phone's routing agrees with tools/attach-fixtures.json (the PC's attach.test.mjs checks the same file). */
class AttachRulesTest {
    private val fx by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/attach-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/attach-fixtures.json").readText())
    }

    @Test fun everyKindOfFileIsRecognised() {
        val a = fx.getJSONArray("kinds")
        for (i in 0 until a.length()) { val r = a.getJSONArray(i); assertEquals("${r[0]} ${r[1]}", r.getString(2), AttachRules.kind(r.getString(0), r.getString(1))) }
    }

    @Test fun eachKindAndSizeTakesTheRightRoute() {
        val a = fx.getJSONArray("routes")
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            val got = AttachRules.route(r.getString(0), r.getLong(1), r.getString(2), r.getInt(3))
            assertEquals("${r[0]} ${r[1]} ${r[2]} ${r[3]}", r.getString(4), got)
            val note = AttachRules.note(r.getString(0), got, if (r.getString(2) == "gemini") "Gemini" else "Claude")
            assertEquals("note for $got", got == "native" || got == "text", note.isEmpty())
        }
    }

    @Test fun longTextIsCutAtLineBreaksAndCoversAllOfIt() {
        val text = (0 until 500).joinToString("\n") { "line $it " + "x".repeat(it % 37) }
        val s = AttachRules.chunkStarts(text, 1000)
        assertEquals(0, s[0])
        for (i in 1 until s.size) { assertTrue(s[i] > s[i - 1]); assertTrue(s[i] - s[i - 1] <= 1000); assertEquals('\n', text[s[i] - 1]) }
        assertTrue(text.length - s.last() <= 1000)
        assertEquals(listOf(0), AttachRules.chunkStarts("short", 1000))
    }

    @Test fun stringsAndSizes() {
        val b = byteArrayOf(0, 1, 2) + "Hello world".toByteArray() + byteArrayOf(0, -1) + "abc".toByteArray() + byteArrayOf(7) + "Version 2.1".toByteArray()
        assertEquals("Hello world Version 2.1", AttachRules.strings(b))
        assertEquals("1 KB", AttachRules.size(1536)); assertEquals("3.5 MB", AttachRules.size(3 * AttachRules.MB + AttachRules.MB / 2))
        assertEquals("00 01 02 48", AttachRules.hex(b, max = 4))
    }
}
