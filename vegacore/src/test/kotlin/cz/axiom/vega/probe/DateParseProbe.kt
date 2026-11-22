package cz.axiom.vega.probe

import cz.axiom.vega.core.CzNum
import org.junit.Assume.assumeTrue
import org.junit.Test

class DateParseProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        for (s in listOf("01.03.2026", "1.3.2026", "1. 3. 2026", "01.03.26", "2026-03-01", "2026-03-01T14:22:00", "2026-03-01 14:22", "01/03/2026", "03/01/2026",
            "13/01/2026", "2026/03/01", "1.3.", "31.02.2026", "29.02.2027", "29.02.2028", "01.13.2026", "0.3.2026", "1 March 2026", "1. března 2026", "Mar 1, 2026",
            "01-03-2026", "20260301", "1.3.1899", "1.3.2150", "12.11.99", "  01.03.2026  ", "01.03.2026 12:00:00", "\"01.03.2026\""))
            println("PROBE|$s|${runCatching { CzNum.date(s) }.getOrElse { "PÁD $it" }}")
    }
}
