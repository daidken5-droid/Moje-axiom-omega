package cz.axiom.omega.core

import cz.axiom.omega.core.step0.Calc
import cz.axiom.omega.core.step0.Forensic
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.core.step0.Step0
import cz.axiom.omega.core.step0.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step0Test {

    @Test fun precedence() = assertEquals(10.0, Calc.eval("2+2*3+2").getOrThrow(), 1e-9)
    @Test fun powerRightAssoc() = assertEquals(512.0, Calc.eval("2^3^2").getOrThrow(), 1e-9)
    @Test fun unaryMinusVsPower() = assertEquals(-4.0, Calc.eval("-2^2").getOrThrow(), 1e-9)
    @Test fun negativeExponent() = assertEquals(0.25, Calc.eval("2^-2").getOrThrow(), 1e-9)
    @Test fun decimalComma() = assertEquals(3.5, Calc.eval("1,5 + 2").getOrThrow(), 1e-9)
    @Test fun symbols() = assertEquals(12.0, Calc.eval("3 × (8 ÷ 2)").getOrThrow(), 1e-9)
    @Test fun functions() = assertEquals(5.0, Calc.eval("sqrt(9)+abs(-2)").getOrThrow(), 1e-9)

    @Test fun missingParenIsError() = assertTrue(Calc.eval("(2+3").isFailure)
    @Test fun trailingGarbageIsError() = assertTrue(Calc.eval("2+2)").isFailure)
    @Test fun divisionByZero() = assertTrue(Calc.eval("5/0").isFailure)

    @Test fun mathInText() {
        val f = Step0.math("Kolik je 12*7 + 3 a pak (2+3)^2?")
        assertTrue(f.toString(), f.any { it.text == "12*7 + 3 = 87" })
        assertTrue(f.toString(), f.any { it.text.endsWith("= 25") })
    }

    @Test fun datesAndPhonesAreNotMath() {
        assertTrue(Step0.math("Narozen 2024-05-12, volej 777-123-456, rok 1/2026, období 2020-2024").isEmpty())
    }

    @Test fun percentOf() {
        val f = Step0.percent("Kolik je 15 % z 2 000?")
        assertEquals("15 % z 2000 = 300", f.single().text)
    }

    @Test fun evPositive() {
        val f = Step0.ev("Sázka: kurz 2,10, pravděpodobnost 55 %, bankroll 10000")!!
        assertTrue(f.text, f.text.contains("SILNÉ +EV"))
        assertTrue(f.text, f.text.contains("Half-Kelly"))
        assertTrue(f.text, f.text.contains("vklad"))
    }

    @Test fun evNegative() {
        val f = Step0.ev("kurz 1.50 a šance 60%")!!
        assertTrue(f.text, f.text.contains("-EV"))
    }

    @Test fun unitsBasic() {
        assertEquals(1.5, Units.convert(150.0, "cm", "m").getOrThrow(), 1e-9)
        assertEquals(212.0, Units.convert(100.0, "C", "F").getOrThrow(), 1e-9)
        assertEquals(1.0, Units.convert(1000.0, "W", "kW").getOrThrow(), 1e-9)
        assertTrue(Units.convert(1.0, "kg", "m").isFailure)
    }

    @Test fun unitScan() {
        val s = Units.scan("Převeď 100 Nm na lbft a 2 bar na psi")
        assertEquals(2, s.size)
        assertTrue(s[0], s[0].startsWith("100 NM = 73,75"))
    }

    @Test fun currencyWithRates() {
        val r = Rates(mapOf("EUR" to 25.0, "JPY" to 0.15), "1.1.2026", "test")
        assertEquals(250.0, Units.convert(10.0, "eur", "czk", r).getOrThrow(), 1e-9)
        assertEquals(1.0, Units.convert(25.0, "kč", "eur", r).getOrThrow(), 1e-9)
    }

    @Test fun cnbParser() {
        val txt = """
            31.10.2026 #211
            země|měna|množství|kód|kurz
            EMU|euro|1|EUR|24,305
            Japonsko|jen|100|JPY|14,215
            USA|dolar|1|USD|21,880
        """.trimIndent()
        val r = Rates.parseCnb(txt)
        assertEquals("31.10.2026", r.date)
        assertEquals(0.14215, r.of("JPY")!!, 1e-9)
        assertEquals(1.0, r.of("CZK")!!, 0.0)
    }

    @Test fun importIncludesShippingInVatBase() {
        val f = Step0.importJp("Karty za 10000 jenů", Rates(mapOf("JPY" to 0.15), "x", "test"))!!
        // zboží 1500 + doprava 450 = 1950; DPH 409,5; celkem 2359,5
        assertTrue(f.text, f.text.contains("DPH 21 % ze 1950 Kč = 409,5 Kč"))
        assertTrue(f.text, f.text.contains("celkem ≈ 2359,5 Kč"))
    }

    @Test fun build() {
        val f = Step0.build("Kolik materiálu na 20 m2 mikrocementu?")!!
        assertTrue(f.text, f.text.contains("22 m²"))
    }

    @Test fun forensic() {
        val t = Forensic.scan("Platba z CZ6508000000192000145399 dne 12.03.2026 v 14:05, IP 10.0.0.300 a 192.168.1.20, hash ${"a".repeat(64)}")
        val kinds = t.map { it.kind }
        assertTrue(kinds.toString(), "IBAN" in kinds)
        assertTrue("DATUM" in kinds && "ČAS" in kinds && "SHA256" in kinds)
        assertEquals(listOf("192.168.1.20"), t.filter { it.kind == "IPv4" }.map { it.value })
    }

    @Test fun ibanChecksum() {
        assertTrue(Forensic.ibanValid("CZ65 0800 0000 1920 0014 5399"))
        assertFalse(Forensic.ibanValid("CZ65 0800 0000 1920 0014 5398"))
    }

    @Test fun nothingForPlainText() {
        assertNull(Step0.ev("Ahoj, jak se máš?"))
        assertTrue(Step0.analyze("Ahoj, jak se máš?").isEmpty())
    }
}
