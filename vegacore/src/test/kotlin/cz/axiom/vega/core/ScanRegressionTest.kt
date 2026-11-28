package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regrese z hloubkového scanu a red teamu (každý test = jeden konkrétní nález s reprodukujícím vstupem). */
class ScanRegressionTest {
    @Test fun evidenceOrigins() {
        fun parts(vararg d: Pair<String, String>) = Evidence.assess(d.map { Evidence.Doc(it.first, it.second) }).parts
        // ČTK × Reuters = dvě různé agentury, ne jedna linie
        val p = parts("a" to "Podle zprávy ČTK se most zřítil 3. 5. 2021.", "b" to "Podle zprávy Reuters se most zřítil 3. 5. 2021.")
        assertEquals("dvě nezávislé linie", p[0].status)
        // kopie téhož „úředního záznamu“ pod dvěma jmény není dvojí potvrzení
        val z = "Úřední záznam ze dne 3. 5. 2021: most se zřítil, protože chtěl zakrýt chybu."
        assertFalse(parts("x.txt" to z, "y.txt" to z)[0].status.startsWith("potvrzeno"))
        // krátká URL nesloučí nezávislé zdroje; neplatné datum se nepočítá
        val q = parts("a" to "Podle zprávy ČTK se most zřítil 3. 5. 2021.", "b" to "Podle svědectví místního hasiče se most zřítil 3. 5. 2021.", "c" to "Viz https://a")
        assertEquals("dvě nezávislé linie", q[0].status)
        assertTrue(Evidence.parse(Evidence.Doc("d", "Stalo se 31. 2. 2023.")).dates.isEmpty())
    }

    @Test fun claimSplitting() {
        val c = Grounding.claims("Praha je hlavní město ČR.[1] Brno je druhé největší město. [2] Dne 5. května 2020 se otevřel web www.example.com pro veřejnost [3]. Např. Novák to potvrdil [1].")
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(1)), c.map { it.cites })
        assertTrue(c[0].text.startsWith("Praha")); assertTrue(c[2].text, c[2].text.contains("5. května 2020") && c[2].text.contains("www.example.com"))
        assertTrue(c[3].text.startsWith("Např. Novák"))
    }

    @Test fun codeAgentParseIsLinearAndResumeDoesNotDuplicateInsert() {
        val evil = "### UPRAVIT: a.js\n<<<<<<< HLEDAT\nx\n=======\n".repeat(400)
        val t0 = System.nanoTime(); CodeAgent.parse(evil); assertTrue("parse trval příliš dlouho", (System.nanoTime() - t0) / 1e6 < 1500)
        val dir = kotlin.io.path.createTempDirectory("sr").toFile()
        val ws = Workspace.create(File(dir, "p"), "x", mapOf("a.js" to "foo();\nend();\n".toByteArray()))
        val plan = CodeAgent.parse("### UPRAVIT: a.js\n<<<<<<< HLEDAT\nfoo();\n=======\nfoo();\nbar(1, 2);\n>>>>>>> NAHRADIT\n")
        CodeAgent.apply(ws, plan, mapOf("a.js" to ws.hash("a.js")!!), "1")
        val again = CodeAgent.apply(ws, plan, mapOf("a.js" to ws.hash("a.js")!!), "2", resume = true)
        assertEquals(1, Regex("bar\\(1, 2\\)").findAll(ws.read("a.js")).count()); assertEquals(1, again.skipped.size)
        dir.deleteRecursively()
    }

    @Test fun econGuards() {
        val u = Econ.parseUnit("Cena za 1 kus je 250 Kč, variabilní náklady na 1 kus 120 Kč a fixní náklady 6 500 Kč.")
        assertEquals(250.0, u.price!!, 0.0); assertEquals(120.0, u.variable!!, 0.0); assertEquals(6500.0, u.fixed!!, 0.0)
        assertFalse(Econ.isUnitCorrection("Jaká je cena benzínu v roce 2024?"))
        assertFalse(Econ.isUnitCorrection("Objednávka je na 3 kusy."))
        assertTrue(Econ.isUnitCorrection("Spletl jsem se, fixní náklady jsou 7 700 Kč."))
        assertTrue(Econ.tariffAnswer(Econ.Tariff(1200.0, 0.0)).contains("kladné"))
        assertNull(Econ.num("1" + "0".repeat(400)))
        assertEquals(1.0, Econ.unitAnswer(Econ.Unit(100.0, 1.0, 0.5, null))!!.breakEven!!, 0.0)
        assertEquals(1234.0, CzNum.parse("1,234", decimalComma = false)!!, 0.0)
    }

    @Test fun revTaskSharedLockAndUpToDate() {
        val kv = MemKv(); val a = RevTask(kv, "t"); val b = RevTask(kv, "t")
        val r1 = a.start(listOf(RevTask.Input("x", "1")))
        val t = Thread { repeat(200) { a.log(r1.n, "s", "x", "l$it") } }; t.start()
        val r2 = b.revise(RevTask.Change.Scope("jen výdaje")); t.join()
        assertEquals(r2.n, a.current()!!.n) // revize se při souběhu neztratila
        assertFalse(a.upToDate()); assertTrue(a.publish(r2.n)); assertTrue(b.upToDate())
    }

    @Test fun verifierManifestRangesEnforced() {
        val d = kotlin.io.path.createTempDirectory("vm").toFile()
        File(d, "manifest.json").writeText("""{"format":"vega-verifier/1","name":"x","labels":["PODPORUJE","NEDOSTAČUJE","ODPORUJE"],"max_len":256,"threshold":0.0,"files":{"model.onnx":"a","vocab.tsv":"b"},"base_repo":"r","base_rev":"v"}""")
        val e = runCatching { VerifierBundle.read(d, verifyHashes = false) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("Práh"))
        d.deleteRecursively()
    }
}
