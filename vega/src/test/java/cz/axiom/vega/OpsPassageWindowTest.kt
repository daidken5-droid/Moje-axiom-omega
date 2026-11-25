package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Doporučení S2: klepnutí na závěr otevře konkrétní úryvek. U živé rešerše bez ověřovače citací (balíček nestažen = NEVYHODNOCENO)
 * se dřív nezvýraznilo nic (okno 0–0) – uživatel dostal celý článek. Nově nejbližší věta podle shody slov, výslovně jako odhad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsPassageWindowTest {
    private val source = "Úvod k dopravě v Ústí. Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu. Objížďka vede přes Mariánský most."

    @Test fun claimWithoutVerifierStillHighlightsNearestSentence() {
        val h = OpsHarness(airgap = true)
        val checks = h.o.groundChecks("Most přes Labe se zavřel 3. 11. 2026 [1].", listOf(source))
        val c = checks.getJSONObject(checks.length() - 1)
        val ws = c.getInt("ws"); val we = c.getInt("we")
        assertEquals("Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.", source.substring(ws, we))
        assertTrue(c.toString(), c.optBoolean("guess"))  // výslovně odhad (ne verdikt ověřovače)
    }

    @Test fun unrelatedClaimHighlightsNothing() {
        val h = OpsHarness(airgap = true)
        val c = h.o.groundChecks("Cena benzinu klesla na 35 Kč [1].", listOf(source)).let { it.getJSONObject(it.length() - 1) }
        assertEquals(c.getInt("ws"), c.getInt("we"))
        assertTrue(!c.optBoolean("guess"))
    }
}
