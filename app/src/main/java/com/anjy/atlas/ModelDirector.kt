package com.anjy.atlas

import org.json.JSONObject
import java.io.InputStream

/**
 * What this device has, right now. Read fresh before each decision ([DeviceProfiler] fills it on the phone); nothing
 * here is assumed from the phone's model name. -1 (or "") means "couldn't find out".
 */
data class DeviceProfile(
    val model: String = "",            // what the maker calls it, e.g. "OPPO CPH2801"
    val chip: String = "",             // the processor, e.g. "Qualcomm SM6450"
    val abi: String = "",
    val cores: Int = -1,
    val bigCores: Int = -1,            // the fast cores, when the phone says which they are
    val totalRamMb: Long = -1,
    val availRamMb: Long = -1,         // what Android says can be used right now (not the advertised size)
    val lowRamLineMb: Long = 0,        // below this, Android starts closing apps
    val lowMemory: Boolean = false,
    val memoryClassMb: Int = -1,       // the most one ordinary app may hold
    val freeStorageMb: Long = -1,
    val thermal: Int = -1,             // Android's 0 (cool) to 6 (shutting down)
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val powerSave: Boolean = false,
    val network: String = "",          // "wifi", "mobile", "none"
    val graphics: String = ""
)

/** What a GGUF model file says about itself, enough to work out the memory it needs. 0 = not stated. */
data class ModelSpec(
    val name: String, val fileMb: Long,
    val arch: String = "", val layers: Int = 0, val embd: Int = 0, val heads: Int = 0, val headsKv: Int = 0, val ctxTrain: Int = 0
) {
    val known get() = layers > 0 && embd > 0 && heads > 0
    fun json(): JSONObject = JSONObject().put("name", name).put("fileMb", fileMb).put("arch", arch).put("layers", layers).put("embd", embd)
        .put("heads", heads).put("headsKv", headsKv).put("ctxTrain", ctxTrain)
    companion object {
        fun from(o: JSONObject) = ModelSpec(o.optString("name"), o.optLong("fileMb"), o.optString("arch"), o.optInt("layers"), o.optInt("embd"),
            o.optInt("heads"), o.optInt("headsKv"), o.optInt("ctxTrain"))
    }
}

/** How a model has done on this device before: the director trusts what worked and steers off what didn't. */
data class ModelRecord(
    var loads: Int = 0, var failures: Int = 0, var died: Int = 0,
    var diedSinceOk: Int = 0,          // times its process was lost since it last answered
    var loadMs: Long = 0, var firstTokenMs: Long = 0, var tokensPerSec: Double = 0.0,
    var okAvailMb: Long = 0,           // free memory the last time it loaded and answered
    var lastAt: Long = 0
) {
    val worked get() = tokensPerSec > 0 && diedSinceOk == 0
    fun json(): JSONObject = JSONObject().put("loads", loads).put("failures", failures).put("died", died).put("diedSinceOk", diedSinceOk)
        .put("loadMs", loadMs).put("firstTokenMs", firstTokenMs).put("tokensPerSec", tokensPerSec).put("okAvailMb", okAvailMb).put("lastAt", lastAt)
    companion object {
        fun from(o: JSONObject) = ModelRecord(o.optInt("loads"), o.optInt("failures"), o.optInt("died"), o.optInt("diedSinceOk"), o.optLong("loadMs"),
            o.optLong("firstTokenMs"), o.optDouble("tokensPerSec", 0.0), o.optLong("okAvailMb"), o.optLong("lastAt"))
    }
}

/**
 * Decides whether a local model may run on this device right now, so a model too big for the moment is turned away
 * with a reason instead of freezing the phone. Pure arithmetic over a [DeviceProfile]: no Android calls, so the rules
 * are tested off the phone.
 *
 * The memory a model needs at its peak:
 *   the weights (the file's size) + the conversation cache for the context window + the runtime's working space,
 * and it must fit in what is free now, minus the line where Android starts closing apps, minus a reserve for the
 * rest of the phone.
 *
 * What it cannot do yet: the on-device runtime (llama.cpp's Android library) fixes the context window at 8192 tokens
 * and picks its own thread count, so a model that doesn't fit is refused (with the size that would fit) rather than
 * shrunk.
 */
object ModelDirector {
    /** The on-device runtime's fixed context window, in tokens. */
    const val PHONE_CTX = 8192
    /** Working space the runtime needs beside the weights and the cache (batch buffers, the graph, the process itself). */
    const val WORKSPACE_MB = 400L
    /** Thermal status from which a heavy load is not started (Android's THERMAL_STATUS_SEVERE). */
    const val TOO_HOT = 3
    const val LOW_BATTERY = 10
    /** With no numbers from the file: the cache is taken as this share of the weights at the full window. */
    private const val CACHE_SHARE = 0.45

    enum class Fit { FITS, RISKY, NO }

    class Verdict(
        val fit: Fit, val allowed: Boolean, val reason: String,
        val peakMb: Long, val budgetMb: Long,
        /** The largest model file that would fit right now, for "try a smaller one" (0 if nothing would). */
        val roomForFileMb: Long
    )

