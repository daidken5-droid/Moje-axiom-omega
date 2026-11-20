package cz.axiom.vega.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Red-team kolo 4: tajné soubory, obejití přes mezery, kódování, CRLF, paměť diffu, CSV, ZIP. */
class WorkspaceScanTest {
    private fun ws(files: Map<String, ByteArray>, name: String = "p") = Workspace.create(Files.createTempDirectory("ws").toFile().resolve("x"), name, files)

    @Test fun secretsByNameAndContentStayOutOfModelAndExport() {
        for (n in listOf("serviceAccountKey.json", "client_secret_123.apps.googleusercontent.com.json", "app-firebase-adminsdk-ab12.json", "token.json", ".env ", " .env", "cfg/ .env"))
            assertTrue(Workspace.isSecret(n), n)
        val w = ws(mapOf("a.js" to "module.exports = 1".toByteArray(), "keys/data.json" to """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\nMIIE"}""".toByteArray(),
            "big.json" to ByteArray(30_000) { 'x'.code.toByte() }))
        assertTrue(w.isSecretFile("keys/data.json")); assertFalse(w.isSecretFile("a.js"))
        val rel = w.relevant("cokoli")
        assertFalse("keys/data.json" in rel, rel.toString()); assertFalse("big.json" in rel, "soubor větší než limit nesmí zabrat celý kontext")
        val zip = String(w.exportZip(), Charsets.ISO_8859_1)
        assertFalse(zip.contains("keys/data.json"))
    }

    @Test fun nonUtf8IsNotPatchedAndCrlfPatches() {
        val cp = "// cena v Kč\nval x = 1\n".toByteArray(charset("windows-1250"))
        val w = ws(mapOf("a.kt" to cp, "b.kt" to "val a = 1\r\nval b = 2\r\n".toByteArray()))
        assertFailsWith<Workspace.Conflict> { w.patch("a.kt", "val x = 1", "val x = 2") }
        assertTrue(w.file("a.kt").readBytes().contentEquals(cp), "soubor zůstal netknutý")
        w.patch("b.kt", "val a = 1\nval b = 2", "val a = 1\nval b = 3")
        assertEquals("val a = 1\r\nval b = 3\r\n", w.file("b.kt").readText())
    }

    @Test fun zipNameCannotEscape() {
        val w = ws(mapOf("a.txt" to "x".toByteArray()), name = "../../x")
        val z = java.util.zip.ZipInputStream(w.exportZip().inputStream()); val e = z.nextEntry!!
        assertFalse(e.name.contains(".."), e.name)
    }

    @Test fun hugeDiffAndQuoteHeavyCsvStayCheap() {
        val a = (1..3000).joinToString("\n") { "a$it" }; val b = (1..3000).joinToString("\n") { "b$it" }
        val t0 = System.currentTimeMillis(); val d = Diff.lines(a, b)
        assertEquals(3000, d.added); assertEquals(3000, d.removed); assertTrue(System.currentTimeMillis() - t0 < 5_000)
        val csv = "a;b\n" + (1..40_000).joinToString("\n") { "monitor 24\" $it;1" }
        val t1 = System.currentTimeMillis(); val r = Csv.read(csv)
        assertTrue(System.currentTimeMillis() - t1 < 5_000, "CSV s uvozovkami uprostřed nesmí být kvadratické"); assertTrue(r.rows.size >= 40_000)
    }
}

class CzNumColumnTest {
    @Test fun decidesDecimalSeparatorFromWholeColumn() {
        // americké tisíce bez desetinných míst: dřív „1,500“ = 1,5
        val us = listOf("1,500", "2,300", "12,000", "950")
        assertFalse(CzNum.usesDecimalComma(us)); assertEquals(1500.0, CzNum.parse("1,500", CzNum.usesDecimalComma(us)))
        assertFalse(CzNum.usesDecimalComma(listOf("12,300.50", "1,200.00")))
        // české
        assertTrue(CzNum.usesDecimalComma(listOf("1 234,50", "-12,00", "300")))
        assertTrue(CzNum.usesDecimalComma(listOf("1.500", "2.300,00")))
        assertTrue(CzNum.usesDecimalComma(listOf("100", "200")), "bez důkazů česky")
        assertEquals(1.5, CzNum.parse("1,5", CzNum.usesDecimalComma(listOf("1,5", "2,25"))))
    }
}

