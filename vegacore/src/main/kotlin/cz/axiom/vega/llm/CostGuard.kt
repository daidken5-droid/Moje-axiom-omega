package cz.axiom.vega.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * HLÍDÁNÍ NÁKLADŮ placených poskytovatelů. Přísný režim: odhad se před voláním ZAREZERVUJE (strop hlídá i běžící
 * volání), po odpovědi se vyrovná podle skutečných tokenů; volání, které spadlo ještě před odpovědí, se vrátí.
 * Čeština ~2,5 znaku na token (horní odhad, ať se rozpočet nepodcení).
 */
class CostGuard(private val store: Store, private val charsPerToken: Double = 2.5, val strict: Boolean = true) {
    interface Store {
        fun allowed(provider: String): Boolean
        fun budgetUsd(provider: String): Double
        fun spentUsd(provider: String): Double
        fun addSpent(provider: String, usd: Double)
    }
    data class Price(val inPerM: Double, val outPerM: Double, val flat: Boolean = false)
    sealed class Decision { object Allow : Decision(); data class Deny(val reason: String) : Decision() }
    class BudgetException(msg: String, val provider: String?, val model: String? = null, val estUsd: Double = 0.0) : EngineException(msg)

    private val reserved = ConcurrentHashMap<String, Double>()

    fun priceOf(provider: String, model: String) = defaultPrice(provider, model)
    fun estimateUsd(provider: String, model: String, inputChars: Int, maxOut: Int): Double {
        val p = priceOf(provider, model); if (p.flat) return 0.0
        return inputChars / charsPerToken * p.inPerM / 1e6 + maxOut * p.outPerM / 1e6
    }
    @Synchronized fun check(provider: String, model: String, inputChars: Int, maxOut: Int): Decision {
        if (!store.allowed(provider)) return Decision.Deny("$provider není povolený (schval ho pro úkol nebo v Nastavení)")
        val est = estimateUsd(provider, model, inputChars, maxOut)
        val left = store.budgetUsd(provider) - store.spentUsd(provider) - (reserved[provider] ?: 0.0)
        return if (est > left) Decision.Deny("odhad %.3f USD přesahuje zbývající strop %.3f USD".format(java.util.Locale.ROOT, est, maxOf(0.0, left))) else Decision.Allow
    }
    @Synchronized fun reserve(provider: String, model: String, inputChars: Int, maxOut: Int): Double {
        val est = estimateUsd(provider, model, inputChars, maxOut); reserved.merge(provider, est, Double::plus); return est
    }
    /** Kontrola + rezervace ATOMICKY (dvě souběžná volání nesmí obě projít kontrolou a pak společně přečerpat strop). */
    @Synchronized fun tryReserve(provider: String, model: String, inputChars: Int, maxOut: Int): Pair<Decision, Double> {
        val d = check(provider, model, inputChars, maxOut)
        return if (d is Decision.Deny) d to 0.0 else d to reserve(provider, model, inputChars, maxOut)
    }
    @Synchronized fun refund(provider: String, amount: Double) { reserved.computeIfPresent(provider) { _, v -> maxOf(0.0, v - amount) } }
    /** Skutečná cena (tokeny od poskytovatele, jinak odhad ze znaků) místo rezervace. */
    @Synchronized fun settle(provider: String, model: String, reservedUsd: Double, inTok: Long?, outTok: Long?, inChars: Int, outChars: Int, maxOut: Int) {
        refund(provider, reservedUsd); record(provider, model, inTok, outTok, inChars, outChars)
    }
    @Synchronized fun record(provider: String, model: String, inTok: Long?, outTok: Long?, inChars: Int, outChars: Int) {
        val p = priceOf(provider, model); if (p.flat) return
        val i = inTok ?: (inChars / charsPerToken).toLong(); val o = outTok ?: (outChars / charsPerToken).toLong()
        store.addSpent(provider, i * p.inPerM / 1e6 + o * p.outPerM / 1e6)
    }

    companion object {
        /** Chyba před odpovědí poskytovatele (spojení, HTTP chyba) = nic se neúčtovalo. */
        fun preResponse(e: Throwable): Boolean = e !is CancelledException && (e as? EngineException)?.billed != true
        fun defaultPrice(provider: String, model: String): Price {
            val m = model.lowercase()
            return when (provider) {
                "claude" -> when { "opus" in m -> Price(15.0, 75.0); "haiku" in m -> Price(1.0, 5.0); else -> Price(3.0, 15.0) }
                "geminipro", "gemini" -> when { "hydrogen" in m -> Price(1.5, 7.5); Regex("-pro\\b|-pro-").containsMatchIn(m) -> Price(4.0, 18.0); "flash" in m -> Price(1.0, 4.0); else -> Price(3.0, 15.0) }
                "deepseek" -> Price(0.7, 2.5)
                else -> Price(3.0, 15.0) // neznámý = draze
            }
        }
    }
}

/** Placený engine za strážcem: kontrola → rezervace → volání → vyrovnání (nebo vrácení, když spadl před odpovědí). */
class GuardedEngine(private val inner: LlmEngine, private val provider: String, private val model: String, private val guard: CostGuard) : LlmEngine by inner {
    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val chars = messages.sumOf { it.content.length }
        val (d, res) = guard.tryReserve(provider, model, chars, options.maxTokens)
        (d as? CostGuard.Decision.Deny)?.let {
            throw CostGuard.BudgetException("Placené volání zastaveno: ${it.reason}.", provider, model, guard.estimateUsd(provider, model, chars, options.maxTokens)) }
        var streamed = 0
        val r = try { inner.generate(messages, options, cancel) { d -> streamed += d.text.length; onDelta(d) } } catch (e: Throwable) {
            // nic nepřišlo a nic se neúčtovalo → vrátit; jinak (Stop během psaní, chyba po zaúčtování) zapsat odhad podle přijatého textu
            if (CostGuard.preResponse(e) && streamed == 0) guard.refund(provider, res) else guard.settle(provider, model, res, null, null, chars, streamed, options.maxTokens)
            throw e
        }
        guard.settle(provider, model, res, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length, options.maxTokens)
        return r
    }
}
