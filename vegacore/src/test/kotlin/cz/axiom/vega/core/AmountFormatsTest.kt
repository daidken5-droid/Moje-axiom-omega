package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N5: 19 tvarů částek z výpisů (tisíce tečkou, Kč/CZK/€, mínus vzadu, závorky, NBSP, CR/DR). */
class AmountFormatsTest { @Test fun nineteenShapes() {
    listOf("-1 234,56" to -1234.56, "1.234,56" to 1234.56, "-1.234,56" to -1234.56, "1 234,56 Kč" to 1234.56, "-1 234,56 CZK" to -1234.56, "1234,56-" to -1234.56,
        "(123,45)" to -123.45, "−1 234,56" to -1234.56, "1 234,56" to 1234.56, "1 234,56" to 1234.56, "+500,00" to 500.0, "1 234" to 1234.0, "12,5 €" to 12.5,
        "-0,50" to -0.5, "1'234.56" to 1234.56, "CZK -1 234,56" to -1234.56, "Kč 99,90" to 99.9, "1 234,56 CR" to 1234.56, "1 234,56 DR" to -1234.56)
        .forEach { (s, e) -> org.junit.Assert.assertEquals(s, e, CzNum.parse(s, true)!!, 1e-9) }
    org.junit.Assert.assertNull(CzNum.parse("DR", true)); org.junit.Assert.assertNull(CzNum.parse("ADR 12", true))
} }
