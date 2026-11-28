package cz.axiom.vega.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kolo 27. 11. (varování překladače): spojení se otevírá ze stejného rozboru adresy, jaký prošel kontrolou. */
class HttpSameParserTest {
    @Test fun connectionTargetsTheCheckedHost() {
        val prev = Http.airgap; Http.airgap = false
        try {
            for (u in listOf("https://example.cz/a?b=1", "https://user@example.cz:8443/x#y", "https://example.cz/..;@127.0.0.1/")) {
                val c = Http.open(u)  // openConnection nic neodesílá
                assertEquals(u, java.net.URI(u).host, c.url.host)
                c.disconnect()
            }
            // „uživatel@“ před lokální adresou: kontrola i spojení vidí 127.0.0.1 → zamítnuto
            val e = runCatching { Http.open("https://example.cz:443@127.0.0.1/", publicOnly = true) }.exceptionOrNull()
            assertTrue(e.toString(), e is NetException)
        } finally { Http.airgap = prev }
    }
}
