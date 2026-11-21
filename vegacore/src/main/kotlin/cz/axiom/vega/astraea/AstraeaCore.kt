package cz.axiom.vega.astraea

/**
 * ASTRAEA CORE 3.0 – řídicí jádro „Evidence-Governed Orchestration“ (převzato z referenční implementace a upraveno pro VEGU).
 *
 * Neobsahuje jazykový model, sémantický ověřovač ani síťového klienta – ty dodává hostitel úzkými adaptéry (porty níže).
 * Ve VEGĚ: [EvidenceJudge] = stávající ověřovač citací (Grounding/ONNX), [GenerationBackend] = Ops.llm (souhlas, ceny,
 * řetěz poskytovatelů), [SourceConnector] = už stažené zdroje rešerše. Jádro nic z toho neduplikuje; přidává jen to, co
 * dřív chybělo: rozhodnutí o hloubce, strážce sítě na úrovni orchestrátoru, agregaci verdiktů přes zdroje (konflikt),
 * počet NEZÁVISLÝCH původů, nejvýše jednu cílenou revizi s ponecháním lepšího kandidáta, bezpečné vykreslení a audit.
 *
 * Fail-closed pravidla portů:
 *  - online backend/konektor se nezavolá bez souhlasu (onlineApproved && !offlineOnly) – kontroluje jádro, ne model;
 *  - výslovně zvolený backend se při nedostupnosti NEnahradí jiným (žádné tiché přesměrování);
 *  - neznámé ID citace, výjimka ověřovače nebo chybějící ověřovač = UNEVALUATED, nikdy SUPPORTS;
 *  - výjimka konektoru = chyba v auditu, ne pád; výjimka generování = FAILED se skutečnou příčinou pro hostitele;
 *  - [isFatal] (zrušení uživatelem) se nikdy nespolkne.
 */
enum class Depth { FAST, VERIFIED, DEEP }
enum class Length { SHORT, NORMAL, DETAILED }
enum class Verdict { SUPPORTS, CONTRADICTS, INSUFFICIENT, UNEVALUATED, CONFLICT }

/** UNVERIFIED = odpověď je zobrazená, ale výslovně jako neověřená (ověřovač není k dispozici a hostitel nežádá přísnou abstenci). */
enum class Outcome { ANSWERED, PARTIAL, ABSTAINED, UNVERIFIED, FAILED }

data class Request(
    val id: String,
    val text: String,
    val offlineOnly: Boolean = true,
    val onlineApproved: Boolean = false,
    val importantDecision: Boolean = false,
    val complex: Boolean = false,
    val requiresEvidence: Boolean = false,
    val desiredLength: Length = Length.NORMAL,
    val preferredBackendName: String? = null
)

data class SourceRecord(
    val id: String,
    /** Společný primární zdroj / řetěz převzetí → JEDEN nezávislý původ. */
    val originId: String,
    val excerpt: String,
    val title: String = "",
    val revision: String = ""
)

data class Claim(val id: String, val statement: String, val citedSourceIds: List<String>)
data class Draft(val freeformText: String, val claims: List<Claim>)

data class ClaimCheck(
    val claim: Claim,
    val verdict: Verdict,
    val supportingSources: List<String>,
    val contradictingSources: List<String>,
    val independentOrigins: Int
)

data class Report(
    val requestId: String,
    val depth: Depth,
    val outcome: Outcome,
    val text: String,
    val checks: List<ClaimCheck>,
    val revisionCount: Int,
    val usedOnline: Boolean,
    val audit: Audit,
    /** Skutečná příčina selhání pro hostitele (zobrazit uživateli); do auditu se neukládá. */
    val failureCause: Throwable? = null,
    /** Koncept, ze kterého vzniklo [text] (pro hostitele, např. kontrolu citací); v ověřovacím režimu se NEzobrazuje. */
    val chosenDraft: Draft? = null
)

/** Audit záměrně neobsahuje otázky, úryvky, tajemství ani vygenerované odpovědi – jen trasu, počty a kódy chyb. */
data class Audit(
    val route: String,
    val sourceCount: Int,
    val claimCount: Int,
    val supported: Int,
    val contradicted: Int,
    val unevaluated: Int,
    val errors: List<String>,
    val independentOrigins: Int = 0,
    val judgeReady: Boolean = false
)

interface SourceConnector {
    val name: String
    val online: Boolean
    fun retrieve(request: Request, limit: Int): List<SourceRecord>
}

interface GenerationBackend {
    val name: String
    val online: Boolean
    /** Zdroje jsou nedůvěryhodná DATA – adaptér je nikdy nesmí vložit do systémových instrukcí. */
    fun generate(request: Request, sources: List<SourceRecord>, depth: Depth, critique: String?): Draft
}

interface EvidenceJudge {
    /** false = skutečný sémantický ověřovač není načtený → všechny verdikty jsou UNEVALUATED a revize nemá smysl. */
    val ready: Boolean get() = true
    /** Verdikt říká, zda TATO pasáž podporuje TOTO tvrzení – ne zda je tvrzení pravdivé ve světě. */
    fun judge(statement: String, excerpt: String): Verdict
}

