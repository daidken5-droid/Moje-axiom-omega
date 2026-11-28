package cz.axiom.omega.core.agent

/**
 * HYPERFORGE M3 – ANSWER CONTRACT: typovaný výsledek odpovědi bez mozku. Proveniencí se neřídí jedno číslo jistoty,
 * ale typ + důvod + zdroj + datum platnosti + předpoklady. Typ se NIKDY nezvyšuje při průchodu vrstvami
 * (STEP-0 → RANDY → OmegaService → WebView): kdo kontrakt dostane, smí ho jen oslabit.
 */
data class AnswerContract(
    val type: Type,
    val reason: String,
    val evidenceRefs: List<String> = emptyList(),
    /** k jakému dni údaj platí (fakta, kurzy); null = nadčasové / výpočet */
    val asOf: String? = null,
    val assumptions: List<String> = emptyList(),
    val elapsedNs: Long = 0,
    val cachePolicy: CachePolicy = CachePolicy.NONE,
) {
    enum class Type(val code: String, val label: String, val rank: Int) {
        EXACT("exact", "přesný výpočet", 5),
        VERIFIED_FACT("fact", "fakt ze zdroje", 4),
        PERSONAL_MEMORY("memory", "tvoje paměť / potvrzení", 3),
        ESTIMATE("estimate", "orientační odhad", 2),
        NEEDS_FRESH_SOURCE("fresh", "potřebuje aktuální zdroj", 1),
        ABSTAIN("abstain", "bez odpovědi", 0),
    }
    enum class CachePolicy { NONE, SESSION, UNTIL_REVISION }

    /** Kontrakt smí jen zeslábnout (např. RANDY nebo UI nesmí z odhadu udělat fakt). */
    fun weakenTo(other: Type, why: String): AnswerContract = if (other.rank < type.rank) copy(type = other, reason = "$reason; $why") else this

    companion object {
        private val estimateKind = Regex("(?i)orientačn|odhad|asi|přibližn|typick")

        /** Typ podle MODULU, který odpověděl (ne podle textu), + výhrady z druhu odpovědi. */
        fun of(module: String, a: Instant.Answer, asOf: String? = null, assumptions: List<String> = emptyList()): AnswerContract {
            // vstup je orientační: druh odpovědi to říká, Wikipedie se zdráhá („asi“), nebo jde o vestavěný (ne aktuální) kurz
            val est = estimateKind.containsMatchIn(a.kind) || (estimateKind.containsMatchIn(a.text.take(200)) && module in setOf("wiki", "compare")) ||
                asOf?.contains("vestavěn") == true
            return when (module) {
                "practical", "instant", "everyday", "calendar" ->
                    if (est) AnswerContract(Type.ESTIMATE, "výpočet z orientačního vstupu (${a.kind})", asOf = asOf, assumptions = assumptions)
                    else AnswerContract(Type.EXACT, "deterministický výpočet (${a.kind})", assumptions = assumptions)
                // názorový pohled (politika) NIKDY jako fakt
                "facts", "politics" -> if ("pohled" in a.kind) AnswerContract(Type.ESTIMATE, "názorový pohled (${a.kind}) – není to fakt", asOf = asOf)
                    else AnswerContract(Type.VERIFIED_FACT, "vestavěná faktová tabulka (${a.kind})", listOf("AVIOR fakta"), asOf = asOf, cachePolicy = CachePolicy.UNTIL_REVISION)
                "recall" -> AnswerContract(Type.PERSONAL_MEMORY, "z tvé paměti – není to nezávislé ověření")
                "cache" -> when (a.kind) {
                    Proof.Origin.WIKI.label -> AnswerContract(Type.VERIFIED_FACT, "dříve ověřeno offline Wikipedií", listOf("offline Wikipedie"), asOf = asOf, cachePolicy = CachePolicy.UNTIL_REVISION)
                    Proof.Origin.USER.label -> AnswerContract(Type.PERSONAL_MEMORY, "odpověď, kterou jsi potvrdil ✓ – osobní potvrzení", cachePolicy = CachePolicy.UNTIL_REVISION)
                    else -> AnswerContract(Type.ESTIMATE, "dřívější neověřená odpověď mozku", cachePolicy = CachePolicy.UNTIL_REVISION)
                }
                "compare", "wiki" ->
                    if (est) AnswerContract(Type.ESTIMATE, "z offline Wikipedie s výhradou", a.sources.map { it.title }, asOf = asOf)
                    else AnswerContract(Type.VERIFIED_FACT, "přesné heslo offline Wikipedie", a.sources.map { it.title }, asOf = asOf)
                else -> AnswerContract(Type.ESTIMATE, "neznámý modul $module")
            }
        }
    }
}
