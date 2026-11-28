package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Strážný test (refaktoring 10, noc 27./28. 11.) PŘED vyjmutím převodu formuláře výzvy z Ops.kt: vzor → výpočet → karta; vadné datum = srozumitelná chyba. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsDemandGuardTest {
    @Test fun sampleFormProducesCard() {
        val h = OpsHarness(); val c = h.o.newChat(); h.o.pdfBuilder = { "%PDF-1.4 náhrada".toByteArray() }
        val s = h.o.demandSample()
        val n = h.mark(); h.o.demand(c, s)
        val r = h.replyAfter(n, ms = 60_000)
        assertEquals(r.toString(), "demand", r.card?.optString("type"))
        assertTrue(r.text, r.text.startsWith("Výzva ${s.getString("invoice")}: celkem "))
        val lines = r.card!!.getJSONArray("lines")
        assertTrue(lines.length() >= 2)
        assertEquals(2, r.card!!.getJSONArray("files").length())
        assertTrue(r.text, r.text.startsWith("Výzva FV2025014: celkem 30\u00a0638,94 Kč (jistina 28\u00a0813,72, úrok 625,22 k 2025-06-15). Sazba 12.0 % ověřena"))
        assertEquals(30638.94, r.card!!.optDouble("total"), 0.005)
        assertEquals(5, lines.length())
        println("DEMAND " + r.text.take(200) + " | lines=" + lines.length() + " total=" + r.card!!.optDouble("total"))
    }

    @Test fun badDateIsClearError() {
        val h = OpsHarness(); val c = h.o.newChat(); h.o.pdfBuilder = { "%PDF-1.4 náhrada".toByteArray() }
        val s = h.o.demandSample().put("due", "31.12.2026")
        val n = h.mark(); h.o.demand(c, s)
        val r = h.replyAfter(n, ms = 30_000)
        assertTrue(r.toString(), (r.error ?: r.text).contains("Datum „due“ chybí nebo nemá tvar RRRR-MM-DD."))
    }

    /** Noc 27./28. 11.: prázdné / chybějící pole a nesmyslná částka = srozumitelná věta s názvem pole, ne „Něco se pokazilo uvnitř VEGA“. */
    @Test fun missingOrBadFieldsAreNamed() {
        val cases = listOf(
            { o: org.json.JSONObject -> o.remove("debtor"); o } to "dlužník",
            { o: org.json.JSONObject -> o.put("creditor", "  "); o } to "věřitel",
            { o: org.json.JSONObject -> o.put("amount", "abc"); o } to "částka",
            { o: org.json.JSONObject -> o.put("amount", -5); o } to "částka",
            { o: org.json.JSONObject -> o.remove("invoice"); o } to "číslo faktury")
        for ((mod, word) in cases) {
            val h = OpsHarness(); val c = h.o.newChat(); h.o.pdfBuilder = { "%PDF-1.4".toByteArray() }
            val n = h.mark(); h.o.demand(c, mod(h.o.demandSample()))
            val r = h.replyAfter(n, ms = 30_000)
            val msg = r.error ?: r.text
            assertTrue("$word → $msg", msg.contains(word, ignoreCase = true) && !msg.contains("Něco se pokazilo"))
        }
    }

    /** Noc 27./28. 11. (oprava regrese z N6): adresy a předmět jsou NEPOVINNÉ jako dřív – formulář je posílá prázdné a výzva musí vzniknout. */
    @Test fun emptyAddressesAndSubjectStillWork() {
        val h = OpsHarness(); val c = h.o.newChat(); h.o.pdfBuilder = { "%PDF-1.4".toByteArray() }
        val s = h.o.demandSample().put("creditorAddr", "").put("debtorAddr", "").put("subject", "")
        val n = h.mark(); h.o.demand(c, s)
        val r = h.replyAfter(n, ms = 60_000)
        assertEquals(r.toString(), "demand", r.card?.optString("type"))
    }
}
