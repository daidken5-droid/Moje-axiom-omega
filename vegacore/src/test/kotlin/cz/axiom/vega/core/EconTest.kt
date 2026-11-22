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

    /** Přesné případy 10 a 11 ze zadání ASTRAEA: výpočet dělá deterministická kalkulačka, ne model. */
    @Test fun astraeaAcceptanceCalculations() {
        val loss = Econ.unitAnswer(Econ.Unit(250.0, 150.0, 8000.0, 60.0))!!
        assertEquals(-2000.0, loss.profit, 0.0)
        val fixed = Econ.unitAnswer(Econ.Unit(250.0, 150.0, 5000.0, 60.0))!!  // oprava fixních nákladů → přepočet
        assertEquals(1000.0, fixed.profit, 0.0)
        assertTrue(fixed.text, fixed.text.startsWith("Při 60 kusech máš zisk 1 000 Kč."))
        val t = Econ.parseTariff("Vyplatí se roční předplatné 2 400 Kč, nebo 250 Kč měsíčně?")!!
        assertTrue(Econ.tariffAnswer(t), Econ.tariffAnswer(t).contains("aspoň 10 měsíců"))
        val t8 = Econ.tariffAnswer(t.copy(months = 8.0)); val t12 = Econ.tariffAnswer(t.copy(months = 12.0))
        assertTrue(t8, t8.startsWith("Při 8 měsících vyjde měsíční varianta o 400 Kč levněji"))
        assertTrue(t12, t12.startsWith("Při 12 měsících vyjde roční tarif o 600 Kč levněji"))
    }
}

/** Scan S12 (22. 11.): ekonomické výpočty se nesmí chytit na běžnou větu. */
class EconFalsePositiveTest {
    @org.junit.Test fun noNonsenseTariffOrBreakEven() {
        // „roční navýšení o 5 %“ / „roční úrok 5 %“ nejsou roční cena 5 Kč
        org.junit.Assert.assertNull(Econ.parseTariff("nájem 15 000 měsíčně, roční navýšení o 5 %, kolik zaplatím za rok?"))
        org.junit.Assert.assertNull(Econ.parseTariff("kolik je roční úrok 5 % z 100 000 měsíčně"))
        // psaní textu s čísly není výpočet bodu zvratu
        org.junit.Assert.assertFalse(Econ.isUnitQuestion("napiš mi email: cena 250 Kč za kus, fixní poplatek 1000 Kč, náklad 100"))
        // skutečné dotazy dál fungují
        org.junit.Assert.assertNotNull(Econ.parseTariff("Vyplatí se roční předplatné 2 400 Kč, nebo 250 Kč měsíčně?"))
        org.junit.Assert.assertTrue(Econ.isUnitQuestion("cena 250, náklad na kus 150, fixní náklady 8000, 60 kusů"))
    }
    @org.junit.Test fun monthlyYearly() {
        org.junit.Assert.assertEquals("299 Kč měsíčně je 3 588 Kč za rok.", cz.axiom.vega.calc.Quick.answer("předplatné Netflixu stojí 299 měsíčně, kolik ročně?"))
        org.junit.Assert.assertEquals("600 000 Kč ročně je 50 000 Kč měsíčně (÷ 12).", cz.axiom.vega.calc.Quick.answer("mám roční plat 600 000, kolik je to měsíčně?"))
        org.junit.Assert.assertNull("navýšení v % není prostý převod", cz.axiom.vega.calc.Quick.answer("nájem 15 000 měsíčně, roční navýšení o 5 %, kolik zaplatím za rok?"))
    }
}
