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
}
