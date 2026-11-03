package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.AnswerContract.Type
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.core.step0.Step0
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HYPERFORGE M3: falešné EV, datované kurzy, typovaný kontrakt odpovědi. */
class Step0ContractTest {
    @Test fun evNeedsExplicitProbability() {
        assertNull(Step0.ev("Sleva 20 % a kurz 2,0"))
        assertNull(Step0.ev("kurz 2,1, DPH 21 %"))
        assertNull(Step0.ev("kurz 1,9 a marže sázkovky 5 %"))
        assertNotNull(Step0.ev("kurz 2,1 a pravděpodobnost 55 %"))
        assertNotNull(Step0.ev("kurz 2,1, šance 55 %"))
        assertNotNull(Step0.ev("kurz 2,1 a 55 % šance na výhru"))
    }

    @Test fun fallbackRatesAreVisiblyDatedAndNotCurrent() {
        val l = Rates.FALLBACK.label()
        assertTrue("NENÍ aktuální" in l && "10/2026" in l, l)
        assertTrue(Rates.FALLBACK.stale(LocalDate.of(2026, 11, 3)))
        val cnb = Rates(mapOf("EUR" to 24.3), "31.10.2026", "ČNB")
        assertTrue(!cnb.stale(LocalDate.of(2026, 11, 3)) && cnb.stale(LocalDate.of(2026, 11, 20)))
    }

    @Test fun contractTypesByModule() {
        fun ask(q: String) = Reflex.answer(q, Reflex.Ctx(Instant.Ctx(), threshold = 70))
        assertEquals(Type.EXACT, assertNotNull(ask("kolik je 17 * 23")).contract?.type)
        val eur = assertNotNull(ask("kolik je 50 eur"))
        assertEquals(Type.ESTIMATE, eur.contract?.type, eur.kind)            // vestavěný kurz = orientační vstup
        assertTrue(eur.contract!!.asOf!!.contains("10/2026"))
        assertTrue("NENÍ aktuální" in eur.text, eur.text)
        assertTrue(eur.contract!!.elapsedNs > 0)
    }

    @Test fun opinionIsNeverFact() {
        val a = assertNotNull(Reflex.answer("jaký je pravicový pohled na daně", Reflex.Ctx(Instant.Ctx(), threshold = 70)))
        assertEquals(Type.ESTIMATE, a.contract?.type, a.kind)
        assertTrue("ne fakta" in a.text || "názorový" in a.text)
    }

    @Test fun contractNeverStrengthens() {
        val c = AnswerContract(Type.ESTIMATE, "odhad")
        assertEquals(Type.ESTIMATE, c.weakenTo(Type.VERIFIED_FACT, "pokus o povýšení").type)
        assertEquals(Type.ABSTAIN, c.weakenTo(Type.ABSTAIN, "konflikt").type)
    }
}
