package cz.axiom.vega.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.zip.CRC32

/** Inspektor JSON: cesta ($.a.b[0], a.*.name), filtr pole objektů, převod na tabulku. */
object JsonTool {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String): Pair<JsonElement?, String?> = try {
        lenient.parseToJsonElement(text) to null
    } catch (e: Exception) {
        // poloha chyby pro uživatele (řádek/sloupec)
        val m = Regex("at (?:path: .*? )?(?:offset|index) (\\d+)").find(e.message ?: "")
        val off = m?.groupValues?.get(1)?.toIntOrNull()
        val where = off?.let { o -> val before = text.take(o); " na řádku ${before.count { it == '\n' } + 1}, sloupci ${o - before.lastIndexOf('\n')}" } ?: ""
        null to "Neplatný JSON$where: ${e.message?.lineSequence()?.firstOrNull()?.take(160)}"
    }

    /** Cesta: segmenty oddělené tečkou, [n] index, * všechny položky. */
    fun select(root: JsonElement, path: String): List<JsonElement> {
        val segs = path.trim().removePrefix("$").removePrefix(".").replace(Regex("\\[(\\d+|\\*)]"), ".$1").split('.').filter { it.isNotEmpty() }
        var cur = listOf(root)
        for (s in segs) cur = cur.flatMap { e ->
            when {
                s == "*" -> when (e) { is JsonArray -> e.toList(); is JsonObject -> e.values.toList(); else -> emptyList() }
                e is JsonArray && s.toIntOrNull() != null -> listOfNotNull(e.getOrNull(s.toInt()))
                e is JsonObject -> listOfNotNull(e[s])
                e is JsonArray -> e.mapNotNull { (it as? JsonObject)?.get(s) }
                else -> emptyList()
            }
        }
        return cur
    }

    private fun flat(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull ?: ""
        else -> e.toString()
    }

    /** Pole objektů → tabulka (sloupce = sjednocení klíčů v pořadí výskytu). */
    fun toTable(e: JsonElement): Value.Table {
        val arr = when (e) { is JsonArray -> e; is JsonObject -> e.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(listOf(e)); else -> JsonArray(listOf(e)) }
        val keys = LinkedHashSet<String>()
        arr.forEach { (it as? JsonObject)?.keys?.let(keys::addAll) }
        if (keys.isEmpty()) return Value.Table(listOf("hodnota"), arr.map { listOf(flat(it)) })
        return Value.Table(keys.toList(), arr.map { o -> keys.map { k -> flat((o as? JsonObject)?.get(k)) } })
    }
}

/** Filtr tabulky: „sloupec op hodnota“, op = = != > < >= <= ~ (obsahuje). Čísla v českém zápisu. */
object TableFilter {
    private val rx = Regex("^\\s*(.+?)\\s*(>=|<=|!=|=|>|<|~)\\s*(.*?)\\s*$")

    fun apply(t: Value.Table, expr: String): Value.Table {
        if (expr.isBlank()) return t
        val m = rx.find(expr) ?: throw IllegalArgumentException("Filtr ve tvaru „sloupec > 100“ nebo „Popis ~ nájem“")
        val (col, op, raw) = m.destructured
        val ci = t.headers.indexOfFirst { cz.axiom.vega.base.Text.fold(it).equals(cz.axiom.vega.base.Text.fold(col), true) }
        require(ci >= 0) { "Sloupec „$col“ neexistuje (máš: ${t.headers.joinToString()})" }
        val num = CzNum.parse(raw)
        val rows = t.rows.filter { r ->
            val v = r.getOrElse(ci) { "" }
            val n = CzNum.parse(v, true) ?: v.toDoubleOrNull()
            when (op) {
                "~" -> cz.axiom.vega.base.Text.fold(v).contains(cz.axiom.vega.base.Text.fold(raw), true)
                "=" -> if (num != null && n != null) n == num else v.equals(raw, true)
                "!=" -> if (num != null && n != null) n != num else !v.equals(raw, true)
                else -> if (num == null || n == null) false else when (op) { ">" -> n > num; "<" -> n < num; ">=" -> n >= num; else -> n <= num }
            }
        }
        return t.copy(rows = rows, notes = listOf("Filtr $col $op $raw: ${rows.size} z ${t.rows.size} řádků"))
    }

    fun sum(t: Value.Table, col: String, by: String?): Value.Table {
        val ci = t.col(col); require(ci >= 0) { "Sloupec „$col“ neexistuje" }
        val bi = by?.let { t.col(it) } ?: -1
        val groups = if (bi >= 0) t.rows.groupBy { it.getOrElse(bi) { "" } } else mapOf("celkem" to t.rows)
        return Value.Table(listOf(by ?: "Skupina", "Součet $col", "Počet"), groups.map { (k, rs) ->
            listOf(k, CzNum.format(rs.sumOf { CzNum.parse(it.getOrElse(ci) { "" }) ?: it.getOrElse(ci) { "" }.toDoubleOrNull() ?: 0.0 }), rs.size.toString())
        })
    }
}

/** Kontrolní součty (MD5 jen pro porovnání se starými výpisy, ne pro bezpečnost). */
object Checksum {
    val ALGS = listOf("SHA-256", "SHA-1", "SHA-512", "MD5", "CRC32")

    fun of(bytes: ByteArray, alg: String): String = if (alg == "CRC32") CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')
    else MessageDigest.getInstance(alg).digest(bytes).joinToString("") { "%02x".format(it) }

