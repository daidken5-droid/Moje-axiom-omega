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

/** Scan S3 (22. 11.): kalendářní hraniční případy kalkulačky. */
class QuickCalendarTest {
    private fun at(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String, y: Int, m: Int, d: Int) = Quick.answer(s, at(y, m, d))

    @Test fun pluralsYearBoundaryAndToday() {
        org.junit.Assert.assertEquals("Mezi 28. 2. 2028 a 1. 3. 2028 jsou 2 dny.", q("kolik dní mezi 28.2.2028 a 1.3.2028", 2028, 2, 27))
        org.junit.Assert.assertEquals("Mezi 1. 3. 2026 a 2. 3. 2026 je 1 den.", q("kolik dní mezi 1.3.2026 a 2.3.2026", 2026, 1, 1))
        assertTrue(q("kolik dní do vánoc", 2026, 12, 25)!!.startsWith("Do Štědrého dne zbývá 364 dní (24. 12. 2027"))
        assertTrue(q("kolik dní do vánoc", 2026, 12, 24)!!.startsWith("Dnes je to tady"))
        assertTrue(q("kolik dní do 24.12.", 2026, 11, 22)!!.startsWith("Do 24. 12. zbývá 32 dní"))
        assertTrue(q("kolik dní do 1.1.", 2026, 11, 22)!!.contains("1. 1. 2027"))
        org.junit.Assert.assertEquals("Datum 31. 2. 2027 neexistuje – zkontroluj den a měsíc.", q("kolik dní do 31.2.2027", 2026, 11, 22))
        // denní scan 23. 11., kolo 7: „před 1 dny“ → „před 1 dnem“
        org.junit.Assert.assertEquals("To už bylo před 1 dnem (21. 11. 2026 (sobota)).", q("kolik dní do 21.11.2026", 2026, 11, 22))
        assertTrue(q("kolik dní do 20.11.2026", 2026, 11, 22)!!.startsWith("To už bylo před 2 dny"))
    }

    @Test fun anniversariesIncludingLeapDay() {
        org.junit.Assert.assertEquals("Od 29. 2. 2000 uplynulo právě 27 let – výročí je dnes.", q("kolik let je od 29.2.2000", 2027, 2, 28))
        assertTrue(q("kolik let je od 29.2.2000", 2027, 3, 1)!!.contains("další výročí 29. 2. 2028"))
        org.junit.Assert.assertEquals("Od 22. 11. 2000 uplynulo právě 26 let – výročí je dnes.", q("kolik let je od 22.11.2000", 2026, 11, 22))
        assertTrue(q("kolik mi je když jsem se narodil 23.11.2000", 2026, 11, 22)!!.contains("uplynulo 25 let; další výročí 23. 11. 2026 (pondělí) (za 1 den)"))
    }
}

/** Scan S10 (22. 11.): otevřený projekt nesmí z každé prosby udělat úpravu kódu. */
class RouterProjectTest {
    private val proj = Router.Ctx(false, null, true, false, null)
    @Test fun everydayRequestsStayConversationEvenWithProjectOpen() {
        for (s in listOf("uprav mi ten e-mail formálněji", "oprav mi pravopis v textu: dobrí den jak se mate", "přepiš to slušněji", "změň tón na přátelštější",
            "vysvětli mi co je fotosyntéza", "popiš mi cestu do Brna", "přidej do seznamu mléko", "udělej mi plán na víkend", "smaž poslední větu"))
            assertTrue("$s → ${Router.route(s, proj)}", Router.route(s, proj) !is Router.Intent.Code)
    }
    @Test fun realCodeTasksStillGoToProject() {
        mapOf("oprav to" to Router.CodeKind.CHANGE, "přidej tlačítko pro odeslání formuláře" to Router.CodeKind.CHANGE, "vysvětli mi tenhle kód" to Router.CodeKind.EXPLAIN,
            "spusť testy" to Router.CodeKind.TEST, "přejmenuj ten soubor na index.html" to Router.CodeKind.CHANGE, "oprav chybu v přihlášení" to Router.CodeKind.CHANGE,
            "sleva se počítá špatně, oprav to" to Router.CodeKind.CHANGE).forEach { (s, k) ->
            val r = Router.route(s, proj); assertTrue("$s → $r", r is Router.Intent.Code && r.kind == k) }
        assertTrue(Router.route("přejmenuj ten soubor na index.html", Router.Ctx(false, null, false, false, null)) !is Router.Intent.Research)
    }
}

/** Scan S11 (22. 11.): peněžní otázka k přiloženému výpisu → přesný nástroj (ne odhad modelu). */
class RouterAttachmentTest {
    @Test fun moneyQuestionsOnStatementFileUseTool() {
        val csv = Router.Ctx(true, "vypis.csv", false, false, null); val pdf = Router.Ctx(true, "smlouva.pdf", false, false, null)
        for (s in listOf("kolik jsem celkem utratil", "spočítej příjmy a výdaje", "jaké mám příjmy", "kolik mi přišlo na účet"))
            assertTrue("$s → ${Router.route(s, csv)}", Router.route(s, csv) is Router.Intent.BuildStatementTool)
        for (s in listOf("shrň to", "přelož to do angličtiny", "co je v tom souboru")) assertTrue(s, Router.route(s, csv) is Router.Intent.Talk)
        // smlouva v PDF není výpis
        assertTrue(Router.route("kolik jsem celkem utratil", pdf) !is Router.Intent.BuildStatementTool)
    }
}

