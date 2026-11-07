package cz.axiom.omega.core.llm

/**
 * COST GUARD (REBIRTH P0): „FREE FIRST — PAID ONLY WITHIN EXPLICIT BUDGET“.
 *  • placený poskytovatel jen s VÝSLOVNÝM povolením uživatele (přepínač, nebo zvolený hlavní cloud),
 *  • tvrdý měsíční rozpočet v USD pro každého poskytovatele zvlášť,
 *  • odhad ceny PŘED voláním (vstup + maximální výstup) – když by se nevešel do rozpočtu, volání neproběhne,
 *  • po volání zápis skutečné spotřeby (tokeny od poskytovatele, jinak odhad ze znaků),
 *  • neznámá cena = konzervativní (vyšší) odhad, nikdy nula.
 * Dřív: FreeCloud při výpadku bezplatného serveru sám přešel na placený (Chutes, NanoGPT, DeepSeek…), konzilium a
 * výchozí pořadí samo bralo Claude → kredit se mohl utrácet bez souhlasu.
 */
class CostGuard(private val store: Store, private val price: (provider: String, model: String) -> Price = ::defaultPrice) {

    interface Store {
        /** uživatel výslovně povolil placená volání tohoto poskytovatele */
        fun allowed(provider: String): Boolean
        /** měsíční strop v USD (0 = nic) */
        fun budgetUsd(provider: String): Double
        /** utraceno tento měsíc (USD) */
        fun spentUsd(provider: String): Double
        fun addSpent(provider: String, usd: Double)
    }

    /** cena v USD za milion tokenů */
    data class Price(val inPerM: Double, val outPerM: Double, val flat: Boolean = false)

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: String) : Decision()
    }

    class BudgetException(msg: String) : EngineException(msg)

    fun estimateUsd(provider: String, model: String, inputChars: Int, maxOutTokens: Int): Double {
        val p = price(provider, model)
        if (p.flat) return 0.0
        return inputChars / 3.5 / 1e6 * p.inPerM + maxOutTokens / 1e6 * p.outPerM
    }

    fun check(provider: String, model: String, inputChars: Int, maxOutTokens: Int): Decision {
        if (!store.allowed(provider)) return Decision.Deny("$provider je placený a není povolený (Nastavení → Cloud → Náklady)")
        val budget = store.budgetUsd(provider)
        val spent = store.spentUsd(provider)
        val est = estimateUsd(provider, model, inputChars, maxOutTokens)
        if (spent + est > budget) return Decision.Deny(
            "$provider: rozpočet ${"%.2f".format(budget)} USD/měsíc by byl překročen (utraceno ${"%.2f".format(spent)}, odhad volání ${"%.3f".format(est)})")
        return Decision.Allow
    }

    fun record(provider: String, model: String, inputTokens: Long?, outputTokens: Long?, inputChars: Int, outputChars: Int) {
        val p = price(provider, model)
        if (p.flat) return
        val inT = inputTokens ?: (inputChars / 3.5).toLong()
        val outT = outputTokens ?: (outputChars / 3.5).toLong()
        store.addSpent(provider, inT / 1e6 * p.inPerM + outT / 1e6 * p.outPerM)
    }

    companion object {
        /**
         * Konzervativní ceník (horní odhady veřejných ceníků k 11/2026; skutečná cena bývá nižší).
         * Paušály (NanoGPT, Ollama Cloud, Synthetic) = flat: volání nestojí navíc, ale i tak vyžadují povolení.
         */
        fun defaultPrice(provider: String, model: String): Price {
            val m = model.lowercase()
            return when (provider) {
                "claude" -> when { "opus" in m -> Price(15.0, 75.0); "haiku" in m -> Price(1.0, 5.0); else -> Price(3.0, 15.0) }
                "kimi" -> Price(1.0, 4.0)
                "deepseek" -> Price(0.7, 2.5)
                "chutes" -> Price(1.0, 4.0)
                "nanogpt", "ollama", "synthetic" -> Price(0.0, 0.0, flat = true)
                else -> Price(3.0, 15.0) // neznámý = draze, ať se rozpočet nepodcení
            }
        }
    }
}

/** Obal placeného enginu: kontrola rozpočtu před voláním, zápis spotřeby po něm. */
class GuardedEngine(private val inner: LlmEngine, private val provider: String, private val model: String, private val guard: CostGuard) : LlmEngine by inner {
    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val chars = messages.sumOf { it.content.length }
        when (val d = guard.check(provider, model, chars, options.maxTokens)) {
            is CostGuard.Decision.Deny -> throw CostGuard.BudgetException("Placené volání zastaveno: ${d.reason}.")
            CostGuard.Decision.Allow -> Unit
        }
        val r = inner.generate(messages, options, cancel, onDelta)
        guard.record(provider, model, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length)
        return r
    }
}
