package cz.axiom.vega.probe

import cz.axiom.vega.calc.CnbRates
import org.junit.Assume.assumeTrue
import org.junit.Test

class CurProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        for (q in listOf("100 eur na koruny", "kolik je 50 dolarů v korunách", "100 € kolik Kč", "1000 Kč na eura", "1 000 korun v eurech", "kolik je 20 liber", "200 zlotých na koruny", "50 usd", "100 eur", "kurz eura", "kolik stojí euro", "převeď 300 Kč na dolary", "5000 jenů v korunách", "100 franků na Kč", "100 eur na dolary", "2,5 eura v kč", "kolik je 1 euro"))
            println("PROBE|$q|${runCatching { CnbRates.query(q) }.getOrElse { "PÁD $it" }}")
    }
}
