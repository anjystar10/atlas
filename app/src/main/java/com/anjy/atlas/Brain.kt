package com.anjy.atlas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.anjy.atlas.core.AtlasState
import com.anjy.atlas.core.Engine
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

data class Reply(val text: String, val source: String)

/** The local model slot. Any GGUF chat model runs here through llama.cpp, fully on the phone. */
object ModelManager {
    @Volatile var status: String = "No model loaded"; private set
    @Volatile var ready = false; private set
    private var engine: InferenceEngine? = null
    private var nativeOk: Boolean? = null

    fun modelFile(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, "atlas-model.gguf")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("model", Context.MODE_PRIVATE)
    fun modelName(ctx: Context): String? = prefs(ctx).getString("name", null)?.takeIf { modelFile(ctx).exists() }
    fun lastLoadCrashed(ctx: Context) = prefs(ctx).getBoolean("loading", false)

    private fun engine(ctx: Context): InferenceEngine? {
        engine?.let { return it }
        if (nativeOk == null) {
            nativeOk = try { System.loadLibrary("ai-chat"); true } catch (t: Throwable) { status = "Model runtime is missing from this build: ${t.message}"; false }
        }
        if (nativeOk != true) return null
        return try { AiChat.getInferenceEngine(ctx.applicationContext).also { engine = it } }
        catch (t: Throwable) { status = "Model runtime failed to start: ${t.message}"; null }
    }

    /** Copy a .gguf file the user picked into Atlas's private storage. */
    suspend fun import(ctx: Context, uri: Uri, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "model.gguf"; var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        val input = cr.openInputStream(uri) ?: throw IllegalStateException("Couldn't open that file.")
        val dest = modelFile(ctx)
        val tmp = File(dest.parentFile, "incoming.tmp")
        if (size > 0 && dest.parentFile!!.usableSpace < size + 200_000_000L) {
            input.close()
            throw IllegalStateException("Not enough free space: the model needs ${size / 1_000_000} MB.")
        }
        try {
            input.use { ins ->
                val head = ins.readNBytes(4)
                if (head.size != 4 || String(head, Charsets.US_ASCII) != "GGUF") throw IllegalArgumentException("That file isn't a GGUF model.")
                tmp.outputStream().use { out ->
                    out.write(head)
                    val buf = ByteArray(1 shl 20)
                    var done = 4L
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - lastReport >= (8 shl 20)) { lastReport = done; onProgress(done, size) }
                    }
                    onProgress(done, size)
                }
            }
        } catch (e: Throwable) {
            tmp.delete(); throw e
        }
        if (ready) unload()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save the model file.")
        prefs(ctx).edit().putString("name", name).apply()
        name
    }

    suspend fun load(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val f = modelFile(ctx)
        if (!f.exists()) { status = "No model file. Choose one in Me."; return@withContext false }
        val e = engine(ctx) ?: return@withContext false
        prefs(ctx).edit().putBoolean("loading", true).commit()
        try {
            status = "Loading ${modelName(ctx) ?: "model"}…"
            val st = e.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error || it.isModelLoaded }
            if (st.isModelLoaded || st is InferenceEngine.State.Error) e.cleanUp()
            e.loadModel(f.absolutePath)
            e.setSystemPrompt(Brain.systemPrompt(ctx))
            usedChars = 0; stale = false
            ready = true
            status = "${modelName(ctx) ?: "Model"} ready, running on this phone"
            true
        } catch (t: Throwable) {
            ready = false
            status = if ((t.message ?: "").startsWith("Cannot unload")) "The model got stuck. Close Atlas from recent apps and open it again."
            else "Model failed to load: ${t.message ?: t.javaClass.simpleName}"
            try { e.cleanUp() } catch (_: Throwable) {}
            false
        } finally {
            prefs(ctx).edit().putBoolean("loading", false).apply()
        }
    }

    fun unload() {
        val e = engine ?: return
        try { if (e.state.value.isModelLoaded || e.state.value is InferenceEngine.State.Error) e.cleanUp() } catch (_: Throwable) {}
        ready = false
        status = "Model unloaded"
    }

    fun remove(ctx: Context) {
        unload()
        modelFile(ctx).delete()
        prefs(ctx).edit().remove("name").apply()
        status = "No model loaded"
    }

    /**
     * The runtime keeps the whole conversation in an 8192-token window and stops answering for good once it is full,
     * so Atlas starts a fresh conversation well before that (and after memories change, so the model sees them).
     */
    private const val CHAR_BUDGET = 12_000
    @Volatile private var usedChars = 0
    @Volatile private var stale = false
    fun markStale() { stale = true }

    suspend fun generate(ctx: Context, text: String, onToken: (String) -> Unit): String {
        var e = engine ?: throw IllegalStateException("No model loaded.")
        if (stale || usedChars + text.length > CHAR_BUDGET || e.state.value !is InferenceEngine.State.ModelReady) {
            if (!load(ctx)) throw IllegalStateException(status)
            e = engine ?: throw IllegalStateException("No model loaded.")
        }
        val sb = StringBuilder()
        e.sendUserPrompt(text, 320).collect { tok -> sb.append(tok); onToken(sb.toString()) }
        usedChars += text.length + sb.length + 40
        // An empty answer means the runtime refused the prompt; start fresh next time.
        if (sb.isBlank()) stale = true
        return sb.toString()
    }
}

