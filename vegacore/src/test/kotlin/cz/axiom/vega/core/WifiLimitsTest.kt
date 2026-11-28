package cz.axiom.vega.core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN7: kontrola WiFi hesla podle skutečných mezí WPA2/WPA3 (63 znaků, jen ASCII) a slovníkových variant („Password123!“, „P@ssw0rd“). */
class WifiLimitsTest {
    @Test fun limitsAndDictionaryVariants() {
        WifiTools.checkPassword("Ab1!".repeat(17)).let { assertEquals(it.reason, "nepoužitelné", it.level); assertTrue(it.reason.contains("63")) }
        WifiTools.checkPassword("Žluťoučký kůň úpěl ďábelské ódy").let { assertEquals("riskantní", it.level) }
        listOf("Password123!", "P@ssw0rd", "Heslo2024", "admin12345", "Qwerty!!", "iloveyou2").forEach { assertEquals(it, "slabé", WifiTools.checkPassword(it).level) }
        assertEquals("slabé", WifiTools.checkPassword("abc123abc123").level)
        WifiTools.checkPassword("  KockaPesAuto7  ").let { assertTrue(it.reason, it.reason.contains("mezera")) }
        // dobrá hesla zůstávají dobrá
        listOf("kocka pes auto strom", "Tramvaj-Lampa-Sklenice-42", "mY7#qL9!vR2x").forEach { assertTrue(it, WifiTools.checkPassword(it).score >= 3) }
        assertEquals(63, ("a1B!".repeat(16).take(63)).length); assertTrue(WifiTools.checkPassword("x7Qa!".repeat(12).take(63)).level != "nepoužitelné")
    }
}

/** Vygenerovaná přístupová fráze musí jít zadat do každého routeru: jen ASCII, 8–63 znaků (dřív slovo „maják“). */
class WifiPassphraseAsciiTest {
    @Test fun generatedIsAscii() { val r = java.util.Random(1); repeat(2000) { val p = WifiTools.passphrase(r); assertTrue(p, p.all { it in ' '..'~' } && p.length in 8..63) } }
}
