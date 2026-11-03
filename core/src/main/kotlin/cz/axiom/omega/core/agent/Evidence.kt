package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * MAPA DŮKAZŮ – jedinečná vlastnost AVIORu: u každé věty odpovědi ukáže, odkud pochází.
 *
 *  • SPOČÍTÁNO – číslo ve větě spočítal deterministický kód STEP-0 (ne odhad jazykového modelu),
 *  • DOLOŽENO  – věta cituje podklad [n] z offline knihovny / znalostí / webu,
 *  • Z PAMĚTI  – opírá se o to, co si AVIOR pamatuje o uživateli,
 *  • ODHAD     – sám model přiznává nejistotu („pravděpodobně“, „asi“, „odhaduji“…),
 *  • ÚVAHA     – vlastní úvaha modelu bez přímé opory (není to chyba, ale vyplatí se ověřit).
 *
 * Čistě deterministické a lokální (žádné další volání modelu) – vychází ze zásady RANDY
 * „rozlišuj doložená fakta, odvození, předpoklady a neznámé“.
 */
object Evidence {

    /**
     * HYPERFORGE M1-B: klasifikace je HEURISTICKÁ (ne ověření pravdivosti). Za „s oporou“ se počítá jen
     *  • COMPUTED – věta je celá o spočítaném výsledku (číslo + většina obsahových slov z řádku STEP-0),
     *  • SOURCED  – cituje [n], zdroj n EXISTUJE a věta s ním sdílí obsahové slovo.
     * Nikdy: věta s jedním spočítaným číslem a dalšími tvrzeními (PARTIAL), citace neexistujícího / nesouvisejícího
     * zdroje (CITED), paměť o uživateli (MEMORY – osobní kontext, ne fakt).
     */
    enum class Kind(val code: String, val label: String) {
        COMPUTED("c", "spočítáno"), SOURCED("s", "doloženo"), MEMORY("m", "z paměti"), ESTIMATE("e", "odhad"), MODEL("u", "úvaha"),
        PARTIAL("p", "část spočítána"), CITED("x", "citace bez opory"),
    }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    data class Map(val spans: List<Span>) {
        fun count(k: Kind) = spans.count { it.kind == k }
        /** Podíl vět s HEURISTICKOU oporou (celá věta spočítaná, nebo citace existujícího souvisejícího zdroje) 0–100. */
        val grounded: Int get() = if (spans.isEmpty()) 0 else
            spans.count { it.kind == Kind.COMPUTED || it.kind == Kind.SOURCED } * 100 / spans.size
    }

    private val citation = Regex("\\[(\\d{1,2})]")
    private val hedge = Regex("(?iU)\\b(pravděpodobně|nejspíš|asi|možná|odhad\\w*|zřejmě|patrně|přibližně|orientačně|nejsem si jist\\w*|tipuji|domnívám se)\\b")
    private val number = Regex("\\d+(?:[ \\u00a0]\\d{3})*(?:[.,]\\d+)?")

    /**
     * @param facts řádky STEP-0 (spočítané kódem)
     * @param memories texty vzpomínek použitých v odpovědi
     * @param sources texty podkladů v pořadí [1], [2], … (název + úryvek); prázdné = citace nelze ověřit
     */
    fun map(answer: String, facts: List<String>, memories: List<String> = emptyList(), sources: List<String> = emptyList()): Map {
        val factNumbers = facts.flatMap { f -> number.findAll(f).map { norm(it.value) }.filter { it.length >= 2 || it.toDoubleOrNull()?.let { v -> v >= 10 } == true }.toList() }.toSet()
        val factWords = facts.flatMap { content(it) }.toSet()
        val sourceWords = sources.map { content(it).toSet() }
        val memWords = memories.flatMap { TextUtil.words(it, 5) }.toSet()
        val spans = mutableListOf<Span>()
        for ((s, e) in sentences(answer)) {
            val t = answer.substring(s, e)
            if (TextUtil.words(t, 2).size < 3) continue // nadpisy, oddělovače, „JISTOTA“ apod.
            if (t.trimStart().startsWith("JISTOTA") || t.trimStart().startsWith("POUČENÍ")) continue
            val tt = t.trimStart()
            // nadpisy a řádky tabulky bez čísel nejsou tvrzení (záhlaví, popisky)
            if (tt.startsWith("#") || (tt.startsWith("|") && tt.none { it.isDigit() })) continue
            val nums = number.findAll(t).map { norm(it.value) }.filter { it.length >= 2 || it.toDoubleOrNull()?.let { v -> v >= 10 } == true }
            val words = content(t)
            val kind = when {
                nums.any { it in factNumbers } -> {
                    // celá věta = spočítaný výsledek: žádné cizí číslo a většina obsahových slov je z řádku STEP-0
                    val foreignNum = nums.any { it !in factNumbers }
                    val uncovered = words.count { it !in factWords }
                    val covered = uncovered <= 3 || (words.size - uncovered) * 2 >= words.size
                    if (!foreignNum && covered) Kind.COMPUTED else Kind.PARTIAL
                }
                citation.containsMatchIn(t) -> {
                    val refs = citation.findAll(t).map { it.groupValues[1].toInt() }.toList()
                    val ok = sources.isNotEmpty() && refs.all { it in 1..sources.size } &&
                        refs.all { r -> words.any { it in sourceWords[r - 1] } }
                    if (ok) Kind.SOURCED else Kind.CITED
                }
                hedge.containsMatchIn(t) -> Kind.ESTIMATE
                memWords.isNotEmpty() && TextUtil.words(t, 5).count { it in memWords } >= 2 -> Kind.MEMORY
                else -> Kind.MODEL
            }
            spans += Span(s, e, kind)
        }
        return Map(spans)
    }

    /** Obsahová slova (≥ 5 znaků, bez diakritiky, prvních 5 písmen – ohýbání) pro porovnání věty s podkladem. */
    private fun content(t: String): List<String> =
        TextUtil.fold(t).lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }.map { it.take(5) }

    private fun norm(n: String): String {
        val x = n.replace(" ", "").replace("\u00a0", "").replace(',', '.')
        // nuly umazávat jen za desetinnou čárkou („15,50“ = „15,5“), ne z celých čísel („10000“)
        return if ('.' in x) x.trimEnd('0').trimEnd('.') else x
    }

    /** Věty a položky seznamu/tabulky jako rozsahy [start, end) v původním textu. */
    fun sentences(text: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var i = 0
        fun flush(end: Int) {
            var s = start; var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) out += s to e
            start = end
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> flush(i + 1)
                (c == '.' || c == '!' || c == '?') && i + 1 < text.length && text[i + 1] == ' ' &&
                    !(c == '.' && i > 0 && text[i - 1].isDigit() && i + 2 < text.length && text[i + 2].isDigit()) -> flush(i + 1)
            }
            i++
        }
        flush(text.length)
        return out
    }
}