/** Decides how to answer: fixed commands first (always work, no model needed), then the local model. */
object Brain {
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

    fun systemPrompt(ctx: Context): String {
        val (name, mems) = Store.read(ctx) { s -> Pair(s.settings.name, s.memories.takeLast(25).map { it.text }) }
        return buildString {
            append("You are Atlas, a personal assistant that lives on ")
            append(if (name.isNotBlank()) "$name's" else "the user's")
            append(" phone. Be brief, direct and honest. Never claim to have done something you did not do.\n")
            append("When the user asks you to schedule or remind something, to remember something, or to open an app, ")
            append("reply with exactly one line of JSON and nothing else, using one of these forms:\n")
            append("{\"tool\":\"create_task\",\"title\":\"...\",\"day\":\"today|tomorrow|YYYY-MM-DD\",\"time\":\"HH:MM\",\"duration_min\":30}\n")
            append("{\"tool\":\"remember\",\"text\":\"...\"}\n")
            append("{\"tool\":\"open_app\",\"name\":\"...\"}\n")
            append("Otherwise reply in plain text.")
            if (mems.isNotEmpty()) {
                append("\nThings the user asked you to remember:\n")
                mems.forEach { append("- ").append(it).append('\n') }
            }
        }
    }

    /** Phone keyboards type curly quotes ("what’s", "I’m"); the commands are written with straight ones. */
    private fun clean(s: String) = s.replace('\u2019', '\'').replace('\u2018', '\'').trim().trimEnd('.', '!', '?').trim()

