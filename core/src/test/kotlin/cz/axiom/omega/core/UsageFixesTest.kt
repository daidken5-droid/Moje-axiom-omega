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
}
