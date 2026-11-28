package cz.axiom.vega.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware 27. 11.: odpověď karty max. 480 B v jednom rámci, ale řada čteček (krátké APDU) přijme nejvýš 256 B dat.
 * Čtení po částech (offset v P1 P2, délka v Le) – a obě kombinace se starou verzí VEGA na druhém telefonu dál fungují.
 */
class CardChunkTest {
    private val big = CardApdu.encode(CardApdu.Card("Vrtačka Bosch GSB 18V", "INV-00123", (1..12).associate { "pole$it" to "hodnota číslo $it, dost dlouhá ať je karta velká" }.let { m ->
        // naplnit těsně pod limit 480 B
        val out = LinkedHashMap<String, String>(); for ((k, v) in m) { out[k] = v; if (CardApdu.Card("Vrtačka Bosch GSB 18V", "INV-00123", out).let { runCatching { CardApdu.encode(it) }.isFailure }) { out.remove(k); break } }; out }))

    @Test fun chunkedReadStaysUnderShortApduLimitAndReassembles() {
        assertTrue("karta má být větší než 256 B: ${big.size}", big.size > 256)
        val sizes = ArrayList<Int>()
        val got = CardApdu.readAll { apdu -> CardApdu.process(apdu, big).also { sizes += it.size } }
        assertArrayEquals(big, got)
        assertTrue("odpovědi: $sizes", sizes.all { it <= 256 + 2 })
    }

    @Test fun legacyReaderStillGetsWholeCard() {
        val (data, sw) = CardApdu.splitSw(CardApdu.process(CardApdu.readApdu(), big))
        assertEquals(CardApdu.SW_OK_INT, sw); assertArrayEquals(big, data)
    }

    @Test fun newReaderWithLegacyCard() {
        // stará karta: P1 P2 i Le ignoruje, vrací vždy celý obsah
        val legacy = { a: ByteArray -> if (a[1].toInt() and 0xFF == 0xA4) byteArrayOf(1, 0x90.toByte(), 0) else big + CardApdu.SW_OK }
        assertArrayEquals(big, CardApdu.readAll(legacy))
    }

    @Test fun noCardAndOffsetPastEnd() {
        assertEquals(null, CardApdu.readAll { CardApdu.process(it, null) })
        val past = CardApdu.process(CardApdu.readApdu(1000, 100), big)
        assertEquals(CardApdu.SW_OK_INT, CardApdu.splitSw(past).second); assertEquals(0, CardApdu.splitSw(past).first.size)
        // vadná čtečka by mohla posílat stále stejný offset – readAll má strop kroků
        val small = CardApdu.encode(CardApdu.Card("A", "B", emptyMap()))
        assertArrayEquals(small, CardApdu.readAll { CardApdu.process(it, small) })
    }
}
