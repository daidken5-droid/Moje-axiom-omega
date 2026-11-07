package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.FreeCloud
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Δ Strážce faktů pro režim BEZ MOZKU: ověřuje odpovědi z paměti a tabulek, levně a s pamětí výsledků. */
class SentinelNoBrainTest {
    private val searches = AtomicInteger()
    private val k = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> {
            searches.incrementAndGet()
            return if ("gott" in cz.axiom.omega.core.util.TextUtil.fold(query).lowercase())
                listOf(Snippet(2, "Wikipedie: Karel Gott", "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl český zpěvák.", "wiki"))
            else emptyList()
        }
    }

    @Test fun wrongCachedAnswerIsRejectedAndWikipediaAnswersInstead() {
        val rejected = mutableListOf<Instant.Answer>()
        val cache = listOf(Reflex.Entry("kdy se narodil karel gott", "Karel Gott se narodil v roce 1938 v Plzni.", 95, true, System.currentTimeMillis()))
        val c = Reflex.Ctx(Instant.Ctx(knowledge = k), cache = cache, onRejected = { a, _ -> rejected += a })
        val a = Reflex.answer("kdy se narodil karel gott", c)
        assertNotNull(a)
        assertTrue(a.text.contains("14. července 1939"), a.text)        // odpověděla offline Wikipedie, ne chybná paměť
        assertTrue(rejected.isNotEmpty() && Reflex.fromCache(rejected[0]))
        // správná uložená odpověď projde
        val ok = listOf(Reflex.Entry("kdy se narodil karel gott", "Karel Gott se narodil 14. července 1939 v Plzni.", 95, true, System.currentTimeMillis()))
        val b = Reflex.answer("kdy se narodil karel gott", Reflex.Ctx(Instant.Ctx(knowledge = k), cache = ok))
        assertTrue(b!!.kind == Proof.Origin.USER.label, b.kind) // ✓ uživatele = osobní potvrzení (M1-A)
    }

    @Test fun cheapSieveAndMemo() {
        assertFalse(Sentinel.mayClaim("Je 14:05."))
        assertFalse(Sentinel.mayClaim("Výsledek je **1939**."))                 // rok bez tvrzení
        assertTrue(Sentinel.mayClaim("Karel Gott (1939–2019) byl zpěvák."))
        assertTrue(Sentinel.mayClaim("Karel Gott se narodil roku 1939."))
        // výpočty a čas → síto, žádné hledání v knihovně
        val before = searches.get()
        repeat(1000) { Sentinel.check("Je 14:05. Výsledek **87**, rok 2026.", k) }
        assertEquals(before, searches.get())
        // stejná odpověď podruhé → z paměti výsledků, bez hledání
        Sentinel.reset()
        Sentinel.check("Karel Gott se narodil v roce 1938.", k)
        val after1 = searches.get()
        repeat(50) { assertEquals(1, Sentinel.check("Karel Gott se narodil v roce 1938.", k).size) }
        assertEquals(after1, searches.get())
        // síto je rychlé: 100 000 běžných odpovědí pod 300 ms
        val t = System.nanoTime()
        repeat(100_000) { Sentinel.mayClaim("Podle kurzu je 100 EUR asi **2 430 Kč**. Kurz z 2. 11. 2026.") }
        assertTrue((System.nanoTime() - t) / 1e6 < 300)
    }

    @Test fun cloudPrimaryAndModelList() {
        assertEquals("nanogpt", FreeCloud.order(false, "nanogpt").first().id)
        assertEquals("cerebras", FreeCloud.order(false, "").first().id)
        assertTrue(FreeCloud.order(true, null).first().paid)
        val p = FreeCloud.provider("nanogpt")!!
        val list = FreeCloud.usableModels(p, listOf("text-embedding-3", "openai/gpt-oss-120b", "moonshotai/Kimi-K2-Instruct-0905", "tiny-coder-1b", "deepseek-ai/DeepSeek-V3.1-Terminus", "flux-dev"))
        assertEquals(listOf("deepseek-ai/DeepSeek-V3.1-Terminus", "moonshotai/Kimi-K2-Instruct-0905", "openai/gpt-oss-120b", "tiny-coder-1b"), list)
        assertTrue(listOf("ollama", "synthetic").all { FreeCloud.provider(it)!!.paid })
    }
}
