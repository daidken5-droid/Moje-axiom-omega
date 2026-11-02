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
