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
        assertEquals(Earl.Path.RETRIEVE, d("co je to fotosyntéza?", Earl.Ctx(knowledge = wiki, cloud = true)).path) // holá definice: stačí heslo i s cloudem
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

class ParaphraseCacheTest {
    @Test fun cachedAnswerRecognizesRephrasedQuestion() {
        val e = Reflex.Entry("Jaké je hlavní město Austrálie?", "Hlavním městem Austrálie je **Canberra**.", 95, good = true, at = System.currentTimeMillis())
        // jiná formulace, slang a překlep → stejná ověřená odpověď z paměti, bez mozku
        val a = Reflex.cached("hele, jaký je hlavák austrálie", listOf(e))
        kotlin.test.assertNotNull(a); kotlin.test.assertTrue(a.text.contains("Canberra"))
        kotlin.test.assertNull(Reflex.cached("jaké je počasí v austrálii", listOf(e)))
        // ✗ u přeformulované otázky musí najít a smazat i původní záznam
        kotlin.test.assertTrue(Reflex.sameQuestion("hele, jaký je hlavák austrálie", e.question))
        kotlin.test.assertTrue(!Reflex.sameQuestion("jaké je počasí v austrálii", e.question))
    }
}

class EarlRealDataTest {
    // skutečný tvar dat z offline knihovny: název s předponou „Wikipedie: “
    private val lib = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> {
            val q = cz.axiom.omega.core.util.TextUtil.fold(query).lowercase()
            return listOfNotNull(
                Snippet(1, "Wikipedie: Fotosyntéza", "Fotosyntéza je biochemický proces, při kterém rostliny přeměňují světelnou energii na chemickou. Vzniká při ní kyslík a glukóza z oxidu uhličitého a vody. Probíhá v chloroplastech.", "wiki").takeIf { "fotosynt" in q },
                Snippet(2, "Wikipedie: Karel Gott", "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl český zpěvák populární hudby, jeden z nejúspěšnějších interpretů. Proslul jako „Zlatý slavík“.", "wiki").takeIf { "gott" in q },
                Snippet(3, "Wikipedie: Telč", "Telč je město v okrese Jihlava v Kraji Vysočina. Leží na jihozápadě Moravy asi 25 km jihozápadně od Jihlavy. Historické centrum je zapsáno na seznamu UNESCO.", "wiki").takeIf { "telc" in q },
                Snippet(4, "Wikipedie: Brno (město)", "Brno je statutární město, druhé největší v Česku. Je přirozeným centrem Moravy.", "wiki").takeIf { "brn" in q },
            )
        }
    }

    @Test fun earlFindsRealWikiTitles() {
        // dřív: předpona „Wikipedie: “ → přesná shoda nikdy → výňatek se na telefonu nespustil
        kotlin.test.assertEquals(Earl.Path.RETRIEVE, Earl.decide(Pavucina.of("co je fotosyntéza"), Earl.Ctx(knowledge = { lib.search(it, 3) })).path)
    }

    @Test fun earlClassifiesConversationalOpeners() {
        val d = Earl.decide(Pavucina.of("Dobře jiná otázka kdo hraje Čestmíra Mázla v seriálu ordinace v růžové zahradě 2?"), Earl.Ctx())
        kotlin.test.assertEquals("fakt", d.category)
        kotlin.test.assertEquals(Earl.Path.LIGHT, d.path)
    }

    @Test fun wikiReflexAnswersFromOfflineWikipedia() {
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false, knowledge = lib))
        fun a(q: String) = Reflex.answer(q, c)?.text ?: "∅"
        kotlin.test.assertTrue(a("kdy se narodil Karel Gott").contains("14. července 1939"), a("kdy se narodil Karel Gott"))
        kotlin.test.assertTrue(a("kdy zemřel karel gott?").contains("1. října 2019"), a("kdy zemřel karel gott?"))
        kotlin.test.assertTrue(a("kde leží Telč").contains("okrese Jihlava") || a("kde leží Telč").contains("Leží"), a("kde leží Telč"))
        kotlin.test.assertTrue(a("kdo byl Karel Gott").contains("zpěvák"), a("kdo byl Karel Gott"))
        kotlin.test.assertTrue(a("co je Brno").contains("statutární město"), a("co je Brno"))
        // neznámé heslo → mlčí (rozhodne mozek)
        kotlin.test.assertEquals("∅", a("kdy se narodil Jan Novák z Horní Dolní"))
    }
}

/** Rozhodovací tabulka E-ARL na skutečných otázkách z reportů a běžném používání. */
class EarlDecisionTableTest {
    @Test fun table() {
        val expect = listOf(
            "Dobře jiná otázka kdo hraje Čestmíra Mázla v seriálu ordinace v růžové zahradě 2?" to ("fakt" to Earl.Path.LIGHT),
            "Kdy se narodil Mozart" to ("fakt" to Earl.Path.LIGHT),
            "Co si myslíš o aktuálním dění ve světě ?" to ("aktuální" to Earl.Path.BRAIN),
            "Kolik stojí nový iPhone?" to ("aktuální" to Earl.Path.BRAIN),
            "Jaké bude zítra počasí v Brně?" to ("aktuální" to Earl.Path.BRAIN),
            "Napiš funkci v pythonu která seřadí seznam" to ("kód" to Earl.Path.BRAIN),
            "Napiš mi email šéfovi, že zítra nepřijdu" to ("tvorba" to Earl.Path.BRAIN),
            "Vysvětli mi rozdíl mezi ETF a akciemi" to ("analýza" to Earl.Path.BRAIN),
            "Co je to kvantová provázanost" to ("definice" to Earl.Path.LIGHT),
        )
        val wrong = expect.mapNotNull { (q, e) -> Earl.decide(Pavucina.of(q), Earl.Ctx(cloud = true)).let { d -> if (d.category != e.first || d.path != e.second) "$q → ${d.category}/${d.path}, čekáno ${e.first}/${e.second}" else null } }
        kotlin.test.assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