    fun table(bytes: ByteArray, expect: String?): Value.Table {
        val e = expect?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rows = ALGS.map { a -> val h = of(bytes, a); listOf(a, h, if (e == null) "" else if (h == e) "✓ shoda" else "") }
        val notes = buildList {
            add("${bytes.size} bajtů")
            if (e != null) add(if (rows.any { it[2].isNotEmpty() }) "Očekávaný otisk SEDÍ (${rows.first { it[2].isNotEmpty() }[0]})." else "Očekávaný otisk NESEDÍ s žádným algoritmem – soubor se liší.")
        }
        return Value.Table(listOf("Algoritmus", "Otisk", "Ověření"), rows, notes)
    }
}

/** Hledání v logu + rozbor pádu (výjimka, příčina, rámce z vlastního kódu). */
object LogTool {
    fun search(text: String, query: String, regex: Boolean, context: Int, level: String?): Value.Table {
        val lines = text.lines()
        val rx = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
        val lv = level?.uppercase()?.takeIf { it.isNotBlank() }
        val hits = lines.indices.filter { i ->
            val l = lines[i]
            (query.isBlank() || (rx?.containsMatchIn(l) ?: l.contains(query, true))) && (lv == null || Regex("\\b$lv\\b|\\s${lv.first()}/").containsMatchIn(l))
        }
        val keep = sortedSetOf<Int>()
        hits.forEach { h -> for (j in maxOf(0, h - context)..minOf(lines.size - 1, h + context)) keep += j }
        val hitSet = hits.toSet()
        val rows = keep.map { listOf((it + 1).toString(), if (it in hitSet) "●" else "", lines[it]) }
        return Value.Table(listOf("Řádek", "Shoda", "Text"), rows, listOf("${hits.size} shod v ${lines.size} řádcích"))
    }

    data class Crash(val exception: String, val message: String, val causes: List<String>, val frames: List<String>, val ownFrames: List<String>, val line: Int)

    private val exRx = Regex("((?:[a-zA-Z_$][\\w$]{0,80}\\.){1,30}[A-Z][\\w$]{0,80}(?:Exception|Error|Throwable))(?::\\s*(.*))?")
    private val pyRx = Regex("^(\\w+(?:Error|Exception)):\\s*(.*)$")
    private val frameRx = Regex("^\\s*at\\s+([\\w.$<>]+)\\(([^)]*)\\)")
    private val pyFrame = Regex("^\\s*File \"([^\"]+)\", line (\\d+), in (\\S+)")
    private val jsFrame = Regex("^\\s*at\\s+(?:(\\S+)\\s+)?\\(?([^()\\s]+:\\d+:\\d+)\\)?")

    /** Najde první pád v logu (Java/Kotlin, Python, JS). [ownPkg] = předpona vlastního kódu (zvýrazní se). */
    fun crash(text: String, ownPkg: String? = null): Crash? {
        val lines = text.lines()
        // Python traceback: rámce jsou NAD výjimkou
        val tb = lines.indexOfFirst { it.startsWith("Traceback (most recent call last)") }
        if (tb >= 0) {
            val frames = ArrayList<String>(); var i = tb + 1
            while (i < lines.size && !pyRx.matches(lines[i].trim())) { pyFrame.find(lines[i])?.let { frames += "${it.groupValues[1]}:${it.groupValues[2]} (${it.groupValues[3]})" }; i++ }
            val m = lines.getOrNull(i)?.trim()?.let { pyRx.find(it) }
            return Crash(m?.groupValues?.get(1) ?: "Error", m?.groupValues?.get(2) ?: "", emptyList(), frames.reversed(), frames.reversed().filter { f -> ownPkg == null || f.contains(ownPkg) }, tb + 1)
        }
        val at = lines.indexOfFirst { exRx.containsMatchIn(it.take(4000)) && (it.contains("Exception") || it.contains("Error")) && !it.trimStart().startsWith("at ") }
        if (at < 0) return null
        val m = exRx.find(lines[at].take(4000))!!
        val frames = ArrayList<String>(); val causes = ArrayList<String>()
        var i = at + 1
        while (i < lines.size) {
            val l = lines[i]
            frameRx.find(l)?.let { frames += "${it.groupValues[1]} (${it.groupValues[2]})" }
                ?: jsFrame.find(l)?.takeIf { l.trimStart().startsWith("at ") }?.let { frames += "${it.groupValues[1].ifBlank { "?" }} (${it.groupValues[2]})" }
                ?: if (l.trimStart().startsWith("Caused by:")) causes += l.trim().removePrefix("Caused by:").trim()
                else if (l.trimStart().startsWith("...")) {} else if (l.isBlank() || !l.startsWith(" ") && !l.startsWith("\t")) break else {}
            i++
        }
        val own = frames.filter { f -> ownPkg?.let { f.startsWith(it) } ?: !(f.startsWith("java.") || f.startsWith("android.") || f.startsWith("kotlin.") || f.startsWith("sun.") || f.startsWith("jdk.") || f.startsWith("com.android.") || f.startsWith("androidx.")) }
        return Crash(m.groupValues[1], m.groupValues[2].trim(), causes, frames, own, at + 1)
    }

    fun crashTable(c: Crash): Value.Table = Value.Table(listOf("Co", "Detail"), buildList {
        add(listOf("Výjimka", c.exception)); add(listOf("Zpráva", c.message)); add(listOf("Řádek logu", c.line.toString()))
        c.causes.forEach { add(listOf("Příčina", it)) }
        c.ownFrames.take(5).forEachIndexed { i, f -> add(listOf(if (i == 0) "Místo ve vlastním kódu" else "Volal", f)) }
    }, listOf("Nejhlubší vlastní rámec je nejpravděpodobnější místo opravy." ))
}
