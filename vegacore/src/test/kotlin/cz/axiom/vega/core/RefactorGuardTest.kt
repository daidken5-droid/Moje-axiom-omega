package cz.axiom.vega.core

import cz.axiom.vega.base.Mime
import cz.axiom.vega.base.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hard refactoring 25. 11.: české tvary podle počtu a typy souborů z jednoho místa – a co se cestou opravilo. */
class RefactorGuardTest {
    @Test fun pluralWordAllCounts() {
        val got = listOf(0, 1, 2, 4, 5, 11, 21, -1).map { Text.pluralWord(it, "soubor", "soubory", "souborů") }
        assertEquals(listOf("souborů", "soubor", "soubory", "soubory", "souborů", "souborů", "souborů", "souborů"), got)
        assertEquals("3 sítě", Text.plural(3, "síť", "sítě", "sítí"))
    }

    @Test fun mimeOneSource() {
        assertEquals("application/pdf", Mime.of("Predzalobni-vyzva-1.PDF"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Mime.of("vypis.xlsx"))
        assertEquals("text/plain", Mime.of("vystup.txt"))   // dřív „*/*“ → Otevřít často nenašlo aplikaci
        assertEquals("application/zip", Mime.of("projekt.zip")) // dřív jen u Sdílet, ne u Otevřít
        assertEquals("*/*", Mime.of("bez-pripony"))
    }

    @Test fun wifiWeakCountGrammar() {
        val nets = listOf(WifiScan.Net("Kavarna", "aa:01", -60, 2412, "[WEP]"), WifiScan.Net("Soused", "aa:02", -70, 2437, "[WEP]"))
        val adv = WifiScan.analyze(nets, null, null).advice.joinToString("\n")
        assertTrue(adv.contains("V okolí jsou 2 sítě se slabým zabezpečením"), adv)
    }
}
