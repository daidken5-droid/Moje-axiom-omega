package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Den 24. 11., kolo 7: výpis z banky v Excelu (XLSX) – vynechané prázdné buňky a data jako pořadová čísla Excelu. */
class XlsxStatementTest {
    private fun xlsx(sheet: String, shared: List<String>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types/>")
            put("xl/sharedStrings.xml", "<sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>")
            put("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$sheet</sheetData></worksheet>")
        }
        return bos.toByteArray()
    }
    // sdílené texty: 0 Datum, 1 Částka, 2 Poznámka, 3 Protistrana, 4 Albert, 5 Zaměstnavatel, 6 Benzina
    private val shared = listOf("Datum", "Částka", "Poznámka", "Protistrana", "Albert", "Zaměstnavatel", "Benzina")
    private val sheet = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>""" +
        // C2 chybí (prázdná poznámka) – dřív by se „Albert“ posunul do sloupce Poznámka
        """<row r="2"><c r="A2"><v>46328</v></c><c r="B2"><v>-489.9</v></c><c r="D2" t="s"><v>4</v></c></row>""" +
        """<row r="3"><c r="A3"><v>46331</v></c><c r="B3"><v>35000</v></c><c r="C3" t="inlineStr"><is><t>mzda</t></is></c><c r="D3" t="s"><v>5</v></c></row>""" +
        """<row r="4"><c r="A4"><v>46333</v></c><c r="B4"><v>-899</v></c><c r="D4" t="s"><v>6</v></c></row>"""

    @Test fun cellsAlignedAndSerialDates() {
        val rows = cz.axiom.vega.files.Extract.xlsxRows(xlsx(sheet, shared))!!
        assertEquals(listOf("46328", "-489.9", "", "Albert"), rows[1])
        val c = Statement.fromXlsxRows(rows)!!
        val r = Statement.process(c.csv, Statement.detect(c.csv).spec!!)
        assertEquals(3, r.ok); assertEquals(35000.0, r.income, 0.001); assertEquals(-1388.9, r.expense, 0.001)
        assertEquals("2026-11-02", r.table.rows[0][0])
        assertTrue(r.table.rows[0][3], r.table.rows[0][3].contains("Albert"))
        // textový náhled (shrnutí) má taky zarovnané sloupce
        val txt = cz.axiom.vega.files.Extract.extract("vypis.xlsx", null, xlsx(sheet, shared))!!.text
        assertTrue(txt, txt.contains("46328;-489.9;;Albert"))
    }
}
