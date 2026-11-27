package cz.axiom.vega

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Strážný snímek (refaktoring 8, noc 27./28. 11.) PŘED vyjmutím hardwarových odpovědí z Ops.kt: přesný text všech pěti
 * odpovědí (WiFi připojení, WiFi okolí, BLE prázdné/plné, virtuální karta) i jejich karta a značka cizích dat.
 * Snímek se pořídí na původním kódu (VEGA_WRITE_SNAPSHOT=1) a refaktoring ho nesmí změnit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsHwSnapshotTest {
    private fun dump(): String {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val out = StringBuilder()
        fun take(label: String, act: () -> Unit) {
            val n = h.mark(); act()
            val m = h.await(n, "message", 10_000).last { it.first == "message" }.second.getJSONObject("msg")
            val card = m.optJSONObject("card")?.let { k -> JSONObject(k.toString()).apply { optJSONObject("data")?.remove("at") } }
            out.append("=== $label\n").append(m.getString("text").replace(Regex("změřeno \\d{1,2}\\. \\d{1,2}\\. \\d{4} \\d\\d:\\d\\d:\\d\\d"), "změřeno <čas>"))
                .append("\n--- untrusted=").append(m.optString("untrusted")).append(" card=").append(card?.toString() ?: "-").append("\n")
        }
        take("wifiLink") { h.o.onWifiLink(JSONObject().put("ssid", "Doma").put("security", "WPA2").put("rssi", -58).put("quality", "dobrý").put("freq", 5180).put("band", "5 GHz").put("link", 433).put("ip", "192.168.1.20").put("stale", true).toString(), null, c) }
        take("wifiLinkErr") { h.o.onWifiLink("{}", "WiFi je vypnutá.", c) }
        take("bleEmpty") { h.o.onBle("[]", null, c) }
        take("ble") { h.o.onBle(JSONArray().put(JSONObject().put("name", "Sluchátka").put("addr", "AA:BB:CC:DD:EE:01").put("rssi", -50))
            .put(JSONObject().put("name", "(bez názvu)").put("addr", "AA:BB:CC:DD:EE:02").put("rssi", -70)).put(JSONObject().put("name", "Hodinky").put("addr", "AA:BB:CC:DD:EE:03").put("rssi", -90)).toString(), null, c) }
        take("wifiEmpty") { h.o.onWifi("[]", null, null, null, c) }
        take("wifi") { h.o.onWifi("""[{"ssid":"Doma","bssid":"aa:bb:cc:00:00:01","rssi":-50,"freq":2412,"caps":"[WPA2-PSK-CCMP]"},{"ssid":"Soused","bssid":"aa:bb:cc:00:00:02","rssi":-70,"freq":2437,"caps":"[WEP]"},{"ssid":"Kavarna","bssid":"aa:bb:cc:00:00:03","rssi":-80,"freq":5180,"caps":"[ESS]"}]""", "\"Doma\"", "aa:bb:cc:00:00:01", null, c) }
        take("card") { h.o.onCardRead(cz.axiom.vega.core.CardApdu.encode(cz.axiom.vega.core.CardApdu.Card("Vrtačka", "INV-1", mapOf("stav" to "půjčeno"))), null, c) }
        take("cardErr") { h.o.onCardRead(null, "Spojení se přerušilo.", c) }
        return out.toString()
    }

    @Test fun hardwareRepliesUnchanged() {
        val f = File("src/test/resources/ops-hw-snapshot.txt")
        val now = dump()
        if (System.getenv("VEGA_WRITE_SNAPSHOT") == "1") { f.writeText(now); return }
        assertEquals(f.readText(), now)
    }
}
