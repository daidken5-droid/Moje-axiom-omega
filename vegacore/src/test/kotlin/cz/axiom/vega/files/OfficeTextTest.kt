package cz.axiom.vega.files
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN9: text z Wordu / ODT bez slepených slov – tabulátor, zalomení, buňky tabulky, číselné entity, mezery v ODT. */
class OfficeTextTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray { val o = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { z -> e.forEach { (n, c) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }; return o.toByteArray() }
    @Test fun docx() {
        val body = """<w:document><w:body><w:p><w:r><w:t>Jméno:</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Jan Novák</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>Ulice 1</w:t><w:br/><w:t>Praha</w:t></w:r></w:p>""" +
            """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cena</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Množství</w:t></w:r></w:p></w:tc></w:tr>""" +
            """<w:tr><w:tc><w:p><w:r><w:t>250 Kč</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
            """<w:p><w:r><w:t xml:space="preserve">2020&#8211;2026 &amp; dál</w:t></w:r></w:p></w:body></w:document>"""
        val t = Extract.extract("smlouva.docx", null, zip("word/document.xml" to body))!!.text
        assertTrue(t, t.contains("Jméno:\tJan Novák") && t.contains("Ulice 1\nPraha") && t.contains("2020–2026 & dál"))
        assertTrue(t, t.contains("Cena\tMnožství") && t.contains("250 Kč\t3") && !t.contains("CenaMnožství"))
    }
    @Test fun odt() {
        val c = """<office:document-content><office:body><office:text><text:p>Dobrý<text:s/>den,<text:line-break/>druhý řádek</text:p>""" +
            """<table:table><table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell><text:p>B</text:p></table:table-cell></table:table-row></table:table></office:text></office:body></office:document-content>"""
        val t = Extract.extract("dopis.odt", null, zip("content.xml" to c))!!.text
        assertTrue(t, t.contains("Dobrý den,\ndruhý řádek") && t.contains("A\tB"))
    }
}

/** Noc 24./25. 11., kolo NN10: XLSX – vložený text po částech (inlineStr), čísla jako v Excelu, logické hodnoty. */
class XlsxCellsTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray { val o = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { z -> e.forEach { (n, c) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }; return o.toByteArray() }
    @Test fun cells() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Datum</t></is></c><c r="B1" t="inlineStr"><is><r><t>Čás</t></r><r><t>tka</t></r></is></c><c r="C1" t="inlineStr"><is><t>Popis</t></is></c></row>""" +
            """<row r="2"><c r="A2"><v>46328</v></c><c r="B2"><v>-312.39999999999998</v></c><c r="C2" t="inlineStr"><is><t xml:space="preserve">Lidl &amp; spol</t></is></c><c r="D2" t="b"><v>1</v></c><c r="E2"><v>1.5E-2</v></c><c r="F2"><v>0.1</v></c></row></sheetData></worksheet>"""
        val rows = Extract.xlsxRows(zip("xl/worksheets/sheet1.xml" to sheet))!!
        org.junit.Assert.assertEquals(listOf("Datum", "Částka", "Popis"), rows[0])
        org.junit.Assert.assertEquals(listOf("46328", "-312.4", "Lidl & spol", "PRAVDA", "0.015", "0.1"), rows[1])
        // celý řetězec: výpis z banky v takovém XLSX se rozpozná a sečte
        val conv = cz.axiom.vega.core.Statement.fromXlsxRows(rows)!!
        val r = cz.axiom.vega.core.Statement.process(conv.csv, cz.axiom.vega.core.Statement.detect(conv.csv).spec!!)
        org.junit.Assert.assertEquals(-312.4, r.expense, 0.001)
    }
}
