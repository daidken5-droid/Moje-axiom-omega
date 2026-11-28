package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne R2 (red team: prompter) – rychlá odpověď nesmí ukrást dotaz, který patří modelu. */
class QuickPrompterTest {
    @Test fun acronymsAreNotRomanNumerals() {
        listOf("co je CV", "co je XL", "co je CD", "co je DIV", "co je MD", "Co je MIX?", "co znamená DC").forEach { assertNull(Quick.answer(it), it) }
        assertTrue(Quick.answer("co je XLII")!!.contains("42"))
        assertTrue(Quick.answer("XL římsky")!!.contains("40"))
        assertTrue(Quick.answer("římské číslo CD")!!.contains("400"))
    }

    @Test fun bareWordsStayWithModel() {
        listOf("kostka", "Kostka", "mince").forEach { assertNull(Quick.answer(it), it) }
        assertTrue(Quick.answer("hoď kostkou")!!.startsWith("Padla"))
    }
}
