package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N7: struktura CSV (CRLF, CR z Excelu na Macu, BOM, ; a nový řádek v uvozovkách, patička) a kódování (cp1250, UTF-16 LE/BE s i bez BOM). */
class CsvRobustnessTest { @Test fun structureAndEncodings() {
    val base = "Datum;Částka;Popis\n02.11.2026;-100,00;A\n03.11.2026;200,00;B\n"
    val cases = linkedMapOf(
        "crlf" to base.replace("\n", "\r\n"),
        "bom" to "﻿" + base,
        "quoted;" to "Datum;Částka;Popis\n02.11.2026;-100,00;\"Nákup; Albert\"\n03.11.2026;200,00;B\n",
        "newline-in-quotes" to "Datum;Částka;Popis\n02.11.2026;-100,00;\"první řádek\ndruhý řádek\"\n03.11.2026;200,00;B\n",
        "blank-lines" to "Datum;Částka;Popis\n\n02.11.2026;-100,00;A\n\n\n03.11.2026;200,00;B\n\n",
        "trailing;" to "Datum;Částka;Popis;\n02.11.2026;-100,00;A;\n03.11.2026;200,00;B;\n",
        "escaped-quote" to "Datum;Částka;Popis\n02.11.2026;-100,00;\"Obchod \"\"U Lva\"\"\"\n03.11.2026;200,00;B\n",
        "tab" to base.replace(';', '\t'),
        "cr-only" to base.replace("\n", "\r"),
        "footer" to base + "Celkem;100,00;\nKonečný zůstatek;1 100,00;\n")
    cases.forEach { (k, txt) -> val d = Statement.detect(txt); val r = d.spec?.let { runCatching { Statement.process(txt, it) }.getOrElse { e -> throw AssertionError("$k: $e") } }
        org.junit.Assert.assertTrue("$k: inc=${r?.income} exp=${r?.expense} ok=${r?.ok} failed=${r?.failed} desc=${r?.table?.rows?.map { it[3] }}", r != null && r.income == 200.0 && r.expense == -100.0 && r.failed.isEmpty() && r.ok == 2 && r.table.rows.all { it[3].isNotBlank() }) }
    // kódování: Windows-1250 a UTF-16 přes Encoding.decode
    val w1250 = base.toByteArray(charset("windows-1250")); val u16 = ("﻿" + base).toByteArray(Charsets.UTF_16LE)
    listOf("cp1250" to w1250, "utf16le" to u16, "utf16le-nobom" to base.toByteArray(Charsets.UTF_16LE), "utf16be" to ("\uFEFF" + base).toByteArray(Charsets.UTF_16BE), "utf16be-nobom" to base.toByteArray(Charsets.UTF_16BE)).forEach { (k, b) -> val t = Encoding.decode(b); org.junit.Assert.assertEquals(k, base, t.removePrefix("\uFEFF")) }
} }

/** Den 24. 11., kolo 20: export do Excelu – popis platby z cizího výpisu nesmí být vzorec (=HYPERLINK, +, @, -cmd); čísla zůstanou čísly. */
class CsvFormulaInjectionTest { @Test fun formulasBecomeText() {
    val t = Value.Table(listOf("Datum", "Částka", "Popis"), listOf(
        listOf("2026-11-02", "-1250,50", "=HYPERLINK(\"http://zly.example\";\"Klikni\")"),
        listOf("2026-11-03", "-12 500,00", "+420 777 123 456"),
        listOf("2026-11-04", "30000,00", "@SUM(A1:A9)"),
        listOf("2026-11-05", "-5,00", "-2+3+cmd|' /C calc'!A0"),
        listOf("2026-11-06", "-7,00", "Platba = OK")))
    val csv = Csv.write(t, ';', excel = true)
    org.junit.Assert.assertTrue(csv, csv.contains("\"'=HYPERLINK(\"\"http://zly.example\"\";\"\"Klikni\"\")\"") && csv.contains(";'+420 777 123 456") && csv.contains(";'@SUM(A1:A9)") && csv.contains("'-2+3+cmd"))
    // čísla a obyčejný text beze změny
    org.junit.Assert.assertTrue(csv, csv.contains(";-1250,50;") && csv.contains(";-12 500,00;") && csv.contains(";-5,00;") && csv.contains(";Platba = OK"))
    // bez excel=true (vnitřní text) se nic nemění
    org.junit.Assert.assertTrue(Csv.write(t, ';').contains("\"=HYPERLINK"))
} }

/** Den 25. 11., kolo 7: české regexy s (?i) bez UNICODE_CASE nerozlišily „KČ/Kč“, „VYSVĚTLI/vysvětli“ – text VELKÝMI písmeny (PDF výpisy, CAPS LOCK). */
class UnicodeCaseTest { @Test fun czechUppercase() {
    org.junit.Assert.assertEquals(1250.0, CzNum.parse("1 250,00 KČ", true)!!, 0.001)
    org.junit.Assert.assertEquals(-312.4, CzNum.parse("-312,40 Kč", true)!!, 0.001)
    val subs = cz.axiom.vega.calc.LongText.subQuestions("Smlouva o nájmu.\nVYSVĚTLI výpovědní lhůtu.\nPOPIŠ povinnosti nájemce.")
    org.junit.Assert.assertTrue("$subs", subs.size >= 2)
} }
