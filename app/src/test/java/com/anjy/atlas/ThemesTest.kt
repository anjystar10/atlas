package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The colour themes (tools/themes.json) clear WCAG AA in both looks, and the phone's ThemeData.kt says exactly what
 * the file says. The same numbers are checked on the PC (desktop/test) and by tools/gen_themes.py --check.
 */
class ThemesTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/themes.json").exists()) dir = dir.parentFile
        return dir!!
    }
    private val data by lazy { JSONObject(File(root(), "tools/themes.json").readText()) }

    private fun rgb(hex: String) = hex.removePrefix("#").toInt(16) or (0xFF shl 24)
    private fun ratio(a: String, b: String): Double {
        val x = luminance(rgb(a)); val y = luminance(rgb(b))
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private val text = listOf("fg", "muted", "faint", "accentInk", "blue", "warm", "done", "danger")
    private val grounds = listOf("bg", "surface", "surface2")

    @Test fun everyTextColourClearsAA() {
        val bad = mutableListOf<String>()
        val themes = data.getJSONArray("themes")
        assertEquals(4, themes.length())
        for (i in 0 until themes.length()) {
            val t = themes.getJSONObject(i)
            for (mode in listOf("dark", "light")) {
                val p = t.getJSONObject(mode)
                for (k in text) for (g in grounds) {
                    val r = ratio(p.getString(k), p.getString(g))
                    if (r < 4.5) bad += "${t.getString("id")} $mode $k on $g = ${"%.2f".format(r)}"
                }
                for (g in listOf("primaryTop", "primaryBottom")) {
                    val r = ratio(p.getString("onPrimary"), p.getString(g))
                    if (r < 4.5) bad += "${t.getString("id")} $mode onPrimary on $g = ${"%.2f".format(r)}"
                }
                val r = ratio(p.getString("accent"), p.getString("bg"))
                if (r < 3.0) bad += "${t.getString("id")} $mode accent on bg = ${"%.2f".format(r)}"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun phoneThemeDataMatchesTheFile() {
        val kt = File(root(), "app/src/main/java/com/anjy/atlas/ThemeData.kt").readText().uppercase()
        val themes = data.getJSONArray("themes")
        for (i in 0 until themes.length()) {
            val t = themes.getJSONObject(i)
            assertTrue(t.getString("id"), kt.contains("\"${t.getString("id").uppercase()}\""))
            for (mode in listOf("dark", "light")) {
                val p = t.getJSONObject(mode)
                for (k in p.keys()) {
                    val v = p.get(k)
                    if (v is String && v.startsWith("#")) assertTrue("${t.getString("id")} $mode $k $v", kt.contains(v.uppercase()))
                }
            }
        }
        assertTrue(kt.contains("DEFAULT = \"MIDNIGHT\""))
    }

    @Test fun inkOnPicksTheReadableInk() {
        // bright gold and coral take dark ink; deep indigo and navy take white
        assertEquals(0xFF0B0B10.toInt(), inkOn(rgb("#E8B65A")))
        assertEquals(0xFF0B0B10.toInt(), inkOn(rgb("#FF7A5C")))
        assertEquals(0xFFFFFFFF.toInt(), inkOn(rgb("#26304C")))
        assertEquals(0xFFFFFFFF.toInt(), inkOn(rgb("#4A32C8")))
    }

    @Test fun blendIsLinearPerChannel() {
        assertEquals(0xFF7F7F7F.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))   // 127.5 rounds down
        assertEquals(0xFF102030.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
    }
}
