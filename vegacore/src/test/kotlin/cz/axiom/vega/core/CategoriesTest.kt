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
        assertTrue(a, a.startsWith("**Pravidelné platby** (2) – dohromady") && a.contains("NETFLIX"))
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

/** Den 24. 11., kolo 12: kategorie bez falešných shod („transaction“ ≠ Action, „mobil“ ≠ OBI, „kurzový rozdíl“ ≠ kurz) + úřady, zásilky, sázky. */
class CategoryPrecisionTest {
    @Test fun noFalseHitsAndMoreMerchants() {
        val c = Statement::categoryOf
        listOf("Card transaction ACME LTD", "Mobilni platba 123", "Platba z mobilu", "Kurzovy rozdil", "Platba k temu", "Hromol s.r.o.", "Leon Novak", "Upce", "Platba pre Jan", "Internetove bankovnictvi")
            .forEach { org.junit.Assert.assertNotEquals(it, c(it).let { k -> if (k == "Ostatní" || k == "Pojištění a finance") "ok" else k }, "x").also { _ -> assertTrue("$it → ${c(it)}", c(it) == "Ostatní" || c(it) == "Pojištění a finance") } }
        val want = mapOf("OBI Praha" to "Nákupy", "ACTION Brno" to "Nákupy", "Zpetna uhrada temu.com" to "Nákupy", "MOL CS" to "Doprava a palivo", "E.ON Energie" to "Bydlení a energie", "PRE elektrina" to "Bydlení a energie",
            "PRE a.s." to "Bydlení a energie", "UPC Ceska republika" to "Telefon a internet", "Internet Nordic" to "Telefon a internet", "Anglicky kurz" to "Děti a vzdělání", "Pilulka.cz" to "Zdraví a drogerie",
            "Zasilkovna" to "Nákupy", "Sazka" to "Volný čas", "Tipsport" to "Volný čas", "SIPO" to "Bydlení a energie", "Financni urad Praha" to "Úřady a daně", "Ceska sprava socialniho zabezpeceni" to "Úřady a daně",
            "Pokuta Policie CR" to "Úřady a daně", "Poplatek za odpad" to "Úřady a daně", "VZP" to "Pojištění a finance", "Kaufland" to "Potraviny", "Poplatek za vedeni uctu" to "Pojištění a finance")
        want.forEach { (d, k) -> org.junit.Assert.assertEquals(d, k, c(d)) }
    }
}

