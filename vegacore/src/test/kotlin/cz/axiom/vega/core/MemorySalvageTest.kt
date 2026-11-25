package cz.axiom.vega.core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/** Den 25. 11., kolo 3: useknutý soubor paměti (vybitá baterie při zápisu) – celé záznamy před poškozením se zachrání, ne prázdná paměť. */
class MemorySalvageTest {
    @Test fun truncatedMemoryIsSalvaged() {
        val kv = MemKv(); var t = 1L
        val m1 = Memory(kv) { t++ }
        listOf("Bydlím v Brně", "Kávu piju bez cukru", "Výplata chodí 20. dne", "Syn se jmenuje Petr \"Péťa\" Novák, má rád {lego} a [vlaky]").forEach { m1.remember(it) }
        val raw = kv.read("memory/entries")!!
        kv.write("memory/entries", raw.substring(0, raw.length - 30))  // useknutý konec posledního záznamu
        val m = Memory(kv) { t++ }
        val texts = m.all().map { it.text }
        assertEquals("zachráněno: $texts", 3, texts.size)
        assertTrue(texts.contains("Bydlím v Brně") && texts.contains("Kávu piju bez cukru"))
        assertTrue("záloha", kv.keys("memory/").any { it.startsWith("memory/entries.bad-") })
        // zcela rozbitý soubor dál nespadne
        kv.write("memory/entries", "{nesmysl"); assertEquals(0, Memory(kv) { t++ }.all().size)
    }
}

class JsonSalvageTest {
    @Test fun edges() {
        assertEquals(listOf("{\"a\":\"x]}{\\\"\"}", "[1,[2,3]]", "5"), JsonSalvage.arrayElements("[{\"a\":\"x]}{\\\"\"}, [1,[2,3]], 5, {\"b\":"))
        assertEquals(emptyList<String>(), JsonSalvage.arrayElements("[]"))
        assertEquals(emptyList<String>(), JsonSalvage.arrayElements("{nesmysl"))
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), JsonSalvage.arrayElements("[{\"a\":1},{\"b\":2}]"))
    }
}
