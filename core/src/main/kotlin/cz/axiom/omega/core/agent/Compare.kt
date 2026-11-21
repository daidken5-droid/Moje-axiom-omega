package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx

/**
 * Δ SROVNÁVAČ BEZ MOZKU – „Co je větší, Praha nebo Brno?“, „Kdo byl starší, Masaryk nebo Beneš?“,
 * „Porovnej Vltavu a Labe“, „Rozdíl mezi krokodýlem a aligátorem“.
 *
 * Najde OBĚ hesla v offline Wikipedii, vytáhne z jejich úvodu čísla (obyvatelé, rozloha, výška, délka,
 * narození, vznik), postaví z nich tabulku a u otázky „co je větší / starší / delší…“ rovnou SPOČÍTÁ verdikt.
 * Žádný jazykový model: čísla jsou přesně ta z encyklopedie, výpočet je deterministický, odpověď za milisekundy.
 * Když chybí některé heslo nebo údaj potřebný pro verdikt, mlčí a otázku převezme mozek.
 */
object Compare {

    private const val SIDE = "(.{2,40}?)"
    private val compareRx = rx("^(?:porovnej|srovnej|porovnani|srovnani|jaky je rozdil mezi|rozdil mezi|v cem se lisi|cim se lisi) $SIDE (?:a|s|se|vs\\.?|versus|nebo|proti) $SIDE$")
    private val whichRx = rx("^(?:kdo|co|ktery|ktera|ktere|kterej|kde)?(?: \\p{L}+)?(?: (?:je|byl|byla|bylo|jsou|ma|mel|mela))? ?(starsi|mladsi|vetsi|mensi|vyssi|nizsi|delsi|kratsi|lidnatejsi|rozlehlejsi)[,:]? $SIDE (?:nebo|ci|vs\\.?|versus|a) $SIDE$")
    // „je Praha větší než Brno?“
    private val thanRx = rx("^(?:je|byl|byla|bylo|ma|mel|mela)? ?$SIDE (starsi|mladsi|vetsi|mensi|vyssi|nizsi|delsi|kratsi|lidnatejsi|rozlehlejsi) nez $SIDE$")

    /** Údaje vytažené z úvodu hesla. */
    data class Facts(
        val title: String, val lead: String,
        val population: Double? = null, val area: Double? = null, val height: Double? = null, val length: Double? = null,
        val born: Int? = null, val died: Int? = null, val founded: Int? = null,
    )

