package com.anjy.atlas

import com.anjy.atlas.core.Engine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GeminiTest {

    @Test fun requestCarriesInstructionsHistoryAndTools() {
        val req = Gemini.request("be nice", listOf(Gemini.userText("hi")), Tools.declarations, "gemini-3.8-flash")
        assertEquals("be nice", req.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val c = req.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", c.getString("role"))
        assertEquals("hi", c.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(Tools.declarations.length(), req.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").length())
        assertEquals("low", req.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun olderModelsGetNoThinkingLevel() {
        val req = Gemini.request("x", listOf(Gemini.userText("hi")), Tools.declarations, "gemini-2.5-flash")
        assertFalse(req.has("generationConfig"))
    }

    @Test fun toolCallsAreReadAndTheTurnIsKeptExactly() {
        val body = JSONObject("""
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"open_app","args":{"name":"ChatGPT"}},"thoughtSignature":"SIG-1"},
              {"functionCall":{"name":"read_screen","args":{}}}
            ]},"finishReason":"STOP"}]}
        """.trimIndent())
        val t = Gemini.parse(body)
        assertEquals(2, t.calls.size)
        assertEquals("open_app", t.calls[0].name)
        assertEquals("ChatGPT", t.calls[0].args.getString("name"))
        assertEquals("read_screen", t.calls[1].name)
        // the signature must go back to Gemini untouched, in the same place
        assertEquals("SIG-1", t.content.getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals("", t.text)
    }

    @Test fun textAnswerSkipsThoughts() {
        val body = JSONObject("""{"candidates":[{"content":{"role":"model","parts":[{"text":"pondering","thought":true},{"text":"Hey! "},{"text":"All good."}]},"finishReason":"STOP"}]}""")
        val t = Gemini.parse(body)
        assertTrue(t.calls.isEmpty())
        assertEquals("Hey! All good.", t.text)
    }

    @Test fun blockedAndEmptyAnswersAreFailures() {
        try { Gemini.parse(JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")); fail() } catch (e: Gemini.Failure) { assertTrue(e.message!!.contains("SAFETY")) }
        try { Gemini.parse(JSONObject("""{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""")); fail() } catch (e: Gemini.Failure) { assertFalse(e.offline) }
        try { Gemini.parse(JSONObject("""{"candidates":[]}""")); fail() } catch (e: Gemini.Failure) { }
    }

    @Test fun toolResultsAnswerEachCallInOrder() {
        val calls = listOf(Gemini.Call("tap", JSONObject().put("element", 3), "abc"), Gemini.Call("read_screen", JSONObject(), null))
        val turn = Gemini.toolResults(listOf(calls[0] to "Tapped", calls[1] to "Screen: x"))
        assertEquals("user", turn.getString("role"))
        val parts = turn.getJSONArray("parts")
        assertEquals(2, parts.length())
        val fr0 = parts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("tap", fr0.getString("name")); assertEquals("abc", fr0.getString("id")); assertEquals("Tapped", fr0.getJSONObject("response").getString("result"))
        assertFalse(parts.getJSONObject(1).getJSONObject("functionResponse").has("id"))
    }

    @Test fun olderScreenReadingsAreShortenedLatestKept() {
        val screen = "Tapped \"Send\".\nScreen: ChatGPT\n" + "[1] Button \"x\" (tap)\n".repeat(40)
        fun res(n: Int) = Gemini.toolResults(listOf(Gemini.Call("tap", JSONObject().put("element", n), null) to screen))
        val calls = JSONObject("{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"tap\",\"args\":{}},\"thoughtSignature\":\"S\"}]}")
        val contents = listOf(Gemini.userText("go"), calls, res(1), calls, res(2), calls, res(3))
        val out = Gemini.trimScreens(contents)
        fun result(c: JSONObject) = c.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("result")
        assertEquals("Tapped \"Send\". (older screen reading left out)", result(out[2]))
        assertEquals(screen, result(out[4]))
        assertEquals(screen, result(out[6]))
        assertEquals(screen, result(contents[2]))   // the original isn't changed
        assertEquals("S", out[1].getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals(7, out.size)
    }

    @Test fun errorsReadAsPlainSentences() {
        assertTrue(Gemini.explain(429, "{}").contains("limit"))
        assertTrue(Gemini.explain(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""").contains("key isn't valid"))
        assertTrue(Gemini.explain(404, """{"error":{"message":"models/x is not found"}}""").contains("model"))
    }

    /** Every declaration must be a valid function schema, or Gemini refuses the whole request. */
    @Test fun toolDeclarationsAreWellFormed() {
        val names = mutableSetOf<String>()
        val d: JSONArray = Tools.declarations
        for (i in 0 until d.length()) {
            val f = d.getJSONObject(i)
            val name = f.getString("name")
            assertTrue("duplicate $name", names.add(name))
            assertTrue(Regex("^[a-z_]+$").matches(name))
            assertTrue(f.getString("description").isNotBlank())
            // no arguments: no "parameters" (an object without properties is refused)
            val p = f.optJSONObject("parameters") ?: continue
            assertTrue("$name has empty parameters", p.getJSONObject("properties").length() > 0)
            assertEquals("object", p.getString("type"))
            val props = p.getJSONObject("properties")
            props.keys().forEach { k ->
                val prop = props.getJSONObject(k)
                assertTrue("$name.$k type", prop.getString("type") in setOf("string", "integer", "number", "boolean"))
                assertTrue("$name.$k description", prop.getString("description").isNotBlank())
            }
            p.optJSONArray("required")?.let { r -> for (j in 0 until r.length()) assertTrue("$name requires missing ${r.getString(j)}", props.has(r.getString(j))) }
        }
        listOf("set_reminder", "update_reminder", "remember", "forget", "open_app", "read_screen", "tap", "type_text", "scroll", "press_key", "wait").forEach {
            assertTrue("missing $it", it in names)
        }
    }

    @Test fun reminderTimesReadInLocalTime() {
        Engine.zone = ZoneId.of("Africa/Lagos")
        val want = LocalDateTime.of(2026, 11, 12, 16, 0).atZone(Engine.zone).toInstant().toEpochMilli()
        assertEquals(want, Tools.parseWhen("2026-11-12 16:00"))
        assertEquals(want, Tools.parseWhen("2026-11-12T16:00"))
        assertEquals(want, Tools.parseWhen(" 2026-11-12 16:00:00 "))
        assertNull(Tools.parseWhen("next thursday"))
        assertNull(Tools.parseWhen(""))
        assertNull(Tools.parseWhen(null))
    }

    @Test fun requestsOnlyEverGoToGoogleOrThePhoneItself() {
        assertEquals(Gemini.ENDPOINT, Gemini.endpoint(null))
        assertEquals(Gemini.ENDPOINT, Gemini.endpoint(""))
        assertEquals(Gemini.ENDPOINT, Gemini.endpoint("https://evil.example.com/"))
        assertEquals(Gemini.ENDPOINT, Gemini.endpoint("http://10.0.2.2:8765/"))
        assertEquals("http://127.0.0.1:8765/v1beta/models/", Gemini.endpoint("http://127.0.0.1:8765/v1beta/models/"))
    }

    @Test fun wakeWordStageOneUsesOnlyFinishedUtterances() {
        assertTrue(Hotword.listenerHeard("{\"text\" : \"hey atlas\"}"))
        assertTrue(Hotword.listenerHeard("{\"text\" : \"[unk] hey atlas [unk]\"}"))
        assertFalse(Hotword.listenerHeard("{\"text\" : \"[unk]\"}"))
        assertFalse(Hotword.listenerHeard("{\"partial\" : \"hey atlas\"}"))       // partials never wake it
        assertFalse(Hotword.listenerHeard("not json"))
        assertFalse(Hotword.listenerHeard(null))
        assertEquals("[\"hey atlas\",\"[unk]\"]", Hotword.grammar(Hotword.LISTEN))
    }

    @Test fun wakeWordStageTwoNeedsTheRealWord() {
        fun t(s: String) = "{\"text\" : \"$s\"}"
        assertTrue(Hotword.checkPasses(t("hey atlas")))
        assertTrue(Hotword.checkPasses(t("the atlas")))
        assertFalse(Hotword.checkPasses(t("hey dallas")))
        assertFalse(Hotword.checkPasses(t("hey at last")))
        assertFalse(Hotword.checkPasses(t("hey alice")))
        assertFalse(Hotword.checkPasses(t("atlantic")))
        assertFalse(Hotword.checkPasses(t("")))
        assertFalse(Hotword.checkPasses(null))
    }
}
