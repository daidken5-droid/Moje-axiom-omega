package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Den 24. 11., kolo 1: „zpracuj výpis“ nad PDF z banky – celá cesta (PdfBox → převod → parser → tabulka). Dřív jen „stáhni CSV“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsPdfStatementTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun pdfStatementBecomesTable() {
        val events = java.util.concurrent.LinkedBlockingQueue<Pair<String, JSONObject>>()
        val o = Ops(ctx) { t, d -> events.add(t to d) }.also { it.settings.airgap = true }
        // text, jak ho z PDF banky vytáhne PdfBox (sortByPosition): hlavička banky, záhlaví tabulky, řádky pohybů, patička
        val pdfText = listOf("Vypis z uctu 123-456789/0100   Obdobi 01.11.2026 - 30.11.2026", "Datum   Popis   Castka   Zustatek",
            "03.11.2026  CEZ zaloha  -1 250,00  8 750,00", "10.11.2026  Mzda  30 000,00  38 750,00", "12.11.2026  Lidl  -312,40  38 437,60", "20.11.2026  Bankomat  -2 000,00  36 437,60", "Strana 1/1").joinToString("\n")
        o.fileTextHook = { b -> if (b.name.endsWith(".pdf")) pdfText else error("jen PDF") }
        val bytes = "%PDF-1.4\n%fake".toByteArray()
        val chat = o.newChat()
        val id = o.attach("vypis-listopad.pdf", "application/pdf", bytes).getString("id")
        o.chat(chat, "udělej z toho nástroj na výpis", listOf(id))
        var msg: JSONObject? = null; val until = System.currentTimeMillis() + 30_000
        while (msg == null && System.currentTimeMillis() < until) { val e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (e.first == "message" && e.second.getJSONObject("msg").optString("role") == "vega") msg = e.second.getJSONObject("msg") }
        val text = msg!!.getString("text")
        assertTrue(text, text.contains("Převedeno z PDF (4 pohybů)") && text.contains("návazností sedí"))
        assertFalse(text, text.contains("spočítat neumím"))
        // kolo 4: tři největší kategorie výdajů rovnou v odpovědi (bez modelu)
        assertTrue(text, text.contains("**Největší výdaje:** Hotovost") && text.contains("Bydlení a energie") && text.contains("Potraviny"))
        val months = msg.getJSONObject("card").getJSONObject("value").getJSONArray("rows").getJSONArray(0).toString().replace('\u00a0', ' ')
        assertTrue(months, months.contains("30 000,00") && months.contains("−3 562,40") && months.contains("\"4\""))
        assertFalse("u PDF se postup neukládá (příště by dostal surové PDF)", msg.getJSONObject("card").has("save"))
        // kolo 5: doplňující dotaz na útraty – z tabulky výpisu, bez modelu (režim Bez sítě)
        events.clear(); o.chat(chat, "kolik jsem utratil za jídlo?", emptyList())
        var ans: JSONObject? = null; val u2 = System.currentTimeMillis() + 15_000
        while (ans == null && System.currentTimeMillis() < u2) { val e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (e.first == "message" && e.second.getJSONObject("msg").optString("role") == "vega") ans = e.second.getJSONObject("msg") }
        val at = ans!!.getString("text").replace('\u00a0', ' ')
        assertTrue(at, at.contains("**−312,40 Kč** (1 platba"))
    }
}
