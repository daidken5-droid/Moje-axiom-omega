package cz.axiom.vega.core

import cz.axiom.vega.calc.CnbRates
import cz.axiom.vega.calc.LongText
import cz.axiom.vega.calc.Quick
import cz.axiom.vega.calc.Topic
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kolo testů T4: hraniční a náhodné vstupy do parserů, které zpracovávají cizí data (výpis, odpověď modelu, NFC, WiFi,
 * APDU, zdroje, RAG). Funkce nesmí spadnout neočekávanou výjimkou ani „zamrznout“ (> 1,5 s = podezření na katastrofální
 * backtracking regexu → zamrzlé UI na telefonu). Pevný seed → opakovatelné.
 */
class EdgeFuzzTest {
    private val rnd = java.util.Random(20261121)
    private val alphabet = "aáčďeéěiíňoóřšťuúůyýž ;,.\"'\n\r\t|[]()0123456789-+*/%€$:=@#{}<>\\\u0000﻿😀"
    private fun randStr(max: Int) = buildString { repeat(rnd.nextInt(max)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }

    private val nasty: List<String> = listOf(
        "", " ", "\n", "\r\n\r\n", "﻿", "\u0000\u0000", "😀😀😀", "\"", "\"\"\"", "\";\";\"", ";;;;;", ",,,,,", "a\"b;c\"d",
        "Datum;Částka;Popis\n01.03.2026;-1 234,50;Nákup\n02.03.2026;\"1 000,00\";\"Výplata; březen\"",
        "Datum,Amount\r\n2026-03-01,\"12,5\"\r\n2026-03-02,\"unterminated\n", "x".repeat(200_000), "1;".repeat(20_000), "\"".repeat(5_000),
        "[1] [2] [99] [] [a] [1", "Věta. Věta2! Věta3? [1] např. tzv. 1. ledna", ".".repeat(10_000), "[1]".repeat(3_000),
        "a ".repeat(50_000), "999999999999999999999999 %", "-0", "NaN", "Infinity", "1e309", "0,0,0,0", "1.000.000,00", "1,000,000.00",
        "kolik je " + "(".repeat(500) + "1" + ")".repeat(500), "2^2^2^2^2^2^2^2", "9^9^9", "((((", "1/0/0", "%%%%",
        "podle zprávy ČTK podle zprávy ČTK podle zprávy ČTK ".repeat(500), "https://" + "a".repeat(5_000) + ".cz",
        "‮obrácený text", "Ｆｕｌｌｗｉｄｔｈ １２３", "ŽLUŤOUČKÝ KŮŇ ÚPĚL ĎÁBELSKÉ ÓDY",
    )
    private val inputs = nasty + List(300) { randStr(400) }

    private fun check(name: String, allowed: Set<Class<out Throwable>> = emptySet(), f: (String) -> Unit) {
        val bad = ArrayList<String>()
        for (s in inputs) {
            val t0 = System.nanoTime()
            try { f(s) } catch (e: Throwable) { if (allowed.none { it.isInstance(e) }) bad += "${e.javaClass.simpleName}: ${e.message?.take(80)} ← „${s.take(40).replace("\n", "⏎")}“ (${s.length} zn.)" }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 1500) bad += "POMALÉ ${ms} ms ← „${s.take(40).replace("\n", "⏎")}“ (${s.length} zn.)"
        }
        assertTrue("$name:\n" + bad.take(8).joinToString("\n"), bad.isEmpty())
    }

    @Test fun csv() {
        check("Csv.read") { Csv.read(it) }
        check("Csv.detectDelimiter") { Csv.detectDelimiter(it) }
        check("CzNum.parse") { CzNum.parse(it) }
    }
    @Test fun statement() { check("Statement.detect+process") { s -> val d = Statement.detect(s); d.spec?.let { Statement.process(s, it) } } }
    @Test fun grounding() {
        check("Grounding.sentences") { Grounding.sentences(it) }
        check("Grounding.claims") { Grounding.claims(it) }
    }
    @Test fun calculatorRouterTopic() {
        check("Quick.answer") { Quick.answer(it) }
        check("Topic.bare") { Topic.bare(it) }
        check("Router.route") { Router.route(it, Router.Ctx(false, null, false, false, null)) }
        check("CnbRates.query") { CnbRates.query(it) }
        check("Econ") { Econ.parseUnit(it); Econ.parseTariff(it); Econ.simpleProduct(it) }
        check("LongText") { LongText.subQuestions(it); LongText.chunks(it, 500) }
    }
    @Test fun evidenceRag() {
        check("Evidence.parse") { Evidence.parse(Evidence.Doc("doc", it)) }
        check("Evidence.assess") { Evidence.assess(listOf(Evidence.Doc("a", it), Evidence.Doc("b", it.reversed()))) }
        check("Rag") { s -> Rag.build(mapOf("d" to s)).query(s.take(100)) }
        check("Diff") { s -> Diff.lines(s.take(20_000), s.take(20_000).reversed()) }
    }
    @Test fun wifiNfcApdu() {
        check("WifiTools") { WifiTools.qrPayload(it, it); WifiTools.checkPassword(it, it.take(10)) }
        check("WifiScan.security") { WifiScan.security(it) }
        check("CardApdu.decode", setOf(IllegalArgumentException::class.java)) { CardApdu.decode(it.toByteArray()) }
        check("CardApdu.process") { CardApdu.process(it.toByteArray(), it.toByteArray()) }
        check("NdefParser.parse", setOf(NdefParser.NdefException::class.java)) { NdefParser.parse(it.toByteArray()) }
        check("NdefParser.uriPayload") { NdefParser.uriPayload(it) }
    }
    @Test fun jsonAndLogTools() {
        check("JsonTool.parse") { JsonTool.parse(it) }
        check("LogTool.search") { s -> LogTool.search(s, "chyba", false, 2, null); LogTool.search(s, "(a+)+$", true, 1, null) }
    }
}
