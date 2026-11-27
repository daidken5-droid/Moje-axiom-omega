package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardware kolo 27. 11.: cizí NFC štítek nesmí podvrhnout adresu skrytými znaky ani zahltit parser vnořováním. */
class NdefHardeningTest {
    private fun uri(u: String) = NdefParser.parse(NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload(u)))))[0]

    @Test fun bidiOverrideInUrlIsNotOpenableAndIsShown() {
        // na displeji by se „https://evil.cz/‮zc.knab.www“ ukázalo jako „…www.bank.cz“
        val r = uri("https://evil.cz/‮zc.knab.www")
        assertFalse("adresa se skrytým znakem se nesmí nabídnout k otevření", r.asValue() is Value.Url)
        assertTrue(r.display, r.display.contains("U+202E"))
        assertFalse("skrytý znak nesmí zůstat v zobrazení", r.display.contains('‮'))
        assertTrue(r.display, r.display.contains("skryté znaky"))
    }

    @Test fun otherInvisiblesToo() {
        for (c in listOf('​', '‎', '⁦', '﻿', '\u0007', '­')) {
            val r = uri("https://bank.cz$c.evil.cz/")
            assertFalse("U+%04X".format(c.code), r.asValue() is Value.Url)
        }
    }

    @Test fun plainUrlStillOpens() {
        val r = uri("https://www.example.cz/a?b=1")
        assertEquals(Value.Url("https://www.example.cz/a?b=1"), r.asValue())
        assertEquals("https://www.example.cz/a?b=1", r.display)
    }

    @Test fun smartPosterWithHiddenUrlIsFlagged() {
        val inner = NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload("https://evil.cz/‮zc.knab")), Triple(1, "T", NdefParser.textPayload("Banka"))))
        val sp = NdefParser.parse(NdefParser.build(listOf(Triple(1, "Sp", inner))))[0]
        assertFalse(sp.display.contains('‮')); assertTrue(sp.display, sp.display.contains("U+202E"))
    }

    @Test fun nestedSmartPostersAreNotRecursedDeeply() {
        var b = NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload("https://a.cz"))))
        repeat(20_000) { b = NdefParser.build(listOf(Triple(1, "Sp", b))) }
        // dřív rekurze až do přetečení zásobníku (zachycené jen náhodou); teď Smart Poster uvnitř Smart Posteru se nerozbaluje
        val t = System.nanoTime()
        val r = NdefParser.parse(b)
        assertEquals(1, r.size)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 2000)
        val leaf = NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload("https://a.cz"))))
        val mid = NdefParser.build(listOf(Triple(1, "Sp", leaf)))
        val one = NdefParser.parse(NdefParser.build(listOf(Triple(1, "Sp", mid))))
        assertTrue(one[0].display, one[0].display.contains("vnořený"))
    }
}
