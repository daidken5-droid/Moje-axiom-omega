package cz.axiom.vega.core

import cz.axiom.vega.astraea.VegaAdapters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Doporučení pro vývoj 25. 11., sprint 1: nezávislost zdrojů. Přejímací případy z tabulky – verdikt Evidence.assess
 * (co čte uživatel) i webový adaptér; výsledek nesmí záviset na pořadí vstupů.
 */
class SourceIndependenceTest {
    private fun assess(vararg d: Pair<String, String>) = Evidence.assess(d.map { Evidence.Doc(it.first, it.second) })
    private fun datePart(a: Evidence.Assessment) = a.parts.first { it.label.startsWith("datum") }
    private fun motivePart(a: Evidence.Assessment) = a.parts.first { it.label.startsWith("motiv") }

    private val ctk1 = "a" to "Most přes Labe v Ústí uzavřeli 3. 11. 2026, informovala agentura ČTK. Silničáři objíždějí centrum."
    private val ctk2 = "b" to "Podle ČTK je od 3. 11. 2026 zavřený most v Ústí nad Labem; řidiči musí jet po objízdné trase."

    @Test fun twoRewritesOfOneCtkReportAreOneLineInAnyOrder() {
        for (docs in listOf(listOf(ctk1, ctk2), listOf(ctk2, ctk1))) {
            val a = assess(*docs.toTypedArray())
            val d = datePart(a)
            assertEquals(1, d.lines, "pořadí ${docs.map { it.first }}: ${d.status}")
            assertTrue(d.status.startsWith("jen jedna linie"), d.status)
            assertEquals(setOf("a", "b"), d.support.toSet())
            assertTrue(a.headline.contains("dokládají, že se tvrzení šířilo"), a.headline)
        }
    }

    @Test fun mereMentionOfAgencyIsNotTakeover() {
        val p = Evidence.parse(Evidence.Doc("c", "Na tiskové konferenci, kde byli novináři z ČTK i ČT, oznámil starosta 3. 11. 2026 uzavření mostu."))
        assertFalse(p.origins.any { it.startsWith("agentura:") }, p.origins.toString())
        // a s výslovným převzetím ČTK nevytvoří linii
        val a = assess(ctk1, "c" to "Na tiskové konferenci, kde byli novináři z ČTK, oznámil starosta Petr Novák 3. 11. 2026 uzavření mostu podle úředního záznamu.")
        assertTrue(datePart(a).lines >= 2, datePart(a).status)
    }

    @Test fun dateFromAgencyAndCauseFromOwnInterviewAreSeparate() {
        val doc = "d" to "Most uzavřeli 3. 11. 2026, informovala ČTK. „Uzavřeli jsme ho kvůli prasklé nosné konstrukci,“ řekl našemu webu statik Jan Dvořák."
        val a = assess(ctk1, doc)
        assertEquals(1, datePart(a).lines, datePart(a).status)  // datum: obě přebírají ČTK
        val m = motivePart(a)
        assertTrue(m.support.any { it.contains("Jan Dvořák") }, m.support.toString())  // výrok připsán mluvčímu
        assertFalse(m.status.startsWith("jen jedna linie"), m.status)  // výrok z vlastního rozhovoru není převzatý od ČTK
    }

    @Test fun twoSitesWithoutStatedOriginAreUndetermined() {
        val a = assess("w1" to "Most v Ústí je od 3. 11. 2026 zavřený.", "w2" to "Od 3. 11. 2026 je ústecký most uzavřen pro dopravu.")
        val d = datePart(a)
        assertTrue(d.status.contains("nezávislost neurčena"), d.status)
        assertFalse(a.inferred.any { it.contains("dvě nezávislé") }, a.inferred.toString())
    }

    @Test fun twoPrimaryDocumentsStayTwoLines() {
        val a = assess("protokol.txt" to "Úřední záznam: most uzavřen 3. 11. 2026 v 6:00, rozhodnutí odboru dopravy.",
            "fotka.txt" to "Fotografie s datem pořízení 3. 11. 2026 zachycuje zábrany na mostě.")
        val d = datePart(a)
        assertEquals(2, d.lines, d.status); assertEquals(setOf("protokol.txt", "fotka.txt"), d.support.toSet())
        assertTrue(d.status.startsWith("potvrzeno") || d.status.startsWith("dvě nezávislé"), d.status)
    }

    @Test fun webAdapterSameAsLocalAndOrderIndependent() {
        val o1 = VegaAdapters.originOf("Most zavřen", "Odkaz https://example.cz/most a text: most uzavřeli, informovala ČTK.", "https://web1.cz/a")
        val o2 = VegaAdapters.originOf("Most zavřen", "Most uzavřeli, informovala ČTK. Víc na https://jiny.cz/x", "https://web2.cz/b")
        assertEquals("agentura:čtk", o1); assertEquals(o1, o2)
        // bez uvedeného původu = vlastní adresa stránky
        assertEquals("url:web3.cz/c", VegaAdapters.originOf("T", "Most je zavřený.", "https://web3.cz/c"))
    }
}