    /** The conversation cache: every layer keeps a key and a value per token, 16-bit numbers. */
    fun cacheMb(m: ModelSpec, ctx: Int = PHONE_CTX): Long {
        if (!m.known) return (m.fileMb * CACHE_SHARE * ctx / PHONE_CTX).toLong().coerceAtLeast(150)
        val perHead = m.embd / m.heads
        val kv = perHead.toLong() * (if (m.headsKv > 0) m.headsKv else m.heads)
        return m.layers.toLong() * ctx * kv * 2 /* key and value */ * 2 /* bytes each */ / (1024 * 1024)
    }

    fun peakMb(m: ModelSpec, ctx: Int = PHONE_CTX): Long = m.fileMb + cacheMb(m, ctx) + WORKSPACE_MB

    /** Kept free for the rest of the phone: half a gigabyte, or 6% of its memory if that is more. */
    fun reserveMb(d: DeviceProfile): Long = maxOf(500L, d.totalRamMb * 6 / 100)

    /** What a local model may take right now. */
    fun budgetMb(d: DeviceProfile): Long = (d.availRamMb - d.lowRamLineMb - reserveMb(d)).coerceAtLeast(0)

    /**
     * May [m] be loaded now? [record] is how it has done here before; [force] is the user's "run it anyway".
     * Heat is never overridden: a hot phone gets no heavy load.
     */
    fun decide(d: DeviceProfile, m: ModelSpec, record: ModelRecord? = null, force: Boolean = false): Verdict {
        val peak = peakMb(m)
        val budget = budgetMb(d)
        // the biggest file whose own peak fits the budget (weights + cache as a share + working space)
        val room = if (m.known) (budget - cacheMb(m) - WORKSPACE_MB).coerceAtLeast(0) else (((budget - WORKSPACE_MB) / (1 + CACHE_SHARE)).toLong()).coerceAtLeast(0)
        fun v(fit: Fit, ok: Boolean, why: String) = Verdict(fit, ok, why, peak, budget, room)
        val gb = { mb: Long -> "%.1f GB".format(mb / 1024.0) }

        if (d.thermal >= TOO_HOT) return v(Fit.NO, false, "The phone is hot, so I'm not starting the offline model until it cools down.")
        if (d.availRamMb < 0 || d.totalRamMb < 0) return v(Fit.RISKY, true, "I couldn't read this phone's memory, so I can't check the fit.")
        if (d.lowMemory) return v(Fit.NO, false, "The phone is short of memory right now. Close a few apps and try again.")

        val crashed = record != null && record.diedSinceOk >= 2
        val hardLimit = d.availRamMb - d.lowRamLineMb
        val fit = when {
            peak <= budget -> Fit.FITS
            peak <= hardLimit -> Fit.RISKY
            else -> Fit.NO
        }
        val needs = "${m.name.ifBlank { "This model" }} needs about ${gb(peak)} of memory and ${gb(budget)} is safely free right now"
        val smaller = if (room >= 300) " A model file up to about ${gb(room)} would fit." else ""
        val provenHere = record != null && record.worked && record.okAvailMb > 0 && d.availRamMb >= record.okAvailMb * 8 / 10

        if (crashed && !force) return v(if (fit == Fit.FITS) Fit.RISKY else fit, false,
            "${m.name.ifBlank { "This model" }} has stopped twice on this phone (it likely ran out of memory). Try a smaller model, or free some memory and load it yourself in Me.$smaller")
        if (d.powerSave && !force) return v(fit, false, "Battery Saver is on, so I'm not running the offline model. Turn it off, or load the model yourself in Me.")
        if (d.batteryPct in 0 until LOW_BATTERY && !d.charging && !force) return v(fit, false, "The battery is at ${d.batteryPct}%, so I'm not running the offline model until it's charging.")
        return when (fit) {
            Fit.FITS -> v(fit, true, "Fits: needs about ${gb(peak)}, ${gb(budget)} safely free.")
            Fit.RISKY -> when {
                provenHere -> v(fit, true, "Tight, but it has run here before with this much free.")
                force -> v(fit, true, "Tight: $needs. Running it because you asked.")
                else -> v(fit, false, "$needs. Close a few apps and try again, or load it anyway in Me.$smaller")
            }
            Fit.NO -> if (provenHere) v(Fit.RISKY, true, "Tight, but it has run here before with this much free.")
                else v(fit, false, "$needs, so it would be shut down part-way. Close some apps or use a smaller model.$smaller")
        }
    }

    /** How many bots may work at once: one on a strained phone, two otherwise. */
    fun botSlots(d: DeviceProfile): Int =
        if (d.lowMemory || (d.availRamMb in 0 until 1500) || d.thermal >= TOO_HOT || d.powerSave || (d.batteryPct in 0 until 15 && !d.charging)) 1 else 2