    /** Fixed commands. Returns null when the message is not one of them. */
    fun route(ctx: Context, input: String): Reply? {
        val raw = clean(input)
        val low = raw.lowercase()
        val now = System.currentTimeMillis()

        // "remember when…?" is a question, not something to save
        if (!input.trim().endsWith("?")) Regex("^(?:please\\s+)?(?:remember|note|save)(?:\\s+that)?[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val text = m.groupValues[1]
            Store.edit(ctx) { Engine.remember(it, text, now) }
            ModelManager.markStale()
            return Reply("Saved to memory: “$text”", "Atlas")
        }
        if (Regex("^(?:what do you (?:remember|know)(?: about me)?|show (?:my )?(?:memories|notes)|what did i (?:save|tell you)|my (?:memories|notes))$").matches(low)) {
            val mems = Store.read(ctx) { it.memories.takeLast(15).map { m -> m.text } }
            return Reply(if (mems.isEmpty()) "Nothing saved yet. Say “remember …” and I'll keep it." else mems.joinToString("\n") { "• $it" }, "Atlas")
        }
        Regex("^(?:open|launch)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val app = findApp(ctx, m.groupValues[1])
            // "open the door…" isn't an app: let the model answer it when there is one
            if (app != null) return Reply(launchApp(ctx, app), "Atlas")
            if (!ModelManager.ready) return Reply("I couldn't find an app called “${m.groupValues[1]}” on this phone.", "Atlas")
        }
        Regex("^(?:remind me(?: to)?|add (?:a )?task|new task|schedule|to-?do)[:\\s]+(.+)$", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val p = Engine.parseQuick(m.groupValues[1], now)
            if (p.title.isBlank()) return Reply("What should the task be called?", "Atlas")
            val t = Store.edit(ctx) { Engine.create(it, p.title, now, p.startAt, p.durationMin ?: 30) }
            Reminders.tickAndSchedule(ctx)
            return Reply(describeAdded(t.title, t.startAt, t.durationMin), "Atlas")
        }
        if (Regex("^(?:what(?:'?s| is) (?:on )?(?:my )?(?:plan|schedule|agenda|day)(?: today| for today)?|today|my day|agenda|what do i have(?: today)?)$").matches(low)) {
            return Reply(Store.read(ctx) { daySummary(it, now) }, "Atlas")
        }
        Regex("^(?:i'?m |i am |i'?ve |i have )?(?:done|finished)(?: with)?\\s*(.*)$", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val q = m.groupValues[1].trim().lowercase()
            // a long sentence starting with "done" is conversation, not a command
            if (q.split(Regex("\\s+")).size > 6) return null
            val target = Store.read(ctx) { s ->
                val cands = s.tasks.filter { it.status == "awaiting" || it.status == "active" }
                when {
                    q.isEmpty() && cands.size == 1 -> cands[0].id
                    q.isNotEmpty() -> cands.firstOrNull { it.title.lowercase().contains(q) }?.id
                        ?: s.tasks.firstOrNull { it.status == "planned" && it.title.lowercase().contains(q) }?.id
                    else -> null
                }
            } ?: return Reply(if (q.isEmpty()) "Done with which task? Say “done with …” and part of its name." else "I couldn't find an open task matching “$q”.", "Atlas")
            val title = Store.edit(ctx) { s -> Engine.respond(s, target, "done", now).first.title }
            Reminders.tickAndSchedule(ctx)
            AvatarService.celebrate(ctx)
            return Reply("Marked “$title” done at ${Reminders.fmt(now)}.", "Atlas")
        }
        if (Regex("^(?:help|what can you do)$").matches(low)) {
            return Reply(
                "Things that always work, with or without a model:\n" +
                    "• remind me to gym at 6pm for 1h\n• remember my chess rating is 1200\n• what do you remember\n" +
                    "• what's my day\n• done with gym\n• open whatsapp\n" +
                    "Anything else goes to the local model, if one is loaded.", "Atlas"
            )
        }
        return null
    }

    /** Ask the local model. Executes at most one validated tool call from its answer. */
    suspend fun ask(ctx: Context, input: String, onPartial: (String) -> Unit): Reply {
        if (!ModelManager.ready) {
            return Reply(
                "No model is loaded, so I can only handle fixed commands right now (say “help”). " +
                    "Add a GGUF model in Me to talk freely.", "Atlas"
            )
        }
        val now = System.currentTimeMillis()
        val stamp = Instant.ofEpochMilli(now).atZone(Engine.zone).let { it.format(dayFmt) + " " + Reminders.fmt(now) }
        val out = ModelManager.generate(ctx, "[Now: $stamp] $input", onPartial).trim()
        val call = extractToolCall(out)
        if (call != null) {
            val result = if (call.optString("tool") == "open_app") runTool(ctx, call, now)
            else withContext(Dispatchers.IO) { runTool(ctx, call, now) }
            if (result != null) return Reply(result, "Local model")
        }
        return Reply(out.ifBlank { "(no answer)" }, "Local model")
    }

