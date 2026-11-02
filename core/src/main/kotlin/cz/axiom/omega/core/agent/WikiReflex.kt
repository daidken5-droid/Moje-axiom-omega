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
        return t == s || (s.length >= 4 && (t.startsWith(s) || s.startsWith(t)) && kotlin.math.abs(t.length - s.length) <= 3)
    }

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
        return null
    }

    private fun article(subject: String, k: KnowledgeSource): Snippet? {
        if (subject.split(' ').size > 6) return null
        val hits = runCatching { k.search(subject, 5) }.getOrDefault(emptyList())
        return hits.firstOrNull { titleMatches(it.title, subject) }
    }

    private fun source(s: Snippet) = "\n\n— *offline: ${s.title.replace(Regex("^Wikipedie:\\s*"), "Wikipedie – ")}*"

    private fun lifeDate(subject: String, k: KnowledgeSource, born: Boolean): Instant.Answer? {
        val a = article(subject, k) ?: return null
        // úvodní závorka hesla o osobě: „(14. července 1939 Plzeň – 1. října 2019 Praha)“ / „(* 12. 3. 1950)“
        val paren = Regex("\\(([^()]{4,160})\\)").find(a.text.take(400))?.groupValues?.get(1) ?: return null
        val sides = paren.split(Regex("\\s[–—-]\\s|\\s†\\s?")).map { it.trim() }
        val d = if (born) dateRx.find(sides.first().removePrefix("*").trim())?.value
            else sides.getOrNull(1)?.let { dateRx.find(it)?.value } ?: Regex("†\\s*([^,;)]+)").find(paren)?.groupValues?.get(1)?.let { dateRx.find(it)?.value }
        if (d == null) return if (!born && sides.size == 1) Instant.Answer("Podle offline Wikipedie **${cleanName(a)} žije** (heslo neuvádí datum úmrtí)." + source(a), "Wikipedie", confidence = 86) else null
        val place = (if (born) sides.first() else sides.getOrNull(1)).orEmpty().substringAfter(d, "").trim().trim(',', ' ').takeIf { it.length in 2..40 }
        return Instant.Answer("**${cleanName(a)}** se ${if (born) "narodil(a)" else "zemřel(a)"} **$d**" + (place?.let { " ($it)" } ?: "") + "." + source(a), "Wikipedie", confidence = 90)
    }

    private fun cleanName(a: Snippet) = a.title.replace(Regex("^Wikipedie:\\s*"), "").replace(Regex("\\s*\\(.*\\)"), "")

    private fun sentence(subject: String, k: KnowledgeSource, cue: Regex, kind: String): Instant.Answer? {
        val a = article(subject, k) ?: return null
        val s = Instant.firstSentences(a.text, 6, 2000)?.split(Regex("(?<=[.!?])\\s+(?=[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ])"))
            ?.firstOrNull { cue.containsMatchIn(TextUtil.fold(it).lowercase()) } ?: return null
        return Instant.Answer(s.trim() + source(a), "Wikipedie · $kind", confidence = 88)
    }

    private fun define(subject: String, k: KnowledgeSource): Instant.Answer? {
        val a = article(subject, k) ?: return null
        val t = Instant.firstSentences(a.text, 2, 420) ?: return null
        return Instant.Answer(t + source(a), "Wikipedie", confidence = 87)
    }
}
