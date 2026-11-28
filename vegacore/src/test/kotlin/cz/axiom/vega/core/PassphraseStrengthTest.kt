package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware kolo 27. 11.: vygenerovaná WiFi fráze musí odolat offline útoku na zachycený WPA2 handshake.
 * Dřív 4 slova z 30 (≈ 29 bitů = minuty na grafické kartě), přitom kontrola hesla hlásila „silné“.
 */
class PassphraseStrengthTest {
    private fun gen(n: Int) = java.util.Random(7).let { r -> (1..n).map { WifiTools.passphrase(r) } }

    @Test fun enoughWordsAndVarietyMeasuredFromOutput() {
        val phrases = gen(5000)
        // měřeno z výstupu (ne z konstant): kolik slov ve frázi a kolik různých slov se reálně objeví
        val parts = phrases.map { it.dropLast(3).split('-') }
        assertTrue("slov ve frázi: ${parts.first()}", parts.all { it.size >= 6 })
        val distinct = parts.flatten().map { it.lowercase() }.toSet()
        assertTrue("různých slov jen ${distinct.size}", distinct.size >= 256)
        val bits = parts.first().size * Math.log(distinct.size.toDouble()) / Math.log(2.0)
        assertTrue("náhodnost slov jen %.1f bitů".format(bits), bits >= 48)
        assertTrue(WifiTools.passphraseBits() >= 55)
        assertEquals("žádné dvě fráze stejné", phrases.size, phrases.toSet().size)
    }

    @Test fun wordListIsCleanAndFitsWpaLimit() {
        val w = WifiTools.WORDS
        assertEquals(w.size, w.toSet().size)
        assertTrue(w.all { Regex("[a-z]{3,9}").matches(it) })
        // nejdelší možná fráze se vejde do 63 znaků WPA2
        assertTrue(WifiTools.PASSPHRASE_WORDS * w.maxOf { it.length } + (WifiTools.PASSPHRASE_WORDS - 1) + 3 <= 63)
        gen(2000).forEach { assertTrue(it, WifiTools.checkPassword(it).score == 4) }
    }
}
