package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vývojové parafráze (NE závěrečné vstupy z VegaEval2Test): různé české formulace stejných údajů. */
class EconTest {
    private fun u(s: String) = Econ.parseUnit(s)

    @Test fun paraphrases() {
        listOf(
            "Prodávám trička za 400 Kč, jedno mě vyjde na 250 Kč, fixy 3 000 Kč, prodám 15 kusů" to Econ.Unit(400.0, 250.0, 3000.0, 15.0),
            "Cena 99 Kč, náklad na kus 60 Kč, fixní náklady 1 950 Kč" to Econ.Unit(99.0, 60.0, 1950.0, null),
            "Výroba jedné krabičky stojí 35 Kč, prodej za 70 Kč, režie 1 400 Kč měsíčně, při 30 kusech" to Econ.Unit(70.0, 35.0, 1400.0, 30.0),
            "materiál na kus je 12 Kč, prodávám je po 30 Kč a pevné náklady mám 900 Kč" to Econ.Unit(30.0, 12.0, 900.0, null),
        ).forEach { (t, want) -> assertEquals(t, want, u(t)) }
    }

    @Test fun answersAndTariff() {
        val r = Econ.unitAnswer(Econ.Unit(250.0, 150.0, 8000.0, 60.0), claim = 1)!!
        assertTrue(r.text, r.text.startsWith("Při 60 kusech máš ztrátu 2 000 Kč – nejsi v plusu.") && r.breakEven == 80.0)
        val t = Econ.parseTariff("Vyplatí se roční předplatné 1 200 Kč, nebo 110 Kč měsíčně?")!!
        assertEquals(1200.0, t.yearly, 0.0); assertEquals(110.0, t.monthly, 0.0)
        assertTrue(Econ.tariffAnswer(t), Econ.tariffAnswer(t).contains("aspoň 11 měsíců"))
        assertEquals("1 250 Kč", Econ.simpleProduct("kolik je 5 × 250 Kč"))
    }
}
