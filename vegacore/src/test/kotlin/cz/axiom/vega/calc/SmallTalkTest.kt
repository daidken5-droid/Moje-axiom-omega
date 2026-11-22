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
}
