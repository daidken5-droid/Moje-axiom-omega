package cz.axiom.vega.probe

import cz.axiom.vega.core.Mem
import org.junit.Assume.assumeTrue
import org.junit.Test

class SecretProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        for (s in listOf("moje heslo k bance je Kocka123", "heslo: Kocka123", "moje heslo je kocka", "heslo do wifi je Doma2024!", "PIN ke kartě je 1234", "pin je 1234",
            "můj PIN 4321", "číslo karty 4111 1111 1111 1111", "rodné číslo 900101/1234", "účet 123456789/0800", "IBAN CZ65 0800 0000 1920 0014 5399",
            "klíč AIzaSyA1234567890abcdefghijklmnopqrstu", "sk-proj-abcdefghijklmnop123456", "heslo k e-mailu: Pes", "přihlašovací jméno jan.novak a heslo xyz12345",
            "CVV 123", "kód z SMS 482913", "mám psa Rexe", "pin kód od trezoru 0000", "heslem je Kocka123", "moje heslo zní tajne123", "heslo je 12345678", "zapomněl jsem heslo k bance, co mám dělat", "jak si vytvořit silné heslo", "kód programu je v souboru main.kt", "PIN kód nefunguje, co s tím", "mám 3 děti a pin jsem zapomněl", "heslo mám v trezoru"))
            println("PROBE|${Mem.looksSecret(s)}|$s")
    }
}
