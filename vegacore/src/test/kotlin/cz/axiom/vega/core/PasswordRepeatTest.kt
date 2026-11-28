package cz.axiom.vega.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardware 27. 11.: opakovaný úsek nebo dlouhá řada stejného znaku jen zdánlivě prodlužují heslo – nesmí vyjít „silné“. */
class PasswordRepeatTest {
    @Test fun repeatedOrRunHeavyPasswordsAreWeak() {
        for (p in listOf("Heslo-heslo-heslo-heslo", "Password1Password1", "aaaaaaaaaaaaaaaaB1", "Praha2024Praha2024", "Jablko-Jablko-Jablko1", "Ahoj1234Ahoj1234", "Kocka!Kocka!Kocka!")) {
            val c = WifiTools.checkPassword(p)
            assertTrue("$p → $c", c.score <= 1)
        }
    }

    @Test fun strongOnesStayStrong() {
        for (p in listOf("Vrtacka-mesic-reka-hora!42", "k7#Qm2!vX9pL@4zR", "Zahrada-klic-most-kniha-sova-majak#42", "Tr0ub4dor&3-horse-staple"))
            assertTrue("$p → ${WifiTools.checkPassword(p)}", WifiTools.checkPassword(p).score >= 3)
    }
}
