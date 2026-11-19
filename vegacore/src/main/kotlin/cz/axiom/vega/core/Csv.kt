package cz.axiom.vega.core

/**
 * CSV podle RFC 4180 a českých zvyklostí: oddělovač „;“ (Excel v češtině), desetinná čárka, mezery v tisících,
 * uvozovky s "" uvnitř, víceřádková pole. Diagnostika místo tichého zahození řádku.
 */
object Csv {
    data class Issue(val line: Int, val message: String, val raw: String)

    data class Parsed(val delimiter: Char, val headers: List<String>, val rows: List<List<String>>, val issues: List<Issue>, val rowLines: List<Int>)

    /** Oddělovač podle konzistence počtu polí v prvních řádcích (ne podle prostého počtu znaků). */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ';'
        return listOf(';', ',', '\t', '|').maxBy { d ->
            val counts = sample.map { splitLine(it, d).size }
            val mode = counts.groupingBy { it }.eachCount().maxBy { it.value }
            if (mode.key <= 1) -1.0 else mode.value.toDouble() / counts.size * 100 + mode.key * 0.01
        }
    }

    private fun splitLine(line: String, d: Char): List<String> = parse(line, d).let { it.firstOrNull() ?: emptyList() }

    /** Surové záznamy (víceřádková pole v uvozovkách drží pohromadě) + číslo řádku začátku každého záznamu. */
    private fun records(text: String, d: Char, issues: MutableList<Issue>? = null): List<Pair<Int, List<String>>> {
        val out = ArrayList<Pair<Int, List<String>>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQ = false
        var line = 1
        var start = 1
        var i = 0
        var quotedField = false
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQ = false
                } else { if (c == '\n') line++; field.append(c) }
            } else when {
                c == '"' && field.isEmpty() && !quotedField -> { inQ = true; quotedField = true }
                c == '"' -> { field.append(c); issues?.add(Issue(line, "uvozovka uprostřed pole (neuzavřené nebo chybně escapované uvozovky)", text.lineSequence().drop(line - 1).firstOrNull() ?: "")) }
                c == d -> { row.add(field.toString()); field = StringBuilder(); quotedField = false }
                c == '\r' -> {}
                c == '\n' -> {
                    row.add(field.toString()); field = StringBuilder(); quotedField = false
                    if (!(row.size == 1 && row[0].isEmpty())) out += start to row
                    row = ArrayList(); line++; start = line
                }
                else -> field.append(c)
            }
            i++
        }
        if (inQ) issues?.add(Issue(start, "neuzavřené uvozovky až do konce souboru", text.lineSequence().drop(start - 1).firstOrNull() ?: ""))
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); if (!(row.size == 1 && row[0].isEmpty())) out += start to row }
        return out
    }

    fun parse(text: String, d: Char): List<List<String>> = records(text, d).map { it.second }

    fun read(text: String, delimiter: Char? = null, header: Boolean = true): Parsed {
        val clean = text.removePrefix("﻿")
        val d = delimiter ?: detectDelimiter(clean)
        val issues = ArrayList<Issue>()
        val recs = records(clean, d, issues)
        if (recs.isEmpty()) return Parsed(d, emptyList(), emptyList(), issues, emptyList())
        val headers = if (header) recs[0].second.map { it.trim() } else List(recs[0].second.size) { "Sloupec ${it + 1}" }
        val body = if (header) recs.drop(1) else recs
        val rawLines = clean.lines()
        body.forEach { (ln, r) -> if (r.size != headers.size) issues += Issue(ln, "${r.size} polí místo ${headers.size}", rawLines.getOrElse(ln - 1) { "" }) }
        return Parsed(d, headers, body.map { it.second }, issues.distinctBy { it.line to it.message }, body.map { it.first })
    }

    fun write(t: Value.Table, d: Char = ';'): String {
        fun q(s: String) = if (s.any { it == d || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return (listOf(t.headers) + t.rows).joinToString("\r\n") { r -> r.joinToString(d.toString()) { q(it) } } + "\r\n"
    }
}

/** Čísla a data v českém zápisu. */
object CzNum {
    private val currency = Regex("(?i)\\s*(kč|czk|eur|€|usd|\\$)\\s*")

    /**
     * „1 234,56“, „-1.234,56“, „1234.5“, „1 234,56 Kč“, „(12,00)“ → Double. Pro dvojznačné „1.234“ rozhodne
     * [decimalComma]: true = česky (tečka = tisíce), false = tečka je desetinná.
     */
    fun parse(raw: String, decimalComma: Boolean = true): Double? {
        var s = raw.trim().replace(currency, "").replace(' ', ' ').replace(' ', ' ').replace('−', '-').trim()
        if (s.isEmpty()) return null
        var neg = false
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length - 1) }
        if (s.endsWith("-")) { neg = true; s = s.dropLast(1) }
        if (s.startsWith("+")) s = s.drop(1)
        if (s.startsWith("-")) { neg = !neg; s = s.drop(1) }
        s = s.replace(" ", "").replace("'", "")
        if (!s.matches(Regex("[0-9.,]+")) || !s.any(Char::isDigit)) return null
        val lastComma = s.lastIndexOf(','); val lastDot = s.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            // jediná čárka: v souboru s desetinnou TEČKOU je „1,234“ oddělovač tisíců
            lastComma >= 0 -> if (s.count { it == ',' } > 1 || (!decimalComma && s.length - lastComma - 1 == 3)) s.replace(",", "") else s.replace(',', '.')
            lastDot >= 0 -> if (s.count { it == '.' } > 1 || (decimalComma && s.length - lastDot - 1 == 3)) s.replace(".", "") else s
            else -> s
        }
        val v = normalized.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e15 } ?: return null
        return if (neg) -v else v
    }

    /** Používá sloupec desetinnou čárku? (vzorky typu „123,45“) */
    fun usesDecimalComma(samples: List<String>): Boolean {
        val c = samples.count { Regex("\\d,\\d{1,2}(\\s|$|[^\\d])").containsMatchIn(it.trim() + " ") }
        val d = samples.count { Regex("\\d\\.\\d{1,2}(\\s|$|[^\\d])").containsMatchIn(it.trim() + " ") }
        return c >= d
    }

    fun format(v: Double, decimals: Int = 2): String {
        val s = String.format(java.util.Locale.ROOT, "%.${decimals}f", kotlin.math.abs(v))
        val (int, frac) = s.split('.').let { it[0] to it.getOrNull(1) }
        val grouped = int.reversed().chunked(3).joinToString(" ").reversed()
        return (if (v < 0) "−" else "") + grouped + (frac?.let { ",$it" } ?: "")
    }

    private val dateRx = listOf(
        Regex("^(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})") to "dmy",
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})") to "ymd",
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})") to "dmy",
    )

    /** Datum → ISO (yyyy-mm-dd), nebo null. */
    fun date(raw: String): String? {
        val s = raw.trim()
        for ((rx, kind) in dateRx) {
            val m = rx.find(s) ?: continue
            val (a, b, c) = m.destructured
            val (y, mo, d) = if (kind == "ymd") Triple(a.toInt(), b.toInt(), c.toInt()) else Triple(c.toInt(), b.toInt(), a.toInt())
            if (mo !in 1..12 || d !in 1..31) return null
            return runCatching { java.time.LocalDate.of(y, mo, d).toString() }.getOrNull()
        }
        return null
    }
}
