package cz.axiom.vega.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ZPRACOVÁNÍ VÝPISU (ukázka pro první spuštění): zjistí formát exportu z banky/účetnictví, upozorní na české
 * desetinné čárky, sestaví parser (ParserSpec), ověří ho na vzorku a vrátí jednotnou tabulku + souhrn.
 * Spec se dá uložit do postupu → příště stačí nový soubor a „Zpracovat výpis“.
 */
object Statement {
    @Serializable
    data class ParserSpec(
        val delimiter: String,
        val decimalComma: Boolean,
        val dateCol: Int,
        val amountCol: Int,
        val descCols: List<Int>,
        val currencyCol: Int = -1,
        /** Některé banky mají zvlášť „Příjem“ a „Výdaj“ – pak amountCol = příjem a debitCol = výdaj. */
        val debitCol: Int = -1,
        val headerHash: String = "",
    ) {
        fun json() = Json.encodeToString(serializer(), this)
        companion object { fun parse(s: String) = Json.decodeFromString(serializer(), s) }
    }

    data class Detection(val spec: ParserSpec?, val headers: List<String>, val warnings: List<String>, val sample: List<List<String>>)

    private val dateNames = listOf("datum", "date", "datum zaúčtování", "datum provedení", "splatnost", "dne", "booking date", "valuta")
    private val amountNames = listOf("částka", "castka", "amount", "objem", "suma", "hodnota", "částka v měně účtu", "obrat")
    private val creditNames = listOf("příjem", "prijem", "kredit", "credit", "připsáno")
    private val debitNames = listOf("výdaj", "vydaj", "debet", "debit", "odepsáno")
    private val descNames = listOf("popis", "zpráva", "zprava", "poznámka", "protistrana", "název protiúčtu", "nazev", "obchodník", "description", "counterparty", "message", "účel", "typ")
    private val currencyNames = listOf("měna", "mena", "currency")

    private fun f(s: String) = cz.axiom.omega.core.util.TextUtil.fold(s).lowercase().trim()
    private fun findCol(headers: List<String>, names: List<String>): Int {
        val h = headers.map { f(it) }
        names.forEach { n -> h.indexOfFirst { it == f(n) }.takeIf { it >= 0 }?.let { return it } }
        names.forEach { n -> h.indexOfFirst { it.contains(f(n)) }.takeIf { it >= 0 }?.let { return it } }
        return -1
    }

    fun detect(text: String): Detection {
        val warnings = ArrayList<String>()
        // banky často přidávají úvodní řádky (číslo účtu, období) – hlavička je první řádek se známými názvy sloupců
        val lines = text.removePrefix("﻿").lines()
        val headerAt = lines.indexOfFirst { l -> val x = f(l); (dateNames.any { x.contains(f(it)) }) && (amountNames + creditNames + debitNames).any { x.contains(f(it)) } }
        val body = if (headerAt > 0) { warnings += "Přeskočeno $headerAt úvodních řádků před hlavičkou."; lines.drop(headerAt).joinToString("\n") } else text
        val p = Csv.read(body)
        if (p.headers.size < 2) return Detection(null, p.headers, warnings + "Nepodařilo se rozpoznat sloupce (oddělovač?).", emptyList())
        val dateCol = findCol(p.headers, dateNames).takeIf { it >= 0 } ?: p.headers.indices.firstOrNull { c -> p.rows.take(20).count { CzNum.date(it.getOrElse(c) { "" }) != null } >= minOf(3, p.rows.size) } ?: -1
        var amountCol = findCol(p.headers, amountNames)
        var debitCol = -1
        if (amountCol < 0) { amountCol = findCol(p.headers, creditNames); debitCol = findCol(p.headers, debitNames) }
        if (amountCol < 0) amountCol = p.headers.indices.firstOrNull { c -> c != dateCol && p.rows.take(20).count { CzNum.parse(it.getOrElse(c) { "" }) != null } >= minOf(3, p.rows.size) } ?: -1
        val descCols = p.headers.indices.filter { c -> c != dateCol && c != amountCol && c != debitCol && descNames.any { f(p.headers[c]).contains(f(it)) } }.take(3)
        val currencyCol = findCol(p.headers, currencyNames)
        if (dateCol < 0) warnings += "Nenašel jsem sloupec s datem."
        if (amountCol < 0) warnings += "Nenašel jsem sloupec s částkou."
        val amounts = p.rows.take(200).mapNotNull { it.getOrNull(amountCol) }.filter { it.isNotBlank() }
        val decimalComma = CzNum.usesDecimalComma(amounts)
        if (decimalComma && amounts.any { it.contains(',') }) warnings += "Částky používají českou desetinnou čárku (např. „${amounts.first { it.contains(',') }.trim()}“) – převádím na čísla, ne na text."
        if (p.delimiter == ';') warnings += "Oddělovač je středník (český Excel)."
        if (p.issues.isNotEmpty()) warnings += "${p.issues.size} problémových řádků (např. řádek ${p.issues[0].line}: ${p.issues[0].message})."
        val spec = if (dateCol >= 0 && amountCol >= 0) ParserSpec(p.delimiter.toString(), decimalComma, dateCol, amountCol, descCols, currencyCol, debitCol,
            headerHash = p.headers.joinToString("|") { f(it) }.hashCode().toString(16)) else null
        return Detection(spec, p.headers, warnings, p.rows.take(5))
    }

