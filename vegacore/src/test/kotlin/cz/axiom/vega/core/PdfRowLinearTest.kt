package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Řádek PDF výpisu: stejný výsledek jako dřívější regex, ale bez kubického zpětného hledání u dlouhých mezer. */
class PdfRowLinearTest {
    private val old = Regex("^\\s*(\\d{1,2}\\.\\s?\\d{1,2}\\.(?:\\s?(?:\\d{4}|\\d{2}))?)\\s+(.*?)\\s+(${Statement.PDF_NUM})(?:\\s+(${Statement.PDF_NUM}))?\\s*$")
    private fun oldRow(l: String) = old.find(l)?.let { listOf(it.groupValues[1], it.groupValues[2].replace(Regex("\\s+"), " ").trim(), it.groupValues[3], it.groupValues[4]) }
    private fun newRow(l: String) = Statement.pdfRow(l)?.let { listOf(it.date, it.desc.replace(Regex("\\s+"), " ").trim(), it.a, it.b) }

    @Test fun sameAsOldRegexOnFuzzedLines() {
        val r = java.util.Random(25_11)
        val parts = listOf("01.11.2026", "2.11.", "03. 11. 26", "Lidl", "CEZ zaloha", "Splátka 1", "Mzda", "1 234,00", "-312,40", "+30 000,00", "(1 250,00)", "8 750,00 CZK",
            "5,00 Kč", "120,00 CR", "45,50 DR", "12", "2026", "x", "1.234,00", "234,00", "-", "€", "—", "Strana 1/1", "1\u00a0234,00", "\u00a0", "01.11.2026\t", "15.1.", "7. 3. 2027", "9 999 999,99", "0,01")
        val bad = ArrayList<String>()
        repeat(60_000) {
            val line = (0 until 1 + r.nextInt(6)).joinToString("") { (when (r.nextInt(6)) { 0 -> "   "; 1 -> "\t"; 2 -> "  \t "; else -> " " }) + parts[r.nextInt(parts.size)] }.let { if (r.nextBoolean()) it.trimStart() else it }.let { if (r.nextInt(5) == 0) it + "  " else it }
            if (oldRow(line) != newRow(line) && bad.size < 10) bad += "„$line“: dřív ${oldRow(line)}, teď ${newRow(line)}"
        }
        assertEquals(emptyList(), bad)
    }

    @Test fun longWhitespaceLineIsFast() {
        val lines = listOf("01.11.2026 a" + " ".repeat(150 * 1024) + "x", "01.11.2026 " + "1 234,00 x ".repeat(15_000), "01.11.2026" + "  ".repeat(60_000) + "5,00")
        val slow = lines.mapIndexedNotNull { i, l -> val t = System.nanoTime(); Statement.fromPdfText(l + "\n" + l); ((System.nanoTime() - t) / 1_000_000).takeIf { it > 2_000 }?.let { "řádek $i: $it ms" } }
        assertEquals(emptyList(), slow)
    }
}
