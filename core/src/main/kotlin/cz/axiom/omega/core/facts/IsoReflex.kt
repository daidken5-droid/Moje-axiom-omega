package cz.axiom.omega.core.facts

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.util.TextUtil
import java.time.LocalDate

/**
 * HYPERFORGE M4 – vertikál ISO 3166-1 / ISO 4217 bez mozku, z [FactStore] (Unicode CLDR 49, 249 území).
 *  • „ISO kód Německa“, „třípísmenný kód Francie“, „číselný kód ISO Česka“, „ISO 3166 Japonsko“
 *  • zpětně: „co je ISO 3166 kód CZ“, „která země má kód DEU“
 *  • „jakou měnu má Gruzie“ (aktuální zákonné platidlo + od kdy)
 * Konzervativně: jiná otázka, nejednoznačný název nebo neplatná data = null (zdržet se, převezme mozek / zdroj).
 */
object IsoReflex {
    private val store by lazy { FactStore.load("iso") }
    private const val CONF = 97

    private val endings = setOf("", "a", "u", "e", "o", "y", "i", "em", "ou", "ech", "ach", "um", "ami", "im", "ych", "eho", "emu", "ym", "ie", "ii", "iich", "ho", "mu", "m", "ch", "mi", "je", "ji", "ou")
    private val nonLetter = Regex("[^\\p{L}]+")
    private fun words(s: String) = TextUtil.fold(s).lowercase().split(nonLetter).filter { it.length >= 2 }

    /** Shoda slova otázky [w] s tokenem názvu [t] přes české koncovky („nemecka“ ~ „nemecko“, „iraku“ ≁ „iran“). */
    private fun same(w: String, t: String): Boolean {
        if (w == t) return true
        var p = 0; while (p < w.length && p < t.length && w[p] == t[p]) p++
        return p >= 3 && p >= t.length - 2 && t.substring(p) in endings && w.substring(p) in endings
    }

    private val names: List<Pair<String, List<String>>> by lazy {
        store.facts.filter { it.predicate == "name_cs" || it.predicate == "alias_cs" }.map { it.entityId to words(it.value) }.filter { it.second.isNotEmpty() }
    }

    /**
     * Entita, jejíž CELÝ název je v otázce. Pořadí: víc slov názvu > víc PŘESNĚ shodných slov („Niger“ × „Nigérie“).
     * Remíza dvou různých entit = null (zdržet se).
     */
    fun resolve(prompt: String): String? {
        val w = words(prompt)
        val hits = names.filter { (_, toks) -> toks.all { t -> w.any { same(it, t) } } }
            .map { (e, toks) -> e to (toks.size * 100 + toks.count { it in w }) }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        val best = hits.values.maxOrNull() ?: return null
        return hits.filterValues { it == best }.keys.singleOrNull()
    }

    /** České názvy entity (bez diakritiky, malá písmena) – pro křížovou kontrolu jiných modulů. */
    fun namesOf(entityId: String): List<String> = store.of(entityId).filter { it.predicate == "name_cs" || it.predicate == "alias_cs" }.map { TextUtil.fold(it.value).lowercase() }

    private val askCode = Regex("\\b(kod\\w*|iso|alpha|alfa|zkratk\\w*|3166)\\b")
    private val askCurrency = Regex("\\b(jakou menu|jaka mena|jakou menou|cim se plati|v cem se plati|kod meny|mena|menu|meny|menou|platidl\\w*)\\b")
    private val a3 = Regex("(alpha|alfa)[ -]?3|tripismen\\w*|3[ -]?pismen\\w*|tri pismen\\w*")
    private val a2 = Regex("(alpha|alfa)[ -]?2|dvoupismen\\w*|2[ -]?pismen\\w*|dve pismen\\w*")
    private val num = Regex("ciseln\\w*|numeric\\w*")
    private val reverseCode = Regex("(?<![A-Za-z])([A-Z]{2,3})(?![A-Za-z])")
    private val reverseKey = Regex("\\b(kod\\w*|iso|3166|zkratk\\w*)\\b")
    private val reverseAsk = Regex("\\b(ktera zeme|jaky stat|ktery stat|ktera zem|co je|co znamena|komu patri|cí je|ci je|ma kod|kod)\\b")

