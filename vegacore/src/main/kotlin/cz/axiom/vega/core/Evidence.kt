package cz.axiom.vega.core

import cz.axiom.omega.core.util.TextUtil

/**
 * PRÁCE S DŮKAZY: kolik NEZÁVISLÝCH linií doložení má každá část tvrzení (datum, událost, motiv).
 * Dva texty, které citují tutéž původní zprávu, jsou JEDNA linie – dokládají šíření, ne pravdivost.
 * Kritéria jsou stejná pro oficiální i alternativní výklad a pověst autora do hodnocení nevstupuje:
 * rozhoduje původ (koho text přebírá), ověřitelnost a datace.
 * Číselnou pravděpodobnost nepočítá – místo ní řekne, jaký údaj chybí a jak by závěr změnil.
 */
object Evidence {
    data class Doc(val name: String, val text: String)
    data class Parsed(val doc: Doc, val origins: Set<String>, val dates: Set<String>, val motive: Boolean, val primary: Boolean, val anonymousOrigin: Boolean, val label: String = "")
    data class Part(val label: String, val status: String, val lines: Int, val support: List<String>)
    data class Assessment(val headline: String, val parts: List<Part>, val direct: List<String>, val inferred: List<String>, val open: List<String>, val missing: String?)

    private fun f(s: String) = TextUtil.fold(s).lowercase()
    private val citeRx = Regex("(?:podle|cituje|odkazuje na|prevzato z|zdroj(?:em)?:?|jak uvedl[ao]?|informoval[ao]?)\\s+(?:anonymni(?:ho)?\\s+)?((?:prispev\\w*|zprav\\w*|clan\\w*|tweet\\w*|video\\w*|post\\w*|blog\\w*|svedect\\w*|vypoved\\w*)[^.;\\n]{0,60})")
    private val urlRx = Regex("https?://[^\\s)\"'>]+")