    data class Result(val table: Value.Table, val ok: Int, val failed: List<Pair<Int, String>>, val income: Double, val expense: Double)

    /** Použije parser na celý soubor. Neplatné řádky se nezahodí potichu – jsou v [Result.failed] s číslem řádku. */
    fun process(text: String, spec: ParserSpec): Result {
        val lines = text.removePrefix("﻿").lines()
        val d = spec.delimiter.first()
        // hlavička: řádek se stejným počtem polí a s textem ve sloupci částky
        val p0 = Csv.read(lines.joinToString("\n"), d)
        var offset = 0
        val headerIdx = lines.indexOfFirst { l -> val c = Csv.parse(l, d).firstOrNull() ?: emptyList(); c.size > maxOf(spec.dateCol, spec.amountCol) && CzNum.date(c[spec.dateCol]) == null && CzNum.parse(c[spec.amountCol], spec.decimalComma) == null }
        val p = if (headerIdx > 0) { offset = headerIdx; Csv.read(lines.drop(headerIdx).joinToString("\n"), d) } else p0
        val out = ArrayList<List<String>>()
        val failed = ArrayList<Pair<Int, String>>()
        var inc = 0.0; var exp = 0.0
        p.rows.forEachIndexed { i, r ->
            val line = p.rowLines.getOrElse(i) { i + 2 } + offset
            val date = r.getOrNull(spec.dateCol)?.let { CzNum.date(it) }
            var amt = r.getOrNull(spec.amountCol)?.let { CzNum.parse(it, spec.decimalComma) }
            if (spec.debitCol >= 0) {
                val deb = r.getOrNull(spec.debitCol)?.let { CzNum.parse(it, spec.decimalComma) }
                amt = when { amt != null && amt != 0.0 -> amt; deb != null -> -kotlin.math.abs(deb); else -> amt }
            }
            when {
                r.all { it.isBlank() } -> {}
                date == null -> failed += line to "neplatné datum „${r.getOrNull(spec.dateCol) ?: ""}“"
                amt == null -> failed += line to "neplatná částka „${r.getOrNull(spec.amountCol) ?: ""}“"
                else -> {
                    if (amt >= 0) inc += amt else exp += amt
                    val desc = spec.descCols.mapNotNull { r.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(" · ")
                    out += listOf(date, String.format(java.util.Locale.ROOT, "%.2f", amt), r.getOrNull(spec.currencyCol)?.trim()?.ifBlank { null } ?: "CZK", desc)
                }
            }
        }
        val notes = buildList {
            add("Příjmy ${CzNum.format(inc)} · výdaje ${CzNum.format(exp)} · saldo ${CzNum.format(inc + exp)} · ${out.size} pohybů")
            if (failed.isNotEmpty()) add("Nezpracováno ${failed.size} řádků: " + failed.take(5).joinToString("; ") { "ř. ${it.first} ${it.second}" })
        }
        return Result(Value.Table(listOf("Datum", "Částka", "Měna", "Popis"), out, notes, "výpis"), out.size, failed, inc, exp)
    }

    /** Ověření parseru na vzorku: aspoň 90 % řádků musí projít, jinak spec nepoužít. */
    fun verify(text: String, spec: ParserSpec): Pair<Boolean, String> {
        val r = process(text, spec)
        val total = r.ok + r.failed.size
        val ok = total > 0 && r.ok * 10 >= total * 9
        return ok to "Ověřeno na vzorku: ${r.ok}/$total řádků prošlo" + if (r.failed.isNotEmpty()) " (první chyba: ř. ${r.failed[0].first} ${r.failed[0].second})" else ""
    }

    /** Součty po měsících (navazující nástroj). */
    fun byMonth(t: Value.Table): Value.Table {
        val di = t.col("Datum"); val ai = t.col("Částka")
        val g = t.rows.groupBy { it[di].take(7) }.toSortedMap()
        val rows = g.map { (m, rs) ->
            val a = rs.mapNotNull { it[ai].toDoubleOrNull() }
            listOf(m, CzNum.format(a.filter { it > 0 }.sum()), CzNum.format(a.filter { it < 0 }.sum()), CzNum.format(a.sum()), rs.size.toString())
        }
        return Value.Table(listOf("Měsíc", "Příjmy", "Výdaje", "Saldo", "Pohybů"), rows, name = "měsíce")
    }
}
