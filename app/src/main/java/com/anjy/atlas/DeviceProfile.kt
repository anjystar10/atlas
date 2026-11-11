package com.anjy.atlas

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Measures this phone as it is right now. Cheap (a few system calls), so it is read fresh before each decision. */
object DeviceProfiler {
    /** The graphics chip's name, once the orb has asked it (see OrbGl). */
    @Volatile var graphics: String = ""

    fun read(ctx: Context): DeviceProfile {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mb = 1024L * 1024L
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = try { pm.currentThermalStatus } catch (_: Exception) { -1 }
        val storage = try { StatFs(app.filesDir.path).availableBytes / mb } catch (_: Exception) { -1L }
        val (cores, big) = cores()
        return DeviceProfile(
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
            chip = chip(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
            cores = cores, bigCores = big,
            totalRamMb = mi.totalMem / mb, availRamMb = mi.availMem / mb, lowRamLineMb = mi.threshold / mb, lowMemory = mi.lowMemory,
            memoryClassMb = am.memoryClass,
            freeStorageMb = storage,
            thermal = thermal,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = plugged,
            powerSave = try { pm.isPowerSaveMode } catch (_: Exception) { false },
            network = network(app),
            graphics = graphics
        )
    }

    private fun chip(): String {
        val maker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val named = listOf(maker, soc).filter { !it.isNullOrBlank() && it != Build.UNKNOWN }.joinToString(" ")
        return named.ifBlank { Build.HARDWARE ?: "" }
    }

    /** All cores, and how many of them are the fast ones (by their top speed), when the phone lets apps read that. */
    private fun cores(): Pair<Int, Int> {
        val n = Runtime.getRuntime().availableProcessors()
        return try {
            val top = (0 until n).map { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
            val slowest = top.minOrNull() ?: 0L
            val big = top.count { it > slowest }
            Pair(n, if (big == 0) n else big)
        } catch (_: Exception) { Pair(n, -1) }
    }

    private fun network(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    } catch (_: Exception) { "" }
}

/**
 * The phone's side of the director: remembers how each local model has done here, reads what a model file needs,
 * and lets go of the model when Android says memory is running out. Everything here stays on the phone.
 */
object DeviceGuard {
    private const val TAG = "AtlasDevice"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    // ------------------------------------------------------------- what a model file needs (read once per file)

    /** The model file's numbers if they were read before (no disk work beyond the settings file), else null. */
    fun cachedSpec(ctx: Context, file: File, name: String): ModelSpec? {
        val p = prefs(ctx)
        if (p.getString("spec_stamp", null) != "${file.length()}|${file.lastModified()}") return null
        return try { ModelSpec.from(JSONObject(p.getString("spec", null) ?: return null)).copy(name = name) } catch (_: Exception) { null }
    }

    /** Reads the model file's own numbers, off the main thread; cached until the file changes. */
    fun spec(ctx: Context, file: File, name: String): ModelSpec {
        val stamp = "${file.length()}|${file.lastModified()}"
        val p = prefs(ctx)
        if (p.getString("spec_stamp", null) == stamp) p.getString("spec", null)?.let { try { return ModelSpec.from(JSONObject(it)).copy(name = name) } catch (_: Exception) {} }
        val s = try { file.inputStream().buffered(1 shl 16).use { Gguf.read(it, name, file.length()) } }
            catch (e: Exception) { Log.w(TAG, "couldn't read the model's header", e); ModelSpec(name, file.length() / (1024 * 1024)) }
        p.edit().putString("spec_stamp", stamp).putString("spec", s.json().toString()).apply()
        return s
    }

    // ------------------------------------------------------------- how models have done here

    private fun key(m: ModelSpec) = "${m.name}|${m.fileMb}"
    private fun ledger(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("ledger", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun record(ctx: Context, m: ModelSpec): ModelRecord? = ledger(ctx).optJSONObject(key(m))?.let { ModelRecord.from(it) }
    @Synchronized private fun update(ctx: Context, m: ModelSpec, change: (ModelRecord) -> Unit) {
        val all = ledger(ctx)
        val r = all.optJSONObject(key(m))?.let { ModelRecord.from(it) } ?: ModelRecord()
        change(r); r.lastAt = System.currentTimeMillis()
        // keep the ledger small: the 12 most recent models
        all.put(key(m), r.json())
        if (all.length() > 12) all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("lastAt") ?: 0 }.take(all.length() - 12).forEach { all.remove(it) }
        prefs(ctx).edit().putString("ledger", all.toString()).apply()
    }
    fun loaded(ctx: Context, m: ModelSpec, ms: Long) = update(ctx, m) { it.loads++; it.loadMs = ms }
    fun loadFailed(ctx: Context, m: ModelSpec) = update(ctx, m) { it.failures++ }
    fun died(ctx: Context, m: ModelSpec) = update(ctx, m) { it.died++; it.diedSinceOk++ }
    /** One answer finished: [firstMs] until the first word, [chars] written in [ms]. A token is about four characters. */
    fun answered(ctx: Context, m: ModelSpec, firstMs: Long, chars: Int, ms: Long, availMb: Long) = update(ctx, m) {
        it.diedSinceOk = 0
        if (firstMs > 0) it.firstTokenMs = firstMs
        if (chars >= 40 && ms > 500) { val tps = chars / 4.0 / (ms / 1000.0); it.tokensPerSec = if (it.tokensPerSec > 0) it.tokensPerSec * 0.6 + tps * 0.4 else tps }
        if (availMb > 0) it.okAvailMb = availMb
    }

    /** "Run it anyway" for the offline model, set in Me. */
    fun force(ctx: Context) = prefs(ctx).getBoolean("force", false)
    fun setForce(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("force", on).apply()

    // ------------------------------------------------------------- one heavy thing at a time

    /** What heavy work is under way ("Loading the offline model"), or null: a second heavy job waits its turn. */
    @Volatile var heavy: String? = null; private set
    @Synchronized fun begin(what: String): Boolean { if (heavy != null) return false; heavy = what; return true }
    @Synchronized fun end(what: String) { if (heavy == what) heavy = null }

    // ------------------------------------------------------------- memory pressure

    @Volatile private var installed = false
    /** Android tells every app when memory runs short; Atlas answers by letting the offline model go. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() = release(app, "the phone ran short of memory")
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) release(app, "the phone ran short of memory")
            }
        })
    }

    private fun release(ctx: Context, why: String) {
        if (!ModelManager.ready || ModelManager.busy) return
        Log.w(TAG, "letting the offline model go: $why")
        ModelManager.unload(ctx, "Offline model put away because $why. It loads again when you need it.")
    }

    /** One short line for Atlas's own notes about where it is running. */
    fun line(ctx: Context): String {
        val d = DeviceProfiler.read(ctx)
        val gb = { mb: Long -> if (mb < 0) "?" else "%.0f GB".format(mb / 1024.0) }
        val hold = ModelDirector.holdingBack(d)
        return "This phone: ${d.model.ifBlank { "Android" }}, ${gb(d.totalRamMb)} memory (${gb(d.availRamMb)} free), ${ModelDirector.thermalWord(d.thermal)}, battery ${if (d.batteryPct < 0) "?" else "${d.batteryPct}%"}" +
            (if (hold != null) ". $hold" else "")
    }
}