    fun answer(prompt: String, k: KnowledgeSource?): Instant.Answer? {
        if (k == null) return null
        val sig = Pavucina.of(prompt)
        val f = sig.folded.trimEnd('?', '.', '!', ' ').replace(Regex("\\s+"), " ")
        val (a, b, metric) = compareRx.find(f)?.let { Triple(it.groupValues[1], it.groupValues[2], null as String?) }
            ?: whichRx.find(f)?.let { Triple(it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
            ?: thanRx.find(f)?.let { Triple(it.groupValues[1], it.groupValues[3], it.groupValues[2]) }
            ?: return null
        if (a.split(' ').size > 4 || b.split(' ').size > 4 || TextUtil.fold(a) == TextUtil.fold(b)) return null
        // „výhody a nevýhody“, „cenu a výkon“ – obecná slova bez hesla → mozek (ověří se níž: obě hesla musí existovat)
        val x = facts(article(a, k) ?: return null)
        val y = facts(article(b, k) ?: return null)
        if (x.title == y.title) return null
        val table = table(x, y)
        val verdict = metric?.let { verdict(it, x, y) ?: return null }
        if (metric == null && table == null) return null
        val text = buildString {
            if (verdict != null) append(verdict).append("\n\n")
            if (table != null) append(table).append("\n\n")
            append("• **${x.title}** – ${x.lead}\n• **${y.title}** – ${y.lead}")
            append("\n\n— *offline: Wikipedie – ${x.title}, ${y.title}*")
        }
        return Instant.Answer(text, "Δ srovnání · Wikipedie", confidence = 88)
    }

    // ------------------------------------------------------------------ hesla

    /** Pár běžných pádových koncovek → 1. pád („Prahu“ → „Praha“, „Brnem“ → „Brno“, „krokodýlem“ → „krokodýl“). */
    fun nominatives(s: String): List<String> {
        val w = s.trim()
        val out = linkedSetOf(w)
        val last = w.substringAfterLast(' '); val head = w.dropLast(last.length)
        fun alt(stem: String, vararg ends: String) = ends.forEach { out += head + stem + it }
        when {
            last.length < 4 -> {}
            last.endsWith("em") -> alt(last.dropLast(2), "", "o", "a")
            last.endsWith("ou") -> alt(last.dropLast(2), "a")
            last.endsWith("u") -> alt(last.dropLast(1), "a", "", "o")
            last.endsWith("y") -> alt(last.dropLast(1), "a")
            last.endsWith("e") -> alt(last.dropLast(1), "a", "")
            last.endsWith("a") -> alt(last.dropLast(1), "")
            last.endsWith("i") -> alt(last.dropLast(1), "e", "")
        }
        return out.toList().take(4)
    }

    private fun article(side: String, k: KnowledgeSource): Snippet? {
        val subject = side.trim().removePrefix("mestem ").removePrefix("mesto ").removePrefix("reka ").removePrefix("rekou ")
        for (n in nominatives(subject)) {
            val hits = runCatching { k.search(n, 5) }.getOrDefault(emptyList())
                .filter { it.title.startsWith("Wikipedie:") && WikiReflex.titleMatches(it.title, n) }
            // přednost má heslo bez rozlišení („Praha“ před „Praha (album)“)
            hits.minByOrNull { if (it.title.contains('(')) 1 else 0 }?.let { return it }
        }
        // jen příjmení („Masaryk nebo Beneš“) → jediná osoba v top výsledcích, jejíž heslo končí tímto slovem
        if (' ' !in subject && subject.length >= 4) for (n in nominatives(subject)) {
            val people = runCatching { k.search(n, 5) }.getOrDefault(emptyList()).filter {
                it.title.startsWith("Wikipedie:") && WikiReflex.cleanTitle(it.title).substringAfterLast(' ') == n && Sentinel.lifeDates(it.text)?.first != null
            }.distinctBy { it.title }
            if (people.size == 1) return people[0]
        }
        return null
    }

    // ------------------------------------------------------------------ čísla z úvodu hesla

    private const val NUM = "(\\d{1,3}(?:[  .]\\d{3})+|\\d+(?:,\\d+)?)"
    private val popRx = Regex("$NUM\\s*(tis\\.|tisíc|milion\\w*|mil\\.)?\\s*(?:stálých\\s+)?obyvatel", RegexOption.IGNORE_CASE)
    private val areaRx = Regex("$NUM\\s*km(?:²|2\\b)", RegexOption.IGNORE_CASE)
    private val heightRx = Regex("(?:výšk\\w*|vysok\\w*|měří)\\D{0,25}?$NUM\\s*m\\b(?!\\s*n)", RegexOption.IGNORE_CASE)
    private val altRx = Regex("$NUM\\s*m\\s*n\\.\\s*m\\.", RegexOption.IGNORE_CASE)
    private val lengthRx = Regex("(?:délk\\w*|dlouh\\w*|měří)\\D{0,25}?$NUM\\s*km\\b(?!²|2)", RegexOption.IGNORE_CASE)

    private fun num(s: String, mult: String? = null): Double? {
        val base = s.replace(Regex("[  .]"), "").replace(',', '.').toDoubleOrNull() ?: return null
        return base * when {
            mult == null || mult.isEmpty() -> 1.0
            mult.startsWith("tis") -> 1_000.0
            else -> 1_000_000.0
        }
    }

    fun facts(s: Snippet): Facts {
        val title = s.title.removePrefix("Wikipedie:").trim().replace(Regex("\\s*\\(.*\\)"), "")
        val head = s.text.take(1500)
        val lead = Instant.firstSentences(s.text, 1, 260) ?: s.text.take(200)
        val life = Sentinel.lifeDates(s.text)
        return Facts(
            title = title, lead = lead,
            population = popRx.find(head)?.let { num(it.groupValues[1], it.groupValues[2]) },
            area = areaRx.find(head)?.let { num(it.groupValues[1]) },
            height = (heightRx.find(head) ?: altRx.find(head))?.let { num(it.groupValues[1]) },
            length = lengthRx.find(head)?.let { num(it.groupValues[1]) },
            born = life?.first?.second?.year?.toIntOrNull(), died = life?.second?.second?.year?.toIntOrNull(),
            founded = if (life == null) Sentinel.foundingYear(s.text)?.toIntOrNull() else null,
        )
    }

    // ------------------------------------------------------------------ výstup

    private fun fmt(v: Double, unit: String = ""): String {
        val L = java.util.Locale.ROOT // na telefonu s češtinou by „%,d“ dal jiné oddělovače
        val s = if (v == Math.floor(v) && v < 1e15) String.format(L, "%,d", v.toLong()).replace(',', ' ')
            else String.format(L, "%,.1f", v).replace(',', ' ').replace('.', ',')
        return if (unit.isEmpty()) s else "$s $unit"
    }

    private fun table(x: Facts, y: Facts): String? {
        val rows = listOfNotNull(
            row("Obyvatel", x.population, y.population) { fmt(it) },
            row("Rozloha", x.area, y.area) { fmt(it, "km²") },
            row("Výška", x.height, y.height) { fmt(it, "m") },
            row("Délka", x.length, y.length) { fmt(it, "km") },
            row("Narození", x.born?.toDouble(), y.born?.toDouble()) { it.toInt().toString() },
            row("Úmrtí", x.died?.toDouble(), y.died?.toDouble()) { it.toInt().toString() },
            row("Vznik", x.founded?.toDouble(), y.founded?.toDouble()) { it.toInt().toString() },
        )
        if (rows.isEmpty()) return null
        return "| | ${x.title} | ${y.title} |\n|---|---|---|\n" + rows.joinToString("\n")
    }

    private fun row(label: String, a: Double?, b: Double?, show: (Double) -> String): String? =
        if (a == null && b == null) null else "| $label | ${a?.let(show) ?: "–"} | ${b?.let(show) ?: "–"} |"

    /** Spočítaný verdikt („Praha je větší – 496 km² proti 230 km² (2,2×)“). */
    private fun verdict(metric: String, x: Facts, y: Facts): String? {
        fun pick(a: Double?, b: Double?, bigger: Boolean, word: String, unit: String, show: (Double) -> String = { fmt(it, unit) }): String? {
            if (a == null || b == null || a == b) return null
            val (w, l) = if ((a > b) == bigger) (x.title to a) to (y.title to b) else (y.title to b) to (x.title to a)
            val ratio = if (l.second > 0 && w.second / l.second >= 1.15) " (${fmt(Math.round(w.second / l.second * 10) / 10.0)}×)" else ""
            return "**${w.first}** je $word – ${show(w.second)} proti ${show(l.second)}" + (if (unit.isNotEmpty()) ratio else "") + "."
        }
        fun age(older: Boolean): String? {
            val a = (x.born ?: x.founded)?.toDouble(); val b = (y.born ?: y.founded)?.toDouble()
            if (a == null || b == null || a == b) return null
            val xFirst = a < b
            val (w, l) = if (xFirst == older) (x.title to a) to (y.title to b) else (y.title to b) to (x.title to a)
            val what = if (x.born != null && y.born != null) "narození" else "vznik"
            return "**${w.first}** je ${if (older) "starší" else "mladší"} – $what ${w.second.toInt()} proti ${l.second.toInt()} (rozdíl ${Math.abs(a - b).toInt()} let)."
        }
        return when (metric) {
            "vetsi", "mensi" -> pick(x.area, y.area, metric == "vetsi", if (metric == "vetsi") "větší" else "menší", "km²")
                ?: pick(x.population, y.population, metric == "vetsi", if (metric == "vetsi") "větší (obyvatel)" else "menší (obyvatel)", "") { fmt(it) }
            "rozlehlejsi" -> pick(x.area, y.area, true, "rozlehlejší", "km²")
            "lidnatejsi" -> pick(x.population, y.population, true, "lidnatější", "") { fmt(it) + " obyvatel" }
            "vyssi", "nizsi" -> pick(x.height, y.height, metric == "vyssi", if (metric == "vyssi") "vyšší" else "nižší", "m")
            "delsi", "kratsi" -> pick(x.length, y.length, metric == "delsi", if (metric == "delsi") "delší" else "kratší", "km")
            "starsi", "mladsi" -> age(metric == "starsi")
            else -> null
        }
    }
}
