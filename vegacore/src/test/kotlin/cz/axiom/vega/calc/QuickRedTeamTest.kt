package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne – red team (hacker, vývojář) nad koly D12–D14: radši nic než tichá špatná odpověď. */
class QuickRedTeamTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 28, 13, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String) = Quick.answer(s, now)

    @Test fun clockOverflow() {
        assertNull(q("10:00 + 99999999999 h"))      // dřív „10:00 + 0 min = 10:00“
        assertNull(q("23:59 + 99999999 h"))         // dřív přetečení Int → nesmyslný čas
        assertTrue(q("10:00 + 30 h")!!.contains("16:00"))
    }

    @Test fun vatRateNotSilentlyReplaced() {
        assertNull(q("přičti 99 % DPH k 5000"))     // dřív tiše 21 %
        assertTrue(q("kolik je 5,5 % DPH z 1000")!!.contains("55 Kč"))
        assertTrue(q("přičti 12 % DPH k 1000")!!.contains("1 120 Kč"))
    }

    @Test fun travelBounds() {
        assertNull(q("jak dlouho pojedu 999999999999 km rychlostí 1 km/h"))   // dřív „trvá  (bez zastávek)“
        assertTrue(q("jak dlouho pojedu 1000 km rychlostí 50 km/h")!!.contains("20 h"))
    }

    @Test fun noInputEcho() {
        assertNull(q("kdy má svátek " + "a".repeat(10_000)))
        assertTrue(q("kdy má svátek Xyzzy")!!.contains("nenašel"))
    }

    @Test fun holidayYearsWithinLaw() {
        assertNull(q("státní svátky v prosinci 0001"))
        assertTrue(q("státní svátky v prosinci 2030")!!.contains("24. 12."))
    }
}
