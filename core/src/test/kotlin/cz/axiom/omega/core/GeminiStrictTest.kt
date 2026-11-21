package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.CancelledException
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.CostGuard
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.FreeCloud
import cz.axiom.omega.core.llm.FreeCloudEngine
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.Role
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regrese z red teamu (přísný režim CostGuard, VEGA). MOCK server, ne živé API. */
class GeminiStrictTest {
    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private fun serve(handler: (String) -> Pair<Int, String>, hits: MutableList<String> = mutableListOf()): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1beta/openai/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString(); hits += body
            val (code, payload) = handler(body); val b = payload.toByteArray()
            ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.createContext("/v1beta/openai/models") { ex ->
            val b = """{"data":[{"id":"models/gemini-4-hydrogen"},{"id":"models/gemini-2.5-flash"},{"id":"models/gemini-3.1-pro-preview"}]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); servers += s
        return "http://127.0.0.1:${s.address.port}/v1beta/openai"
    }

    private class Spend { var usd = 0.0 }
    private fun guard(sp: Spend, allowed: Boolean = true, budget: Double = 10.0) = CostGuard(object : CostGuard.Store {
        override fun allowed(provider: String) = allowed
        override fun budgetUsd(provider: String) = budget
        override fun spentUsd(provider: String) = sp.usd
        override fun addSpent(provider: String, usd: Double) { sp.usd += usd }
    }, charsPerToken = 2.5, strict = true)

    private val pro = FreeCloud.provider("geminipro")!!
    private val free = FreeCloud.provider("gemini")!!
    private val ok = "data: {\"choices\":[{\"delta\":{\"content\":\"Ahoj\"}}]}\n\ndata: [DONE]\n\n"

    @Test fun streamErrorAfterResponseKeepsReservationAndDoesNotPayTwice() {
        val sp = Spend()
        val hits = mutableListOf<String>()
        // placený člen: 200 + chyba ve streamu (tokeny naúčtované) → rezervace zůstane, záloha se NEvolá
        val bad = serve({ 200 to "data: {\"error\":{\"message\":\"internal\"}}\n\n" })
        val backup = serve({ 200 to ok }, hits)
        val eng = FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = bad), "k-123456789012345", "gemini-4-hydrogen"), FreeCloud.Member(free.copy(base = backup), "k-free-1234567890", "gemini-2.5-flash")), guard = guard(sp))
        assertFailsWith<EngineException> { eng.generate(listOf(ChatMessage(Role.USER, "x".repeat(2500))), GenOptions(maxTokens = 1000), CancelToken()) {} }
        // rezervace: 2500/2,5 = 1000 vstup × 1,5 + 1000 výstup × 7,5 → 0,009 USD
        assertEquals(0.009, sp.usd, 1e-6)
        assertTrue(hits.isEmpty())
    }

    @Test fun preResponseErrorRefundsAndFallsBack() {
        val sp = Spend()
        val limited = serve({ 429 to """{"error":{"message":"Resource exhausted"}}""" })
        val backup = serve({ 200 to ok })
        val r = FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = limited), "k-123456789012345", "gemini-4-hydrogen"), FreeCloud.Member(free.copy(base = backup), "k-free-1234567890", "gemini-2.5-flash")), guard = guard(sp))
            .generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals("Ahoj", r.text); assertEquals(0.0, sp.usd, 1e-9)
    }

    @Test fun cancelKeepsReservation() {
        val sp = Spend(); val c = CancelToken()
        val slow = serve({ Thread.sleep(400); 200 to ok })
        val eng = FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = slow), "k-123456789012345", "gemini-4-hydrogen")), guard = guard(sp))
        Thread { Thread.sleep(100); c.cancel() }.start()
        assertFailsWith<CancelledException> { eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(maxTokens = 1000), c) {} }
        assertTrue(sp.usd > 0.007, "rezervace ${sp.usd}")
    }

    @Test fun missingUsageForThinkingModelCountsFullOutputLimit() {
        val sp = Spend()
        val base = serve({ 200 to ok })
        FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = base), "k-123456789012345", "gemini-4-hydrogen")), guard = guard(sp))
            .generate(listOf(ChatMessage(Role.USER, "x".repeat(250))), GenOptions(maxTokens = 2000), CancelToken()) {}
        // 100 tok vstup × 1,5 + 2000 výstup × 7,5 (myšlení neviditelné) = 0,01515
        assertEquals(0.01515, sp.usd, 1e-6)
    }

    @Test fun dailyFreeQuotaDoesNotDowngradeModelAndSummaryIsCzech() {
        val healed = mutableListOf<String>()
        val base = serve({ 429 to """[{"error":{"code":429,"message":"Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 250, model: gemini-4-hydrogen GenerateRequestsPerDayPerProjectPerModel"}}]""" })
        val e = assertFailsWith<FreeCloudEngine.ChainException> {
            FreeCloudEngine(listOf(FreeCloud.Member(free.copy(base = base), "k-free-1234567890", "gemini-4-hydrogen")), guard = guard(Spend()), onModel = { _, m -> healed += m })
                .generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        }
        assertTrue(healed.isEmpty())
        assertEquals("Gemini: denní limit zdarma vyčerpán", e.message)
        assertTrue(e.failures.single().rateLimited && !e.failures.single().budget)
    }

    @Test fun paidMemberNeverHealsToPricierModel() {
        val sp = Spend()
        val base = serve({ b -> if (b.contains("hydrogen")) 404 to """{"error":{"message":"model not found"}}""" else 200 to ok })
        val hits = mutableListOf<String>()
        val eng = FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = base, preferred = listOf("gemini-4-hydrogen")), "k-123456789012345", "gemini-4-hydrogen")), guard = guard(sp),
            listModels = { _, _ -> listOf("gemini-3.1-pro-preview") }, onModel = { _, m -> hits += m })
        assertFailsWith<FreeCloudEngine.ChainException> { eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
        assertTrue(hits.isEmpty())
    }

    @Test fun deniedPaidMemberIsReportedForApproval() {
        val base = serve({ 200 to ok })
        val e = assertFailsWith<FreeCloudEngine.ChainException> {
            FreeCloudEngine(listOf(FreeCloud.Member(pro.copy(base = base), "k-123456789012345", "gemini-4-hydrogen")), guard = guard(Spend(), allowed = false))
                .generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        }
        assertTrue(e.failures.single().budget); assertEquals("geminipro", e.failures.single().provider)
    }

    @Test fun unknownCodenameNeverPickedAutomatically() {
        assertEquals("gemini-2.5-flash", FreeCloud.pickModel(free, listOf("models/gemini-4-argon", "models/gemini-2.5-flash")))
    }
}
