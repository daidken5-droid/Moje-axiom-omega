package cz.axiom.vega

import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.llm.CostGuard
import cz.axiom.omega.core.llm.FreeCloud
import cz.axiom.omega.core.llm.FreeCloudEngine
import cz.axiom.omega.core.llm.GuardedEngine
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.llm.LlmEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * CLOUDY přes adaptéry z AVIORu, PŘÍSNÝ režim hlídání nákladů (rezervace před voláním, vyrovnání po něm,
 * přerušené volání se počítá, čeština ~2,5 znaku na token).
 *
 * Pořadí: nejdřív VŠE ZDARMA (Gemini → Groq → Cerebras → OpenRouter), placené až potom a jen povolené
 * (kredit Pro → DeepSeek). Výchozí automatická placená spotřeba 0 Kč. Schválení po náhledu platí jen pro
 * JEDEN úkol (běh) a po něm se smaže.
 */
class Engines(private val settings: Settings, private val vault: Vault) {
    /** Schválení placeného poskytovatele pro právě běžící úkol: poskytovatel → STROP útraty celého úkolu (USD, absolutně). */
    private val runApprovals = ConcurrentHashMap<String, Double>()

    val NAMES = linkedMapOf("gemini" to "Gemini (zdarma)", "groq" to "Groq", "cerebras" to "Cerebras", "openrouter" to "OpenRouter",
        "geminipro" to "Gemini – kredit Pro", "deepseek" to "DeepSeek", "claude" to "Claude")
    private val CHAIN = listOf("gemini", "groq", "cerebras", "openrouter", "geminipro", "deepseek")
    val PROVIDERS = CHAIN + "claude"
    val PAID = setOf("geminipro", "deepseek", "claude")
    fun name(id: String) = NAMES[id] ?: id

    /** Útrata poskytovatelů BEZ vlastního stropu (kredit Pro má svůj – nesmí ujídat společný). */
    private fun sharedSpentUsd() = PAID.filter { !settings.hasOwnBudget(it) }.sumOf { settings.spentUsd(it) }

    val guard = CostGuard(object : CostGuard.Store {
        override fun allowed(provider: String) = runApprovals.containsKey(provider) || autoAllowed(provider)
        override fun budgetUsd(provider: String): Double {
            runApprovals[provider]?.let { return it }
            val usdKc = settings.usdKc.toDouble()
            // vlastní strop poskytovatele, jinak SPOLEČNÝ strop pro všechny placené dohromady (ne „500 Kč každému“)
            return if (settings.hasOwnBudget(provider)) settings.budgetKc(provider) / usdKc
            else settings.budgetKc / usdKc - (sharedSpentUsd() - settings.spentUsd(provider))
        }
        override fun spentUsd(provider: String) = settings.spentUsd(provider)
        override fun addSpent(provider: String, usd: Double) = settings.addSpent(provider, usd)
    }, charsPerToken = 2.5, strict = true)

    fun autoAllowed(p: String) = settings.allowed(p) && settings.budgetKc(p) > 0

    /** Schválí úkol: strop = dosavadní útrata + [usd] (celý úkol, ne každé volání zvlášť). */
    fun approveRun(provider: String, usd: Double) { runApprovals[provider] = settings.spentUsd(provider) + usd.coerceIn(0.0, 5.0) }
    /** Konec úkolu (i chyba, zrušení) → schválení zaniká. */
    fun clearRun() = runApprovals.clear()

    fun configured(): List<String> = PROVIDERS.filter { vault.has(it) }

    data class Pick(val engine: LlmEngine, val provider: String, val model: String, val paid: Boolean, val chain: List<Pair<String, String>>, val notes: List<String>, val paidIds: List<String>)

    /** Vlastní server uživatele (model s otevřenými vahami). Nekomerční – data jdou jen na jeho server. */
    private fun ownProvider() = FreeCloud.Provider("own", "Vlastní model", settings.ownUrl, preferred = listOf(settings.ownModel.ifBlank { "default" }),
        signup = "", maxOut = 4096, contextChars = 24_000, dataNote = "Vlastní server (${settings.ownUrl}) – podklady jdou jen tam, žádné komerční API.")

    fun ownConfigured() = settings.ownUrl.isNotBlank()

