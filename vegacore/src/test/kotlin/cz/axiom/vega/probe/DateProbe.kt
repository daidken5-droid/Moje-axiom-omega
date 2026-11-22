package cz.axiom.vega.probe

import cz.axiom.vega.calc.Quick
import org.junit.Assume.assumeTrue
import org.junit.Test

class DateProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        fun at(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
        val cases = listOf(
            at(2026, 12, 25) to "kolik dní do vánoc", at(2026, 12, 24) to "kolik dní do vánoc", at(2026, 12, 31) to "kolik dní do silvestra",
            at(2026, 12, 31) to "kolik dní do nového roku", at(2027, 1, 1) to "kolik dní do nového roku", at(2028, 2, 28) to "za 1 den",
            at(2028, 2, 28) to "kolik dní mezi 28.2.2028 a 1.3.2028", at(2027, 2, 28) to "kolik let je od 29.2.2000", at(2027, 1, 10) to "kolik dní do velikonoc",
            at(2027, 3, 28) to "kolik dní do velikonoc", at(2027, 3, 29) to "kolik dní do velikonoc", at(2026, 11, 22) to "jaký den v týdnu bude 1.1.2027",
            at(2026, 12, 31) to "za 2 měsíce", at(2027, 6, 21) to "kolik týdnů do léta", at(2026, 11, 22) to "kolik dní do 24.12.",
            at(2026, 11, 22) to "kolik dní do 31.2.2027", at(2026, 11, 22) to "kolik dní mezi 15.4.2026 a 1.3.2026", at(2026, 11, 22) to "kolik dní do prázdnin",
            at(2026, 11, 22) to "kolik let je od 22.11.2000", at(2026, 11, 22) to "kolik mi je když jsem se narodil 23.11.2000", at(2026, 11, 22) to "před 3 dny",
        )
        for ((now, q) in cases) println("PROBE|${now.toLocalDate()}|$q|${runCatching { Quick.answer(q, now) }.getOrElse { "PÁD $it" }}")
    }
}
