package cz.axiom.vega

import cz.axiom.vega.web.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Strážné testy rešerše (doporučení T1, krok 2) PŘED vyjmutím sběru podkladů z Ops.kt:
 * Bez sítě, vypnutý web, prázdný výsledek, odpověď bez modelu, sloučení duplicit mezi variantami a strop dotazů.
 */
@RunWith(RobolectricTestRunner::class)
class OpsResearchGuardTest {
    private fun snip(src: String, title: String = "T $src") = Snippet(0, title, "Text ze zdroje $src o Karlově mostě.", src)

    @Test fun airgapAsksNothingAndOffersLocalSearch() {
        val h = OpsHarness(airgap = true)
        val calls = CopyOnWriteArrayList<String>()
        h.o.webGather = { q, _ -> calls += q; emptyList() }
        val r = h.say("c", "Karlův most")
        assertEquals("netOff", r.card?.optString("type")); assertEquals("airgap", r.card?.optString("what"))
        assertTrue(r.text, r.text.contains("najdi v mých souborech"))
        assertTrue("v režimu Bez sítě se na web nesmí ani zkusit", calls.isEmpty())
    }

    @Test fun webOffSaysNothingWasSent() {
        val h = OpsHarness(airgap = false)
        val calls = CopyOnWriteArrayList<String>()
        h.o.webGather = { q, _ -> calls += q; emptyList() }
        val r = h.say("c", "Karlův most")
        assertEquals("web", r.card?.optString("what"))
        assertTrue(r.text, r.text.contains("nic neodešlo"))
        assertTrue(calls.isEmpty())
    }

    @Test fun emptyResultListsTriedVariants() {
        val h = OpsHarness(airgap = false); h.o.settings.webAllowed = true
        val calls = CopyOnWriteArrayList<String>()
        h.o.webGather = { q, _ -> calls += q; emptyList() }
        val r = h.say("c", "Karlův most")
        assertTrue(r.text, r.text.contains("nenašel nic veřejného"))
        assertTrue("zkusí víc variant, nejvýš 4: $calls", calls.size in 1..4)
        calls.forEach { assertTrue(r.text, r.text.contains(it)) }
    }

    @Test fun noModelFallbackDedupesAcrossVariantsAndCapsQueries() {
        val h = OpsHarness(airgap = false); h.o.settings.webAllowed = true
        val calls = CopyOnWriteArrayList<String>()
        // každá varianta vrací 4 zdroje, dva z nich pokaždé stejné → po druhém dotazu je 6 různých a hledání skončí
        h.o.webGather = { q, _ -> calls += q; listOf(snip("a.cz"), snip("b.cz"), snip("${calls.size}x.cz"), snip("${calls.size}y.cz")) }
        val r = h.say("c", "Karlův most")
        assertTrue(r.toString(), r.text.startsWith("Bez modelu: našel jsem 6 zdrojů"))
        assertEquals("sources", r.card?.optString("type"))
        val items = r.card!!.getJSONArray("items")
        assertEquals(6, items.length())
        assertEquals((0 until 6).map { it + 1 }, (0 until 6).map { items.getJSONObject(it).getInt("n") })
        assertEquals(setOf("a.cz", "b.cz", "1x.cz", "1y.cz", "2x.cz", "2y.cz"), (0 until 6).map { items.getJSONObject(it).getString("url") }.toSet())
        assertEquals("po 6 různých zdrojích se dál nehledá", 2, calls.size)
    }

    @Test fun failingVariantDoesNotStopOthers() {
        val h = OpsHarness(airgap = false); h.o.settings.webAllowed = true
        var n = 0
        h.o.webGather = { _, _ -> if (n++ == 0) throw java.io.IOException("výpadek") else listOf(snip("c.cz")) }
        val r = h.say("c", "Karlův most")
        assertTrue(r.toString(), r.text.startsWith("Bez modelu: našel jsem"))
    }
}
