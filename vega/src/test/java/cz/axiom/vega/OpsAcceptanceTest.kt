package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PŘIJÍMACÍ ZKOUŠKY přes stejné vstupy jako uživatel (Ops.chat / runTool / attach), na JVM s Android SQLite.
 * Air-gap zapnutý = jakýkoli síťový požadavek by spadl → důkaz, že cesta běží bez sítě a bez LLM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsAcceptanceTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private fun fixture(n: String) = javaClass.getResource("/$n")!!.readBytes()

    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }
    private fun ops(r: Rec) = Ops(ctx) { t, d -> r.ev += t to d }.also { it.settings.airgap = true }

    /** Počká, až operace doběhne (událost idle), a vrátí poslední zprávu. */
    private fun awaitMsg(r: Rec, before: Int): JSONObject {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            if (r.ev.drop(before).any { it.first == "idle" }) break
            Thread.sleep(20)
        }
        r.ev.drop(before).firstOrNull { it.first == "error" }?.let { throw AssertionError("chyba: ${it.second}") }
        return r.ev.drop(before).last { it.first == "message" }.second.getJSONObject("msg")
    }

    @Test fun statementToolProcedureSurvivesRestartAndRunsOnAnotherFile() {
        val r = Rec(); val o = ops(r)
        val chat = o.newChat()
        o.attach("vypis-duben.csv", "text/csv", fixture("vypis-duben.csv"))
        var n = r.ev.size
        o.chat(chat, "udělej z toho nástroj, který každý týden použiju na výpis")
        val m = awaitMsg(r, n)
        assertTrue(m.getString("text"), m.getString("text").contains("desetinnou čárku") && m.getString("text").contains("5/5"))
        val card = m.getJSONObject("card"); assertTrue(card.getBoolean("done"))
        val saved = o.saveProcedure(card.getJSONObject("save"))
        assertEquals(1, saved.getInt("version"))

        // ruční cesta = stejný výsledek (stejná implementace)
        n = r.ev.size
        val fid = o.attach("vypis-duben.csv", "text/csv", fixture("vypis-duben.csv")).getString("id")
        o.runTool(chat, "statement.process", JSONObject().put("file", fid), emptyMap())
        val manual = awaitMsg(r, n).getJSONObject("card").getJSONObject("value")
        val chatFirstStep = o.runJson(card.getString("run"))!!.getJSONArray("stepValues").getJSONObject(0)
        assertEquals(chatFirstStep.getJSONArray("rows").toString(), manual.getJSONArray("rows").toString())

        // „restart“: nová instance Ops nad stejným úložištěm, jiný soubor, spuštění větou
        val r2 = Rec(); val o2 = ops(r2)
        o2.attach("vypis-kveten.csv", "text/csv", fixture("vypis-kveten.csv"))
        n = r2.ev.size
        o2.chat(o2.newChat(), "zpracuj výpis")
        val m2 = awaitMsg(r2, n)
        val v = m2.getJSONObject("card").getJSONObject("value")
        assertEquals("2025-05", v.getJSONArray("rows").getJSONArray(0).getString(0))
        assertTrue(m2.getString("text"), m2.getString("text").contains("v1"))
    }

    @Test fun preferenceAfterRestartCorrectionForgetAndProjects() {
        val r = Rec(); val o = ops(r)
        var n = r.ev.size
        o.chat(o.newChat(), "Pamatuj si, že odpovídej stručně"); awaitMsg(r, n)
        val o2 = ops(Rec())
        assertTrue(o2.memory.context(null)!!.contains("stručně"))
        val r3 = Rec(); val o3 = ops(r3)
        n = r3.ev.size; o3.chat(o3.newChat(), "pamatuj si, že odpovídej podrobně")
        assertTrue(awaitMsg(r3, n).getString("text").contains("Upraveno"))
        assertTrue(ops(Rec()).memory.context(null)!!.let { it.contains("podrobně") && !it.contains("stručně") })
        // projekt: fakt jen pro projekt A
        val pa = o3.createProject("A", emptyMap())
        n = r3.ev.size; o3.chat(o3.newChat(), "pamatuj si, že v tomhle projektu píšeme testy v Jestu"); awaitMsg(r3, n)
        assertTrue(o3.memory.context("project:$pa")!!.contains("Jestu"))
        val pb = o3.createProject("B", emptyMap())
        assertTrue(o3.memory.context("project:$pb")?.contains("Jestu") != true)
        // zapomenutí → nový chat po restartu už nic
        n = r3.ev.size; o3.chat(o3.newChat(), "zapomeň na odpovídej podrobně"); assertTrue(awaitMsg(r3, n).getString("text").contains("Zapomenuto"))
        val o4 = ops(Rec())
        assertTrue(o4.memory.context(null)?.contains("podrobně") != true)
        // „Co si pamatuješ a proč?“
        val r5 = Rec(); val o5 = ops(r5); n = r5.ev.size
        o5.chat(o5.newChat(), "co si pamatuješ a proč?")
        assertNotNull(awaitMsg(r5, n).getString("text"))
    }

    @Test fun offlineLocalSearchAndToolsWithoutNetworkOrLlm() {
        val r = Rec(); val o = ops(r)
        assertNull(o.engines.pick()) // žádný klíč = žádný model
        o.attach("vypis-duben.csv", "text/csv", fixture("vypis-duben.csv"))
        val chat = o.newChat()
        var n = r.ev.size
        o.chat(chat, "najdi v mých souborech Netflix")
        val m = awaitMsg(r, n)
        val rows = m.getJSONObject("card").getJSONObject("value").getJSONArray("rows")
        assertTrue(rows.length() >= 1 && rows.getJSONArray(0).getString(0) == "vypis-duben.csv")
        // CSV diagnostika z chatu bez modelu
        o.attach("rozbity.csv", "text/csv", "a;b\n1;\"neuzavřené\n2;3\n".toByteArray())
        n = r.ev.size
        o.chat(chat, "proč se mi rozbil import CSV?")
        val notes = awaitMsg(r, n).getJSONObject("card").getJSONObject("value").getJSONArray("notes").toString()
        assertTrue(notes, notes.contains("neuzavřené"))
        // rozhovor bez modelu = srozumitelná odpověď, ne pád
        n = r.ev.size
        o.chat(chat, "kolik je 17 * 23")
        assertTrue(awaitMsg(r, n).getString("text").contains("391"))
    }
}
