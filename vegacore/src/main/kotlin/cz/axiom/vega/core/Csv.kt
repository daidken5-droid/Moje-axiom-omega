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
        // řádek pro hlášení: z předpočítaných začátků řádků (ne opakované procházení textu = kvadratická složitost) a max. 100 hlášení
        val starts by lazy { val l = ArrayList<Int>(); l += 0; text.forEachIndexed { j, ch -> if (ch == '\n') l += j + 1 }; l }
        fun lineAt(n: Int): String { if ((issues?.size ?: 0) >= 100) return ""; val st = starts.getOrNull(n - 1) ?: return ""; val e = text.indexOf('\n', st).let { if (it < 0) text.length else it }; return text.substring(st, e).trimEnd('\r') }
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQ = false
                } else { if (c == '\n') line++; field.append(c) }
            } else when {
                c == '"' && field.isEmpty() && !quotedField -> { inQ = true; quotedField = true }
                c == '"' -> { field.append(c); if ((issues?.size ?: 0) < 100) issues?.add(Issue(line, "uvozovka uprostřed pole (neuzavřené nebo chybně escapované uvozovky)", lineAt(line))) }
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
        if (inQ) issues?.add(Issue(start, "neuzavřené uvozovky až do konce souboru", lineAt(start)))
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

    /**
     * Buňka pro Excel/Tabulky: text začínající „= + @“ (nebo „-“, když to není číslo) by se spustil jako VZOREC
     * (=HYPERLINK, =WEBSERVICE… z popisu platby v cizím výpisu) → předsadit apostrof, Excel ho zobrazí jako text.
     */
    fun excelSafe(s: String): String = if (s.isNotEmpty() && (s[0] in "=+@\t\r" || (s[0] == '-' && !RX_NUM.matches(s)))) "'$s" else s
    private val RX_NUM = Regex("^-[\\d \u00a0.,]+$")

    fun write(t: Value.Table, d: Char = ';', excel: Boolean = false): String {
        fun q(s0: String): String { val s = if (excel) excelSafe(s0) else s0
            return if (s.any { it == d || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s }
        return (listOf(t.headers) + t.rows).joinToString("\r\n") { r -> r.joinToString(d.toString()) { q(it) } } + "\r\n"
    }
}

/** Čísla a data v českém zápisu. */
object CzNum {
    private val NUM_CHARS = Regex("[0-9.,]+")
    private val currency = Regex("(?iu)\\s*(kč|czk|eur|€|usd|\\$)\\s*")

    /**
     * „1 234,56“, „-1.234,56“, „1234.5“, „1 234,56 Kč“, „(12,00)“ → Double. Pro dvojznačné „1.234“ rozhodne
     * [decimalComma]: true = česky (tečka = tisíce), false = tečka je desetinná.
     */
    private val RX_DRCR = Regex("(?i)^(.*\\d)\\s*(dr|cr)\\.?$")
    private val RX_TAG = Regex("[,.]\\s*[-–]$")
    fun parse(raw: String, decimalComma: Boolean = true): Double? {
        var s = raw.trim().replace(currency, "").replace(' ', ' ').replace(' ', ' ').replace('−', '-').replace(' ', ' ').let { if (it.startsWith('–')) "-" + it.drop(1) else it }.trim()
        if (s.isEmpty()) return null
        var neg = false
        // „1 234,56 DR“ / „… CR“ (debet / kredit za částkou, anglické exporty): DR = výdaj
        RX_DRCR.find(s)?.let { m -> s = m.groupValues[1].trim(); if (m.groupValues[2].lowercase() == "dr") neg = true }
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length - 1) }
        // „1.000,-“ / „100,–“ = celé koruny (česká cenovka), NE záporné číslo
        var dc = decimalComma
        if (RX_TAG.containsMatchIn(s)) { s = s.replace(RX_TAG, ""); dc = true } // česká cenovka → tečky jsou tisíce
        else if (s.endsWith("-")) { neg = true; s = s.dropLast(1) }
        if (s.startsWith("+")) s = s.drop(1)
        if (s.startsWith("-")) { neg = !neg; s = s.drop(1) }
        s = s.replace(" ", "").replace("'", "")
        if (!s.matches(NUM_CHARS) || !s.any(Char::isDigit)) return null
        val lastComma = s.lastIndexOf(','); val lastDot = s.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            // jediná čárka: v souboru s desetinnou TEČKOU je „1,234“ oddělovač tisíců
            lastComma >= 0 -> if (s.count { it == ',' } > 1 || (!dc && s.length - lastComma - 1 == 3)) s.replace(",", "") else s.replace(',', '.')
            lastDot >= 0 -> if (s.count { it == '.' } > 1 || (dc && s.length - lastDot - 1 == 3)) s.replace(".", "") else s
            else -> s
        }
        val v = normalized.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e15 } ?: return null
        return if (neg) -v else v
    }

    /** Používá sloupec desetinnou čárku? (vzorky typu „123,45“) */
    fun usesDecimalComma(samples: List<String>): Boolean {
        val c = samples.count { Regex("\\d,\\d{1,2}(\\s|$|[^\\d])").containsMatchIn(it.trim() + " ") }
        val d = samples.count { Regex("\\d\\.\\d{1,2}(\\s|$|[^\\d])").containsMatchIn(it.trim() + " ") }
        // tvar celého čísla: „1,500“ / „12,300.50“ = americké tisíce; „1.500“ / „1.234,50“ = české tisíce
        val bare = samples.map { it.replace(currency, "").replace(Regex("[\\s\u00a0\u202f'()+−-]"), "") }
        val usThousands = bare.count { Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$").matches(it) }
        val czThousands = bare.count { Regex("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$").matches(it) }
        val comma = c + czThousands; val dot = d + usThousands
        return if (comma == dot) true else comma > dot // bez důkazů česky
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
        // „1.2.26“ – dvoumístný rok (některé banky a ruční tabulky) = 20yy
        Regex("^(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{2})(?![\\d.])") to "dmy2",
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})") to "dmy",
        Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2})") to "ymd",
        Regex("^(\\d{1,2})-(\\d{1,2})-(\\d{4})") to "dmy",
    )
    private val MONTHS = listOf("ledn", "unor", "brez", "dub", "kvet", "cervna", "cervence", "srp", "zar", "rij", "listopad", "prosin")

    /** Datum → ISO (yyyy-mm-dd), nebo null. */
    private val EN = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private val CZ3 = mapOf("led" to 1, "uno" to 2, "bre" to 3, "dub" to 4, "kve" to 5, "cvn" to 6, "cvc" to 7, "srp" to 8, "zar" to 9, "rij" to 10, "lis" to 11, "pro" to 12)
    // předkompilované (dřív se 4 regexy kompilovaly při KAŽDÉM volání – detect výpisu s 20 000 buňkami trval v CI 1,6 s)
    private val RX_DMON = Regex("^(\\d{1,2})[-\\s.]+([A-Za-z]{3,9})\\.?[-\\s]+(\\d{4})")
    private val RX_MOND = Regex("^([A-Za-z]{3,9})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})")
    private val RX_CZ3 = Regex("(?u)^(\\d{1,2})\\.?\\s+([a-zá-ž]{3})\\.?\\s+(\\d{4})$", RegexOption.IGNORE_CASE)
    private val RX_CZMONTH = Regex("^(\\d{1,2})\\.?\\s+([a-zA-Zá-žÁ-Ž]+)\\s+(\\d{4})")
    fun date(raw: String): String? {
        val r0 = raw.trim()
        if (r0.length < 6 || r0.length > 40 || r0.none { it.isDigit() }) return null  // datum má aspoň „1.1.26“ – rychlé odmítnutí
        // „02-Nov-2026“, „Nov 2, 2026“, „2 Nov 2026“, „2 lis 2026“ → d.m.rrrr
        // (kompaktní „20250115“ záměrně NE – osmimístné číslo bývá variabilní symbol / číslo dokladu, StatementBanksTest)
        val s0 = RX_DMON.find(r0)?.let { m -> EN[m.groupValues[2].lowercase().take(3)]?.let { "${m.groupValues[1]}.$it.${m.groupValues[3]}" } }
            ?: RX_MOND.find(r0)?.let { m -> EN[m.groupValues[1].lowercase().take(3)]?.let { "${m.groupValues[2]}.$it.${m.groupValues[3]}" } }
            ?: RX_CZ3.find(r0)?.let { m -> CZ3[cz.axiom.vega.base.Text.fold(m.groupValues[2]).lowercase()]?.let { "${m.groupValues[1]}.$it.${m.groupValues[3]}" } }
            ?: r0
        // „1. března 2026“ → „1.3.2026“ (český název měsíce; „června“ × „července“ se rozlišují celým kmenem)
        val s = RX_CZMONTH.find(s0)?.let { m ->
            val w = cz.axiom.vega.base.Text.fold(m.groupValues[2]).lowercase()
            val i = MONTHS.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 } ?: when { w == "cerven" -> 5; w.startsWith("cervenec") -> 6; else -> -1 }
            if (i >= 0) "${m.groupValues[1]}.${i + 1}.${m.groupValues[3]}" else null } ?: s0
        for ((rx, kind) in dateRx) {
            val m = rx.find(s) ?: continue
            val (a, b, c) = m.destructured
            val (y, mo, d) = when (kind) { "ymd" -> Triple(a.toInt(), b.toInt(), c.toInt()); "dmy2" -> Triple(c.toInt().let { yy -> if (2000 + yy > java.time.LocalDate.now().year + 10) 1900 + yy else 2000 + yy }, b.toInt(), a.toInt()); else -> Triple(c.toInt(), b.toInt(), a.toInt()) }
            if (mo !in 1..12 || d !in 1..31) return null
            return runCatching { java.time.LocalDate.of(y, mo, d).toString() }.getOrNull()
        }
        return null
    }
}