    /** Why bots are held to one at a time, or null when they aren't. */
    fun holdingBack(d: DeviceProfile): String? = when {
        d.thermal >= TOO_HOT -> "The phone is hot: one bot at a time, and no offline model until it cools."
        d.lowMemory || (d.availRamMb in 0 until 1500) -> "Memory is short: one bot at a time."
        d.powerSave -> "Battery Saver is on: one bot at a time, and the offline model waits."
        d.batteryPct in 0 until 15 && !d.charging -> "Battery is low: one bot at a time."
        else -> null
    }

    fun thermalWord(t: Int) = when (t) { -1 -> "unknown"; 0 -> "cool"; 1 -> "slightly warm"; 2 -> "warm"; 3 -> "hot"; 4 -> "very hot"; else -> "dangerously hot" }
}

/**
 * Reads the few facts the director needs from the start of a GGUF file (its layers, widths and attention heads).
 * Stops as soon as it has them; a file it can't follow just yields what it found so far.
 */
object Gguf {
    private class End : Exception()

    fun read(input: InputStream, name: String, fileBytes: Long): ModelSpec {
        val found = HashMap<String, Long>()
        var arch = ""
        try {
            val s = Reader(input)
            if (s.u32() != 0x46554747L) return ModelSpec(name, fileBytes / (1024 * 1024))     // "GGUF"
            s.u32()                       // version
            s.u64()                       // tensors
            val count = s.u64().coerceAtMost(100_000)
            val want = listOf("block_count", "embedding_length", "attention.head_count", "attention.head_count_kv", "context_length")
            var i = 0L
            while (i++ < count) {
                val key = s.str()
                val type = s.u32().toInt()
                if (key == "general.architecture" && type == 8) { arch = s.str(); continue }
                val short = if (arch.isNotEmpty() && key.startsWith("$arch.")) key.substring(arch.length + 1) else null
                if (short != null && short in want) {
                    val n = s.number(type)
                    if (n != null) found[short] = n
                } else s.skip(type)
                if (arch.isNotEmpty() && want.all { it in found }) break
            }
        } catch (_: End) {} catch (_: java.io.IOException) {} catch (_: IllegalStateException) {}
        return ModelSpec(name, fileBytes / (1024 * 1024), arch, (found["block_count"] ?: 0).toInt(), (found["embedding_length"] ?: 0).toInt(),
            (found["attention.head_count"] ?: 0).toInt(), (found["attention.head_count_kv"] ?: 0).toInt(), (found["context_length"] ?: 0).toInt())
    }

    private class Reader(private val ins: InputStream) {
        private val b = ByteArray(8)
        private fun fill(n: Int) { var got = 0; while (got < n) { val r = ins.read(b, got, n - got); if (r < 0) throw End(); got += r } }
        fun u32(): Long { fill(4); return (b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) or ((b[2].toLong() and 255) shl 16) or ((b[3].toLong() and 255) shl 24) }
        fun u64(): Long { fill(8); var v = 0L; for (k in 7 downTo 0) v = (v shl 8) or (b[k].toLong() and 255); return v }
        fun str(): String {
            val n = u64(); if (n < 0 || n > 1_000_000) throw IllegalStateException("string")
            val buf = ByteArray(n.toInt()); var got = 0
            while (got < buf.size) { val r = ins.read(buf, got, buf.size - got); if (r < 0) throw End(); got += r }
            return String(buf, Charsets.UTF_8)
        }
        private fun drop(n: Long) { var left = n; while (left > 0) { val r = ins.skip(left); if (r <= 0) { if (ins.read() < 0) throw End(); left-- } else left -= r } }
        private fun size(type: Int): Int = when (type) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> -1 }
        /** A whole number of any width; for a list (some models give heads per layer) the first entry. Null if it isn't a number. */
        fun number(type: Int): Long? = when (type) {
            0, 1, 7 -> { fill(1); b[0].toLong() and 255 }
            2, 3 -> { fill(2); (b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) }
            4, 5 -> u32()
            10, 11 -> u64()
            9 -> {
                val t = u32().toInt(); val n = u64()
                if (n <= 0 || size(t) < 0) { skipArray(t, n); null }
                else { val first = number(t); drop((n - 1) * size(t)); first }
            }
            else -> { skip(type); null }
        }
        private fun skipArray(t: Int, n: Long) {
            if (n < 0 || n > 50_000_000) throw IllegalStateException("array")
            val sz = size(t)
            if (sz > 0) drop(n * sz)
            else if (t == 8) { var k = 0L; while (k++ < n) { val len = u64(); if (len < 0 || len > 10_000_000) throw IllegalStateException("string"); drop(len) } }
            else throw IllegalStateException("nested")
        }
        fun skip(type: Int) {
            val sz = size(type)
            when {
                sz > 0 -> drop(sz.toLong())
                type == 8 -> { val len = u64(); if (len < 0 || len > 100_000_000) throw IllegalStateException("string"); drop(len) }
                type == 9 -> { val t = u32().toInt(); skipArray(t, u64()) }
                else -> throw IllegalStateException("type $type")
            }
        }
    }
}
