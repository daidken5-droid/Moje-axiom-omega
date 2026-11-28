package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Doporučení S2: výchozí přehled = odpověď + nejdůležitější rozpor; podrobný audit (přímo / odvozeno / otevřené, kritéria) až na vyžádání. */
class EvidenceOverviewTest {
    private fun assess(vararg d: Pair<String, String>) = Evidence.assess(d.map { Evidence.Doc(it.first, it.second) })

    @Test fun conflictingReasonsAreTheMainContradiction() {
        val a = assess("protokol.txt" to "Úřední záznam: most uzavřen 3. 11. 2026, rozhodnutí odboru dopravy.",
            "statik.txt" to "Most zavřeli 3. 11. 2026 kvůli prasklé nosné konstrukci, uvedl statik.",
            "radnice.txt" to "Most zavřeli 3. 11. 2026 kvůli plánované opravě kanalizace pod vozovkou.")
        val c = assertNotNull(a.contradiction)
        assertTrue(c.contains("prasklé nosné konstrukci") && c.contains("opravě kanalizace"), c)
        val t = Evidence.render(a)
        assertTrue(t.startsWith(a.headline), t)
        assertTrue(t.contains("**Hlavní rozpor:** $c"), t)
        assertFalse(t.contains("**Přímo doloženo:**") || t.contains("**Odvozuji:**") || t.contains("_Kritéria"), t)  // audit až na vyžádání
        assertTrue(t.contains("Podrobný audit"), t)
    }

    @Test fun oneSourceLineIsTheContradictionWhenNothingElse() {
        val a = assess("a" to "Most uzavřeli 3. 11. 2026, informovala agentura ČTK.", "b" to "Podle ČTK je od 3. 11. 2026 zavřený most.")
        assertTrue(a.contradiction!!.contains("jednu výpověď"), a.contradiction)
    }

    @Test fun auditKeepsTheDetail() {
        val a = assess("a" to "Most uzavřeli 3. 11. 2026, informovala agentura ČTK.", "b" to "Podle ČTK je od 3. 11. 2026 zavřený most.")
        val audit = Evidence.renderAudit(a)
        assertTrue(audit.contains("Přímo doloženo") && audit.contains("Kritéria"), audit)
        assertEquals(a.open.isNotEmpty(), audit.contains("Otevřené"))
    }
}
