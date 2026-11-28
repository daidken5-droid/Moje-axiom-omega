package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Strážný test (28. 11., uživatel zvyklý na ChatGPT): dotaz na souhlas „vypadá jako heslo / číslo karty“ se nesmí ptát u běžných čísel
 * (telefon, objednávka, faktura, VS, IČO, PSČ, datum) – a musí se ptát u skutečných citlivých údajů. Sonda 18 vět: dnes 0 chyb.
 */
class SecretDetectGuardTest {
    @Test fun noFalseAlarmsOnEverydayNumbers() {
        for (s in listOf("Zavolej mi na 777 123 456", "Moje číslo je +420 777 123 456", "Objednávka č. 2026001234 nedorazila", "Faktura 20250115 je po splatnosti",
            "Vyrobeno 12. 3. 2024, šarže 4589", "Kolik je 1234567 * 3?", "PSČ 110 00 Praha", "Variabilní symbol 1234567890", "IČO 27082440",
            "Schůzka je 15. 12. ve 14:30", "Mám 3 děti a 2 psy", "napiš mi email na petr@example.cz"))
            assertFalse(Mem.looksSecret(s), s)
    }

    @Test fun catchesRealSecrets() {
        for (s in listOf("Rodné číslo 900101/1234", "Moje heslo je Kocka123", "Číslo karty 4111 1111 1111 1111", "účet 123456789/0100", "PIN 4826", "IBAN CZ65 0800 0000 1920 0014 5399"))
            assertTrue(Mem.looksSecret(s), s)
    }
}
