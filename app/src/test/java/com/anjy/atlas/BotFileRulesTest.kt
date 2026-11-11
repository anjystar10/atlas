package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class BotFileRulesTest {
    private fun unzip(b: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return out
    }
    private fun xml(s: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(s.toByteArray()))

    @Test fun fileNamesStayInTheFolder() {
        assertEquals("evil.txt", BotFileRules.fileName("../../evil.txt"))
        assertEquals("evil.txt", BotFileRules.fileName("..\\..\\evil.txt"))
        assertEquals("notes.md", BotFileRules.fileName("/data/data/com.anjy.atlas/notes.md"))
        assertEquals("a b.csv", BotFileRules.fileName("a:b.csv"))
        assertEquals("file.txt", BotFileRules.fileName(""))
        assertEquals("file.txt", BotFileRules.fileName("..."))
        assertEquals("Report.pdf", BotFileRules.fileName("Report", "pdf"))
        assertEquals("Report.PDF".lowercase().substringAfter('.'), BotFileRules.fileName("Report.PDF").substringAfter('.'))
        assertFalse(BotFileRules.fileName("x/../../y.html").contains("/"))
    }

    @Test fun freshNamesNeverOverwrite() {
        val taken = setOf("Report.pdf", "Report (2).pdf")
        assertEquals("Report (3).pdf", BotFileRules.freshName("Report.pdf") { it in taken })
        assertEquals("Other.pdf", BotFileRules.freshName("Other.pdf") { it in taken })
    }

    @Test fun csvQuotesWhereNeededAndReadsBack() {
        val rows = listOf(listOf("Name", "Note"), listOf("Pixel, 9a", "says \"hi\""), listOf("two\nlines", " padded "))
        val csv = BotFileRules.toCsv(rows)
        assertEquals("Name,Note\r\n\"Pixel, 9a\",\"says \"\"hi\"\"\"\r\n\"two\nlines\",\" padded \"\r\n", csv)
        assertEquals(rows, BotFileRules.parseCsv(csv))
    }

    @Test fun excelFileIsValidWithNumbersTextAndFormulas() {
        val book = BotFileRules.xlsx(listOf("Prices" to listOf(listOf("Phone", "Price", "Qty", "Total"), listOf("Pixel <9a> & co", "499", "2", "=B2*C2"), listOf("007", "1e3", "", "=SUM(D2:D2)"))))
        val parts = unzip(book)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"), parts.keys)
        parts.values.forEach { xml(it) }   // every part is well-formed XML
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<c r=\"A2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Pixel &lt;9a&gt; &amp; co</t></is></c>"))
        assertTrue(sheet.contains("<c r=\"B2\"><v>499</v></c>"))
        assertTrue(sheet.contains("<c r=\"D2\"><f>B2*C2</f></c>"))
        assertTrue("a leading zero stays text", sheet.contains("<c r=\"A3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">007</t></is></c>"))
        assertTrue(sheet.contains("<c r=\"B3\"><v>1e3</v></c>"))
        assertFalse("empty cells are left out", sheet.contains("r=\"C3\""))
        assertTrue("header is bold", sheet.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\">"))
        assertTrue(parts.getValue("xl/workbook.xml").contains("<sheet name=\"Prices\" sheetId=\"1\" r:id=\"rId1\"/>"))
    }

    @Test fun columnLetters() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map { BotFileRules.col(it) })
    }

    @Test fun sheetsFromTheBotsArguments() {
        val one = BotFileRules.sheetsFrom("Phones", JSONObject().put("columns", JSONArray(listOf("A", "B"))).put("rows", JSONArray().put(JSONArray().put("x").put(2.0)).put(JSONArray().put("y").put(2.5))))
        assertEquals(listOf("Phones" to listOf(listOf("A", "B"), listOf("x", "2"), listOf("y", "2.5"))), one)
        val two = BotFileRules.sheetsFrom("Book", JSONObject().put("sheets", JSONArray()
            .put(JSONObject().put("name", "S1").put("columns", JSONArray(listOf("C"))).put("rows", JSONArray().put(JSONArray().put("1"))))
            .put(JSONObject().put("name", "S1").put("csv", "h1,h2\n1,2\n"))))
        assertEquals(2, two.size); assertEquals(listOf(listOf("h1", "h2"), listOf("1", "2")), two[1].second)
        val names = unzip(BotFileRules.xlsx(two)).getValue("xl/workbook.xml")
        assertTrue("same sheet names get told apart", names.contains("name=\"S1\"") && names.contains("name=\"S1 2\""))
    }

    @Test fun pageDataCantBreakOutOfItsScript() {
        val tpl = "<title>Atlas Page</title><script type=\"application/json\">/*ATLAS_PAGE_DATA*/null</script>"
        val spec = BotFileRules.cleanSpec("A <b>title</b>", "</script><script>alert(1)</script>", JSONArray().put(JSONObject().put("columns", JSONArray(listOf("a"))).put("rows", JSONArray().put(JSONArray().put("</script>")))), null)
        val html = BotFileRules.pageHtml(tpl, spec)
        assertEquals(1, Regex("</script>").findAll(html).count())
        assertTrue(html.contains("<title>A &lt;b&gt;title&lt;/b&gt;</title>"))
        assertTrue(html.contains("\\u003c/script>"))
        // the data is still the same JSON once read back
        val data = html.substringAfter("application/json\">").substringBefore("</script>").replace("\\u003c", "<")
        assertEquals("</script>", JSONObject(data).getJSONArray("sections").getJSONObject(0).getJSONArray("rows").getJSONArray(0).getString(0))
    }

    @Test fun pageSpecIsCappedAndChartsCleaned() {
        val rows = JSONArray(); repeat(6000) { rows.put(JSONArray().put("r$it")) }
        val spec = BotFileRules.cleanSpec("T", "", JSONArray().put(JSONObject().put("columns", JSONArray(listOf("a"))).put("rows", rows)),
            JSONArray().put(JSONObject().put("type", "donut").put("labels", JSONArray(listOf("x"))).put("series", JSONArray().put(JSONObject().put("values", JSONArray().put("abc").put(3))))))
        val s = spec.getJSONArray("sections")
        assertEquals(5000, s.getJSONObject(0).getJSONArray("rows").length())
        assertEquals("bar", s.getJSONObject(1).getString("kind"))
        assertEquals(0.0, s.getJSONObject(1).getJSONArray("series").getJSONObject(0).getJSONArray("values").getDouble(0), 0.0)
    }

    @Test fun tablesAreTakenOutOfTheMarkdownForTheGrid() {
        val parts = BotFileRules.split("## Title\n\nIntro.\n\n| Phone | Price |\n|---|---:|\n| Pixel | $499 |\n| Galaxy | $449 |\n\nAfter.\n\n```\n| not | a table |\n|---|---|\n```\n")
        assertEquals(3, parts.size)
        assertTrue((parts[0] as BotFileRules.Part.Text).md.contains("Intro."))
        assertEquals(listOf(listOf("Phone", "Price"), listOf("Pixel", "$499"), listOf("Galaxy", "$449")), (parts[1] as BotFileRules.Part.Table).rows)
        assertTrue("a table inside a code block stays code", (parts[2] as BotFileRules.Part.Text).md.contains("| not | a table |"))
    }

    @Test fun reportFooterNamesTheFiles() {
        assertEquals("", BotFileRules.footer(emptyList()))
        assertEquals("\n\n**Files:** a.pdf, b.xlsx (open them in Bots › Library).", BotFileRules.footer(listOf("a.pdf", "b.xlsx")))
    }

    @Test fun thePhonesPageTemplateIsTheSameFileAsThePcs() {
        // run from app/: the asset and the PC's template must not drift apart
        val phone = File("src/main/assets/page.html"); val pc = File("../desktop/renderer/templates/page.html")
        if (phone.exists() && pc.exists()) assertEquals(pc.readText(), phone.readText())
        assertTrue(phone.exists())
        assertTrue(phone.readText().contains("/*ATLAS_PAGE_DATA*/null"))
    }
}
