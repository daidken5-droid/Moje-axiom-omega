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

/** Den 25. 11., kolo 4: prázdný / rozbitý soubor paměti po výpadku proudu – jedna záloha, ne nová při každém spuštění aplikace. */
class MemoryBackupOnceTest {
    @Test fun oneBackupOnly() {
        for (bad in listOf("", "{nesmysl", "\u0000\u0000\u0000")) {
            val kv = MemKv(); var t = 1L
            kv.write("memory/entries", bad)
            repeat(5) { Memory(kv) { t++ }.all() }   // pět spuštění aplikace
            assertEquals("„$bad“", 1, kv.keys("memory/").count { it.startsWith("memory/entries.bad-") })
            // a dá se dál normálně ukládat
            val m = Memory(kv) { t++ }; m.remember("Kávu piju bez cukru"); assertEquals(1, Memory(kv) { t++ }.all().size)
        }
    }
}

/** Den 25. 11., kolo 10: zálohy poškozených souborů (.bad) se po 30 dnech uklidí; mladší a živá data zůstanou. */
class HousekeepingBadTest {
    @Test fun oldBackupsCleaned() {
        var t = 1L; val kv = MemKv { t }
        kv.write("chats.json", "[{\"id\":\"c1\"}]"); kv.write("chat/c1.json", "[]")
        kv.write("chat/c1.json.bad", "[{\"x\""); kv.write("memory/entries.bad-1", "{rozbit"); kv.write("chats.json.bad", "\u0000")
        t = 40L * 86_400_000L; kv.write("memory/entries.bad-2", "nový")   // čerstvá záloha
        val r = Housekeeping.run(kv, now = t)
        assertTrue(r.toString(), kv.read("chat/c1.json.bad") == null && kv.read("memory/entries.bad-1") == null && kv.read("chats.json.bad") == null)
        assertTrue("mladší záloha zůstane", kv.read("memory/entries.bad-2") != null)
        assertTrue("živý chat zůstane", kv.read("chat/c1.json") != null && kv.read("chats.json") != null)
    }
}