    private fun extractToolCall(text: String): JSONObject? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0
            for (i in start until text.length) {
                if (text[i] == '{') depth++
                if (text[i] == '}') { depth--; if (depth == 0) {
                    try { val o = JSONObject(text.substring(start, i + 1)); if (o.has("tool")) return o } catch (_: Exception) {}
                    break
                } }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun runTool(ctx: Context, o: JSONObject, now: Long): String? = when (o.optString("tool")) {
        "create_task" -> {
            val title = o.optString("title").trim()
            if (title.isEmpty()) null else {
                val at = Engine.resolveDayTime(o.optString("day"), o.optString("time").ifBlank { null }, now)
                val dur = o.optInt("duration_min", 30)
                val t = Store.edit(ctx) { Engine.create(it, title, now, at, dur) }
                Reminders.tickAndSchedule(ctx)
                describeAdded(t.title, t.startAt, t.durationMin)
            }
        }
        "remember" -> o.optString("text").trim().takeIf { it.isNotEmpty() }?.let { txt ->
            Store.edit(ctx) { Engine.remember(it, txt, now) }; ModelManager.markStale(); "Saved to memory: “$txt”"
        }
        "open_app" -> o.optString("name").trim().takeIf { it.isNotEmpty() }?.let { n ->
            findApp(ctx, n)?.let { launchApp(ctx, it) } ?: "I couldn't find an app called “$n” on this phone."
        }
        else -> null
    }

    private fun describeAdded(title: String, at: Long?, dur: Int): String =
        if (at == null) "Added “$title” to Unscheduled. Tell me a time and I'll remind you."
        else "Added “$title” for ${dayLabel(at)} ${Reminders.fmt(at)} ($dur min). I'll remind you when it starts and check in after."

    fun dayLabel(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate()
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(Engine.zone).toLocalDate()
        return when (d) {
            today -> "today"; today.plusDays(1) -> "tomorrow"; today.minusDays(1) -> "yesterday"
            else -> d.format(dayFmt)
        }
    }

    fun daySummary(s: AtlasState, now: Long): String {
        val today = Instant.ofEpochMilli(now).atZone(Engine.zone).toLocalDate()
        fun isToday(ms: Long) = Instant.ofEpochMilli(ms).atZone(Engine.zone).toLocalDate() == today
        val lines = mutableListOf<String>()
        s.tasks.filter { it.status == "awaiting" }.forEach { lines.add("Needs your answer: ${it.title}") }
        s.tasks.filter { it.status == "active" }.forEach { lines.add("Now: ${it.title}") }
        s.tasks.filter { it.status == "planned" && it.startAt != null && isToday(it.startAt!!) }.sortedBy { it.startAt }
            .forEach { lines.add("${Reminders.fmt(it.startAt!!)} ${it.title}") }
        s.tasks.filter { it.status == "blocked" }.forEach { lines.add("Blocked: ${it.title}") }
        val done = s.tasks.count { it.status == "done" && it.completedAt != null && isToday(it.completedAt!!) }
        if (lines.isEmpty()) lines.add("Nothing scheduled for the rest of today.")
        if (done > 0) lines.add("Done today: $done")
        return lines.joinToString("\n")
    }

    /** The launchable app whose name best matches [name], as (label, package). */
    fun findApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.second != ctx.packageName }
        val q = name.trim().lowercase()
        if (q.length < 2) return null
        return apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }
    }

    fun launchApp(ctx: Context, hit: Pair<String, String>): String {
        val pm = ctx.packageManager
        val launch = pm.getLaunchIntentForPackage(hit.second) ?: return "“${hit.first}” can't be opened directly."
        return try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening ${hit.first}."
        } catch (e: Exception) { "Android blocked opening ${hit.first}." }
    }
}
