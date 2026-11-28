package cz.axiom.vega.calc

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne R4 (refaktoring): kalendář vyňatý z Quick.kt jde volat samostatně (vstup složený bez diakritiky). */
class QuickDaysTest {
    private val day = LocalDate.of(2026, 11, 28)

    @Test fun standalone() {
        assertTrue(QuickDays.answer("kdo ma dnes svatek", day)!!.contains("René"))
        assertTrue(QuickDays.answer("kdy je dalsi statni svatek", day)!!.contains("24. 12."))
        assertTrue(QuickDays.answer("je dnes svatek", day)!!.contains("není státní svátek"))
        assertNull(QuickDays.answer("kolik je 2 + 2", day))
    }
}