    private fun member(id: String): FreeCloud.Member? {
        if (id == "own") return if (ownConfigured()) FreeCloud.Member(ownProvider(), vault.read("own") ?: "", settings.model("own") ?: settings.ownModel.ifBlank { "default" }) else null
        val p = FreeCloud.provider(id) ?: return null
        val key = vault.read(id)?.takeIf { it.isNotBlank() } ?: return null
        return FreeCloud.Member(p, key, settings.model(id) ?: FreeCloud.pickModel(p, emptyList()))
    }

    private fun usable(m: FreeCloud.Member) = !m.provider.paid || runApprovals.containsKey(m.provider.id) || autoAllowed(m.provider.id)

    /** Každý pokus o volání modelu se započítá po poskytovatelích (komerční × vlastní). */
    private fun counted(m: FreeCloud.Member): cz.axiom.omega.core.llm.LlmEngine {
        val inner = FreeCloud.engine(m)
        return object : cz.axiom.omega.core.llm.LlmEngine by inner {
            override fun generate(messages: List<cz.axiom.omega.core.llm.ChatMessage>, options: cz.axiom.omega.core.llm.GenOptions, cancel: cz.axiom.omega.core.llm.CancelToken, onDelta: (cz.axiom.omega.core.llm.Delta) -> Unit): cz.axiom.omega.core.llm.GenResult {
                settings.countRequest(m.provider.id); return inner.generate(messages, options, cancel, onDelta)
            }
        }
    }

    private fun engineOf(chain: List<FreeCloud.Member>) = FreeCloudEngine(chain, factory = ::counted, guard = guard,
        listModels = { p, k -> KimiEngine.listModels(p.base, k) },
        onModel = { id, model -> settings.setModel(id, model) },
        onUsage = { id, model, inT, outT ->
            // stínové měření: bezplatný klíč počítat, jako by byl placený (odhalí omylem vložený klíč s fakturací)
            if (id == "gemini") { val pr = CostGuard.defaultPrice("geminipro", model); settings.addShadow(((inT ?: 0) * pr.inPerM + (outT ?: 0) * pr.outPerM) / 1e6) }
        })

    fun pick(): Pick? {
        // POUZE VLASTNÍ MODELY: jediný člen = vlastní server; žádná záloha na komerční API (ani při výpadku)
        if (settings.ownOnly) return member("own")?.let { m -> Pick(engineOf(listOf(m)), "own", m.model, false, listOf(m.provider.name to m.model), listOf(m.provider.dataNote), emptyList()) }
        // vlastní server (je-li nastavený) jde první – je zdarma a soukromý
        val chain = (listOf("own") + CHAIN).mapNotNull { member(it) }.filter { usable(it) }
        if (chain.isNotEmpty()) {
            val f = chain.first()
            return Pick(engineOf(chain), f.provider.id, f.model, chain.any { it.provider.paid }, chain.map { it.provider.name to it.model },
                chain.map { it.provider.dataNote }.filter { it.isNotBlank() }.distinct(), chain.filter { it.provider.paid }.map { it.provider.id })
        }
        if (vault.has("claude") && (runApprovals.containsKey("claude") || autoAllowed("claude"))) return single("claude")
        return null
    }

    /** Jen jeden konkrétní poskytovatel (schválený placený běh). */
    fun single(id: String): Pick? {
        if (id == "claude") {
            val k = vault.read("claude") ?: return null; val m = settings.claudeModel
            val g = GuardedEngine(ClaudeEngine(k, m), "claude", m, guard)
            val counted = object : cz.axiom.omega.core.llm.LlmEngine by g {
                override fun generate(messages: List<cz.axiom.omega.core.llm.ChatMessage>, options: cz.axiom.omega.core.llm.GenOptions, cancel: cz.axiom.omega.core.llm.CancelToken, onDelta: (cz.axiom.omega.core.llm.Delta) -> Unit) =
                    g.generate(messages, options, cancel, onDelta).also { settings.countRequest("claude") }
            }
            return Pick(counted, "claude", m, true, listOf("Claude" to m), listOf("Anthropic API: obsah se ve výchozím stavu nepoužívá k tréninku."), listOf("claude"))
        }
        val m = member(id) ?: return null
        return Pick(engineOf(listOf(m)), id, m.model, m.provider.paid, listOf(m.provider.name to m.model), listOf(m.provider.dataNote), if (m.provider.paid) listOf(id) else emptyList())
    }

