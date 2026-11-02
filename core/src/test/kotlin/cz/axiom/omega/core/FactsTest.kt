package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FactsTest {
    private val today = LocalDate.of(2026, 11, 2)
    private fun a(q: String) = Facts.answer(q, today)?.text
    private fun has(q: String, vararg parts: String) { val t = a(q); assertTrue("$q → $t", t != null && parts.all { it in t }) }

    @Test fun nameDays() {
        has("Kdo má dnes svátek?", "Hubert".let { "Památka zesnulých" })
        has("Kdo má zítra svátek?", "Hubert")
        has("Kdo má svátek 24. 12.?", "Adam, Eva")
        has("Kdy má svátek Jana?", "24. května")
        has("Kdy slaví Petr?", "22. února", "29. června")
    }

    @Test fun holidaysAndEaster() {
        assertEquals(LocalDate.of(2026, 4, 5), Facts.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Facts.easter(2027))
        has("Kdy jsou Velikonoce 2027?", "28. března", "29. března")
        has("Je dnes státní svátek?", "Ne")
        has("Kdy je příští státní svátek?", "17. listopadu")
    }

    @Test fun capitalsWithDeclension() {
        has("Jaké je hlavní město Německa?", "Berlín")
        has("hlavní město Francie", "Paříž")
        has("Jaké je hlavní město Spojených států?", "Washington")
        has("hlavní město Austrálie", "Canberra")
        has("Jaké je hlavní město USA?", "Washington")
        has("Praha je hlavní město čeho?", "Česko")
        has("Na jakém kontinentu je Peru?", "Jižní Amerika")
    }

    @Test fun chemistry() {
        has("Jaká je značka zlata?", "Au", "79")
        has("Co je Fe za prvek?", "železo", "26")
        has("protonové číslo kyslíku", "8")
        has("prvek číslo 92", "uran")
    }

    @Test fun converters() {
        has("2026 římsky", "MMXXVI")
        has("Kolik je MCMLXXXIX?", "1989")
        has("SOS do morseovky", "... --- ...")
        has("... --- ...", "SOS")
        has("hláskuj AVIOR", "Alfa – Victor – India – Oscar – Romeo")
        has("Je 2028 přestupný rok?", "je přestupný")
        has("Je 2100 přestupný rok?", "není")
    }

    @Test fun otherTables() {
        has("Kolik má ČR krajů?", "14 krajů", "Ostrava")
        has("Krajské město Jihočeského kraje", "České Budějovice")
        has("Kolikátá planeta je Mars?", "4.")
        has("Kolik je planet?", "8 planet")
        has("Jaká je rychlost světla?", "299 792 458")
        has("Co znamená předpona giga?", "10⁹")
        has("tísňové číslo", "112", "155")
    }

    @Test fun noFalsePositives() {
        assertNull(a("Proč slaví lidé Vánoce?"))
        assertNull(a("Napiš mi esej o hlavním městě budoucnosti a jeho architektuře"))
        assertNull(a("Jak funguje zlato jako investice?"))
        assertNull(a("Kolik stojí byt v Praze?"))
    }

    @Test fun politicsStableFacts() {
        has("Kdo byl první prezident ČR?", "Václav Havel")
        has("Seznam prezidentů České republiky", "Klaus", "Zeman", "Pavel")
        has("Kolik má Sněmovna poslanců?", "200")
        has("Kolik je senátorů?", "81")
        has("Kdy vstoupila ČR do NATO?", "12. 3. 1999")
        has("Kdy vstoupilo Česko do EU?", "1. 5. 2004")
        assertNull(a("Kdo je premiér?"))   // mění se → mozek s balíčkem a ověřením
        assertNull(a("Co si myslí pravice o EU?"))
    }
}