class CodeAgentParseTest {
    @Test fun editMarkersInsideNewFileAreContentNotEdits() {
        val reply = "Přidávám návod.\n### NOVÝ: docs/format.md\n```md\nPříklad:\n### UPRAVIT: src/a.js\n<<<<<<< HLEDAT\nx\n=======\ny\n>>>>>>> NAHRADIT\n```\n" +
            "### UPRAVIT: src/b.js\n<<<<<<< HLEDAT\nconst a = 1\n=======\nconst a = 2\n>>>>>>> NAHRADIT\n"
        val p = CodeAgent.parse(reply)
        assertEquals(listOf("docs/format.md", "src/b.js"), p.edits.map { it.path })
        assertTrue((p.edits[0] as CodeAgent.Edit.Create).content.contains("### UPRAVIT: src/a.js"))
    }
}

class Round5Test {
    @Test fun tariffOverYearAndMonthlyParsing() {
        val t = Econ.parseTariff("Roční 1200 Kč / měsíční 120 Kč, budu ho používat 24 měsíců")!!
        assertEquals(120.0, t.monthly, "měsíční cena nesmí převzít roční číslo"); assertEquals(1200.0, t.yearly)
        val a = Econ.tariffAnswer(t)
        assertTrue(a.contains("o 480 Kč") || a.contains("o 480,00 Kč"), a) // 24 × 120 = 2 880 vs. 2 × 1 200 = 2 400
        assertEquals(120.0, Econ.parseTariff("roční 1 200 Kč, nebo 120 Kč/měsíc")!!.monthly)
    }

    @Test fun evidenceUrlCopiesAndCzechAcronyms() {
        assertEquals(Evidence.normUrl("https://x.cz/a"), Evidence.normUrl("https://www.x.cz/a/."))
        assertEquals(Evidence.normUrl("https://x.cz/a#top"), Evidence.normUrl("https://x.cz/a,"))
        assertTrue(Evidence.normUrl("https://youtube.com/watch?v=AAA") != Evidence.normUrl("https://youtube.com/watch?v=BBB"))
        val o = Evidence.parse(Evidence.Doc("d", "Podle zprávy ČTK z pondělí se to stalo.")).origins
        assertTrue(o.any { it.contains("čtk") || it.contains("ctk") }, o.toString())
    }

    @Test fun secretsInMemoryAndForgetScope() {
        for (s in listOf("heslo k wifi je Kocour2024", "my password is hunter22", "účet 123456789/0100", "CZ65 0800 0000 1920 0014 5399")) assertTrue(Mem.looksSecret(s), s)
        val m = Memory(MemKv())
        m.remember("v projektu používej středník", "project:a", Mem.Kind.PROJECT_FACT)
        m.remember("piš stručně", "global", Mem.Kind.PREFERENCE)
        assertTrue(m.forget("středník", null).isEmpty(), "bez otevřeného projektu se nemaže v projektech")
        assertEquals(1, m.forget("středník", "project:a").size)
    }
}

class Round5bTest {
    @Test fun econRoundingAndSentenceSplitterSpeed() {
        val r = Econ.unitAnswer(Econ.Unit(0.3, 0.1, 1000.0, 5000.0))!!
        assertTrue(r.text.contains("nulový zisk") && r.text.contains("≥"), r.text)
        assertFalse(r.text.contains("první celý kus"), "přesný bod zvratu bez falešného zbytku: ${r.text}")
        val long = "a.".repeat(40_000)
        val t0 = System.currentTimeMillis(); Grounding.sentences(long)
        assertTrue(System.currentTimeMillis() - t0 < 3_000, "dělič vět nesmí být kvadratický")
        assertEquals(listOf("Platí to [1].", "Další věta [2][3]."), Grounding.sentences("Platí to [1]. Další věta [2][3]."))
    }
}

