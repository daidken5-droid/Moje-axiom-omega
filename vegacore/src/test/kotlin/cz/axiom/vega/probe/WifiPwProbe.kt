package cz.axiom.vega.probe

import cz.axiom.vega.core.WifiTools
import org.junit.Assume.assumeTrue
import org.junit.Test

class WifiPwProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        for ((pw, ssid) in listOf("12345678" to "Doma", "password" to null, "heslo123" to null, "Doma2024!" to "Doma", "doma2024" to "Doma", "Novakovi1985" to "Novakovi",
            "qwertyuiop" to null, "aaaaaaaaaaaa" to null, "Tr0ub4dor&3" to null, "kočka-strom-okno-mrak" to null, "correct horse battery staple" to null,
            "P@ssw0rd" to null, "Admin123" to null, "1qaz2wsx3edc" to null, "abcdefgh" to null, "Xk9#mQ2\$vL7pR4" to null, "iloveyou" to null,
            "Pražské jaro 1968" to null, "zxcvbnm123" to null, "NovakoviWifi" to "NovakoviWifi", "11111111" to null, "Monika1990" to null, "heslo" to null))
            println("PROBE|$pw|${WifiTools.checkPassword(pw, ssid).let { "${it.score} ${it.level} – ${it.reason}" }}")
    }
}
