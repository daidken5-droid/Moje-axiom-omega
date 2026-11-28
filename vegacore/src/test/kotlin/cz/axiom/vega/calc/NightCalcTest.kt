package cz.axiom.vega.calc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN1: laické tvary, které dřív šly na model (sleva „15 % sleva z“, „hrubého“, rozpočítat na lidi, h+min, °F, „5 % úrok z“, „za kolik dní je“), a diakritika v převodu dní na týdny. */
class NightCalcTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 24, 21, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun a(q: String) = (Quick.answer(q, now) ?: "null").replace(' ', ' ').replace(' ', ' ')
    @Test fun layman() {
        assertTrue(a("kolik je 15 % sleva z 899"), a("kolik je 15 % sleva z 899").contains("po slevě zaplatíš 764,15 Kč"))
        assertEquals("12 % z 35 000 Kč je 4 200 Kč.", a("kolik je 12 % z 35 000 Kč hrubého"))
        a("rozpočítej 1840 na 4 lidi").let { assertTrue(it, it.contains("460 Kč na osobu")) }
        a("rozpočti 1500 korun mezi 3 kamarády").let { assertTrue(it, it.contains("500 Kč na osobu")) }
        a("kolik je 3 hodiny 45 minut v minutách").let { assertTrue(it, it.contains("225 minut")) }
        a("2 h 30 min v hodinách").let { assertTrue(it, it.contains("2,5 h")) }
        a("kolik je 100 °F ve stupních").let { assertTrue(it, it.contains("37,78 °C")) }
        a("kolik je 5 % úrok z 200 000 za rok").let { assertTrue(it, it.contains("10 000 Kč")) }
        a("za kolik dní je 1. ledna").let { assertTrue(it, it.contains("38 dní")) }
        a("kolik týdnů je 100 dní").let { assertTrue(it, it.contains("100 dní = 14,29 týdnů")) }
        // stávající tvary beze změny
        assertTrue(a("899 po slevě 20 %").contains("719,2 Kč")); assertTrue(a("převeď 5 mil na km").contains("8,0467 km"))
    }
}
