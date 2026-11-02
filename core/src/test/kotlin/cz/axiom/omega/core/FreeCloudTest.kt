package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.ChatMessage
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

class FreeCloudTest {
    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    /** Server, který buď vrátí [status] s chybou, nebo odstreamuje [chunks]. */
    private fun serve(status: Int, vararg chunks: String, seen: MutableList<String>? = null): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString(); seen?.add(body)
            val payload = if (status != 200) """{"error":{"message":"rate limit"}}""".toByteArray()
            else (chunks.joinToString("") { "data: {\"choices\":[{\"delta\":$it}]}\n\n" } + "data: [DONE]\n\n").toByteArray()
            ex.sendResponseHeaders(status, payload.size.toLong()); ex.responseBody.use { it.write(payload) }
        }
        s.start(); servers += s
        return "http://127.0.0.1:${s.address.port}/v1"
    }

    private fun member(name: String, base: String, model: String = "gpt-oss-120b") =
        FreeCloud.Member(FreeCloud.Provider(name.lowercase(), name, base, listOf(model), signup = ""), "key-12345678", model)

    @Test fun picksBestAvailableModel() {
        val groq = FreeCloud.provider("groq")!!
        assertEquals("openai/gpt-oss-120b", FreeCloud.pickModel(groq, listOf("llama-3.1-8b-instant", "openai/gpt-oss-120b", "whisper-large-v3")))
        assertEquals("llama-3.3-70b-versatile", FreeCloud.pickModel(groq, listOf("llama-3.3-70b-versatile", "whisper-large-v3")))
        assertEquals("openai/gpt-oss-20b", FreeCloud.pickModel(groq, listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b", "openai/gpt-oss-120b"), fast = true))
        assertEquals("openai/gpt-oss-120b", FreeCloud.pickModel(groq, emptyList()))   // seznam neznáme → první preferovaný
        val or = FreeCloud.provider("openrouter")!!
        assertEquals("some/new-model:free", FreeCloud.pickModel(or, listOf("paid/model", "some/new-model:free")))
        // neznámý model, ale žádný embedding/whisper
        assertEquals("brand-new-70b", FreeCloud.pickModel(FreeCloud.provider("cerebras")!!, listOf("text-embed-1", "brand-new-70b")))
    }

    @Test fun gptOssGetsReasoningEffortAndReasoningFieldIsRead() {
        val seen = mutableListOf<String>()
        val base = serve(200, """{"reasoning":"zvažuji"}""", """{"content":"Praha"}""", seen = seen)
        val r = FreeCloudEngine(listOf(member("Cerebras", base))).generate(listOf(ChatMessage(Role.USER, "Hlavní město?")), GenOptions(effort = "low"), CancelToken()) {}
        assertEquals("Praha", r.text); assertEquals("zvažuji", r.reasoning)
        assertTrue(seen.single().contains("\"reasoning_effort\":\"low\""))
    }

    @Test fun fallsBackOnRateLimitBeforeWriting() {
        val notes = mutableListOf<String>()
        val eng = FreeCloudEngine(listOf(member("Groq", serve(429)), member("Cerebras", serve(200, """{"content":"OK"}"""))), onFallback = { notes += it })
        val r = eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals("OK", r.text)
        assertTrue(eng.label.startsWith("Cerebras"))
        assertTrue(notes.single().startsWith("Groq"))
    }

    @Test fun allProvidersFailingGivesReadableError() {
        val eng = FreeCloudEngine(listOf(member("Groq", serve(429)), member("Cerebras", serve(503))))
        val e = assertFailsWith<EngineException> { eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
        assertTrue(e.message!!.startsWith("Cerebras"))
    }

    @Test fun routerPrefersFreeCloudOnlineOnly() {
        val ctx = cz.axiom.omega.core.agent.Router.Context(online = true, engines = listOf("local", "free"), cloudFirst = "free")
        assertEquals("free", cz.axiom.omega.core.agent.Router.route("Jak funguje tepelné čerpadlo?", ctx).engine)
        assertEquals("local", cz.axiom.omega.core.agent.Router.route("Jak funguje tepelné čerpadlo?", ctx.copy(online = false)).engine)
        assertEquals("local", cz.axiom.omega.core.agent.Router.route("Jak funguje tepelné čerpadlo?", ctx.copy(cloudFirst = null)).engine)
    }

    @Test fun qualityPutsPaidFirst() {
        assertEquals("cerebras", FreeCloud.order(quality = false).first().id)
        assertTrue(FreeCloud.order(quality = true).first().paid)
        assertEquals("deepseek-chat", FreeCloud.pickModel(FreeCloud.provider("deepseek")!!, listOf("deepseek-reasoner", "deepseek-chat")))
    }

    @Test fun capsMaxTokensPerProvider() {
        val seen = mutableListOf<String>()
        val base = serve(200, """{"content":"ok"}""", seen = seen)
        val p = FreeCloud.provider("deepseek")!!.copy(base = base)
        FreeCloudEngine(listOf(FreeCloud.Member(p, "key-12345678", "deepseek-chat")))
            .generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(maxTokens = 32_000), CancelToken()) {}
        assertTrue(seen.single().contains("\"max_tokens\":8192"), seen.single())
    }

    @Test fun healsUnknownModelFromModelList() {
        val healed = mutableListOf<String>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val ok = body.contains("\"model\":\"good-model\"")
            val payload = if (ok) "data: {\"choices\":[{\"delta\":{\"content\":\"Opraveno\"}}]}\n\ndata: [DONE]\n\n".toByteArray() else """{"error":{"message":"model not found"}}""".toByteArray()
            ex.sendResponseHeaders(if (ok) 200 else 404, payload.size.toLong()); ex.responseBody.use { it.write(payload) }
        }
        s.start(); servers += s
        val base = "http://127.0.0.1:${s.address.port}/v1"
        val m = FreeCloud.Member(FreeCloud.Provider("x", "X", base, listOf("gone-model"), signup = ""), "key-12345678", "gone-model")
        val eng = FreeCloudEngine(listOf(m), listModels = { _, _ -> listOf("good-model") }, onModel = { id, model -> healed += "$id=$model" })
        assertEquals("Opraveno", eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}.text)
        assertEquals(listOf("x=good-model"), healed)
    }

    @Test fun trimsOldHistoryToProviderWindow() {
        val seen = mutableListOf<String>()
        val base = serve(200, """{"content":"ok"}""", seen = seen)
        val p = FreeCloud.Provider("x", "X", base, listOf("m"), signup = "", contextChars = 5_000)
        val msgs = listOf(ChatMessage(Role.SYSTEM, "S")) + (1..10).map { ChatMessage(if (it % 2 == 0) Role.ASSISTANT else Role.USER, "h$it " + "x".repeat(1_000)) } + ChatMessage(Role.USER, "POSLEDNI")
        FreeCloudEngine(listOf(FreeCloud.Member(p, "key-12345678", "m"))).generate(msgs, GenOptions(), CancelToken()) {}
        assertTrue(seen.single().length < 7_000); assertTrue(seen.single().contains("POSLEDNI")); assertTrue(!seen.single().contains("h1 "))
    }

    @Test fun needsAtLeastOneKey() { assertFailsWith<IllegalArgumentException> { FreeCloudEngine(emptyList()) } }
}
