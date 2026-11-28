package cz.axiom.omega.core

import cz.axiom.omega.core.phone.PhoneCommands
import cz.axiom.omega.core.phone.PhoneCommands.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandsTest {
    private fun p(t: String) = PhoneCommands.parse(t)

    @Test fun everydayCommands() {
        assertEquals(Action.TORCH, p("Zapni svítilnu")!!.action)
        assertEquals("false", p("vypni baterku")!!.args["on"])
        assertEquals("30", p("hlasitost 30 %")!!.args["pct"])
        assertEquals("mute", p("ztlum zvuk")!!.args["mode"])
        assertEquals(Action.ALARM, p("Nastav budík na 6:30")!!.action)
        assertEquals("6", p("vzbuď mě v 6.30")!!.args["h"])
        assertEquals("600", p("minutka 10 minut")!!.args["sec"])
        assertEquals(Action.WIFI, p("vypni wifi")!!.action)
        assertEquals(Action.DND, p("zapni nerušit")!!.action)
        assertEquals("40", p("jas 40 %")!!.args["pct"])
        assertEquals("baterie", p("otevři nastavení baterie")!!.args["section"])
        assertEquals("spotify", p("spusť Spotify")!!.args["app"])
        assertEquals(Action.MEDIA, p("pauza")!!.action)
        assertEquals("+420777123456", p("zavolej +420 777 123 456")!!.args["number"])
        assertEquals(Action.STATUS, p("stav telefonu")!!.action)
    }

    @Test fun rootAlwaysConfirmed() {
        val c = p("root: dumpsys battery")!!
        assertTrue(c.root && c.confirm && c.args["cmd"] == "dumpsys battery")
        assertTrue(p("zavři všechny aplikace na pozadí přes root")!!.confirm)
        assertTrue(p("restartuj telefon")!!.confirm)
        assertEquals(Action.ROOT_STATUS, p("Mám root?")!!.action)
    }

    @Test fun questionsAreNotCommands() {
        assertNull(p("Jak zapnout svítilnu na iPhonu?"))
        assertNull(p("Proč je zvuk ve vodě rychlejší?"))
        assertNull(p("Kolik stojí svítilna?"))
        assertNull(p("Napiš mi báseň o jasu hvězd"))
        assertNull(p("Spusť analýzu dat a porovnej tržby za rok 2025 s rokem 2024 včetně grafu a doporučení pro vedení firmy"))
    }
}
