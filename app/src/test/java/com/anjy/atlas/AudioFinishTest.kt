package com.anjy.atlas

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioFinishTest {
    private val fs = 24000

    private fun tone(vararg hz: Double, amp: Double = 0.2, secs: Double = 1.0, dc: Double = 0.0) =
        DoubleArray((secs * fs).toInt()) { i -> dc + hz.sumOf { amp * sin(2 * PI * it * i / fs) } }

    /** A rough stand-in for speech: a 120 Hz voice with harmonics, in syllables (on 180 ms, off 70 ms). */
    private fun speechLike(secs: Double = 2.0) = DoubleArray((secs * fs).toInt()) { i ->
        val t = i.toDouble() / fs
        val on = (t % 0.25) < 0.18
        if (!on) 0.0 else (1..30).sumOf { h -> 0.25 / h * sin(2 * PI * 120 * h * t) } * (0.6 + 0.4 * sin(2 * PI * 3 * t))
    }

    /** Strength of [hz] in [x] (Goertzel), over the middle of the signal. */
    private fun level(x: DoubleArray, hz: Double): Double {
        val from = x.size / 4; val to = x.size * 3 / 4
        val w = 2 * PI * hz / fs; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in from until to) { val s = x[i] + c * s1 - s2; s2 = s1; s1 = s }
        return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (to - from)
    }
    private fun rms(x: DoubleArray, len: Int = x.size) = sqrt((0 until len).sumOf { x[it] * x[it] } / len)
    private fun db(r: Double) = 20 * log10(r)

    @Test fun silenceStaysSilent() {
        val y = AudioFinish.process(DoubleArray(fs), fs)
        assertTrue(y.all { it == 0.0 })
        assertEquals(fs + (AudioFinish.TAIL_S * fs).toInt(), y.size)
    }

    @Test fun presenceIsLiftedAgainstTheMids() {
        val x = tone(1000.0, 3200.0, amp = 0.05)
        val y = AudioFinish.process(x, fs)
        val lift = db(level(y, 3200.0) / level(y, 1000.0)) - db(level(x, 3200.0) / level(x, 1000.0))
        assertTrue("3.2 kHz lifted by $lift dB", lift in 1.5..4.0)
    }

    @Test fun rumbleAndOffsetGoButTheVoiceStays() {
        val x = tone(40.0, 1000.0, amp = 0.1, dc = 0.1)
        val y = AudioFinish.process(x, fs)
        val hum = db(level(y, 40.0) / level(y, 1000.0)) - db(level(x, 40.0) / level(x, 1000.0))
        assertTrue("40 Hz cut by ${-hum} dB", hum < -6)
        val mean = y.copyOfRange(fs / 2, fs).average()
        assertTrue("offset left: $mean", abs(mean) < 0.005)
    }

    @Test fun sameLoudnessAndNeverClips() {
        for (gain in listOf(0.2, 1.0, 3.0)) {
            val x = speechLike().map { (it * gain).coerceIn(-1.0, 1.0) }.toDoubleArray()
            val y = AudioFinish.process(x, fs)
            assertTrue("peak ${y.maxOf { abs(it) }}", y.all { abs(it) <= 0.89 + 1e-9 })
            // a normal level comes out as loud as it went in; a clipped, too-hot one is brought down, never up
            val change = db(rms(y, x.size) / rms(x))
            if (gain <= 1.0) assertTrue("loudness changed by $change dB at gain $gain", abs(change) < 1.5)
            else assertTrue("hot input changed by $change dB", change < 0.5)
        }
    }

    @Test fun theWordsStayOnTop() {
        // the effects are an accent: what's added after the words stop (the room's tail) is far below the words
        val x = speechLike(1.0).copyOf(fs) // ends with a syllable
        val y = AudioFinish.process(x, fs)
        val tail = y.copyOfRange(x.size + (0.05 * fs).toInt(), y.size)
        assertTrue("tail ${db(rms(tail) / rms(y, x.size))} dB", db(rms(tail) / rms(y, x.size)) < -25)
        // the added effects (sheen, chorus, room) are an accent on the plainly filtered voice, not a second voice
        val plain = AudioFinish.process(x, fs, AudioFinish.Amount(exciter = 0.0, chorus = 0.0, room = 0.0))
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in x.indices) { xy += plain[i] * y[i]; xx += plain[i] * plain[i]; yy += y[i] * y[i] }
        val r = xy / sqrt(xx * yy)
        assertTrue("correlation with the plain voice $r", r > 0.95)
        // the syllables keep their shape: the 10 ms loudness contour follows the plain voice's (compression is
        // shared, so this measures only the smear the room and chorus add)
        val f = fs / 100
        fun contour(v: DoubleArray) = DoubleArray(x.size / f) { k -> rms(v.copyOfRange(k * f, k * f + f)) }
        val cx = contour(plain); val cy = contour(y)
        val mx = cx.average(); val my = cy.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (k in cx.indices) { sxy += (cx[k] - mx) * (cy[k] - my); sxx += (cx[k] - mx) * (cx[k] - mx); syy += (cy[k] - my) * (cy[k] - my) }
        val rc = sxy / sqrt(sxx * syy)
        assertTrue("contour correlation $rc", rc > 0.97)
        // and the gaps between syllables stay clear: the room doesn't fill them (the last 40 ms of each 70 ms gap)
        val gaps = cy.indices.filter { k -> val t = (k * 0.01) % 0.25; t >= 0.21 && t < 0.25 }
        val words = cy.indices.filter { k -> val t = (k * 0.01) % 0.25; t >= 0.02 && t < 0.16 }
        val gapDb = db(sqrt(gaps.sumOf { cy[it] * cy[it] } / gaps.size) / sqrt(words.sumOf { cy[it] * cy[it] } / words.size))
        assertTrue("gaps at $gapDb dB", gapDb < -20)
    }

    @Test fun pcmRoundTrip() {
        val x = speechLike(0.5)
        val pcm = ByteArray(x.size * 2)
        x.forEachIndexed { i, v -> val s = (v * 32767).toInt(); pcm[2 * i] = (s and 0xff).toByte(); pcm[2 * i + 1] = (s shr 8).toByte() }
        val out = AudioFinish.apply(pcm, fs)
        assertEquals(pcm.size + 2 * (AudioFinish.TAIL_S * fs).toInt(), out.size)
        assertFalse(out.all { it == 0.toByte() })
    }
}
