package cz.axiom.vega.core

import cz.axiom.vega.net.Http
import cz.axiom.vega.net.NetException
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan S14 (22. 11.): „Přečíst stránku“ nesmí sáhnout do telefonu ani lokální sítě (adresa z webu, NFC štítku, od modelu). */
class SsrfTest {
    private fun blocked(url: String): Boolean = runCatching {
        Http.requireAllowed(url, publicOnly = true)
        val host = java.net.URI(url).host ?: return true
        Http.requirePublicResolved(host)
    }.exceptionOrNull() is NetException

    @Test fun localAndTrickyAddressesAreBlocked() {
        val bad = listOf("file:///data/data/cz.axiom.vega/shared_prefs/x.xml", "http://localhost:8080/", "https://localhost/", "https://127.0.0.1/", "https://127.1/",
            "https://2130706433/", "https://0x7f000001/", "https://0177.0.0.1/", "https://[::1]/", "https://[::ffff:127.0.0.1]/", "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.5/", "https://192.168.1.1/", "https://172.16.0.1/", "https://100.64.0.1/", "https://[fd00::1]/", "https://router.local/", "https://nas.lan/",
            "javascript:alert(1)", "content://cz.axiom.vega.files/x", "ftp://example.com/", "http://example.com/")
        val leaked = bad.filterNot { blocked(it) }
        assertTrue("prošlo: $leaked", leaked.isEmpty())
    }
    @Test fun normalHttpsPasses() {
        assertTrue(!blocked("https://8.8.8.8/") && runCatching { Http.requireAllowed("https://www.cnb.cz/x", publicOnly = true) }.isSuccess)
    }
}
