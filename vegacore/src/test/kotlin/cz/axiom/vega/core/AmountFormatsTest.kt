package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N5: 19 tvarů částek z výpisů (tisíce tečkou, Kč/CZK/€, mínus vzadu, závorky, NBSP, CR/DR). */
class AmountFormatsTest { @Test fun nineteenShapes() {
    listOf("-1 234,56" to -1234.56, "1.234,56" to 1234.56, "-1.234,56" to -1234.56, "1 234,56 Kč" to 1234.56, "-1 234,56 CZK" to -1234.56, "1234,56-" to -1234.56,
        "(123,45)" to -123.45, "−1 234,56" to -1234.56, "1 234,56" to 1234.56, "1 234,56" to 1234.56, "+500,00" to 500.0, "1 234" to 1234.0, "12,5 €" to 12.5,
        "-0,50" to -0.5, "1'234.56" to 1234.56, "CZK -1 234,56" to -1234.56, "Kč 99,90" to 99.9, "1 234,56 CR" to 1234.56, "1 234,56 DR" to -1234.56)
        .forEach { (s, e) -> org.junit.Assert.assertEquals(s, e, CzNum.parse(s, true)!!, 1e-9) }
    org.junit.Assert.assertNull(CzNum.parse("DR", true)); org.junit.Assert.assertNull(CzNum.parse("ADR 12", true))
} }

/** Den 24. 11., kolo 3: export tabulky výpisu pro český Excel – středník, BOM a desetinná ČÁRKA (dřív „-1250.50“ = text). */
class CsvExportExcelTest {
    @Test fun czechExcelGetsDecimalComma() {
        val txt = "Datum;Částka;Popis\n02.11.2026;-1 250,50;Albert\n05.11.2026;35 000,00;Mzda 2.5 dne\n"
        val r = Statement.process(txt, Statement.detect(txt).spec!!)
        val ctx = object : ToolCtx { override val cancel = cz.axiom.vega.llm.CancelToken() }
        val tool = Tools.BUILTIN.first { it.id == "csv.export" }
        val b = (tool.run(r.table, mapOf("delimiter" to ";"), ctx) as Value.Bytes).bytes
        org.junit.Assert.assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), b.copyOf(3))
        val s = String(b, Charsets.UTF_8)
        org.junit.Assert.assertTrue(s, s.contains("2026-11-02;-1250,50;CZK;Albert") && s.contains("35000,00;CZK;Mzda 2.5 dne"))
        // s čárkou jako oddělovačem zůstane tečka (jinak by se číslo rozpadlo do dvou sloupců)
        val c = String((tool.run(r.table, mapOf("delimiter" to ","), ctx) as Value.Bytes).bytes, Charsets.UTF_8)
        org.junit.Assert.assertTrue(c, c.contains("2026-11-02,-1250.50,CZK,Albert"))
    }
}
