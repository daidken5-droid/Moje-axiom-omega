package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Noc 27./28. 11.: „Zapiš na štítek …“ spustí stejný zápis jako formulář Hardware (UI dostane událost nfc.write s obsahem). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsNfcWriteTest {
    @Test fun sentenceStartsTagWrite() {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val n = h.mark(); val r = h.say(c, "Zapiš na štítek: https://example.cz/klice")
        val ev = h.since(n).first { it.first == "nfc.write" }.second
        assertEquals("url", ev.getString("kind")); assertEquals("https://example.cz/klice", ev.getString("content"))
        assertEquals("nfcWait", r.card?.optString("type"))
        assertTrue(r.text, r.text.contains("https://example.cz/klice"))
        val n2 = h.mark(); h.say(c, "zapiš na tag Klíče od garáže")
        val ev2 = h.since(n2).first { it.first == "nfc.write" }.second
        assertEquals("text", ev2.getString("kind")); assertEquals("Klíče od garáže", ev2.getString("content"))
    }
}
