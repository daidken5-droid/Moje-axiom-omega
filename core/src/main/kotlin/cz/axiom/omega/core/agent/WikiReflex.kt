package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx

/**
 * WIKIPEDIE BEZ MOZKU – reflex, který čte staženou offline Wikipedii (přes Pavučinu sdílený zdroj znalostí)
 * a rozumí, CO se ptáš:
 *  • „kdy se narodil / zemřel X“  → datum z úvodní závorky článku („Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha)“),
 *  • „kde leží / kde je X“         → věta s polohou („leží“, „nachází se“, „v okrese / kraji“),
 *  • „co je / kdo byl / co znamená X“ → první věty hesla (i s rozlišovacím názvem „Brno (město)“),
 *  • „kdo založil / kdy vznikl X“  → věta s „založil“, „vznikl“, „byl založen“.
 * Odpovídá jen při PŘESNÉ shodě názvu hesla s tím, na co se ptáš – jinak mlčí a rozhodne mozek.
 */
object WikiReflex {

    /** Název bez zdrojové předpony a rozlišení: „Wikipedie: Brno (město)“ → „brno“. */
    fun cleanTitle(title: String): String =
        TextUtil.fold(title).lowercase().replace(Regex("^(wikipedie|wikipedia|wiki)\\s*:\\s*"), "").replace(Regex("\\s*\\(.*\\)\\s*"), "").trim()

    /** Odpovídá název hesla tomu, na co se uživatel ptá? (přesně, nebo heslo je jen o pár znaků delší – skloňování) */
    fun titleMatches(title: String, subject: String): Boolean {
        val t = cleanTitle(title); val s = TextUtil.fold(subject).lowercase().trim()
        if (s.length < 2) return false
        if (t == s) return true
        if (s.length < 4) return false
        // jen skloňování: delší tvar = kratší + pádová koncovka („Telč“/„Telče“). Dřív stačil rozdíl ≤ 3 znaky,
        // takže „co je auto“ dostalo heslo „Autor“.
        val (short, long) = if (t.length <= s.length) t to s else s to t
        return long.startsWith(short) && long.length - short.length <= 3 && long.substring(short.length) in ENDINGS
    }
    private val ENDINGS = setOf("a", "e", "i", "o", "u", "y", "em", "ou", "ech", "ach", "ami", "ovi", "um", "im", "eho", "emu", "ym", "ych", "mi", "ove", "ie", "ii", "ia")

    private val birth = rx("^kdy se narodil[a]? (.{2,50})$|^kdy (?:ma|mel|mela) narozeniny (.{2,50})$|^datum narozeni (.{2,50})$")
    private val death = rx("^kdy zemrel[a]? (.{2,50})$|^kdy umrel[a]? (.{2,50})$|^datum umrti (.{2,50})$")
    private val where = rx("^kde (?:je|lezi|se nachazi|najdu|lezi mesto|je mesto) (.{2,50})$")
    private val founded = rx("^(?:kdo zalozil|kdy vznikl[ao]?|kdy byl[ao]? zalozen[ao]?|kdy zalozili) (.{2,50})$")
    private val what = rx("^(?:co je|co je to|co jsou|kdo je|kdo byl|kdo byla|kdo byli|co znamena|co to je|vysvetli pojem) (.{2,50})$")

    private val months = "ledna|února|března|dubna|května|června|července|srpna|září|října|listopadu|prosince"
    private val dateRx = Regex("(\\d{1,2}\\.\\s*(?:$months|\\d{1,2}\\.)\\s*\\d{3,4}|\\d{3,4})")

