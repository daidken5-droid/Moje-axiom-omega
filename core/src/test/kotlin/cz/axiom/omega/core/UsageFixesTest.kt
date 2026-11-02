package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.hub.Web
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Opravy podle reportů z reálného používání (8.8). */
class UsageFixesTest {
    private val article = "Sněmovna v pátek schválila v prvním čtení zrušení televizního a rozhlasového poplatku. " +
        "Druhé čtení by se podle premiéra mohlo konat v lednu. Zaměstnanci ČT a ČRo ohlásili na úterý jednodenní stávku. "

    @Test fun readsArticleNotMenu() {
        val menu = (1..40).joinToString("") { "<li><a href='/a$it'>Titulek jiného článku číslo $it</a></li>" }
        val html = "<html><head><title>ČT24</title></head><body><nav><ul>$menu</ul></nav><header>Hlavička</header>" +
            "<div class='related'><ul>$menu</ul></div><article><h1>Poplatky</h1>" + (1..4).joinToString("") { "<p>$article</p>" } + "</article><footer>patička</footer></body></html>"
        val t = Web.mainContent(html)
        assertTrue(t.startsWith("Sněmovna") || t.contains("Sněmovna v pátek"), t.take(200))
        assertFalse(t.contains("Titulek jiného článku"), t.take(300))
        // strukturovaná data JSON-LD mají přednost
        val ld = "<script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"articleBody\":\"${article.repeat(4)}\"}</script>"
        assertTrue(Web.mainContent("<html><body>$ld<nav>$menu</nav></body></html>").contains("Druhé čtení"))
    }

    @Test fun reportCasesFromRealUse() {
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        // report 1: holá fráze → reflex, ne mozek
        assertTrue(Reflex.answer("Prezident České republiky", c)?.text?.contains("Pavel") == true)
        assertTrue(Reflex.answer("premiér", c)?.text?.contains("Babiš") == true)
        // report 1: nová otázka na jiné téma → telefon bez staré výměny; navazující otázka historii dostane
        assertTrue(Conversation.standalone("Prezident České republiky", "Kdy byla bitva na Bílé hoře?"))
        assertFalse(Conversation.standalone("a kdo ji vyhrál?", "Kdy byla bitva na Bílé hoře?"))
        assertFalse(Conversation.standalone("proč?", "Kdy byla bitva na Bílé hoře?"))
        // ozvěna: stejná odpověď na jinou otázku
        val bh = "**8. 11. 1620** – Bitva na Bílé hoře – porážka českých stavů, začátek rekatolizace a pobělohorského období."
        assertTrue(Conversation.isEcho("**8. 11. 1620** – Bitva na Bílé hoře (porážka českých stavů, začátek rekatolizace).", bh, "Prezident České republiky", "Kdy byla bitva na Bílé hoře?"))
        assertFalse(Conversation.isEcho("Prezidentem ČR je Petr Pavel.", bh, "Prezident České republiky", "Kdy byla bitva na Bílé hoře?"))
        // report 2: oprava od uživatele
        assertTrue(Conversation.isCorrection("Špatně hraje po Petr Rychlý"))
        assertTrue(Conversation.isCorrection("Ne, hraje ho Petr Rychlý"))
        assertFalse(Conversation.isCorrection("Kdo hraje Čestmíra Mázla?"))
        assertFalse(Conversation.standalone("Špatně hraje po Petr Rychlý", "kdo hraje Čestmíra Mázla v Ordinaci?"))
        // report 2: artefakty gpt-oss a značka sebereflexe
        assertTrue(Conversation.clean("hraje **Petr Kostka**【zdroj‑neznámý, ale běžně uváděný】.") == "hraje **Petr Kostka**.")
        assertTrue(Conversation.clean("seriálu *Ordinace*【4】.") == "seriálu *Ordinace*.")
        val p = cz.axiom.omega.core.self.Reflection.parse("Odpověď\n⟨SEBEREFLEXE⟩\nJistota: 90 %\nPoučení: Vždy ověřuj údaje. ⟨/SEBEREFLEXE⟩")
        assertFalse(p.lesson!!.contains("SEBEREFLEXE"), p.lesson)
        // report 2: OpenRouter – nejsilnější obecný model, ne malý na kód
        val or = cz.axiom.omega.core.llm.FreeCloud.provider("openrouter")!!
        val pick = cz.axiom.omega.core.llm.FreeCloud.pickModel(or, listOf("cohere/north-mini-code:free", "meta-llama/llama-3.1-8b-instruct:free", "qwen/qwen3-235b-a22b-2507:free", "paid/x"))
        assertTrue(pick == "qwen/qwen3-235b-a22b-2507:free", pick)
    }
}

