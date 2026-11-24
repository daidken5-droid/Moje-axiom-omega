package cz.axiom.vega.calc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN3: kalendářní otázky, které jdou spočítat přesně bez modelu (přestupný rok, dny v měsíci, Velikonoce, věk jen z roku narození). */
class CalendarCalcTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 24, 22, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun a(q: String) = Quick.answer(q, now) ?: "null"
    @Test fun calendar() {
        assertEquals("Rok 2027 není přestupný – únor má 28 dní.", a("je 2027 přestupný rok"))
        assertTrue(a("je rok 2028 přestupný?").startsWith("Rok 2028 je přestupný"))
        assertEquals("Únor 2028 má 29 dní.", a("kolik je dní v únoru 2028"))
        assertEquals("Listopad 2026 má 30 dní.", a("kolik dní má listopad"))
        a("kdy je Velikonoční pondělí 2027").let { assertTrue(it, it.contains("29. 3. 2027")) }
        a("kdy jsou velikonoce").let { assertTrue(it, it.contains("neděle 28. 3. 2027") && it.contains("pondělí 29. 3. 2027")) }
        a("kdy je Velký pátek 2027").let { assertTrue(it, it.contains("26. 3. 2027")) }
        a("kolik mi je, když jsem se narodil 1990").let { assertTrue(it, it.contains("36") && it.contains("35")) }
        // mimo kalendář dál model
        listOf("kolik obyvatel má Praha", "kdy byla bitva na Bílé hoře").forEach { assertEquals(it, "null", a(it)) }
    }
}
