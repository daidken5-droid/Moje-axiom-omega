package cz.axiom.vega

import cz.axiom.vega.core.Evidence
import cz.axiom.vega.web.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Refaktoring T1 (25. 11. noc): sestavení karet výsledku mimo Ops – stejný obsah jako dřív, uložené doklady se načtou beze ztráty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EvidenceCardsTest {
    @Test fun storedDocsRoundTrip() {
        val docs = listOf(Evidence.Doc("a.txt", "text a"), Evidence.Doc("b.html", "text b", "https://x.cz/b", "2026-11-02", "2026-11-25"))
        val back = EvidenceCards.docsOf(org.json.JSONArray(EvidenceCards.docsJson(docs).toString()))
        assertEquals(docs, back)
        assertFalse("bez adresy se nic nevymýšlí", EvidenceCards.docJson(docs[0]).has("url"))
    }

    @Test fun sourceItemsKeepAllFields() {
        val it = EvidenceCards.sourceItems(listOf(Snippet(1, "T", "x".repeat(3000), "https://x.cz", "2026-11-02")), today = "2026-11-25").getJSONObject(0)
        assertEquals(1, it.getInt("n")); assertEquals(2500, it.getString("text").length)
        assertEquals("2026-11-02", it.getString("date")); assertEquals("2026-11-25", it.getString("fetched")); assertEquals("https://x.cz", it.getString("url"))
    }

    @Test fun evidenceCardHasPartsPassagesAndAudit() {
        val a = Evidence.assess(listOf(Evidence.Doc("r.txt", "Úřední záznam: most uzavřen 3. 11. 2026, rozhodnutí odboru."), Evidence.Doc("z.txt", "Most zavřeli 3. 11. 2026, informovala ČTK.")))
        val c = EvidenceCards.evidenceCard(a)
        assertEquals("evidence", c.getString("type"))
        val p = c.getJSONArray("parts").getJSONObject(0)
        assertTrue(p.getJSONArray("passages").length() >= 1)
        assertTrue(c.getJSONObject("audit").getString("criteria").startsWith("Kritéria"))
        assertEquals(EvidenceCards.changes(null, a), emptyList<String>())
    }
}