/** Den 24. 11., kolo 13: příjmy a úspora z výpisu („kolik mi přišlo v říjnu“, „kolik jsem ušetřil“) bez modelu. */
class IncomeQueryTest {
    private val t = Statement.process("Datum;Částka;Popis\n02.10.2026;-1 000,00;Albert\n15.10.2026;-500,00;Shell\n20.10.2026;30 000,00;Mzda ACME\n25.10.2026;2 000,00;Vraceni preplatku\n" +
        "02.11.2026;-200,00;Lidl\n05.11.2026;-40 000,00;Nájem byt\n20.11.2026;31 000,00;Mzda ACME\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    private fun a(q: String) = Statement.spendQuery(t, q)?.replace(' ', ' ')?.replace(' ', ' ') ?: "null"
    @Test fun incomeAndSavings() {
        a("kolik mi přišlo v říjnu?").let { assertTrue(it, it.contains("Příjmy za říjen 2026: **32 000,00 Kč** (2 platby)") && it.contains("Mzda ACME 30 000,00")) }
        a("jaké mám příjmy?").let { assertTrue(it, it.contains("**63 000,00 Kč** (3 platby)") && it.contains("Mzda ACME 61 000,00")) }
        a("kolik jsem ušetřil v říjnu").let { assertTrue(it, it.startsWith("Ušetřil jsi za říjen 2026 **30 500,00 Kč**") && it.contains("5 % příjmů")) }
        a("kolik jsem ušetřil minulý měsíc").let { assertTrue(it, it.contains("říjen 2026")) }
        a("jsem v listopadu v plusu?").let { assertTrue(it, it.startsWith("Utratil jsi víc, než ti přišlo – rozdíl za listopad 2026 **9 200,00 Kč**")) }
        // mimo výpis
        listOf("kdy přijde výplata", "přišlo mi SMS", "shrň mi to").forEach { org.junit.Assert.assertEquals(it, "null", a(it)) }
    }
}

/** Den 24. 11., kolo 16: skloňování po čísle v hlášeních výpisu („4 pohyby“, „1 platba“, ne „4 pohybů“ / „1 plateb“). */
class PluralTest {
    @Test fun czechPlurals() {
        org.junit.Assert.assertEquals(listOf("0 řádků", "1 řádek", "3 řádky", "5 řádků", "22 řádků"), listOf(0, 1, 3, 5, 22).map { cz.axiom.vega.base.Text.plural(it, "řádek", "řádky", "řádků") })
        val txt = "Datum;Částka;Popis\n02.11.2026;-100,00;Albert\n03.11.2026;-200,00;Lidl\n04.11.2026;500,00;Mzda\n"
        val r = Statement.process(txt, Statement.detect(txt).spec!!)
        assertTrue(r.table.notes.toString(), r.table.notes.any { it.endsWith("· 3 pohyby") })
        val c = Statement.categories(r.table).notes.first()
        assertTrue(c, c.contains("· 2 platby ·"))
        val one = Statement.process("Datum;Částka;Popis\n02.11.2026;-100,00;Albert\n", Statement.detect(txt).spec!!)
        assertTrue(one.table.notes.toString(), one.table.notes.any { it.endsWith("· 1 pohyb") } && Statement.categories(one.table).notes.first().contains("· 1 platba ·"))
    }
}

/** Den 24. 11., kolo 22: přesné texty návrhů pod výpisem (app.js chips()) musí odpovědět jádro bez modelu – i u hraničních výpisů. */
class StatementChipsTest {
    private val sp = Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!
    @Test fun chipQuestionsAnswered() {
        val js = java.io.File("../vega/src/main/assets/ui/app.js").readText()
        val chips = Regex("a: 'say:([^']+)' \\}").findAll(js.substringAfter("hd.includes('Příjmy')")).map { it.groupValues[1] }.take(2).toList()
        assertTrue("návrhy v app.js: $chips", chips.size == 2 && chips[0].startsWith("Za co"))
        val full = Statement.process("Datum;Částka;Popis\n02.10.2026;-259,00;NETFLIX.COM\n02.11.2026;-259,00;NETFLIX.COM\n05.11.2026;-12 500,00;Najem byt\n20.11.2026;30 000,00;Mzda\n", sp).table
        chips.forEach { q -> assertTrue(q, Statement.spendQuery(full, q) != null) }
        assertTrue(Statement.spendQuery(full, chips[0])!!.startsWith("Nejvíc utrácíš za: **Bydlení a energie**"))
        // jen příjmy: žádný prázdný výčet ani „0 plateb“
        val inc = Statement.process("Datum;Částka;Popis\n02.11.2026;30 000,00;Mzda\n", sp).table
        assertTrue(Statement.spendQuery(inc, chips[0])!!.contains("nejsou žádné výdaje"))
        Statement.spendQuery(inc, "kolik jsem celkem utratil")!!.let { assertTrue(it, it.contains("nejsou žádné výdaje") && !it.contains("0 plateb")) }
    }
}

/** Den 24. 11., kolo 24: pravidelné platby kladně + součet za měsíc; „za Netflix“ = obchodník, ne celá kategorie předplatného. */
class RecurringWordingTest {
    private val t = Statement.process("Datum;Částka;Popis\n03.10.2026;-259,00;NETFLIX.COM\n04.10.2026;-169,00;Spotify P1\n05.10.2026;-12 500,00;Najem byt\n" +
        "03.11.2026;-259,00;NETFLIX.COM\n04.11.2026;-169,00;Spotify P1\n05.11.2026;-12 500,00;Najem byt\n20.11.2026;30 000,00;Mzda\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    private fun a(q: String) = Statement.spendQuery(t, q)!!.replace(' ', ' ').replace(' ', ' ')
    @Test fun wording() {
        a("Jaké mám pravidelné platby?").let { assertTrue(it, it.contains("dohromady **12 928,00 Kč měsíčně**") && it.contains("• Najem byt – 12 500,00 Kč měsíčně") && !it.contains("−")) }
        a("kolik jsem utratil za Netflix").let { assertTrue(it, it.contains("NETFLIX") && it.contains("**−518,00 Kč**") && !it.contains("687")) }
        a("kolik jsem utratil za nájem").let { assertTrue(it, it.startsWith("Za bydlení a energie")) }
    }
}

/** Den 24. 11., kolo 27: po výpisu v chatu si výpočty a hypotézy nepřivlastní tabulka výpisu (kalkulačka / model je spočítá správně). */
class StatementNoHijackTest {
    private val t = Statement.process("Datum;Částka;Popis\n03.10.2026;-259,00;NETFLIX.COM\n05.10.2026;-12 500,00;Splatka hypoteky\n20.11.2026;30 000,00;Mzda\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    @Test fun calculationsStayOut() {
        listOf("kolik bych platil měsíčně za hypotéku 3 miliony na 30 let při 5 %", "kdybych utratil 500 Kč denně, kolik je to za měsíc", "kolik mi zbylo z 30 000 po zaplacení 12 500",
            "kolik budu platit za elektřinu", "kolik bych ušetřil, kdybych zrušil Netflix").forEach { org.junit.Assert.assertNull(it, Statement.spendQuery(t, it)) }
        // skutečné dotazy na výpis dál fungují, i s letopočtem
        listOf("kolik jsem utratil za hypotéku", "kolik jsem utratil v říjnu 2026", "kolik mi přišlo v listopadu 2026", "jaké mám pravidelné platby?").forEach { org.junit.Assert.assertNotNull(it, Statement.spendQuery(t, it)) }
    }
}

/** Den 24. 11., kolo 28: obecné otázky na mzdy / příjmy / bilanci (ne osobní) si nepřivlastní výpis. */
class StatementPersonalTest {
    private val t = Statement.process("Datum;Částka;Popis\n03.10.2026;-259,00;NETFLIX.COM\n20.11.2026;30 000,00;Mzda\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    @Test fun onlyPersonal() {
        listOf("kolik je minimální mzda 2026", "kolik je průměrná mzda v Česku", "jaké jsou příjmy státního rozpočtu", "jaká je bilance obchodu ČR").forEach { org.junit.Assert.assertNull(it, Statement.spendQuery(t, it)) }
        listOf("kolik mi přišlo", "jaké mám příjmy", "kolik jsem ušetřil", "jsem v plusu?", "kolik nám přišlo na účet").forEach { org.junit.Assert.assertNotNull(it, Statement.spendQuery(t, it)) }
    }
}