    /** Placení poskytovatelé s klíčem, kteří teď automaticky neprojdou (nabídka schválení, když zdarma došlo). */
    fun pendingPaid(): List<String> = if (settings.ownOnly) emptyList() else PROVIDERS.filter { it in PAID && vault.has(it) && !autoAllowed(it) && !runApprovals.containsKey(it) }

    /** Odhad v Kč pro jedno volání konkrétního poskytovatele (u Gemini podle uloženého modelu). */
    fun estimateKc(provider: String, inputChars: Int, maxOut: Int = 4096): Double {
        val model = if (provider == "claude") settings.claudeModel else settings.model(provider) ?: FreeCloud.provider(provider)?.let { FreeCloud.pickModel(it, emptyList()) } ?: ""
        return guard.estimateUsd(provider, model, inputChars, maxOut) * settings.usdKc
    }

    /** Nejhorší případ řetězu: když zdarma dojde, kolik může stát placený člen. */
    fun worstKc(p: Pick, inputChars: Int, maxOut: Int) = p.paidIds.maxOfOrNull { estimateKc(it, inputChars, maxOut) } ?: 0.0

    data class KeyCheck(val ok: Boolean, val message: String, val model: String?, val models: Int)

    /**
     * OVĚŘENÍ KLÍČE hned po uložení (bez útraty: jen seznam modelů): funguje? který model bude odpovídat?
     * U Gemini řekne, jestli klíč už má Gemini 4 Hydrogen (Google ho zpřístupňuje postupně).
     */
    fun check(provider: String): KeyCheck {
        if (provider == "own") return try {
            require(ownConfigured()) { "Nejdřív zadej adresu serveru." }
            val list = KimiEngine.listModels(settings.ownUrl, vault.read("own"))
            val m = settings.ownModel.takeIf { it.isNotBlank() && list.any { x -> x.equals(it, true) } } ?: list.firstOrNull() ?: settings.ownModel
            settings.setModel("own", m)
            KeyCheck(true, "Vlastní server odpovídá · model $m (${list.size} v nabídce).", m, list.size)
        } catch (e: Exception) { KeyCheck(false, "Server neodpovídá: ${e.message?.substringBefore(" [{")?.take(140)}", null, 0) }
        val key = vault.read(provider) ?: return KeyCheck(false, "Klíč není uložený.", null, 0)
        return try {
            if (provider == "claude") { val l = ClaudeEngine.listModels(key); return KeyCheck(true, "Claude funguje · ${l.size} modelů.", settings.claudeModel, l.size) }
            val p = FreeCloud.provider(provider) ?: return KeyCheck(false, "Neznámý poskytovatel.", null, 0)
            val list = KimiEngine.listModels(p.base, key)
            val best = FreeCloud.pickModel(p, list)
            settings.setModel(provider, best)
            val hydrogen = list.any { it.contains("hydrogen", true) }
            val gem = if (provider.startsWith("gemini")) (if (hydrogen) " Gemini 4 Hydrogen je pro tvůj klíč dostupný." else " Gemini 4 Hydrogen pro tvůj klíč zatím není v nabídce (Google ho zpřístupňuje postupně) – odpovídá nejsilnější dostupný model.") else ""
            val price = if (p.paid) CostGuard.defaultPrice(provider, best).let { " Cena: ${it.inPerM}/${it.outPerM} USD za milion tokenů vstupu/výstupu." } else ""
            KeyCheck(true, "${p.name} funguje · odpovídat bude $best.$gem$price", best, list.size)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            KeyCheck(false, when {
                Regex("HTTP 40[013]").containsMatchIn(msg) && Regex("(?i)key|klíč|API_KEY|auth|permission").containsMatchIn(msg) || Regex("HTTP 40[13]").containsMatchIn(msg) ->
                    "Klíč nefunguje (neplatný nebo bez oprávnění). Zkontroluj, že je celý a z účtu, kde je Gemini API povolené."
                Regex("HTTP 429").containsMatchIn(msg) -> "Klíč funguje, ale limit je teď vyčerpaný – zkus to za chvíli."
                generateSequence(e as Throwable) { it.cause }.any { it is java.net.UnknownHostException || it is java.net.ConnectException } -> "Bez připojení – klíč jsem uložil, ověřím ho, až bude síť."
                else -> "Ověření selhalo: ${msg.substringBefore(" [{").substringBefore(" {").take(140)}"
            }, null, 0)
        }
    }
}
