package cz.axiom.vega

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** „Kde bydlím?“ v režimu Bez sítě: odpověď z paměti, ne „na tohle potřebuji model v cloudu“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsRecallTest {
    @Test fun ownFactFromMemoryOffline() {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        h.say(c, "pamatuj si, že bydlím v Brně")
        val a = h.say(c, "kde bydlím?").text
        assertTrue(a, a.contains("Bydlím v Brně"))
    }
}
