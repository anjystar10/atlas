package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Connections on the phone (McpWire.kt). The server answers below were recorded from a real MCP server made with the
 * official SDK (desktop/test/fixtures/mcp-server.js, the one the PC's end-to-end test uses).
 */
class McpWireTest {
    private val initSse = "event: message\ndata: {\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"atlas-test\",\"version\":\"1.0.0\"}},\"jsonrpc\":\"2.0\",\"id\":1}\n\n"
    private val pictureSse = "event: message\ndata: {\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"here it is\"},{\"type\":\"image\",\"data\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==\",\"mimeType\":\"image/png\"}]},\"jsonrpc\":\"2.0\",\"id\":3}\n\n"
    private val toolsSse: String = javaClass.classLoader!!.getResource("mcp-tools-list.sse")!!.readText()

    @Test fun readsTheServersRealAnswers() {
        val init = McpWire.answerFor("text/event-stream", initSse, 1)!!
        assertEquals("2025-06-18", init.getJSONObject("result").getString("protocolVersion"))
        assertNull("another id isn't this answer", McpWire.answerFor("text/event-stream", initSse, 2))
        val list = McpWire.answerFor("text/event-stream", toolsSse, 2)!!.getJSONObject("result").getJSONArray("tools")
        val tools = List(list.length()) { McpWire.Tool.fromMcp(list.getJSONObject(it)) }
        assertEquals(listOf("add", "save_note", "picture", "big", "fails", "shape"), tools.map { it.name })
        assertTrue(tools.first { it.name == "add" }.readOnly)
        assertTrue(tools.first { it.name == "save_note" }.destructive)
        val out = McpWire.readResult(McpWire.answerFor("text/event-stream", pictureSse, 3)!!.getJSONObject("result"))
        assertEquals("here it is", out.text); assertEquals("image/png", out.imageMime); assertTrue(out.imageBase64!!.startsWith("iVBOR"))
    }

    @Test fun readsPlainJsonListsAndMultiLineEvents() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}"
        assertTrue(McpWire.answerFor("application/json", json, 7)!!.getJSONObject("result").getBoolean("ok"))
        val batch = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"},{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{}}]"
        assertNotNull(McpWire.answerFor("application/json", batch, 8))
        // a progress notice first, then the answer split over two data lines (allowed by SSE)
        val sse = "event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\r\n\r\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\ndata: \"id\":9,\"result\":{\"x\":1}}\n\n"
        assertEquals(1, McpWire.answerFor("text/event-stream; charset=utf-8", sse, 9)!!.getJSONObject("result").getInt("x"))
        val err = McpWire.answerFor("application/json", "{\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-32602,\"message\":\"bad\"}}", 5)!!
        assertEquals("bad", err.getJSONObject("error").getString("message"))
    }

    @Test fun toolResultsAreCappedAndErrorsMarked() {
        val big = McpWire.readResult(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(50_000)))))
        assertTrue(big.text.length < McpWire.MAX_OUT + 100 && big.text.endsWith("(cut short: the answer was longer)"))
        val e = McpWire.readResult(JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "went wrong"))))
        assertTrue(e.error); assertEquals("went wrong", e.text)
        assertEquals("{\"n\":1}", McpWire.readResult(JSONObject().put("structuredContent", JSONObject().put("n", 1))).text)
    }

    @Test fun schemasKeepOnlyWhatEveryServiceAccepts() {
        val list = McpWire.answerFor("text/event-stream", toolsSse, 2)!!.getJSONObject("result").getJSONArray("tools")
        val shape = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("name") == "shape" }.getJSONObject("inputSchema")
        val s = McpWire.simpleSchema(shape)
        val allowed = setOf("type", "description", "enum", "properties", "required", "items", "nullable")
        fun walk(o: JSONObject, at: String) {
            o.keys().forEach { k -> assertTrue("$at: \"$k\" isn't accepted by Gemini", k in allowed) }
            o.optJSONObject("properties")?.let { p -> p.keys().forEach { walk(p.getJSONObject(it), "$at.$it") } }
            o.optJSONObject("items")?.let { walk(it, "$at[]") }
        }
        walk(s, "shape")
        assertTrue(s.getJSONObject("properties").getJSONObject("where").getJSONObject("properties").getJSONObject("zip").getBoolean("nullable"))
        assertEquals(JSONArray(listOf("fast", "slow")).toString(), s.getJSONObject("properties").getJSONObject("mode").getJSONArray("enum").toString())
        assertEquals("an open object becomes JSON text", "string", s.getJSONObject("properties").getJSONObject("extra").getString("type"))
        // references, allOf and either/or
        val r = McpWire.simpleSchema(JSONObject("""{"type":"object","${'$'}defs":{"Pt":{"type":"object","properties":{"x":{"type":"number"}},"required":["x"]}},
            "properties":{"at":{"${'$'}ref":"#/${'$'}defs/Pt"},"kind":{"anyOf":[{"type":"string","enum":["a","b"]},{"type":"null"}]},
            "both":{"allOf":[{"type":"object","properties":{"p":{"type":"string"}}},{"properties":{"q":{"type":"boolean"}},"required":["q"]}]}},"required":["at","ghost"]}"""))
        assertEquals("number", r.getJSONObject("properties").getJSONObject("at").getJSONObject("properties").getJSONObject("x").getString("type"))
        assertTrue(r.getJSONObject("properties").getJSONObject("kind").getBoolean("nullable"))
        assertEquals(setOf("p", "q"), r.getJSONObject("properties").getJSONObject("both").getJSONObject("properties").keys().asSequence().toSet())
        assertEquals("[\"at\"]", r.getJSONArray("required").toString())
        assertFalse(r.toString().contains("$"))
    }

    @Test fun argumentsGoBackToTheServersShapes() {
        val schema = JSONObject("""{"type":"object","properties":{"n":{"type":"integer"},"on":{"type":"boolean"},"extra":{"type":"object","additionalProperties":{}},"list":{"type":"array","items":{"type":"number"}}}}""")
        val out = McpWire.coerceArgs(schema, JSONObject("""{"n":"5","on":"true","extra":"{\"floor\":3}","list":["1","2.5"]}""")) as JSONObject
        assertEquals(5L, out.get("n")); assertEquals(true, out.get("on"))
        assertEquals(3, out.getJSONObject("extra").getInt("floor"))
        assertEquals(2.5, out.getJSONArray("list").getDouble(1), 0.0)
    }

    @Test fun namesFitEveryService() {
        assertEquals("mcp__notes__add", McpWire.toolName("notes", "add"))
        assertEquals("mcp__my_files__read_file", McpWire.toolName("my-files", "read.file"))
        val a = McpWire.toolName("server", "a".repeat(80) + "_one"); val b = McpWire.toolName("server", "a".repeat(80) + "_two")
        assertTrue(a.length <= 64 && b.length <= 64 && a != b && Regex("^[A-Za-z0-9_]+$").matches(a))
    }

    @Test fun approvalsFollowTheTrustSetting() {
        fun e(trust: String, ro: Boolean, de: Boolean = false) = McpWire.Entry("notes", "save", JSONObject(), ro, de, trust)
        val args = JSONObject().put("text", "hi")
        assertNull(McpWire.approval(e("reads", ro = true), "mcp__notes__save", args))
        assertNotNull(McpWire.approval(e("reads", ro = false), "mcp__notes__save", args))
        assertNotNull("read-only AND destructive asks", McpWire.approval(e("reads", ro = true, de = true), "mcp__notes__save", args))
        assertNotNull(McpWire.approval(e("ask", ro = true), "mcp__notes__save", args))
        assertNull(McpWire.approval(e("all", ro = false), "mcp__notes__save", args))
        assertNull(McpWire.approval(null, McpWire.CALL_API, JSONObject().put("method", "GET").put("path", "/x")))
        assertTrue(McpWire.approval(null, McpWire.CALL_API, JSONObject().put("method", "DELETE").put("path", "/items/1").put("connection", "shop"))!!.startsWith("DELETE /items/1 on shop"))
    }

    @Test fun webApiPathsCantLeaveTheApi() {
        assertEquals("https://api.example.com/v1/items", McpWire.apiUrl("https://api.example.com/v1", "/items"))
        assertEquals("https://api.example.com/v1/items?limit=5", McpWire.apiUrl("https://api.example.com/v1/", "items?limit=5"))
        assertEquals("https://api.example.com/v1/items?a=1&b=x+y", McpWire.apiUrl("https://api.example.com/v1", "items?a=1", mapOf("b" to "x y")))
        for (p in listOf("https://evil.example/x", "//evil.example/x", "../../admin", "/..%2f..%2fadmin", "http:evil", "a\\b")) {
            try { McpWire.apiUrl("https://api.example.com/v1", p); fail("$p was allowed") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun pastedSetups() {
        val c = McpWire.parseConfig("""{"mcpServers":{"my files":{"command":"npx","args":["-y","x"]},"web":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer t"}}}}""")
        assertEquals(setOf("my_files", "web"), c.map { it.name }.toSet())   // org.json keeps no key order
        val prog = c.first { it.name == "my_files" }; val web = c.first { it.name == "web" }
        assertNull("a program on a computer: no address", prog.url); assertEquals("npx", prog.command)
        assertEquals("Bearer t", web.headers["Authorization"])
        assertEquals("server", McpWire.parseConfig("""{"url":"https://example.com/mcp"}""").single().name)
        try { McpWire.parseConfig("nope"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("valid JSON")) }
        try { McpWire.parseConfig("""{"mcpServers":{"x":{}}}"""); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("No servers")) }
    }

    @Test fun connectionKindsForTheApprovalRules() {
        assertEquals("delete", BotExtras.Rules.connectionCategory(McpWire.CALL_API, JSONObject().put("method", "DELETE").put("path", "/x")))
        assertEquals("purchase", BotExtras.Rules.connectionCategory(McpWire.CALL_API, JSONObject().put("method", "POST").put("path", "/v1/orders")))
        assertEquals("message", BotExtras.Rules.connectionCategory("mcp__gmail__send_email", null))
        assertEquals("publish", BotExtras.Rules.connectionCategory("mcp__slack__post_message", null))
        assertNull(BotExtras.Rules.connectionCategory("run_command", null))
    }
}
