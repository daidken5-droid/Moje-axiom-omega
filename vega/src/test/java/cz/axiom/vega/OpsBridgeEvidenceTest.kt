package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Doporučení 25. 11., sprint 2 – přejímací úloha „most“: nad třemi místními texty v režimu Bez sítě zjistit, zda je doloženo
 * datum uzavření mostu a proč k němu došlo; z odpovědi otevřít pasáž s datem; příčinu bez doložení nechat otevřenou.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsBridgeEvidenceTest {
    private val docs = listOf(
        "rozhodnuti.txt" to "Rozhodnutí odboru dopravy č. j. 45/2026. Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.",
        "zpravy.txt" to "Most v Ústí uzavřeli 3. 11. 2026, informovala ČTK. Řidiči objíždějí přes Mariánský most.",
        "blog.txt" to "Lidé na sociálních sítích spekulují o tom, co bude s mostem dál. Objížďky jsou dlouhé.",
    )

    @Test fun dateDocumentedWithOpenablePassageCauseOpen() {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val ids = docs.map { (n, t) -> h.o.attach(n, "text/plain", t.toByteArray()).getString("id") }
        val r = h.say(c, "Je doložené, kdy zavřeli most a proč?", ids)
        val parts = r.card!!.getJSONArray("parts")
        val date = (0 until parts.length()).map { parts.getJSONObject(it) }.first { it.getString("label").startsWith("datum") }
        val motive = (0 until parts.length()).map { parts.getJSONObject(it) }.first { it.getString("label").startsWith("motiv") }
        // pasáž s datem jde otevřít: soubor + přesná věta z něj
        val ps = date.getJSONArray("passages")
        val p = (0 until ps.length()).map { ps.getJSONObject(it) }.first { it.getString("doc") == "rozhodnuti.txt" }
        assertEquals("Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.", p.getString("text"))
        assertTrue(p.toString(), docs.first().second.contains(p.getString("text")))  // doslovná citace, ne parafráze
        assertTrue(date.getString("status"), date.getString("status").startsWith("potvrzeno"))
        // příčina: žádná pasáž ji nedokládá → otevřená, bez pasáží
        assertEquals(0, motive.getJSONArray("passages").length())
        assertTrue(r.text, r.text.contains("Motiv / důvod: nedoloženo – zůstává otevřené"))
    }
}
