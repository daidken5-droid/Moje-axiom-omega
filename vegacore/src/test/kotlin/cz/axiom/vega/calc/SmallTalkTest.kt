package cz.axiom.vega.calc

import cz.axiom.vega.core.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan S27 (22. 11.): bez modelu na „ahoj“ lidská odpověď; „vymaž paměť“ / „nastavení“ nejsou webová rešerše. */
class SmallTalkTest {
    @Test fun replies() {
        assertTrue(SmallTalk.answer("Ahoj!", true)!!.startsWith("Ahoj! Teď běžím v režimu Bez sítě"))
        assertTrue(SmallTalk.answer("dobrý den", false)!!.startsWith("Dobrý den! Teď nemám připojený model"))
        assertEquals("Rádo se stalo.", SmallTalk.answer("děkuju", true))
        assertEquals("Dobře. Kdybys něco potřeboval/a, stačí napsat.", SmallTalk.answer("👍", true))
        assertTrue(SmallTalk.answer("co umíš?", true)!!.contains("vypni Bez sítě"))
        assertTrue(SmallTalk.answer("co umíš?", false)!!.contains("v Nastavení"))
        assertTrue(SmallTalk.answer("nastavení", true)!!.contains("menu ☰"))
        assertTrue(SmallTalk.answer("co je nového ve světě?", true)!!.contains("Ranní přehled"))
        // obsahové dotazy nejsou drobná konverzace
        for (q in listOf("ahoj, napiš mi e-mail šéfovi", "děkuju za pomoc s výpisem, ještě jednou ho spočítej", "pomoc s daněmi", "Operace Prato", "kurz eura"))
            assertNull(q, SmallTalk.answer(q, true))
    }

    @Test fun forgetAllAndRouting() {
        for (q in listOf("zapomeň všechno", "Vymaž paměť!", "smaž celou paměť", "zapomeň na všechno co víš")) assertTrue(q, SmallTalk.isForgetAll(q))
        for (q in listOf("zapomeň na psa Rexe", "smaž paměťovou kartu", "vymaž poslední zprávu")) assertFalse(q, SmallTalk.isForgetAll(q))
        val ctx = Router.Ctx(false, null, false, false, null)
        assertEquals(Router.Intent.Forget("*"), Router.route("vymaž paměť", ctx))
        assertTrue(Router.route("nastavení", ctx) !is Router.Intent.Research)
    }

    @Test fun phoneActionsAndDates() {
        assertTrue(SmallTalk.phoneAction("Zavolej mámě")!!.startsWith("Volat neumím"))
        assertTrue(SmallTalk.phoneAction("připomeň mi zítra v 8 zavolat mámě")!!.contains("Nic jsem nenastavil"))
        assertNull(SmallTalk.phoneAction("napiš SMS mámě, že přijdu později"))
        assertNull("vysvětlení, ne připomínka", SmallTalk.phoneAction("připomeň mi, co je to inflace"))
        val now = java.time.ZonedDateTime.of(2026, 11, 22, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
        assertEquals("Mezi 1. 1. 2026 a 22. 11. 2026 je 325 dní.", Quick.answer("jaký je rozdíl mezi 1.1.2026 a 22.11.2026", now))
        assertEquals("Od roku 1989 uplynulo 37 let (2026 − 1989).", Quick.answer("kolik let je od roku 1989", now))
    }
}
