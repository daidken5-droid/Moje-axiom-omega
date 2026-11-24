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
        /** Sloupec se SMĚREM pohybu (Debet/Kredit, Příchozí/Odchozí) u bank, které píšou částku bez znaménka; -1 = není. */
        val directionCol: Int = -1,
        /** Sloupec se STAVEM transakce (Revolut „State“): REVERTED / DECLINED / FAILED se nepočítají; -1 = není. */
        val stateCol: Int = -1,
        /** Sloupec s POPLATKEM zvlášť (Revolut „Fee“): odečte se od částky; -1 = není. */
        val feeCol: Int = -1,
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

    private fun f(s: String) = cz.axiom.vega.base.Text.fold(s).lowercase().trim()
    // jen jednoznačné hodnoty (ne „platba“, „vklad“ – mohou být v popisu a otočily by znaménko příjmu)
    private val DEBIT = setOf("debet", "debit", "odchozi", "vydaj", "odepsano", "d", "dr", "-", "odchozi platba", "outgoing")
    /** Stavy transakce, které se nestaly (zrušené, odmítnuté, vrácené) – do součtů nepatří. */
    private val VOID = setOf("reverted", "declined", "failed", "cancelled", "canceled", "zamitnuto", "zamitnuta", "zruseno", "zrusena", "stornovano", "odmitnuto")
    private val CREDIT = setOf("kredit", "credit", "prichozi", "prijem", "pripsano", "c", "cr", "+", "prichozi platba", "incoming")
    /** Výdaj podle sloupce se směrem (hodnota „Debet“, „Odchozí“…). */
    fun isDebit(v: String?) = v != null && f(v) in DEBIT
    /** Sloupec, jehož hodnoty jsou skoro jen Debet/Kredit (aspoň jeden výdaj) – jinak by kladné částky byly všechny „příjmy“. */
    private fun directionColumn(rows: List<List<String>>, skip: Set<Int>, width: Int): Int = (0 until width).firstOrNull { c ->
        c !in skip && rows.take(200).mapNotNull { it.getOrNull(c)?.takeIf { v -> v.isNotBlank() }?.let { v -> f(v) } }.let { v ->
            v.size >= 1 && v.count { it in DEBIT || it in CREDIT } >= v.size * 0.8 && v.any { it in DEBIT } }
    } ?: -1
    private fun findCol(headers: List<String>, names: List<String>, folded: List<String>? = null): Int {
        val h = folded ?: headers.map { f(it) }
        // jména převést jednou (dřív f(n) pro KAŽDOU hlavičku – export s 20 000 sloupci: statisíce převodů, v CI 1,6 s)
        val fn = names.map { f(it) }
        fn.forEach { n -> h.indexOfFirst { it == n }.takeIf { it >= 0 }?.let { return it } }
        fn.forEach { n -> h.indexOfFirst { it.contains(n) }.takeIf { it >= 0 }?.let { return it } }
        return -1
    }

    /** Konce řádků CRLF i samotné CR (CSV z Excelu na Macu) → \n; dřív CR-only ztratil popisy. */
    private fun eol(t: String) = if (t.indexOf('\r') < 0) t else t.replace("\r\n", "\n").replace('\r', '\n')
    fun detect(text0: String): Detection {
        val text = eol(text0)
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
        // hlavičky převést (bez diakritiky, malá písmena) JEDNOU – export s 20 000 sloupci dřív stál statisíce převodů (CI: 1,6 s)
        val hf = p.headers.map { f(it) }
        val dateCol = findCol(p.headers, dateNames, hf).takeIf { it >= 0 } ?: p.headers.indices.firstOrNull { c -> p.rows.take(20).count { CzNum.date(it.getOrElse(c) { "" }) != null } >= minOf(3, p.rows.size) } ?: -1
        var amountCol = findCol(p.headers, amountNames, hf)
        var debitCol = -1
        if (amountCol < 0) { amountCol = findCol(p.headers, creditNames, hf); debitCol = findCol(p.headers, debitNames, hf) }
        if (amountCol < 0) amountCol = p.headers.indices.firstOrNull { c -> c != dateCol && p.rows.take(20).count { CzNum.parse(it.getOrElse(c) { "" }) != null } >= minOf(3, p.rows.size) } ?: -1
        val directionCol = if (debitCol < 0 && amountCol >= 0) directionColumn(p.rows, setOf(dateCol, amountCol), p.headers.size) else -1
        if (directionCol >= 0) warnings += "Směr pohybu beru ze sloupce „${p.headers[directionCol]}“ (Debet/Odchozí = výdaj)."
        val descFolded = descNames.map { f(it) }
        val descCols = p.headers.indices.filter { c -> c != dateCol && c != amountCol && c != debitCol && c != directionCol && descFolded.any { hf[c].contains(it) } }.take(3)
        // „Originální měna“ (KB) je měna transakce před převodem – částka je v měně účtu
        val currencyCol = p.headers.indices.firstOrNull { hf[it] in setOf("mena", "currency", "mena uctu") }
            ?: run { val cn = currencyNames.map { f(it) }; val orig = Regex("origin|puvodn")
                p.headers.indices.firstOrNull { c -> hf[c].let { h -> cn.any { h.contains(it) } && !orig.containsMatchIn(h) } } } ?: -1
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
        val stateCol = p.headers.indices.firstOrNull { hf[it] in setOf("state", "status", "stav transakce", "stav platby") } ?: -1
        val feeCol = p.headers.indices.firstOrNull { hf[it] in setOf("fee", "fees") && it != amountCol } ?: -1
        val spec = if (dateCol >= 0 && amountCol >= 0) ParserSpec(p.delimiter.toString(), decimalComma, dateCol, amountCol, descCols, currencyCol, debitCol,
            headerHash = p.headers.joinToString("|") { f(it) }.hashCode().toString(16), columns = namesOf(p.headers, dateCol, amountCol, descCols, currencyCol, debitCol) +
                (p.headers.getOrNull(directionCol)?.let { mapOf("direction" to f(it)) } ?: emptyMap()) +
                (p.headers.getOrNull(stateCol)?.let { mapOf("state" to f(it)) } ?: emptyMap()) + (p.headers.getOrNull(feeCol)?.let { mapOf("fee" to f(it)) } ?: emptyMap()),
            directionCol = directionCol, stateCol = stateCol, feeCol = feeCol) else null
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
        if (date < 0 || amount < 0) {
            // hlavička je poznat (některé uložené názvy sedí), ale datum/částka zmizely → staré pozice by tiše četly jiný sloupec (např. zůstatek)
            // jen „jmenné“ hlavičky (ne čísla ani data z prvního řádku souboru bez hlavičky)
            val known = spec.columns.values.count { it in h && it.any(Char::isLetter) && CzNum.parse(it) == null && CzNum.date(it) == null }
            if (known > 0) throw ToolError("Hlavička souboru se změnila – chybí sloupec „${spec.columns[if (amount < 0) "amount" else "date"]}“. Nepočítám ze staré pozice (mohl bych sečíst zůstatek). Napiš třeba „částka je ve sloupci …“.")
            return spec // bez rozpoznatelné hlavičky (soubor bez názvů sloupců) → uložené pozice
        }
        val descs = spec.columns.keys.filter { it.startsWith("desc") }.sorted().mapNotNull { k -> h.indexOf(spec.columns[k]).takeIf { it >= 0 } }
        return spec.copy(dateCol = date, amountCol = amount, descCols = descs, currencyCol = idx("currency", -1), debitCol = idx("debit", -1), directionCol = idx("direction", -1),
            stateCol = idx("state", -1), feeCol = idx("fee", -1))
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
    fun headersOf(text0: String, spec: ParserSpec): List<String> {
        val text = eol(text0)
        // hlavička bývá v prvních řádcích (úvod banky) – neprocházet celý soubor
        val lines = text.removePrefix("\uFEFF").lineSequence().take(60).toList(); val d = spec.delimiter.first()
        val known = spec.columns.values.toSet()
        val i = lines.indexOfFirst { l -> val c = Csv.parse(l, d).firstOrNull() ?: emptyList(); if (known.isNotEmpty()) c.count { f(it) in known } >= 2 else c.size > maxOf(spec.dateCol, spec.amountCol) && CzNum.date(c[spec.dateCol]) == null }
        return if (i >= 0) (Csv.parse(lines[i], d).firstOrNull() ?: emptyList()).map { it.trim() } else emptyList()
    }

    /** „Kč“, „CZK“, „koruna“ jsou jedna měna (dřív by se výpis se sloupcem „Kč/CZK“ rozpadl na dvě měny a součty by byly poloviční). */
    fun currency(raw: String?): String {
        val c = raw?.trim()?.ifBlank { null } ?: return "CZK"
        val k = f(c).replace(".", "").trim()
        return when {
            k in setOf("kc", "czk", "korun", "koruna", "koruny") -> "CZK"
            c == "€" || k in setOf("eur", "euro", "eura") -> "EUR"
            c == "$" || k in setOf("usd", "us$", "dolar", "dolary") -> "USD"
            c == "£" || k == "gbp" -> "GBP"
            else -> c.uppercase()
        }
    }

    fun process(text0: String, spec0: ParserSpec): Result {
        val text = eol(text0)
        val lines = text.removePrefix("\uFEFF").lines()
        val spec = remap(spec0, headersOf(text, spec0))
        val d = spec.delimiter.first()
        // hlavička: řádek se stejným počtem polí a s textem ve sloupci částky
        // soubor BEZ hlavičky: první řádek je pohyb (platné datum i částka) → nesmí se spolknout jako hlavička
        val first = lines.firstOrNull { it.isNotBlank() }?.let { Csv.parse(it, d).firstOrNull() } ?: emptyList()
        val firstIsData = first.size > maxOf(spec.dateCol, spec.amountCol) && CzNum.date(first[spec.dateCol]) != null && CzNum.parse(first[spec.amountCol], spec.decimalComma) != null
        val p0 = Csv.read(lines.joinToString("\n"), d, header = !firstIsData)
        var offset = 0
        val headerIdx = lines.indexOfFirst { l -> val c = Csv.parse(l, d).firstOrNull() ?: emptyList(); c.size > maxOf(spec.dateCol, spec.amountCol) && CzNum.date(c[spec.dateCol]) == null && CzNum.parse(c[spec.amountCol], spec.decimalComma) == null }
        val p = if (headerIdx > 0) { offset = headerIdx; Csv.read(lines.drop(headerIdx).joinToString("\n"), d) } else p0
        val out = ArrayList<List<String>>()
        val failed = ArrayList<Pair<Int, String>>()
        var inc = 0.0; var exp = 0.0; var voided = 0; var fees = 0.0
        val perCur = LinkedHashMap<String, DoubleArray>() // měna → [příjmy, výdaje, počet]
        p.rows.forEachIndexed { i, r ->
            val line = p.rowLines.getOrElse(i) { i + 2 } + offset
            val date = r.getOrNull(spec.dateCol)?.let { CzNum.date(it) }
            var amt = r.getOrNull(spec.amountCol)?.let { CzNum.parse(it, spec.decimalComma) }
            if (spec.debitCol >= 0) {
                val deb = r.getOrNull(spec.debitCol)?.let { CzNum.parse(it, spec.decimalComma) }
                amt = when { amt != null && amt != 0.0 -> amt; deb != null -> -kotlin.math.abs(deb); else -> amt }
            }
            // částka bez znaménka + sloupec se směrem: výdaj (Debet/Odchozí) je záporný
            if (spec.directionCol >= 0 && amt != null && amt > 0 && isDebit(r.getOrNull(spec.directionCol))) amt = -amt
            if (spec.feeCol >= 0 && amt != null) r.getOrNull(spec.feeCol)?.let { CzNum.parse(it, spec.decimalComma) }?.takeIf { it != 0.0 }?.let { fee -> amt = amt!! - kotlin.math.abs(fee); fees += kotlin.math.abs(fee) }
            when {
                r.all { it.isBlank() } -> {}
                spec.stateCol >= 0 && f(r.getOrNull(spec.stateCol) ?: "") in VOID -> voided++
                // souhrnné řádky banky („Celkem“, „Konečný zůstatek“, „Obrat“) nejsou chyba – jen se nepočítají
                date == null && Regex("celkem|zustatek|soucet|obrat|pocatecni|konecn|suma\\b|total|balance").containsMatchIn(cz.axiom.vega.base.Text.fold(r.joinToString(" ")).lowercase()) -> {}
                date == null -> failed += line to "neplatné datum „${r.getOrNull(spec.dateCol) ?: ""}“"
                amt == null -> failed += line to "neplatná částka „${r.getOrNull(spec.amountCol) ?: ""}“"
                else -> {
                    val cur = currency(r.getOrNull(spec.currencyCol))
                    perCur.getOrPut(cur) { DoubleArray(3) }.let { a -> if (amt >= 0) a[0] += amt else a[1] += amt; a[2] += 1.0 }
                    val desc = spec.descCols.mapNotNull { r.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(" · ")
                    out += listOf(date, String.format(java.util.Locale.ROOT, "%.2f", amt), cur, desc)
                }
            }
        }
        // různé měny se nesčítají: součty za hlavní měnu (nejvíc pohybů), ostatní zvlášť
        val main = perCur.maxByOrNull { it.value[2] }?.key ?: "CZK"
        perCur[main]?.let { inc = it[0]; exp = it[1] }
        val notes = buildList {
            if (perCur.size > 1) {
                add("Výpis obsahuje víc měn – nesčítám je dohromady. " + perCur.entries.joinToString(" · ") { (c, a) -> "$c: příjmy ${CzNum.format(a[0])}, výdaje ${CzNum.format(a[1])}, saldo ${CzNum.format(a[0] + a[1])} (${cz.axiom.vega.base.Text.plural(a[2].toInt(), "pohyb", "pohyby", "pohybů")})" })
                add("Součty níže jsou jen v $main.")
            }
            add("Příjmy ${CzNum.format(inc)} · výdaje ${CzNum.format(exp)} · saldo ${CzNum.format(inc + exp)}${if (perCur.size > 1) " $main" else ""} · ${cz.axiom.vega.base.Text.plural(out.size, "pohyb", "pohyby", "pohybů")}")
            if (voided > 0) add("Vynecháno $voided ${if (voided == 1) "zrušená / odmítnutá platba" else if (voided in 2..4) "zrušené / odmítnuté platby" else "zrušených / odmítnutých plateb"} (stav REVERTED, DECLINED…) – nestaly se.")
            if (fees > 0) add("Poplatky ze sloupce „Poplatek / Fee“ (${CzNum.format(-fees)}) jsou započtené ve výdajích.")
            if (failed.isNotEmpty()) add("Nezpracováno ${cz.axiom.vega.base.Text.plural(failed.size, "řádek", "řádky", "řádků")}: " + failed.take(5).joinToString("; ") { "ř. ${it.first} ${it.second}" })
        }
        return Result(Value.Table(listOf("Datum", "Částka", "Měna", "Popis"), out, notes, "výpis"), out.size, failed, inc, exp)
    }

    /**
     * Kategorie výdajů bez modelu: pravidla podle českých obchodníků a klíčových slov v popisu (první shoda vyhrává).
     * Nejisté → „Ostatní“ (raději přiznat než hádat); příjmy se do výdajů nepočítají.
     */
    private val CATEGORIES: List<Pair<String, Regex>> = listOf(
        "Potraviny" to "albert|lidl|billa|tesco|kaufland|penny|globus|rohlik|kosik|coop|jednota|norma|potravin|zabka|zabka|hruska|tamda",
        "Restaurace a kavárny" to "restaura|bistro|kavarn|cafe|pizz|burger|mcdonald|kfc|starbucks|costa coffee|wolt|foodora|dame jidlo|bageterie|pivnic|hospod",
        "Doprava a palivo" to "benzina|shell|omv|orlen|\\bmol\\b|eurooil|cerpaci|tank ono|dpp|dpmb|ceske drahy|cd\\.cz|regiojet|leo express|flixbus|uber|bolt|liftago|parkov|dalnicni|myto|jizdenk|litacka",
        "Bydlení a energie" to "najem|najemne|cez|\\bpre( a\\.? ?s|\\s*$|\\s+(elektr|zaloh|distrib|energ))|pražská energetika|prazska energetika|innogy|e\\.on|\\beon\\b|\\bsipo\\b|plyn|vodne|vodarny|svj|fond oprav|zaloha na|energie|elektri|bytove druzstvo",
        "Zdraví a drogerie" to "lekarn|dr\\.? ?max|benu|pilulka|lekar|nemocnic|zubar|optik|dm drogerie|^dm |rossmann|teta drogerie|drogeri",
        "Telefon a internet" to "o2 |o2$|o2 czech|t-mobile|tmobile|vodafone|\\bupc\\b|vodafone|internet(?!\\w* bankovn)|nordic telecom",
        "Předplatné a digitální" to "netflix|spotify|youtube|google|apple\\.com|itunes|disney|hbo|max\\.com|voyo|microsoft|steam|playstation|xbox|patreon|chatgpt|openai",
        "Nákupy" to "alza|mall\\.cz|ikea|decathlon|h&m|zara|amazon|aliexpress|temu\\.|^temu\\b|notino|datart|electro world|okay|hornbach|\\bobi\\b|bauhaus|sportisimo|ccc|deichmann|pepco|\\baction\\b|zasilkovn|balikovn|\\bppl\\b|\\bdpd\\b",
        "Hotovost" to "vyber|bankomat|atm|hotovost",
        "Úřady a daně" to "financni urad|danov\\w* (urad|priznan|nedoplat)|dan z (prijm|nemovit)|silnicni dan|\\bcssz\\b|socialni\\w* zabezp|pokut|magistrat|urad mest|obecni urad|poplatek za odpad|komunalni odpad|rozhlas|ceska televize|\\bsoud|exekut",
        "Pojištění a finance" to "pojist|pojišt|\\bvzp\\b|\\bozp\\b|\\bzpmv\\b|kooperativ|allianz|generali|uniqa|csob pojist|splatk|uver|hypote|leasing|poplatek|vedeni uctu",
        "Děti a vzdělání" to "skolk|skola|krouzek|druzin|obedy|skolne|knih|knihkupect|\\bkurz(u|y|em|ech)?\\b",
        "Volný čas" to "kino|cinema city|divadl|koncert|ticketportal|goout|vstupen|fitness|posilov|bazen|sport|dovolen|booking|airbnb|hotel|cestovn|sazka|tipsport|fortuna|chance|loteri",
    ).map { (k, v) -> k to Regex(v) }

    fun categoryOf(desc: String): String { val d = cz.axiom.vega.base.Text.fold(desc).lowercase(); return CATEGORIES.firstOrNull { it.second.containsMatchIn(d) }?.first ?: "Ostatní" }

    /** Tabulka výpisu (Datum · Částka · Měna · Popis) → výdaje podle kategorií, seřazeno od největší. */
    /** Posledních pár výsledků podle IDENTITY tabulky – doplňující dotazy nad stejným výpisem nepočítají 20 000 řádků znovu. */
    private val catCache = java.util.Collections.synchronizedMap(object : java.util.IdentityHashMap<Value.Table, Value.Table>() {})
    fun categories(t: Value.Table): Value.Table {
        catCache[t]?.let { return it }
        return categories0(t).also { synchronized(catCache) { if (catCache.size > 8) catCache.clear(); catCache[t] = it } }
    }
    private fun categories0(t: Value.Table): Value.Table {
        val ai = t.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }.takeIf { it >= 0 } ?: 1
        val di = t.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("popis") }.takeIf { it >= 0 } ?: 3
        val exp = t.rows.mapNotNull { r -> r.getOrNull(ai)?.let { CzNum.parse(it, false) }?.takeIf { it < 0 }?.let { categoryOf(r.getOrElse(di) { "" }) to it } }
        val total = exp.sumOf { it.second }
        val rows = exp.groupBy { it.first }.map { (k, v) -> Triple(k, v.sumOf { it.second }, v.size) }.sortedBy { it.second }
            .map { (k, s, n) -> listOf(k, CzNum.format(s), n.toString(), if (total != 0.0) "${Math.round(s / total * 100)} %" else "–") }
        val other = rows.firstOrNull { it[0] == "Ostatní" }
        val notes = buildList {
            add("Výdaje celkem ${CzNum.format(total)} · ${cz.axiom.vega.base.Text.plural(exp.size, "platba", "platby", "plateb")} · kategorie podle obchodníka a popisu (bez modelu, v telefonu)")
            if (other != null) add("„Ostatní“ = popis bez známého obchodníka (${cz.axiom.vega.base.Text.plural(other[2].toInt(), "platba", "platby", "plateb")}) – kategorie jsou orientační.")
        }
        return Value.Table(listOf("Kategorie", "Výdaje", "Plateb", "Podíl"), rows, notes, "kategorie")
    }

    /** Klíč obchodníka z popisu: bez diakritiky, bez čísel a obecných slov („Platba kartou NETFLIX.COM 123“ → „netflix com“). */
    private val NOISE = setOf("platba", "kartou", "karta", "trvaly", "prikaz", "inkaso", "sepa", "prevod", "odchozi", "prichozi", "uhrada", "nakup", "transakce", "cz", "praha", "s", "r", "o", "a", "as", "sro")
    fun merchantKey(desc: String): String = Regex("[a-z]{2,}").findAll(cz.axiom.vega.base.Text.fold(desc).lowercase()).map { it.value }.filter { it !in NOISE }.take(2).joinToString(" ")

    /**
     * Pravidelné platby (předplatné, nájem, pojištění…): stejný obchodník ve ≥ 2 různých měsících s podobnou částkou (±10 %).
     * Tabulka: Platba, Měsíčně (průměr), Ročně (odhad ×12), Měsíců, Kategorie – seřazeno od nejdražší.
     */
    fun recurring(t: Value.Table): Value.Table {
        val ai = t.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }.takeIf { it >= 0 } ?: 1
        val di = t.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("popis") }.takeIf { it >= 0 } ?: 3
        data class P(val month: String, val amt: Double, val desc: String)
        val byKey = t.rows.mapNotNull { r -> val a = r.getOrNull(ai)?.let { CzNum.parse(it, false) } ?: return@mapNotNull null; if (a >= 0) return@mapNotNull null
            val d = r.getOrElse(di) { "" }; val k = merchantKey(d).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k to P(r.getOrElse(0) { "" }.take(7), a, d) }.groupBy({ it.first }, { it.second })
        val rows = byKey.mapNotNull { (_, ps) ->
            val months = ps.map { it.month }.distinct(); if (months.size < 2) return@mapNotNull null
            val perMonth = months.map { m -> ps.filter { it.month == m }.sumOf { it.amt } }
            val avg = perMonth.average(); if (perMonth.any { kotlin.math.abs(it - avg) > kotlin.math.abs(avg) * 0.10 }) return@mapNotNull null
            listOf(ps.first().desc.take(60), CzNum.format(avg), CzNum.format(avg * 12), months.size.toString(), categoryOf(ps.first().desc)) to avg
        }.sortedBy { it.second }.map { it.first }
        val yearly = rows.sumOf { CzNum.parse(it[2], false) ?: 0.0 }
        val notes = if (rows.isEmpty()) listOf("Pravidelné platby jsem nenašel – výpis musí pokrývat aspoň 2 měsíce.")
            else listOf("Pravidelné platby: ${rows.size}, dohromady ${CzNum.format(yearly)} Kč ročně (odhad ×12). Hledám stejného obchodníka s podobnou částkou v ≥ 2 měsících – bez modelu.")
        return Value.Table(listOf("Platba", "Měsíčně", "Ročně (odhad)", "Měsíců", "Kategorie"), rows, notes, "pravidelné platby")
    }

    /** Slova laika → kategorie („jídlo“ = obchody i restaurace). */
    private val SPEND_WORDS: List<Pair<Regex, List<String>>> = listOf(
        Regex("jidl|jist|papani") to listOf("Potraviny", "Restaurace a kavárny"),
        Regex("potravin|nakup(y)? jidla|obchod|supermarket|albert|lidl|billa|tesco|kaufland") to listOf("Potraviny"),
        Regex("restaura|kavarn|kafe|obed|vecer[ie] venku|dovaz|wolt|foodora") to listOf("Restaurace a kavárny"),
        Regex("benzin|naft|palivo|tankov|auto|doprav|jizdenk|mhd|vlak|bus|taxi|bolt|uber|parkov") to listOf("Doprava a palivo"),
        Regex("najem|bydlen|energi|elektri|plyn|vod[ua]|cez|zaloh") to listOf("Bydlení a energie"),
        Regex("\\blek|lekarn|zdravi|doktor|lekar|drogeri") to listOf("Zdraví a drogerie"),
        Regex("telefon|mobil|internet|tarif") to listOf("Telefon a internet"),
        Regex("predplat|netflix|spotify|streaming|aplikac") to listOf("Předplatné a digitální"),
        Regex("nakup|obleceni|boty|elektronik|alza|ikea|e-?shop") to listOf("Nákupy"),
        Regex("hotovost|bankomat|vyber") to listOf("Hotovost"),
        Regex("pojist|splatk|uver|hypote|poplat") to listOf("Pojištění a finance"),
        Regex("deti|skol|krouz") to listOf("Děti a vzdělání"),
        Regex("zabav|volny cas|kino|sport|dovolen|cestov") to listOf("Volný čas"),
    )

    /**
     * „Kolik jsem utratil za jídlo / benzín / nájem?“, „za co nejvíc utrácím?“ nad tabulkou výpisu – bez modelu.
     * null = dotaz se výdajů netýká (zpracuje ho dál router / model).
     */
    fun spendQuery(t: Value.Table, raw: String, prev: String? = null): String? {
        val q = cz.axiom.vega.base.Text.fold(raw).lowercase()
        // eliptická navazující otázka („a v říjnu?“, „a za benzín?“, „a kolik za jídlo?“) → doplnit podle PŘEDCHOZÍ otázky k výpisu
        if (prev != null) {
            val e = Regex("^a (?:co |kolik |jak )?((?:v|ve|za|u|na|v lidlu|minul|tento|tenhle|celkem|co |prijmy|vydaje)\\b.*)$").find(q.trim().trimEnd('?', '!', '.'))
            if (e != null && q.split(' ').size <= 7 && spendQuery(t, prev) != null) {
                val p = cz.axiom.vega.base.Text.fold(prev).lowercase()
                val kind = when {
                    Regex("prijm|prislo|prisl[ay]|vydelal|mzd|vyplat").containsMatchIn(p) -> "kolik mi prislo"
                    Regex("usetril|zbylo|zustalo|bilanc|saldo|v plusu|v minusu").containsMatchIn(p) -> "kolik jsem usetril"
                    Regex("nejvetsi (platb|jednotliv|castk)|nejdrazsi|co jsem (nejvic|nejvice)").containsMatchIn(p) -> "jaka byla nejvetsi platba"
                    Regex("(za|na) co\\b.{0,24}\\b(nejvic|nejvice)").containsMatchIn(p) -> "za co utracim nejvic"
                    else -> "kolik jsem utratil" }
                return spendQuery(t, "$kind ${e.groupValues[1]}")
            }
        }
        if (!t.headers.any { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }) return null
        // „jaké mám pravidelné platby / předplatné?“, „co mi pravidelně odchází?“
        if (Regex("pravideln|predplatn|opakovan|kazdy mesic odchaz|mesicne (platim|odchazi)|trval[ey] prikaz").containsMatchIn(q)) {
            val r = recurring(t)
            if (r.rows.isEmpty()) return r.notes.first()
            // částky kladně („platíš 259 Kč měsíčně“) – mínus u „měsíčně“ laika mate
            fun pos(v: String) = CzNum.parse(v, false)?.let { CzNum.format(kotlin.math.abs(it)) } ?: v
            val month = r.rows.sumOf { kotlin.math.abs(CzNum.parse(it[1], false) ?: 0.0) }
            return "**Pravidelné platby** (${r.rows.size}) – dohromady **${CzNum.format(month)} Kč měsíčně**, asi ${CzNum.format(month * 12)} Kč ročně:\n" +
                r.rows.joinToString("\n") { "• ${it[0]} – ${pos(it[1])} Kč měsíčně" } + "\n_Stejný obchodník s podobnou částkou aspoň ve 2 měsících – bez modelu._"
        }
        // výpočet / hypotéza, ne dotaz na výpis: „kolik BYCH platil za hypotéku 3 mil.“, „kdybych utratil 500 Kč denně“, „kolik zbylo z 30 000 po 12 500“
        // (podmiňovací / budoucí tvar nebo vlastní čísla v otázce – letopočet „v říjnu 2026“ se nepočítá) → kalkulačka / model
        if (Regex("\\b(bych|bys|by|bysme|bychom|kdyby\\w*|budu|bude|budes|budeme|budou)\\b").containsMatchIn(q) || Regex("\\d").containsMatchIn(q.replace(Regex("\\b(19|20)\\d{2}\\b"), ""))) return null
        // příjmy / úspora z VÝPISU jen u osobní otázky („kolik MI přišlo“, „jaké MÁM příjmy“, „kolik JSEM ušetřil“) – „minimální mzda“, „příjmy státního rozpočtu“, „bilance obchodu ČR“ nejsou dotaz na výpis
        val personal = Regex("\\b(mi|mne|me|jsem|jsme|jsi|mam|mame|muj|moje|moji|meho|nam|nas|nase|nasi)\\b").containsMatchIn(q)
        val incomeQ = personal && Regex("\\b(kolik|jak[ey]|jaky|celkem|prehled|souhrn)\\b").containsMatchIn(q) && Regex("prijm|prislo|prisl[ay]|dosl[oa]|vydelal|mzd[auy]|vyplat|dostal jsem").containsMatchIn(q)
        val saldoQ = personal && Regex("usetril|zbylo|zustalo|bilanc|saldo|v plusu|v minusu|vic nez vydelam|vic nez jsem vydelal").containsMatchIn(q)
        // „kolik stojí…“ (přítomný čas) je otázka na cenu, ne na výpis
        val biggestQ = Regex("nejvetsi (platb|jednotliv|castk)|nejdrazsi (platb|nakup|vec)|co jsem (nejvic|nejvice) (zaplatil|utratil|platil)").containsMatchIn(q)
        if (!incomeQ && !saldoQ && !biggestQ && !Regex("utrat|utraci|utracim|utracel|\\bstal[oa]?\\b|dal[a]? jsem|zaplatil|platil|vydaj|vydal").containsMatchIn(q)) return null
        // měsíc v dotazu („v říjnu“, „za listopad“, „minulý / tento měsíc“) → jen pohyby toho měsíce (data v tabulce jsou ISO rrrr-mm-dd)
        val months = t.rows.mapNotNull { r -> r.getOrNull(0)?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }?.take(7) }.distinct().sorted()
        val mi = MONTH_RX.indexOfFirst { it.containsMatchIn(q) }
        val ym: String? = when {
            mi >= 0 -> months.lastOrNull { it.endsWith("-%02d".format(mi + 1)) } ?: "?-%02d".format(mi + 1)
            Regex("(tento|tenhle|letosni|tomto|tomhle) mesic|tento mesic|tomto mesici|tenhle mesic").containsMatchIn(q) -> months.lastOrNull()
            Regex("minul(y|em|ej) mesic|minulem mesici|predchozi mesic").containsMatchIn(q) -> months.dropLast(1).lastOrNull() ?: months.lastOrNull()?.let { java.time.YearMonth.parse(it).minusMonths(1).toString() }
            else -> null }
        fun label(m: String) = m.substringAfter('-').toInt().let { MONTH_NAMES[it - 1] } + (m.substringBefore('-').takeIf { it != "?" }?.let { " $it" } ?: "")
        val tt = if (ym == null) t else t.copy(rows = t.rows.filter { it.getOrNull(0)?.startsWith(ym) == true })
        val per = if (ym == null) "" else " za ${label(ym)}"
        if (ym != null && tt.rows.isEmpty()) return "Ve výpisu nejsou žádné pohyby za ${label(ym)}. Výpis má: ${months.joinToString(", ") { label(it) }.ifEmpty { "–" }}."
        fun plat(n: Int) = cz.axiom.vega.base.Text.plural(n, "platba", "platby", "plateb")
        if (incomeQ || saldoQ) {
            val ai = tt.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }
            val di = tt.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("popis") }.takeIf { it >= 0 } ?: 3
            val mv = tt.rows.mapNotNull { r -> r.getOrNull(ai)?.let { CzNum.parse(it, false) }?.let { r.getOrElse(di) { "" } to it } }
            val inc = mv.filter { it.second > 0 }; val exp = mv.filter { it.second < 0 }.sumOf { it.second }; val incSum = inc.sumOf { it.second }
            if (saldoQ && !incomeQ) {
                val bal = incSum + exp
                return "${if (bal >= 0) "Ušetřil jsi" else "Utratil jsi víc, než ti přišlo – rozdíl"}$per **${CzNum.format(kotlin.math.abs(bal))} Kč**: příjmy ${CzNum.format(incSum)} Kč, výdaje ${CzNum.format(exp)} Kč." +
                    (if (incSum > 0) " Utratil jsi ${Math.round(-exp / incSum * 100)} % příjmů." else "") + "\n_Spočítáno z tabulky výpisu, bez modelu (převody mezi vlastními účty se počítají jako pohyb)._"
            }
            if (inc.isEmpty()) return "Ve výpisu$per nejsou žádné příjmy (kladné částky)."
            val top = inc.groupBy { merchantKey(it.first).ifBlank { it.first.take(30) } }.map { (_, v) -> v.first().first.take(40) to v.sumOf { it.second } }.sortedByDescending { it.second }.take(3)
            return "Příjmy$per: **${CzNum.format(incSum)} Kč** (${plat(inc.size)}). Největší: " + top.joinToString(" · ") { "${it.first} ${CzNum.format(it.second)} Kč" } + ".\n_Spočítáno z tabulky výpisu, bez modelu._"
        }
        val amtI = tt.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }
        val descI = tt.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("popis") }.takeIf { it >= 0 } ?: 3
        val spent = tt.rows.mapNotNull { r -> r.getOrNull(amtI)?.let { CzNum.parse(it, false) }?.takeIf { it < 0 }?.let { Triple(r.getOrElse(0) { "" }, r.getOrElse(descI) { "" }, it) } }
        // „jaká byla největší platba?“, „co jsem nejvíc zaplatil v říjnu“ → největší JEDNOTLIVÉ platby
        if (biggestQ) {
            if (spent.isEmpty()) return "Ve výpisu$per nejsou žádné výdaje."
            return "Největší platby$per:\n" + spent.sortedBy { it.third }.take(5).joinToString("\n") { (d, desc, v) ->
                "• ${CzNum.format(v)} Kč – ${desc.take(50).ifBlank { "bez popisu" }} (${d.split('-').let { p -> if (p.size == 3) "${p[2].toInt()}. ${p[1].toInt()}." else d }})" } + "\n_Z tabulky výpisu, bez modelu._"
        }
        // „kolik jsem utratil v Lidlu / za Netflix / u Alzy“ → platby, jejichž popis obsahuje toho obchodníka (přednost před kategorií)
        val stop = setOf("mesic", "mesici", "celkem", "dohromady", "jidlo", "jidla", "potraviny", "obchode", "obchodech", "kartou", "hotovost", "tom", "tomhle", "tohle", "to", "ten", "tento", "tenhle", "co", "nej", "cem", "mne", "mi", "me", "sebe")
        val cand = Regex("\\b(v|ve|u|za|na|do|pro|od)\\s+(\\p{L}{3,})").findAll(MONTH_RX.fold(q) { acc, rx -> rx.replace(acc, " ") }).map { it.groupValues[2] }.filter { it !in stop }.toList()
        val folded by lazy { spent.map { cz.axiom.vega.base.Text.fold(it.second).lowercase() } }  // jednou pro všechny kandidáty, ne na každý řádek
        for (w in cand) {
            val stem = if (w.length >= 5) w.take(maxOf(4, w.length - 2)) else w
            val rx = Regex("\\b" + Regex.escape(stem))
            val hit = spent.filterIndexed { i, _ -> folded[i].contains(stem) && rx.containsMatchIn(folded[i]) }
            // značka („za Netflix“, „v Lidlu“) = konkrétní obchodník; obecné slovo („za jídlo“, „za nájem“) = kategorie
            if (hit.isNotEmpty() && GENERIC.none { w.startsWith(it) }) {
                val name = hit.first().second.let { d -> Regex("(?i)\\b" + Regex.escape(stem) + "\\S*").find(cz.axiom.vega.base.Text.fold(d))?.let { m -> d.substring(m.range.first, minOf(d.length, m.range.last + 1)) } ?: w }
                return "U „$name“ jsi$per utratil **${CzNum.format(hit.sumOf { it.third })} Kč** (${plat(hit.size)}): " + hit.take(6).joinToString(", ") { (d, _, v) -> "${d.split('-').let { p -> if (p.size == 3) "${p[2].toInt()}. ${p[1].toInt()}." else d }} ${CzNum.format(v)}" } +
                    (if (hit.size > 6) " …" else "") + ".\n_Hledáno v popisu plateb, bez modelu._"
            }
        }
        val cats = categories(tt)
        // „za co nejvíc utrácím“ i „za co utrácím nejvíc“ (slovosled návrhu pod polem)
        if (Regex("(za|na) co\\b.{0,24}\\b(nejvic|nejvice)\\b|nejvetsi vydaj|kde\\b.{0,24}\\bnejvic").containsMatchIn(q)) {
            if (cats.rows.isEmpty()) return "Ve výpisu$per nejsou žádné výdaje – jen příjmy."
            return "Nejvíc utrácíš$per za: " + cats.rows.take(3).joinToString(" · ") { "**${it[0]}** ${it[1]} Kč (${it[3]})" } + ".\n_${cats.notes.first()}_"
        }
        val stripped = MONTH_RX.fold(q) { acc, rx -> rx.replace(acc, " ") }.replace(Regex("(minul\\w*|predchozi|tento|tenhle|tomto|tomhle|letosni)\\s+mesic\\w*"), " ")
        val after = Regex("\\bza\\s+(.+)$").find(stripped)?.groupValues?.get(1)?.trim()?.takeIf { a -> a.any { it.isLetter() } }
        val word = after ?: stripped
        val want = SPEND_WORDS.filter { it.first.containsMatchIn(word) }.flatMap { it.second }.distinct()
        if (want.isEmpty()) {
            // „kolik jsem (celkem) utratil (v říjnu)?“ → všechny výdaje; „za ponožky“ (neznámá věc) → null, rozhodne model
            if (!Regex("utrat|utrac|vydaj|vydal|zaplatil|platil|dal[a]? jsem").containsMatchIn(q)) return null  // „kolik stojí 3 kg jablek“ není dotaz na výpis
            if (!after.isNullOrBlank() && !Regex("^(celkem|dohromady|vsechno|vse|ten mesic|mesic|obdobi|to)\\W*$").matches(after)) return null
            val ai = tt.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).lowercase().startsWith("castk") }
            val am = tt.rows.mapNotNull { it.getOrNull(ai)?.let { v -> CzNum.parse(v, false) } }
            val ex = am.filter { it < 0 }; val inc = am.filter { it > 0 }.sum()
            if (ex.isEmpty()) return "Ve výpisu$per nejsou žádné výdaje – jen příjmy ${CzNum.format(inc)} Kč."
            return "Celkem jsi$per utratil **${CzNum.format(ex.sum())} Kč** (${plat(ex.size)}); příjmy ${CzNum.format(inc)} Kč, saldo ${CzNum.format(inc + ex.sum())} Kč." +
                (cats.rows.firstOrNull()?.let { "\nNejvíc: ${it[0].lowercase()} ${it[1]} Kč (${it[3]})." } ?: "") + "\n_Spočítáno z tabulky výpisu, bez modelu._"
        }
        val hit = cats.rows.filter { it[0] in want }
        if (hit.isEmpty()) return "Ve výpisu$per jsem v kategorii ${want.joinToString(" ani ") { "„$it“" }} nenašel žádnou platbu. (Kategorie určuji podle obchodníka v popisu – co nepoznám, je v „Ostatní“.)"
        val sum = hit.sumOf { CzNum.parse(it[1], false) ?: 0.0 }; val n = hit.sumOf { it[2].toInt() }
        return "Za ${want.joinToString(" a ") { it.lowercase() }}$per jsi utratil **${CzNum.format(sum)} Kč** (${plat(n)}" +
            (if (hit.size > 1) ": " + hit.joinToString(", ") { "${it[0].lowercase()} ${it[1]} Kč" } else "") + ").\n_Podle obchodníka v popisu, bez modelu – část plateb může být v „Ostatní“._"
    }
    private val GENERIC = listOf("jidl", "potravin", "bydlen", "najem", "najm", "energi", "elektri", "plyn", "vod", "benzin", "naft", "palivo", "doprav", "restaura", "obed", "kafe", "kav",
        "zdravi", "lek", "drogeri", "obleceni", "nakup", "telefon", "mobil", "internet", "predplat", "pojist", "det", "skol", "volny", "zabav", "kultur", "sport", "dovolen", "hotovost", "uver", "hypotek", "dan", "urad", "poplat")
    private val MONTH_NAMES = listOf("leden", "únor", "březen", "duben", "květen", "červen", "červenec", "srpen", "září", "říjen", "listopad", "prosinec")
    /** Český název měsíce ve všech pádech (bez diakritiky); červen × červenec rozlišeny celým tvarem. */
    private val MONTH_RX = listOf("led(en|na|nu|nem)", "unor(a|u|em)?", "brez(en|na|nu|nem)", "dub(en|na|nu|nem)", "kvet(en|na|nu|nem)", "cerv(en|na|nu|nem)",
        "cervene?c(e|i|em)?", "srp(en|na|nu|nem)", "zari", "rij(en|na|nu|nem)", "listopad(u|em)?", "prosin(ec|ce|ci|cem)").map { Regex("\\b$it\\b") }

    /**
     * Výpis z Excelu (řádky prvního listu) → CSV se středníkem. Sloupce s datem (hlavička „datum/date/splatnost…“) nesou
     * pořadové číslo Excelu (46328 = 2. 11. 2026) → převést na d.m.rrrr; čísla zůstávají s desetinnou tečkou.
     */
    fun fromXlsxRows(rows: List<List<String>>): PdfConversion? {
        val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
        val hi = nonEmpty.indexOfFirst { r -> r.any { c -> f(c).let { h -> dateNames.any { h.contains(f(it)) } } } }.takeIf { it >= 0 } ?: return null
        val header = nonEmpty[hi]
        val dateCols = header.indices.filter { i -> f(header[i]).let { h -> dateNames.any { h.contains(f(it)) } } }.toSet()
        fun serial(v: String): String { val n = v.toDoubleOrNull() ?: return v; if (n !in 20000.0..80000.0) return v
            return java.time.LocalDate.of(1899, 12, 30).plusDays(n.toLong()).let { "${it.dayOfMonth}.${it.monthValue}.${it.year}" } }
        fun q(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val body = nonEmpty.drop(hi + 1).map { r -> List(header.size) { i -> r.getOrElse(i) { "" }.let { if (i in dateCols) serial(it) else it } } }
        if (body.size < 1) return null
        val csv = (listOf(header) + body).joinToString("\n") { r -> r.joinToString(";") { q(it) } }
        return PdfConversion(csv, body.size, listOf("Převedeno z Excelu (první list, ${cz.axiom.vega.base.Text.plural(body.size, "řádek", "řádky", "řádků")}) – data převedena z čísel Excelu na datum."))
    }

    /** Výpis z PDF převedený na CSV „Datum;Částka;Popis“ (+ poznámky); null = v textu nejsou rozpoznatelné pohyby. */
    data class PdfConversion(val csv: String, val rows: Int, val notes: List<String>)

    /**
     * PDF výpis (text z PdfBox, řazený podle pozice): řádek = datum na začátku … částka na konci. Když řádek končí DVĚMA
     * čísly (pohyb a průběžný zůstatek), rozhodne návaznost zůstatků – bez ní by se jako částka sečetl zůstatek.
     * Částky bez znaménka a bez návaznosti → nevím, co je příjem a výdaj → null (raději nic než špatně).
     */
    fun fromPdfText(text: String): PdfConversion? {
        val num = "[-−+]?\\(?\\d{1,3}(?:[ \u00a0.]\\d{3})*(?:,\\d{2})\\)?(?:\\s*(?:CZK|Kč|EUR|€))?(?:\\s*(?:CR|DR))?"
        // datum i bez roku („02.11.“) – rok ze záhlaví výpisu („Období 01.11.2026 – 30.11.2026“), jinak žádný převod
        val rx = Regex("^\\s*(\\d{1,2}\\.\\s?\\d{1,2}\\.(?:\\s?(?:\\d{4}|\\d{2}))?)\\s+(.*?)\\s+($num)(?:\\s+($num))?\\s*$")
        val year = Regex("(?<!\\d)(20\\d{2})(?!\\d)").find(text)?.groupValues?.get(1)
        data class L(val date: String, var desc: String, val a: Double, val b: Double?, val signed: Boolean)
        val ls = ArrayList<L>()
        for (line in text.lines()) {
            val m = rx.find(line)
            if (m == null) {
                // pokračování popisu na dalším řádku (bez data a bez částky) – připojit k poslednímu pohybu
                val t = line.trim()
                if (ls.isNotEmpty() && t.length in 2..80 && !Regex("\\d{1,2}\\.\\s?\\d{1,2}\\.").containsMatchIn(t) && !Regex("(?i)strana|celkem|zůstatek|zustatek|obrat|součet|soucet|period|období|obdobi").containsMatchIn(t)
                    && !Regex("\\d{1,3}(?:[ .]\\d{3})*,\\d{2}").containsMatchIn(t)) ls.last().desc = (ls.last().desc + " " + t).take(300)
                continue
            }
            val raw = m.groupValues[1].trim()
            val ds = if (!raw.endsWith(".")) raw else if (year != null) raw + year else continue
            val d = CzNum.date(ds) ?: continue
            val a = CzNum.parse(m.groupValues[3]) ?: continue
            val b = m.groupValues[4].takeIf { it.isNotBlank() }?.let { CzNum.parse(it) }
            val desc = m.groupValues[2].replace(Regex("\\s+"), " ").trim()
            ls += L(d, desc, a, b, Regex("^[-−+(]|CR|DR").containsMatchIn(m.groupValues[3].trim()) || m.groupValues[3].trim().endsWith("-"))
        }
        if (ls.size < 2) return null
        val notes = ArrayList<String>()
        // dvě čísla na konci: [pohyb, zůstatek] – ověřit návaznost zůstatků (bal[i] = bal[i-1] + pohyb[i])
        val withBal = ls.count { it.b != null } >= ls.size * 0.8
        val amounts: List<Double> = if (withBal) {
            val pairs = ls.filter { it.b != null }
            val ok = (1 until pairs.size).count { i -> kotlin.math.abs(pairs[i - 1].b!! + pairs[i].a - pairs[i].b!!) < 0.011 }
            if (pairs.size >= 3 && ok < (pairs.size - 1) * 0.8) {
                // možná je pořadí obrácené (zůstatek, pohyb) nebo jsou pohyby bez znaménka – zkusit znaménko z rozdílu zůstatků
                val diffs = (1 until pairs.size).map { i -> pairs[i].b!! - pairs[i - 1].b!! }
                val unsignedMatch = (1 until pairs.size).count { i -> kotlin.math.abs(kotlin.math.abs(diffs[i - 1]) - kotlin.math.abs(pairs[i].a)) < 0.011 }
                if (unsignedMatch < (pairs.size - 1) * 0.8) return null
                notes += "Znaménko pohybů jsem určil z průběžného zůstatku (výpis píše částky bez +/−)."
                listOf(if (pairs[0].signed) pairs[0].a else return null) + diffs
            } else { notes += "Kontrola proti zůstatku v PDF: ${ok}/${pairs.size - 1} návazností sedí ✓"; pairs.map { it.a } }
        } else {
            if (ls.none { it.signed || it.a < 0 }) return null // samé kladné částky bez znaménka a bez zůstatku → nevím, co je výdaj
            ls.map { it.a }
        }
        val src = if (withBal) ls.filter { it.b != null } else ls
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = "Datum;Částka;Popis\n" + src.mapIndexed { i, l -> "${l.date};${String.format(java.util.Locale.ROOT, "%.2f", amounts[i])};${q(l.desc)}" }.joinToString("\n")
        notes += "Převedeno z PDF (${cz.axiom.vega.base.Text.plural(src.size, "pohyb", "pohyby", "pohybů")}) – PDF nemá pevnou strukturu, zkontroluj prosím pár řádků proti výpisu."
        return PdfConversion(csv, src.size, notes)
    }

    /**
     * Pohyby, které jsou ve VÍCE souborech zároveň (překrývající se exporty: 1.–15. a 10.–30.) – stejné datum, částka a popis.
     * Vrací (počet zdvojených pohybů, jejich součet) – stejná platba dvakrát v JEDNOM souboru se nepočítá (dvě kávy jsou dvě kávy).
     */
    fun crossDuplicates(files: List<List<List<String>>>): Pair<Int, Double> {
        fun key(r: List<String>) = listOf(r.getOrElse(0) { "" }, r.getOrElse(1) { "" }, cz.axiom.vega.base.Text.fold(r.getOrElse(3) { "" }).lowercase().replace(Regex("\\s+"), " ").trim()).joinToString("|")
        val seen = HashMap<String, Int>() // klíč → kolikrát už v předchozích souborech
        var n = 0; var sum = 0.0
        files.forEach { rows ->
            val here = rows.groupingBy { key(it) }.eachCount()
            here.forEach { (k, c) -> val before = seen[k] ?: 0; if (before > 0) { val dup = minOf(before, c); n += dup; sum += dup * (k.split('|')[1].toDoubleOrNull() ?: 0.0) } }
            here.forEach { (k, c) -> seen[k] = maxOf(seen[k] ?: 0, c) }
        }
        return n to sum
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
        val di = t.col("Datum"); val ai = t.col("Částka"); val ci = t.headers.indexOf("Měna")
        val multi = ci >= 0 && t.rows.map { it.getOrNull(ci) }.distinct().size > 1
        val g = t.rows.groupBy { it[di].take(7) + if (multi) " " + it[ci] else "" }.toSortedMap()
        val rows = g.map { (m, rs) ->
            val a = rs.mapNotNull { it[ai].toDoubleOrNull() }
            val (month, cur) = if (multi) m.substringBefore(' ') to m.substringAfter(' ') else m to null
            listOfNotNull(month, cur, CzNum.format(a.filter { it > 0 }.sum()), CzNum.format(a.filter { it < 0 }.sum()), CzNum.format(a.sum()), rs.size.toString())
        }
        return Value.Table(if (multi) listOf("Měsíc", "Měna", "Příjmy", "Výdaje", "Saldo", "Pohybů") else listOf("Měsíc", "Příjmy", "Výdaje", "Saldo", "Pohybů"), rows, name = "měsíce")
    }
}
