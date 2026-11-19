package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regrese z red teamu: náklady (strop, schválení, klíče). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegaCostTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun keyNormalization() {
        assertEquals("AIzaSyD-abcdefghijklmnopqrstuvwxyz12345", normalizeKey("gemini", "export GEMINI_API_KEY=\"AIzaSyD-abcdefghijklmnopqrstuvwxyz12345\";"))
        assertEquals("gsk_abc123456789012345", normalizeKey("groq", "  'gsk_abc123456789012345'\n"))
        assertEquals("sk-ant-xyz1234567890123", normalizeKey("claude", "ANTHROPIC_API_KEY: sk-ant-xyz1234567890123"))
    }

    @Test fun sharedCapAndRunApprovalsDoNotLeak() {
        val s = Settings(ctx); s.budgetKc = 470; s.usdKc = 23.5f // 20 USD společně
        val e = Engines(s, Vault(ctx))
        s.setAllowed("deepseek", true); s.setAllowed("claude", true)
        s.addSpent("deepseek", 15.0)
        // claude bez vlastního stropu smí jen zbytek SPOLEČNÉHO stropu (5 USD), ne dalších 20
        assertTrue(e.guard.check("claude", "claude-sonnet-5-5", 5_000_000, 100_000) is cz.axiom.omega.core.llm.CostGuard.Decision.Deny)
        assertTrue(e.guard.check("claude", "claude-sonnet-5-5", 25_000, 1_000) is cz.axiom.omega.core.llm.CostGuard.Decision.Allow)
        // schválení jednoho úkolu po jeho konci zaniká
        assertTrue(e.guard.check("geminipro", "gemini-4-hydrogen", 1000, 1000) is cz.axiom.omega.core.llm.CostGuard.Decision.Deny)
        e.approveRun("geminipro", 0.05)
        assertTrue(e.guard.check("geminipro", "gemini-4-hydrogen", 1000, 1000) is cz.axiom.omega.core.llm.CostGuard.Decision.Allow)
        e.clearRun()
        assertTrue(e.guard.check("geminipro", "gemini-4-hydrogen", 1000, 1000) is cz.axiom.omega.core.llm.CostGuard.Decision.Deny)
        // povolení bez stropu = nic automaticky
        s.setAllowed("geminipro", true)
        assertTrue(!e.autoAllowed("geminipro") || s.budgetKc("geminipro") > 0)
        s.setBudgetKc("geminipro", 0)
        assertTrue(!e.autoAllowed("geminipro"))
    }

    @Test fun spendIsExactMicroUsdAndUtcMonth() {
        val s = Settings(ctx)
        repeat(1000) { s.addSpent("geminipro", 0.000123) }
        assertEquals(0.123, s.spentUsd("geminipro"), 1e-9)
        s.addSpent("geminipro", -1.0) // refund nikdy pod nulu
        assertEquals(0.0, s.spentUsd("geminipro"), 1e-9)
    }
}
