package cz.axiom.vega

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Smazání chatu smaže i výsledek zpracovaného výpisu (všechny pohyby) – dřív zůstal v telefonu, dokud nebylo přes 30 běhů. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsDeleteChatRunsTest {
    private val pdfText = listOf("Vypis z uctu 123-456789/0100   Obdobi 01.11.2026 - 30.11.2026", "Datum   Popis   Castka   Zustatek",
        "03.11.2026  CEZ zaloha  -1 250,00  8 750,00", "10.11.2026  Mzda  30 000,00  38 750,00", "12.11.2026  Lidl  -312,40  38 437,60").joinToString("\n")

    private fun statementRun(h: OpsHarness, chat: String, name: String): String {
        val id = h.o.attach(name, "application/pdf", "%PDF-1.4\n%fake $name".toByteArray()).getString("id")
        val run = h.say(chat, "udělej z toho nástroj na výpis", listOf(id), ms = 30_000).card?.optString("run")
        return run?.takeIf { it.isNotEmpty() } ?: error("výpis nezpracován")
    }

    @Test fun deletingChatDeletesItsStatementResult() {
        val h = OpsHarness(airgap = true); val o = h.o
        o.fileTextHook = { pdfText }
        val a = o.newChat(); val runA = statementRun(h, a, "vypis-a.pdf")
        val b = o.newChat(); val runB = statementRun(h, b, "vypis-b.pdf")
        assertNotNull(o.run(runA)); assertNotNull(o.run(runB))
        o.deleteChat(a)
        assertNull("výsledek ze smazaného chatu zůstal", o.run(runA))
        assertNotNull("výsledek jiného chatu se nesmí smazat", o.run(runB))
    }
}
