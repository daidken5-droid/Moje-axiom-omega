package cz.axiom.vega.files

import cz.axiom.vega.base.Tags
import kotlin.test.Test
import kotlin.test.assertEquals

/** Poškozený / podvržený DOCX nebo XLSX (otevřené značky bez konce) nesmí zablokovat VEGA – čtení je lineární. */
class OfficeLinearTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray { val bo = java.io.ByteArrayOutputStream(); java.util.zip.ZipOutputStream(bo).use { z -> e.forEach { (n, t) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }; return bo.toByteArray() }
    private fun ms(b: () -> Unit): Long { val t = System.nanoTime(); runCatching(b); return (System.nanoTime() - t) / 1_000_000 }
    private val ct = "[Content_Types].xml" to "<Types/>"

    @Test fun brokenOfficeFilesAreFast() {
        val n = 150 * 1024
        val cases = mapOf<String, () -> Unit>(
            "XLSX sdílené řetězce bez konce" to { Extract.xlsxRows(zip(ct, "xl/sharedStrings.xml" to "<sst>" + "<si>".repeat(n / 4), "xl/worksheets/sheet1.xml" to "<sheetData><row><c r=\"A1\"><v>1</v></c></row></sheetData>")) },
            "XLSX řádky bez konce" to { Extract.xlsxRows(zip(ct, "xl/worksheets/sheet1.xml" to "<sheetData>" + "<row>".repeat(n / 5))) },
            "XLSX řádky bez >" to { Extract.xlsxRows(zip(ct, "xl/worksheets/sheet1.xml" to "<sheetData>" + "<row".repeat(n / 4))) },
            "DOCX text bez >" to { Extract.extract("a.docx", null, zip(ct, "word/document.xml" to "<w:body>" + "<w:t ".repeat(n / 5))) },
            "ODT značky bez >" to { Extract.extract("a.odt", null, zip(ct, "content.xml" to "<office:text>" + "<text:p a".repeat(n / 9))) },
        )
        val slow = cases.mapNotNull { (k, b) -> ms(b).takeIf { it > 2_000 }?.let { "$k: $it ms" } }
        assertEquals(emptyList(), slow)
    }

    @Test fun xlsxStillReadsCellsAndSelfClosingRows() {
        val sheet = """<sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><v>42</v></c></row><row r="2"/><row r="3"><c r="B3" t="inlineStr"><is><t>Čás</t></is></c><c r="C3"/></row></sheetData>"""
        val rows = Extract.xlsxRows(zip(ct, "xl/sharedStrings.xml" to "<sst><si><t xml:space=\"preserve\">Jídlo</t></si></sst>", "xl/worksheets/sheet1.xml" to sheet))!!
        assertEquals(listOf(listOf("Jídlo", "", "42"), listOf("", "Čás", "")), rows)
    }

    @Test fun tagsHelper() {
        assertEquals(listOf("a", "b"), Tags.blocks("<c>a</c><cols>x</cols><c:x>y</c:x><c r=\"1\">b</c >", "c"))
        assertEquals(listOf("1" to "", "2" to "z"), Tags.elements("<c r=\"1\"/><c r=\"2\">z</c>", "c", selfClosing = true).map { it.attrs.trim().removePrefix("r=\"").removeSuffix("\"") to it.body })
        assertEquals("Cena < 100 Kč a > 50 tučně", Tags.strip("Cena < 100 Kč a > 50 <b>tučně</b>"))
        assertEquals(listOf("ok"), Tags.blocks("<row<row r=\"1\">ok</row>", "row"))
    }
}
