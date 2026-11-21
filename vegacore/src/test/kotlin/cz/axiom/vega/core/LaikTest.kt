package cz.axiom.vega.core

import cz.axiom.vega.calc.Quick
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kolo „laik“: běžné české věty se nesmí ztratit v rešerši a časté výpočty mají odpověď hned (i bez modelu). */
class LaikTest {
    private val now = ZonedDateTime.of(2026, 11, 20, 13, 0, 0, 0, ZoneId.of("Europe/Prague"))

    @Test fun instructionsAreNotTopicResearch() {
        for (q in listOf("Přepiš to formálněji", "Změň barvu na zelenou", "Pomoz mi s výpisem z banky", "Vylepši se v psaní e-mailů", "Oprav mi pravopis",
                "Zkontroluj tenhle text", "Připrav mi seznam na nákup", "Dej mi tip na dárek", "Řekni mi vtip", "Ukaž mi příklad"))
            assertTrue(Router.route(q, RouterTuning.NONE) !is Router.Intent.Research, q)
        // holé názvy témat zůstávají rešerší
        for (q in listOf("Operation Prato", "Projekt Manhattan", "kauza Dozimetr", "Apollo 11"))
            assertTrue(Router.route(q, RouterTuning.NONE) is Router.Intent.Research, q)
    }

    @Test fun everydayCalculations() {
        assertEquals("DPH 21 % z 1 000 Kč je 210 Kč; s DPH 1 210 Kč.", Quick.answer("21 % DPH z 1000", now))
        assertEquals("Do Štědrého dne zbývá 34 dní (24. 12. 2026 (čtvrtek)).", Quick.answer("Kolik dní do Vánoc?", now))
        assertTrue(Quick.answer("kolik dní zbývá do Silvestra", now)!!.contains("41 dní"))
        assertTrue(Quick.answer("za kolik dní jsou Velikonoce", now)!!.contains("28. 3. 2027"))
        assertTrue(Quick.answer("Spočítej mi hypotéku 3 miliony na 30 let s úrokem 5 %", now)!!.contains("16 104,65 Kč měsíčně"))
        assertTrue(Quick.answer("úvěr 200 tisíc na 5 let úrok 9 %", now)!!.contains("4 151,67 Kč měsíčně"))
        assertEquals("5 palců = 12,7 cm", Quick.answer("převeď 5 palců na cm", now))
        // běžná věta se slovem „hypotéka“ bez čísel = žádný výpočet
        assertEquals(null, Quick.answer("Jak funguje hypotéka?", now))
    }

    @Test fun czechBankAmounts() {
        assertEquals(-1234.5, CzNum.parse("\u2212 1\u00A0234,50 Kč")); assertEquals(-1234.5, CzNum.parse("\u20131 234,50"))
        assertEquals(1234.5, CzNum.parse("1\u2009234,50 CZK")); assertEquals(-99.0, CzNum.parse("99,00-"))
    }

    @Test fun everydaySentencesStayConversation() {
        for (q in listOf("Napiš mi omluvný e-mail šéfovi kvůli projektu", "Napiš mi program na oslavu", "Vytvoř mi seznam nástrojů na zahradu", "Napiš projekt zahrady",
                "Myslím, že mám chřipku, co dělat?", "Je pravda, že banány jsou zdravé?", "Jaký je rozdíl mezi verzí Windows 10 a 11?", "Proč Česko importuje plyn?",
                "Dluží mi kamarád 2000, co mám dělat?", "Kde jsme byli loni na dovolené?", "Jak si nechat posílat výpis z účtu každý týden?", "Kde mi zpracují výpis z rejstříku?"))
            assertTrue(Router.route(q, RouterTuning.NONE) is Router.Intent.Talk, "$q → ${Router.route(q, RouterTuning.NONE)}")
        // výslovné žádosti fungují dál
        assertTrue(Router.route("Napiš skript, který spočítá průměr ze souboru", RouterTuning.NONE) is Router.Intent.NewProject)
        assertTrue(Router.route("Založ nový projekt kalkulačka", RouterTuning.NONE) is Router.Intent.NewProject)
        assertTrue(Router.route("Napiš mi upomínku na nezaplacenou fakturu", RouterTuning.NONE) is Router.Intent.Demand)
        assertTrue(Router.route("Mám teorii, že přistání na Měsíci bylo natočené", RouterTuning.NONE) is Router.Intent.Hypothesis)
        // s přílohou je krátký popisek o souboru, ne rešerše
        assertTrue(Router.route("faktura za elektřinu", RouterTuning.NONE.copy(hasAttachment = true, attachmentName = "f.pdf", attachments = 1)) !is Router.Intent.Research)
    }

    @Test fun percentWordAndCurrency() {
        assertEquals("15 % z 2 400 je 360.", Quick.answer("kolik je 15 procent z 2400", now))
        assertEquals("100 EUR po 25,2 = 2 520 Kč.", Quick.answer("100 eur po 25,2", now))
        assertTrue(Quick.answer("100 eur na koruny", now)!!.contains("kurz offline neznám"))
    }

    @Test fun bankSummaryRowsAndShortYear() {
        assertEquals("2026-02-01", CzNum.date("1.2.26")); assertEquals("2026-02-01", CzNum.date("01.02.2026"))
        val csv = "Datum;Částka;Popis\n1.2.26;-100,00;Pekárna\n3.2.26;2 000,00;Výplata\n;1 900,00;Celkem\nKonečný zůstatek;5 000,00;\n"
        val spec = Statement.detect(csv).spec!!
        val r = Statement.process(csv, spec)
        assertEquals(2, r.table.rows.size, r.toString()); assertTrue(r.failed.isEmpty(), r.failed.toString())
    }

    @Test fun ranniLaikRegrese() {
        fun r(q: String) = Router.route(q, RouterTuning.NONE)
        // dřívější chyby → teď rozhovor
        for (q in listOf("měj se", "jsi chytrá?", "Kde koupit levný hash brown", "Potřebuju log na dřevo", "test osobnosti zdarma"))
            assertTrue(r(q) is Router.Intent.Talk, "$q → ${r(q)}")
        // legitimní nástroje/témata fungují dál
        assertTrue(r("spočítej sha256 tohoto souboru") is Router.Intent.Tool)
        assertTrue(r("kontrolní součet 5d41402abc4b2a76b9719d911017c592") is Router.Intent.Tool)
        assertTrue(r("Operation Prato") is Router.Intent.Research)
        assertTrue(r("Projekt Manhattan") is Router.Intent.Research)
    }
}

class RanoVylepseniTest {
    private val now = java.time.ZonedDateTime.of(2026,11,21,8,0,0,0, java.time.ZoneId.of("Europe/Prague"))
    @Test fun tipAndCurrencyWords() {
        val tip = cz.axiom.vega.calc.Quick.answer("20 % spropitné z 540", now)!!
        assertTrue(tip.contains("108 Kč") && tip.contains("648 Kč"), tip)
        assertTrue(cz.axiom.vega.calc.Quick.answer("kolik dát dýško 10 % z 1200", now)!!.contains("120 Kč"))
        // širší měnové tvary
        assertEquals("EUR", cz.axiom.vega.calc.CnbRates.code("eurech"))
        assertEquals("CZK", cz.axiom.vega.calc.CnbRates.code("korunách".lowercase()))
        assertEquals("USD", cz.axiom.vega.calc.CnbRates.code("dolarech"))
    }
}
