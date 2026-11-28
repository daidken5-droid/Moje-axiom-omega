package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sobota 28. 11.: „Kdo má dnes svátek?“ bez modelu – tabulka z cs.wikipedia.org/wiki/Jmeniny_v_Česku (366 dní), ne zpaměti. */
class NameDaysTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 28, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))

    @Test fun completeTable() {
        assertEquals(366, NameDays.size())
        assertEquals("René", NameDays.on(11, 28))
        assertEquals("Horymír", NameDays.on(2, 29))
        assertEquals("Den vítězství (státní svátek)", NameDays.on(5, 8))
    }

    @Test fun questions() {
        val today = Quick.answer("kdo má dnes svátek?", now); assertNotNull(today); assertTrue(today.contains("René") && today.contains("28. 11."), today)
        val tomorrow = Quick.answer("kdo má zítra svátek", now); assertNotNull(tomorrow); assertTrue(tomorrow.contains("Zina"), tomorrow)
        val petr = Quick.answer("kdy má svátek Petr?", now); assertNotNull(petr); assertTrue(petr.contains("22. 2.") && petr.contains("29. 6."), petr)
        val jana = Quick.answer("Kdy slaví svátek Jana", now); assertNotNull(jana); assertTrue(jana.contains("24. 5."), jana)
        val none = Quick.answer("kdy má svátek Xyzzy", now); assertNotNull(none); assertTrue(none.contains("nenašel"), none)
    }
}
