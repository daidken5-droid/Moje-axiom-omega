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

/** Kolo 5: „kolik jsem utratil za jídlo?“ nad výpisem – bez modelu. */
class SpendQueryTest {
    private val t = Statement.process("Datum;Částka;Popis\n02.11.2026;-1 250,50;Albert Praha\n03.11.2026;-300,00;Lidl\n04.11.2026;-420,00;Wolt\n05.11.2026;35 000,00;Mzda\n" +
        "07.11.2026;-12 500,00;Nájem byt\n09.11.2026;-899,00;Benzina\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    @Test fun answers() {
        val j = Statement.spendQuery(t, "Kolik jsem utratil za jídlo?")!!.replace(' ', ' ')
        assertTrue(j, j.contains("**−1 970,50 Kč** (3 platby: potraviny −1 550,50 Kč, restaurace a kavárny −420,00 Kč)"))
        assertTrue(Statement.spendQuery(t, "kolik mě stál benzín")!!.replace(' ', ' ').contains("**−899,00 Kč** (1 platba"))
        assertTrue(Statement.spendQuery(t, "za co nejvíc utrácím?")!!.startsWith("Nejvíc utrácíš za: **Bydlení a energie**"))
        assertTrue(Statement.spendQuery(t, "kolik jsem utratil za léky")!!.contains("nenašel žádnou platbu"))
        // dotazy mimo výdaje → null (dál router / model)
        listOf("shrň mi to", "kolik je 15 % z 2400", "co je divné na tom výpisu").forEach { org.junit.Assert.assertNull(it, Statement.spendQuery(t, it)) }
    }
}

/** Den 24. 11., kolo 8: běžné věty laika u výpisu (i „export.xlsx“) → zpracování výpisu, ne odhad modelu. */
class StatementRoutingTest {
    private fun r(q: String, f: String) = Router.route(q, Router.Ctx(true, f, false, false, null))
    @Test fun laymanPhrasesRouteToStatement() {
        listOf("kolik jsem utratil?", "spočítej příjmy a výdaje", "udělej mi přehled výdajů", "rozděl výdaje do kategorií", "výdaje podle kategorií",
            "za co utrácím nejvíc?", "kolik jsem dal za jídlo", "kolik jsem tento měsíc utratil").forEach { q ->
            listOf("vypis.pdf", "export.xlsx", "pohyby.csv").forEach { f -> assertTrue("$f: $q", r(q, f) is Router.Intent.BuildStatementTool) } }
        listOf("projdi mi výpis z banky", "tady je výpis, co s tím").forEach { q -> assertTrue(q, r(q, "export.xlsx") is Router.Intent.BuildStatementTool) }
        // jiný dokument / shrnutí zůstává modelu
        org.junit.Assert.assertFalse(r("shrň mi to", "vypis.pdf") is Router.Intent.BuildStatementTool)
        org.junit.Assert.assertFalse(r("kolik jsem utratil?", "smlouva.pdf") is Router.Intent.BuildStatementTool)
        org.junit.Assert.assertFalse(r("přelož mi to", "export.xlsx") is Router.Intent.BuildStatementTool)
    }
}

/** Den 24. 11., kolo 9 (nová funkce): pravidelné platby z výpisu – předplatné, nájem… měsíčně a ročně. */
class RecurringTest {
    private val csv = "Datum;Částka;Popis\n" +
        "05.09.2026;-12 500,00;Trvalý příkaz NÁJEM BYT\n05.10.2026;-12 500,00;Trvalý příkaz NÁJEM BYT\n05.11.2026;-12 500,00;Trvalý příkaz NÁJEM BYT\n" +
        "12.09.2026;-259,00;Platba kartou NETFLIX.COM 4412\n12.10.2026;-259,00;Platba kartou NETFLIX.COM 9921\n12.11.2026;-289,00;Platba kartou NETFLIX.COM 1033\n" +
        "03.09.2026;-1 250,50;Albert Praha\n17.10.2026;-389,00;Albert Praha\n" +   // Albert: velmi rozdílné částky → není pravidelné
        "20.10.2026;-5 000,00;Alza.cz\n" +                                          // jen jednou
        "15.09.2026;35 000,00;Mzda\n15.10.2026;35 000,00;Mzda\n"                    // příjem se nepočítá
    @Test fun findsSubscriptionsAndRent() {
        val t = Statement.process(csv, Statement.detect(csv).spec!!).table
        val r = Statement.recurring(t)
        assertEquals(2, r.rows.size)
        assertEquals("Trvalý příkaz NÁJEM BYT", r.rows[0][0]); assertEquals("−12 500,00", r.rows[0][1].replace(' ', ' ')); assertEquals("3", r.rows[0][3])
        assertTrue(r.rows[1][0].contains("NETFLIX")); assertEquals("Předplatné a digitální", r.rows[1][4])
        val a = Statement.spendQuery(t, "jaké mám pravidelné platby?")!!
        assertTrue(a, a.startsWith("**Pravidelné platby** (2):") && a.contains("NETFLIX"))
        assertTrue(Statement.recurring(Statement.process("Datum;Částka;Popis\n01.11.2026;-100,00;Netflix\n", Statement.detect("Datum;Částka;Popis\n01.11.2026;-100,00;Netflix\n").spec!!).table).notes[0].contains("aspoň 2 měsíce"))
    }
}

/** Den 24. 11., kolo 10: dotaz na měsíc („v říjnu“, „minulý měsíc“) a celkový součet („kolik jsem celkem utratil“) bez modelu. */
class SpendMonthTest {
    private val t = Statement.process("Datum;Částka;Popis\n02.10.2026;-1 000,00;Albert\n15.10.2026;-500,00;Shell\n20.10.2026;30 000,00;Mzda\n" +
        "02.11.2026;-200,00;Lidl\n05.11.2026;-12 500,00;Nájem byt\n20.11.2026;31 000,00;Mzda\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    private fun a(q: String) = Statement.spendQuery(t, q)?.replace('\u00a0', ' ')?.replace('\u202f', ' ') ?: "null"
    @Test fun monthAndTotal() {
        a("Kolik jsem celkem utratil?").let { assertTrue(it, it.contains("**−14 200,00 Kč**") && it.contains("4 platby") && it.contains("saldo 46 800,00")) }
        a("kolik jsem utratil v říjnu").let { assertTrue(it, it.contains("říjen 2026") && it.contains("**−1 500,00 Kč**") && it.contains("2 platby")) }
        a("kolik jsem utratil za listopad?").let { assertTrue(it, it.contains("listopad 2026") && it.contains("**−12 700,00 Kč**")) }
        a("kolik jsem v říjnu utratil za jídlo").let { assertTrue(it, it.contains("říjen 2026") && it.contains("**−1 000,00 Kč**") && !it.contains("200,00")) }
        a("kolik jsem utratil minulý měsíc").let { assertTrue(it, it.contains("říjen 2026") && it.contains("−1 500,00")) }
        a("kolik jsem tento měsíc utratil").let { assertTrue(it, it.contains("listopad 2026") && it.contains("−12 700,00")) }
        a("kolik jsem utratil v březnu").let { assertTrue(it, it.contains("březen") && it.contains("nejsou") && it.contains("říjen 2026")) }
        // červen × červenec se nepletou; neznámá věc zůstává modelu
        assertTrue(a("kolik jsem utratil v červenci").contains("červenec"))
        org.junit.Assert.assertEquals("null", a("kolik jsem utratil za ponožky"))
        listOf("kolik stojí 3 kg jablek po 25 Kč", "kolik to stálo").forEach { org.junit.Assert.assertEquals(it, "null", a(it)) }
    }
}
