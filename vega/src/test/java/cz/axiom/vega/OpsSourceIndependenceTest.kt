package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Doporučení 25. 11., sprint 1: od dotazu uživatele po zobrazené odůvodnění – dva přepisy jedné zprávy ČTK = jedna linie, v obou pořadích. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsSourceIndependenceTest {
    private val a = "Most přes Labe v Ústí uzavřeli 3. 11. 2026, informovala agentura ČTK. Silničáři objíždějí centrum."
    private val b = "Podle ČTK je od 3. 11. 2026 zavřený most v Ústí nad Labem; řidiči musí jet po objízdné trase."

    private fun run(first: Pair<String, String>, second: Pair<String, String>): OpsHarness.Reply {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val ids = listOf(first, second).map { (n, t) -> h.o.attach(n, "text/plain", t.toByteArray()).getString("id") }
        return h.say(c, "Dva weby píšou, že most zavřeli. Je to doložené?", ids)
    }

    @Test fun twoRewritesOfOneAgencyReportShownAsOneLine() {
        val r1 = run("idnes.txt" to a, "novinky.txt" to b); val r2 = run("novinky.txt" to b, "idnes.txt" to a)
        for (r in listOf(r1, r2)) {
            assertTrue(r.text, r.text.contains("tvrzení šířilo") && r.text.contains("tutéž výpověď"))
            assertFalse(r.text, r.text.contains("dvě nezávislé linie"))
            val part = r.card!!.getJSONArray("parts").getJSONObject(0)
            assertEquals(r.text, 1, part.getInt("lines"))
        }
        fun st(r: OpsHarness.Reply) = r.card!!.getJSONArray("parts").let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("status") } }
        assertEquals(st(r1), st(r2))
    }
}
