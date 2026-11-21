package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import cz.axiom.omega.core.kg.KnowledgeGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REBIRTH fáze V.5: deterministické odvozování přes graf (bez LLM), řetězec s podklady, žádná kauzalita. */
class KnowledgeGraphTest {
    private fun kg(q: String) = KnowledgeGraph.answer(q)
    private fun has(q: String, vararg parts: String) {
        val a = assertNotNull(kg(q), q)
        parts.forEach { assertTrue(it in a.text, "$q ⇒ chci „$it“: ${a.text}") }
        assertTrue("Řetězec:" in a.text && "podklady:" in a.text, a.text)
        assertEquals(AnswerContract.Type.VERIFIED_FACT, a.contract?.type)
    }

    @Test fun multiHopWithChain() {
        has("Jakou měnu používá země s hlavním městem Varšava?", "Polsko", "PLN", "hlavní město", "měna")
        has("Na jakém kontinentu leží Nairobi?", "Afrika", "Keňa", "odvozeno")
        has("Ve kterém státě je Canberra?", "Austrálie")
        has("Je Lima v Evropě?", "Ne", "Peru")
        has("Leží Praha v Evropě?", "Ano")
    }

    @Test fun refusesWhatItCannotDerive() {
        assertNull(kg("Na jakém kontinentu leží Gotham?"))                 // neznámá entita
        assertNull(kg("Způsobil pád Berlína změnu měny v Polsku?"))         // kauzalita se neodvozuje
        assertNull(kg("Jaká je měna?"))                                       // chybí článek řetězu
    }

    @Test fun reachableThroughReflexWithoutLlm() {
        val a = assertNotNull(Reflex.answer("Na jakém kontinentu leží Nairobi?", Reflex.Ctx(Instant.Ctx(), threshold = 85)))
        assertTrue("Afrika" in a.text, a.text)
    }
}
