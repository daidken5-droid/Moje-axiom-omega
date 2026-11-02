package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EarlTest {
    private val wiki = { q: String ->
        if (cz.axiom.omega.core.util.TextUtil.fold(q).lowercase() == "fotosynteza")
            listOf(Snippet(1, "Fotosyntéza", "Fotosyntéza je biochemický proces, při kterém rostliny a sinice přeměňují světelnou energii na chemickou. Probíhá v chloroplastech. Vzniká při ní kyslík.", "wiki"))
        else emptyList()
    }
    private fun d(q: String, c: Earl.Ctx = Earl.Ctx(knowledge = wiki)) = Earl.decide(Pavucina.of(q), c)

    @Test fun routes() {
        assertEquals(Earl.Path.REFLEX, d("kolik je hodin", Earl.Ctx(reflexHit = true)).path)
        // definice s přesným heslem, mozek není v RAM ani cloud → výňatek, mozek se nenačítá
        assertEquals(Earl.Path.RETRIEVE, d("co je to fotosyntéza?").path)
        assertEquals("fotosynteza", d("co je to fotosyntéza?").entity)
        // stejná otázka, ale cloud je po ruce → lehký mozek (bohatší odpověď, pořád rychle)
        assertEquals(Earl.Path.LIGHT, d("co je to fotosyntéza?", Earl.Ctx(knowledge = wiki, cloud = true)).path)
        assertEquals(Earl.Path.LIGHT, d("kdy se narodil Mozart").path)
        assertEquals(Earl.Path.DEEP, d("kolik obyvatel má Praha a jak se to změnilo za 50 let").path)
        assertEquals(Earl.Path.DEEP, d("porovnej výhody a nevýhody tepelného čerpadla a plynového kotle pro rodinný dům v horách a navrhni strategii").path)
        assertEquals("tvorba", d("napiš mi básničku o kočce").category)
        assertTrue(d("napiš mi básničku o kočce").path in setOf(Earl.Path.BRAIN, Earl.Path.LIGHT))
    }

    @Test fun learnsFromBadRatings() {
        val th = mutableMapOf<String, Int>()
        repeat(2) { Earl.learn(th, "definice", Earl.Path.RETRIEVE, good = false) }
        // po dvou ✗ je práh 95 → výňatek už nestačí, jde mozek
        assertTrue(d("co je to fotosyntéza?", Earl.Ctx(knowledge = wiki, thresholds = th)).path != Earl.Path.RETRIEVE)
        repeat(3) { Earl.learn(th, "fakt", Earl.Path.LIGHT, good = false) }
        assertEquals(Earl.Path.BRAIN, d("kdy se narodil Mozart", Earl.Ctx(thresholds = th)).path)
    }

    @Test fun webSharesSignalsAndMemo() {
        assertSame(Pavucina.of("Jak funguje kompresor?"), Pavucina.of("Jak funguje kompresor?"))
        var calls = 0
        val src = object : KnowledgeSource { override fun search(query: String, limit: Int): List<Snippet> { calls++; return emptyList() } }
        val shared = Pavucina.shared("t", src)
        repeat(5) { shared.search("kompresor", 3) }
        assertEquals(1, calls)
        Pavucina.invalidateKnowledge(); shared.search("kompresor", 3)
        assertEquals(2, calls)
    }
}
