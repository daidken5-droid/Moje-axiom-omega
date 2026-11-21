package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.hub.Web
import cz.axiom.omega.core.hub.WebAnswer
import cz.axiom.omega.core.hub.WebGround
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.SelfState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ε1/Ε2: holý název tématu a webové podklady online modulu (bez sítě – web je podvržený). */
class TopicWebTest {
    @Test fun bareTopicDetection() {
        listOf("operation Prato", "Operation Prato?", "Fotosyntéza", "Karel Gott", "černá díra", "Operace Anthropoid", "Velká Morava").forEach {
            assertNotNull(Topic.bare(it), it)
        }
        listOf("ahoj", "díky moc", "co je fotosyntéza", "kolik je 5+5", "napiš mi báseň", "je Pluto planeta", "jak se máš", "ok",
            "/chat test", "zapni baterku", "a", "proč je nebe modré", "Kdy se narodil Karel Gott", "spočítej to").forEach {
            assertNull(Topic.bare(it), it)
        }
    }

    @Test fun earlTreatsBareTopicAsDefinition() {
        assertEquals("definice", Earl.category(Pavucina.of("operation Prato")))
        assertEquals("definice", Earl.category(Pavucina.of("Fotosyntéza")))
        // tvorba a pozdrav zůstávají
        assertEquals("tvorba", Earl.category(Pavucina.of("napiš mi email šéfovi")))
    }

    @Test fun wikiReflexAnswersBareHeadword() {
        val kb = object : KnowledgeSource {
            override fun search(query: String, limit: Int) = listOf(Snippet(1, "Wikipedie: Fotosyntéza",
                "Fotosyntéza je biochemický proces, při kterém se mění energie světelného záření na energii chemických vazeb. Probíhá v chloroplastech rostlin a sinic.", "wiki"))
        }
        val a = assertNotNull(WikiReflex.answer("Fotosyntéza", kb))
        assertTrue("biochemický proces" in a.text, a.text)
        assertNull(WikiReflex.answer("operation Prato", kb)) // heslo neodpovídá → mlčí (převezme web / mozek)
    }

    private val wikiPrato = Web.WikiPage("Operation Prato", "https://en.wikipedia.org/wiki/Operation_Prato",
        "Operation Prato was a fictional test operation used here only as a fixture. It took place in a test environment. It has no real-world meaning.")

    @Test fun webGroundMergesWikiAndSearchWithTimeout() {
        val r = WebGround.gather("Operation Prato", useCache = false,
            search = { listOf(Web.Hit("Prato – zprávy", "https://example.org/prato", "Článek o operaci")) },
            wiki = { _, lang -> if (lang == "en") wikiPrato else null })
        assertEquals(listOf("https://en.wikipedia.org/wiki/Operation_Prato", "https://example.org/prato"), r.snippets.map { it.source })
        // pomalý zdroj nezdrží odpověď déle než limit
        val slow = WebGround.gather("x", timeoutMs = 300, useCache = false, search = { Thread.sleep(3_000); emptyList() }, wiki = { _, _ -> null })
        assertTrue(slow.millis < 1_500, "trvalo ${slow.millis} ms")
        // výpadek / ochrana proti botům = prázdno, žádná výjimka
        assertTrue(WebGround.gather("y", useCache = false, search = { error("HTTP 202") }, wiki = { _, _ -> error("429") }).snippets.isEmpty())
    }

    @Test fun webAnswerWithoutBrainCitesSource() {
        val a = assertNotNull(WebAnswer.compose("operation Prato") { listOf(Snippet(-2, "Wikipedie: Operation Prato", wikiPrato.text, wikiPrato.url)) })
        assertTrue(wikiPrato.url in a.text && "fictional test operation" in a.text, a.text)
        assertEquals(AnswerContract.Type.VERIFIED_FACT, a.contract?.type)
        val b = assertNotNull(WebAnswer.compose("něco") { listOf(Snippet(-10, "Výsledek", "úryvek", "https://example.org/a")) })
        assertEquals(AnswerContract.Type.ESTIMATE, b.contract?.type)
        assertNull(WebAnswer.compose("nic") { emptyList() })
    }

    private class Spy : LlmEngine {
        override val id = "spy"; override val label = "Spy"; override val kind = EngineKind.KIMI_CLOUD
        override val contextBudgetChars = 100_000
        var lastUser = ""
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            lastUser = messages.last { it.role == Role.USER }.content
            return GenResult("Operation Prato je testovací operace [1].", model = "spy")
        }
    }
    private val noKb = object : KnowledgeSource { override fun search(query: String, limit: Int) = emptyList<Snippet>() }
    private val mem = object : MemoryStore {
        override fun all() = emptyList<MemoryItem>(); override fun remember(item: MemoryItem) = 0L; override fun forget(query: String) = 0
    }

    @Test fun cloudModelGetsWebGroundingAndSources() {
        val eng = Spy(); var asked = ""
        val o = Orchestrator(noKb, mem, { SelfState("Spy", "test", false) }, sleeper = {})
        val r = o.run(AskRequest("operation Prato", maxPasses = 1, reflect = false, webGround = { q -> asked = q; listOf(Snippet(-2, "Wikipedie: Operation Prato", wikiPrato.text, wikiPrato.url)) }), eng, CancelToken())
        assertEquals("operation Prato", asked)
        assertTrue("Co je „operation Prato“" in eng.lastUser && "fictional test operation" in eng.lastUser, eng.lastUser)
        assertTrue(r.sources.any { it.url == wikiPrato.url })
        // tvorba web nedostane
        var called = false
        o.run(AskRequest("napiš mi báseň o jaru", maxPasses = 1, reflect = false, webGround = { called = true; emptyList() }), Spy(), CancelToken())
        assertTrue(!called)
    }
}
