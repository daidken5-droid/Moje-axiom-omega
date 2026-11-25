package cz.axiom.vega.core

import cz.axiom.vega.base.Text

/**
 * PRÁCE S DŮKAZY: kolik NEZÁVISLÝCH linií doložení má každá část tvrzení (datum, událost, motiv).
 * Dva texty, které citují tutéž původní zprávu, jsou JEDNA linie – dokládají šíření, ne pravdivost.
 * Kritéria jsou stejná pro oficiální i alternativní výklad a pověst autora do hodnocení nevstupuje:
 * rozhoduje původ (koho text přebírá), ověřitelnost a datace.
 * Číselnou pravděpodobnost nepočítá – místo ní řekne, jaký údaj chybí a jak by závěr změnil.
 */
object Evidence {
    data class Doc(val name: String, val text: String)
    data class Parsed(val doc: Doc, val origins: Set<String>, val dates: Set<String>, val motive: Boolean, val primary: Boolean, val anonymousOrigin: Boolean, val label: String = "",
                      /** Kmeny slov uvedeného důvodu („zakrýt chybu v projektu“) – motiv podporuje jen doklad se SHODNÝM důvodem. */
                      val reason: Set<String> = emptySet(), val reasonText: String = "",
                      /** Kdo důvod řekl ve vlastní citaci („…kvůli prasklé konstrukci,“ řekl statik Jan Dvořák) – výrok má vlastní původ, ne převzatou agenturu. */
                      val motiveSpeaker: String? = null,
                      /** Kde v textu stojí datum (ISO → pozice) a uvedený důvod – pro citaci pasáže. */
                      val datePos: Map<String, Int> = emptyMap(), val reasonPos: Int? = null)
    /** Přesná věta z dokladu, o kterou se část závěru opírá (doslovná citace – jde otevřít a porovnat se zdrojem). */
    data class Passage(val doc: String, val text: String)
    data class Part(val label: String, val status: String, val lines: Int, val support: List<String>, val passages: List<Passage> = emptyList())
    data class Assessment(val headline: String, val parts: List<Part>, val direct: List<String>, val inferred: List<String>, val open: List<String>, val missing: String?)

    private fun f(s: String) = Text.fold(s).lowercase()
    private val citeRx = Regex("(?:podle|cituje|odkazuje na|prevzato z|zdroj(?:em)?:?|jak uvedl[ao]?|informoval[ao]?)\\s+(?:anonymni(?:ho)?\\s+)?((?:prispev\\w*|zprav\\w*|clan\\w*|tweet\\w*|video\\w*|post\\w*|blog\\w*|svedect\\w*|vypoved\\w*)[^.;\\n]{0,60})")
    private val urlRx = Regex("https?://[^\\s)\"'>]+")

    /** Stejná adresa = stejný původ: bez interpunkce na konci, kotvy, www a lomítka; z dotazu jen klíče identity (v, id, p…). */
    fun normUrl(u: String): String {
        val x = u.trimEnd('.', ',', ';', ':', '“', '”', '»', '!', '?').substringBefore('#').lowercase()
        val base = x.substringBefore('?').substringAfter("://").removePrefix("www.").removeSuffix("/")
        val keep = x.substringAfter('?', "").split('&').filter { it.substringBefore('=') in setOf("v", "id", "p", "t", "article", "aid", "story") }.sorted()
        return if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")
    }

