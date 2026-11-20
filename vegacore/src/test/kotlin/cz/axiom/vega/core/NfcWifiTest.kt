package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NfcWifiTest {
    // --- NFC inspektor (jen čtení a pojmenování) ---
    @Test fun nfcClassifiesCommonTags() {
        val ntag = NfcTag.classify(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66), listOf("NfcA", "MifareUltralight", "Ndef"), sak = 0x00, atqa = 0x0044)
        assertEquals("MIFARE Ultralight / NTAG", ntag.family); assertEquals("04 11 22 33 44 55 66", ntag.uid); assertEquals(7, ntag.uidLen)

        val classic = NfcTag.classify(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), listOf("NfcA", "MifareClassic"), sak = 0x08, atqa = 0x0004)
        assertEquals("MIFARE Classic", classic.family)
        assertTrue(classic.note.contains("nekopíruje"), "u chráněné karty musí být jasné, že se obsah nekopíruje")

        val card = NfcTag.classify(byteArrayOf(0x08, 0x01, 0x02, 0x03), listOf("NfcA", "IsoDep"), sak = 0x20)
        assertEquals("ISO/IEC 14443-4 (Type A)", card.standard); assertTrue(card.note.contains("nezasahuje"))

        val v = NfcTag.classify(ByteArray(8), listOf("NfcV"))
        assertEquals("NFC-V (Vicinity)", v.family)
        assertEquals("NfcA", NfcTag.shortTech("android.nfc.tech.NfcA"))
    }

    // --- WiFi analyzátor ---
    @Test fun wifiChannelsBandsSecurity() {
        assertEquals(1, WifiScan.channel(2412)); assertEquals(6, WifiScan.channel(2437)); assertEquals(11, WifiScan.channel(2462))
        assertEquals(36, WifiScan.channel(5180)); assertEquals("2,4 GHz", WifiScan.band(2412)); assertEquals("5 GHz", WifiScan.band(5180))
        assertEquals("WPA2" to null, WifiScan.security("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA3", WifiScan.security("[RSN-SAE-CCMP][ESS]").first)
        assertTrue(WifiScan.security("[WEP][ESS]").second!!.contains("WEP"))
        assertTrue(WifiScan.security("[ESS]").second!!.contains("bez hesla"))
        assertTrue(WifiScan.security("[WPA2-PSK-CCMP][WPS][ESS]").second!!.contains("WPS"))
    }

    @Test fun wifiReportWarnsAboutOwnWeakNetwork() {
        val nets = listOf(
            WifiScan.Net("Doma", "AA:BB:CC:DD:EE:FF", -45, 2437, "[WEP][ESS]"),
            WifiScan.Net("Soused", "11:22:33:44:55:66", -70, 2437, "[WPA2-PSK-CCMP][ESS]"),
            WifiScan.Net("Soused2", "11:22:33:44:55:67", -72, 2462, "[WPA2-PSK-CCMP][ESS]"),
        )
        val r = WifiScan.analyze(nets, mySsid = "Doma")
        assertEquals(3, r.nets.size)
        assertTrue(r.nets.first().mine)
        assertTrue(r.advice.any { it.contains("Tvoje síť") && it.contains("WEP") }, r.advice.toString())
        assertEquals(2, r.channels24[6])
        // skrytá i cizí slabá se shrne, ale neřeší se cizí
        assertTrue(r.advice.any { it.contains("jen pasivně") })
    }

    @Test fun wifiMissingOwnNetwork() {
        val r = WifiScan.analyze(listOf(WifiScan.Net("Jina", "00:00:00:00:00:01", -60, 5180, "[WPA2-PSK-CCMP][ESS]")), mySsid = "Doma")
        assertTrue(r.advice.any { it.contains("nenašel") })
        assertNull(r.nets.first().weak)
    }
}

class CnbRatesTest {
    private val sample = """
        20.11.2026 #224
        země|měna|množství|kód|kurz
        EMU|euro|1|EUR|25,340
        USA|dolar|1|USD|23,456
        Japonsko|jen|100|JPY|15,120
        Polsko|zlotý|1|PLN|5,842
    """.trimIndent()

    @org.junit.Test fun parsesAndConverts() {
        val t = cz.axiom.vega.calc.CnbRates.parse(sample)
        kotlin.test.assertEquals("20.11.2026", t.date)
        kotlin.test.assertEquals(2534.0, cz.axiom.vega.calc.CnbRates.toCzk(t, 100.0, "EUR"))
        kotlin.test.assertEquals(15.12, cz.axiom.vega.calc.CnbRates.toCzk(t, 100.0, "JPY")) // množství 100
        kotlin.test.assertEquals(100.0, cz.axiom.vega.calc.CnbRates.toCzk(t, 100.0, "CZK"))
        kotlin.test.assertNull(cz.axiom.vega.calc.CnbRates.toCzk(t, 1.0, "XXX"))
        // EUR → USD přes CZK
        val eurUsd = cz.axiom.vega.calc.CnbRates.convert(t, 100.0, "EUR", "USD")!!
        kotlin.test.assertTrue(kotlin.math.abs(eurUsd - 2534.0 / 23.456) < 0.01)
        kotlin.test.assertEquals("EUR", cz.axiom.vega.calc.CnbRates.code("euro"))
        kotlin.test.assertEquals("USD", cz.axiom.vega.calc.CnbRates.code("dolaru"))
        kotlin.test.assertEquals("GBP", cz.axiom.vega.calc.CnbRates.code("gbp"))
        kotlin.test.assertNull(cz.axiom.vega.calc.CnbRates.code("banán"))
    }
}
