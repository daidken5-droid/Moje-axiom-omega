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
