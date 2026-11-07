package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ε3: modul bez mozku rozumí heslovitým dotazům a propojuje témata (bez jazykového modelu). */
class NoBrainUnderstandingTest {
    private val ctx = Reflex.Ctx(Instant.Ctx(), threshold = 85)
    private fun has(q: String, vararg parts: String) {
        val a = assertNotNull(Reflex.answer(q, ctx), q)
        parts.forEach { assertTrue(it in a.text, "$q ⇒ chci „$it“, dostal: ${a.text}") }
    }

    @Test fun telegraphicQueries() {
        has("Gruzie měna", "GEL")
        has("Německo hlavní město", "Berlín")
        has("Slovensko předvolba", "+421")
        has("Polsko ISO kód", "PL", "POL")
        // nic se nedosazuje: neznámé heslovité spojení mlčí
        assertNull(Reflex.answer("Gotham City měna", ctx))
        assertNull(QuestionNorm.telegraphic("kolik je hodin"))
    }

    @Test fun relatedTopicsFromLibrary() {
        val kb = object : KnowledgeSource {
            override fun search(query: String, limit: Int) = listOf(
                Snippet(1, "Wikipedie: Fotosyntéza", "Fotosyntéza je biochemický proces, při kterém rostliny mění světelnou energii na chemickou. Probíhá v chloroplastech.", "w"),
                Snippet(2, "Wikipedie: Chlorofyl", "Chlorofyl je zelené barvivo, které umožňuje fotosyntézu v rostlinách.", "w"),
                Snippet(3, "Wikipedie: Chloroplast", "Chloroplast je organela, ve které probíhá fotosyntéza.", "w"),
                Snippet(4, "Wikipedie: Fotbal", "Fotbal je kolektivní míčový sport.", "w"),
            )
        }
        val a = assertNotNull(Reflex.answer("Fotosyntéza", Reflex.Ctx(Instant.Ctx(knowledge = kb), threshold = 85)))
        assertTrue("Souvisí:" in a.text && "Chlorofyl" in a.text && "Chloroplast" in a.text, a.text)
        assertTrue("Fotbal" !in a.text, "nesouvisející heslo: ${a.text}")
    }
}
