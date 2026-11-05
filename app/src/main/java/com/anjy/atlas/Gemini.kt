package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Atlas's online brain: Google's Gemini API (generateContent, REST), called with the user's own key.
 * Request building and response reading are plain functions so they're unit-tested without a network.
 */
object Gemini {
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** A reply from one call: the model's turn exactly as sent (thought signatures included), its words, and its tool calls. */
    class Turn(val content: JSONObject, val text: String, val calls: List<Call>, val finish: String)
    class Call(val name: String, val args: JSONObject, val id: String?)

    /** Something went wrong talking to Gemini. [offline] means the phone couldn't reach it at all. */
    class Failure(message: String, val offline: Boolean = false, val code: Int = 0) : Exception(message)

    fun request(system: String, contents: List<JSONObject>, tools: JSONArray, model: String): JSONObject = JSONObject().apply {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        put("contents", JSONArray().apply { contents.forEach { put(it) } })
        put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
        // Gemini 3 models think before answering; "low" keeps replies quick. Older models don't take this setting.
        if (model.startsWith("gemini-3")) put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
    }

    fun userText(text: String): JSONObject =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun modelText(text: String): JSONObject =
        JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** The answers to the model's tool calls, in the order it asked, as one user turn. */
    fun toolResults(results: List<Pair<Call, String>>): JSONObject {
        val parts = JSONArray()
        results.forEach { (c, out) ->
            val fr = JSONObject().put("name", c.name).put("response", JSONObject().put("result", out))
            if (c.id != null) fr.put("id", c.id)
            parts.put(JSONObject().put("functionResponse", fr))
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    /**
     * Every action returns a full reading of the screen, and the whole task is sent with each step. Only the latest
     * [keep] readings are useful: older ones are cut to their first line ("Tapped “Send”."), which keeps long tasks
     * within the free tier's limits. Returns a new list; the turns themselves aren't changed.
     */
    fun trimScreens(contents: List<JSONObject>, keep: Int = 2): List<JSONObject> {
        val withResults = contents.indices.filter { i -> contents[i].optJSONArray("parts")?.let { p -> (0 until p.length()).any { p.optJSONObject(it)?.has("functionResponse") == true } } == true }
        val old = withResults.dropLast(keep).toSet()
        if (old.isEmpty()) return contents
        return contents.mapIndexed { i, c ->
            if (i !in old) c else {
                val copy = JSONObject(c.toString())
                val parts = copy.getJSONArray("parts")
                for (k in 0 until parts.length()) {
                    val resp = parts.optJSONObject(k)?.optJSONObject("functionResponse")?.optJSONObject("response") ?: continue
                    val r = resp.optString("result")
                    val nl = r.indexOf('\n')
                    if (nl > 0 && r.length > 300) resp.put("result", r.substring(0, nl) + " (older screen reading left out)")
                }
                copy
            }
        }
    }

    /** Reads a generateContent response. Throws [Failure] when there's no usable answer. */
    fun parse(body: JSONObject): Turn {
        body.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw Failure("Gemini refused that message ($it).")
        }
        val cands = body.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) throw Failure("Gemini sent back an empty answer.")
        val cand = cands.getJSONObject(0)
        val finish = cand.optString("finishReason", "")
        val content = cand.optJSONObject("content") ?: JSONObject().put("role", "model").put("parts", JSONArray())
        if (!content.has("role")) content.put("role", "model")
        val parts = content.optJSONArray("parts") ?: JSONArray().also { content.put("parts", it) }
        val text = StringBuilder()
        val calls = mutableListOf<Call>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            p.optJSONObject("functionCall")?.let { fc ->
                calls.add(Call(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(), fc.optString("id").takeIf { it.isNotEmpty() }))
            }
            if (p.has("text")) text.append(p.optString("text"))
        }
        if (calls.isEmpty() && text.isBlank()) {
            throw Failure(when (finish) {
                "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> "Gemini wouldn't answer that one."
                "MAX_TOKENS" -> "Gemini ran out of room before answering."
                "MALFORMED_FUNCTION_CALL" -> "Gemini got muddled calling a tool. Try asking again."
                else -> "Gemini sent back an empty answer${if (finish.isNotEmpty()) " ($finish)" else ""}."
            })
        }
        return Turn(content, text.toString().trim(), calls, finish)
    }

    /** Turns an error body into one plain sentence. */
    fun explain(code: Int, body: String): String {
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
        return when (code) {
            400 -> if (msg.contains("API key", true)) "That Gemini key isn't valid. Check it in Me." else "Gemini didn't accept the request: ${msg.ifBlank { "bad request" }.take(180)}"
            401, 403 -> "Gemini turned the key down: ${msg.ifBlank { "permission denied" }.take(160)}"
            404 -> "Gemini doesn't know the model name set in Me: ${msg.take(160)}"
            429 -> "The free Gemini limit is used up for now. Wait a minute and try again."
            in 500..599 -> "Gemini is having trouble right now ($code). Try again shortly."
            else -> "Gemini error $code: ${msg.ifBlank { body }.take(160)}"
        }
    }

    /**
     * Where requests go. Always Google, except that the emulator test may point it at a stand-in running on the
     * same phone (127.0.0.1 only, so a key can never be sent anywhere else).
     */
    fun endpoint(override: String?): String = override?.takeIf { it.startsWith("http://127.0.0.1:") } ?: ENDPOINT

    /** One call to generateContent. Network work: never call on the main thread. */
    fun call(key: String, model: String, req: JSONObject, endpoint: String = ENDPOINT): Turn {
        val url = URL(endpoint + model.trim() + ":generateContent")
        val con = try { url.openConnection() as HttpURLConnection } catch (e: IOException) { throw Failure("Couldn't reach Gemini.", offline = true) }
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000; con.readTimeout = 120_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", key.trim())
            con.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw Failure(explain(code, body), code = code)
            return parse(JSONObject(body))
        } catch (e: Failure) { throw e
        } catch (e: UnknownHostException) { throw Failure("No internet connection.", offline = true)
        } catch (e: SocketTimeoutException) { throw Failure("Gemini took too long to answer.", offline = true)
        } catch (e: IOException) { throw Failure("Couldn't reach Gemini: ${e.message ?: e.javaClass.simpleName}", offline = true)
        } finally { con.disconnect() }
    }
}