class Round6Test {
    private val none = Router.Ctx(false, null, false, false, null)
    private fun area(s: String, c: Router.Ctx = none) = SelfTune.area(Router.route(s, c))

    @Test fun routerDoesNotHijackOrdinarySentences() {
        assertEquals("záměr:Talk", area("porovnej ceny elektřiny u ČEZ a E.ON"))
        assertFalse(area("vymysli hashtag na Instagram").contains("hash"))
        assertFalse(area("cenový štítek na zboží").contains("Nfc"))
        assertFalse(area("Zpět k tomu receptu – kolik tam bylo mouky?").contains("Undo"))
        assertEquals("nástroj:hash", area("spočítej sha256 tohohle souboru"))
        assertEquals("záměr:Nfc", area("přečti NFC štítek"))
        assertEquals("záměr:Undo", area("vrať to"))
    }

    @Test fun procedureFindMatchesWholeWords() {
        val kv = MemKv(); val st = ProcedureStore(kv)
        st.save("Faktury", listOf(Step("csv.inspect")), "d", Kind.TEXT, "", Kind.TABLE)
        st.save("Zpracovat výpis", listOf(Step("csv.inspect")), "d", Kind.TEXT, "", Kind.TABLE)
        assertEquals(null, st.find("To je fakt dobrý nápad"))
        assertEquals(null, st.find("Nezpracovávej ten výpis, jen se zeptám"))
        assertEquals("Zpracovat výpis", st.find("zpracuj výpis")?.name)
        assertEquals("Faktury", st.find("faktury")?.name)
        // návrat na předchozí verzi
        st.save("Faktury", listOf(Step("json.inspect")), "d2", Kind.TEXT, "", Kind.TABLE)
        val back = st.revert(st.find("faktury")!!.id)!!
        assertEquals("csv.inspect", back.steps.first().tool); assertEquals(3, back.version)
    }

    @Test fun statementCurrenciesAndChangedHeader() {
        val csv = "Datum;Částka;Měna;Popis\n1.1.2026;-100,00;EUR;Hotel\n2.1.2026;-2000,00;CZK;Nákup\n3.1.2026;-500,00;CZK;Benzín\n"
        val spec = Statement.detect(csv).spec!!
        val r = Statement.process(csv, spec)
        assertEquals(-2500.0, r.expense, 0.001) // jen hlavní měna CZK, EUR se nepřičte
        assertTrue(r.table.notes.any { it.contains("víc měn") && it.contains("EUR") }, r.table.notes.toString())
        val m = Statement.byMonth(r.table); assertTrue(m.headers.contains("Měna") && m.rows.size == 2, m.toString())
        // uložený postup na jiné hlavičce: částka zmizela, zůstatek je na jejím místě → chyba, ne tichý součet zůstatků
        val saved = Statement.detect("Datum;Částka;Zůstatek;Popis\n1.1.2026;-10,00;990,00;A\n2.1.2026;-20,00;970,00;B\n3.1.2026;5,00;975,00;C\n").spec!!
        val changed = "Datum;Zůstatek;Částka v CZK;Popis\n1.1.2026;990,00;-10,00;A\n2.1.2026;970,00;-20,00;B\n"
        assertTrue(runCatching { Statement.process(changed, saved) }.exceptionOrNull() is ToolError)
        // KB: měna účtu vs. originální měna
        val kb = "Datum;Částka;Originální měna;Měna účtu;Popis\n1.1.2026;-250,00;EUR;CZK;Platba\n2.1.2026;-100,00;CZK;CZK;Kafe\n3.1.2026;50,00;CZK;CZK;Vratka\n"
        assertTrue(Statement.process(kb, Statement.detect(kb).spec!!).table.rows.all { it[2] == "CZK" })
    }

    @Test fun crashRegexDoesNotOverflow() {
        val long = "x." .repeat(8_000) + "Foo"
        LogTool.crash(long + "\njava.lang.IllegalStateException: boom\n\tat a.b.C.d(C.kt:1)")
    }
}
