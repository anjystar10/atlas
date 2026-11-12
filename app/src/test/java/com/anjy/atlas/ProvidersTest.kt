package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    @Test fun keysPickTheirService() {
        assertEquals("gemini", Providers.guess("AIzaSyD-example"))
        assertEquals("anthropic", Providers.guess("sk-ant-api03-xyz"))
        assertEquals("openrouter", Providers.guess("sk-or-v1-abc"))
        assertEquals("groq", Providers.guess("gsk_abc"))
        assertEquals("xai", Providers.guess("xai-abc"))
        assertEquals("openai", Providers.guess("sk-proj-abc"))
        assertNull(Providers.guess("hello"))
    }

    @Test fun aKeyLandsOnTheRightService() {
        // a key with a telling shape goes to its own service, whatever page you're on
        assertEquals("groq", Providers.keyTarget("gsk_abcdefghijklmnop", "mistral"))
        assertEquals("anthropic", Providers.keyTarget("sk-ant-api03-xyz", "openai"))
        // an "sk-…" key on another OpenAI-style service's page stays there (DeepSeek, custom and others look like OpenAI's)
        assertEquals("deepseek", Providers.keyTarget("sk-1234567890abcdef", "deepseek"))
        assertEquals("custom", Providers.keyTarget("sk-1234567890abcdef", "custom"))
        // …but on Claude's or Gemini's page it's an OpenAI key
        assertEquals("openai", Providers.keyTarget("sk-proj-1234567890", "anthropic"))
        // no telling shape: the page you're on
        assertEquals("cohere", Providers.keyTarget("abcdefghijklmnopqrstuvwxyz", "cohere"))
        assertEquals("sambanova", Providers.keyTarget("0123456789abcdef0123", "sambanova"))
    }

    @Test fun theNewServicesAreThere() {
        listOf("cerebras", "sambanova", "cohere", "mistral", "zai", "fireworks", "ovh").forEach { assertTrue(it, Providers.byId(it) != null) }
        assertEquals("https://api.cohere.ai/compatibility/v1", Providers.byId("cohere")!!.baseUrl)
        // only one "Other" built in; services the user adds get ids of their own
        assertEquals(1, Providers.builtIn.count { it.custom })
    }

    @Test fun everyServiceIsComplete() {
        Providers.all.forEach { p ->
            assertTrue(p.id, p.style in setOf("gemini", "openai", "anthropic"))
            if (!p.custom) { assertTrue(p.id, p.baseUrl.startsWith("https://")); assertTrue(p.id, p.defaultModel.isNotBlank()); assertTrue(p.id, p.keyUrl.startsWith("https://")) }
            if (p.style != "gemini") assertFalse(p.id, p.baseUrl.endsWith("/"))
        }
        assertEquals(Providers.all.size, Providers.all.map { it.id }.toSet().size)
    }

    @Test fun toolsTranslateToEachDialect() {
        val oa = Providers.openAiTools(Tools.declarations)
        val an = Providers.anthropicTools(Tools.declarations)
        assertEquals(Tools.declarations.length(), oa.length())
        assertEquals(Tools.declarations.length(), an.length())
        for (i in 0 until oa.length()) {
            val f = oa.getJSONObject(i)
            assertEquals("function", f.getString("type"))
            val fn = f.getJSONObject("function")
            // every tool has an object schema, even one with no arguments (OpenAI and Claude require it)
            assertEquals("object", fn.getJSONObject("parameters").getString("type"))
            assertTrue(fn.getJSONObject("parameters").has("properties"))
            assertEquals("object", an.getJSONObject(i).getJSONObject("input_schema").getString("type"))
        }
        val names = (0 until an.length()).map { an.getJSONObject(it).getString("name") }
        assertTrue("read_log" in names)
    }

    @Test fun olderToolScreensAreCutInBothShapes() {
        val screen = "Tapped.\nScreen: X\n" + "[1] Button (tap)\n".repeat(40)
        val oa = listOf(
            JSONObject().put("role", "user").put("content", "go"),
            JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", screen),
            JSONObject().put("role", "tool").put("tool_call_id", "b").put("content", screen),
            JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", screen))
        val out = Providers.trimToolScreens(oa)
        assertEquals("Tapped. (older screen reading left out)", out[1].getString("content"))
        assertEquals(screen, out[3].getString("content"))
        assertEquals(screen, oa[1].getString("content"))
        val an = listOf(
            JSONObject("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"x\",\"content\":${JSONObject.quote(screen)}}]}"),
            JSONObject("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"y\",\"content\":${JSONObject.quote(screen)}}]}"),
            JSONObject("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"z\",\"content\":${JSONObject.quote(screen)}}]}"))
        val out2 = Providers.trimToolScreens(an)
        assertFalse(out2[0].getJSONArray("content").getJSONObject(0).getString("content").contains("[1]"))
        assertEquals(screen, out2[2].getJSONArray("content").getJSONObject(0).getString("content"))
    }

    @Test fun errorsReadPlainly() {
        assertTrue(Providers.explain("Claude", 401, "{\"error\":{\"message\":\"invalid x-api-key\"}}").contains("turned the key down"))
        assertTrue(Providers.explain("OpenAI", 429, "{}").contains("limit"))
        assertTrue(Providers.explain("Groq", 404, "{\"error\":{\"message\":\"model not found\"}}").contains("model"))
    }
}
