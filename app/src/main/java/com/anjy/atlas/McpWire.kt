package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plain rules behind Connections (Connections.kt), with nothing Android in them so they're unit-tested:
 * tool names, schemas every AI service accepts, MCP's JSON-RPC messages and the two ways a server answers
 * (plain JSON or a stream of SSE events), pasted setups, web API paths, and what needs the user's OK.
 * The PC follows the same rules in desktop/renderer/connect.js.
 */
object McpWire {
    const val PROTOCOL = "2025-06-18"
    const val CALL_API = "call_api"
    val TRUST = listOf("ask", "reads", "all")
    val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")

    // ---------------------------------------------------------------- names

    private fun slug(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")
    private fun shortHash(s: String): String { var h = 5381L; for (ch in s) h = ((h * 33) xor ch.code.toLong()) and 0xFFFFFFFFL; return java.lang.Long.toString(h, 36).take(5) }

    /** mcp__<server>__<tool>: letters, numbers and _, at most 64 characters (every service's limit); unique when cut. */
    fun toolName(server: String, tool: String): String {
        val head = "mcp__${slug(server)}__"; val t = slug(tool)
        return if (head.length + t.length <= 64) head + t else head + t.take(64 - head.length - 6) + "_" + shortHash(tool)
    }

    // ---------------------------------------------------------------- schemas

    private val simple = setOf("string", "number", "integer", "boolean", "array", "object")

    private fun ref(s: JSONObject, root: JSONObject?): JSONObject? {
        val r = s.optString("\$ref"); val m = Regex("^#/(\\\$defs|definitions)/(.+)$").find(r) ?: return null
        return root?.optJSONObject(m.groupValues[1])?.optJSONObject(m.groupValues[2])
    }

    /**
     * An MCP tool's JSON Schema reduced to what every service takes (Gemini's is the strictest): type, description,
     * enum, properties, required, items, nullable. References are followed, either/or picks its first real option,
     * and an object with no listed fields becomes text holding JSON (see [coerceArgs]).
     */
    fun simpleSchema(schema: JSONObject?, root: JSONObject? = schema, depth: Int = 0): JSONObject {
        var s = schema ?: JSONObject()
        if (depth > 8) return JSONObject().put("type", "string")
        if (s.has("\$ref")) {
            val target = ref(s, root)
            return if (target != null) simpleSchema(JSONObject(target.toString()).apply { s.optString("description").takeIf { it.isNotEmpty() }?.let { put("description", it) } }, root, depth + 1)
            else JSONObject().put("type", "string").apply { s.optString("description").takeIf { it.isNotEmpty() }?.let { put("description", it) } }
        }
        var nullable = s.optBoolean("nullable", false)
        for (k in listOf("anyOf", "oneOf")) {
            val arr = s.optJSONArray(k) ?: continue
            if (arr.length() == 0) continue
            val real = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type") != "null" }
            if (real.size < arr.length()) nullable = true
            val first = JSONObject((real.firstOrNull() ?: JSONObject()).toString())
            s.optString("description").takeIf { it.isNotEmpty() }?.let { first.put("description", it) }
            val pick = simpleSchema(first, root, depth + 1)
            if (nullable) pick.put("nullable", true)
            return pick
        }
        s.optJSONArray("allOf")?.let { all ->
            val merged = JSONObject(s.toString()); merged.remove("allOf")
            val props = merged.optJSONObject("properties") ?: JSONObject(); val req = merged.optJSONArray("required") ?: JSONArray()
            for (i in 0 until all.length()) {
                var part = all.optJSONObject(i) ?: continue
                if (part.has("\$ref")) part = ref(part, root) ?: continue
                part.optJSONObject("properties")?.let { p -> p.keys().forEach { k -> props.put(k, p.get(k)) } }
                part.optJSONArray("required")?.let { r -> for (j in 0 until r.length()) req.put(r.get(j)) }
                if (!merged.has("type") && part.has("type")) merged.put("type", part.get("type"))
            }
            merged.put("properties", props).put("required", req)
            s = merged
        }
        var type: String? = when (val t = s.opt("type")) {
            is String -> t
            is JSONArray -> { val l = (0 until t.length()).map { t.optString(it) }; if ("null" in l) nullable = true; l.firstOrNull { it != "null" } }
            else -> null
        }
        if (type !in simple) type = when { s.has("properties") -> "object"; s.has("items") -> "array"; else -> "string" }
        val out = JSONObject().put("type", type)
        val desc = listOf(s.optString("description").ifEmpty { s.optString("title") }, if (s.has("default") && type != "object") "Default: ${JSONArray().put(s.get("default")).toString().removePrefix("[").removeSuffix("]")}" else "")
            .filter { it.isNotEmpty() }.joinToString(" ")
        if (desc.isNotEmpty()) out.put("description", desc.take(600))
        s.optJSONArray("enum")?.let { e ->
            val vals = (0 until e.length()).map { e.get(it) }
            if (type == "string") { val strs = vals.filterIsInstance<String>().filter { it.isNotEmpty() }; if (strs.isNotEmpty()) out.put("enum", JSONArray(strs)) }
            else if (vals.isNotEmpty()) out.put("description", ((out.optString("description").let { if (it.isEmpty()) "" else "$it " }) + "One of: " + vals.joinToString(", ") { JSONArray().put(it).toString().removePrefix("[").removeSuffix("]") }).take(600))
        }
        if (type == "array") out.put("items", simpleSchema(s.optJSONObject("items") ?: JSONObject(), root, depth + 1))
        if (type == "object") {
            val props = s.optJSONObject("properties")
            if (props == null || props.length() == 0) {
                if (depth == 0) return JSONObject().put("type", "object").put("properties", JSONObject())
                return JSONObject().put("type", "string").put("description", ((out.optString("description").let { if (it.isEmpty()) "" else "$it " }) + "(Give this as JSON text, e.g. {\"key\":\"value\"}.)").take(600))
                    .apply { if (nullable) put("nullable", true) }
            }
            val outProps = JSONObject()
            props.keys().forEach { k -> outProps.put(k, simpleSchema(props.optJSONObject(k) ?: JSONObject(), root, depth + 1)) }
            out.put("properties", outProps)
            s.optJSONArray("required")?.let { r -> val req = (0 until r.length()).map { r.optString(it) }.filter { outProps.has(it) }.distinct(); if (req.isNotEmpty()) out.put("required", JSONArray(req)) }
        }
        if (nullable) out.put("nullable", true)
        return out
    }

    /** Puts arguments back in the shape the server's own schema asks for (JSON text → object, "5" → 5, "true" → true). */
    fun coerceArgs(schema: JSONObject?, value: Any?, root: JSONObject? = schema, depth: Int = 0): Any? {
        var s = schema ?: return value
        if (depth > 8 || value == null || value == JSONObject.NULL) return value
        if (s.has("\$ref")) s = ref(s, root) ?: JSONObject()
        for (k in listOf("anyOf", "oneOf")) s.optJSONArray(k)?.let { a -> s = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") != "null" } ?: JSONObject() }
        var type: String? = when (val t = s.opt("type")) { is String -> t; is JSONArray -> (0 until t.length()).map { t.optString(it) }.firstOrNull { it != "null" }; else -> null }
        if (type == null) type = when { s.has("properties") -> "object"; s.has("items") -> "array"; else -> null }
        var v: Any = value
        if ((type == "object" || type == "array") && v is String) {
            val t = (v as String).trim()
            try { v = if (t.startsWith("{")) JSONObject(t) else if (t.startsWith("[")) JSONArray(t) else v } catch (_: Exception) {}
        }
        if (type == "object" && v is JSONObject) {
            val props = s.optJSONObject("properties") ?: return v
            val out = JSONObject(v.toString())
            props.keys().forEach { k -> if (out.has(k)) out.put(k, coerceArgs(props.optJSONObject(k), out.get(k), root, depth + 1) ?: JSONObject.NULL) }
            return out
        }
        if (type == "array" && v is JSONArray) { val items = s.optJSONObject("items") ?: return v; return JSONArray().apply { for (i in 0 until v.length()) put(coerceArgs(items, v.get(i), root, depth + 1) ?: JSONObject.NULL) } }
        if ((type == "number" || type == "integer") && v is String) { val t = v.trim(); t.toLongOrNull()?.let { return it }; t.toDoubleOrNull()?.let { return it } }
        if (type == "boolean" && v is String) return when (v) { "true" -> true; "false" -> false; else -> v }
        return v
    }

    // ---------------------------------------------------------------- MCP messages (JSON-RPC)

    fun request(id: Int, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).apply { if (params != null) put("params", params) }
    fun notification(method: String): JSONObject = JSONObject().put("jsonrpc", "2.0").put("method", method)
    fun initialize(id: Int, version: String): JSONObject = request(id, "initialize", JSONObject().put("protocolVersion", PROTOCOL)
        .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "atlas").put("version", version)))

