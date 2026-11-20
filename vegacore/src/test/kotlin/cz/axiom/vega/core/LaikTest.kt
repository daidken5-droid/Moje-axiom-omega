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
}
