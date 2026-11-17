package com.anjy.atlas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Atlas can think with any of several AI services, using the user's own key. Each speaks a different dialect of
 * "chat with tools"; a [Session] hides that, so the conversation loop is the same for all of them.
 */
object Providers {
    /**
     * [style]: "gemini", "openai" (Chat Completions, used by many services) or "anthropic".
     * [note]: one line for the Brain page (what it's good for, what's free). [custom]: one the user added by hand.
     */
    class Provider(val id: String, val name: String, val style: String, val baseUrl: String, val defaultModel: String,
                   val keyUrl: String, val keyHint: String, val note: String = "", val custom: Boolean = false)

    /**
     * The built-in services. Every base address and default model below was checked against the service's own docs
     * (November 2026), and each default model supports tool calling, which Atlas needs. The order is the order Atlas
     * falls back in when a service runs out (free and fast first after the big three).
     */
    val builtIn = listOf(
        Provider("gemini", "Gemini", "gemini", Gemini.ENDPOINT, Gemini.DEFAULT_MODEL, "https://aistudio.google.com/apikey", "AIza…", "Google. Free tier with limits; the best all-rounder for Atlas"),
        Provider("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-6-sol", "https://platform.openai.com/api-keys", "sk-…", "Paid"),
        Provider("anthropic", "Claude", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5", "https://console.anthropic.com/settings/keys", "sk-ant-…", "Paid"),
        Provider("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys", "sk-or-…", "Many models behind one key; some free"),
        Provider("groq", "Groq", "openai", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys", "gsk_…", "Very fast; free tier with limits"),
        Provider("cerebras", "Cerebras", "openai", "https://api.cerebras.ai/v1", "gpt-oss-120b", "https://cloud.cerebras.ai", "your key", "Very fast; $5 trial credit for 30 days, then paid"),
        Provider("sambanova", "SambaNova", "openai", "https://api.sambanova.ai/v1", "gpt-oss-120b", "https://cloud.sambanova.ai", "your key", "Fast; free tier of 20 requests a day"),
        Provider("cohere", "Cohere", "openai", "https://api.cohere.ai/compatibility/v1", "command-a-plus-05-2026", "https://dashboard.cohere.com/api-keys", "your key", "Free trial key: 1,000 calls a month"),
        Provider("mistral", "Mistral", "openai", "https://api.mistral.ai/v1", "mistral-large-latest", "https://console.mistral.ai", "your key", "Paid (the API needs billing turned on)"),
        Provider("zai", "Z.AI (GLM)", "openai", "https://api.z.ai/api/paas/v4", "glm-5.3", "https://z.ai/model-api", "your key", "GLM models; paid"),
        Provider("fireworks", "Fireworks", "openai", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/kimi-k2-instruct-0905", "https://fireworks.ai", "your key", "Open models; paid"),
        Provider("ovh", "OVHcloud AI", "openai", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "Qwen3.5-397B-A17B", "https://endpoints.ai.cloud.ovh.net", "your key", "European hosting; paid"),
        Provider("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys", "sk-…", "Cheap; paid"),
        Provider("xai", "xAI Grok", "openai", "https://api.x.ai/v1", "grok-4.7", "https://console.x.ai", "xai-…", "Paid"),
        Provider("custom", "Other (OpenAI-compatible)", "openai", "", "", "", "your key", "Any service that speaks the OpenAI chat format", custom = true),
    )

    /** Services the user added by hand (as many as they like), loaded from settings. */
    @Volatile private var customs: List<Provider> = emptyList()
    @Volatile private var customsLoaded = false

    /** Every service: built in, then the user's own. Call [load] first in a fresh process (the functions taking a Context do). */
    val all: List<Provider> get() = builtIn + customs

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    fun load(ctx: Context) {
        if (customsLoaded) return
        customs = readCustoms(ctx); customsLoaded = true
    }

    private fun readCustoms(ctx: Context): List<Provider> = try {
        val a = JSONArray(Store.getString(ctx, "custom_services") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("id"); val name = o.optString("name").trim()
            if (!id.startsWith("c_") || name.isEmpty()) null
            else Provider(id, name, "openai", o.optString("base").trim().trimEnd('/'), o.optString("model").trim(), "", "your key", "Added by you", custom = true)
        }
    } catch (_: Exception) { emptyList() }

    private fun writeCustoms(ctx: Context, list: List<Provider>) {
        Store.putString(ctx, "custom_services", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.baseUrl).put("model", it.defaultModel)) }
        }.toString())
        customs = list; customsLoaded = true
    }

    /** Adds a service of the user's own (an OpenAI-compatible address); returns it. */
    fun addCustom(ctx: Context, name: String, base: String, model: String): Provider {
        load(ctx)
        var id = "c_" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        while (byId(id) != null) id += java.lang.Long.toString((Math.random() * 36).toLong(), 36)
        val p = Provider(id, name.trim().take(40), "openai",
            base.trim().trimEnd('/'), model.trim(), "", "your key", "Added by you", custom = true)
        writeCustoms(ctx, customs + p)
        return p
    }

    fun updateCustom(ctx: Context, id: String, name: String, base: String, model: String) {
        load(ctx)
        writeCustoms(ctx, customs.map { if (it.id == id) Provider(id, name.trim().take(40).ifEmpty { it.name }, "openai", base.trim().trimEnd('/'), model.trim(), "", "your key", "Added by you", custom = true) else it })
    }

    /** Removes a service the user added, with its keys. */
    fun removeCustom(ctx: Context, id: String) {
        load(ctx)
        if (!id.startsWith("c_")) return
        writeCustoms(ctx, customs.filter { it.id != id })
        Store.putString(ctx, "keys_$id", "[]")
        if (Store.getString(ctx, "provider") == id) Store.putString(ctx, "provider", "")
    }

    /**
     * Which service a pasted key belongs to, from its shape. Only prefixes that belong to one service are used;
     * many services' keys have no telling shape (or look like OpenAI's), so those need choosing by hand.
     */
    fun guess(key: String): String? {
        val k = key.trim()
        return when {
            k.startsWith("AIza") -> "gemini"
            k.startsWith("sk-ant-") -> "anthropic"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("xai-") -> "xai"
            k.startsWith("sk-") -> "openai"
            else -> null
        }
    }

    /**
     * Where a pasted key goes: the service its shape names, unless it only looks like an OpenAI key (many services
     * use "sk-…") while the user is on another OpenAI-style service's page, or has no telling shape at all.
     */
    fun keyTarget(key: String, shownId: String): String {
        val g = guess(key) ?: return shownId
        val shown = byId(shownId)
        return if (g == "openai" && shown != null && shown.id != "openai" && shown.style == "openai") shownId else g
    }

    // ---- keys: as many per service as you like (they stay on the phone, outside the backup) ----

    /**
     * One key in the pool. [restUntil]: when it ran out, Atlas rests it until then (free tiers refill); [bad]: the
     * service turned it down (wrong or revoked key), so it's skipped until you try it again. [fails]: runs in a row
     * that ran out, so the rest grows (2 min, 4, 8 … up to a day).
     */
    class KeyEntry(val key: String, var restUntil: Long = 0, var bad: Boolean = false, var fails: Int = 0, var problem: String = "") {
        fun usable(now: Long = System.currentTimeMillis()) = !bad && restUntil <= now
        fun json() = JSONObject().put("key", key).put("restUntil", restUntil).put("bad", bad).put("fails", fails).put("problem", problem)
    }

    @Synchronized fun keys(ctx: Context, id: String): MutableList<KeyEntry> {
        val raw = Store.getString(ctx, "keys_$id")
        val list = mutableListOf<KeyEntry>()
        if (raw != null) try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.getJSONObject(i).let { o -> list.add(KeyEntry(o.getString("key"), o.optLong("restUntil"), o.optBoolean("bad"), o.optInt("fails"), o.optString("problem"))) }
        } catch (_: Exception) {}
        if (raw == null) {
            // the one key saved before the pool existed
            (Store.getString(ctx, "key_$id") ?: if (id == "gemini") Store.getString(ctx, "gemini_key") else null)?.trim()?.takeIf { it.isNotEmpty() }?.let { list.add(KeyEntry(it)) }
            saveKeys(ctx, id, list)
        }
        return list
    }
    @Synchronized private fun saveKeys(ctx: Context, id: String, list: List<KeyEntry>) =
        Store.putString(ctx, "keys_$id", JSONArray().apply { list.forEach { put(it.json()) } }.toString())

    /** The key to use for [id] now: the first one not resting; if all are resting, the one that wakes soonest. */
    fun key(ctx: Context, id: String): String? {
        val list = keys(ctx, id).filter { !it.bad }
        if (list.isEmpty()) return null
        return (list.firstOrNull { it.usable() } ?: list.minByOrNull { it.restUntil })?.key
    }
    /** A key for [id] that is ready right now (not resting, not turned down), other than [except]. */
    fun readyKey(ctx: Context, id: String, except: String? = null): String? = keys(ctx, id).firstOrNull { it.usable() && it.key != except }?.key
    fun hasKeys(ctx: Context, id: String) = keys(ctx, id).any { !it.bad }

    /** Adds a key to the service's pool (or wakes it, if it's already there). */
    fun setKey(ctx: Context, id: String, key: String) = addKey(ctx, id, key)
    fun addKey(ctx: Context, id: String, key: String) {
        val k = key.trim(); if (k.isEmpty()) return
        val list = keys(ctx, id)
        val have = list.firstOrNull { it.key == k }
        if (have != null) { have.bad = false; have.restUntil = 0; have.fails = 0; have.problem = "" } else list.add(KeyEntry(k))
        saveKeys(ctx, id, list)
    }
    fun removeKey(ctx: Context, id: String, key: String) { saveKeys(ctx, id, keys(ctx, id).filter { it.key != key }) }
    fun wakeKey(ctx: Context, id: String, key: String) { val l = keys(ctx, id); l.firstOrNull { it.key == key }?.apply { bad = false; restUntil = 0; fails = 0; problem = "" }; saveKeys(ctx, id, l) }

    /** A key failed with [code]: rest it (ran out of quota or credit) or set it aside (turned down). */
    fun rest(ctx: Context, id: String, key: String, code: Int, problem: String) {
        val list = keys(ctx, id)
        val e = list.firstOrNull { it.key == key } ?: return
        val now = System.currentTimeMillis()
        when (code) {
            401, 403, 400 -> { e.bad = true }
            402 -> { e.restUntil = now + 6 * 3_600_000L }
            else -> { e.fails++; e.restUntil = now + minOf(24 * 3_600_000L, 120_000L shl minOf(e.fails - 1, 10)) }
        }
        e.problem = problem.take(160)
        saveKeys(ctx, id, list)
    }
    /** A call with this key worked: its run of failures is over. */
    fun worked(ctx: Context, id: String, key: String) {
        val list = keys(ctx, id); val e = list.firstOrNull { it.key == key } ?: return
        if (e.fails != 0 || e.problem.isNotEmpty()) { e.fails = 0; e.problem = ""; saveKeys(ctx, id, list) }
    }

    /** Whether a failure means "this key can't go on" (switch keys), rather than a passing hiccup. */
    fun keyExhausted(f: Gemini.Failure) = f.code == 429 || f.code == 402 || f.code == 401 || f.code == 403 ||
        (f.code == 400 && (f.message ?: "").contains("key", ignoreCase = true))

    /** The services that can take over, in list order: those with a key ready now. */
    /** A service Atlas can call: built in, or one of the user's with its address filled in. */
    fun reachable(ctx: Context, p: Provider) = !p.custom || baseUrl(ctx, p).isNotEmpty()
    fun fallbacks(ctx: Context, except: String): List<Provider> { load(ctx); return all.filter { it.id != except && reachable(ctx, it) && readyKey(ctx, it.id) != null } }
    fun model(ctx: Context, p: Provider): String =
        (Store.getString(ctx, "model_${p.id}") ?: if (p.id == "gemini") Store.getString(ctx, "gemini_model") else null)?.trim()?.takeIf { it.isNotEmpty() } ?: p.defaultModel
    fun setModel(ctx: Context, id: String, model: String) = Store.putString(ctx, "model_$id", model.trim())
    fun baseUrl(ctx: Context, p: Provider): String =
        if (p.id == "custom") (Store.getString(ctx, "base_custom") ?: "").trim().trimEnd('/') else p.baseUrl

    /** The service Atlas thinks with: the chosen one if it has a key, else the first that does. Null: none set up. */
    fun active(ctx: Context): Provider? {
        load(ctx)
        val chosen = byId(Store.getString(ctx, "provider"))
        if (chosen != null && hasKeys(ctx, chosen.id) && reachable(ctx, chosen)) {
            // the chosen service, unless all its keys are resting and another service has one ready
            // (with Don't Switch Services on, always the chosen service)
            if (readyKey(ctx, chosen.id) != null || KeyRouter.dontSwitch(ctx)) return chosen
            return fallbacks(ctx, chosen.id).firstOrNull() ?: chosen
        }
        return all.firstOrNull { readyKey(ctx, it.id) != null && reachable(ctx, it) }
            ?: all.firstOrNull { hasKeys(ctx, it.id) && reachable(ctx, it) }
    }
    fun choose(ctx: Context, id: String) = Store.putString(ctx, "provider", id)

    // ---- tool declarations in each dialect ----

    private fun params(f: JSONObject): JSONObject =
        f.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())

    fun openAiTools(decl: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until decl.length()) {
            val f = decl.getJSONObject(i)
            put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", f.getString("name")).put("description", f.getString("description")).put("parameters", params(f))))
        }
    }

    fun anthropicTools(decl: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until decl.length()) {
            val f = decl.getJSONObject(i)
            put(JSONObject().put("name", f.getString("name")).put("description", f.getString("description")).put("input_schema", params(f)))
        }
    }

    // ---- one HTTP call, with errors in plain words ----

    fun post(url: String, headers: Map<String, String>, body: JSONObject, service: String): JSONObject {
        val con = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw Gemini.Failure("The address for $service isn't valid: $url") }
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000; con.readTimeout = 120_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw Gemini.Failure(explain(service, code, text), code = code, retryAfterMs = KeyRouter.retryHint(text, con.getHeaderField("retry-after")))
            return try { JSONObject(text) } catch (e: Exception) { throw Gemini.Failure("$service sent back something unreadable.") }
        } catch (e: Gemini.Failure) { throw e
        } catch (e: UnknownHostException) { throw Gemini.Failure("No internet connection.", offline = true)
        } catch (e: SocketTimeoutException) { throw Gemini.Failure("$service took too long to answer.", offline = true)
        } catch (e: IOException) { throw Gemini.Failure("Couldn't reach $service: ${e.message ?: e.javaClass.simpleName}", offline = true)
        } finally { con.disconnect() }
    }

    fun explain(service: String, code: Int, body: String): String {
        val msg = try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message")
        } catch (_: Exception) { "" }
        return when (code) {
            401, 403 -> "$service turned the key down: ${msg.ifBlank { "not authorised" }.take(160)}"
            400 -> if (msg.contains("key", true) && (msg.contains("invalid", true) || msg.contains("not valid", true))) "That $service key isn't valid. Check it in Me."
                   else "$service didn't accept the request: ${msg.ifBlank { "bad request" }.take(180)}"
            404 -> "$service doesn't know the model set in Me: ${msg.take(160)}"
            402 -> "$service says the account has no credit left: ${msg.take(120)}"
            429 -> "The $service limit is used up for now. Wait a minute and try again."
            in 500..599 -> "$service is having trouble right now ($code). Try again shortly."
            else -> "$service error $code: ${msg.ifBlank { body }.take(160)}"
        }
    }

    // ---- a conversation with tools, one task long ----

    abstract class Session {
        /** The key it's using; swapped for another when one runs out partway through a task. */
        abstract var key: String
        /** One round: the model answers, maybe asking for tools. */
        abstract fun call(system: String): Gemini.Turn
        /** The answers to the tools it asked for, in order. */
        abstract fun results(results: List<Pair<Gemini.Call, String>>)
    }

    /** A picture, video or PDF sent with a message: its type, its bytes as base64, and (for files) its name. */
    /** [fileUri]: a file already uploaded to Gemini's Files API (then [base64] is empty; Gemini only). */
    class Media(val mime: String, val base64: String, val name: String = "", val fileUri: String = "") {
        val video get() = mime.startsWith("video/")
        val pdf get() = mime == "application/pdf"
    }

    /**
     * [history]: earlier exchanges as (fromUser, text). [media]: pictures or a video sent with this message.
     * [tools]: the functions the model may call (Atlas Bot passes its own set).
     */
    fun session(ctx: Context, p: Provider, history: List<Pair<Boolean, String>>, user: String, media: List<Media> = emptyList(),
                tools: JSONArray = Tools.declarations): Session {
        val key = key(ctx, p.id) ?: throw Gemini.Failure("No key for ${p.name}.")
        val model = model(ctx, p)
        if (media.any { it.video } && p.style != "gemini") throw Gemini.Failure("${p.name} can't watch videos. Gemini can: switch to it in Me → Brain, or send a picture instead.")
        return when (p.style) {
            "gemini" -> GeminiSession(key, model, Conversation.endpoint(ctx), history, user, media, tools)
            "anthropic" -> AnthropicSession(key, model, p.baseUrl, history, user, media, tools)
            else -> OpenAiSession(key, model, baseUrl(ctx, p), p, history, user, media, tools)
        }
    }

    class GeminiSession(override var key: String, private val model: String, private val endpoint: String, history: List<Pair<Boolean, String>>, user: String,
                        media: List<Media> = emptyList(), private val tools: JSONArray = Tools.declarations) : Session() {
        private val contents = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(if (u) Gemini.userText(t) else Gemini.modelText(t)) }
            if (media.isEmpty()) add(Gemini.userText(user)) else {
                val parts = JSONArray()
                media.forEach { m ->
                    if (m.fileUri.isNotEmpty()) parts.put(JSONObject().put("file_data", JSONObject().put("mime_type", m.mime).put("file_uri", m.fileUri)))
                    else parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", m.mime).put("data", m.base64)))
                }
                parts.put(JSONObject().put("text", user))
                add(JSONObject().put("role", "user").put("parts", parts))
            }
        }
        override fun call(system: String): Gemini.Turn {
            val req = Gemini.request(system, Gemini.trimScreens(contents), tools, model)
            val t = Gemini.call(key, model, req, endpoint)
            contents.add(t.content)
            return t
        }
        override fun results(results: List<Pair<Gemini.Call, String>>) { contents.add(Gemini.toolResults(results)) }
    }

    class OpenAiSession(override var key: String, private val model: String, private val base: String, private val p: Provider,
                        history: List<Pair<Boolean, String>>, user: String, media: List<Media> = emptyList(),
                        private val tools: JSONArray = Tools.declarations) : Session() {
        private val messages = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(JSONObject().put("role", if (u) "user" else "assistant").put("content", t)) }
            if (media.isEmpty()) add(JSONObject().put("role", "user").put("content", user)) else {
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", user))
                media.forEach { m ->
                    if (m.pdf) parts.put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", m.name.ifBlank { "document.pdf" }).put("file_data", "data:application/pdf;base64,${m.base64}")))
                    else parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${m.mime};base64,${m.base64}")))
                }
                add(JSONObject().put("role", "user").put("content", parts))
            }
        }
        override fun call(system: String): Gemini.Turn {
            if (base.isEmpty()) throw Gemini.Failure("Set the address of your service in Me.")
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            trimToolScreens(messages).forEach { msgs.put(it) }
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", openAiTools(tools))
            // OpenAI's GPT-6 models only call functions in this API with reasoning off (their docs)
            if (p.id == "openai" && model.startsWith("gpt-6")) body.put("reasoning_effort", "none")
            val headers = mutableMapOf("Authorization" to "Bearer $key")
            if (p.id == "openrouter") { headers["HTTP-Referer"] = "https://github.com/anjystar10/atlas"; headers["X-Title"] = "Atlas" }
            val r = post("$base/chat/completions", headers, body, p.name)
            val choice = r.optJSONArray("choices")?.optJSONObject(0) ?: throw Gemini.Failure("${p.name} sent back an empty answer.")
            val m = choice.optJSONObject("message") ?: throw Gemini.Failure("${p.name} sent back an empty answer.")
            val text = if (m.isNull("content")) "" else m.optString("content", "")
            val calls = mutableListOf<Gemini.Call>()
            m.optJSONArray("tool_calls")?.let { a ->
                for (i in 0 until a.length()) {
                    val tc = a.getJSONObject(i); val f = tc.optJSONObject("function") ?: continue
                    val args = try { JSONObject(f.optString("arguments").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    calls.add(Gemini.Call(f.optString("name"), args, tc.optString("id").ifEmpty { "call_$i" }))
                }
            }
            // keep the assistant turn as sent, minus fields some services refuse to get back
            val keep = JSONObject().put("role", "assistant").put("content", if (text.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else text)
            m.optJSONArray("tool_calls")?.let { keep.put("tool_calls", it) }
            messages.add(keep)
            if (calls.isEmpty() && text.isBlank()) throw Gemini.Failure("${p.name} sent back an empty answer (${choice.optString("finish_reason")}).")
            return Gemini.Turn(keep, text.trim(), calls, choice.optString("finish_reason"))
        }
        override fun results(results: List<Pair<Gemini.Call, String>>) {
            results.forEach { (c, out) -> messages.add(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", out)) }
        }
    }

    class AnthropicSession(override var key: String, private val model: String, private val base: String,
                           history: List<Pair<Boolean, String>>, user: String, media: List<Media> = emptyList(),
                           private val tools: JSONArray = Tools.declarations) : Session() {
        private val messages = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(JSONObject().put("role", if (u) "user" else "assistant").put("content", t)) }
            if (media.isEmpty()) add(JSONObject().put("role", "user").put("content", user)) else {
                val parts = JSONArray()
                media.forEach { m ->
                    parts.put(JSONObject().put("type", if (m.pdf) "document" else "image").put("source", JSONObject().put("type", "base64").put("media_type", m.mime).put("data", m.base64)))
                }
                parts.put(JSONObject().put("type", "text").put("text", user))
                add(JSONObject().put("role", "user").put("content", parts))
            }
            // Claude needs turns to alternate, starting with the user
            while (isNotEmpty() && first().optString("role") != "user") removeAt(0)
        }
        override fun call(system: String): Gemini.Turn {
            val msgs = JSONArray(); trimToolScreens(messages).forEach { msgs.put(it) }
            val body = JSONObject().put("model", model).put("max_tokens", 2048).put("system", system)
                .put("messages", msgs).put("tools", anthropicTools(tools))
            val r = post("$base/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body, "Claude")
            val content = r.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder(); val calls = mutableListOf<Gemini.Call>()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> calls.add(Gemini.Call(b.optString("name"), b.optJSONObject("input") ?: JSONObject(), b.optString("id")))
                }
            }
            messages.add(JSONObject().put("role", "assistant").put("content", content))
            if (calls.isEmpty() && text.isBlank()) throw Gemini.Failure("Claude sent back an empty answer (${r.optString("stop_reason")}).")
            return Gemini.Turn(messages.last(), text.toString().trim(), calls, r.optString("stop_reason"))
        }
        override fun results(results: List<Pair<Gemini.Call, String>>) {
            val parts = JSONArray()
            results.forEach { (c, out) -> parts.put(JSONObject().put("type", "tool_result").put("tool_use_id", c.id).put("content", out)) }
            messages.add(JSONObject().put("role", "user").put("content", parts))
        }
    }

    /**
     * Older screen readings within a task are cut to their first line (the same idea as [Gemini.trimScreens]) so long
     * phone tasks stay within limits. Works on both tool-message shapes; returns copies.
     */
    fun trimToolScreens(messages: List<JSONObject>, keep: Int = 2): List<JSONObject> {
        fun isResult(m: JSONObject) = m.optString("role") == "tool" ||
            (m.optString("role") == "user" && m.optJSONArray("content")?.optJSONObject(0)?.optString("type") == "tool_result")
        val idx = messages.indices.filter { isResult(messages[it]) }
        val old = idx.dropLast(keep).toSet()
        if (old.isEmpty()) return messages
        fun cut(s: String): String { val nl = s.indexOf('\n'); return if (nl > 0 && s.length > 300) s.substring(0, nl) + " (older screen reading left out)" else s }
        return messages.mapIndexed { i, m ->
            if (i !in old) m else {
                val c = JSONObject(m.toString())
                if (c.optString("role") == "tool") c.put("content", cut(c.optString("content")))
                else c.optJSONArray("content")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { b -> b.put("content", cut(b.optString("content"))) } }
                c
            }
        }
    }
}
