package cz.axiom.omega.core

import cz.axiom.omega.core.files.Extract
import cz.axiom.omega.core.forge.FileForge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun word() {
        val r = Extract.extract("smlouva.docx", null, FileForge.docx("Nabídka", "# Úvod\nCena **12 500 Kč** & DPH\n- bod jedna"))!!
        assertEquals(Extract.Kind.DOCX, r.kind)
        assertTrue(r.text, r.text.contains("Nabídka") && r.text.contains("Cena 12 500 Kč & DPH") && r.text.contains("• bod jedna"))
    }

    @Test fun excel() {
        val x = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Rozpočet" sheetId="1"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Položka</t></si><si><t>Cena</t></si><si><r><t>Ob</t></r><r><t>klad</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c t="s"><v>2</v></c><c><v>650.5</v></c></row></sheetData></worksheet>""",
        )
        val r = Extract.extract("a.xlsx", null, x)!!
        assertEquals("=== List Rozpočet ===\nPoložka;Cena\nObklad;650.5", r.text)
    }

    @Test fun powerpointOdtHtmlRtfZip() {
        val p = zip("ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>Druhý</a:t></a:r></a:p></p:sld>", "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>První</a:t></a:r></a:p></p:sld>")
        assertEquals("— Snímek 1 —\nPrvní\n\n— Snímek 2 —\nDruhý", Extract.extract("a.pptx", null, p)!!.text)
        val o = zip("content.xml" to "<office:text><text:h>Nadpis</text:h><text:p>Řádek <text:span>jedna</text:span></text:p></office:text>")
        assertEquals("Nadpis\nŘádek jedna", Extract.extract("a.odt", null, o)!!.text)
        val h = Extract.extract("a.html", "text/html", "<html><head><title>x</title><style>p{}</style></head><body><h1>Ahoj</h1><p>Svět &amp; <b>lidé</b></p><script>bad()</script></body></html>".toByteArray())!!
        assertEquals("Ahoj\nSvět & lidé", h.text)
        val rtf = Extract.extract("a.rtf", null, "{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}P\\'f8\\'edli\\'9a \\u382?lu\\par dal\\'9a\\'ed}".toByteArray())!!
        assertEquals("Příliš žlu\ndalší", rtf.text)
        val z = zip("docs/poznamka.txt" to "Text v archivu", "obr.png" to "\u0089PNG")
        assertTrue(Extract.extract("balik.zip", null, z)!!.text.contains("=== docs/poznamka.txt (text) ===\nText v archivu"))
    }

    @Test fun encodingsAndDelegation() {
        val cp1250 = "Žluťoučký kůň".toByteArray(charset("windows-1250"))
        assertEquals("Žluťoučký kůň", Extract.extract("stary.txt", null, cp1250)!!.text)
        assertNull(Extract.extract("sken.pdf", null, "%PDF-1.7".toByteArray()))
        assertNull(Extract.extract("foto.jpg", "image/jpeg", byteArrayOf(1, 2)))
        assertEquals(Extract.Kind.AUDIO, Extract.kindOf("hlas.m4a", null, byteArrayOf()))
    }
}