    fun parse(d: Doc): Parsed {
        val t = f(d.text)
        val origins = LinkedHashSet<String>()
        urlRx.findAll(d.text).forEach { origins += it.value.lowercase().removeSuffix("/").substringBefore("?").substringAfter("://").removePrefix("www.") }
        // původ = kmeny slov (prvních 5 písmen), bez interpunkce a čísel: „příspěvku na fóru Kvasar“ = „příspěvek na fóru Kvasar,“
        citeRx.findAll(t).forEach { m -> origins += m.groupValues[1].replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.length > 3 && it !in STOP }.take(4).joinToString(" ") { it.take(5) } }
        val dates = Regex("\\b(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})\\b").findAll(d.text).map { "%04d-%02d-%02d".format(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.toSet()
        val motive = Regex("motiv|kvuli|protoze|aby |z duvodu|pricin|chteli|zamer").containsMatchIn(t)
        val anon = Regex("anonymn|nepodepsan|neznam\\w* autor").containsMatchIn(t)
        // prvotní doklad: vlastní datovaný záznam bez převzetí od jiného zdroje (úřední záznam, protokol, výpis, fotka s datem)
        val primary = origins.isEmpty() && dates.isNotEmpty() && Regex("zaznam|protokol|vypis|zapis|potvrzen|faktur|smlouv|rozhodnut|fotografi|snimek|registr|uredni|evidenc").containsMatchIn(t)
        val label = citeRx.find(t)?.let { m -> if (t.length == d.text.length) d.text.substring(m.groups[1]!!.range).trim().trimEnd(',', ' ') else m.groupValues[1] }
            ?: urlRx.find(d.text)?.value ?: ""
        return Parsed(d, origins, dates, motive, primary, anon && origins.isNotEmpty(), label.replace(Regex("\\s+\\d+$"), "").replace(Regex("\\s+(došlo|unikla|uvedl\\w*)$"), ""))
    }
    private val STOP = setOf("ktery", "ktera", "ktere", "jako", "tento", "tato", "podle", "uvedl", "uvedla")

    /** Linie = skupina dokumentů se stejným původem (dokument bez citace je sám sobě původem). */
    private fun lineOf(p: Parsed, all: List<Parsed>): String {
        if (p.origins.isEmpty()) return "vlastní:" + p.doc.name
        // stejný původ i při mírně jiném popisu („příspěvek na fóru X“ × „anonymní příspěvek na fóru X“)
        val key = p.origins.first()
        val shared = all.flatMap { it.origins }.firstOrNull { o -> o != key && (o.contains(key) || key.contains(o) || overlap(o, key) >= 2) }
        return "převzato:" + listOfNotNull(key, shared).minByOrNull { it.length }!!
    }
    private fun overlap(a: String, b: String) = a.split(' ').toSet().intersect(b.split(' ').toSet()).count { it.length >= 4 }

    fun assess(docs: List<Doc>): Assessment {
        val ps = docs.map { parse(it) }
        val lines = ps.associateWith { lineOf(it, ps) }
        val allDates = ps.flatMap { it.dates }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val parts = ArrayList<Part>()
        fun grade(label: String, sup: List<Parsed>): Part {
            val ls = sup.map { lines[it]!! }.distinct()
            val indep = ls.count { it.startsWith("vlastní:") && sup.first { p -> lines[p] == it }.primary } + ls.count { it.startsWith("převzato:") }.coerceAtMost(1).let { if (ls.size > 1) ls.count { l -> l.startsWith("převzato:") } else it }
            val status = when {
                sup.isEmpty() -> "nedoloženo"
                ls.size >= 2 && sup.any { it.primary } -> "potvrzeno nezávislým datovaným dokladem"
                ls.size >= 2 -> "dvě nezávislé linie"
                sup.all { it.anonymousOrigin || it.origins.isNotEmpty() } -> "jen jedna linie (texty přebírají tutéž výpověď)"
                else -> "jediný zdroj"
            }
            return Part(label, status, ls.size.coerceAtLeast(if (sup.isEmpty()) 0 else 1).let { if (indep > it) indep else it }, sup.map { it.doc.name })
        }
        allDates.firstOrNull()?.let { d -> parts += grade("datum události (${d.split('-').let { "${it[2].toInt()}. ${it[1].toInt()}. ${it[0]}" }})", ps.filter { d in it.dates }) }
        parts += grade("motiv / důvod", ps.filter { it.motive })
        val spread = ps.filter { it.origins.isNotEmpty() }.map { lines[it]!! }
        val oneLine = spread.size >= 2 && spread.distinct().size == 1
        val direct = buildList {
            if (oneLine) add("Texty ${ps.filter { it.origins.isNotEmpty() }.joinToString(", ") { it.doc.name }} dokládají, že se tvrzení šířilo; oba přebírají tutéž výpověď (${ps.first { it.origins.isNotEmpty() }.label.ifBlank { spread.first().removePrefix("převzato:") }}).")
            ps.filter { it.primary }.forEach { add("${it.doc.name} je vlastní datovaný doklad (${it.dates.joinToString { d -> d.split('-').let { x -> "${x[2].toInt()}. ${x[1].toInt()}. ${x[0]}" } }}), nepřebírá jiný zdroj.") }
        }
        val inferred = parts.filter { it.status.startsWith("potvrzeno") || it.status.startsWith("dvě") }.map { "${it.label.replaceFirstChar { c -> c.uppercase() }}: ${it.status}." }
        val open = parts.filter { !it.status.startsWith("potvrzeno") && !it.status.startsWith("dvě") }.map { "${it.label.replaceFirstChar { c -> c.uppercase() }}: ${it.status} – zůstává otevřené." }
        val missing = if (open.isNotEmpty()) "Pro odhad pravdivosti chybí nezávislé potvrzení nebo ověřitelný prvotní doklad" + (if (open.any { it.startsWith("Motiv") }) " – u motivu např. výpověď nebo dokument, který nepřebírá původní zprávu." else ".") else null
        val headline = when {
            oneLine && parts.none { it.status.startsWith("potvrzeno") } -> direct.first() + " " + (missing ?: "")
            parts.any { it.status.startsWith("potvrzeno") } -> parts.filter { it.status.startsWith("potvrzeno") }.joinToString("; ") { "${it.label.replaceFirstChar { c -> c.uppercase() }} je potvrzené nezávislým datovaným dokladem" } +
                (if (open.isNotEmpty()) "; " + open.joinToString("; ") { it.substringBefore(":").lowercase() + " zůstává otevřený" } + "." else ".")
            else -> "Podklady nestačí k závěru o pravdivosti. " + (missing ?: "")
        }
        return Assessment(headline.trim(), parts, direct, inferred, open, missing)
    }

    fun render(a: Assessment): String = buildString {
        append(a.headline).append("\n\n")
        if (a.direct.isNotEmpty()) append("**Přímo doloženo:** ").append(a.direct.joinToString(" ")).append('\n')
        if (a.inferred.isNotEmpty()) append("**Odvozuji:** ").append(a.inferred.joinToString(" ")).append('\n')
        if (a.open.isNotEmpty()) append("**Otevřené:** ").append(a.open.joinToString(" ")).append('\n')
        append("\nČíselnou pravděpodobnost neuvádím – chybí základ, ze kterého by šla vysvětlit. ")
        a.missing?.let { append(it.removePrefix("Pro odhad pravdivosti chybí ").replaceFirstChar { c -> c.uppercase() }.let { m -> "Změnil by ji: $m" }) }
        append("\n_Kritéria: původ a nezávislost zdroje, ověřitelnost, datace – stejná pro oficiální i alternativní výklad; pověst autora do hodnocení nevstupuje._")
    }

    fun isQuestion(raw: String) = Regex("pravdiv|procent|duveryhodn|nakolik|na kolik|overen|dolozen|dukaz|potvrzuj|veri[mt]").containsMatchIn(f(raw))
    fun isAddition(raw: String) = Regex("pridavam|tady je (dalsi|novy)|dalsi doklad|novy doklad|nezavisl\\w* doklad|prikladam|a jeste").containsMatchIn(f(raw))
}
