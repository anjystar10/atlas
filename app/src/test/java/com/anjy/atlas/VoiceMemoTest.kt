package com.anjy.atlas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMemoTest {
    @Test fun aWavFilesSoundIsFoundBehindItsHeader() {
        val pcm = ByteArray(10) { it.toByte() }
        // a plain WAV header written by hand (Voice itself needs a phone to load)
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(22050); h.putInt(22050 * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        val (rate, data) = Speech.wavData(h.array() + pcm)
        assertEquals(22050, rate)
        assertArrayEquals(pcm, data)
    }

    @Test fun rawSoundIsTakenAsIs() {
        val raw = byteArrayOf(1, 2, 3, 4)
        assertEquals(24000 to raw.toList(), Speech.wavData(raw).let { it.first to it.second.toList() })
    }

    @Test fun theMemoLeavesOutSourcesAndStopsAtASentence() {
        val report = "## Best kettles\n\nThe Russell is the best value. It boils fast.\n\n## Sources\n- https://example.com/a"
        val said = Speech.memoText(report)
        assertTrue(said.contains("Russell is the best value"))
        assertFalse(said.contains("example.com"))
        val long = (1..80).joinToString(" ") { "Sentence number $it is here." }
        val cut = Speech.memoText(long, max = 300)
        assertTrue(cut.endsWith("The full report is in the chat."))
        assertTrue(cut.length < 360)
    }
}
