package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.SelfState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REBIRTH fáze II/III: hloubka výzkumu, dlouhý výstup s pokračováním, matice důkazů. */
class ResearchDepthTest {
    @Test fun autoAndExplicitDepth() {
        assertEquals(ResearchDepth.FAST, ResearchDepth.auto("Co je Operation Prato?"))
        assertEquals(ResearchDepth.DEEP, ResearchDepth.auto("Proč vznikla Georgia Guidestones?"))
        assertEquals(ResearchDepth.FORENSIC, ResearchDepth.auto("Porovnej Operation Prato, Blue Beam a Georgia Guidestones s dnešním společenským a technologickým vývojem a zjisti, zda mají doložitelné společné prvky."))
        assertEquals(ResearchDepth.FORENSIC, ResearchDepth.auto("/forensic co je hypotéka"))
        assertEquals(ResearchDepth.FAST, ResearchDepth.auto("/fast porovnej, zjisti a analyzuj souvislosti"))
    }

    @Test fun overlapDedupe() {
        assertEquals(" a pak dál.", Orchestrator.dedupeOverlap("Text končí touto větou, která se opakuje", "touto větou, která se opakuje a pak dál."))
        assertEquals("nový text", Orchestrator.dedupeOverlap("abc", "nový text"))
    }

    private class Seq(val replies: MutableList<GenResult>) : LlmEngine {
        override val id = "q"; override val label = "seq"; override val kind = EngineKind.FREE; override val contextBudgetChars = 200_000
        val sys = mutableListOf<String>(); var calls = 0
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            calls++; sys += messages.first { it.role == Role.SYSTEM }.content; return replies.removeAt(0)
        }
    }
    private val noKb = object : KnowledgeSource { override fun search(query: String, limit: Int) = emptyList<Snippet>() }
    private val mem = object : MemoryStore { override fun all() = emptyList<MemoryItem>(); override fun remember(item: MemoryItem) = 0L; override fun forget(query: String) = 0 }
    private fun orch() = Orchestrator(noKb, mem, { SelfState("S", "t", false) }, sleeper = {})

    @Test fun longOutputContinuesWithoutDuplicates() {
        val eng = Seq(mutableListOf(
            GenResult("První část dlouhé analýzy, která končí uprostřed", model = "m", stopReason = "length"),
            GenResult("která končí uprostřed věty a pokračuje druhou částí.", model = "m", stopReason = "length"),
            GenResult("Závěr analýzy.", model = "m", stopReason = "stop"),
        ))
        val r = orch().run(AskRequest("Co je X?", maxPasses = 1, reflect = false, depth = ResearchDepth.FAST), eng, CancelToken())
        assertEquals(3, eng.calls)
        assertEquals("První část dlouhé analýzy, která končí uprostřed věty a pokračuje druhou částí. Závěr analýzy.", r.answer)
        assertTrue(!r.truncated)
    }

    @Test fun continuationIsBounded() {
        val eng = Seq(MutableList(5) { GenResult("pořád dál $it", model = "m", stopReason = "length") })
        val r = orch().run(AskRequest("x", maxPasses = 1, reflect = false, depth = ResearchDepth.FAST, autoContinue = 2), eng, CancelToken())
        assertEquals(3, eng.calls); assertTrue(r.truncated && "zkrácena" in r.answer)
    }

    @Test fun forensicGetsInstructionsAndSubQuestionSearches() {
        val asked = mutableListOf<String>()
        val eng = Seq(MutableList(3) { GenResult("[FAKT] Něco [1].", model = "m") })
        val q = "Porovnej Operation Prato a Blue Beam. Zjisti, zda mají společné prvky. Jaké jsou primární zdroje k Operation Prato? Kdo stojí za Blue Beam? Co by tyto hypotézy vyvrátilo?"
        orch().run(AskRequest(q, maxPasses = 2, reflect = false, webGround = { asked += it; listOf(Snippet(-1, "Wikipedie: $it", "text", "https://cs.wikipedia.org/wiki/${it.length}")) }), eng, CancelToken())
        assertTrue(asked.size >= 3, "dotazy na web: $asked")
        assertTrue(eng.sys.first().contains("HLOUBKA: FORENSIC") && eng.sys.first().contains("ŠTÍTKY EVIDENCE"))
    }

    @Test fun evidenceMatrixFromLabels() {
        val answer = "[FAKT] Projekt byl oznámen v roce 2010 [1].\n[ZDROJ] Podle článku šlo o test [2].\n[FAKT] Neexistující citace [7].\n" +
            "[INFERENCE] Oba jevy spojuje stejné období.\n[HYPOTÉZA] Mohlo jít o koordinaci.\n[NEZNÁMÉ] Původce dokumentu."
        val m = EvidenceMatrix.build(answer, listOf("https://www.example.org/a", "https://example.org/b", "https://other.net/c"))
        assertEquals(1, m.count(EvidenceMatrix.Kind.FACT))            // fakt s neplatnou citací [7] degradován
        assertEquals(2, m.count(EvidenceMatrix.Kind.UNKNOWN))
        assertEquals(1, m.invalidRefs)
        assertEquals(2, m.independentSources)                          // example.org ×2 = jeden nezávislý zdroj
    }
}
