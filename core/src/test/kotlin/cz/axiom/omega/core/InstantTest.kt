package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.KnowledgeSource
import cz.axiom.omega.core.agent.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InstantTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 14, 5, 0, 0, ZoneId.of("Europe/Prague"))
    private val ctx = Instant.Ctx(now = now, userName = "Dan", battery = 64, charging = false)

    @Test fun simpleQuestionsAnswerInstantly() {
        assertEquals("Je 14:05.", Instant.answer("Kolik je hodin?", ctx)!!.text)
        assertEquals("Dnes je pondělí 2. listopadu 2026.", Instant.answer("Jaký je dnes den?", ctx)!!.text)
        assertTrue(Instant.answer("Ahoj", ctx)!!.text.startsWith("Ahoj, Dan"))
        assertEquals("Jmenuješ se Dan.", Instant.answer("jak se jmenuji?", ctx)!!.text)
        assertTrue(Instant.answer("kolik mám baterie", ctx)!!.text.contains("64 %"))
        assertTrue(Instant.answer("Kolik je 12*7+3?", ctx)!!.text.contains("**87**"))
        assertTrue(Instant.answer("(5+3)/2", ctx)!!.text.contains("**4**"))
        assertTrue(Instant.answer("kolik je 15 % z 2400", ctx)!!.text.contains("**360**"))
    }

    @Test fun complexQuestionsGoToBrain() {
        assertNull(Instant.answer("Jak funguje tepelné čerpadlo a vyplatí se mi?", ctx))
        assertNull(Instant.answer("Ahoj, potřebuju napsat výpověď z nájmu", ctx))
        assertNull(Instant.answer("Kolik je hodin v Tokiu?", ctx))
        assertNull(Instant.answer("2024-2025", ctx))
        assertNull(Instant.answer("co je láska", ctx)) // bez znalostí žádná definice
    }

    @Test fun definitionFromOfflineKnowledge() {
        val k = object : KnowledgeSource {
            override fun search(query: String, limit: Int) = listOf(
                Snippet(1, "Tepelné čerpadlo", "# Tepelné čerpadlo\nTepelné čerpadlo je zařízení, které odebírá teplo z okolí a předává ho do topného systému. Využívá k tomu např. kompresor. Třetí věta už se nevypíše.", "Wikipedie"),
            )
        }
        val a = Instant.answer("Co je tepelné čerpadlo?", ctx.copy(knowledge = k))
        assertNotNull(a)
        assertTrue(a!!.text.startsWith("Tepelné čerpadlo je zařízení"))
        assertTrue(a.text.contains("kompresor."))
        assertTrue(!a.text.contains("Třetí věta"))
        assertNull(Instant.answer("Co je jaderná fúze?", ctx.copy(knowledge = k)))
    }
}

class ClaudeBoostRouterTest {
    private val eng = listOf("local", "claude")
    @Test fun hardQuestionsGoToClaudeSimpleStayLocal() {
        val ctx = cz.axiom.omega.core.agent.Router.Context(true, eng, boost = "claude")
        assertEquals("local", cz.axiom.omega.core.agent.Router.route("Kolik stojí chleba?", ctx).engine)
        assertEquals("claude", cz.axiom.omega.core.agent.Router.route("/agent najdi nejlevnější let do Říma", ctx).engine)
        assertEquals("local", cz.axiom.omega.core.agent.Router.route("/agent najdi let", ctx.copy(online = false)).engine)
        assertEquals("local", cz.axiom.omega.core.agent.Router.route("/agent najdi let", ctx.copy(boost = null)).engine)
    }
}
