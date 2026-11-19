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
class CostGuard(
    private val store: Store,
    private val price: (provider: String, model: String) -> Price = ::defaultPrice,
    /** znaků na token pro odhad (čeština se tokenizuje hůř než angličtina: ~2,5–3) */
    val charsPerToken: Double = 3.5,
    /**
     * PŘÍSNÝ REŽIM (VEGA): odhad se ZAREZERVUJE před voláním a po něm vyrovná na skutečnost; přerušené / zrušené volání
     * po přijetí odpovědi rezervaci ponechá (tokeny už byly naúčtované); bez údajů o spotřebě u přemýšlejících modelů
     * se počítá celý limit výstupu.
     */
    val strict: Boolean = false,
) {

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

    class BudgetException(msg: String, val provider: String? = null, val model: String? = null, val estUsd: Double = 0.0) : EngineException(msg)

    fun priceOf(provider: String, model: String) = price(provider, model)

    fun estimateUsd(provider: String, model: String, inputChars: Int, maxOutTokens: Int): Double {
        val p = price(provider, model)
        if (p.flat) return 0.0
        return inputChars / charsPerToken / 1e6 * p.inPerM + maxOutTokens / 1e6 * p.outPerM
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
        val inT = inputTokens ?: (inputChars / charsPerToken).toLong()
        val outT = outputTokens ?: (outputChars / charsPerToken).toLong()
        store.addSpent(provider, inT / 1e6 * p.inPerM + outT / 1e6 * p.outPerM)
    }

    // ------------------------------------------------------------------ přísný režim: rezervace a vyrovnání

    /** Zarezervuje odhad (vstup + celý limit výstupu) – útrata je vidět a hlídá strop ještě během volání. */
    fun reserve(provider: String, model: String, inputChars: Int, maxOutTokens: Int): Double {
        val e = estimateUsd(provider, model, inputChars, maxOutTokens)
        if (e > 0) store.addSpent(provider, e)
        return e
    }

    /** Po úspěchu: rezervace → skutečnost (tokeny od poskytovatele; bez nich u přemýšlejících modelů celý limit výstupu). */
    fun settle(provider: String, model: String, reserved: Double, inputTokens: Long?, outputTokens: Long?, inputChars: Int, outputChars: Int, maxOutTokens: Int) {
        val p = price(provider, model)
        if (p.flat) { if (reserved != 0.0) store.addSpent(provider, -reserved); return }
        val inT = inputTokens ?: (inputChars / charsPerToken).toLong()
        val thinking = Regex("(?i)gemini|reason|gpt-oss|o\\d|r1|thinking").containsMatchIn(model)
        val outT = outputTokens ?: if (thinking) maxOutTokens.toLong() else (outputChars / charsPerToken).toLong()
        store.addSpent(provider, inT / 1e6 * p.inPerM + outT / 1e6 * p.outPerM - reserved)
    }

    /** Volání neprošlo k poskytovateli (HTTP chyba před odpovědí, síť) → nic se neúčtovalo, rezervace zpět. */
    fun refund(provider: String, reserved: Double) { if (reserved > 0) store.addSpent(provider, -reserved) }

    companion object {
        /** Chyba vznikla dřív, než poskytovatel začal odpovídat (nic nenaúčtoval)? */
        fun preResponse(e: Throwable): Boolean = e is EngineException && !e.billed


        /**
         * Konzervativní ceník (horní odhady veřejných ceníků k 11/2026; skutečná cena bývá nižší).
         * Paušály (NanoGPT, Ollama Cloud, Synthetic) = flat: volání nestojí navíc, ale i tak vyžadují povolení.
         */
        fun defaultPrice(provider: String, model: String): Price {
            val m = model.lowercase()
            return when (provider) {
                "claude" -> when { "opus" in m -> Price(15.0, 75.0); "haiku" in m -> Price(1.0, 5.0); else -> Price(3.0, 15.0) }
                "kimi" -> Price(1.0, 4.0)
                // Gemini API placený projekt (kredit Google AI Pro): Hydrogen 1,50/7,50 USD za 1M (ceník Google 11/2026),
                // ostatní horním odhadem; myšlenkové tokeny se účtují jako výstup (KimiEngine je bere z total_tokens)
                "geminipro" -> when { "hydrogen" in m -> Price(1.5, 7.5); Regex("-pro\\b|-pro-").containsMatchIn(m) -> Price(4.0, 18.0); "flash" in m -> Price(1.0, 4.0); else -> Price(3.0, 15.0) }
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
            is CostGuard.Decision.Deny -> throw CostGuard.BudgetException("Placené volání zastaveno: ${d.reason}.", provider, model, guard.estimateUsd(provider, model, chars, options.maxTokens))
            CostGuard.Decision.Allow -> Unit
        }
        if (!guard.strict) {
            val r = inner.generate(messages, options, cancel, onDelta)
            guard.record(provider, model, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length)
            return r
        }
        val reserved = guard.reserve(provider, model, chars, options.maxTokens)
        val r = try { inner.generate(messages, options, cancel, onDelta) } catch (e: CancelledException) { throw e } catch (e: Exception) {
            // jiný SDK (Claude): HTTP chyby před odpovědí poznáme podle kódu v textu → vrátit; jinak konzervativně ponechat
            // vrátit JEN chybu, která jasně přišla před odpovědí (HTTP kód / přetížení); vše ostatní (výpadek streamu,
            // prázdná odpověď, odmítnutí) mohlo být naúčtováno → rezervace zůstává
            val pre = e is EngineException && !e.billed && Regex("HTTP [45]\\d\\d|\\b(400|401|403|404|413|429|500|502|503|529)\\b|overloaded_error|rate_limit_error|authentication_error|permission_error", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
            if (pre) guard.refund(provider, reserved)
            throw e
        }
        guard.settle(provider, model, reserved, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length, options.maxTokens)
        return r
    }
}
