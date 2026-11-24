package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Den 24. 11., kolo 23: přesné texty návrhů z app.js celou cestou Ops.chat() – PDF výpis → tři doplňující dotazy, vše bez modelu (režim Bez sítě). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsStatementChipsTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun chipFlowWithoutModel() {
        val js = java.io.File("src/main/assets/ui/app.js").readText()
        val process = Regex("t: 'Zpracovat výpis', a: 'say:([^']+)'").find(js)!!.groupValues[1]
        val follow = Regex("a: 'say:([^']+)' \\}").findAll(js.substringAfter("hd.includes('Příjmy')")).map { it.groupValues[1] }.take(3).toList()
        assertTrue("$follow", follow.size == 3)
        val events = java.util.concurrent.LinkedBlockingQueue<Pair<String, JSONObject>>()
        val o = Ops(ctx) { t, d -> events.add(t to d) }.also { it.settings.airgap = true }
        val pdfText = listOf("Vypis z uctu 123-456789/0100   Obdobi 01.10.2026 - 30.11.2026", "Datum   Popis   Castka   Zustatek",
            "03.10.2026  NETFLIX.COM  -259,00  9 741,00", "10.10.2026  Mzda  30 000,00  39 741,00", "03.11.2026  NETFLIX.COM  -259,00  39 482,00",
            "05.11.2026  Najem byt  -12 500,00  26 982,00", "12.11.2026  Lidl  -312,40  26 669,60").joinToString("\n")
        o.fileTextHook = { pdfText }
        val chat = o.newChat()
        fun say(q: String, ids: List<String> = emptyList()): JSONObject { events.clear(); o.chat(chat, q, ids); var m: JSONObject? = null; val u = System.currentTimeMillis() + 30_000
            while (m == null && System.currentTimeMillis() < u) { val e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (e.first == "message" && e.second.getJSONObject("msg").optString("role") == "vega") m = e.second.getJSONObject("msg") }
            return m ?: throw AssertionError("žádná odpověď na „$q“") }
        val id = o.attach("vypis-rijen-listopad.pdf", "application/pdf", "%PDF-1.4\n%fake".toByteArray()).getString("id")
        val r0 = say(process, listOf(id))
        assertTrue(r0.toString(), r0.optJSONObject("card")?.optString("type") == "run" && r0.getString("text").contains("Převedeno z PDF (5 pohybů)"))
        // výsledek jako první řádek; u PDF bez technického „Formát / oddělovač“
        val t0 = r0.getString("text").replace('\u00a0', ' ')
        assertTrue(t0, t0.startsWith("Příjmy 30 000,00 · výdaje −13 330,40 · saldo 16 669,60 · 5 pohybů") && !t0.contains("**Formát:**") && !t0.contains("Oddělovač je středník"))
        val a1 = say(follow[0]).getString("text").replace(' ', ' ')
        assertTrue(a1, a1.startsWith("Nejvíc utrácíš za: **Bydlení a energie**"))
        val a2 = say(follow[1]).getString("text")
        assertTrue(a2, a2.contains("**Pravidelné platby** (1)") && a2.contains("NETFLIX", ignoreCase = true))
        val a3 = say(follow[2])
        assertTrue(a3.toString(), a3.optJSONObject("card")?.optString("type") == "file" && a3.getString("text").contains("5 pohyb").not() && a3.getString("text").contains("5 řádků"))
        assertFalse(a3.getString("text"), a3.getString("text").contains("souhrn"))
        // noc NN5: eliptická navazující otázka bere předchozí otázku z historie chatu
        say("kolik jsem utratil za jídlo")
        val e1 = say("a za bydlení?").getString("text").replace('\u00a0', ' ')
        assertTrue(e1, e1.contains("bydlení a energie") && e1.contains("−12 500,00"))
    }
}
