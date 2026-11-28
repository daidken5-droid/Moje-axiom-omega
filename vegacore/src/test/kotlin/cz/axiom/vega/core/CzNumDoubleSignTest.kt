package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Noc 27./28. 11.: „-(1 234,50)“ (minus i závorky = výdaj, některé exporty znaménko zdvojují) se dřív nepřečetl vůbec (null) → řádek výpisu by zmizel. */
class CzNumDoubleSignTest {
    @Test fun minusWithParenthesesIsNegative() {
        assertEquals(-1234.5, CzNum.parse("-(1 234,50)"))
        assertEquals(-1234.5, CzNum.parse("−(1 234,50) Kč"))
        assertEquals(-1234.5, CzNum.parse("(1 234,50)"))
        assertEquals(1234.5, CzNum.parse("1 234,50"))
    }
}
