package cz.axiom.vega.core
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** „Kde bydlím?“ / „Jak se jmenuju?“ z paměti bez modelu; „se jmenuju Petr“ se uloží jako „Jmenuju se Petr“. */
class MemRecallTest {
    private fun mem(): Memory { var t = 1L; return Memory(MemKv()) { t++ } }

    @Test fun normalizesNameOrder() {
        val m = mem()
        val o = m.remember("se jmenuju Petr") as Mem.Outcome.Saved
        assertEquals("Jmenuju se Petr", o.entry.text)
        // a je to údaj „jméno“ – nové jméno = oprava, ne druhý záznam
        assertTrue(m.remember("jmenuju se Pavel") is Mem.Outcome.Corrected)
    }

    @Test fun answersOwnFacts() {
        val m = mem()
        listOf("Bydlím v Brně", "se jmenuju Petr", "Je mi 42 let", "Pracuju jako učitel", "Manželka se jmenuje Jana").forEach { m.remember(it) }
        assertTrue(m.recall("kde bydlím?")!!.contains("Bydlím v Brně"))
        assertTrue(m.recall("Víš, jak se jmenuju?")!!.contains("Jmenuju se Petr"))
        assertTrue(m.recall("kolik mi je")!!.contains("42"))
        assertTrue(m.recall("kde pracuju?")!!.contains("učitel"))
        assertTrue(m.recall("jak se jmenuje moje manželka?")!!.contains("Jana"))
    }

    @Test fun unknownAndUnrelated() {
        val m = mem()
        assertTrue(m.recall("kde bydlím?")!!.startsWith("To zatím nevím"))
        assertNull(m.recall("kde bydlí prezident?"))
        assertNull(m.recall("jak se jmenuje hlavní město Francie?"))
        assertNull(m.recall("kde bydlím nejlevněji v Praze?"))
    }
}