class DeltaVersionTest {
    @Test fun tagsAndUpdateOrder() {
        val u = cz.axiom.omega.core.update.Updates
        kotlin.test.assertEquals("Delta I" to 1000, u.parseTag("avior-delta-i-c1000"))
        kotlin.test.assertEquals("Delta II" to 1001, u.parseTag("avior-delta-ii-c1001"))
        kotlin.test.assertEquals("8.9.0" to 890, u.parseTag("avior-v8.9.0"))
        // Delta I (1000) je novější než 8.9.0 (890); Delta II než Delta I
        assertTrue(u.newer(u.parseTag("avior-delta-i-c1000")!!.second, 890))
        assertTrue(u.newer(1001, 1000)); assertFalse(u.newer(1000, 1000))
        kotlin.test.assertEquals("Delta III" to 1002, u.parseTag("avior-delta-iii-c1002")); assertTrue(u.newer(1002, 1001))
        // Delta III: nové knihovny a zdroj v názvu výsledku (reflex věří jen české Wikipedii)
        val L = cz.axiom.omega.core.library.Libraries
        kotlin.test.assertEquals("Wikizdroje", L.of("wikisource-cs")!!.source)
        kotlin.test.assertEquals("wikimedia/wikisource", L.of("wikisource-cs")!!.dataset)
        kotlin.test.assertEquals("Wikipedie", L.label("wiki-cs")); kotlin.test.assertEquals("Wikipedie", L.label(null))
        assertFalse(cz.axiom.omega.core.agent.WikiReflex.titleMatches("Wikipedia PL: Praga", "praga"))
        assertFalse(cz.axiom.omega.core.agent.WikiReflex.titleMatches("Wikizdroje: Babička", "babicka"))
        assertTrue(cz.axiom.omega.core.agent.WikiReflex.titleMatches("Wikipedie: Babička", "babicka"))
        kotlin.test.assertEquals("library-wikisource-cs-20231201", cz.axiom.omega.core.library.Prebuilt.tag(L.of("wikisource-cs")!!))
    }
}

