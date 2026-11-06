package com.anjy.atlas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Atlas on the internet, directly (without opening a browser on the phone): search the web and read pages.
 * Search uses Google Search through Gemini when there's a Gemini key (the best results, with sources), and
 * DuckDuckGo otherwise. Pages come back as plain readable text.
 */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 Atlas"
    private const val PAGE_CHARS = 12_000

    fun search(ctx: Context, query: String): String {
        val q = query.trim().ifEmpty { return "Error: say what to search for." }
        Providers.key(ctx, "gemini")?.let { key ->
            try { return googleViaGemini(ctx, key, q) } catch (e: Exception) { android.util.Log.w("AtlasWeb", "Google search via Gemini failed", e) }
        }
        return duckDuckGo(q)
    }

    /** Gemini with its Google Search tool: an up-to-date answer plus the pages it came from. */
    private fun googleViaGemini(ctx: Context, key: String, q: String): String {
        val model = Providers.all.firstOrNull { it.id == "gemini" }?.let { Providers.model(ctx, it) } ?: "gemini-3.8-flash"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
                "Search the web and report what answers this, with dates where they matter. Facts only, concise: $q")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val r = Providers.post(url, mapOf("x-goog-api-key" to key), body, "Web search")
        val cand = r.optJSONArray("candidates")?.optJSONObject(0) ?: throw IllegalStateException("no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) parts.optJSONObject(i)?.optString("text")?.let { text.append(it) }
        val sources = StringBuilder()
        cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until minOf(chunks.length(), 8)) {
                val w = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                sources.append("\n- ").append(w.optString("title")).append(" — ").append(w.optString("uri"))
            }
        }
        if (text.isBlank()) throw IllegalStateException("empty answer")
        return "Search results for \"$q\" (Google):\n$text" + if (sources.isNotEmpty()) "\nSources:$sources" else ""
    }

    /** DuckDuckGo's plain HTML results: titles, links and snippets. */
    private fun duckDuckGo(q: String): String {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).second
        val links = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { strip(it.groupValues[1]) }.toList()
        if (links.isEmpty()) return "No results found for \"$q\"."
        val sb = StringBuilder("Search results for \"$q\":")
        links.take(6).forEachIndexed { i, m ->
            var href = m.groupValues[1].replace("&amp;", "&")
            Regex("""uddg=([^&]+)""").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            sb.append("\n${i + 1}. ").append(strip(m.groupValues[2])).append(" — ").append(href)
            snippets.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { sb.append("\n   ").append(it) }
        }
        sb.append("\nUse read_page on a link to read it.")
        return sb.toString()
    }

    /** A web page as readable text (title, then the words), cut to a sensible length. */
    fun read(url: String): String {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val (type, raw) = get(u)
        if (!type.contains("html") && !type.contains("text") && !type.contains("json") && type.isNotEmpty()) return "That link is a $type file, not a page I can read."
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(raw)?.groupValues?.get(1)?.let { strip(it) }
        val main = Regex("""<(main|article)[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(raw)?.groupValues?.get(2) ?: raw
        var text = if (type.contains("html") || raw.trimStart().startsWith("<")) readable(main) else raw
        if (text.length > PAGE_CHARS) text = text.take(PAGE_CHARS) + "\n(page continues; ask to read more if needed)"
        return (if (title.isNullOrBlank()) "" else "Title: $title\n") + "URL: $u\n\n" + text.ifBlank { "(no readable text on this page)" }
    }

    private fun get(url: String, hops: Int = 0): Pair<String, String> {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.instanceFollowRedirects = true
            con.connectTimeout = 12_000; con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            con.setRequestProperty("Accept-Language", "en")
            val code = con.responseCode
            if (code in 300..399 && hops < 5) con.getHeaderField("Location")?.let { return get(URL(URL(url), it).toString(), hops + 1) }
            if (code !in 200..299) throw IllegalStateException("the site answered $code")
            val type = con.contentType ?: ""
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1) ?: "UTF-8"
            val bytes = con.inputStream.use { i -> val b = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var n: Int; var total = 0
                while (i.read(buf).also { n = it } > 0 && total < 3_000_000) { b.write(buf, 0, n); total += n }; b.toByteArray() }
            return type.lowercase() to String(bytes, try { charset(charset) } catch (_: Exception) { Charsets.UTF_8 })
        } finally { con.disconnect() }
    }

    /** HTML to plain text, keeping paragraph and list breaks. */
    internal fun readable(html: String): String {
        var t = html.replace(Regex("""<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        t = t.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        t = t.replace(Regex("""<\s*(br|/p|/div|/h[1-6]|/tr|/section|/article)\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("""<\s*li[^>]*>""", RegexOption.IGNORE_CASE), "\n- ")
        t = t.replace(Regex("""<\s*h([1-6])[^>]*>""", RegexOption.IGNORE_CASE), "\n\n")
        return strip(t).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }

    private fun strip(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[ \t ]+"), " ").trim()
}