    fun answer(prompt: String, k: KnowledgeSource?): Instant.Answer? {
        if (k == null) return null
        val sig = Pavucina.of(prompt)
        // zkusit původní znění i kanonické varianty (slang, obaly) – první, která má tvar otázky na heslo
        val forms = listOf(sig.folded.trimEnd('?', '.', '!', ' ')) + sig.variants
        for (f in forms) {
            fun subj(m: MatchResult) = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('?', '.', ' ')
            birth.find(f)?.let { m -> return lifeDate(subj(m) ?: return null, k, born = true) }
            death.find(f)?.let { m -> return lifeDate(subj(m) ?: return null, k, born = false) }
            where.find(f)?.let { m -> return sentence(subj(m) ?: return null, k, rx("\\b(lezi|nachazi|se rozklada|v okrese|v kraji|na uzemi|na severu|na jihu|na vychode|na zapade|hlavni mesto|mesto v)\\b"), "poloha") }
            founded.find(f)?.let { m -> return sentence(subj(m) ?: return null, k, rx("\\b(zalozil|zalozila|zalozen|vznikl|vznikla|vzniklo|zrizen|zalozeni)\\w*"), "vznik") }
            what.find(f)?.let { m -> return define(subj(m) ?: return null, k) }
        }
        // holý název hesla („Fotosyntéza“, „Karel Gott“) = „co je X“ – jen přesná shoda hesla
        Topic.bare(prompt)?.let { return define(it, k) }
        return null
    }

    /**
     * Hovorové / zkrácené tvary → heslo (jen jednoznačné). „Co je porno“ dřív heslo „Pornografie“ nenašlo
     * (rozdíl délky > 3) a odpověď převzal jazykový model.
     */
    private val aliases = mapOf("porno" to "pornografie", "foto" to "fotografie", "fotka" to "fotografie", "auto" to "automobil",
        "moto" to "motocykl", "kilo" to "kilogram", "info" to "informace", "mobil" to "mobilni telefon", "net" to "internet",
        "televka" to "televize", "tv" to "televize", "lednice" to "chladnicka", "kolo" to "jizdni kolo",
        "eko" to "ekologie", "bio" to "biologie", "psycho" to "psychologie", "hifi" to "hi-fi", "wifi" to "wi-fi")

    /**
     * Heslo pro [subject] mezi [hits]: přesná shoda (± skloňování) → hovorová zkratka → heslo začínající slovem, které
     * ho v úvodu samo zmiňuje („Pornografie (zkráceně porno) …“). Jinak null (zdržet se).
     */
    fun match(subject: String, hits: List<Snippet>): Snippet? {
        hits.firstOrNull { titleMatches(it.title, subject) }?.let { return it }
        val s = TextUtil.fold(subject).lowercase().trim()
        aliases[s]?.let { a -> hits.firstOrNull { titleMatches(it.title, a) }?.let { return it } }
        if (s.length >= 4 && ' ' !in s) {
            val word = Regex("(^|[^\\p{L}])" + Regex.escape(s) + "([^\\p{L}]|$)")
            hits.firstOrNull { cleanTitle(it.title).startsWith(s) && word.containsMatchIn(TextUtil.fold(it.text.take(500)).lowercase()) }?.let { return it }
        }
        return null
    }

    /** Hledání hesla včetně hovorové zkratky (druhé hledání pod plným názvem). Vrací (heslo, všechny výsledky). */
    fun find(subject: String, k: KnowledgeSource, limit: Int = 6): Pair<Snippet, List<Snippet>>? {
        if (subject.split(' ').size > 6) return null
        val hits = runCatching { k.search(subject, limit) }.getOrDefault(emptyList())
        match(subject, hits)?.let { return it to hits }
        val alias = aliases[TextUtil.fold(subject).lowercase().trim()] ?: return null
        val more = runCatching { k.search(alias, limit) }.getOrDefault(emptyList())
        return hits.firstOrNull { titleMatches(it.title, alias) }?.let { it to hits } ?: more.firstOrNull { titleMatches(it.title, alias) }?.let { it to more }
    }

    private fun article(subject: String, k: KnowledgeSource): Snippet? = find(subject, k, 5)?.first

    private fun source(s: Snippet) = "\n\n— *offline: ${s.title.replace(Regex("^Wikipedie:\\s*"), "Wikipedie – ")}*"

