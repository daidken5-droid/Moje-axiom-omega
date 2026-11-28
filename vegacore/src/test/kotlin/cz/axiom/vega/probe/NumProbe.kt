package cz.axiom.vega.probe

import cz.axiom.vega.calc.Quick
import cz.axiom.vega.core.CzNum
import cz.axiom.vega.core.Econ
import org.junit.Assume.assumeTrue
import org.junit.Test

class NumProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        for (s in listOf("1.234", "1,234", "1 234", "1.234,5", "1,234.5", "1.234.567", "12,5", "-0,00", "−0", "1 234 567,891", "0,005", "2,675", "1e5", "12.5", "12.50", "'1 234,50", "1 234,50 Kč", "+250", "250-", "(1 000)", "1.000,-", "100,-", "5,- Kč", ",5", "5,"))
            println("PROBE|parse|$s|cz=${CzNum.parse(s)} en=${CzNum.parse(s, false)}")
        println("PROBE|usesComma|[1.234;2.500;3.100]|${CzNum.usesDecimalComma(listOf("1.234", "2.500", "3.100"))}")
        println("PROBE|usesComma|[12.50;3.99]|${CzNum.usesDecimalComma(listOf("12.50", "3.99"))}")
        println("PROBE|usesComma|[1 234,50;-99,00]|${CzNum.usesDecimalComma(listOf("1 234,50", "-99,00"))}")
        println("PROBE|usesComma|[100;200]|${CzNum.usesDecimalComma(listOf("100", "200"))}")
        for (v in listOf(-0.0, 0.004, 0.005, 2.675, 1e12, -1234.5, 999999.995)) println("PROBE|format|$v|CzNum=${CzNum.format(v)} kc=${Econ.kc(v)} quick=${Quick.fmtPublic(v)}")
        for (q in listOf("kolik je 0,1 + 0,2", "kolik je 1/3", "10/3", "2,675 * 1", "kolik je 1 000 000 * 1 000 000", "999999999999 * 999999999999", "0.5 + 0,5", "1.5 + 1", "5 % z 0", "0 % z 100", "-5 % z 100", "150 % z 80"))
            println("PROBE|quick|$q|${Quick.answer(q)}")
    }
}
