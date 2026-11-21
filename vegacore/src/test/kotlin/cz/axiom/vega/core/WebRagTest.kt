package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRagTest {
    // --- neurální pavučina ---
    @Test fun webLinksAndSurfacesRelated() {
        val kv = MemKv()
        val w = NeuralWeb(kv)
        // dva tahy o hypotékách s pamětí a nástrojem, jeden nesouvisející
        w.observe(listOf(NeuralWeb.topic("hypoteka"), NeuralWeb.topic("urok"), NeuralWeb.mem("m1"), NeuralWeb.tool("quick")))
        w.observe(listOf(NeuralWeb.topic("hypoteka"), NeuralWeb.topic("splatka"), NeuralWeb.mem("m1"), NeuralWeb.tool("quick")))
        w.observe(listOf(NeuralWeb.topic("recept"), NeuralWeb.mem("m2")))
        // dotaz na hypotéku vytáhne paměť m1 a nástroj quick, ne m2
        val mems = w.relatedMemories(listOf(NeuralWeb.topic("hypoteka")))
        assertTrue("m1" in mems, mems.toString()); assertTrue("m2" !in mems)
        assertTrue("quick" in w.relatedTools(listOf(NeuralWeb.topic("hypoteka"))))
        // persistence: nová instance nad stejným kv vidí graf
        val w2 = NeuralWeb(kv)
        assertTrue("m1" in w2.relatedMemories(listOf(NeuralWeb.topic("urok"))))
    }

    @Test fun webIgnoresSingleNodeAndDecays() {
        val kv = MemKv()
        var now = 1_000_000L
        val w = NeuralWeb(kv) { now }
        w.observe(listOf(NeuralWeb.topic("a"))) // jeden uzel = nic
        assertTrue(w.related(listOf(NeuralWeb.topic("a"))).isEmpty())
        w.observe(listOf(NeuralWeb.topic("a"), NeuralWeb.topic("b")))
        assertTrue(w.related(listOf(NeuralWeb.topic("a"))).isNotEmpty())
        now += 400L * 24 * 3600_000 // ~400 dní → silný útlum, hrana zmizí
        assertTrue(w.related(listOf(NeuralWeb.topic("a"))).isEmpty())
    }

    // --- RAG (BM25) ---
    @Test fun ragRanksRelevantPassageWithSource() {
        val docs = mapOf(
            "hypoteky.txt" to "Hypotéka je úvěr na bydlení zajištěný nemovitostí. Úrok z prodlení se počítá jinak. ".repeat(10),
            "vareni.txt" to "Svíčková na smetaně potřebuje hovězí zadní a kořenovou zeleninu. ".repeat(10),
        )
        val idx = Rag.build(docs, size = 120, overlap = 30)
        assertTrue(idx.passages.size > 2)
        val hits = idx.query("jak se počítá hypotéka na bydlení", 3)
        assertTrue(hits.isNotEmpty()); assertEquals("hypoteky.txt", hits.first().passage.source)
        assertTrue(hits.first().score > 0)
        // diakritika nerozhoduje
        assertEquals("vareni.txt", idx.query("recept na svickovou", 1).first().passage.source)
        // kontext pro model má zdroj a čísla
        val block = Rag.contextBlock(hits)
        assertTrue(block.contains("[1]") && block.contains("hypoteky.txt"))
    }

    @Test fun ragChunkCoversAndQueryEmptyIsSafe() {
        val p = Rag.chunk("d", "x".repeat(2000), 500, 100)
        assertTrue(p.size >= 3); assertEquals(0, p.first().start)
        assertTrue(Rag.build(emptyMap()).query("cokoliv").isEmpty())
        assertTrue(Rag.build(mapOf("a" to "text")).query("").isEmpty())
    }
}