    private fun lifeDate(subject: String, k: KnowledgeSource, born: Boolean): Instant.Answer? {
        val a = article(subject, k) ?: return null
        // úvodní závorka hesla o osobě: „(14. července 1939 Plzeň – 1. října 2019 Praha)“ / „(* 12. 3. 1950)“
        val paren = Regex("\\(([^()]{4,160})\\)").find(a.text.take(400))?.groupValues?.get(1) ?: return null
        val sides = paren.split(Regex("\\s[–—-]\\s|\\s†\\s?")).map { it.trim() }
        val d = if (born) dateRx.find(sides.first().removePrefix("*").trim())?.value
            else sides.getOrNull(1)?.let { dateRx.find(it)?.value } ?: Regex("†\\s*([^,;)]+)").find(paren)?.groupValues?.get(1)?.let { dateRx.find(it)?.value }
        if (d == null) return if (!born && sides.size == 1) Instant.Answer("Podle offline Wikipedie **${cleanName(a)} žije** (heslo neuvádí datum úmrtí)." + source(a), "Wikipedie", confidence = 86) else null
        val side = (if (born) sides.first() else sides.getOrNull(1)).orEmpty()
        val place = side.substringAfter(d, "").trim().trim(',', ' ').takeIf { it.length in 2..40 }
        // CONVERGENCE G: „(asi 1045 – …)“ → „asi 1045“, ne falešně přesné „1045“; nejistota sníží i jistotu odpovědi
        val hedge = Sentinel.UNCERTAIN.find(side.substringBefore(d))?.value?.let { "$it " } ?: ""
        return Instant.Answer("**${cleanName(a)}** se ${if (born) "narodil(a)" else "zemřel(a)"} **$hedge$d**" + (place?.let { " ($it)" } ?: "") + "." + source(a), "Wikipedie", confidence = if (hedge.isEmpty()) 90 else 86)
    }

    private fun cleanName(a: Snippet) = a.title.replace(Regex("^Wikipedie:\\s*"), "").replace(Regex("\\s*\\(.*\\)"), "")

    private fun sentence(subject: String, k: KnowledgeSource, cue: Regex, kind: String): Instant.Answer? {
        val a = article(subject, k) ?: return null
        val s = Instant.firstSentences(a.text, 6, 2000)?.split(Regex("(?<=[.!?])\\s+(?=[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ])"))
            ?.firstOrNull { cue.containsMatchIn(TextUtil.fold(it).lowercase()) } ?: return null
        return Instant.Answer(s.trim() + source(a), "Wikipedie · $kind", confidence = 88)
    }

    private fun define(subject: String, k: KnowledgeSource): Instant.Answer? {
        val (a, hits) = find(subject, k) ?: return null
        val t = Instant.firstSentences(a.text, 2, 420) ?: return null
        return Instant.Answer(t + source(a) + related(a, subject, hits), "Wikipedie", confidence = 87)
    }

    /**
     * PROPOJENÍ TÉMAT bez mozku: jiná hesla ze stejného hledání, která o tématu skutečně mluví (zmíní ho v textu).
     * Žádné další hledání ani model – jen hesla, která v knihovně existují; na každé se dá rovnou zeptat holým názvem.
     */
    fun related(article: Snippet, subject: String, hits: List<Snippet>, max: Int = 3): String {
        val stem = TextUtil.fold(subject).lowercase().split(' ').filter { it.length >= 4 }.map { it.take(maxOf(4, it.length - 2)) }
        if (stem.isEmpty()) return ""
        val self = cleanTitle(article.title)
        val rel = hits.asSequence().filter { it !== article && it.title.startsWith("Wikipedie") }
            .map { it to cleanTitle(it.title) }.filter { (_, t) -> t != self && t.isNotBlank() }
            .filter { (s, _) -> val txt = TextUtil.fold(s.text).lowercase(); stem.all { it in txt } }
            .map { (s, _) -> s.title.removePrefix("Wikipedie:").trim() }.distinct().take(max).toList()
        return if (rel.isEmpty()) "" else "\n\n**Souvisí:** " + rel.joinToString(" · ")
    }
}
