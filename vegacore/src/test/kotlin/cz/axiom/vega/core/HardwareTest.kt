package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareTest {
    // --- virtuální karta: APDU protokol ---
    @Test fun apduFullExchange() {
        val card = CardApdu.Card("Vrtačka Bosch", "INV-00123", linkedMapOf("umístění" to "dílna", "stav" to "půjčeno"))
        val payload = CardApdu.encode(card)

        // SELECT s aktivní kartou → verze + 9000
        val sel = CardApdu.process(CardApdu.selectApdu(), payload)
        assertEquals(CardApdu.SW_OK_INT, CardApdu.splitSw(sel).second)
        assertEquals(CardApdu.VERSION, sel[0].toInt())

        // READ → data + 9000, dekódováním vznikne stejná karta
        val read = CardApdu.process(CardApdu.readApdu(), payload)
        val (data, sw) = CardApdu.splitSw(read)
        assertEquals(CardApdu.SW_OK_INT, sw)
        val back = CardApdu.decode(data)!!
        assertEquals("Vrtačka Bosch", back.name); assertEquals("INV-00123", back.publicId); assertEquals("dílna", back.fields["umístění"])
    }

    @Test fun apduNoActiveCardAndBadInput() {
        // deaktivovaná karta (payload null): SELECT i READ vrátí 6A82
        assertEquals(CardApdu.SW_NO_CARD_INT, CardApdu.splitSw(CardApdu.process(CardApdu.selectApdu(), null)).second)
        assertEquals(CardApdu.SW_NO_CARD_INT, CardApdu.splitSw(CardApdu.process(CardApdu.readApdu(), null)).second)
        // neznámý příkaz → 6D00
        assertContentEquals(CardApdu.SW_UNKNOWN, CardApdu.process(byteArrayOf(0x00, 0x11, 0x22, 0x33), byteArrayOf(1)))
        // vadná/krátká zpráva → 6700
        assertContentEquals(CardApdu.SW_WRONG_LEN, CardApdu.process(byteArrayOf(0x00, 0x01), byteArrayOf(1)))
        assertContentEquals(CardApdu.SW_WRONG_LEN, CardApdu.process(null, byteArrayOf(1)))
        // poškozený payload se nedekóduje
        assertNull(CardApdu.decode(byteArrayOf(0x99.toByte(), 1, 2, 3)))
        assertNull(CardApdu.decode(ByteArray(0)))
    }

    @Test fun apduAidIsProprietaryAndSelectMatches() {
        assertEquals(0xF0, CardApdu.AID[0].toInt() and 0xFF) // proprietární RID (mimo registrované)
        // SELECT jiného AID se nás netýká → 6A82 i s aktivní kartou
        val otherSel = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x05, 1, 2, 3, 4, 5, 0x00)
        assertEquals(CardApdu.SW_NO_CARD_INT, CardApdu.splitSw(CardApdu.process(otherSel, byteArrayOf(1))).second)
    }

    // --- WiFi QR + heslo ---
    @Test fun wifiQrEscapesSpecials() {
        assertEquals("WIFI:T:WPA;S:Moje\\;síť;P:a\\:b\\\\c\\,d\\\"e;;", WifiTools.qrPayload("Moje;síť", "a:b\\c,d\"e"))
        assertEquals("WIFI:T:nopass;S:Free;;", WifiTools.qrPayload("Free", ""))
        assertTrue(WifiTools.qrPayload("S", "p", hidden = true).contains("H:true;"))
    }

    @Test fun passwordCheckFlagsWeak() {
        assertEquals(0, WifiTools.checkPassword("1234567").score) // krátké
        assertEquals(0, WifiTools.checkPassword("password").score)
        assertEquals(0, WifiTools.checkPassword("abcdefgh").score) // sekvence
        assertTrue(WifiTools.checkPassword("MojeSitHeslo", "MojeSit").reason.contains("název sítě"))
        assertTrue(WifiTools.checkPassword("Jablko-Reka-Sova-Most!42").score >= 3)
        // generátor dává silné heslo
        val gen = WifiTools.passphrase(java.util.Random(42))
        assertTrue(WifiTools.checkPassword(gen).score >= 3, gen)
        assertTrue(gen.length >= 12)
    }
}
