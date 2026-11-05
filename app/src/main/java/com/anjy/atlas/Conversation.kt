package com.anjy.atlas

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.AtomicFile
import android.util.Log
import com.anjy.atlas.core.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one conversation with Atlas, shared by the app, the "Hey Atlas" panel and the floating creature.
 * Online it thinks with Gemini and can use the phone; offline it falls back to fixed commands and the local model.
 * All of its state is read and changed on the main thread.
 */
object Conversation {
    private const val TAG = "AtlasChat"
    private const val MAX_STEPS = 40
    private const val KEEP_MESSAGES = 120
    private const val KEEP_TURNS = 24

    class Msg(val fromUser: Boolean, var text: String, var source: String, val at: Long, val steps: MutableList<String> = mutableListOf())

    val messages = mutableListOf<Msg>()
    var busy = false; private set
    /** What Atlas is doing right now, in a few words ("Tapped “Send”"), while it works. */
    var status: String? = null; private set
    /** True while Atlas is working in another app: the panel steps aside so the screen is free. */
    var usingPhone = false; private set

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { if (l !in listeners) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { listeners.toList().forEach { try { it() } catch (e: Exception) { Log.w(TAG, "listener", e) } } }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    /** Which request is current: a stopped one finishing late must not touch the state of the next. */
    private var gen = 0
    /** Earlier turns as Gemini sees them (words only; tool calls of finished turns are folded into a short note). */
    private val history = mutableListOf<JSONObject>()
    private var loaded = false

    // ---------------------------------------------------------------- settings

    fun key(ctx: Context): String? = Store.getString(ctx, "gemini_key")?.trim()?.takeIf { it.isNotEmpty() }
    fun model(ctx: Context): String = Store.getString(ctx, "gemini_model")?.trim()?.takeIf { it.isNotEmpty() } ?: Gemini.DEFAULT_MODEL
    fun online(ctx: Context) = key(ctx) != null
    fun endpoint(ctx: Context) = Gemini.endpoint(Store.getString(ctx, "gemini_endpoint"))

    // ---------------------------------------------------------------- saving the chat

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "chat.json"))

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.baseFile.exists()) return
            val a = JSONArray(String(f.readFully(), Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val steps = o.optJSONArray("steps")?.let { s -> MutableList(s.length()) { s.getString(it) } } ?: mutableListOf()
                messages.add(Msg(o.getBoolean("user"), o.getString("text"), o.optString("source"), o.optLong("at"), steps))
            }
            // the model picks up from the last few exchanges
            messages.takeLast(KEEP_TURNS * 2).forEach { m ->
                if (m.source == "error") return@forEach
                history.add(if (m.fromUser) Gemini.userText(m.text) else Gemini.modelText(m.text + stepNote(m.steps)))
            }
            fixHistory()
        } catch (e: Exception) { Log.w(TAG, "chat unreadable", e) }
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        messages.takeLast(KEEP_MESSAGES).forEach { m ->
            a.put(JSONObject().put("user", m.fromUser).put("text", m.text).put("source", m.source).put("at", m.at).put("steps", JSONArray(m.steps)))
        }
        val bytes = a.toString().toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            val f = file(ctx)
            synchronized(this@Conversation) {
                val out = f.startWrite()
                try { out.write(bytes); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
            }
        }
    }

    fun clear(ctx: Context) {
        stop()
        messages.clear(); history.clear(); save(ctx); changed()
    }

    // ---------------------------------------------------------------- talking

    fun stop() {
        job?.cancel(); job = null
        gen++
        if (busy) { busy = false; status = null; usingPhone = false; changed() }
    }

    /** Send [text] as the user. [spoken]: it came by voice, so the answer is read out too. */
    fun send(ctx: Context, text: String, spoken: Boolean = false) {
        val app = ctx.applicationContext
        load(app)
        val q = text.trim()
        if (q.isEmpty()) return
        if (busy) stop()
        messages.add(Msg(true, q, "You", System.currentTimeMillis()))
        val reply = Msg(false, "", "Atlas", System.currentTimeMillis())
        messages.add(reply)
        busy = true; status = "Thinking"; usingPhone = false
        val mine = ++gen
        changed()
        job = scope.launch {
            try {
                answer(app, q, spoken, reply)
            } catch (e: kotlinx.coroutines.CancellationException) {
                reply.text = reply.text.ifBlank { "Stopped." }
                if (reply.source == "Atlas") reply.source = "Stopped"
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "answer failed", e)
                reply.text = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"; reply.source = "error"
            } finally {
                if (mine == gen) {
                    if (messages.size > KEEP_MESSAGES * 2) messages.subList(0, messages.size - KEEP_MESSAGES).clear()
                    busy = false; status = null; usingPhone = false; job = null
                }
                save(app)
                changed()
            }
            if (reply.source != "error" && (spoken || Store.read(app) { it.settings.speakReplies })) speak(app, reply.text)
        }
    }

    private suspend fun answer(ctx: Context, q: String, spoken: Boolean, reply: Msg) {
        val key = key(ctx)
        if (key != null) {
            try {
                cloud(ctx, key, q, spoken, reply)
                return
            } catch (f: Gemini.Failure) {
                if (!f.offline) { reply.text = f.message ?: "Gemini failed."; reply.source = "error"; return }
                Log.i(TAG, "offline: ${f.message}")
                reply.steps.add("${f.message} Answering offline.")
            }
        }
        offline(ctx, q, reply)
    }

    // ---------------------------------------------------------------- online: Gemini, with tools

    private val stampFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    fun systemPrompt(ctx: Context, spoken: Boolean): String {
        val name = Store.read(ctx) { it.settings.name }.ifBlank { "the user" }
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(Engine.zone)
        val phone = if (PhoneControl.on()) "on" else "OFF (the user must turn it on in Atlas → Me → Phone control before you can use apps)"
        return """
You are Atlas, $name's personal AI assistant. You live on their Android phone and you can use it the way a person would: open apps, read the screen, tap, type, scroll and press keys. You also keep their reminders and the things they want remembered in Atlas's Log.

How you talk:
- Like a sharp, warm friend. Natural and relaxed, short by default, longer when they want detail.
- Read their meaning, not just their words. They often write casually with slang and typos ("ik", "ngl", "bro", "tryna"); understand it and match their energy. A compliment is a compliment: take it warmly.
- Never curt, defensive or preachy. Never say "what's your issue".
- You can help with anything: questions, advice, ideas, writing, maths, plans, explaining things.
- Be honest. Only say you did something if a tool result shows it worked. If you don't know, say so.
- Plain text only: no markdown, no asterisks, no headings.${if (spoken) "\n- This message came by voice and your answer will be read aloud: one to three short spoken sentences, no lists." else ""}

The Log (only what's worth keeping):
- set_reminder when they want reminding or have something to do at a time ("remind me to pick up my sister next Thursday"). Work out the exact date from today's date. If they gave a day but no time, ask what time unless one is obvious from context.
- remember for lasting facts or preferences they want kept. update_reminder / forget to change the Log.
- Don't log ordinary questions, chat or phone actions.

Using the phone:
- To do something in an app: open_app, then work from the numbered elements on the screen. Every action returns the new screen; check it to see what happened. Numbers change after each action, so only use numbers from the latest screen.
- If something isn't visible, scroll or look for another way in. If an approach fails twice, try a different one; after a few tries, stop and say exactly what blocked you.
- Before anything that sends a message, posts, buys, deletes, or changes an account setting, say exactly what you're about to do and wait for their go-ahead, unless they already asked for that exact action.
- Never type passwords, payment details or verification codes unless the user gives them to you in this chat for that purpose.
- When another app is writing an answer (like ChatGPT), wait and read the screen until it has finished, then tell the user what it said.
- When you're done, say what happened in a sentence or two.
- Phone control is $phone.

Now: ${now.format(stampFmt)} (${Engine.zone.id}).
${Tools.logContext(ctx)}
""".trim()
    }

    private suspend fun cloud(ctx: Context, key: String, q: String, spoken: Boolean, reply: Msg) {
        val model = model(ctx)
        reply.source = "Gemini"
        val contents = ArrayList(history)
        contents.add(Gemini.userText(q))
        var final: String? = null
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            val system = withContext(Dispatchers.IO) { systemPrompt(ctx, spoken) }
            val req = Gemini.request(system, contents, Tools.declarations, model)
            val turn = withContext(Dispatchers.IO) { Gemini.call(key, model, req, endpoint(ctx)) }
            currentCoroutineContext().ensureActive()
            contents.add(turn.content)
            if (turn.calls.isEmpty()) { final = turn.text; break }
            if (turn.text.isNotBlank()) { reply.text = turn.text; changed() }
            val results = mutableListOf<Pair<Gemini.Call, String>>()
            for (c in turn.calls) {
                status = label(c); changed()
                val r = Tools.run(ctx, c)
                if (r.phone && !usingPhone) usingPhone = true
                r.step?.let { reply.steps.add(it) }
                results.add(c to r.out)
                changed()
            }
            contents.add(Gemini.toolResults(results))
        }
        val text = final ?: "I stopped after $MAX_STEPS steps without finishing. Tell me how you'd like me to continue."
        reply.text = text
        // keep the exchange for next time as plain words, with a note of what was done
        history.add(Gemini.userText(q))
        history.add(Gemini.modelText(text + stepNote(reply.steps)))
        fixHistory()
    }

    private fun stepNote(steps: List<String>) = if (steps.isEmpty()) "" else "\n(What I did: ${steps.joinToString("; ")})"

    /** At most [KEEP_TURNS] exchanges, starting with the user. */
    private fun fixHistory() {
        while (history.size > KEEP_TURNS * 2) history.removeAt(0)
        while (history.isNotEmpty() && history[0].optString("role") != "user") history.removeAt(0)
    }

    private fun label(c: Gemini.Call): String = when (c.name) {
        "open_app" -> "Opening ${c.args.optString("name")}"
        "read_screen" -> "Looking at the screen"
        "tap" -> "Tapping"
        "long_press" -> "Pressing"
        "type_text" -> "Typing"
        "scroll" -> "Scrolling"
        "press_key" -> "Pressing ${c.args.optString("key")}"
        "wait" -> "Waiting"
        "set_reminder" -> "Logging a reminder"
        "remember" -> "Saving to memory"
        "open_link" -> "Opening a link"
        else -> "Working"
    }

    // ---------------------------------------------------------------- offline: fixed commands, then the local model

    private suspend fun offline(ctx: Context, q: String, reply: Msg) {
        status = "Answering offline"; changed()
        val fixed = withContext(Dispatchers.IO) { Brain.route(ctx, q) }
        if (fixed != null) { reply.text = fixed.text; reply.source = "Atlas"; return }
        if (ModelManager.modelName(ctx) == null) {
            reply.source = "Atlas"
            reply.text = if (online(ctx)) "I can't reach Gemini and there's no offline model on this phone, so I can only do fixed commands right now (say “help”)."
            else "Add your Gemini key in Me so I can think properly. Until then I can only do fixed commands (say “help”)."
            return
        }
        if (!ModelManager.ready) { status = "Loading the offline model"; changed(); ModelManager.load(ctx) }
        reply.source = "Offline model"
        val r = Brain.ask(ctx, q) { partial -> reply.text = partial; changed() }
        reply.text = r.text
    }

    // ---------------------------------------------------------------- speaking

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun speak(ctx: Context, s: String) {
        val text = s.replace(Regex("[*_#`]"), "").trim()
        if (text.isEmpty()) return
        val t = tts
        if (t == null) {
            pendingSpeech = text
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "atlas") }
                pendingSpeech = null
            }
        } else if (ttsReady) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "atlas")
        else pendingSpeech = text
    }

    fun hush() { try { tts?.stop() } catch (_: Exception) {} }
}
