package cz.axiom.vega.calc

import java.time.LocalDate

/** Český kalendář bez sítě: Velikonoce (gregoriánský výpočet) a svátky – dny pracovního klidu podle zákona 245/2000 Sb. */
object CzCalendar {
    /** Velikonoční neděle (anonymní gregoriánský algoritmus). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, (h + l - 7 * m + 114) % 31 + 1)
    }

    /** Všech 13 dnů volna v roce y, seřazené podle data. */
    fun holidays(y: Int): List<Pair<LocalDate, String>> { val e = easter(y)
        return listOf(LocalDate.of(y, 1, 1) to "Den obnovy samostatného českého státu, Nový rok", e.minusDays(2) to "Velký pátek", e.plusDays(1) to "Velikonoční pondělí",
            LocalDate.of(y, 5, 1) to "Svátek práce", LocalDate.of(y, 5, 8) to "Den vítězství", LocalDate.of(y, 7, 5) to "Den slovanských věrozvěstů Cyrila a Metoděje",
            LocalDate.of(y, 7, 6) to "Den upálení mistra Jana Husa", LocalDate.of(y, 9, 28) to "Den české státnosti", LocalDate.of(y, 10, 28) to "Den vzniku samostatného československého státu",
            LocalDate.of(y, 11, 17) to "Den boje za svobodu a demokracii a Mezinárodní den studentstva", LocalDate.of(y, 12, 24) to "Štědrý den",
            LocalDate.of(y, 12, 25) to "1. svátek vánoční", LocalDate.of(y, 12, 26) to "2. svátek vánoční").sortedBy { it.first } }

    fun holidayOn(d: LocalDate): String? = holidays(d.year).firstOrNull { it.first == d }?.second
}
