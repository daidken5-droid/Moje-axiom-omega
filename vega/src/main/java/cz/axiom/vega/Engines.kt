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
 * CLOUDY přes adaptéry z AVIORu. Pořadí pro VEGA (parťák na práci = kvalita):
 *   Gemini zdarma → Gemini kredit Pro (jen povolený, v rozpočtu) → Groq → Cerebras → OpenRouter → DeepSeek → Claude.
 * Výchozí automatická placená spotřeba 0 Kč: placený člen řetězu se bez povolení PŘESKOČÍ (nikdy tichý přechod na placené);
 * když nic zdarma neprojde, VEGA ukáže náhled ceny a čeká na schválení konkrétního běhu.
 */
class Engines(private val settings: Settings, private val vault: Vault) {
    /** Jednorázová schválení: poskytovatel → limit v USD pro jedno volání. */
    private val approvals = ConcurrentHashMap<String, Double>()

    val guard = CostGuard(object : CostGuard.Store {
        override fun allowed(provider: String) = approvals.containsKey(provider) || (settings.allowed(provider) && settings.budgetKc(provider) > 0)
        override fun budgetUsd(provider: String) = approvals[provider]?.let { it + settings.spentUsd(provider) } ?: (settings.budgetKc(provider) / settings.usdKc.toDouble())
        override fun spentUsd(provider: String) = settings.spentUsd(provider)
        override fun addSpent(provider: String, usd: Double) { settings.addSpent(provider, usd); approvals.remove(provider) }
    })

    fun approveOnce(provider: String, usd: Double) { approvals[provider] = usd.coerceIn(0.0, 5.0) }

    /** Pořadí řetězu (zdarma i placení OpenAI-kompatibilní); Claude zvlášť (vlastní SDK). */
    private val CHAIN = listOf("gemini", "geminipro", "groq", "cerebras", "openrouter", "deepseek")
    val PROVIDERS = CHAIN + "claude"
    val PAID = setOf("geminipro", "deepseek", "claude")

    fun configured(): List<String> = PROVIDERS.filter { vault.has(it) }

    data class Pick(val engine: LlmEngine, val provider: String, val model: String, val paid: Boolean, val chain: List<Pair<String, String>>, val notes: List<String>)

    private fun members(): List<FreeCloud.Member> = CHAIN.mapNotNull { id ->
        val p = FreeCloud.provider(id) ?: return@mapNotNull null
        val key = vault.read(id)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        FreeCloud.Member(p, key, settings.model(id) ?: FreeCloud.pickModel(p, emptyList()))
    }

    /** Placený člen, který by teď neprošel (bez povolení / bez rozpočtu), se do řetězu ani do souhlasu nepočítá. */
    private fun usable(m: FreeCloud.Member) = !m.provider.paid || approvals.containsKey(m.provider.id) || (settings.allowed(m.provider.id) && settings.budgetKc(m.provider.id) > 0)

    fun pick(): Pick? {
        val all = members()
        val chain = all.filter { usable(it) }
        if (chain.isNotEmpty()) {
            val f = chain.first()
            return Pick(FreeCloudEngine(chain, guard = guard, onModel = { id, model -> settings.setModel(id, model) }), f.provider.id, f.model, f.provider.paid,
                chain.map { it.provider.name to it.model }, chain.map { it.provider.dataNote }.filter { it.isNotBlank() }.distinct())
        }
        vault.read("claude")?.let { k -> val m = settings.claudeModel; return Pick(GuardedEngine(ClaudeEngine(k, m), "claude", m, guard), "claude", m, true, listOf("Claude" to m), listOf("Anthropic API: obsah se ve výchozím stavu nepoužívá k tréninku.")) }
        // jen placení členové bez povolení → nabídnout první z nich (UI se zeptá na schválení běhu)
        all.firstOrNull()?.let { f -> return Pick(FreeCloudEngine(listOf(f), guard = guard), f.provider.id, f.model, true, listOf(f.provider.name to f.model), listOf(f.provider.dataNote)) }
        return null
    }

    /** Odhad ceny v Kč pro náhled před schválením. */
    fun estimateKc(p: Pick, inputChars: Int, maxOut: Int = 4096) = if (!p.paid) 0.0 else guard.estimateUsd(p.provider, p.model, inputChars, maxOut) * settings.usdKc

    data class KeyCheck(val ok: Boolean, val message: String, val model: String?, val models: Int)

    /**
     * OVĚŘENÍ KLÍČE hned po uložení (bez útraty: jen seznam modelů): funguje? který model bude odpovídat?
     * U Gemini řekne, jestli klíč už má Gemini 4 Hydrogen (Google ho zpřístupňuje postupně).
     */
    fun check(provider: String): KeyCheck {
        val key = vault.read(provider) ?: return KeyCheck(false, "Klíč není uložený.", null, 0)
        return try {
            if (provider == "claude") { val l = ClaudeEngine.listModels(key); return KeyCheck(true, "Claude funguje · ${l.size} modelů.", settings.claudeModel, l.size) }
            val p = FreeCloud.provider(provider) ?: return KeyCheck(false, "Neznámý poskytovatel.", null, 0)
            val list = KimiEngine.listModels(p.base, key)
            val best = FreeCloud.pickModel(p, list)
            settings.setModel(provider, best)
            val hydrogen = list.any { it.contains("hydrogen", true) }
            val gem = if (provider.startsWith("gemini")) (if (hydrogen) " Gemini 4 Hydrogen je pro tvůj klíč dostupný." else " Gemini 4 Hydrogen pro tvůj klíč zatím není v nabídce (Google ho zpřístupňuje postupně) – odpovídá nejsilnější dostupný model.") else ""
            KeyCheck(true, "${p.name} funguje · odpovídat bude $best.$gem", best, list.size)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            KeyCheck(false, when {
                Regex("HTTP 40[13]").containsMatchIn(msg) -> "Klíč nefunguje (neplatný nebo bez oprávnění). Zkontroluj, že je celý a z účtu, kde je Gemini API povolené."
                Regex("HTTP 429").containsMatchIn(msg) -> "Klíč funguje, ale limit je teď vyčerpaný – zkus to za chvíli."
                e is java.net.UnknownHostException || e.cause is java.net.UnknownHostException -> "Bez připojení – klíč jsem uložil, ověřím ho, až bude síť."
                else -> "Ověření selhalo: ${msg.take(160)}"
            }, null, 0)
        }
    }
}
