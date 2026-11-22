package cz.axiom.vega.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan S30 (22. 11.): náhodná a rozbitá data do parserů jádra (pevné semínko = opakovatelné). Povolené jsou jen srozumitelné
 * české chyby (ToolError, NdefException); jiná výjimka = na telefonu „Tohle se teď nepovedlo“ a laik neví proč.
 */
class FuzzCoreTest {
    private val rnd = java.util.Random(20261122)
    private val alphabet = "0123456789;,.\t\"'|-−+() \nKčCZK€EURDatumČástkaPopis1.3.2026ěščřžýáíé\r ﻿/:#".toCharArray()
    private fun junk(n: Int) = String(CharArray(n) { alphabet[rnd.nextInt(alphabet.size)] })
    private fun bytes(n: Int) = ByteArray(n) { rnd.nextInt(256).toByte() }

    @Test fun parsersNeverCrashWithForeignExceptions() {
        val bad = ArrayList<String>()
        fun chk(what: String, input: Any, f: () -> Unit) {
            try { f() } catch (e: ToolError) { } catch (e: NdefParser.NdefException) { } catch (e: Throwable) {
                if (bad.size < 12) bad += "$what: ${e.javaClass.simpleName}: ${e.message?.take(80)} ← ${input.toString().take(60).replace("\n", "⏎")}"
            }
        }
        repeat(1500) { i ->
            val s = junk(1 + rnd.nextInt(if (i % 10 == 0) 600 else 80))
            chk("Csv.read", s) { Csv.read(s) }
            chk("CzNum.parse", s) { CzNum.parse(s); CzNum.parse(s, false) }
            chk("CzNum.date", s) { CzNum.date(s) }
            chk("Statement", s) { val d = Statement.detect(s); d.spec?.let { Statement.process(s, it); Statement.reconcile(s, it) } }
            val withHeader = "Datum;Částka;Popis\n$s"
            chk("Statement+hlavička", withHeader) { val d = Statement.detect(withHeader); d.spec?.let { Statement.process(withHeader, it); Statement.byMonth(Statement.process(withHeader, it).table) } }
            chk("Diff", s) { Diff.lines(s, junk(rnd.nextInt(60))); Diff.parseUnified(s) }
            chk("Grounding", s) { Grounding.sentences(s) }
            chk("Rag", s) { Rag.queryWords(s) }
            chk("Quick", s) { cz.axiom.vega.calc.Quick.answer(s) }
            chk("Router", s) { Router.route(s, Router.Ctx(i % 2 == 0, if (i % 3 == 0) "x.csv" else null, i % 5 == 0, i % 7 == 0, null)) }
            val b = bytes(rnd.nextInt(80))
            chk("Ndef", b.toList()) { NdefParser.parse(b) }
            chk("Encoding", b.toList()) { Encoding.decode(b) }
            chk("CardApdu", b.toList()) { CardApdu.process(b, bytes(rnd.nextInt(20))); CardApdu.decode(b); CardApdu.splitSw(b) }
            chk("Wifi", s) { WifiScan.security(s); WifiTools.checkPassword(s, s.take(5)) }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
