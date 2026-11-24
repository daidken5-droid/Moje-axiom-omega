package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Den 24. 11., kolo 4 (nová funkce): výdaje z výpisu podle kategorií – bez modelu, podle obchodníka v popisu. */
class CategoriesTest {
    @Test fun merchantsToCategories() {
        listOf("Platba kartou ALBERT HYPERMARKET PRAHA" to "Potraviny", "LIDL DEKUJE ZA NAKUP" to "Potraviny", "BENZINA s.r.o. CS 123" to "Doprava a palivo",
            "ORLEN Praha" to "Doprava a palivo", "Trvalý příkaz NÁJEM BYT" to "Bydlení a energie", "ČEZ Prodej záloha" to "Bydlení a energie",
            "NETFLIX.COM" to "Předplatné a digitální", "Výběr z bankomatu" to "Hotovost", "Dr.Max lékárna" to "Zdraví a drogerie",
            "ALZA.CZ a.s." to "Nákupy", "Wolt objednávka" to "Restaurace a kavárny", "Bolt Rides" to "Doprava a palivo", "Platba Jan Novák" to "Ostatní")
            .forEach { (d, c) -> assertEquals(d, c, Statement.categoryOf(d)) }
    }

    @Test fun categoriesTableFromStatement() {
        val txt = "Datum;Částka;Popis\n02.11.2026;-1 250,50;Albert Praha\n03.11.2026;-300,00;Lidl\n05.11.2026;35 000,00;Mzda\n" +
            "07.11.2026;-12 500,00;Nájem byt\n09.11.2026;-899,00;Benzina\n12.11.2026;-50,00;Platba Jan Novák\n"
        val t = Statement.categories(Statement.process(txt, Statement.detect(txt).spec!!).table)
        assertEquals(listOf("Bydlení a energie", "Potraviny", "Doprava a palivo", "Ostatní"), t.rows.map { it[0] })
        assertEquals(listOf("Potraviny", "−1 550,50", "2", "10 %"), t.rows[1].map { it.replace(' ', ' ') })
        assertTrue(t.notes.toString(), t.notes[0].contains("5 plateb") && t.notes.any { it.contains("„Ostatní“") })
        // příjem (mzda) se do výdajů nepočítá
        assertTrue(t.rows.none { it[0].contains("Mzda") })
    }
}
