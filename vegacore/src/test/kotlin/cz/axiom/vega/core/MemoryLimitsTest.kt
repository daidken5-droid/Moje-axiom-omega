package cz.axiom.vega.core
import org.junit.Assert.assertTrue
import org.junit.Test
/** Den 25. 11., kolo 1: limity paměti – délka záznamu, historie oprav, rychlost při stovkách záznamů. */
class MemoryLimitsTest {
    @Test fun limits() {
        val kv = MemKv(); var t = 1L; val m = Memory(kv) { t++ }
        (m.remember("Moje oblíbená věc je kafe. ".repeat(40)) as Mem.Outcome).let { assertTrue("$it", it is Mem.Outcome.Rejected && (it as Mem.Outcome.Rejected).reason.contains("moc dlouhé")) }
        assertTrue(m.remember("Kávu piju bez cukru") is Mem.Outcome.Saved)
        repeat(60) { i -> m.remember("Výplata mi chodí ${i % 28 + 1}. dne v měsíci") }
        val e = m.all().first { it.text.startsWith("Výplata") }
        assertTrue("historie ${e.history.size}", e.history.size <= Memory.MAX_HISTORY)
        assertTrue(m.revert(e.id) != null)  // vrácení opravy dál funguje
        val t0 = System.nanoTime(); repeat(800) { i -> m.remember("Fakt číslo $i: mám rád věc$i a jinou$i") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("800 uložení: $ms ms", ms < 8_000)
    }
}

/** Den 25. 11., kolo 6: další citlivé údaje, které si paměť nesmí uložit; běžná fakta ano. */
class MemorySecretsTest {
    @Test fun secrets() {
        listOf("moje rodné číslo je 9001011234", "RČ 9001011234", "číslo OP 123456789", "číslo pasu AB1234567", "kód k trezoru je 9988", "kombinace k zámku na kolo 4512",
            "bezpečnostní otázka: jméno psa Rex", "OVĚŘOVACÍ KÓD 482913", "HESLO K E-MAILU: Pes", "RODNÉ ČÍSLO 9001011234").forEach { assertTrue(it, Mem.looksSecret(it)) }
        listOf("jmenuju se Jan", "bydlím v Brně", "výplata chodí 20.", "telefon na mámu 777 123 456", "číslo OP vyřizuju na úřadě", "trezor máme ve sklepě", "syn se narodil 1. 5. 2012")
            .forEach { org.junit.Assert.assertFalse(it, Mem.looksSecret(it)) }
    }
}
