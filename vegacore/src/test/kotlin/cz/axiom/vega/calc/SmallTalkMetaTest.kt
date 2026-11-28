package cz.axiom.vega.calc

import cz.axiom.vega.core.Router
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. (uživatel zvyklý na ChatGPT): otázky na VEGA samotnou bez modelu – dřív „potřebuji model v cloudu“, dvě dokonce rešerše na web. */
class SmallTalkMetaTest {
    private val qs = mapOf(
        "Jsi lepší než ChatGPT?" to "ChatGPT", "Čím se lišíš od ChatGPT?" to "telefon", "Kdo tě vytvořil?" to "VEGA", "Jsi umělá inteligence?" to "model",
        "Funguješ bez internetu?" to "bez", "Posíláš moje data někam?" to "Bez sítě", "Kolik to stojí?" to "zdarma", "Jaký model používáš?" to "Bez sítě",
        "Pamatuješ si naše minulé konverzace?" to "pamatuj si", "Umíš česky?" to "čeština", "Proč mi neodpovídáš na všechno?" to "model", "Jak tě mám používat?" to "15 %")

    @Test fun answersQuestionsAboutVegaOffline() {
        for ((q, key) in qs) {
            val a = SmallTalk.answer(q, airgap = true)
            assertNotNull(a, q)
            assertTrue(a.contains(key, ignoreCase = true), "$q → $a")
            assertTrue(!a.contains("potřebuji model v cloudu"), q)
        }
        assertNull(SmallTalk.answer("Co je inflace?", true))
        assertNull(SmallTalk.answer("Je bezpečné otevřít adresu z NFC štítku?", true))
    }

    @Test fun notSentToWebResearch() {
        val ctx = Router.Ctx(false, null, false, true, null)
        for (q in listOf("Čím se lišíš od ChatGPT?", "Funguješ bez internetu?", "Jsi lepší než Claude?"))
            assertTrue(Router.route(q, ctx) !is Router.Intent.Research, q)
    }

    /** Povely, jak je píše uživatel ChatGPT (lomítka, angličtina, „nový chat“) – dřív rešerše na web nebo „potřebuji model“. */
    @Test fun chatGptStyleCommands() {
        val want = mapOf("nový chat" to "Nová konverzace", "Nová konverzace" to "Nová konverzace", "/new" to "Nová konverzace", "začni znovu" to "Nová konverzace",
            "/help" to "15 %", "what can you do?" to "15 %", "/clear" to "Smazat", "stop" to "Zastavit")
        for ((q, key) in want) { val a = SmallTalk.answer(q, true); assertNotNull(a, q); assertTrue(a.contains(key), "$q → $a") }
        val ctx = Router.Ctx(false, null, false, true, null)
        for (q in listOf("nový chat", "nová konverzace", "what can you do?")) assertTrue(Router.route(q, ctx) !is Router.Intent.Research, q)
    }
}
