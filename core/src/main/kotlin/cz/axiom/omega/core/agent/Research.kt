package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * RESEARCH DEPTH CONTROLLER (REBIRTH fáze II.3–2.5).
 *  FAST     – jednoduché otázky, minimální latence (web jen pro hlavní dotaz, stručně),
 *  DEEP     – více zdrojů, porovnání, analytická syntéza, štítky evidence,
 *  FORENSIC – složitý výzkum: časová osa, konkurenční hypotézy, protiargumenty, co by hypotézu vyvrátilo,
 *             audit argumentace (≥ 2 průchody), hledání i pro jednotlivé podotázky.
 * AUTO odhadne hloubku z délky, počtu podotázek, kategorie a slov jako „porovnej / zjisti / souvislosti“.
 * Výslovná volba uživatele („/deep“, „/forensic“, „hloubkově“, „forenzně“) má vždy přednost.
 */
enum class ResearchDepth(val label: String, val webQueries: Int, val webMax: Int, val minPasses: Int, val words: String) {
    FAST("rychle", 1, 3, 1, "150–500 slov podle potřeby"),
    DEEP("hloubkově", 3, 6, 1, "800–2 000 slov, pokud to téma odůvodňuje"),
    FORENSIC("forenzně", 4, 8, 2, "2 500–5 000+ slov u rozsáhlého výzkumu, klidně po částech");

    /** Instrukce pro model (jen DEEP/FORENSIC – FAST nechává styl na variantě). */
    fun instruction(): String? = when (this) {
        FAST -> null
        DEEP -> "HLOUBKA: DEEP · rozsah $words; kvalita a úplnost mají přednost před délkou.\n" + EVIDENCE_RULES
        FORENSIC -> "HLOUBKA: FORENSIC · rozsah $words; postupuj systematicky:\n" +
            "1) rozlož zadání na podtémata; 2) časová osa doložených událostí; 3) co je veřejně doložené × interpretace × nepotvrzená tvrzení;\n" +
            "4) konkurenční hypotézy (i oficiální, i alternativní) – stejná pravidla dokazování pro všechny; 5) pro každou hypotézu: co ji podporuje, co jí odporuje, co by ji VYVRÁTILO;\n" +
            "6) podobnost ≠ společná příčina, korelace ≠ kauzalita – řekni výslovně; 7) co zůstává neprokázané.\n" + EVIDENCE_RULES
    }

    companion object {
        const val EVIDENCE_RULES = "ŠTÍTKY EVIDENCE: každé důležité tvrzení označ na začátku věty jedním štítkem – [FAKT] (doloženo podkladem [n]), " +
            "[ZDROJ] (tvrzení konkrétního zdroje [n], ne nutně pravda), [INFERENCE] (tvůj analytický závěr), [HYPOTÉZA], [SPEKULACE], [NEZNÁMÉ]. " +
            "Nikdy si nevymýšlej citace ani URL; když podklady něco neříkají, napiš [NEZNÁMÉ]. Víc článků opisujících jeden zdroj není víc nezávislých potvrzení. " +
            "Důvěru nevyjadřuj vymyšlenými procenty."

        private val explicit = mapOf(
            Regex("(?i)^/(forensic|forenzne|forenzně)\\b|\\b(forenzn\\w*|forensic)\\b") to FORENSIC,
            Regex("(?i)^/(deep|hloubkove|hloubkově)\\b|\\bhloubkov\\w*\\b|\\bdeep research\\b") to DEEP,
            Regex("(?i)^/(fast|rychle)\\b") to FAST,
        )
        private val analytic = Regex("\\b(porovnej|srovnej|zjisti|souvislost\\w*|souvisi|spolecn\\w* prvk\\w*|hypotez\\w*|dukaz\\w*|analyzuj|rozeber|proc|pricin\\w*|dopad\\w*|vyvoj\\w*|geopoliti\\w*|konspira\\w*|kontroverz\\w*)\\b")

        fun explicit(prompt: String): ResearchDepth? = explicit.entries.firstOrNull { it.key.containsMatchIn(prompt) }?.value

        fun auto(prompt: String, subQuestions: Int = LongInput.subQuestions(prompt).size): ResearchDepth {
            explicit(prompt)?.let { return it }
            val f = TextUtil.fold(prompt).lowercase()
            val hits = analytic.findAll(f).count()
            return when {
                prompt.length > 1_500 || subQuestions >= 5 || hits >= 3 -> FORENSIC
                prompt.length > 220 || subQuestions >= 2 || hits >= 1 -> DEEP
                else -> FAST
            }
        }
    }
}

/**
 * EVIDENCE MATRIX (REBIRTH fáze III, deterministická část): ze štítků v odpovědi a ze seznamu podkladů složí matici
 * tvrzení × typ důkazu × zdroje. Nic nedopočítává modelem – jen čte, co odpověď výslovně tvrdí a čím to dokládá.
 * Nezávislost zdrojů: podklady ze stejné domény se počítají jako jeden nezávislý zdroj.
 */
object EvidenceMatrix {
    enum class Kind(val tag: String, val label: String) {
        FACT("FAKT", "ověřený fakt"), SOURCE("ZDROJ", "tvrzení zdroje"), INFERENCE("INFERENCE", "analytický závěr"),
        HYPOTHESIS("HYPOTÉZA", "hypotéza"), SPECULATION("SPEKULACE", "spekulace"), UNKNOWN("NEZNÁMÉ", "neznámé / neověřené"),
    }
    data class Claim(val kind: Kind, val text: String, val refs: List<Int>, val refsValid: Boolean)
    data class Matrix(val claims: List<Claim>, val independentSources: Int, val invalidRefs: Int) {
        fun count(k: Kind) = claims.count { it.kind == k }
    }

    private val tagRx = Regex("\\[(FAKT|ZDROJ|INFERENCE|HYPOT[ÉE]ZA|SPEKULACE|NEZN[ÁA]M[ÉE])]\\s*([^\\n]{3,600}?)(?=(?:\\s*\\[(?:FAKT|ZDROJ|INFERENCE|HYPOT[ÉE]ZA|SPEKULACE|NEZN[ÁA]M[ÉE])])|\\n|$)")
    private val refRx = Regex("\\[(\\d{1,2})]")

    /** @param sourceUrls adresy podkladů v pořadí [1], [2], … */
    fun build(answer: String, sourceUrls: List<String>): Matrix {
        val claims = tagRx.findAll(answer).map { m ->
            val tag = TextUtil.fold(m.groupValues[1]).uppercase()
            val kind = Kind.entries.first { TextUtil.fold(it.tag).uppercase() == tag }
            val refs = refRx.findAll(m.groupValues[2]).map { it.groupValues[1].toInt() }.toList()
            val valid = refs.all { it in 1..sourceUrls.size }
            // [FAKT] bez platného podkladu není fakt – degraduje se na tvrzení bez opory
            val k = if (kind == Kind.FACT && (refs.isEmpty() || !valid)) Kind.UNKNOWN else kind
            Claim(k, m.groupValues[2].trim(), refs, valid)
        }.toList()
        val hosts = sourceUrls.mapNotNull { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }.toSet()
        return Matrix(claims, hosts.size, claims.count { !it.refsValid })
    }
}
