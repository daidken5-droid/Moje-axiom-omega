package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * Δ STRÁŽCE FAKTŮ – každou odpověď mozku nebo cloudu ověří PŘÍMO V TELEFONU proti offline Wikipedii.
 *
 * AI si fakta občas vymýšlí a uživatel to nepozná. Strážce najde v odpovědi tvrzení, která se dají spolehlivě
 * ověřit, dohledá k nim heslo v offline encyklopedii a když se liší, ukáže rozdíl (Δ) přímo pod odpovědí.
 * Bez internetu, bez dalšího modelu, za pár milisekund. Při jakékoli nejistotě MLČÍ (raději nic než planý poplach).
 *
 * Ověřuje (úzce, ale přesně):
 *  • „X se narodil(a) … 1938“ / „X zemřel(a) … 2019“ – rok, a když odpověď i heslo uvádí celé datum, i den a měsíc,
 *  • „X (1939–2019)“, „X (14. 7. 1939 – 1. 10. 2019)“, „X (* 1950)“ – závorka za jménem,
 *  • „X byla založena / vznikla v roce 1348“ – proti větě o založení v úvodu hesla.
 * Mlčí, když: heslo nenajde, jmen se stejným názvem je víc (jmenovci), rok v odpovědi patří jiné události
 * („narodil se v Plzni, kde v roce 1945…“), heslo neuvádí datum jednoznačně, nebo zdroj není česká Wikipedie.
 */
object Sentinel {

    data class Flag(val subject: String, val claimed: String, val wiki: String, val title: String) {
        fun text() = "Wikipedie uvádí jinak: **$subject** – $wiki (v odpovědi: $claimed)."
    }

    private const val UP = "A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ"
    private const val LOW = "a-záčďéěíňóřšťúůýž"
    // jméno = 2–4 slova s velkým písmenem („Karel Gott“, „Tomáš Garrigue Masaryk“, „Jan Amos Komenský“)
    private const val NAME = "((?:[$UP][$LOW]+\\s){1,3}[$UP][$LOW]+)"
    // instituce / místo: velké první slovo + až 3 další („Univerzita Karlova“, „Národní divadlo“, „Karlův most“)
    private const val THING = "([$UP][$LOW]+(?:\\s[$UP$LOW][$LOW]+){1,3})"
    private const val YEAR = "(1[0-9]{3}|20[0-2][0-9])"
    private const val MONTHS = "ledna|února|března|dubna|května|června|července|srpna|září|října|listopadu|prosince"
    // volitelné celé datum před rokem: „14. července “ / „14. 7. “
    private const val DAY = "(?:(\\d{1,2})\\.\\s*($MONTHS|\\d{1,2}\\.)\\s*)?"
    // mezera mezi slovesem a rokem nesmí přeskočit do jiné věty ani vedlejší věty („…, kde v roce 1945“)
    private const val GAP = "(?:(?!,\\s*(?:kde|když|kdy|který|která|které|ale|než|načež|a\\s+(?:zemřel|umřel|vznikl))\\b)[^.;!?]){0,60}?"
    private val bornRx = Regex("$NAME[^.]{0,40}?\\b(?:se )?narodil[a]?\\b$GAP\\b$DAY$YEAR\\b")
    private val diedRx = Regex("$NAME[^.]{0,40}?\\b(?:zemřel[a]?|umřel[a]?)\\b$GAP\\b$DAY$YEAR\\b")
    private val spanRx = Regex("$NAME\\s*\\(\\s*(?:\\*\\s*)?$DAY$YEAR\\s*[–—-]\\s*(?:†\\s*)?$DAY$YEAR\\s*\\)")
    private val bornOnlyRx = Regex("$NAME\\s*\\(\\s*\\*\\s*$DAY$YEAR\\s*\\)")
    private val foundRx = Regex("$THING\\s+(?:byl[aoy]?\\s+(?:založen[aoy]?|zřízen[aoy]?)|vznikl[aoy]?)\\b$GAP\\b$YEAR\\b")
    private val anyYear = Regex("\\b(1[0-9]{3}|20[0-2][0-9])\\b")

    /** Datum v tvrzení: rok + volitelně den a měsíc (měsíc jako číslo 1–12). */
    data class When(val year: String, val day: Int? = null, val month: Int? = null) {
        fun show() = if (day != null && month != null) "$day. $month. $year" else year
    }

    private fun mkWhen(day: String, month: String, year: String): When {
        val m = month.trimEnd('.').let { it.toIntOrNull() ?: (MONTHS.split('|').indexOf(it) + 1).takeIf { i -> i > 0 } }
        val d = day.toIntOrNull()
        return if (d != null && m != null && d in 1..31 && m in 1..12) When(year, d, m) else When(year)
    }

