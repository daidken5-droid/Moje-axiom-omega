package cz.axiom.vega.calc

import cz.axiom.vega.core.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan S26 (22. 11.): běžné laické výpočty, které dřív skončily bez odpovědi nebo dokonce webovou rešerší. */
class EverydayCalcTest {
    private fun a(q: String) = Quick.answer(q)

    @Test fun everyday() {
        assertEquals("Z 25 000 na 28 000 je to nárůst o 12 % (o 3 000).", a("zvýšení z 25 000 na 28 000 je kolik procent"))
        assertEquals("Z 120 Kč na 90 Kč je to pokles o 25 % (o 30 Kč).", a("o kolik procent klesla cena ze 120 Kč na 90 Kč"))
        assertEquals("1 500 ÷ 4 = 375 na každého.", a("rozděl 1500 mezi 4 lidi"))
        assertEquals("Průměr 4, 8, 15 je 9 (součet 27 ÷ 3).", a("průměr z 4, 8 a 15"))
        assertEquals("Průměr 4,5, 5,5 je 5 (součet 10 ÷ 2).", a("průměr 4,5; 5,5"))
        assertEquals("Třetina z 900 je 300.", a("třetina z 900"))
        assertEquals("Polovina z 1 234 je 617.", a("polovina z 1 234"))
        assertEquals("2× 450 = 900.", a("dvojnásobek 450"))
        assertEquals("3 × 4,5 Kč = 13,5 Kč.", a("kolik stojí 3 rohlíky po 4,50"))
        assertEquals("2,5 kg × 89 Kč/kg = 222,5 Kč.", a("2,5 kg za 89 Kč/kg"))
        assertEquals("Únor 2028 má 29 dní.", a("kolik dní má únor 2028"))
        assertEquals("Rok 2027 má 365 dní.", a("kolik dní má rok 2027"))
        assertEquals("Den má 86 400 vteřin.", a("kolik vteřin má den"))
        assertEquals("1 galon = 3,7854 l", a("kolik litrů je 1 galon"))
        // slovensky / hovorově (scan S31)
        assertEquals("1 500 ÷ 4 = 375 na každého.", a("rozdel 1500 medzi 4 ludi"))
        assertEquals("Polovina z 800 je 400.", a("polovicu z 800"))
        assertTrue(a("kolik dní má letos únor")!!.matches(Regex("Únor \\d{4} má 2[89] dní\\.")))
        assertEquals("4 × 59 Kč = 236 Kč.", a("4 piva po 59"))
        val h = a("splátka hypotéky 3 000 000 na 30 let s úrokem 5 %")!!
        assertTrue(h, h.contains("16 104,65 Kč měsíčně"))
    }

    @Test fun noFalsePositivesAndNoResearch() {
        assertTrue(a("sleva 30 % z 899 na 629")!!.contains("po slevě zaplatíš 629,3 Kč"))  // sleva má vlastní výpočet, ne „pokles o %“
        assertNull(a("kolik lidí je 5 rodin"))
        assertNull("procentní body, ne výpočet", a("o kolik procent klesla inflace z 15 na 10 %"))
        val ctx = Router.Ctx(false, null, false, false, null)
        for (q in listOf("třetina z 900", "dvojnásobek 450", "polovina z 1 234"))
            assertTrue(q, Router.route(q, ctx) !is Router.Intent.Research)
    }
}

/** Scan S38: cizí měna bez cílové („kolik je 20 liber“, „50 usd“, „kurz eura“) = na koruny; ne webová rešerše. */
class ForeignOnlyCurrencyTest {
    @Test fun toCzk() {
        for ((q, want) in listOf("kolik je 20 liber" to "GBP", "50 usd" to "USD", "100 € kolik Kč" to "EUR", "kurz eura" to "EUR", "kolik stojí euro" to "EUR")) {
            val r = CnbRates.query(q)!!; assertEquals(q, want, r.from); assertEquals(q, "CZK", r.to)
        }
        assertNull(CnbRates.query("100 kč"))
        assertNull(CnbRates.query("kurz angličtiny"))
        assertTrue(Router.route("kurz eura", Router.Ctx(false, null, false, false, null)) !is Router.Intent.Research)
    }
}

/** Denní scan 23. 11., kolo 1: laické dotazy – gramatika splátky, příklad kurzu v měně dotazu, nové formulace. */
class DailyScanMon1Test {
    private val now = java.time.ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun a(q: String) = Quick.answer(q, now)
    @Test fun fixes() {
        assertTrue(a("splátka úvěru 200 000 na 4 roky s úrokem 6,9 %")!!.contains("na 4 roky při úroku"))
        assertTrue(a("hypotéka 1 000 000 na 1 rok 5 %")!!.contains("na 1 rok při"))
        assertTrue("dolar → příklad v USD", a("200 dolarů v korunách")!!.contains("„200 usd po 23,5“"))
        assertTrue(a("kurz dolaru")!!.contains("usd po"))
        assertEquals("450 je o 150 víc než 300 (o 50 %).", a("o kolik je 450 víc než 300"))
        assertEquals("Do konce měsíce zbývá 7 dní (30. 11. 2026 (pondělí)).", a("kolik dní zbývá do konce měsíce"))
        assertTrue(a("kolik je to dní od 1. 9. 2026")!!.startsWith("Od 1. 9. 2026 (úterý) uplynulo 83 dní"))
        assertEquals("24. 12. 2026 je čtvrtek.", a("co je za den 24. 12. 2026"))
        assertTrue(a("za 100 dní bude jaké datum")!!.startsWith("Za 100 dní bude 3. 3. 2027"))
        assertTrue(a("1 míle kolik km")!!.contains("1,6093 km"))
        assertEquals("3 dcl = 0,3 l", a("3 dcl v litrech"))
        assertTrue(a("je rok 2100 přestupný")!!.startsWith("Rok 2100 není přestupný"))
        assertTrue(a("je rok 2028 přestupný")!!.startsWith("Rok 2028 je přestupný"))
    }
}