class SentinelTest {
    private val lib = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> =
            if ("gott" in cz.axiom.omega.core.util.TextUtil.fold(query).lowercase())
                listOf(Snippet(2, "Wikipedie: Karel Gott", "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl český zpěvák populární hudby.", "wiki"))
            else emptyList()
    }

    @Test fun flagsWrongBirthAndDeath() {
        val f = Sentinel.check("**Karel Gott** se narodil v roce 1938 v Plzni a proslavil se jako Zlatý slavík.", lib)
        assertTrue(f.size == 1 && f[0].wiki.contains("14. července 1939") && f[0].claimed.contains("1938"), f.toString())
        val g = Sentinel.check("Zpěvák Karel Gott (1939–2018) byl legenda.", lib)
        assertTrue(g.size == 1 && g[0].claimed.contains("2018") && g[0].wiki.contains("2019"), g.toString())
    }

    @Test fun silentWhenCorrectOrUnknown() {
        assertTrue(Sentinel.check("Karel Gott se narodil 14. července 1939 v Plzni.", lib).isEmpty())
        assertTrue(Sentinel.check("Karel Gott (1939–2019) byl legenda české pop music.", lib).isEmpty())
        assertTrue(Sentinel.check("Jan Novák se narodil v roce 1950.", lib).isEmpty())          // heslo neexistuje → mlčí
        assertTrue(Sentinel.check("Bitva na Bílé hoře proběhla v roce 1620.", lib).isEmpty())   // není životopisné tvrzení
        assertTrue(Sentinel.check("Bez letopočtu.", lib).isEmpty())
    }

    // Delta III: celé datum, jmenovci, rok jiné události, založení, nejistá data
    private val lib3 = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> {
            val q = cz.axiom.omega.core.util.TextUtil.fold(query).lowercase()
            return when {
                "gott" in q -> listOf(Snippet(2, "Wikipedie: Karel Gott", "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl český zpěvák.", "wiki"))
                "novak" in q -> listOf(
                    Snippet(3, "Wikipedie: Jan Novák (fotbalista)", "Jan Novák (* 12. 3. 1985 Brno) je český fotbalista.", "wiki"),
                    Snippet(4, "Wikipedie: Jan Novák (politik)", "Jan Novák (5. 5. 1921 – 1. 1. 1990) byl politik.", "wiki"))
                "karlova" in q -> listOf(Snippet(5, "Wikipedie: Univerzita Karlova", "Univerzita Karlova je nejstarší vysoká škola ve střední Evropě. Byla založena roku 1348 Karlem IV.", "wiki"))
                "kosmas" in q -> listOf(Snippet(6, "Wikipedie: Kosmas Pražský", "Kosmas Pražský (asi 1045 – 21. října 1125 Praha) byl kronikář.", "wiki"))
                "polsk" in q || "warszaw" in q -> listOf(Snippet(7, "Wikipedia PL: Karel Gott", "Karel Gott (ur. 14 lipca 1939 w Pilźnie, zm. 1 października 2018)", "wiki-pl"))
                else -> emptyList()
            }
        }
    }

    @Test fun fullDatesHomonymsFoundingDelta3() {
        // stejný rok, jiný den → Strážce to pozná, když odpověď uvádí celé datum
        val d = Sentinel.check("Karel Gott se narodil 14. srpna 1939 v Plzni.", lib3)
        assertTrue(d.size == 1 && d[0].wiki.contains("14. července 1939"), d.toString())
        assertTrue(Sentinel.check("Karel Gott se narodil 14. 7. 1939 v Plzni.", lib3).isEmpty())
        assertTrue(Sentinel.check("Karel Gott (14. 7. 1939 – 1. 10. 2019) byl zpěvák.", lib3).isEmpty())
        assertTrue(Sentinel.check("Karel Gott (* 1938) byl zpěvák.", lib3).size == 1)
        // jmenovci → mlčí (nevíme, o kom odpověď mluví)
        assertTrue(Sentinel.check("Jan Novák se narodil v roce 1950.", lib3).isEmpty())
        // rok patří jiné události ve vedlejší větě → mlčí
        assertTrue(Sentinel.check("Karel Gott se narodil v Plzni, kde v roce 1945 skončila válka.", lib3).isEmpty())
        // založení
        val f = Sentinel.check("Univerzita Karlova byla založena v roce 1350 císařem.", lib3)
        assertTrue(f.size == 1 && f[0].wiki.contains("1348"), f.toString())
        assertTrue(Sentinel.check("Univerzita Karlova byla založena roku 1348.", lib3).isEmpty())
        // nejisté datum v hesle („asi 1045“) → mlčí
        assertTrue(Sentinel.check("Kosmas Pražský se narodil roku 1050.", lib3).isEmpty())
        kotlin.test.assertEquals("1348", Sentinel.foundingYear("Univerzita Karlova je škola. Byla založena roku 1348 Karlem IV."))
    }
}
