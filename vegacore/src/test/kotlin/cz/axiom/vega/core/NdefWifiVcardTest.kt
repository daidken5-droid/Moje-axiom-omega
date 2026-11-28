package cz.axiom.vega.core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN8: WiFi NFC štítek (Wi-Fi Simple Config) a vizitka čitelně; heslo ze štítku se nezobrazí ani neuloží. */
class NdefWifiVcardTest {
    private fun tlv(t: Int, v: ByteArray) = byteArrayOf((t shr 8).toByte(), t.toByte(), (v.size shr 8).toByte(), v.size.toByte()) + v
    @Test fun wifiTag() {
        val cred = tlv(0x1026, byteArrayOf(1)) + tlv(0x1045, "Doma-5G".toByteArray()) + tlv(0x1003, byteArrayOf(0, 0x20)) + tlv(0x100F, byteArrayOf(0, 8)) + tlv(0x1027, "TajneHeslo123".toByteArray())
        val msg = NdefParser.build(listOf(Triple(2, "application/vnd.wfa.wsc", tlv(0x100E, cred))))
        val r = NdefParser.parse(msg).single()
        assertEquals("WiFi", r.typeLabel)
        assertTrue(r.display, r.display.contains("„Doma-5G“") && r.display.contains("WPA2"))
        assertFalse("heslo nesmí být vidět", r.display.contains("TajneHeslo123"))
        assertFalse("heslo nesmí jít do uloženého JSON", r.toJson().toString().contains(java.util.Base64.getEncoder().encodeToString(r.payload)))
        assertFalse((r.asValue() as Value.Text).text.contains("TajneHeslo123"))
        // poškozený WSC → jen velikost, žádný pád
        assertEquals("MIME", NdefParser.parse(NdefParser.build(listOf(Triple(2, "application/vnd.wfa.wsc", byteArrayOf(1, 2, 3))))).single().typeLabel)
    }
    @Test fun vcard() {
        val v = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Novák;Jan;;;\r\nFN:Jan Novák\r\nORG:Firma s.r.o.\r\nTEL;TYPE=CELL:+420 777 123 456\r\nEMAIL:jan@example.cz\r\nEND:VCARD\r\n"
        val r = NdefParser.parse(NdefParser.build(listOf(Triple(2, "text/vcard", v.toByteArray())))).single()
        assertEquals("Kontakt", r.typeLabel)
        assertEquals("Kontakt: Jan Novák · Firma s.r.o. · +420 777 123 456 · jan@example.cz", r.display)
    }
}
