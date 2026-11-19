package cz.axiom.vega

import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.llm.CostGuard
import cz.axiom.omega.core.llm.FreeCloud
import cz.axiom.omega.core.llm.FreeCloudEngine
import cz.axiom.omega.core.llm.GuardedEngine
import cz.axiom.omega.core.llm.LlmEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * CLOUDY přes adaptéry z AVIORu (OpenAI-kompatibilní Groq/Cerebras/OpenRouter/DeepSeek…, Claude SDK).
 * Výchozí automatická placená spotřeba 0 Kč: placený poskytovatel projde jen (a) s povolením a rozpočtem v Nastavení,
 * nebo (b) po náhledu a schválení KONKRÉTNÍHO běhu (jednorázový limit). Zdarma poskytovatelé jdou bez schvalování.
 */
class Engines(private val settings: Settings, private val vault: Vault) {
    /** Jednorázová schválení: poskytovatel → limit v USD pro jedno volání. */
    private val approvals = ConcurrentHashMap<String, Double>()

    val guard = CostGuard(object : CostGuard.Store {
        override fun allowed(provider: String) = approvals.containsKey(provider) || (settings.allowed(provider) && settings.budgetKc > 0)
        override fun budgetUsd(provider: String) = approvals[provider]?.let { it + settings.spentUsd(provider) } ?: (settings.budgetKc / settings.usdKc.toDouble())
        override fun spentUsd(provider: String) = settings.spentUsd(provider)
        override fun addSpent(provider: String, usd: Double) { settings.addSpent(provider, usd); approvals.remove(provider) }
    })

    fun approveOnce(provider: String, usd: Double) { approvals[provider] = usd.coerceIn(0.0, 5.0) }

    val PROVIDERS = listOf("groq", "cerebras", "openrouter", "deepseek", "claude")

    fun configured(): List<String> = PROVIDERS.filter { vault.has(it) }

    data class Pick(val engine: LlmEngine, val provider: String, val model: String, val paid: Boolean)

    /** Nejlepší dostupný: zdarma (s přepnutím při limitu) → placený (přes CostGuard). */
    fun pick(): Pick? {
        val free = FreeCloud.order(quality = false).mapNotNull { p ->
            val key = vault.read(p.id)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FreeCloud.Member(p, key, FreeCloud.pickModel(p, emptyList()))
        }
        if (free.any { !it.provider.paid }) {
            val f = free.first { !it.provider.paid }
            return Pick(FreeCloudEngine(free, guard = guard), f.provider.id, f.model, false)
        }
        vault.read("claude")?.let { k -> val m = settings.claudeModel; return Pick(GuardedEngine(ClaudeEngine(k, m), "claude", m, guard), "claude", m, true) }
        free.firstOrNull()?.let { f -> return Pick(FreeCloudEngine(free, guard = guard), f.provider.id, f.model, true) }
        return null
    }

    /** Odhad ceny v Kč pro náhled před schválením. */
    fun estimateKc(p: Pick, inputChars: Int, maxOut: Int = 4096) = if (!p.paid) 0.0 else guard.estimateUsd(p.provider, p.model, inputChars, maxOut) * settings.usdKc
}
