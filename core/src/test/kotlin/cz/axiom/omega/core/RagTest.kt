package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.rag.Fusion
import cz.axiom.omega.core.rag.RagIndex
import cz.axiom.omega.core.rag.RagManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RagTest {
    private fun res(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())

    /** Skutečný index z CI nástroje (avior_embed + merge.py) a skutečné dotazy modelem Qwen3-Embedding. */
    @Test fun realIndexFindsRightArticle() {
        val idx = RagIndex.open(res("rag-mini.rag"))
        assertEquals(256, idx.dim); assertEquals(5, idx.count)
        // dotazy: 1 = tepelné čerpadlo (rowid 2), 2 = Karel IV. (rowid 4), 3 = fotosyntéza (rowid 3)
        val raw = ByteBuffer.wrap(res("rag-queries.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val expected = mapOf(1 to 2, 2 to 4, 3 to 3)
        while (raw.remaining() >= 8 + 256) {
            val qid = raw.int; val s = raw.float
            val q = FloatArray(256) { raw.get() * s }
            val top = idx.search(q, 3)
            assertEquals("dotaz $qid", expected[qid], top.first().first)
            assertTrue(top.first().second > 0.45f)
        }
    }

    @Test fun buildAndSearchSynthetic() {
        val a = FloatArray(8) { if (it == 0) 1f else 0f }
        val b = FloatArray(8) { if (it == 1) 1f else 0f }
        val c = FloatArray(8) { if (it < 2) 0.7f else 0f }
        val idx = RagIndex.build(8, listOf(10 to a, 20 to b, 30 to c))
        val top = idx.search(floatArrayOf(0.9f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f), 2)
        assertEquals(10, top[0].first); assertEquals(30, top[1].first)
        assertTrue(idx.search(a, 5, minScore = 0.9f).all { it.first == 10 })
    }

    @Test fun manifestAndFusion() {
        val m = RagManifest.parse("""{"lib":"wiki-cs","format":"avior-rag-int8","model":"omega-sense-qwen3-embedding-0.6b","dim":256,"count":534044,
            "size":139000000,"sha256":"ab","file":"wiki-cs.rag.gz","fileSize":120000000,"fileSha256":"cd"}""")
        assertEquals(256, m.dim); assertEquals("wiki-cs.rag.gz", m.asPrebuilt().parts.single().name)
        fun s(t: String) = Snippet(0, t, "", "")
        val fused = Fusion.rrf(listOf(listOf(s("A"), s("B"), s("C")), listOf(s("C"), s("D"))), 3)
        assertEquals(listOf("C", "A", "B"), fused.map { it.title })
    }
}