    /**
     * The answer to request [id] in a server's reply: the body is either JSON (one message or a list) or an SSE stream
     * ("event: message\ndata: {...}" blocks, data possibly over several lines). Notifications and other ids are skipped.
     */
    fun answerFor(contentType: String?, body: String, id: Int): JSONObject? {
        val messages = mutableListOf<JSONObject>()
        val isSse = contentType?.contains("text/event-stream", ignoreCase = true) == true || (contentType.isNullOrBlank() && body.trimStart().let { it.startsWith("event:") || it.startsWith("data:") })
        if (isSse) {
            for (block in body.replace("\r\n", "\n").split(Regex("\n\n+"))) {
                val data = block.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                if (data.isBlank()) continue
                try { parse(data, messages) } catch (_: Exception) {}
            }
        } else if (body.isNotBlank()) parse(body, messages)
        return messages.firstOrNull { it.has("id") && it.opt("id")?.toString() == id.toString() && (it.has("result") || it.has("error")) }
    }
    private fun parse(text: String, into: MutableList<JSONObject>) {
        val t = text.trim()
        if (t.startsWith("[")) { val a = JSONArray(t); for (i in 0 until a.length()) a.optJSONObject(i)?.let { into.add(it) } } else into.add(JSONObject(t))
    }

    class Tool(val name: String, val description: String, val schema: JSONObject, val readOnly: Boolean, val destructive: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("inputSchema", schema).put("readOnly", readOnly).put("destructive", destructive)
        companion object {
            fun fromJson(o: JSONObject) = Tool(o.optString("name"), o.optString("description"), o.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"), o.optBoolean("readOnly"), o.optBoolean("destructive"))
            /** From a tools/list result entry. */
            fun fromMcp(t: JSONObject): Tool {
                val ann = t.optJSONObject("annotations") ?: JSONObject()
                return Tool(t.optString("name"), t.optString("description").ifEmpty { ann.optString("title").ifEmpty { t.optString("title") } }.take(1024),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()), ann.optBoolean("readOnlyHint", false), ann.optBoolean("destructiveHint", false))
            }
        }
    }

