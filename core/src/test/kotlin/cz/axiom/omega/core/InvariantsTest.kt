package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** CONVERGENCE: systémové invarianty AVIORu (chování, ne podoba kódu). */
class InvariantsTest {

    private class Mem : MemoryStore {
        val items = mutableListOf<MemoryItem>()
        override fun all() = items.toList()
        override fun remember(item: MemoryItem): Long { items += item.copy(id = items.size + 1L); return items.size.toLong() }
        override fun forget(query: String) = 0
    }

    /** B – provenience: bez kladného auditu Strážce nikdy „ověřeno Wikipedií“ (vlastnostní test, 2 000 náhodných vstupů). */
    @Test fun b_noWikiOriginWithoutProof() {
        val rnd = Random(7)
        val words = listOf("kdy", "se", "narodil", "zemřel", "Karel", "Gott", "1939", "byl", "vznikl", "Praha", "je", "založena", "1348", "a", ".")
        repeat(2_000) {
            val q = List(rnd.nextInt(1, 8)) { words.random(rnd) }.joinToString(" ")
            val a = List(rnd.nextInt(1, 20)) { words.random(rnd) }.joinToString(" ")
            assertNotEquals(Proof.Origin.WIKI, Proof.originOf(q, a, Sentinel.Audit.EMPTY))
            assertNotEquals(Proof.Origin.WIKI, Proof.originOf(q, a, Sentinel.Audit(emptyList(), listOf(Sentinel.Flag("x", "y", "z", "t")))))
        }
    }

    /** C – výpočetní rozpočet: cloud při samých chybách skončí po konečném počtu pokusů (≤ 2 na poskytovatele). */
    @Test fun c_cloudRetriesAreBounded() {
        val calls = AtomicInteger()
        val failing = object : LlmEngine {
            override val id = "f"; override val label = "f"; override val kind = EngineKind.FREE; override val contextBudgetChars = 10_000
            override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
                calls.incrementAndGet(); throw EngineException("HTTP 404 model not found")
            }
        }
        val members = FreeCloud.PROVIDERS.map { FreeCloud.Member(it, "k", "m") }
        val e = FreeCloudEngine(members, factory = { failing }, listModels = { _, _ -> listOf("jiny-model") })
        assertFailsWith<EngineException> { e.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
        assertTrue(calls.get() <= 2 * members.size, "pokusů ${calls.get()} > ${2 * members.size}")
    }

    /** D – paměťová konzistence: text, který si agent (model) uloží, se NESMÍ stát faktem, kterým reflex odpoví bez mozku. */
    @Test fun d_agentMemoryIsNotFact() {
        val mem = Mem()
        val save = Tools.offline(object : KnowledgeSource { override fun search(query: String, limit: Int) = emptyList<Snippet>() }, mem, { cz.axiom.omega.core.step0.Rates.FALLBACK }) { _, _ -> }
            .first { it.name == "memory_save" }
        save.run(buildJsonObject { put("text", JsonPrimitive("Uživatelův pes se jmenuje Rex.")) }, CancelToken())
        assertTrue(mem.items.none { it.kind == MemoryKind.FACT || it.kind == MemoryKind.PROFILE }, mem.items.toString())
        val a = Reflex.recall("Jak se jmenuje můj pes?", mem.all())
        assertTrue(a == null || a.confidence < 85, "reflex odpověděl z neověřené poznámky modelu: $a")
        // fakt od uživatele (oprava, profil) reflex použít smí
        val user = listOf(MemoryItem(1, MemoryKind.FACT, "Uživatelův pes se jmenuje Rex."))
        assertTrue((Reflex.recall("Jak se jmenuje můj pes?", user)?.confidence ?: 0) >= 85)
    }

    /** E – soukromí: v air-gapu žádný síťový požadavek; nešifrované http jen do lokální sítě. */
    @Test fun e_networkGuards() {
        Http.airgap = true
        try { assertFailsWith<EngineException> { Http.open("https://api.groq.com/openai/v1/models") } } finally { Http.airgap = false }
        assertFailsWith<EngineException> { Http.requireAllowed("http://example.com/v1") }
        Http.requireAllowed("http://192.168.1.10:8080/v1")
    }
}

/** G – žádná falešná přesnost: nejisté datum v hesle („asi 1045“) se nesmí vypsat jako přesné. */
class NoFalsePrecisionTest {
    private val k = object : KnowledgeSource {
        override fun search(query: String, limit: Int) = listOf(Snippet(1, "Wikipedie: Kosmas Pražský", "Kosmas Pražský (asi 1045 – 21. října 1125 Praha) byl kronikář.", "wiki"))
    }
    @Test fun uncertainBirthYearKeepsHedge() {
        val a = WikiReflex.answer("kdy se narodil Kosmas Pražský", k)
        kotlin.test.assertTrue(a == null || a.text.contains("asi 1045") || a.text.contains("kolem 1045"), a?.text)
        val d = WikiReflex.answer("kdy zemřel Kosmas Pražský", k)
        kotlin.test.assertTrue(d != null && d.text.contains("21. října 1125"), d?.text)
    }
}
