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
 *  • „X se narodil(a) … 1938“ / „X zemřel(a) … 2019“ proti úvodní závorce hesla o osobě,
 *  • „X (1939–2019)“ – roky narození a úmrtí v závorce za jménem.
 */
object Sentinel {

    data class Flag(val subject: String, val claimed: String, val wiki: String, val title: String) {
        fun text() = "Wikipedie uvádí jinak: **$subject** – $wiki (v odpovědi: $claimed)."
    }

    // jméno = 2–4 slova s velkým písmenem („Karel Gott“, „Tomáš Garrigue Masaryk“, „Jan Amos Komenský“)
    private const val NAME = "((?:[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ][a-záčďéěíňóřšťúůýž]+\\s){1,3}[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ][a-záčďéěíňóřšťúůýž]+)"
    private val bornRx = Regex("$NAME[^.]{0,40}?\\b(?:se )?narodil[a]?\\b[^.]{0,60}?\\b(1[0-9]{3}|20[0-2][0-9])\\b")
    private val diedRx = Regex("$NAME[^.]{0,40}?\\b(?:zemřel[a]?|umřel[a]?)\\b[^.]{0,60}?\\b(1[0-9]{3}|20[0-2][0-9])\\b")
    private val spanRx = Regex("$NAME\\s*\\(\\s*(?:\\*\\s*)?(1[0-9]{3}|20[0-2][0-9])\\s*[–—-]\\s*(1[0-9]{3}|20[0-2][0-9])\\s*\\)")

    fun check(answer: String, k: KnowledgeSource?, maxLookups: Int = 3): List<Flag> {
        if (k == null || !Regex("\\b(1[0-9]{3}|20[0-2][0-9])\\b").containsMatchIn(answer)) return emptyList()
        data class Claim(val name: String, val born: String?, val died: String?)
        val claims = LinkedHashMap<String, Claim>()
        fun add(name: String, b: String?, d: String?) {
            val c = claims[name]; claims[name] = Claim(name, b ?: c?.born, d ?: c?.died)
        }
        spanRx.findAll(answer).forEach { add(it.groupValues[1].trim(), it.groupValues[2], it.groupValues[3]) }
        bornRx.findAll(answer).forEach { add(it.groupValues[1].trim(), it.groupValues[2], null) }
        diedRx.findAll(answer).forEach { add(it.groupValues[1].trim(), null, it.groupValues[2]) }
        val out = mutableListOf<Flag>()
        for (c in claims.values.take(maxLookups)) {
            // první slovo věty má taky velké písmeno („Zpěvák Karel Gott“) → zkusit i jméno bez úvodních slov
            val words = c.name.split(' ')
            val art = (0..maxOf(0, words.size - 2)).asSequence().map { words.drop(it).joinToString(" ") }.firstNotNullOfOrNull { name ->
                runCatching { k.search(name, 4) }.getOrDefault(emptyList()).firstOrNull { WikiReflex.titleMatches(it.title, name) }
            } ?: continue
            val subject = art.title.replace(Regex("^Wikipedie:\\s*"), "").replace(Regex("\\s*\\(.*\\)"), "")
            val (wb, wd) = lifeYears(art.text) ?: continue
            val title = art.title.replace(Regex("^Wikipedie:\\s*"), "")
            if (c.born != null && wb != null && c.born != wb.second)
                out += Flag(subject, "narození ${c.born}", "narození **${wb.first}**", title)
            if (c.died != null && wd != null && c.died != wd.second)
                out += Flag(subject, "úmrtí ${c.died}", "úmrtí **${wd.first}**", title)
        }
        return out
    }

    private val dateRx = Regex("(\\d{1,2}\\.\\s*\\p{L}+\\s*(\\d{4})|\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*(\\d{4})|\\b(\\d{4})\\b)")

    /** Z úvodní závorky hesla („(14. července 1939 Plzeň – 1. října 2019 Praha)“) → (celé datum, rok) narození a úmrtí. */
    fun lifeYears(text: String): Pair<Pair<String, String>?, Pair<String, String>?>? {
        val paren = Regex("\\(([^()]{4,180})\\)").find(text.take(500))?.groupValues?.get(1) ?: return null
        // musí to vypadat jako životopisná závorka (datum, ne třeba „(z latiny …)“)
        if (!dateRx.containsMatchIn(paren)) return null
        val sides = paren.split(Regex("\\s[–—-]\\s|\\s†\\s?")).map { it.trim() }
        fun parse(s: String?): Pair<String, String>? = s?.let { dateRx.find(it.removePrefix("*").trim()) }?.let { m ->
            val year = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4]).firstOrNull { it.isNotEmpty() } ?: return@let null
            TextUtil.normalize(m.value) to year
        }
        return parse(sides.firstOrNull()) to parse(sides.getOrNull(1))
    }
}