    fun answer(prompt: String, today: LocalDate = LocalDate.now()): Instant.Answer? {
        if (prompt.length > 160 || store.integrity != FactStore.Integrity.OK) return null
        val f = TextUtil.fold(prompt).lowercase()
        val wantsCode = askCode.containsMatchIn(f)
        val wantsCurrency = !wantsCode && askCurrency.containsMatchIn(f)
        if (!wantsCode && !wantsCurrency) return null
        // rozpoznaný NÁZEV má přednost před kódem v textu („Menší odlehlé ostrovy USA“ ≠ kód USA)
        val e = resolve(prompt) ?: return reverse(prompt, f, today)
        val name = store.value(e, "name_cs", today) ?: return null
        fun v(p: String) = store.value(e, p, today)
        if (wantsCurrency) {
            val c = v("currency") ?: return null
            val cn = v("currency_name_cs")?.value
            val since = v("currency_since")?.value?.let { " (od $it)" } ?: ""
            return ans("Ve státě **${name.value}** je zákonným platidlem **${cn ?: c.value} (${c.value})**$since.", c, "ISO 4217 měna")
        }
        val (A2, A3, N) = Triple(v("alpha2") ?: return null, v("alpha3") ?: return null, v("numeric") ?: return null)
        val text = when {
            a3.containsMatchIn(f) -> "Třípísmenný kód ISO 3166-1 alpha-3 pro **${name.value}** je **${A3.value}**."
            num.containsMatchIn(f) -> "Číselný kód ISO 3166-1 pro **${name.value}** je **${N.value}**."
            a2.containsMatchIn(f) -> "Dvoupísmenný kód ISO 3166-1 alpha-2 pro **${name.value}** je **${A2.value}**."
            else -> "**${name.value}** – ISO 3166-1: alpha-2 **${A2.value}**, alpha-3 **${A3.value}**, číselný **${N.value}**."
        }
        return ans(text, A2, "ISO 3166-1")
    }

    private fun reverse(prompt: String, f: String, today: LocalDate): Instant.Answer? {
        if (!reverseAsk.containsMatchIn(f) || !reverseKey.containsMatchIn(f)) return null
        val codes = reverseCode.findAll(prompt).map { it.groupValues[1] }.filter { it != "ISO" }.toList()
        val code = codes.singleOrNull() ?: return null
        val pred = if (code.length == 2) "alpha2" else "alpha3"
        val hit = store.facts.firstOrNull { it.predicate == pred && it.value == code && it.valid(today) } ?: return null
        val name = store.value(hit.entityId, "name_cs", today) ?: return null
        val other = store.value(hit.entityId, if (pred == "alpha2") "alpha3" else "alpha2", today)?.value
        val n = store.value(hit.entityId, "numeric", today)?.value
        return ans("Kód **$code** (ISO 3166-1 ${if (pred == "alpha2") "alpha-2" else "alpha-3"}) = **${name.value}**" +
            (other?.let { " (${if (pred == "alpha2") "alpha-3" else "alpha-2"} $it" + (n?.let { x -> ", číselný $x" } ?: "") + ")" } ?: "") + ".", hit, "ISO 3166-1")
    }

    private fun ans(text: String, f: FactStore.Fact, kind: String) = Instant.Answer(
        "$text\n\n_Zdroj: ${f.source}, licence ${f.license}; stav k ${f.asOf}, znovu ověřit po ${f.validUntil}._", kind, confidence = CONF,
        contract = AnswerContract(AnswerContract.Type.VERIFIED_FACT, "Verified Fact Store (${f.reliabilityClass})", listOf(f.factId, f.source),
            asOf = f.asOf, cachePolicy = AnswerContract.CachePolicy.UNTIL_REVISION),
    )
}
