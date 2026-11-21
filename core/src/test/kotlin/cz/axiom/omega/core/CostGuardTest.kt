package cz.axiom.omega.core

import cz.axiom.omega.core.llm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** REBIRTH P0: žádné neautorizované placené volání; tvrdý strop; zápis spotřeby. */
class CostGuardTest {
    private class MemStore(val allow: MutableSet<String> = mutableSetOf(), val budget: MutableMap<String, Double> = mutableMapOf()) : CostGuard.Store {
        val spent = mutableMapOf<String, Double>()
        override fun allowed(provider: String) = provider in allow
        override fun budgetUsd(provider: String) = budget[provider] ?: 2.0
        override fun spentUsd(provider: String) = spent[provider] ?: 0.0
        override fun addSpent(provider: String, usd: Double) { spent[provider] = spentUsd(provider) + usd }
    }
    private class Fake(override val label: String, val reply: String = "ok", val fail: Exception? = null) : LlmEngine {
        override val id = label; override val kind = EngineKind.FREE; override val contextBudgetChars = 100_000
        var calls = 0
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            calls++; fail?.let { throw it }; return GenResult(reply, model = label, inputTokens = 1_000, outputTokens = 2_000)
        }
    }

    @Test fun deniedWithoutPermissionAndOverBudget() {
        val st = MemStore(); val g = CostGuard(st)
        assertTrue(g.check("claude", "claude-sonnet", 1_000, 1_000) is CostGuard.Decision.Deny)        // bez povolení
        st.allow += "claude"
        assertEquals(CostGuard.Decision.Allow, g.check("claude", "claude-sonnet", 1_000, 1_000))
        st.spent["claude"] = 1.99
        assertTrue(g.check("claude", "claude-opus", 100_000, 32_000) is CostGuard.Decision.Deny)       // odhad by přetekl strop
    }

    @Test fun guardedEngineBlocksAndRecords() {
        val st = MemStore(); val inner = Fake("claude")
        val e = GuardedEngine(inner, "claude", "claude-sonnet", CostGuard(st))
        assertFailsWith<CostGuard.BudgetException> { e.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
        assertEquals(0, inner.calls)                                                                    // volání vůbec neodešlo
        st.allow += "claude"
        e.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals(1_000 / 1e6 * 3.0 + 2_000 / 1e6 * 15.0, st.spentUsd("claude"), 1e-9)                // skutečné tokeny
    }

    @Test fun freeCloudNeverFallsBackToPaidWithoutPermission() {
        val free = FreeCloud.PROVIDERS.first { !it.paid }; val paid = FreeCloud.PROVIDERS.first { it.id == "chutes" }
        val members = listOf(FreeCloud.Member(free, "k", "m"), FreeCloud.Member(paid, "k", "kimi-k3"))
        val engines = mapOf(free.id to Fake("free", fail = EngineException("HTTP 429", retryable = true)), paid.id to Fake("chutes", "placená odpověď"))
        val st = MemStore()
        val fc = FreeCloudEngine(members, factory = { engines.getValue(it.provider.id) }, guard = CostGuard(st))
        val err = assertFailsWith<EngineException> { fc.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
        assertTrue("není povolený" in err.message.orEmpty(), err.message)
        assertEquals(0, (engines.getValue(paid.id) as Fake).calls)                                       // Chutes se nevolal
        st.allow += "chutes"
        assertEquals("placená odpověď", fc.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}.text)
        assertTrue(st.spentUsd("chutes") > 0)
    }

    @Test fun flatPlansCostNothingButNeedPermission() {
        val st = MemStore(); val g = CostGuard(st)
        assertTrue(g.check("synthetic", "x", 10_000, 8_000) is CostGuard.Decision.Deny)
        st.allow += "synthetic"; st.budget["synthetic"] = 0.0
        assertEquals(CostGuard.Decision.Allow, g.check("synthetic", "x", 10_000, 8_000))
    }
}
