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

/** Scan S4 (22. 11.): české cenovky a velká čísla. */
class NumbersEdgeTest {
    @org.junit.Test fun czechPriceTagIsNotNegative() {
        org.junit.Assert.assertEquals(1000.0, CzNum.parse("1.000,-")!!, 0.0)
        org.junit.Assert.assertEquals(1000.0, CzNum.parse("1.000,-", false)!!, 0.0)
        org.junit.Assert.assertEquals(100.0, CzNum.parse("100,–")!!, 0.0)
        org.junit.Assert.assertEquals(5.0, CzNum.parse("5,- Kč")!!, 0.0)
        // bankovní „250-“ (mínus na konci) zůstává záporné
        org.junit.Assert.assertEquals(-250.0, CzNum.parse("250-")!!, 0.0)
        org.junit.Assert.assertEquals(-1000.0, CzNum.parse("(1 000)")!!, 0.0)
    }
    @org.junit.Test fun hugeResultsAreNotGarbage() {
        val a = cz.axiom.vega.calc.Quick.answer("999999999999 * 999999999999")!!
        org.junit.Assert.assertTrue(a, a.endsWith("= 9,99999999998 × 10^23"))
        org.junit.Assert.assertEquals("1 000 000 * 1 000 000 = 1 000 000 000 000", cz.axiom.vega.calc.Quick.answer("kolik je 1 000 000 * 1 000 000"))
        org.junit.Assert.assertEquals("12345678901 * 1000 = 12 345 678 901 000", cz.axiom.vega.calc.Quick.answer("12345678901 * 1000"))
    }
}

/** Scan S5 (22. 11.): formáty data ve výpisech. */
class DateFormatsTest {
    @org.junit.Test fun formats() {
        val d = mapOf("12.11.99" to "1999-11-12", "01.03.26" to "2026-03-01", "2026/03/01" to "2026-03-01", "01-03-2026" to "2026-03-01",
            "1. března 2026" to "2026-03-01", "5. června 2026" to "2026-06-05", "5. července 2026" to "2026-07-05", "5 červen 2026" to "2026-06-05",
            "2026-03-01T14:22:00" to "2026-03-01", "29.02.2028" to "2028-02-29")
        d.forEach { (raw, iso) -> org.junit.Assert.assertEquals(raw, iso, CzNum.date(raw)) }
        for (bad in listOf("31.02.2026", "29.02.2027", "01.13.2026", "20250115", "1.3.")) org.junit.Assert.assertNull(bad, CzNum.date(bad))
    }
    @org.junit.Test fun statementWithOldTwoDigitYears() {
        val r = Statement.process("Datum;Částka;Popis\n15.12.99;100,00;A\n02.01.00;-40,00;B", Statement.detect("Datum;Částka;Popis\n15.12.99;100,00;A\n02.01.00;-40,00;B").spec!!)
        org.junit.Assert.assertEquals("1999-12-15", r.table.rows[0][0]); org.junit.Assert.assertEquals("2000-01-02", r.table.rows[1][0])
    }
}

/** Scan S9 (22. 11.): dělení odpovědi na ověřitelná tvrzení. */
class ClaimSplitTest {
    private fun c(t: String) = Grounding.claims(t).map { it.cites to it.text }
    @org.junit.Test fun initialsQuotesUnits() {
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Podle J. Nováka je inflace 3,5 %.", listOf(2) to "Ministr T. G. Masaryk to potvrdil."),
            c("Podle J. Nováka je inflace 3,5 % [1]. Ministr T. G. Masaryk to potvrdil [2]."))
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Prezident řekl: „Zvýšíme daně.“", listOf(2) to "Opozice nesouhlasila."),
            c("Prezident řekl: „Zvýšíme daně.“ [1] Opozice nesouhlasila [2]."))
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Teplota dosáhla 35 °C.", listOf(2) to "Rekord padl."), c("Teplota dosáhla 35 °C. [1] Rekord padl [2]."))
        org.junit.Assert.assertEquals(2, c("Zákon platí od 1. ledna 2026 [1]. Týká se všech firem [2].").size)
        org.junit.Assert.assertEquals(2, c("Např. Praha a tzv. Brno mají víc obyvatel [1]. To je fakt [2].").size)
    }
}
