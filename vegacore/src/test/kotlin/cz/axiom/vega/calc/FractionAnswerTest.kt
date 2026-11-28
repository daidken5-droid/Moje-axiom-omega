package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sobota 28. 11.: „kolik je 1/3 + 1/4“ – přesný zlomek (7/12) místo jen „0,583333“. */
class FractionAnswerTest {
    @Test fun exactFractions() {
        assertEquals("1/3 + 1/4 = 7/12 (≈ 0,5833)", Quick.answer("kolik je 1/3 + 1/4"))
        assertEquals("1/2 + 1/2 = 1", Quick.answer("1/2 + 1/2"))
        assertEquals("3/4 - 1/6 = 7/12 (≈ 0,5833)", Quick.answer("3/4 - 1/6"))
        assertEquals("2/3 * 3/5 = 2/5 (≈ 0,4)", Quick.answer("kolik je 2/3 * 3/5"))
        assertEquals("1/2 : 1/4 = 2", Quick.answer("1/2 : 1/4"))
        assertTrue(Quick.answer("10/4")!!.contains("2,5"), "dělení dvou čísel zůstává desetinné")
    }
}
