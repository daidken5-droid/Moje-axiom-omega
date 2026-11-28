package cz.axiom.vega.llm

/**
 * ŘETĚZ POSKYTOVATELŮ: zkouší členy po pořadí (zdarma první). Placený člen projde jen přes strážce nákladů.
 * Když už model začal psát, další člen se nezkouší (nemíchat dvě odpovědi). Zmizelý model nebo model mimo tarif
 * se jednou samoopraví výběrem jiného z nabídky téhož poskytovatele.
 */
class ChainEngine(
    private val members: List<Providers.Member>,
    private val factory: (Providers.Member) -> LlmEngine = Providers::engine,
    private val guard: CostGuard? = null,
    private val listModels: (Providers.Provider, String) -> List<String> = { p, k -> OpenAiEngine.listModels(p.base, k) },
    private val onModel: (String, String) -> Unit = { _, _ -> },
    private val onUsage: (provider: String, model: String, inTok: Long?, outTok: Long?) -> Unit = { _, _, _, _ -> },
) : LlmEngine {
    init { require(members.isNotEmpty()) { "Chybí poskytovatel modelu." } }
    override val id = "chain:" + members.joinToString(",") { it.provider.id }
    override val label get() = members.first().let { "${it.provider.name} · ${it.model}" }
    override val contextChars = members.first().provider.contextChars

    data class Failure(val provider: String, val name: String, val model: String, val message: String, val budget: Boolean, val rateLimited: Boolean, val estUsd: Double = 0.0)
    class ChainException(msg: String, val failures: List<Failure>, cause: Throwable?) :
        EngineException(msg, retryable = true, billed = failures.isEmpty() && (cause as? EngineException)?.billed == true, cause = cause)

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val failures = ArrayList<Failure>(); var last: Throwable? = null
        for (m0 in members) {
            var m = m0; var healed = false
            while (true) {
                cancel.check()
                val opts = options.copy(maxTokens = options.maxTokens.coerceIn(256, m.provider.maxOut))
                val fitted = fit(messages, m.provider.contextChars); val chars = fitted.sumOf { it.content.length }
                var reservedUsd = 0.0
                if (m.provider.paid && guard != null) {
                    val (d, res) = guard.tryReserve(m.provider.id, m.model, chars, opts.maxTokens)
                    if (d is CostGuard.Decision.Deny) {
                        val est = guard.estimateUsd(m.provider.id, m.model, chars, opts.maxTokens)
                        last = CostGuard.BudgetException(d.reason, m.provider.id, m.model, est)
                        failures += Failure(m.provider.id, m.provider.name, m.model, d.reason, budget = true, rateLimited = false, estUsd = est); break
                    }
                    reservedUsd = res
                }
                var started = false; var streamed = 0
                // placené volání ukončené Stopem / chybou až po zaúčtování: vrátit rezervaci a zapsat odhad podle skutečně přijatého textu
                fun chargePartial() { if (m.provider.paid && guard != null && reservedUsd > 0) guard.settle(m.provider.id, m.model, reservedUsd, null, null, chars, streamed, opts.maxTokens) }
                try {
                    val r = factory(m).generate(fitted, opts, cancel) { d -> started = true; streamed += d.text.length; onDelta(d) }
                    if (m.provider.paid && guard != null) guard.settle(m.provider.id, m.model, reservedUsd, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length, opts.maxTokens)
                    onUsage(m.provider.id, m.model, r.inputTokens, r.outputTokens)
                    return r
                } catch (e: CancelledException) { chargePartial(); throw e
                } catch (e: Exception) {
                    last = e
                    val pre = CostGuard.preResponse(e)
                    if (reservedUsd > 0 && pre && !started) guard!!.refund(m.provider.id, reservedUsd)
                    else if (reservedUsd > 0) chargePartial()
                    if (started) throw e
                    if (m.provider.paid && !pre) throw e // zaplacený pokus → neplatit podruhé u dalšího člena
                    val msg = e.message.orEmpty()
                    val zeroQuota = Regex("limit: 0\\b|not available (for|on|in) (your|the) (free )?tier|not supported for", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                    val notFound = Regex("HTTP 404|model.{0,40}(not found|does not exist)", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                    if (!healed && (zeroQuota || notFound)) {
                        healed = true
                        val fixed = runCatching { Providers.pickModel(m.provider, listModels(m.provider, m.key), avoid = setOf(m.model)) }.getOrNull()
                        val pricier = fixed != null && m.provider.paid && guard != null && guard.priceOf(m.provider.id, fixed).let { n -> guard.priceOf(m.provider.id, m.model).let { o -> n.inPerM > o.inPerM || n.outPerM > o.outPerM } }
                        if (fixed != null && fixed != m.model && !pricier) { m = m.copy(model = fixed); if (notFound) onModel(m.provider.id, fixed); continue }
                    }
                    failures += Failure(m.provider.id, m.provider.name, m.model, shortReason(msg), budget = false, rateLimited = Regex("HTTP 429").containsMatchIn(msg))
                    break
                }
            }
        }
        throw ChainException(failures.joinToString(" · ") { "${it.name}: ${it.message}" }.ifBlank { last?.message ?: "Model neodpověděl." }, failures, last)
    }

    private fun shortReason(m: String) = when {
        Regex("HTTP 429").containsMatchIn(m) && Regex("(?i)per ?day|daily").containsMatchIn(m) -> "denní limit zdarma vyčerpán"
        Regex("HTTP 429").containsMatchIn(m) -> "limit – zkus za minutu"
        Regex("HTTP 40[13]").containsMatchIn(m) -> "neplatný klíč"
        Regex("HTTP 5\\d\\d").containsMatchIn(m) -> "výpadek serveru"
        Regex("(?i)connect|refused|resolve|timeout|timed out|unreachable").containsMatchIn(m) -> "bez spojení"
        else -> m.substringBefore(" {").take(90)
    }

    /** Zkrátí historii do okna poskytovatele: systém + poslední zprávy, nejstarší zprávy pryč, poslední zpráva zkrácená až nakonec. */
    private fun fit(ms: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (ms.sumOf { it.content.length } <= budget) return ms
        val sys = ms.filter { it.role == Role.SYSTEM }; val rest = ms.filter { it.role != Role.SYSTEM }.toMutableList()
        while (rest.size > 1 && (sys + rest).sumOf { it.content.length } > budget) rest.removeAt(0)
        val over = (sys + rest).sumOf { it.content.length } - budget
        if (over > 0 && rest.isNotEmpty()) rest[rest.lastIndex] = rest.last().copy(content = rest.last().content.take(maxOf(500, rest.last().content.length - over)))
        return sys + rest
    }
}
