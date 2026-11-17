package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Any size: copies stream in constant memory and can be stopped; big text is read and searched part by part. */
class AttachStreamTest {
    /** [n] bytes made up on the fly: nothing this size ever exists in memory. */
    private class Endless(private val n: Long) : InputStream() {
        var given = 0L
        override fun read(): Int = if (given >= n) -1 else (given++ % 251).toInt()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (given >= n) return -1
            val k = minOf(len.toLong(), n - given).toInt(); for (i in 0 until k) b[off + i] = ((given + i) % 251).toByte(); given += k; return k
        }
    }
    private class Sink : OutputStream() { var got = 0L; var sum = 0L
        override fun write(b: Int) { got++; sum += b and 0xFF }
        override fun write(b: ByteArray, off: Int, len: Int) { got += len; for (i in off until off + len) sum += b[i].toInt() and 0xFF } }

    @Test fun copiesAnySizeInConstantMemoryWithProgress() {
        val n = 300L * 1024 * 1024   // 300 MB, never held
        val sink = Sink(); var last = -1L; var calls = 0; var checks = 0
        val rt = Runtime.getRuntime(); System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val done = Attach.copyStream(Endless(n), sink, n, { d, t -> assertTrue(d > last); assertEquals(n, t); last = d; calls++ }) { checks++ }
        val after = rt.totalMemory() - rt.freeMemory()
        assertEquals(n, done); assertEquals(n, sink.got); assertEquals(n, last)
        assertTrue("checked every 8 MB", checks >= 300 / 8)
        assertTrue("memory stayed flat", after - before < 64L * 1024 * 1024)
    }

    @Test fun stopsWhenAskedAndSaysSo() {
        val sink = Sink(); var checks = 0
        try {
            Attach.copyStream(Endless(100L * 1024 * 1024), sink, -1, { _, _ -> }) { if (++checks == 3) throw IllegalStateException("stopped") }
            assertFalse("should have stopped", true)
        } catch (e: IllegalStateException) { assertEquals("stopped", e.message) }
        assertTrue(sink.got in 16L * 1024 * 1024..17L * 1024 * 1024)
    }

    @Test fun readsBigTextInPartsEndingAtLineBreaksAndSearchesIt() {
        val f = File.createTempFile("big", ".log"); f.deleteOnExit()
        f.bufferedWriter().use { w -> for (i in 0 until 50_000) w.write("line $i: ${if (i == 41_234) "the NEEDLE is here" else "nothing to see"} é\n") }
        var at = 0L; var parts = 0; val seen = StringBuilder()
        while (true) {
            val out = AttachTools.read(f, at, 20_000)
            val body = out.substringAfter("]\n").substringBeforeLast("\n(")
            seen.append(body); parts++
            val next = Regex("next: offset=(\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: break
            assertTrue(body.endsWith("\n")); assertTrue(next > at); at = next
        }
        assertEquals(f.readText(), seen.toString())
        assertTrue(parts > 10)
        val hit = AttachTools.search(f, "needle here", 5)
        assertTrue(hit, hit.contains("line 41235 @offset"))
        val off = Regex("@offset (\\d+)").find(hit)!!.groupValues[1].toLong()
        assertTrue(AttachTools.read(f, off, 100).contains("line 41234: the NEEDLE"))
        assertTrue(AttachTools.search(f, "absent words", 5).startsWith("No line"))
    }

    @Test fun binaryFilesShowReadableTextAndHex() {
        val f = File.createTempFile("blob", ".bin"); f.deleteOnExit()
        f.writeBytes(byteArrayOf(0, 1, 2) + "MAGIC header v3".toByteArray() + ByteArray(100))
        val out = AttachTools.read(f, 0, 64)
        assertTrue(out, out.contains("MAGIC header v3") && out.contains("Hex:") && out.contains("00 01 02 4d"))
    }
}
