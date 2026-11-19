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
        /**
         * Sloupce podle NÁZVU (role → složený název hlavičky: date, amount, debit, currency, desc0…). Uložený postup tak
         * zpracuje i výpis s PŘEHÁZENÝMI sloupci; pozice výše jsou jen záloha pro soubory bez hlavičky.
         */
        val columns: Map<String, String> = emptyMap(),
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
            .takeIf { it >= 0 }
            // neznámý název částky („Pohyb“): hlavička = řádek s názvem data a ≥ 3 poli, po kterém následuje řádek s datem
            ?: lines.indices.firstOrNull { i -> val x = f(lines[i]); dateNames.any { x.contains(f(it)) } && Regex("[;,\t|]").findAll(lines[i]).count() >= 2 &&
                lines.getOrNull(i + 1)?.split(';', ',', '\t', '|')?.any { CzNum.date(it.trim().trim('"')) != null } == true } ?: -1
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
        // průběžný zůstatek vypadá jako částka (čísla v každém řádku) – výsledek by byl tiše špatně → říct a nabídnout opravu větou
        if (amountCol >= 0 && Regex("zustatek|balance|stav uctu|stav").containsMatchIn(f(p.headers[amountCol]))) {
            val alt = p.headers.indices.filter { it != amountCol && it != dateCol && p.rows.take(20).count { r -> CzNum.parse(r.getOrElse(it) { "" }) != null } >= minOf(3, p.rows.size) }.map { p.headers[it] }
            warnings += "Jako částku jsem vzal sloupec „${p.headers[amountCol]}“, ale to vypadá na průběžný ZŮSTATEK, ne na pohyb." + (alt.firstOrNull()?.let { " Je-li částka jinde, napiš třeba „částka je ve sloupci $it“." } ?: "")
        }
        val amounts = p.rows.take(200).mapNotNull { it.getOrNull(amountCol) }.filter { it.isNotBlank() }
        val decimalComma = CzNum.usesDecimalComma(amounts)
        if (decimalComma && amounts.any { it.contains(',') }) warnings += "Částky používají českou desetinnou čárku (např. „${amounts.first { it.contains(',') }.trim()}“) – převádím na čísla, ne na text."
        if (p.delimiter == ';') warnings += "Oddělovač je středník (český Excel)."
        if (p.issues.isNotEmpty()) warnings += "${p.issues.size} problémových řádků (např. řádek ${p.issues[0].line}: ${p.issues[0].message})."
        val spec = if (dateCol >= 0 && amountCol >= 0) ParserSpec(p.delimiter.toString(), decimalComma, dateCol, amountCol, descCols, currencyCol, debitCol,
            headerHash = p.headers.joinToString("|") { f(it) }.hashCode().toString(16), columns = namesOf(p.headers, dateCol, amountCol, descCols, currencyCol, debitCol)) else null
        return Detection(spec, p.headers, warnings, p.rows.take(5))
    }

    data class Result(val table: Value.Table, val ok: Int, val failed: List<Pair<Int, String>>, val income: Double, val expense: Double)

    /** Použije parser na celý soubor. Neplatné řádky se nezahodí potichu – jsou v [Result.failed] s číslem řádku. */
    private fun namesOf(h: List<String>, date: Int, amount: Int, desc: List<Int>, cur: Int, debit: Int): Map<String, String> = buildMap {
        fun n(i: Int) = h.getOrNull(i)?.let { f(it) }?.takeIf { it.isNotEmpty() }
        n(date)?.let { put("date", it) }; n(amount)?.let { put("amount", it) }; n(cur)?.let { put("currency", it) }; n(debit)?.let { put("debit", it) }
        desc.forEachIndexed { k, i -> n(i)?.let { put("desc$k", it) } }
    }

    /** Pozice podle názvů v hlavičce tohoto souboru (přeházené sloupce); chybí-li některý název, zůstanou uložené pozice. */
    fun remap(spec: ParserSpec, headers: List<String>): ParserSpec {
        if (spec.columns.isEmpty()) return spec
        val h = headers.map { f(it) }
        fun idx(role: String, def: Int) = spec.columns[role]?.let { name -> h.indexOf(name).takeIf { it >= 0 } } ?: def
        val date = spec.columns["date"]?.let { h.indexOf(it) } ?: -1
        val amount = spec.columns["amount"]?.let { h.indexOf(it) } ?: -1
        if (date < 0 || amount < 0) return spec // jiný formát hlavičky → nehádat
        val descs = spec.columns.keys.filter { it.startsWith("desc") }.sorted().mapNotNull { k -> h.indexOf(spec.columns[k]).takeIf { it >= 0 } }
        return spec.copy(dateCol = date, amountCol = amount, descCols = descs, currencyCol = idx("currency", -1), debitCol = idx("debit", -1))
    }

    /** Oprava od uživatele („částka je ve sloupci Pohyb“): role → sloupec podle názvu. Null = sloupec neexistuje. */
    fun withColumn(spec: ParserSpec, headers: List<String>, role: String, column: String): ParserSpec? {
        val h = headers.map { f(it) }
        val c = f(column)
        val i = h.indexOf(c).takeIf { it >= 0 } ?: h.indexOfFirst { it.startsWith(c) || it.contains(c) }.takeIf { it >= 0 } ?: return null
        val cols = spec.columns.toMutableMap()
        return when (role) {
            "date" -> { cols["date"] = h[i]; spec.copy(dateCol = i, columns = cols) }
            "amount" -> { cols["amount"] = h[i]; cols.remove("debit"); spec.copy(amountCol = i, debitCol = -1, columns = cols) }
            "currency" -> { cols["currency"] = h[i]; spec.copy(currencyCol = i, columns = cols) }
            else -> { cols.keys.filter { it.startsWith("desc") }.forEach { cols.remove(it) }; cols["desc0"] = h[i]; spec.copy(descCols = listOf(i), columns = cols) }
        }
    }

    /** Hlavička souboru (stejné hledání jako [process]). */
    fun headersOf(text: String, spec: ParserSpec): List<String> {
        // hlavička bývá v prvních řádcích (úvod banky) – neprocházet celý soubor
        val lines = text.removePrefix("\uFEFF").lineSequence().take(60).toList(); val d = spec.delimiter.first()
        val known = spec.columns.values.toSet()
        val i = lines.indexOfFirst { l -> val c = Csv.parse(l, d).firstOrNull() ?: emptyList(); if (known.isNotEmpty()) c.count { f(it) in known } >= 2 else c.size > maxOf(spec.dateCol, spec.amountCol) && CzNum.date(c[spec.dateCol]) == null }
        return if (i >= 0) (Csv.parse(lines[i], d).firstOrNull() ?: emptyList()).map { it.trim() } else emptyList()
    }

    fun process(text: String, spec0: ParserSpec): Result {
        val lines = text.removePrefix("\uFEFF").lines()
        val spec = remap(spec0, headersOf(text, spec0))
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

    /**
     * NEZÁVISLÝ TEST výsledku: má-li výpis sloupec průběžného zůstatku, musí platit zůstatek[i] = zůstatek[i-1] + částka[i].
     * Vrací (sedí, kontrolováno) nebo null, když zůstatek ve výpisu není (nebo je omylem použit jako částka).
     */
    fun reconcile(text: String, spec0: ParserSpec): Pair<Int, Int>? {
        val spec = remap(spec0, headersOf(text, spec0))
        val h = headersOf(text, spec).map { f(it) }
        val bal = h.indexOfFirst { Regex("zustatek|balance").containsMatchIn(it) }.takeIf { it >= 0 && it != spec.amountCol } ?: return null
        val d = spec.delimiter.first()
        val lines = text.removePrefix("\uFEFF").lines()
        val hi = lines.indexOfFirst { l -> (Csv.parse(l, d).firstOrNull() ?: emptyList()).map { f(it) } == h }
        if (hi < 0) return null
        val rows = Csv.read(lines.drop(hi).joinToString("\n"), d).rows.filter { r -> r.any { it.isNotBlank() } }
        // částka stejně jako v process (zvlášť příjem/výdaj); pořadí řádků může být od nejstaršího i od nejnovějšího
        val pts = rows.mapNotNull { r ->
            val b = r.getOrNull(bal)?.let { CzNum.parse(it, spec.decimalComma) } ?: return@mapNotNull null
            var a = r.getOrNull(spec.amountCol)?.let { CzNum.parse(it, spec.decimalComma) }
            if (spec.debitCol >= 0) { val deb = r.getOrNull(spec.debitCol)?.let { CzNum.parse(it, spec.decimalComma) }; a = when { a != null && a != 0.0 -> a; deb != null -> -kotlin.math.abs(deb); else -> a } }
            a?.let { b to it }
        }
        if (pts.size < 2) return null
        val asc = (1 until pts.size).count { kotlin.math.abs(pts[it - 1].first + pts[it].second - pts[it].first) < 0.005 }
        val desc = (1 until pts.size).count { kotlin.math.abs(pts[it].first + pts[it - 1].second - pts[it - 1].first) < 0.005 }
        return maxOf(asc, desc) to pts.size - 1
    }

    fun reconcileText(text: String, spec: ParserSpec): String? = reconcile(text, spec)?.let { (ok, n) -> if (ok == n) "Kontrola proti zůstatku banky: $ok/$n návazností sedí ✓" else "Kontrola proti zůstatku banky NESEDÍ: $ok/$n – částky jsou nejspíš ze špatného sloupce." }

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
