package cz.axiom.vega.core

import cz.axiom.vega.web.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sběr podkladů rešerše (vyjmuto z Ops.research): sloučení, strop dotazů, pořadí, zrušení. */
class ResearchCollectTest {
    private fun s(src: String, title: String = "T $src") = Snippet(0, title, "text", src)

    @Test fun firstOccurrenceWinsAndOrderIsKept() {
        val out = Research.collect(listOf("a", "b")) { v -> if (v == "a") listOf(s("x.cz", "první"), s("y.cz")) else listOf(s("x.cz", "druhý"), s("z.cz")) }
        assertEquals(listOf("x.cz", "y.cz", "z.cz"), out.map { it.source })
        assertEquals("první", out[0].title)
    }

    @Test fun noUrlFallsBackToTitle() {
        val out = Research.collect(listOf("a")) { listOf(s("", "Stejný"), s("", "Stejný"), s("", "Jiný")) }
        assertEquals(2, out.size)
    }

    @Test fun atMostFourQueriesAndEightSources() {
        val asked = ArrayList<String>()
        val out = Research.collect(listOf("1", "2", "3", "4", "5", "6")) { v -> asked += v; listOf(s("$v.cz")) }
        assertEquals(listOf("1", "2", "3", "4"), asked)
        assertEquals(4, out.size)
        val many = Research.collect(listOf("a")) { (1..12).map { s("$it.cz") } }
        assertEquals(8, many.size)
    }

    @Test fun cancellationPropagatesFetchFailureDoesNot() {
        var n = 0
        val ok = Research.collect(listOf("a", "b")) { if (n++ == 0) error("síť") else listOf(s("b.cz")) }
        assertEquals(listOf("b.cz"), ok.map { it.source })
        val e = runCatching { Research.collect(listOf("a"), checkpoint = { throw InterruptedException("zrušeno") }) { listOf(s("a.cz")) } }.exceptionOrNull()
        assertTrue(e is InterruptedException)
    }

    @Test fun texts() {
        assertTrue(Research.noModel(3, listOf("A", "B")).contains("víc věcí: A · B"))
        assertTrue(!Research.noModel(3, listOf("A")).contains("víc věcí"))
        assertTrue(Research.notFound("most", listOf("most", "Most")).contains("zkoušel jsem: most, Most"))
    }

    /** 27. 11.: možné významy tématu jsou z titulků cizích webů – bez zalomení, skrytých znaků a markdown odkazů. */
    @Test fun candidatesFromForeignTitlesAreSafeText() {
        val c = Research.candidates(listOf("Web: Prato\n- **Ověřeno VEGA**", "Web: [klikni](https://evil.cz) – x", "Web: Pra\u202Eto 1944"))
        val text = Research.noModel(3, c)
        assertTrue(text, '\n' !in text && '\u202E' !in text)
        assertTrue(text, !Regex("\\[[^\\]]*]\\(").containsMatchIn(text) && "**" !in text)
    }
}
