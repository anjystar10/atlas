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
    /** [style]: "gemini", "openai" (Chat Completions, used by many services) or "anthropic". */
    class Provider(val id: String, val name: String, val style: String, val baseUrl: String, val defaultModel: String, val keyUrl: String, val keyHint: String)

    val all = listOf(
        Provider("gemini", "Gemini", "gemini", Gemini.ENDPOINT, Gemini.DEFAULT_MODEL, "https://aistudio.google.com/apikey", "AIza…"),
        Provider("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-6-sol", "https://platform.openai.com/api-keys", "sk-…"),
        Provider("anthropic", "Claude", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5", "https://console.anthropic.com/settings/keys", "sk-ant-…"),
        Provider("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys", "sk-or-…"),
        Provider("groq", "Groq", "openai", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys", "gsk_…"),
        Provider("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys", "sk-…"),
        Provider("xai", "xAI Grok", "openai", "https://api.x.ai/v1", "grok-4.7", "https://console.x.ai", "xai-…"),
        Provider("custom", "Other (OpenAI-compatible)", "openai", "", "", "", "your key"),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Which service a pasted key belongs to, from its shape. DeepSeek keys look like OpenAI's: those need choosing by hand. */
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

    // ---- settings (keys stay on the phone, outside the backup) ----

    fun key(ctx: Context, id: String): String? =
        (Store.getString(ctx, "key_$id") ?: if (id == "gemini") Store.getString(ctx, "gemini_key") else null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setKey(ctx: Context, id: String, key: String) {
        Store.putString(ctx, "key_$id", key.trim())
        if (id == "gemini") Store.putString(ctx, "gemini_key", key.trim())
    }
    fun model(ctx: Context, p: Provider): String =
        (Store.getString(ctx, "model_${p.id}") ?: if (p.id == "gemini") Store.getString(ctx, "gemini_model") else null)?.trim()?.takeIf { it.isNotEmpty() } ?: p.defaultModel
    fun setModel(ctx: Context, id: String, model: String) = Store.putString(ctx, "model_$id", model.trim())
    fun baseUrl(ctx: Context, p: Provider): String =
        if (p.id == "custom") (Store.getString(ctx, "base_custom") ?: "").trim().trimEnd('/') else p.baseUrl

    /** The service Atlas thinks with: the chosen one if it has a key, else the first that does. Null: none set up. */
    fun active(ctx: Context): Provider? {
        val chosen = byId(Store.getString(ctx, "provider"))
        if (chosen != null && key(ctx, chosen.id) != null && (chosen.id != "custom" || baseUrl(ctx, chosen).isNotEmpty())) return chosen
        return all.firstOrNull { key(ctx, it.id) != null && (it.id != "custom" || baseUrl(ctx, it).isNotEmpty()) }
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
            if (code !in 200..299) throw Gemini.Failure(explain(service, code, text), code = code)
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
        /** One round: the model answers, maybe asking for tools. */
        abstract fun call(system: String): Gemini.Turn
        /** The answers to the tools it asked for, in order. */
        abstract fun results(results: List<Pair<Gemini.Call, String>>)
    }

    /** [history]: earlier exchanges as (fromUser, text). */
    fun session(ctx: Context, p: Provider, history: List<Pair<Boolean, String>>, user: String): Session {
        val key = key(ctx, p.id) ?: throw Gemini.Failure("No key for ${p.name}.")
        val model = model(ctx, p)
        return when (p.style) {
            "gemini" -> GeminiSession(key, model, Conversation.endpoint(ctx), history, user)
            "anthropic" -> AnthropicSession(key, model, p.baseUrl, history, user)
            else -> OpenAiSession(key, model, baseUrl(ctx, p), p, history, user)
        }
    }

    class GeminiSession(private val key: String, private val model: String, private val endpoint: String, history: List<Pair<Boolean, String>>, user: String) : Session() {
        private val contents = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(if (u) Gemini.userText(t) else Gemini.modelText(t)) }
            add(Gemini.userText(user))
        }
        override fun call(system: String): Gemini.Turn {
            val req = Gemini.request(system, Gemini.trimScreens(contents), Tools.declarations, model)
            val t = Gemini.call(key, model, req, endpoint)
            contents.add(t.content)
            return t
        }
        override fun results(results: List<Pair<Gemini.Call, String>>) { contents.add(Gemini.toolResults(results)) }
    }

    class OpenAiSession(private val key: String, private val model: String, private val base: String, private val p: Provider,
                        history: List<Pair<Boolean, String>>, user: String) : Session() {
        private val messages = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(JSONObject().put("role", if (u) "user" else "assistant").put("content", t)) }
            add(JSONObject().put("role", "user").put("content", user))
        }
        override fun call(system: String): Gemini.Turn {
            if (base.isEmpty()) throw Gemini.Failure("Set the address of your service in Me.")
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            trimToolScreens(messages).forEach { msgs.put(it) }
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", openAiTools(Tools.declarations))
            // OpenAI's GPT-6 models only call functions in this API with reasoning off (their docs)
            if (p.id == "openai" && model.startsWith("gpt-6")) body.put("reasoning_effort", "none")
            val headers = mutableMapOf("Authorization" to "Bearer $key")
            if (p.id == "openrouter") { headers["HTTP-Referer"] = "https://github.com/anjystar10/atlas-app"; headers["X-Title"] = "Atlas" }
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

    class AnthropicSession(private val key: String, private val model: String, private val base: String,
                           history: List<Pair<Boolean, String>>, user: String) : Session() {
        private val messages = ArrayList<JSONObject>().apply {
            history.forEach { (u, t) -> add(JSONObject().put("role", if (u) "user" else "assistant").put("content", t)) }
            add(JSONObject().put("role", "user").put("content", user))
            // Claude needs turns to alternate, starting with the user
            while (isNotEmpty() && first().optString("role") != "user") removeAt(0)
        }
        override fun call(system: String): Gemini.Turn {
            val msgs = JSONArray(); trimToolScreens(messages).forEach { msgs.put(it) }
            val body = JSONObject().put("model", model).put("max_tokens", 2048).put("system", system)
                .put("messages", msgs).put("tools", anthropicTools(Tools.declarations))
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
