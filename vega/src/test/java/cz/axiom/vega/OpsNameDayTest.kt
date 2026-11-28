package cz.axiom.vega

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sobota 28. 11.: „Kdy má svátek Petr?“ odpoví VEGA z kalendáře jmen i bez sítě – ne „potřebuji model“ ani webová rešerše. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsNameDayTest {
    @Test fun nameDayOffline() {
        val h = OpsHarness(airgap = true)
        val r = h.say(h.o.newChat(), "Kdy má svátek Petr?")
        assertTrue(r.text, r.text.contains("22. 2.") && r.text.contains("29. 6."))
        val r2 = h.say(h.o.newChat(), "kdo má zítra svátek?")
        assertTrue(r2.text, r2.text.contains("svátek") && !r2.text.contains("potřebuji model"))
    }
}
