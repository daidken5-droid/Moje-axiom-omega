package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sobota 28. 11. kolo D13 – sonda 30 otázek uživatele zvyklého na ChatGPT v režimu Bez sítě: tyhle chyběly. */
class EverydayPhrasingTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 28, 11, 50, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun a(q: String): String { val r = Quick.answer(q, now); assertNotNull(r, q); return r }
    private fun has(q: String, vararg parts: String) { val r = a(q); parts.forEach { assertTrue(r.contains(it), "„$q“ → $r (chybí „$it“)") } }

    @Test fun vatPhrasing() {
        has("přičti 21 % DPH k 5000", "6 050 Kč", "1 050 Kč")
        has("odečti DPH od 1210", "1 000 Kč")
        has("kolik je 20 % DPH z 1000", "200 Kč", "1 200 Kč", "21 %")   // nečeská sazba: spočítá a připomene české sazby
        has("12 % DPH z 1000", "120 Kč")                                 // původní tvar beze změny
    }

    @Test fun clockArithmetic() {
        has("kolik je 17:30 mínus 2 hodiny 45 minut", "14:45")
        has("8:15 plus 90 minut", "9:45")
        has("23:30 + 2 h", "1:30", "další den")
    }

    @Test fun travelTime() {
        has("jak dlouho pojedu 120 km rychlostí 90 km/h", "1 h 20 min")
        has("za jak dlouho ujdu 10 km rychlostí 5 km/h", "2 h")
    }

    @Test fun publicHolidays() {
        has("je dnes svátek?", "28. 11.", "René", "není státní svátek")
        has("státní svátky v prosinci", "24. 12.", "25. 12.", "26. 12.")
        has("kdy je státní svátek v prosinci", "24. 12.")
        has("kdy je další státní svátek", "24. 12.")
        has("je 17. 11. státní svátek", "Den boje za svobodu a demokracii")
    }
}