    fun check(answer: String, k: KnowledgeSource?, maxLookups: Int = 3): List<Flag> {
        if (k == null || !anyYear.containsMatchIn(answer)) return emptyList()
        data class Claim(val name: String, val born: When?, val died: When?, val founded: String? = null)
        val claims = LinkedHashMap<String, Claim>()
        fun add(name: String, b: When?, d: When?, f: String? = null) {
            val c = claims[name]; claims[name] = Claim(name, b ?: c?.born, d ?: c?.died, f ?: c?.founded)
        }
        val g = { m: MatchResult, i: Int -> m.groupValues[i] }
        spanRx.findAll(answer).forEach { add(g(it, 1).trim(), mkWhen(g(it, 2), g(it, 3), g(it, 4)), mkWhen(g(it, 5), g(it, 6), g(it, 7))) }
        bornOnlyRx.findAll(answer).forEach { add(g(it, 1).trim(), mkWhen(g(it, 2), g(it, 3), g(it, 4)), null) }
        bornRx.findAll(answer).forEach { add(g(it, 1).trim(), mkWhen(g(it, 2), g(it, 3), g(it, 4)), null) }
        diedRx.findAll(answer).forEach { add(g(it, 1).trim(), null, mkWhen(g(it, 2), g(it, 3), g(it, 4))) }
        foundRx.findAll(answer).forEach { if (g(it, 1).trim() !in claims) add(g(it, 1).trim(), null, null, g(it, 2)) }
        val out = mutableListOf<Flag>()
        for (c in claims.values.take(maxLookups)) {
            val art = lookup(c.name, k) ?: continue
            val subject = art.title.replace(Regex("^Wikipedie:\\s*"), "").replace(Regex("\\s*\\(.*\\)"), "")
            val title = art.title.replace(Regex("^Wikipedie:\\s*"), "")
            if (c.founded != null) {
                val wy = foundingYear(art.text) ?: continue
                if (wy != c.founded) out += Flag(subject, "vznik ${c.founded}", "vznik **$wy**", title)
                continue
            }
            val (wb, wd) = lifeDates(art.text) ?: continue
            differs(c.born, wb)?.let { out += Flag(subject, "narození ${c.born!!.show()}", "narození **${wb!!.first}**", title) }
            differs(c.died, wd)?.let { out += Flag(subject, "úmrtí ${c.died!!.show()}", "úmrtí **${wd!!.first}**", title) }
        }
        return out
    }

    /** Liší se tvrzení od hesla? Rok vždy; den a měsíc jen když je mají OBĚ strany. */
    private fun differs(claim: When?, wiki: Pair<String, When>?): Unit? {
        if (claim == null || wiki == null) return null
        val w = wiki.second
        if (claim.year != w.year) return Unit
        if (claim.day != null && claim.month != null && w.day != null && w.month != null && (claim.day != w.day || claim.month != w.month)) return Unit
        return null
    }

    /**
     * Heslo k jménu: zkusí i jméno bez úvodních slov („Zpěvák Karel Gott“ → „Karel Gott“). Když odpovídá víc hesel
     * se stejným názvem (jmenovci „Jan Novák (fotbalista)“ / „(politik)“), MLČÍ – nevíme, o kom odpověď mluví.
     */
    private fun lookup(name: String, k: KnowledgeSource): Snippet? {
        val words = name.split(' ')
        for (n in (0..maxOf(0, words.size - 2)).map { words.drop(it).joinToString(" ") }) {
            val hits = runCatching { k.search(n, 5) }.getOrDefault(emptyList())
                .filter { it.title.startsWith("Wikipedie:") && WikiReflex.titleMatches(it.title, n) }.distinctBy { it.title }
            if (hits.size == 1) return hits[0]
            if (hits.size > 1) return null
        }
        return null
    }

    private val dateRx = Regex("(\\d{1,2})\\.\\s*($MONTHS|\\d{1,2}\\.)\\s*(\\d{3,4})|\\b(\\d{3,4})\\b")

    /** Z úvodní závorky hesla („(14. července 1939 Plzeň – 1. října 2019 Praha)“) → (text data, datum) narození a úmrtí. */
    fun lifeDates(text: String): Pair<Pair<String, When>?, Pair<String, When>?>? {
        val paren = Regex("\\(([^()]{4,180})\\)").find(text.take(500))?.groupValues?.get(1) ?: return null
        // musí to vypadat jako životopisná závorka (datum, ne „(z latiny …)“ ani „(1. díl)“)
        if (!Regex("\\b1[0-9]{3}\\b|\\b20[0-2][0-9]\\b").containsMatchIn(paren)) return null
        // „asi 1340“, „kolem 1500“, „př. n. l.“ → nejisté datum, raději mlčet
        if (Regex("\\b(asi|kolem|cca|okolo|snad|patrně|př\\.|n\\. l\\.)", RegexOption.IGNORE_CASE).containsMatchIn(paren)) return null
        val sides = paren.split(Regex("\\s[–—-]\\s|\\s†\\s?")).map { it.trim() }
        fun parse(s: String?): Pair<String, When>? = s?.let { dateRx.find(it.removePrefix("*").trim()) }?.let { m ->
            val w = if (m.groupValues[3].isNotEmpty()) mkWhen(m.groupValues[1], m.groupValues[2], m.groupValues[3]) else When(m.groupValues[4])
            TextUtil.normalize(m.value) to w
        }
        return parse(sides.firstOrNull()) to parse(sides.getOrNull(1))
    }

    /** Kompatibilita: jen roky. */
    fun lifeYears(text: String): Pair<Pair<String, String>?, Pair<String, String>?>? =
        lifeDates(text)?.let { (b, d) -> b?.let { it.first to it.second.year } to d?.let { it.first to it.second.year } }

    /** Rok založení z úvodu hesla – jen když věta o založení obsahuje PRÁVĚ JEDEN letopočet. */
    fun foundingYear(text: String): String? {
        val s = Instant.firstSentences(text, 6, 1500) ?: text.take(1500)
        val sentence = s.split(Regex("(?<=[.!?])\\s+(?=[$UP])"))
            .firstOrNull { Regex("\\b(založen|založil|založena|založeno|vznikl|vznikla|vzniklo|zřízen)", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        val years = anyYear.findAll(sentence).map { it.value }.distinct().toList()
        return years.singleOrNull()
    }
}