/** Scan S21 (22. 11.): převody jednotek napsaných slovy. */
class UnitWordsTest {
    @Test fun wordForms() {
        mapOf("5 stop na metry" to "5 stop = 1,524 m", "2 kila v gramech" to "2 kila = 2 000 g", "3 hodiny na minuty" to "3 hodiny = 180 min", "90 minut na hodiny" to "90 minut = 1,5 h",
            "500 ml na litry" to "500 ml = 0,5 l", "1 tuna na kila" to "1 tuna = 1 000 kg", "2 dny na hodiny" to "2 dny = 48 h", "1 týden na dny" to "1 týden = 7 dní",
            "1500 metrů na kilometry" to "1 500 metrů = 1,5 km", "250 gramů na kila" to "250 gramů = 0,25 kg").forEach { (q, a) -> assertEquals(q, a, Quick.answer(q)) }
        // měny a mocniny se s jednotkami nepletou
        assertTrue(Quick.answer("100 eur na koruny")!!.contains("kurz"))
        assertEquals("2^10 = 1 024", Quick.answer("kolik je 2 na 10"))
        assertNull(Quick.answer("5 jablek na hrušky"))
    }
}

/** Denní scan 23. 11., kolo 8: laické formulace, na které kalkulačka mlčela (51 dotazů, 7 mezer v existujících funkcích). */
class DailyScanMon8Test {
    private val now = java.time.ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String) = Quick.answer(s, now)

    @Test fun plusVatIsUnambiguous() {
        org.junit.Assert.assertEquals("350 Kč + DPH 21 % = 423,5 Kč (DPH 73,5 Kč).", q("350 plus 21 % DPH"))
        org.junit.Assert.assertEquals("350 Kč + DPH 21 % = 423,5 Kč (DPH 73,5 Kč).", q("350 + DPH"))
        assertTrue(q("1000 bez DPH")!!.contains("Pokud je 1 000 Kč cena už S DPH"))
    }

    @Test fun afterDiscountPowerAndUnits() {
        assertTrue(q("1999 po slevě 25 %")!!.endsWith("po slevě zaplatíš 1 499,25 Kč."))
        org.junit.Assert.assertEquals("17^2 = 289", q("druhá mocnina 17"))
        org.junit.Assert.assertEquals("5^3 = 125", q("třetí mocnina z 5"))
        org.junit.Assert.assertEquals("Rok má 52,14 týdnů (běžný rok, 365 dní).", q("kolik týdnů má rok"))
        org.junit.Assert.assertEquals("10 000 minut = 166,6667 hodin", q("kolik hodin je 10 000 minut"))
        org.junit.Assert.assertEquals("1 hektar = 10 000 m2", q("kolik je 1 hektar v m2"))
        org.junit.Assert.assertEquals("1 GB = 1 000 MB", q("kolik je 1 GB v MB"))
    }
}

/** Denní scan 23. 11., kolo 9: 24 vět s čísly bez falešného zásahu; 24 variant psaní → 4 mezery opraveny. */
class DailyScanMon9Test {
    private val now = java.time.ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String) = Quick.answer(s, now)

    @Test fun messyTypingVariants() {
        org.junit.Assert.assertEquals("1 500 g = 1,5 kg", q("kolik je 1 500 g v kg"))
        org.junit.Assert.assertEquals("15 * 15 = 225", q("kolik je  15  *  15"))
        assertTrue(q("hypoteka 3000000 30 let 5%")!!.startsWith("Splátka 3 000 000 Kč na 30 let při úroku 5 % ročně: 16 104,65 Kč"))
        org.junit.Assert.assertEquals("Za týden bude 30. 11. 2026 (pondělí).", q("za tyden bude jake datum"))
        org.junit.Assert.assertEquals("Před týdnem bylo 16. 11. 2026 (pondělí).", q("před týdnem bylo jaké datum"))
        org.junit.Assert.assertNull(q("za dny"))
    }

    @Test fun numbersInOrdinarySentencesStayWithTheModel() {
        listOf("napiš mi básničku o 3 kočkách", "přelož do angličtiny: mám 2 psy a 1 kočku", "shrň mi tento text: cena vzrostla o 20 % na 350 Kč",
            "oprav gramatiku: za 5 dní bude 1. 12.", "vysvětli mi jak funguje 21 % DPH", "recept na 4 porce svíčkové", "kolik je 2 + 2 a proč",
            "jaký je rozdíl mezi 4G a 5G", "napiš e-mail šéfovi, že přijdu v 9:30").forEach { org.junit.Assert.assertNull(it, q(it)) }
    }
}

/** Denní scan 23. 11., kolo 22: laické dotazy na zprávy bez modelu – dřív „ranní přehled“, „přehled zpráv“ do rešerše, „zapni ranní přehled“ na model. */
class DailyScanMon22Test {
    @Test fun newsPhrasesPointToMorningDigest() {
        listOf("ranní přehled", "přehled zpráv", "novinky dnes", "zapni ranní přehled", "jak zapnu zprávy ráno", "co píšou noviny", "co se stalo dnes", "vypni ranní přehled", "Ranní přehled?")
            .forEach { q -> assertTrue(q, cz.axiom.vega.calc.SmallTalk.answer(q, false)?.contains("Ranní přehled") == true) }
        listOf("co nového u tebe", "co je nového v aplikaci", "přehled výdajů", "co se stalo v roce 1989").forEach { q -> org.junit.Assert.assertNull(q, cz.axiom.vega.calc.SmallTalk.answer(q, false)) }
        org.junit.Assert.assertFalse(Router.route("ranní přehled", Router.Ctx(false, null, false, false, null)) is Router.Intent.Research)
    }
}
