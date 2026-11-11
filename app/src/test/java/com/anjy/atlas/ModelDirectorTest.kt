package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ModelDirectorTest {
    // the user's phone (OPPO Reno15 F, 12 GB): Android reports a little under the label, and about 5 GB free with apps open
    private val reno = DeviceProfile(model = "OPPO CPH2801", chip = "Qualcomm SM6450", cores = 8, bigCores = 4,
        totalRamMb = 11_500, availRamMb = 5_000, lowRamLineMb = 216, thermal = 0, batteryPct = 70)
    // a small phone: 4 GB, 1.2 GB free
    private val small = DeviceProfile(totalRamMb = 3_700, availRamMb = 1_200, lowRamLineMb = 150, thermal = 0, batteryPct = 70)

    // Qwen 2.5 3B (Q4): 36 layers, 2048 wide, 16 heads, 2 key/value heads, ~2 GB file
    private val qwen3b = ModelSpec("qwen2.5-3b-q4.gguf", 2_000, "qwen2", 36, 2048, 16, 2, 32768)
    // Llama 3.1 8B (Q4): 32 layers, 4096 wide, 32 heads, 8 key/value heads, ~4.7 GB file
    private val llama8b = ModelSpec("llama-3.1-8b-q4.gguf", 4_700, "llama", 32, 4096, 32, 8, 131072)

    @Test fun cacheFollowsTheModelsOwnLayout() {
        // 36 layers × 8192 tokens × (128 per head × 2 heads) × key+value × 2 bytes = 288 MB
        assertEquals(288L, ModelDirector.cacheMb(qwen3b))
        // 32 × 8192 × (128 × 8) × 2 × 2 = 1024 MB
        assertEquals(1024L, ModelDirector.cacheMb(llama8b))
        assertEquals(2_000L + 288 + ModelDirector.WORKSPACE_MB, ModelDirector.peakMb(qwen3b))
    }

    @Test fun unknownLayoutIsEstimatedNotZero() {
        val raw = ModelSpec("mystery.gguf", 2_000)
        assertTrue(ModelDirector.cacheMb(raw) >= 150)
        assertTrue(ModelDirector.peakMb(raw) > 2_000 + ModelDirector.WORKSPACE_MB)
    }

    @Test fun smallModelRunsOnTheReno() {
        val v = ModelDirector.decide(reno, qwen3b)
        assertTrue(v.reason, v.allowed)
        assertEquals(ModelDirector.Fit.FITS, v.fit)
    }

    @Test fun bigModelIsTurnedAwayWithASizeThatWouldFit() {
        val v = ModelDirector.decide(reno, llama8b)
        assertFalse(v.allowed)
        assertEquals(ModelDirector.Fit.NO, v.fit)
        assertTrue(v.roomForFileMb in 1_000..4_000)
        assertTrue(v.reason, v.reason.contains("would fit"))
        // "run it anyway" doesn't override a model that can't fit at all
        assertFalse(ModelDirector.decide(reno, llama8b, force = true).allowed)
    }

    @Test fun tightFitNeedsProofOrPermission() {
        // free memory between the safe budget and the hard line: risky
        val tight = reno.copy(availRamMb = 3_100)
        val v = ModelDirector.decide(tight, qwen3b)
        assertEquals(ModelDirector.Fit.RISKY, v.fit)
        assertFalse(v.allowed)
        assertTrue(ModelDirector.decide(tight, qwen3b, force = true).allowed)
        // it has run here before with about this much free: allowed
        val proven = ModelRecord(loads = 3, tokensPerSec = 9.0, okAvailMb = 3_200)
        assertTrue(ModelDirector.decide(tight, qwen3b, proven).allowed)
    }

    @Test fun heatAlwaysWins() {
        val hot = reno.copy(thermal = 3)
        assertFalse(ModelDirector.decide(hot, qwen3b).allowed)
        assertFalse(ModelDirector.decide(hot, qwen3b, force = true).allowed)
        assertEquals(1, ModelDirector.botSlots(hot))
    }

    @Test fun batteryAndLowMemoryHoldBack() {
        assertFalse(ModelDirector.decide(reno.copy(powerSave = true), qwen3b).allowed)
        assertTrue(ModelDirector.decide(reno.copy(powerSave = true), qwen3b, force = true).allowed)
        assertFalse(ModelDirector.decide(reno.copy(batteryPct = 5), qwen3b).allowed)
        assertTrue(ModelDirector.decide(reno.copy(batteryPct = 5, charging = true), qwen3b).allowed)
        assertFalse(ModelDirector.decide(reno.copy(lowMemory = true), qwen3b).allowed)
    }

    @Test fun aModelThatKeepsDyingIsHeldBack() {
        val dying = ModelRecord(loads = 2, died = 2, diedSinceOk = 2)
        val v = ModelDirector.decide(reno, qwen3b, dying)
        assertFalse(v.allowed)
        assertTrue(ModelDirector.decide(reno, qwen3b, dying, force = true).allowed)
    }

    @Test fun smallPhoneGetsOneBotAndNoBigModel() {
        assertEquals(1, ModelDirector.botSlots(small))
        assertFalse(ModelDirector.decide(small, qwen3b).allowed)
        assertEquals(2, ModelDirector.botSlots(reno))
        assertEquals(null, ModelDirector.holdingBack(reno))
    }

    @Test fun reloadingCountsTheModelBeingReplacedAsFree() {
        // with the 3B model loaded, Android shows only 2.4 GB free; reloading the same model must still be allowed
        val loaded = reno.copy(availRamMb = 2_400)
        assertFalse(ModelDirector.decide(loaded, qwen3b).allowed)
        assertTrue(ModelDirector.decide(ModelDirector.afterUnloading(loaded, qwen3b), qwen3b).allowed)
        // never more than the phone has
        val after = ModelDirector.afterUnloading(small.copy(availRamMb = 3_600), llama8b)
        assertEquals(small.totalRamMb, after.availRamMb)
    }

    // ------------------------------------------------------------ reading a model file's header

    private fun gguf(arch: String, vararg kv: Pair<String, Any>): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 255).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) o.write(((v shr (8 * i)) and 255).toInt()) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); o.write(b) }
        u32(0x46554747L); u32(3); u64(0); u64((kv.size + 2).toLong())
        str("general.name"); u32(8); str("test model")
        str("general.architecture"); u32(8); str(arch)
        kv.forEach { (k, v) ->
            str(k)
            when (v) {
                is Int -> { u32(4); u32(v.toLong()) }
                is IntArray -> { u32(9); u32(4); u64(v.size.toLong()); v.forEach { u32(it.toLong()) } }
                is Float -> { u32(6); u32(java.lang.Float.floatToIntBits(v).toLong()) }
                else -> error("type")
            }
        }
        return o.toByteArray()
    }

    @Test fun readsTheLayoutFromTheHeader() {
        val bytes = gguf("qwen2", "qwen2.rope.freq_base" to 1_000_000f, "qwen2.block_count" to 36, "qwen2.embedding_length" to 2048,
            "qwen2.attention.head_count" to 16, "qwen2.attention.head_count_kv" to 2, "qwen2.context_length" to 32768)
        val s = Gguf.read(ByteArrayInputStream(bytes), "q", 2_000L * 1024 * 1024)
        assertEquals(ModelSpec("q", 2_000, "qwen2", 36, 2048, 16, 2, 32768), s)
    }

    @Test fun headsGivenPerLayerUseTheFirst() {
        val bytes = gguf("llama", "llama.block_count" to 2, "llama.embedding_length" to 512,
            "llama.attention.head_count" to intArrayOf(8, 8), "llama.attention.head_count_kv" to intArrayOf(2, 2), "llama.context_length" to 4096)
        val s = Gguf.read(ByteArrayInputStream(bytes), "l", 100L * 1024 * 1024)
        assertEquals(8, s.heads); assertEquals(2, s.headsKv); assertEquals(2, s.layers)
    }

    @Test fun aTruncatedOrForeignFileGivesWhatItCan() {
        val bytes = gguf("llama", "llama.block_count" to 32)
        val cut = bytes.copyOf(bytes.size - 2)
        val s = Gguf.read(ByteArrayInputStream(cut), "x", 1024L * 1024 * 10)
        assertEquals(10L, s.fileMb)
        assertFalse(s.known)
        val notGguf = Gguf.read(ByteArrayInputStream("<html>".toByteArray()), "y", 1024L * 1024)
        assertEquals(0, notGguf.layers)
    }
}
