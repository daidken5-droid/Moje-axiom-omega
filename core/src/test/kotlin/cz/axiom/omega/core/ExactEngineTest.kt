package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AnswerContract.Type
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import cz.axiom.omega.core.step0.ExactMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HYPERFORGE M4: Exact Engine. Orákulum = nezávisle známé hodnoty (učebnicové příklady, standardní anuitní tabulky),
 * ne výstup stejného kódu. Metamorfní: obal otázky nemění výsledek; „měsíčně“ se neztratí.
 */
class ExactEngineTest {
    private val ctx = Reflex.Ctx(Instant.Ctx(), threshold = 85)
    private fun r(q: String) = Reflex.answer(q, ctx)
    private fun has(q: String, vararg parts: String) {
        val a = assertNotNull(r(q), q)
        val t = a.text.replace('\u00a0', ' ')
        parts.forEach { assertTrue(it in t, "$q ⇒ chci „$it“, dostal: ${a.text}") }
        assertEquals(Type.EXACT, a.contract?.type, q)
        assertTrue("Postup" in a.text, "chybí výpočetní stopa: ${a.text}")
    }

    @Test fun statistics() {
        has("průměr čísel 4, 8, 15, 16, 23, 42", "**18**")
        has("medián 3 1 4 1 5 9 2 6", "**3,5**")
        has("směrodatná odchylka 2, 4, 4, 4, 5, 5, 7, 9", "σ = **2**", "s = **2,1381**")
        has("modus 1, 2, 2, 3, 3, 3", "**3**")
        has("průměr čísel 2,5; 3,1; 4", "**3,2**")
        // desetinná čárka × oddělovač: „4,8,15“ nejde jistě přečíst → reflex neodpoví (zdrží se)
        assertNull(r("průměr 4,8,15"))
        assertEquals(Type.ABSTAIN, ExactMath.answer("průměr 4,8,15")?.contract?.type)
    }

    @Test fun numberTheory() {
        has("je 97 prvočíslo", "Ano")
        has("je 91 prvočíslo", "není", "7 × 13")
        has("je 1 prvočíslo", "není")
        has("rozlož 360 na prvočísla", "2^3 · 3^2 · 5")
        has("největší společný dělitel 48 a 180", "**12**")
        has("nejmenší společný násobek 12 a 18", "**36**")
        has("faktoriál 20", "2432902008176640000")
        has("10 nad 3", "**120**")
        has("kombinační číslo 49 nad 6", "13983816")
    }

    @Test fun geometryBasesUnitsBmi() {
        has("obsah kruhu o poloměru 5 cm", "78,5398 cm²")
        has("přepona trojúhelníku 3 a 4", "**5**")
        has("objem koule r=3", "113,0973")
        has("převeď 0xFF na desítkovou", "**255**")
        has("255 do dvojkové", "0b11111111")
        has("4096 do šestnáctkové soustavy", "0x1000")
        has("kolik bajtů je 1 GiB", "1 073 741 824")
        has("kolik bitů je 2 MB", "16 000 000", "16 777 216")
        val b = assertNotNull(r("BMI 180 cm 75 kg")); assertTrue("23,1" in b.text && "ne diagnóza" in b.text, b.text)
    }

    @Test fun financeExplicitInputsOnly() {
        has("měsíční splátka úvěru 200000 Kč na 5 let při úroku 7 %", "3 960,24 Kč", "37 614,38")
        has("hypotéka 4 000 000 na 30 let úrok 5 %", "21 472,86 Kč")
        has("složené úročení 100000 Kč 5 % 10 let", "162 889,46")
        has("kolik budu mít za 10 let při 5 % z 100000 Kč měsíčně", "164 700,95")
        // chybí sazba → NIC se nedosazuje: reflex mlčí (převezme mozek), Exact Engine řekne co chybí
        assertNull(r("spočítej mi hypotéku na 3 miliony na 30 let"))
        val ab = assertNotNull(ExactMath.answer("splátka hypotéky 3 000 000 Kč na 30 let"))
        assertEquals(Type.ABSTAIN, ab.contract?.type); assertTrue("úrokovou sazbu" in ab.text)
        assertNull(r("co je hypotéka"))
    }

    @Test fun metamorphicWrappersDoNotChangeResult() {
        val pairs = listOf(
            "je 97 prvočíslo" to "prosím tě, je 97 prvočíslo?",
            "kolik budu mít za 10 let při 5 % z 100000 Kč měsíčně" to "hele, kolik budu mít za 10 let při 5 % z 100000 Kč měsíčně?",
            "medián 3 1 4 1 5 9 2 6" to "Medián 3 1 4 1 5 9 2 6.",
        )
        pairs.forEach { (a, b) ->
            val x = assertNotNull(r(a), a).text.substringBefore("_Postup"); val y = assertNotNull(r(b), b).text.substringBefore("_Postup")
            assertEquals(x, y, "obal změnil výsledek: $a × $b")
        }
    }
}
