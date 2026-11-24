package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Den 24. 11., kolo 1: výpis z banky v PDF (text z PdfBox) → tabulka. Dřív „PDF spočítat neumím, stáhni CSV“. */
class PdfStatementTest {
    private fun run(txt: String): Statement.Result { val c = Statement.fromPdfText(txt)!!; return Statement.process(c.csv, Statement.detect(c.csv).spec!!) }

    @Test fun signedAmountsOneColumn() {
        val txt = """Komerční banka, a.s.      Výpis z účtu č. 123-456789/0100
            |Období: 01.11.2026 – 30.11.2026
            |Datum  Popis transakce                     Částka
            |02.11.2026  Platba kartou ALBERT PRAHA 4   -489,90 CZK
            |05.11.2026  Příchozí platba ZAMESTNAVATEL S.R.O.   35 000,00 CZK
            |07.11.2026  Trvalý příkaz NÁJEM BYT      -12 500,00 CZK
            |Strana 1/1""".trimMargin()
        val r = run(txt)
        assertEquals(3, r.ok); assertEquals(35000.0, r.income, 0.001); assertEquals(-12989.9, r.expense, 0.001)
        assertTrue(r.table.rows[0][3].contains("ALBERT"))
    }

    @Test fun amountAndRunningBalanceColumns() {
        // pohyb + průběžný zůstatek: bez kontroly návaznosti by se sčítal zůstatek
        val txt = """Datum   Popis   Částka   Zůstatek
            |01.11.2026  Počáteční zůstatek
            |03.11.2026  ČEZ záloha   -1 250,00   8 750,00
            |10.11.2026  Mzda   30 000,00   38 750,00
            |12.11.2026  Lidl   -312,40   38 437,60
            |20.11.2026  Výběr z bankomatu   -2 000,00   36 437,60""".trimMargin()
        val c = Statement.fromPdfText(txt)!!
        assertTrue(c.notes.toString(), c.notes.any { it.contains("návazností sedí") })
        val r = run(txt); assertEquals(4, r.ok); assertEquals(30000.0, r.income, 0.001); assertEquals(-3562.4, r.expense, 0.001)
    }

    @Test fun unsignedAmountsSignFromBalance() {
        val txt = """02.11.2026  Albert  -500,00  9 500,00
            |04.11.2026  Vratka  200,00  9 700,00
            |06.11.2026  Benzina  1 100,00  8 600,00
            |08.11.2026  Lékárna  300,00  8 300,00""".trimMargin()
        val r = run(txt); assertEquals(200.0, r.income, 0.001); assertEquals(-1900.0, r.expense, 0.001)
    }

    @Test fun refusesWhenUnsure() {
        // samé kladné částky bez znaménka i bez zůstatku: nevím, co je výdaj → nic (ne špatné součty)
        assertNull(Statement.fromPdfText("02.11.2026 Albert 500,00\n04.11.2026 Benzina 1 100,00\n06.11.2026 Lékárna 300,00"))
        assertNull(Statement.fromPdfText("Smlouva o nájmu bytu\nPronajímatel: Jan Novák\nNájemné 12 500,00 Kč měsíčně"))
    }
}