    class Out(val text: String, val imageMime: String? = null, val imageBase64: String? = null, val error: Boolean = false)
    const val MAX_OUT = 12000
    private fun cap(s: String) = if (s.length > MAX_OUT) s.take(MAX_OUT) + "\n(cut short: the answer was longer)" else s

    /** A tools/call result → what Atlas reads: the text (capped), the first picture, and whether the tool said it failed. */
    fun readResult(result: JSONObject): Out {
        val sb = StringBuilder(); var mime: String? = null; var img: String? = null
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> { if (sb.isNotEmpty()) sb.append('\n'); sb.append(c.optString("text")) }
                "image" -> if (img == null && c.optString("data").length in 1 until 6_000_000) { img = c.optString("data"); mime = c.optString("mimeType").ifEmpty { "image/png" } }
                "resource" -> c.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { if (sb.isNotEmpty()) sb.append('\n'); sb.append(it) }
                "resource_link" -> { if (sb.isNotEmpty()) sb.append('\n'); sb.append("(link: ${c.optString("uri")})") }
            }
        }
        if (sb.isEmpty()) result.optJSONObject("structuredContent")?.let { sb.append(it.toString()) }
        val error = result.optBoolean("isError", false)
        val text = if (sb.isEmpty()) (if (error) "The tool reported an error." else if (img != null) "(a picture)" else "Done.") else sb.toString()
        return Out(cap(text), mime, img, error)
    }

    // ---------------------------------------------------------------- pasted setups

    class ServerCfg(val name: String, val url: String?, val headers: Map<String, String>, val command: String?)

    /**
     * {"mcpServers": {"name": {"url": "https://…", "headers": {...}}}} or {"name": {...}} or one server's own block.
     * Programs on a computer (a "command") are returned too, so the phone can say they only run on the PC.
     */
    fun parseConfig(text: String): List<ServerCfg> {
        val j = try { JSONObject(text.trim()) } catch (_: Exception) { throw IllegalArgumentException("That isn't valid JSON. Paste the whole block, braces included.") }
        var map = j.optJSONObject("mcpServers") ?: j.optJSONObject("servers") ?: j
        if (map.has("url") || map.has("command") || map.has("serverUrl")) map = JSONObject().put(j.optString("name", "server"), map)
        val out = mutableListOf<ServerCfg>()
        map.keys().forEach { raw ->
            val c = map.optJSONObject(raw) ?: return@forEach
            val name = slug(raw).replace(Regex("_+"), "_").trim('_').take(32).ifEmpty { "server" }
            val url = c.optString("url").ifEmpty { c.optString("serverUrl").ifEmpty { c.optString("httpUrl") } }
            val headers = mutableMapOf<String, String>(); c.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) out.add(ServerCfg(name, url, headers, null))
            else if (c.optString("command").isNotBlank()) out.add(ServerCfg(name, null, emptyMap(), c.optString("command")))
        }
        if (out.isEmpty()) throw IllegalArgumentException("No servers found in that. Each one needs a \"url\" (or a \"command\" on the PC).")
        return out
    }

    // ---------------------------------------------------------------- web APIs

    /** The address a call goes to; throws when [path] would leave the API's own address. */
    fun apiUrl(base: String, path: String, query: Map<String, String> = emptyMap()): String {
        val b = java.net.URI(base.trimEnd('/') + "/")
        val p = path.trim()
        if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(p) || p.startsWith("//") || '\\' in p) throw IllegalArgumentException("Give a path inside the API, like /v1/items, not a whole address.")
        // encoded dots and slashes: some servers decode them after routing, which would step outside the API
        if (Regex("%2e|%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(p)) throw IllegalArgumentException("That path has encoded dots or slashes in it; write them plainly.")
        val u = b.resolve(p.trimStart('/')).normalize()
        if (u.scheme != b.scheme || u.authority != b.authority || !(u.rawPath ?: "").startsWith(b.rawPath ?: "/")) throw IllegalArgumentException("That path leaves the API.")
        if (query.isEmpty()) return u.toString()
        val q = query.entries.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        return u.toString() + (if (u.rawQuery.isNullOrEmpty()) "?" else "&") + q
    }

    // ---------------------------------------------------------------- what needs the user's OK

    class Entry(val server: String, val tool: String, val schema: JSONObject, val readOnly: Boolean, val destructive: Boolean, val trust: String)

    /** A sentence saying what would happen when the user's OK is needed first; null when it can go ahead. */
    fun approval(e: Entry?, name: String, a: JSONObject): String? {
        if (name == CALL_API) { val m = a.optString("method", "GET").uppercase().ifEmpty { "GET" }; return if (m == "GET") null else "$m ${a.optString("path").take(100)} on ${a.optString("connection")} (this changes something there)" }
        e ?: return null
        if (e.trust == "all") return null
        if (e.trust != "ask" && e.readOnly && !e.destructive) return null
        val args = a.toString().let { if (it == "{}") "" else " with ${it.take(200)}" }
        return "Use ${e.server}: ${e.tool}$args"
    }
}
