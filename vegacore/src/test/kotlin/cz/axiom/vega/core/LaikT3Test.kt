package cz.axiom.vega.core

import cz.axiom.vega.calc.CnbRates
import cz.axiom.vega.calc.Quick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kolo testů T3 (21. 11. 2026): laické dotazy, které dřív selhaly – kalkulačka bez modelu, router, převod měn. */
class LaikT3Test {
    private val now = java.time.ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String) = Quick.answer(s, now)
    private fun route(s: String) = Router.route(s, Router.Ctx(false, null, false, false, null))

    @Test fun calculatorAnswersWhatFailedBefore() {
        assertEquals("15 % z 2 400 Kč je 360 Kč.", q("kolik je 15% z 2 400 Kč"))
        assertEquals("Sleva 30 % z 899 Kč je 269,7 Kč; po slevě zaplatíš 629,3 Kč.", q("sleva 30 % z 899"))
        assertEquals("Sleva 20 % z 1 500 Kč je 300 Kč; po slevě zaplatíš 1 200 Kč.", q("1500 Kč se slevou 20 %"))
        assertEquals("1/3 z 900 je 300.", q("kolik je 1/3 z 900"))
        assertEquals("2^10 = 1 024", q("kolik je 2 na 10"))
        assertEquals("3^2 = 9", q("3 na druhou"))
        assertNull("„3 na 4“ bez „kolik je“ může být rozměr", q("3 na 4"))
        assertEquals("Mezi 1. 3. 2026 a 15. 4. 2026 je 45 dní.", q("kolik dní je mezi 1.3.2026 a 15.4.2026"))
        assertTrue(q("za jak dlouho jsou vánoce")!!.startsWith("Do Štědrého dne zbývá 33 dní"))
        assertTrue(q("kolik dní zbývá do konce roku")!!.startsWith("Do konce roku zbývá 40 dní"))
        assertTrue(q("kolik týdnů do léta")!!.startsWith("Do léta (21. 6.) zbývá 30 týdnů a 2 dny (212 dní"))
        assertEquals("30 °C = 86 °F", q("30 stupňů celsia na fahrenheita"))
        assertEquals("3 palce = 7,62 cm", q("3 palce na cm"))
        assertEquals("70 kg = 154,3236 liber", q("70 kg v librách"))
        assertEquals("Úrok 5 % ročně z 100 000 Kč za rok je 5 000 Kč (jednoduchý úrok, bez daně z úroku a poplatků).", q("úrok 5 % z 100000 za rok"))
        assertTrue(q("úrok 3 % ze 200000 za 2 roky")!!.contains("za 2 roky je 12 000 Kč"))
    }

    @Test fun vatAmbiguityShowsBothDirections() {
        assertEquals("1 210 Kč s DPH 21 % = 1 000 Kč bez DPH (DPH 210 Kč).", q("cena bez dph když s dph je 1210"))
        val a = q("1210 bez DPH")!!
        assertTrue(a, a.contains("= 1 464,1 Kč s DPH") && a.contains("bez DPH je 1 000 Kč"))
    }

    @Test fun slovakAndColloquial() {
        assertEquals("15 % z 2 400 je 360.", q("koľko je 15 percent z 2400"))
        assertTrue(q("koľko dní do vianoc")!!.startsWith("Do Štědrého dne zbývá 33 dní"))
    }

    @Test fun routerNeverSendsCalculableOrSmalltalkToWebResearch() {
        for (s in listOf("koľko dní do vianoc", "díkec", "sto děleno sedmi", "myslím že vakcíny způsobují autismus", "70 kg v librách", "kolik je 1/3 z 900"))
            assertTrue("$s → ${route(s)}", route(s) !is Router.Intent.Research)
        // holý název tématu dál vede na rešerši
        assertTrue(route("Operace Prato") is Router.Intent.Research)
        assertTrue(route("Projekt Manhattan") is Router.Intent.Research)
    }

    @Test fun currencyQueryRecognizedForOnlineRates() {
        assertEquals(CnbRates.Query(100.0, "EUR", "CZK", "eur"), CnbRates.query("100 eur na koruny"))
        assertEquals("EUR" to "CZK", CnbRates.query("kolik je 100 euro v korunách")!!.let { it.from to it.to })
        assertEquals("EUR" to "CZK", CnbRates.query("100 € v Kč")!!.let { it.from to it.to })
        assertEquals(1234.5, CnbRates.query("1 234,5 dolarů na eura")!!.value, 0.0)
        assertNull(CnbRates.query("100 eur na koruny a pak co"))
        assertNull(CnbRates.query("100 kč na koruny"))
        // offline kalkulačka u téhož dotazu řekne, jak zadat kurz (online se dřív kvůli ní kurz ČNB nikdy nepoužil – pořadí v Ops.talk)
        assertNotNull(q("100 € v Kč"))
    }

    @Test fun breakEvenFromLaikWording() {
        fun u(s: String) = Econ.unitAnswer(Econ.parseUnit(s))?.text
        assertTrue(u("prodávám za 250, vyrobit stojí 150, fixní náklady 8000, prodám 60 kusů")!!.startsWith("Při 60 kusech máš ztrátu 2 000 Kč"))
        assertTrue(u("kolik musím prodat kusů abych byl v plusu cena 100 náklad 60 fixy 2000")!!.contains("Bod zvratu je 50 kusů"))
        val r = u("když prodám 100 kusů po 50 Kč a výroba kusu stojí 30 a nájem 1500, vydělám?")!!
        assertTrue(r, r.startsWith("Při 100 kusech máš zisk 500 Kč"))
        // „fixní náklady“ se nesmí přečíst jako náklad na kus
        assertEquals(Econ.Unit(250.0, 150.0, 8000.0, 60.0), Econ.parseUnit("cena 250, náklad na kus 150, fixní náklady 8000, 60 kusů"))
    }

    @Test fun wordArithmeticNegativesAndDivisionByZero() {
        assertEquals("5 - 8 = −3", q("kolik je 5 mínus 8"))
        assertEquals("12 × 199 = 2 388 Kč", q("kolik je 12 krát 199 korun"))
        assertEquals("100 / 4 = 25", q("100 děleno 4"))
        assertEquals("-5 * -5 = 25", q("-5 * -5"))
        assertEquals("Nulou dělit nejde – výsledek není definovaný.", q("100 / 0"))
        assertEquals("2+2*2 = 6", q("2+2*2"))
    }

    @Test fun appCommandsWithoutResultAreNotWebResearch() {
        for (s in listOf("spusť testy", "uložit jako csv", "exportuj do excelu", "stáhni to", "vytiskni to"))
            assertTrue("$s → ${route(s)}", route(s) !is Router.Intent.Research)
    }
}
