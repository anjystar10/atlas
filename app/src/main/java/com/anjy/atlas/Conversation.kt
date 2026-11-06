package com.anjy.atlas

import android.content.Context
import android.os.Looper
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

    class Msg(val fromUser: Boolean, var text: String, var source: String, val at: Long, val steps: MutableList<String> = mutableListOf(),
              val media: List<Attach.Item> = emptyList(), val id: String = java.util.UUID.randomUUID().toString())

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
    private val history = mutableListOf<Pair<Boolean, String>>()
    private var loaded = false

    // ---------------------------------------------------------------- settings

    /** The AI service Atlas thinks with (the user's own key), or null when none is set up. */
    fun provider(ctx: Context) = Providers.active(ctx)
    fun online(ctx: Context) = provider(ctx) != null
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
                val user = o.getBoolean("user")
                val media = o.optJSONArray("media")?.let { a2 -> List(a2.length()) { k -> a2.getJSONObject(k).let { Attach.Item(it.getString("path"), it.getString("mime"), it.optString("name")) } } }
                    ?.filter { it.file.exists() } ?: emptyList()
                messages.add(Msg(user, if (user) o.getString("text") else Plain.reply(o.getString("text")), o.optString("source"), o.optLong("at"), steps, media,
                    o.optString("id").ifEmpty { "${o.optLong("at")}-${if (user) "u" else "a"}" }))
            }
            // the model picks up from the last few exchanges
            messages.takeLast(KEEP_TURNS * 2).forEach { m ->
                if (m.source == "error") return@forEach
                history.add(m.fromUser to (if (m.fromUser) m.text else m.text + stepNote(m.steps)))
            }
            fixHistory()
        } catch (e: Exception) { Log.w(TAG, "chat unreadable", e) }
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        messages.takeLast(KEEP_MESSAGES).forEach { m ->
            a.put(JSONObject().put("id", m.id).put("user", m.fromUser).put("text", m.text).put("source", m.source).put("at", m.at).put("steps", JSONArray(m.steps))
                .put("media", JSONArray().apply { m.media.forEach { put(JSONObject().put("path", it.path).put("mime", it.mime).put("name", it.name)) } }))
        }
        val keep = messages.takeLast(KEEP_MESSAGES).flatMap { m -> m.media.map { it.path } }.toSet()
        val bytes = a.toString().toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            val f = file(ctx)
            synchronized(this@Conversation) {
                val out = f.startWrite()
                try { out.write(bytes); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
            }
            try { Attach.prune(ctx, keep) } catch (_: Exception) {}
            if (!busy) try { Sync.syncSoon(ctx) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- the same conversation on the PC (see Sync)

    /** The finished messages, as records for the other device (pictures stay on this one). */
    fun exportForSync(ctx: Context): List<JSONObject> {
        val list = java.util.concurrent.FutureTask { messages.filterIndexed { i, m -> !(busy && i == messages.size - 1) && m.source != "error" }.map { m ->
            JSONObject().put("id", m.id).put("user", m.fromUser).put("text", m.text).put("source", m.source).put("at", m.at).put("steps", JSONArray(m.steps))
                .put("files", m.media.size)
        } }
        if (Looper.myLooper() == Looper.getMainLooper()) list.run() else { mainHandler.post(list) }
        return try { list.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
    }
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /** Messages from the other device: added in time order, changed, or removed (null). Main thread. */
    fun mergeFromSync(ctx: Context, changes: List<Pair<String, JSONObject?>>) {
        load(ctx.applicationContext)
        var touched = false
        changes.forEach { (id, o) ->
            val i = messages.indexOfFirst { it.id == id }
            if (o == null) { if (i >= 0) { messages.removeAt(i); touched = true }; return@forEach }
            val steps = o.optJSONArray("steps")?.let { s -> MutableList(s.length()) { s.getString(it) } } ?: mutableListOf()
            val m = Msg(o.optBoolean("user"), o.optString("text"), o.optString("source"), o.optLong("at"), steps, emptyList(), id)
            if (i >= 0) { messages[i].text = m.text; messages[i].source = m.source; messages[i].steps.clear(); messages[i].steps.addAll(steps) }
            else { val at = messages.indexOfFirst { it.at > m.at }; if (at < 0) messages.add(m) else messages.add(at, m) }
            touched = true
        }
        if (!touched) return
        history.clear()
        messages.takeLast(KEEP_TURNS * 2).forEach { m -> if (m.source != "error") history.add(m.fromUser to (if (m.fromUser) m.text else m.text + stepNote(m.steps))) }
        fixHistory()
        save(ctx.applicationContext); changed()
    }

    /**
     * Atlas Bot finished a job: its report joins the chat as an Atlas message (and so reaches the other device too).
     * While Atlas is answering, it goes in just before the exchange in progress. Main thread.
     */
    fun postFromBot(ctx: Context, text: String) {
        val app = ctx.applicationContext
        load(app)
        val m = Msg(false, Plain.reply(text), "Atlas Bot", System.currentTimeMillis())
        if (busy && messages.size >= 2) messages.add(messages.size - 2, m) else {
            messages.add(m)
            // the model hears about it next time, keeping turns in order (user, then Atlas)
            history.add(true to "(Atlas Bot finished a job you sent it on. Its report follows.)")
            history.add(false to m.text.take(4000))
            fixHistory()
        }
        save(app); changed()
    }

    /** After a sync took things in: screens showing the Log or chat redraw. */
    fun reloadFromSync(ctx: Context) { changed() }

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
    fun send(ctx: Context, text: String, spoken: Boolean = false, onSpoken: (() -> Unit)? = null, media: List<Attach.Item> = emptyList()) {
        val app = ctx.applicationContext
        load(app)
        val q = text.trim().ifEmpty { when { media.isEmpty() -> ""; media.any { it.video } -> "What's in this video?"; media.any { it.isFile } -> "Have a look at this."; else -> "What's in this picture?" } }
        if (q.isEmpty()) return
        if (busy) stop()
        messages.add(Msg(true, q, "You", System.currentTimeMillis(), media = media))
        val reply = Msg(false, "", "Atlas", System.currentTimeMillis())
        messages.add(reply)
        busy = true; status = "Thinking"; usingPhone = false
        val mine = ++gen
        changed()
        job = scope.launch {
            try {
                answer(app, q, spoken, reply, media)
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
            if (spoken || Store.read(app) { it.settings.speakReplies }) speak(app, reply.text, onSpoken) else onSpoken?.invoke()
        }
    }

    private suspend fun answer(ctx: Context, q: String, spoken: Boolean, reply: Msg, media: List<Attach.Item> = emptyList()) {
        val p = provider(ctx)
        if (p != null) {
            try {
                cloud(ctx, p, q, spoken, reply, media)
                return
            } catch (f: Gemini.Failure) {
                if (!f.offline) { reply.text = f.message ?: "${p.name} failed."; reply.source = "error"; return }
                Log.i(TAG, "offline: ${f.message}")
                reply.steps.add("${f.message} Answering offline.")
            }
        }
        if (media.any { !it.textFile }) { reply.text = "I need an AI service to look at pictures, videos and PDFs. Add a key in Me → Brain."; reply.source = "Atlas"; return }
        offline(ctx, q + withContext(Dispatchers.IO) { media.joinToString("") { Attach.inlineText(it) } }, reply)
    }

    // ---------------------------------------------------------------- online: Gemini, with tools

    private val stampFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    fun systemPrompt(ctx: Context, spoken: Boolean): String {
        val name = Store.read(ctx) { it.settings.name }.ifBlank { "the user" }
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(Engine.zone)
        val phone = if (PhoneControl.on()) "on" else "OFF (the user must turn it on in Atlas → Me → Phone control before you can use apps)"
        return """
You are Atlas, $name's personal AI assistant. You live on their Android phone and you can use it the way a person would: open apps, read the screen, tap, type, scroll and press keys. You can search the internet and read web pages directly. You also keep a Log for them: reminders and things they want remembered.

How you talk:
- Like a sharp, warm friend. Natural and relaxed, short by default, longer when they want detail.
- Read their meaning, not just their words. They often write casually with slang and typos ("ik", "ngl", "bro", "tryna"); understand it and match their energy. A compliment is a compliment: take it warmly.
- Never curt, defensive or preachy. Never say "what's your issue".
- You can help with anything: questions, advice, ideas, writing, maths, plans, explaining things.
- Be honest. Only say you did something if a tool result shows it worked. If you don't know, say so; if it might have changed recently, search the web.
- Never show code, ids, function or tool names, JSON, or how you work inside, unless they asked you for code. Say what you did in plain words ("Saved", "Done, it's in your Log").
${if (spoken) "- This message came by voice and your answer will be read aloud: one to three short spoken sentences. No lists, no formatting, no links." else """- Write neatly, clean and organized, every time. Short answers: a few clear sentences. Anything with several parts: a short heading where it helps, bullet or numbered lists, and **bold** for the key words, using Markdown. Keep paragraphs short. No filler, no repeating the question."""}

The Log (only what's worth keeping):
- set_reminder when they want reminding or have something to do at a time ("remind me to pick up my sister next Thursday"). Work out the exact date from today's date. If they gave a day but no time, ask what time unless one is obvious from context.
- Choose the alert yourself: an alarm for what's important or time-critical, a notification with a sound for lighter things. If they say "make it an alarm" (or a notification), do that; to change an existing one, use update_reminder.
- When they say they're doing something right now ("I'm doing it now", "starting it"), use start_focus: it opens a distraction-free screen with a timer.
- remember for lasting facts or preferences they want kept. update_reminder / forget to change the Log.
- read_log to look things up in the Log (what's coming, what they said they'd do, what they skipped, what they asked you to remember). Use it whenever they ask about something they logged.
- Don't log ordinary questions, chat or phone actions.

The internet:
- web_search for anything current or that you're not sure of; read_page to read a result or a link. Say where facts came from when it matters.

Atlas Bot (deploy_bot):
- For bigger jobs that need many steps (deep research with sources, comparing several options, a long piece of writing, checking many pages, a long chain of phone actions), or when they say "send a bot to…", deploy a bot instead of doing it here. Give it a clear goal and all the details it needs. Tell them it's on it; its report comes into this chat when it's done.
- Quick things (one search, one reminder, a short answer) you do yourself.

Your own settings (change_setting, when they ask, e.g. "talk to me", "use a deeper voice", "dark mode", "get off my screen"):
${Self.describe(ctx)}

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

    private suspend fun cloud(ctx: Context, first: Providers.Provider, q: String, spoken: Boolean, reply: Msg, media: List<Attach.Item> = emptyList()) {
        var p = first
        reply.source = p.name
        val m = withContext(Dispatchers.IO) { media.filter { !it.textFile }.map { Attach.media(it) } }
        // text and Word files go into the message itself, so every service can read them
        val full = q + withContext(Dispatchers.IO) { media.filter { it.textFile }.joinToString("") { Attach.inlineText(it) } }
        var session = Providers.session(ctx, p, history.toList(), full, m)
        var final: String? = null
        var steps = 0
        val tried = mutableSetOf(p.id)
        while (steps < MAX_STEPS) {
            steps++
            val system = withContext(Dispatchers.IO) { systemPrompt(ctx, spoken) }
            val turn = try { callWithKeys(ctx, p, session) { session.call(system) } } catch (sw: KeyRouter.SwitchService) {
                // carry on with the next service; it hears what was already done so nothing is repeated
                if (!tried.add(sw.to.id)) throw Gemini.Failure(sw.message ?: "All your keys have run out for now.")
                p = sw.to; reply.source = p.name
                status = "Switching to ${p.name}"; changed()
                val done = if (reply.steps.isEmpty()) "" else "\n\n(Note for you, Atlas: you already did these steps for this request before the service changed, don't repeat them: ${reply.steps.joinToString("; ")}. Continue from there.)"
                session = Providers.session(ctx, p, history.toList(), full + done, m)
                steps--
                continue
            }
            currentCoroutineContext().ensureActive()
            if (turn.calls.isEmpty()) { final = turn.text; break }
            if (turn.text.isNotBlank()) { reply.text = Plain.reply(turn.text); changed() }
            val results = mutableListOf<Pair<Gemini.Call, String>>()
            for (c in turn.calls) {
                status = label(c); changed()
                val r = Tools.run(ctx, c)
                if (r.phone && !usingPhone) usingPhone = true
                r.step?.let { reply.steps.add(it) }
                results.add(c to r.out)
                changed()
            }
            session.results(results)
        }
        val text = final ?: "I stopped after $MAX_STEPS steps without finishing. Tell me how you'd like me to continue."
        reply.text = Plain.reply(text).ifBlank { "Done." }
        // keep the exchange for next time as plain words, with a note of what was done
        history.add(true to (if (media.isEmpty()) q else "$q\n(sent: ${media.joinToString(", ") { if (it.isFile) it.name else if (it.video) "a video" else "a picture" }})"))
        history.add(false to text + stepNote(reply.steps))
        fixHistory()
    }

    private suspend fun callWithKeys(ctx: Context, p: Providers.Provider, session: Providers.Session, call: () -> Gemini.Turn): Gemini.Turn {
        val was = status
        return try {
            KeyRouter.call(ctx, p, session, onStatus = { st -> status = st ?: was; changed() }, call = call)
        } finally { if (status != was) { status = was; changed() } }
    }

    private fun stepNote(steps: List<String>) = if (steps.isEmpty()) "" else "\n(What I did: ${steps.joinToString("; ")})"

    /** At most [KEEP_TURNS] exchanges, starting with the user. */
    private fun fixHistory() {
        while (history.size > KEEP_TURNS * 2) history.removeAt(0)
        while (history.isNotEmpty() && !history[0].first) history.removeAt(0)
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
        val r = Brain.ask(ctx, q) { partial -> reply.text = Plain.reply(partial); changed() }
        reply.text = Plain.reply(r.text)
    }

    // ---------------------------------------------------------------- speaking

    fun speak(ctx: Context, s: String, onDone: (() -> Unit)? = null) = Voice.speak(ctx, s, onDone)
    fun hush() = Voice.hush()
}
