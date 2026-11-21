package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Kolo testů T4: typické tvary exportů českých bank → správné příjmy a výdaje (dřív: částka bez znaménka + sloupec Debet = vše „příjem“). */
class StatementBanksTest {
    private fun sums(text: String): Pair<Double, Double> {
        val d = Statement.detect(text); val spec = assertNotNull("formát nerozpoznán", d.spec).let { d.spec!! }
        val r = Statement.process(text, spec); return r.income to r.expense
    }
    private fun eq(exp: Pair<Double, Double>, text: String) { val (i, e) = sums(text); assertEquals("příjmy", exp.first, i, 0.001); assertEquals("výdaje", exp.second, e, 0.001) }

    @Test fun signedAmountsAndFormats() {
        eq(25000.0 to -1734.5, "Datum;Částka;Měna;Popis\n01.03.2026;25 000,00;CZK;Výplata\n02.03.2026;−1 234,50;CZK;Albert\n03.03.2026;-500,00;CZK;Benzín")
        eq(3000.0 to -450.0, "Číslo účtu: 123456789/0800\nObdobí: 1.3.2026 - 31.3.2026\n\nDatum zaúčtování;Částka;Protistrana;Zpráva\n05.03.2026;3 000,00;Jan Novák;dárek\n06.03.2026;-450,00;Lidl;\nCelkem;2 550,00;;")
        eq(1200.0 to -300.0, "Datum;Příjem;Výdaj;Popis\n01.03.2026;1 200,00;;Vratka\n02.03.2026;;300,00;Kino")
        eq(100.5 to -20.25, "Date,Amount,Description\n2026-03-01,100.50,Refund\n2026-03-02,-20.25,Coffee")
        eq(0.0 to -1234.5, "Datum;Částka;Popis\n01.03.2026;1 234,50-;Platba")
        eq(0.0 to -99.0, "Datum;Částka;Popis\n01.03.2026;(99,00);Poplatek")
        eq(1000.0 to -250.0, "Datum;Částka;Popis\n01.03.2026;1 000,00 CZK;Příjem\n02.03.2026;-250,00 CZK;Nákup")
        eq(1234567.89 to 0.0, "Datum;Částka;Popis\n01.03.2026;1.234.567,89;Prodej bytu")
        eq(0.0 to -10.0, "Datum;Částka;Popis\n01.03.2026;\"-10,00\";\"Platba; kartou\"")
        eq(1000.0 to 0.0, Encoding.decode("Datum;Částka;Popis\n01.03.2026;1 000,00;Výplata žluťoučký".toByteArray(charset("windows-1250"))))
    }

    @Test fun unsignedAmountWithDirectionColumn() {
        eq(100.0 to -40.0, "Datum;Částka;Typ pohybu;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;40,00;Debet;B")
        eq(100.0 to -40.0, "Datum;Částka;Směr;Popis\n01.03.2026;100,00;Příchozí;A\n02.03.2026;40,00;Odchozí;B")
        eq(100.0 to -40.0, "Datum;Částka;D/C;Popis\n01.03.2026;100,00;C;A\n02.03.2026;40,00;D;B")
        // záporná částka + směr: znaménko se neotáčí dvakrát
        eq(100.0 to -40.0, "Datum;Částka;Typ;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;-40,00;Debet;B")
        // popis „Platba“ u příjmu není směr – příjem zůstane příjmem
        eq(100.0 to 0.0, "Datum;Částka;Popis\n01.03.2026;100,00;Platba")
    }

    @Test fun savedProcedureWithDirectionSurvivesReorderedColumns() {
        val spec = Statement.detect("Datum;Částka;Směr;Popis\n01.03.2026;100,00;Příchozí;A\n02.03.2026;40,00;Odchozí;B").spec!!
        val r = Statement.process("Popis;Směr;Datum;Částka\nX;Odchozí;03.03.2026;70,00\nY;Příchozí;04.03.2026;10,00", spec)
        assertEquals(10.0, r.income, 0.001); assertEquals(-70.0, r.expense, 0.001)
        // starý uložený postup (bez directionCol) se načte beze změny
        assertEquals(-1, Statement.ParserSpec.parse(Statement.ParserSpec(";", true, 0, 1, listOf(2)).json().replace(",\"directionCol\":-1", "")).directionCol)
    }
}

class JsonToolEdgeTest {
    @org.junit.Test fun hugeAndDeepJsonDoNotCrash() {
        // dřív StackOverflowError (Error, ne Exception → pád aplikace) – regex nad zprávou knihovny se vstupem
        for (s in listOf("[1]".repeat(3_000), "[".repeat(100_000), "{\"a\":".repeat(50_000), "\"".repeat(5_000), "[1," + "1,".repeat(200_000) + "1]")) {
            val (el, err) = JsonTool.parse(s)
            org.junit.Assert.assertTrue("„${s.take(20)}“: ${err?.take(80)}", el != null || err != null)
        }
        org.junit.Assert.assertTrue(JsonTool.parse("[".repeat(300) + "]".repeat(300)).second!!.contains("vnořený"))
        org.junit.Assert.assertNotNull(JsonTool.parse("[".repeat(150) + "]".repeat(150)).first)
    }
}
