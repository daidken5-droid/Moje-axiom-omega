package cz.axiom.vega.calc

import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. kolo D14 – zbytek sondy D13: BMI, římská čísla, kostka / náhodné číslo; plus CzCalendar po refaktoringu. */
class QuickMoreTest {
    private fun a(q: String): String { val r = Quick.answer(q); assertNotNull(r, q); return r }
    private fun has(q: String, vararg parts: String) { val r = a(q); parts.forEach { assertTrue(r.contains(it), "„$q“ → $r (chybí „$it“)") } }

    @Test fun bmi() {
        has("BMI 180 cm 80 kg", "24,7", "normální")
        has("spočítej mi bmi, měřím 165 cm a vážím 90 kg", "33,1", "obezita")
        assertNull(Quick.answer("bmi 20 cm 900 kg"))   // nesmysl neodpovídat
    }

    @Test fun roman() {
        has("2026 římskými číslicemi", "MMXXVI")
        has("kolik je římsky 1994", "MCMXCIV")
        has("převeď MCMLXXXIX na arabské číslo", "1989")
        has("co je XLII", "42")
        assertNull(Quick.answer("převeď 4000 na římské"))  // mimo rozsah 1–3999 → nehádat
    }

    @Test fun dice() {
        repeat(30) { val r = QuickMore.answer("hoď kostkou", Random(it))!!; assertTrue(Regex("Padla ([1-6])").containsMatchIn(r), r) }
        repeat(30) { val r = QuickMore.answer("náhodné číslo od 1 do 100", Random(it))!!; val n = Regex("(\\d+)\\.?$").find(r.substringBefore(" _"))!!.groupValues[1].toInt(); assertTrue(n in 1..100, r) }
        has("hoď mincí", "Padl")
    }

    @Test fun calendarModule() {
        assertEquals(LocalDate.of(2027, 3, 28), CzCalendar.easter(2027))
        assertEquals(13, CzCalendar.holidays(2026).size)
        assertEquals("Den boje za svobodu a demokracii a Mezinárodní den studentstva", CzCalendar.holidayOn(LocalDate.of(2026, 11, 17)))
        assertNull(CzCalendar.holidayOn(LocalDate.of(2026, 11, 28)))
    }
}
