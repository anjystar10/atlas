package com.anjy.atlas

/**
 * Keeps the machinery out of sight. Whatever Atlas shows, says or saves goes through here, so you never see
 * internal ids, tool calls, JSON or model tokens: only plain language (and real code only when you asked for code).
 */
object Plain {
    private val TOOLS = listOf(
        "set_reminder", "update_reminder", "remember", "forget", "read_log", "open_app", "open_link", "read_screen",
        "tap", "long_press", "type_text", "scroll", "press_key", "wait", "web_search", "read_page", "change_setting",
        "start_focus", "default_api", "tool_code", "tool_call", "function_call"
    )
    private val UUID = Regex("""\[?\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b]?""")
    private val TOKENS = Regex("""<\|[^|>]{1,40}\|>|</?s>|<think>[\s\S]*?</think>|</?(start_of_turn|end_of_turn)>""")
    private val WHAT_I_DID = Regex("""\(What I did:[^)]*\)""")
    private val FENCE = Regex("""```([a-zA-Z_]*)\n?([\s\S]*?)```""")
    private val TOOL_LINE = Regex("""^\s*(print\()?\s*(default_api\.)?(${TOOLS.joinToString("|")})\s*\(.*$""")

    /** True when the text is a tool call written out (a model sometimes prints one instead of making it). */
    private fun looksLikeToolCall(code: String): Boolean {
        val c = code.trim()
        if (TOOLS.any { c.contains("$it(") || c.contains("\"$it\"") }) return true
        if (c.startsWith("{") && (c.contains("\"name\"") || c.contains("\"args\"") || c.contains("\"arguments\""))) return true
        return false
    }

    /** A reply as the user sees and hears it. Real code they asked for stays; tool calls and ids go. */
    fun reply(s: String): String {
        var t = s.replace(TOKENS, "").replace(WHAT_I_DID, "")
        t = FENCE.replace(t) { m ->
            val lang = m.groupValues[1].lowercase()
            if (lang == "tool_code" || lang == "tool_call" || looksLikeToolCall(m.groupValues[2])) "" else m.value
        }
        t = t.lines().filterNot { TOOL_LINE.matches(it) }.joinToString("\n")
        t = t.replace(UUID, "")
        // tidy what removal left behind: "Saved memory ." / doubled spaces / runs of blank lines
        t = t.replace(Regex("""[ \t]+([.,;:!?])"""), "$1").replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n")
        return t.trim()
    }

    /** Text worth keeping in memory: one plain sentence, never code or JSON. */
    fun memory(s: String): String {
        var t = reply(s)
        if (t.trimStart().startsWith("{")) {
            // a JSON object: keep only its words
            t = Regex("\"[^\"]*\"\\s*:\\s*\"([^\"]*)\"").findAll(t).map { it.groupValues[1] }.joinToString(". ")
        }
        t = t.replace(FENCE) { it.groupValues[2] }.replace("`", "").replace(Regex("""[{}\[\]<>]"""), "")
        t = t.replace(Regex("""\s+"""), " ").trim().trim('"', '\'')
        return t.replaceFirstChar { it.uppercase() }
    }

    /** For speaking aloud: no symbols, no code blocks, list markers turned into pauses. */
    fun spoken(s: String): String {
        var t = reply(s).replace(FENCE, " There's some code on screen. ")
        // links: say the words, not the address; a bare address becomes "a link"
        t = t.replace(Regex("""!?\[([^\]]*)]\((?:[^()\s]|\([^()\s]*\))*\)"""), "$1")
        t = t.replace(Regex("""(https?://|www\.)\S+"""), "a link")
        // tables: the divider row goes; cells are read with pauses
        t = t.lines().filterNot { it.trim().matches(Regex("""\|?[\s:|-]*-{2,}[\s:|-]*\|?""")) }.joinToString("\n")
        val lines = t.lines().map { it.trim().trimStart('#', '>', ' ').trim('|', ' ').replace(Regex("""\s*\|\s*"""), ", ").replace(Regex("""^([-*•]|\d+[.)])\s+"""), "") }.filter { it.isNotBlank() }
        // each line is its own sentence: add a full stop only where there's no ending already
        t = lines.joinToString(" ") { l -> if (l.last() in ".!?…:;,") (if (l.last() == ':') l.dropLast(1) + "." else l) else "$l." }
        t = t.replace(Regex("""[*_#`~^\\]"""), "").replace(Regex("""\s*[–—]\s*"""), ", ").replace("&", " and ").replace(Regex("""(\d)\s*%"""), "$1 percent")
            .replace(Regex("""\(\s*\)"""), "").replace(Regex("""\.\s*\.+"""), ".").replace(Regex(""",\s*([.,!?])"""), "$1").replace(Regex("""\s+"""), " ")
        return t.trim()
    }
}
