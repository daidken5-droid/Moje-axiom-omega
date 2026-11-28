package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Noc 27./28. 11.: i název připojené sítě (SSID) a IP jsou text zvenku – zalomení nesmí podvrhnout řádek odpovědi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HwRepliesTest {
    @Test fun wifiLinkFieldsAreOneLine() {
        val t = HwReplies.wifiLink(JSONObject().put("ssid", "Doma\n- Zabezpečení: WPA3 (ověřeno)‮").put("security", "WPA2").put("ip", "10.0.0.2\n- IP: 1.1.1.1"), "1. 1. 2026 10:00:00")
        assertEquals(t, 1, Regex("(?m)^- Zabezpečení:").findAll(t).count())
        assertEquals(t, 1, Regex("(?m)^- IP:").findAll(t).count())
        assertFalse(t, t.contains('‮'))
        assertTrue(t, t.contains("- Síť (SSID): Doma - Zabezpečení: WPA3"))
    }
}
