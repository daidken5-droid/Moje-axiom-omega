package cz.axiom.vega.core

import cz.axiom.omega.core.llm.CancelToken
import org.junit.Test
import kotlin.test.assertTrue

/** p50/p95 lokálních nástrojů na JVM (orientační – telefon bude pomalejší; Pixel neměřen). */
class VegaBenchTest {
    private val ctx = object : ToolCtx { override val cancel = CancelToken() }

    private fun bench(name: String, n: Int, block: () -> Unit): Pair<Double, Double> {
        repeat(5) { block() }
        val t = (1..n).map { val s = System.nanoTime(); block(); (System.nanoTime() - s) / 1e6 }.sorted()
        val p50 = t[t.size / 2]; val p95 = t[(t.size * 95) / 100 - 1]
        println("VEGA-BENCH $name: p50 ${"%.2f".format(p50)} ms · p95 ${"%.2f".format(p95)} ms")
        return p50 to p95
    }

    @Test fun localToolsAreFast() {
        val rows = (1..10_000).joinToString("\n") { "%02d.%02d.2025;%s;CZK;Obchod %d;\"poznámka; %d\"".format(it % 28 + 1, it % 12 + 1, (if (it % 3 == 0) "" else "-") + "%,d,%02d".format(it * 7, it % 100).replace(",", " ").replaceFirst(" ", ""), it, it) }
        val csv = "Datum;Částka;Měna;Protistrana;Zpráva\n$rows\n"
        val spec = Statement.detect(csv).spec!!
        val (s50, _) = bench("výpis 10 000 řádků", 20) { Statement.process(csv, spec) }
        bench("CSV inspektor 10 000 řádků", 20) { Tools.get("csv.inspect")!!.run(Value.Text(csv), emptyMap(), ctx) }
        val code = (1..5_000).joinToString("\n") { "val x$it = $it" }
        val code2 = code.replace("val x2500 = 2500", "val x2500 = 2501").replace("val x10 = 10", "val x10 = 11")
        bench("diff 5 000 řádků (2 změny)", 30) { Diff.lines(code, code2) }
        val big = ByteArray(10_000_000) { (it % 251).toByte() }
        bench("SHA-256 10 MB", 10) { Checksum.of(big, "SHA-256") }
        val log = (1..50_000).joinToString("\n") { if (it % 997 == 0) "E/App: chyba $it" else "I/App: ok $it" }
        bench("hledání v logu 50 000 řádků", 20) { LogTool.search(log, "chyba", false, 1, null) }
        bench("NDEF parse", 200) { NdefParser.parse(NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload("https://example.cz/a"))))) }
        bench("router (intent)", 500) { Router.route("udělej z toho nástroj, který každý týden použiju na výpis", Router.Ctx(true, "a.csv", false, false, null)) }
        assertTrue(s50 < 2_000)
    }
}
