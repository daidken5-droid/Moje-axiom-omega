package cz.axiom.vega

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sobota 28. 11.: v režimu Bez sítě „Shrň mi tohle: …“ vrátí výtah vět místo „na tohle potřebuji model v cloudu“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsOfflineDigestTest {
    @Test fun airgapSummaryIsExtract() {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val r = h.say(c, "Shrň mi tohle: Vláda dnes schválila rozpočet na příští rok. Schodek rozpočtu má být 230 miliard korun. Opozice kritizuje výdaje na dopravu a schodek rozpočtu. Počasí bylo slunečné. Rozpočet ještě musí schválit sněmovna, hlasování je v prosinci.")
        assertTrue(r.text, r.text.contains("Výtah bez modelu") && r.text.contains("Schodek rozpočtu má být 230 miliard korun."))
        assertTrue(r.text, !r.text.contains("potřebuji model v cloudu"))
    }

    private val art = "Vláda dnes schválila rozpočet na příští rok. Schodek rozpočtu má být 230 miliard korun. Ministr financí řekl, že rozpočet počítá s růstem ekonomiky o dvě procenta. Opozice kritizuje výdaje na dopravu a vysoký schodek rozpočtu. Rozpočet ještě musí schválit sněmovna, hlasování je naplánováno na prosinec."

    /** Jak píše uživatel ChatGPT: pokyn a text na dalším řádku; článek vložený zvlášť a pak „shrň to“; samotný vložený článek. */
    @Test fun chatGptStyleSummaries() {
        val h = OpsHarness(airgap = true)
        val r1 = h.say(h.o.newChat(), "Shrň mi tohle\n$art")
        assertTrue(r1.text, r1.text.contains("Výtah bez modelu"))
        val c = h.o.newChat()
        val r2 = h.say(c, art)
        assertTrue("samotný vložený článek: ${r2.text}", r2.text.contains("výtah", ignoreCase = true) && !r2.text.contains("potřebuji model v cloudu"))
        val r3 = h.say(c, "shrň to")
        assertTrue("„shrň to“ po vloženém článku: ${r3.text}", r3.text.contains("Výtah bez modelu") && r3.text.contains("Schodek rozpočtu má být 230 miliard korun."))
        val r4 = h.say(h.o.newChat(), "zkrať to")
        assertTrue("bez předchozího textu poctivě: ${r4.text}", !r4.text.contains("Výtah bez modelu"))
    }
}