    fun parse(d: Doc): Parsed {
        val t = f(d.text)
        val origins = LinkedHashSet<String>()
        // URL = přesný původ (hostitel + cesta), porovnává se jen shodou celé adresy
        urlRx.findAll(d.text).forEach { origins += "url:" + normUrl(it.value) }
        // textový původ = kmeny slov (5 písmen) + zkratky z velkých písmen (ČTK, ČT): „příspěvku na fóru Kvasar“ = „příspěvek na fóru Kvasar,“
        citeRx.findAll(t).forEach { m ->
            // jen popis ZDROJE: končí na čárce, číslu nebo slovese/spojce (jinak by se texty „shodly“ na slovech děje)
            val g = m.groupValues[1].split(',', ';', ':')[0]
            val cut = g.split(Regex("\\s+")).takeWhile { w -> w !in BREAK && !w.any(Char::isDigit) }.joinToString(" ")
            val orig = if (t.length == d.text.length) d.text.substring(m.groups[1]!!.range.first, m.groups[1]!!.range.first + cut.length) else ""
            val acr = Regex("(?<!\\p{L})\\p{Lu}{2,6}(?!\\p{L})").findAll(orig).map { f(it.value) }
            val words = cut.replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.length > 3 && it !in STOP }.take(5).map { it.take(5) }
            val toks = (words + acr).distinct()
            if (toks.isNotEmpty()) origins += "txt:" + toks.joinToString(" ")
        }
        // převzetí od agentury („podle ČTK“, „(ČTK)“, „informovala agentura Reuters“) = jedna linie, ne nezávislé zdroje
        // jen VÝSLOVNÉ převzetí („podle ČTK“, „informovala agentura ČTK“, „(ČTK)“, „ČTK uvedla“) – pouhá zmínka („novináři z ČTK“) není původ
        AGENCY.findAll(d.text).forEach { m ->
            val before = d.text.substring(maxOf(0, m.range.first - 40), m.range.first).let { f(it) }
            val after = d.text.substring(m.range.last + 1, minOf(d.text.length, m.range.last + 30)).let { f(it) }
            val explicit = TAKEOVER_BEFORE.containsMatchIn(before) || TAKEOVER_AFTER.containsMatchIn(after) || (before.endsWith("(") && after.trimStart().startsWith(")"))
            if (explicit) origins += "agentura:" + f(m.value).replace("ctk", "čtk")
        }
        // jen platná data (31. 2. se nezapočítá)
        val datePos = LinkedHashMap<String, Int>()
        Regex("\\b(\\d{1,2})\\.[\\s\u00a0]?(\\d{1,2})\\.[\\s\u00a0]?(\\d{4})\\b").findAll(d.text).forEach { m ->
            runCatching { java.time.LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()).toString() }.getOrNull()?.let { datePos.putIfAbsent(it, m.range.first) } }
        val dates = datePos.keys.toSet()
        val mRx = Regex("(?:motiv\\w*|kvuli|protoze|aby|z duvodu|pricin\\w*|chtel[ai]?|zamer\\w*)\\s+([^.;\\n]{3,120})")
        val mm = mRx.find(t)
        val motive = mm != null
        val reason = mm?.groupValues?.get(1)?.replace(Regex("[^a-z ]"), " ")?.split(Regex("\\s+"))?.filter { it.length > 3 && it !in STOP && it !in setOf("prej", "tim", "tomu", "firma", "spravce") }?.map { it.take(5) }?.toSet() ?: emptySet()
        val reasonText = mm?.let { if (t.length == d.text.length) d.text.substring(it.groups[1]!!.range).trim() else it.groupValues[1] } ?: ""
        val speaker = mm?.let { m -> QUOTE.findAll(d.text).firstOrNull { q -> m.range.first in q.range }?.let { q ->
            NAME.findAll(q.groupValues[2]).lastOrNull()?.value } }
        val anon = Regex("anonymn|nepodepsan|neznam\\w* autor").containsMatchIn(t)
        // prvotní doklad: vlastní datovaný záznam bez převzetí od jiného zdroje (úřední záznam, protokol, výpis, fotka s datem)
        val primary = origins.isEmpty() && dates.isNotEmpty() && Regex("zaznam|protokol|vypis|zapis|potvrzen|faktur|smlouv|rozhodnut|fotografi|snimek|registr|uredni|evidenc").containsMatchIn(t)
        val label = citeRx.find(t)?.let { m -> if (t.length == d.text.length) d.text.substring(m.groups[1]!!.range).trim().trimEnd(',', ' ') else m.groupValues[1] }
            ?: urlRx.find(d.text)?.value ?: ""
        return Parsed(d, origins, dates, motive, primary, anon && origins.isNotEmpty(), reason = reason, reasonText = reasonText, motiveSpeaker = speaker, datePos = datePos, reasonPos = mm?.range?.first, label = label.replace(Regex("\\s+\\d+$"), "").replace(Regex("\\s+(došlo|unikla|uvedl\\w*)$"), ""))
    }
    private val AGENCY = Regex("(?<!\\p{L})(ČTK|CTK|Reuters|AP|AFP|DPA|TASR|Bloomberg|PAP|APA|ANSA|EFE|Interfax|TASS|Xinhua)(?!\\p{L})")
    private val TAKEOVER_BEFORE = Regex("(?:podle|dle|informoval\\w*|uvedl\\w*|uvadi|pise|napsal\\w*|oznamil\\w*|zdroj\\w*|prevzat\\w*|zprav\\w*|citoval\\w*|cituje|sdelil\\w*|vydal\\w*)(?:\\s+(?:agentur\\w*|tiskov\\w* agentur\\w*))?\\s*:?\\s*$")
    private val TAKEOVER_AFTER = Regex("^\\s*(?:uvedl\\w*|informoval\\w*|pise|napsal\\w*|oznamil\\w*|uvadi|sdelil\\w*|zjistil\\w*)")
    /** Přímá řeč a kdo ji řekl: „…,“ řekl / uvedl … [Jméno Příjmení]. */
    private val QUOTE = Regex("[„\"]([^“\"]{3,400})[“\"],?\\s*((?:řekl|uvedl|sdělil|vysvětlil|dodal|potvrdil|připustil)\\w*[^.;\\n]{0,80})")
    private val NAME = Regex("\\p{Lu}\\p{Ll}+(?:\\s+\\p{Lu}\\p{Ll}+)+")
    private val BREAK = setOf("se", "si", "ze", "je", "jsou", "byl", "byla", "bylo", "byli", "doslo", "unikla", "zavreli", "uzavreli", "a", "kdy", "kde", "ktery", "ktera", "ktere", "prej", "uz", "jiz")
    private val STOP = setOf("ktery", "ktera", "ktere", "jako", "tento", "tato", "podle", "uvedl", "uvedla", "pondeli", "utery", "stredu", "streda", "ctvrtek", "patek", "sobotu", "sobota", "nedeli", "nedele",
        "ledna", "unora", "brezna", "dubna", "kvetna", "cervna", "cervence", "srpna", "zari", "rijna", "listopadu", "prosince", "doslo", "unikla", "zavreli", "uzavren")

    /**
     * Linie = skupina dokumentů se stejným původem (union-find přes VŠECHNY původy dokumentu): stejná URL, nebo textový
     * původ se shodou ≥ 2 slov; doslovné kopie téhož textu jsou také jedna linie. Dokument bez citace je sám sobě původem.
     */
    private fun lines(ps: List<Parsed>): Map<Parsed, String> {
        val par = IntArray(ps.size) { it }
        fun find(i: Int): Int { var x = i; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) par[maxOf(ra, rb)] = minOf(ra, rb) }
        fun toks(o: String) = o.removePrefix("txt:").split(' ').filter { it.length >= 2 }.toSet()
        fun norm(t: String) = f(t).replace(Regex("[^a-z0-9]+"), " ").trim()
        for (i in ps.indices) for (j in i + 1 until ps.size) {
            val a = ps[i]; val b = ps[j]
            val sameOrigin = a.origins.any { oa -> b.origins.any { ob ->
                if (!oa.startsWith("txt:") || !ob.startsWith("txt:")) oa == ob else (toks(oa) intersect toks(ob)).size >= 2 } }
            if (sameOrigin || norm(a.doc.text) == norm(b.doc.text)) union(i, j)
        }
        // bez uvedeného původu: prvotní doklad = vlastní linie; jinak „neurčeno“ (dva weby bez původu nejsou automaticky nezávislé)
        fun own(p: Parsed) = if (p.primary) "vlastní:" else "neurčeno:"
        return ps.indices.associate { i -> ps[i] to (if (ps[i].origins.isEmpty() && ps.indices.none { j -> j != i && find(j) == find(i) }) own(ps[i]) + ps[i].doc.name
            else (if (ps[find(i)].origins.isEmpty()) own(ps[find(i)]) else "převzato:") + ps[find(i)].doc.name) }
    }

    fun assess(docs: List<Doc>): Assessment {
        val ps = docs.map { parse(it) }
        val lines = lines(ps)
        val allDates = ps.flatMap { it.dates }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val parts = ArrayList<Part>()
        fun grade(label: String, sup: List<Parsed>, byQuote: Boolean = false, at: (Parsed) -> Int? = { null }): Part {
            // u motivu má vlastní výrok mluvčího (rozhovor) vlastní původ – ne agenturu, od které text převzal datum
            fun key(p: Parsed) = if (byQuote && p.motiveSpeaker != null) "výrok:" + p.motiveSpeaker else lines[p]!!
            val ls = sup.map { key(it) }.distinct()
            val known = ls.filter { !it.startsWith("neurčeno:") }
            val names = sup.map { p -> if (byQuote && p.motiveSpeaker != null) "${p.doc.name} (výrok: ${p.motiveSpeaker})" else p.doc.name }
            val undetermined = ls.size - known.size
            val status = when {
                sup.isEmpty() -> "nedoloženo"
                ls.size >= 2 && sup.any { it.primary } -> "potvrzeno nezávislým datovaným dokladem"
                known.size >= 2 -> "dvě nezávislé linie"
                ls.size >= 2 && undetermined > 0 -> "${sup.size} zdroje, nezávislost neurčena ($undetermined bez uvedeného původu)"
                ls.size == 1 && ls[0].startsWith("výrok:") -> "jediný zdroj (${ls[0].removePrefix("výrok:")} ve vlastním rozhovoru)"
                sup.all { it.anonymousOrigin || it.origins.isNotEmpty() } -> "jen jedna linie (texty přebírají tutéž výpověď)"
                else -> "jediný zdroj"
            }
            return Part(label, status, if (sup.isEmpty()) 0 else known.size.coerceAtLeast(1), names, sup.mapNotNull { p -> at(p)?.let { Passage(p.doc.name, sentence(p.doc.text, it)) } })
        }
        allDates.firstOrNull()?.let { d -> parts += grade("datum události (${d.split('-').let { "${it[2].toInt()}. ${it[1].toInt()}. ${it[0]}" }})", ps.filter { d in it.dates }) { it.datePos[d] } }
        // tvrzený důvod = nejčastější (první text, který ho uvádí); doklad s JINÝM důvodem motiv nepodporuje – je to alternativa
        val claimed = ps.firstOrNull { it.motive }?.reason ?: emptySet()
        fun same(p: Parsed) = p.motive && p.reason.intersect(claimed).size >= minOf(2, claimed.size).coerceAtLeast(1)
        val alt = ps.filter { it.motive && !same(it) }
        val mp = grade("motiv / důvod", ps.filter { same(it) }, byQuote = true) { it.reasonPos }
        parts += if (alt.isNotEmpty()) mp.copy(status = mp.status + "; jiný doklad uvádí jiný důvod (${alt.joinToString { "${it.doc.name}: „${it.reasonText.take(60)}“" }})") else mp
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

    /**
     * Věta kolem pozice [at], doslovně z dokladu. Konec věty = „. ! ?“ a za ním mezera a velké písmeno (nebo nový řádek / konec);
     * „3. 11. 2026“ ani „č. j.“ větu neukončí. Nejvýš 200 znaků na každou stranu.
     */
    fun sentence(text: String, at: Int): String {
        fun nextNonSpace(k: Int): Char? { var j = k; while (j < text.length && text[j].isWhitespace()) j++; return text.getOrNull(j) }
        fun endsAt(k: Int) = text[k] in ".!?" && (k + 1 >= text.length || text[k + 1] == '\n' ||
            (text[k + 1].isWhitespace() && nextNonSpace(k + 1).let { it == null || it.isUpperCase() || it in "„\"(" }))
        val i = at.coerceIn(0, maxOf(0, text.length - 1))
        var a = i; while (a > 0 && i - a < 200 && text[a - 1] != '\n' && !endsAt(a - 1)) a--
        var b = i; while (b < text.length - 1 && b - i < 200 && text[b] != '\n' && !endsAt(b)) b++
        return text.substring(a, minOf(text.length, b + 1)).trim()
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