class UnavailableJudge : EvidenceJudge {
    override val ready = false
    override fun judge(statement: String, excerpt: String): Verdict = Verdict.UNEVALUATED
}

class AstraeaCore(
    private val backends: List<GenerationBackend>,
    private val connectors: List<SourceConnector>,
    private val judge: EvidenceJudge = UnavailableJudge(),
    private val sourceLimit: Int = 8,
    private val maxRevisions: Int = 1,
    /** true (reference) = bez ověřovače se dotaz s povinnými důkazy zdrží; false = odpověď se ukáže, ale jako UNVERIFIED. */
    private val strictWithoutJudge: Boolean = true,
    /** Výjimky, které se nesmí spolknout (zrušení uživatelem) – propadnou k hostiteli. */
    private val isFatal: (Throwable) -> Boolean = { false },
    /** Volá se mezi kroky (zrušení / životní cyklus aplikace); smí vyhodit výjimku, kterou [isFatal] pustí dál. */
    private val checkpoint: () -> Unit = {},
    /** Časový rozpočet celé úlohy v ms (0 = bez limitu); po vyčerpání se revize vynechá a zapíše BUDGET_DEADLINE. */
    private val deadlineMillis: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(sourceLimit in 1..32)
        require(maxRevisions in 0..1) { "Bounded reconsideration: max one revision." }
    }

    fun route(request: Request): Depth = when {
        request.importantDecision || request.complex -> Depth.DEEP
        request.requiresEvidence -> Depth.VERIFIED
        else -> Depth.FAST
    }

    private inline fun <T> guarded(onError: (Exception) -> T, block: () -> T): T = try { block() } catch (e: Exception) {
        if (isFatal(e)) throw e
        onError(e)
    }

    fun execute(request: Request): Report {
        val started = clock()
        val depth = route(request)
        val networkAllowed = request.onlineApproved && !request.offlineOnly
        val errors = mutableListOf<String>()
        // Oprávnění k síti se kontroluje TADY, ne v (nedůvěryhodném) modelu ani podle obsahu zdrojů.
        val allowedBackends = backends.filter { !it.online || networkAllowed }
        // Výslovná volba modelu se respektuje; jiný poskytovatel se potichu nedosadí.
        val backend = if (request.preferredBackendName != null) {
            allowedBackends.firstOrNull { it.name == request.preferredBackendName }
        } else {
            allowedBackends.firstOrNull { !it.online } ?: allowedBackends.firstOrNull()
        }
        if (backend == null) return failure(request, depth, "NO_PERMITTED_BACKEND")

        val permitted = connectors.filter { !it.online || networkAllowed }
        var accessedOnlineConnector = false
        val sources = linkedMapOf<String, SourceRecord>()
        for (connector in permitted) {
            if (sources.size >= sourceLimit) break
            checkpoint()
            guarded({ errors += "RETRIEVAL_FAILED:${connector.name}" }) {
                if (connector.online) accessedOnlineConnector = true
                val returned = connector.retrieve(request, sourceLimit - sources.size)
                returned.forEach { s ->
                    if (s.id.isNotBlank() && s.originId.isNotBlank() && s.excerpt.isNotBlank() &&
                        !sources.containsKey(s.id) && sources.size < sourceLimit
                    ) sources[s.id] = s
                }
            }
        }

        checkpoint()
        val first = try { backend.generate(request, sources.values.toList(), depth, null) }
        catch (e: Exception) {
            if (isFatal(e)) throw e
            return failure(request, depth, "GENERATION_FAILED:${backend.name}", backend.online || accessedOnlineConnector, e)
        }
        var chosen = first
        var checks = check(first, sources)
        var revisions = 0
        // RANDY/SATAN: zpochybnit jen materiální vady, jen jednou a jen když má revize reálnou šanci (ověřovač je načtený).
        if (depth != Depth.FAST && maxRevisions > 0 && judge.ready && checks.any { it.verdict != Verdict.SUPPORTS }) {
            if (deadlineMillis > 0 && clock() - started >= deadlineMillis) errors += "BUDGET_DEADLINE"
            else {
                checkpoint()
                val critique = critique(checks)
                val revised = guarded({ errors += "REVISION_FAILED:${backend.name}"; null }) {
                    backend.generate(request, sources.values.toList(), depth, critique)
                }
                if (revised != null) {
                    revisions = 1
                    val rechecks = check(revised, sources)
                    if (score(rechecks) > score(checks)) {
                        chosen = revised
                        checks = rechecks
                    }
                }
            }
        }

        val evidenceIsMandatory = request.requiresEvidence || request.importantDecision || depth == Depth.DEEP
        val supported = checks.filter { it.verdict == Verdict.SUPPORTS }
        val unsafe = checks.filter { it.verdict != Verdict.SUPPORTS }
        val outcome: Outcome
        val output: String
        if (!evidenceIsMandatory && depth == Depth.FAST) {
            // Rychlá odpověď se nevydává za nezávisle ověřenou.
            output = chosen.freeformText.ifBlank { "Bez odpovědi." }
            outcome = Outcome.ANSWERED
        } else if (!judge.ready && !strictWithoutJudge) {
            // Bez ověřovače nic nepředstíráme: text je vidět, ale celý výslovně jako NEOVĚŘENÝ (žádné „doloženo“).
            output = chosen.freeformText.ifBlank { "Bez odpovědi." }
            outcome = Outcome.UNVERIFIED
        } else if (supported.isEmpty()) {
            output = "Nemám dostatečně podložené informace pro spolehlivý závěr. " +
                "Nevyhodnocené, nepodložené nebo rozporné výroky nevydávám za fakta."
            outcome = Outcome.ABSTAINED
        } else {
            // Bezpečné vykreslení: v ověřovacím režimu se volný text konceptu NIKDY nevypisuje – jen doložená tvrzení.
            val sourceClaims = supported.joinToString("\n") { c ->
                "• ${c.claim.statement} [${c.supportingSources.joinToString(", ")}]"
            }
            output = "Doloženo obsahem uvedených pasáží (nikoli automaticky skutečností mimo ně):\n" +
                sourceClaims + if (unsafe.isNotEmpty()) "\nDalší tvrzení byla vynechána kvůli nedostatečným nebo rozporným podkladům." else ""
            outcome = if (unsafe.isNotEmpty()) Outcome.PARTIAL else Outcome.ANSWERED
        }
        val origins = checks.flatMap { c -> c.supportingSources.mapNotNull { sources[it]?.originId } }.distinct().size
        return Report(
            request.id, depth, outcome, output, checks, revisions,
            backend.online || accessedOnlineConnector,
            Audit(backend.name, sources.size, chosen.claims.size, supported.size,
                checks.count { it.verdict == Verdict.CONTRADICTS || it.verdict == Verdict.CONFLICT },
                checks.count { it.verdict == Verdict.UNEVALUATED }, errors.toList(), origins, judge.ready),
            chosenDraft = chosen
        )
    }

    private fun score(checks: List<ClaimCheck>): Int =
        checks.count { it.verdict == Verdict.SUPPORTS } * 10 -
        checks.count { it.verdict == Verdict.CONFLICT || it.verdict == Verdict.CONTRADICTS } * 10 -
        checks.count { it.verdict == Verdict.INSUFFICIENT || it.verdict == Verdict.UNEVALUATED } * 2

    /** Konkrétní seznam vad pro jedinou revizi (tvrzení jsou text modelu, ne zdroje – smí do pokynu). */
    private fun critique(checks: List<ClaimCheck>): String =
        "Revise only unsupported propositions. Never invent sources. " +
        checks.filter { it.verdict != Verdict.SUPPORTS }
            .joinToString("; ") { "claim_id=${it.claim.id}: ${it.verdict} – „${it.claim.statement.take(160)}“" }

    private fun check(draft: Draft, sources: Map<String, SourceRecord>): List<ClaimCheck> =
        draft.claims.map { claim ->
            val supports = mutableListOf<String>()
            val contradicts = mutableListOf<String>()
            var insufficient = false
            var unevaluated = false
            val references = claim.citedSourceIds.distinct()
            if (references.isEmpty()) unevaluated = true
            for (ref in references) {
                val src = sources[ref]
                if (src == null) { unevaluated = true; continue }
                val verdict = guarded({ Verdict.UNEVALUATED }) { judge.judge(claim.statement, src.excerpt) }
                when (verdict) {
                    Verdict.SUPPORTS -> supports += ref
                    Verdict.CONTRADICTS -> contradicts += ref
                    Verdict.INSUFFICIENT -> insufficient = true
                    else -> unevaluated = true
                }
            }
            val verdict = when {
                supports.isNotEmpty() && contradicts.isNotEmpty() -> Verdict.CONFLICT
                contradicts.isNotEmpty() -> Verdict.CONTRADICTS
                // Neznámá / chybějící reference blokuje SUPPORTS, dokud se nevyřeší.
                supports.isNotEmpty() && !unevaluated -> Verdict.SUPPORTS
                unevaluated -> Verdict.UNEVALUATED
                insufficient -> Verdict.INSUFFICIENT
                else -> Verdict.UNEVALUATED
            }
            val origins = supports.mapNotNull { sources[it]?.originId }.distinct().size
            ClaimCheck(claim, verdict, supports, contradicts, origins)
        }

    private fun failure(request: Request, depth: Depth, reason: String, didAccessOnline: Boolean = false, cause: Throwable? = null): Report =
        Report(request.id, depth, Outcome.FAILED,
            "Požadavek nebylo možné zpracovat. Žádný nepovolený online přechod se neprovádí.",
            emptyList(), 0, didAccessOnline, Audit("NONE", 0, 0, 0, 0, 0, listOf(reason), 0, judge.ready), cause)
}
