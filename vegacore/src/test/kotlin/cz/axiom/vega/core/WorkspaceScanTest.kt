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
