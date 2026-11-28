package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sobota 28. 11.: běžné české slovosledy procentního rozdílu – dřív „potřebuji model“. */
class PercentPhrasingTest {
    @Test fun differencePhrasings() {
        assertEquals("1 500 je o 25 % víc než 1 200.", Quick.answer("o kolik je 1500 víc než 1200 v procentech"))
        assertEquals("1 500 je o 25 % víc než 1 200.", Quick.answer("o kolik procent je 1500 větší než 1200?"))
        assertEquals("90 je o 25 % míň než 120.", Quick.answer("o kolik je 90 méně než 120 v %"))
        assertEquals("1 200 je o 20 % míň než 1 500.", Quick.answer("o kolik procent je 1200 levnější než 1500"))
        assertEquals("1 500 je o 25 % víc než 1 200.", Quick.answer("o kolik % je 1500 víc než 1200")) // původní tvar beze změny
    }
}
