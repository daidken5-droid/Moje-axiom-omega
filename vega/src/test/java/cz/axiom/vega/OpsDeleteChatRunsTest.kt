package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Smazání chatu smaže i výsledek zpracovaného výpisu (všechny pohyby) – dřív zůstal v telefonu, dokud nebylo přes 30 běhů. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsDeleteChatRunsTest {
    private val pdfText = listOf("Vypis z uctu 123-456789/0100   Obdobi 01.11.2026 - 30.11.2026", "Datum   Popis   Castka   Zustatek",
        "03.11.2026  CEZ zaloha  -1 250,00  8 750,00", "10.11.2026  Mzda  30 000,00  38 750,00", "12.11.2026  Lidl  -312,40  38 437,60").joinToString("\n")

    private fun statementRun(o: Ops, events: java.util.concurrent.LinkedBlockingQueue<Pair<String, JSONObject>>, chat: String, name: String): String {
        events.clear()
        val id = o.attach(name, "application/pdf", "%PDF-1.4\n%fake $name".toByteArray()).getString("id")
        o.chat(chat, "udělej z toho nástroj na výpis", listOf(id))
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) { val e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (e.first == "message") e.second.getJSONObject("msg").optJSONObject("card")?.optString("run")?.takeIf { it.isNotEmpty() }?.let { return it } }
        error("výpis nezpracován")
    }

    @Test fun deletingChatDeletesItsStatementResult() {
        val events = java.util.concurrent.LinkedBlockingQueue<Pair<String, JSONObject>>()
        val o = Ops(RuntimeEnvironment.getApplication()) { t, d -> events.add(t to d) }.also { it.settings.airgap = true }
        o.fileTextHook = { pdfText }
        val a = o.newChat(); val runA = statementRun(o, events, a, "vypis-a.pdf")
        val b = o.newChat(); val runB = statementRun(o, events, b, "vypis-b.pdf")
        assertNotNull(o.run(runA)); assertNotNull(o.run(runB))
        o.deleteChat(a)
        assertNull("výsledek ze smazaného chatu zůstal", o.run(runA))
        assertNotNull("výsledek jiného chatu se nesmí smazat", o.run(runB))
    }
}
