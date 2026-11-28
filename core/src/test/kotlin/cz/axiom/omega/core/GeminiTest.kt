package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.CostGuard
import cz.axiom.omega.core.llm.FreeCloud
import cz.axiom.omega.core.llm.FreeCloudEngine
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.Role
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gemini API (OpenAI-kompatibilní vrstva) – bezplatná kvóta a kredit Google AI Pro. Server je náhrada (MOCK), ne živé API. */
class GeminiTest {
    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private val gemini = FreeCloud.provider("gemini")!!
    private val pro = FreeCloud.provider("geminipro")!!

    // tvar nabídky Gemini API: id s prefixem models/, mezi chatem i obrázky, řeč, embeddingy
    private val offer = listOf("models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-3.1-pro-preview", "models/gemini-4-argon",
        "models/gemini-2.5-flash-image", "models/gemini-embedding-001", "models/gemini-2.5-flash-preview-tts", "models/gemma-3-27b-it", "models/gemini-live-2.5-flash")

    @Test fun providersAreWiredSafely() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.base)
        assertTrue(!gemini.paid && pro.paid)
        assertTrue(gemini.usageInStream && pro.usageInStream)
        assertTrue(gemini.dataNote.contains("EHP") && pro.dataNote.contains("Cloud Billing"))
        // klíč jde v hlavičce Authorization (KimiEngine), ne v URL → neskončí v logu adresy
        assertTrue(!gemini.base.contains("key="))
    }

    @Test fun picksHydrogenWhenOfferedElseBestStableAndNeverMedia() {
        assertEquals("gemini-4-hydrogen", FreeCloud.pickModel(gemini, offer + "models/gemini-4-hydrogen"))
        // bez Hydrogenu: nejsilnější „běžný“ model, ne neznámý kódový název (Argon může být bez bezplatné kvóty), ne obrázky/TTS/embedding/gemma
        val best = FreeCloud.pickModel(gemini, offer)
        assertEquals("gemini-3.1-pro-preview", best)
        assertTrue(!best.startsWith("models/"))
        // model mimo tarif → jiný model téhož poskytovatele
        assertEquals("gemini-2.5-flash", FreeCloud.pickModel(gemini, offer, avoid = setOf("gemini-3.1-pro-preview")))
        assertEquals("gemini-4-hydrogen", FreeCloud.pickModel(gemini, emptyList()))
        assertEquals("high", FreeCloud.effortFor("gemini-4-hydrogen")!!("max"))
    }

    private fun serve(handler: (String) -> Pair<Int, String>, seen: MutableList<String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1beta/openai/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString(); seen += body
            val (code, payload) = handler(body)
            val b = payload.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.createContext("/v1beta/openai/models") { ex ->
            val b = """{"data":[{"id":"models/gemini-2.5-flash"},{"id":"models/gemini-4-hydrogen"},{"id":"models/gemini-embedding-001"}]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); servers += s
        return "http://127.0.0.1:${s.address.port}/v1beta/openai"
    }

    private val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"Ahoj\"}}]}\n\n" +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200,\"total_tokens\":3200}}\n\ndata: [DONE]\n\n"

    @Test fun usageIncludesThinkingTokensAndProCreditIsGuarded() {
        val seen = mutableListOf<String>()
        val base = serve({ 200 to stream }, seen)
        var spent = 0.0; var allowed = true
        val guard = CostGuard(object : CostGuard.Store {
            override fun allowed(provider: String) = allowed
            override fun budgetUsd(provider: String) = 10.0
            override fun spentUsd(provider: String) = spent
            override fun addSpent(provider: String, usd: Double) { spent += usd }
        })
        val m = FreeCloud.Member(pro.copy(base = base), "AIza-test-key-123456", "gemini-4-hydrogen")
        val r = FreeCloudEngine(listOf(m), guard = guard).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(maxTokens = 2000), CancelToken()) {}
        assertEquals("Ahoj", r.text)
        assertTrue(seen.single().contains("\"stream_options\":{\"include_usage\":true}"))
        assertEquals(1000L, r.inputTokens); assertEquals(2200L, r.outputTokens) // 2000 myšlenkových + 200 textu
        // 1000 × 1,5 + 2200 × 7,5 za milion = 0,018 USD
        assertEquals(0.018, spent, 1e-6)
        // bez povolení se placený člen přeskočí – nikdy tichý přechod na placené
        allowed = false
        val free = FreeCloud.Member(gemini.copy(base = base), "AIza-free-key-1234567", "gemini-2.5-flash")
        val before = seen.size
        FreeCloudEngine(listOf(m, free), guard = guard).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertTrue(seen.drop(before).single().contains("gemini-2.5-flash"))
    }

    @Test fun zeroQuotaModelHealsToAnotherGeminiModel() {
        val seen = mutableListOf<String>()
        val base = serve({ b -> if (b.contains("gemini-4-hydrogen")) 429 to """[{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-4-hydrogen","status":"RESOURCE_EXHAUSTED"}}]""" else 200 to stream }, seen)
        val healed = mutableListOf<Pair<String, String>>()
        val m = FreeCloud.Member(gemini.copy(base = base), "AIza-free-key-1234567", "gemini-4-hydrogen")
        val r = FreeCloudEngine(listOf(m), onModel = { id, model -> healed += id to model }).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals("Ahoj", r.text)
        assertEquals("gemini" to "gemini-2.5-flash", healed.single())
    }

    @Test fun serverWithoutStreamOptionsIsRetriedWithout() {
        val seen = mutableListOf<String>()
        val base = serve({ b -> if (b.contains("stream_options")) 400 to """{"error":{"message":"Invalid JSON payload received. Unknown name \"stream_options\""}}""" else 200 to stream }, seen)
        val m = FreeCloud.Member(gemini.copy(base = base), "AIza-free-key-1234567", "gemini-2.5-flash")
        val r = FreeCloudEngine(listOf(m)).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals("Ahoj", r.text); assertEquals(2, seen.size); assertTrue(!seen[1].contains("stream_options"))
    }

    @Test fun proPricing() {
        assertEquals(CostGuard.Price(1.5, 7.5), CostGuard.defaultPrice("geminipro", "gemini-4-hydrogen"))
        assertEquals(CostGuard.Price(4.0, 18.0), CostGuard.defaultPrice("geminipro", "gemini-3.1-pro-preview"))
    }
}
