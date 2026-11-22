package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kolo testů T6 (21. 11. 2026): celá cesta zprávy (Ops.chat → router → kalkulačka / kurz / výpis → odpověď) pro opravy dne.
 * Air-gap zapnutý = žádná síť a žádný model; ověřuje se, co laik skutečně dostane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsDayTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }
    private fun ops(r: Rec) = Ops(ctx) { t, d -> r.ev += t to d }.also { it.settings.airgap = true }

    private fun ask(o: Ops, r: Rec, chat: String, text: String, ids: List<String> = emptyList()): String {
        val before = r.ev.size
        o.chat(chat, text, ids)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && r.ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
        r.ev.drop(before).firstOrNull { it.first == "error" }?.let { return "CHYBA: " + it.second }
        return r.ev.drop(before).lastOrNull { it.first == "message" }?.second?.getJSONObject("msg")?.optString("text") ?: "(žádná odpověď)"
    }

    @Test fun laikQuestionsGetExactOfflineAnswersNotWebResearch() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        assertTrue(ask(o, r, c, "koľko dní do vianoc").contains("Do Štědrého dne zbývá"))
        assertTrue(ask(o, r, c, "sleva 30 % z 899").contains("po slevě zaplatíš 629,3 Kč"))
        assertTrue(ask(o, r, c, "kolik je 15% z 2 400 Kč").contains("360 Kč"))
        assertTrue(ask(o, r, c, "100 / 0").contains("Nulou dělit nejde"))
        val bep = ask(o, r, c, "prodávám za 250, vyrobit stojí 150, fixní náklady 8000, prodám 60 kusů")
        assertTrue(bep, bep.contains("ztrátu 2 000 Kč"))
        // „díkec“ dřív spustil rešerši na webu – v air-gapu by přišla hláška o rešerši
        assertFalse(ask(o, r, c, "díkec").contains("rešerš"))
    }

    @Test fun currencyInAirgapExplainsHowToGiveRate() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        for (q in listOf("100 eur na koruny", "100 € v Kč", "kolik je 100 euro v korunách")) {
            val a = ask(o, r, c, q)
            assertTrue("$q → $a", a.contains("Kurz offline neznám") || a.contains("kurz offline neznám"))
        }
    }

    @Test fun statementWithDebitColumnCountsExpenses() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        val csv = "Datum;Částka;Typ pohybu;Popis\n01.03.2026;25 000,00;Kredit;Výplata\n02.03.2026;1 234,50;Debet;Albert\n03.03.2026;500,00;Debet;Benzín\n04.03.2026;300,00;Kredit;Vratka\n05.03.2026;99,00;Debet;Poplatek"
        val id = o.attach("vypis.csv", "text/csv", csv.toByteArray()).getString("id")
        val a = ask(o, r, c, "spočítej příjmy a výdaje z výpisu", listOf(id)).replace('\u00a0', ' ').replace('\u202f', ' ')
        assertTrue(a, a.contains("Příjmy 25 300,00") && a.contains("výdaje −1 833,50"))
    }

    /** Bez klíče: odpověď rovnou nese tlačítko „Zapnout rozhovor zdarma“ (laik se nemusí vracet na úvod); v Bez sítě ne. */
    @Test fun noModelReplyOffersSetupButtonOnlyWhenItHelps() {
        fun lastCard(r: Rec, o: Ops, c: String, t: String): JSONObject? {
            val before = r.ev.size; o.chat(c, t, emptyList())
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && r.ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
            return r.ev.drop(before).lastOrNull { it.first == "message" }?.second?.getJSONObject("msg")?.optJSONObject("card")
        }
        val r = Rec(); val o = Ops(ctx) { t, d -> r.ev += t to d }.also { it.settings.airgap = false; it.settings.webAllowed = false }
        val c = o.newChat()
        assertTrue(lastCard(r, o, c, "napiš omluvný e-mail šéfovi")?.optString("type") == "setup")
        // výpočet bez modelu tlačítko nepotřebuje
        assertTrue(lastCard(r, o, c, "kolik je 15 % z 2400") == null)
        o.settings.airgap = true
        assertTrue(lastCard(r, o, c, "napiš omluvný e-mail šéfovi") == null)
    }
}
