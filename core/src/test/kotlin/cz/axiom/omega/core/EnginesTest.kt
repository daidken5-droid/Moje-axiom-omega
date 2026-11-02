package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.agent.AskRequest
import cz.axiom.omega.core.agent.KnowledgeSource
import cz.axiom.omega.core.agent.MemoryStore
import cz.axiom.omega.core.agent.Orchestrator
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.brain.BrainCatalog
import cz.axiom.omega.core.brain.BrainSpec
import cz.axiom.omega.core.brain.Downloader
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.llm.Delta
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.GenResult
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.llm.LlmEngine
import cz.axiom.omega.core.llm.Role
import cz.axiom.omega.core.llm.ThinkSplitter
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.self.SelfState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class EnginesTest {

    private var server: HttpServer? = null

    @After fun stop() { server?.stop(0) }

    private fun serve(configure: (HttpServer) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(s)
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    // ---------------------------------------------------------------- Kimi K3

    @Test fun kimiStreamsTextAndReasoning() {
        var body = ""
        val base = serve { s ->
            s.createContext("/v1/chat/completions") { ex ->
                body = ex.requestBody.readBytes().decodeToString()
                assertEquals("Bearer sk-test", ex.requestHeaders.getFirst("Authorization"))
                val events = listOf(
                    """{"model":"kimi-k3","choices":[{"delta":{"reasoning_content":"Zvažuji…"}}]}""",
                    """{"choices":[{"delta":{"content":"Ahoj, "}}]}""",
                    """{"choices":[{"delta":{"content":"jsem Ω."},"finish_reason":"stop","usage":{"prompt_tokens":12,"completion_tokens":5}}]}""",
                    "[DONE]",
                )
                val payload = events.joinToString("") { "data: $it\n\n" }.toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
        }
        val eng = KimiEngine("$base/v1", "sk-test", "kimi-k3", EngineKind.KIMI_SERVER, effortMapper = KimiEngine::mapEffort)
        val deltas = mutableListOf<Delta>()
        val r = eng.generate(
            listOf(ChatMessage(Role.SYSTEM, "sys A"), ChatMessage(Role.SYSTEM, "sys B"), ChatMessage(Role.USER, "Kdo jsi?")),
            GenOptions(effort = "max"), CancelToken(),
        ) { deltas += it }

        assertEquals("Ahoj, jsem Ω.", r.text)
        assertEquals("Zvažuji…", r.reasoning)
        assertEquals(12L, r.inputTokens)
        assertTrue(deltas.first().reasoning)
        val req = Json.parseToJsonElement(body).jsonObject
        assertEquals("kimi-k3", req["model"]!!.jsonPrimitive.content)
        assertEquals("max", req["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(body.contains("sys A\\n\\nsys B"))   // systémové zprávy sloučené do jedné
    }

    @Test fun kimiCloudRequestHasNoTemperature() {
        val eng = KimiEngine(KimiEngine.CLOUD_BASE, "k", KimiEngine.CLOUD_MODEL, EngineKind.KIMI_CLOUD)
        val req = eng.buildRequest(listOf(ChatMessage(Role.USER, "x")), GenOptions(effort = "medium"))
        assertNull(req["temperature"])
        assertEquals("high", req["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun kimiInlineThinkTagsAreSplit() {
        val base = serve { s ->
            s.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val chunks = listOf("<thi", "nk>úvaha</th", "ink>Výsledek")
                val payload = (chunks.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), it)}}}]}\n\n" } + "data: [DONE]\n\n").toByteArray()
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
        }
        val r = KimiEngine("$base/v1", null, "k3-local", EngineKind.KIMI_SERVER).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
        assertEquals("Výsledek", r.text)
        assertEquals("úvaha", r.reasoning)
    }

    @Test fun kimiUnauthorizedIsReadable() {
        val base = serve { s ->
            s.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val b = """{"error":{"message":"Invalid Authentication","type":"invalid_authentication_error"}}""".toByteArray()
                ex.sendResponseHeaders(401, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
        }
        try {
            KimiEngine("$base/v1", "bad", "kimi-k3", EngineKind.KIMI_SERVER).generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {}
            fail()
        } catch (e: EngineException) {
            assertTrue(e.message!!, e.message!!.contains("Invalid Authentication"))
            assertFalse(e.retryable)
        }
    }

    @Test fun plainHttpOnlyForLan() {
        assertTrue(Http.isPrivateHost("192.168.1.50"))
        assertTrue(Http.isPrivateHost("k3-server.local"))
        assertTrue(Http.isPrivateHost("100.101.1.2"))
        assertFalse(Http.isPrivateHost("api.moonshot.ai"))
        assertFalse(Http.isPrivateHost("8.8.8.8"))
        try { Http.requireAllowed("http://api.example.com/v1"); fail() } catch (_: EngineException) {}
        Http.requireAllowed("https://api.moonshot.ai/v1")
    }

    @Test fun thinkSplitterAcrossChunks() {
        val out = mutableListOf<Delta>()
        val s = ThinkSplitter { out += it }
        listOf("A<", "think>B</", "think", ">C").forEach(s::feed)
        s.finish()
        assertEquals("AC", s.answer.toString())
        assertEquals("B", s.reasoning.toString())
    }

    // ---------------------------------------------------------------- Claude

    @Test fun claudeParamsUseAdaptiveThinkingEffortAndFallbacks() {
        val p = ClaudeEngine("sk-ant-test").buildParams(
            listOf(ChatMessage(Role.SYSTEM, "S"), ChatMessage(Role.USER, "Ahoj")),
            GenOptions(effort = "max", webSearch = true, maxTokens = 32_000),
        )
        assertEquals("claude-opus-5-5", p.model().asString())
        assertEquals(32_000L, p.maxTokens())
        assertTrue(p._additionalBodyProperties().containsKey("fallbacks"))
        assertTrue(p.betas().get().any { it.asString() == ClaudeEngine.FALLBACK_BETA })
        assertEquals(1, p.tools().get().size)
        assertEquals("S", p.system().get().string().get())
    }

    /** Turbo: Haiku 5.5 s pevnou nízkou hloubkou a bez serverových fallbacků (Haiku je nepodporuje). */
    @Test fun claudeTurboUsesHaikuLowEffortWithoutFallbacks() {
        val p = ClaudeEngine("sk-ant-test", ClaudeEngine.TURBO_MODEL, effortOverride = "low").buildParams(
            listOf(ChatMessage(Role.USER, "Ahoj")), GenOptions(effort = "high"),
        )
        assertEquals("claude-haiku-5-5", p.model().asString())
        assertEquals("low", p.outputConfig().get().effort().get().asString())
        assertTrue(!p._additionalBodyProperties().containsKey("fallbacks"))
    }

    // ---------------------------------------------------------------- Downloader

    private fun spec(data: ByteArray, urls: List<String>) = BrainSpec(
        id = "test-brain", name = "Test", family = "t", description = "", urls = urls,
        sizeBytes = data.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        minRamGb = 1, contextTokens = 1024, license = "", thinking = false,
    )

    @Test fun downloadResumesAfterDropAndFollowsRedirect() {
        val data = Random(7).nextBytes(3_000_000)
        val requests = AtomicInteger()
        val ranges = mutableListOf<String?>()
        val base = serve { s ->
            s.createContext("/redirect") { ex ->
                ex.responseHeaders.add("Location", "/file")
                ex.sendResponseHeaders(302, -1); ex.close()
            }
            s.createContext("/file") { ex ->
                val n = requests.incrementAndGet()
                val range = ex.requestHeaders.getFirst("Range")
                synchronized(ranges) { ranges += range }
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                val slice = data.copyOfRange(from, data.size)
                if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(if (range != null) 206 else 200, slice.size.toLong())
                ex.responseBody.use { out ->
                    if (n == 1) { out.write(slice, 0, 1_000_000); out.flush(); throw java.io.IOException("simulovaný výpadek") }
                    out.write(slice)
                }
            }
        }
        val dir = createTempDir()
        val target = File(dir, "brain.gguf")
        val phases = mutableListOf<String>()
        val f = Downloader(sleeper = {}).download(spec(data, listOf("$base/redirect")), target, CancelToken()) { phases += it.phase }
        assertTrue(f.exists())
        assertTrue(data.contentEquals(f.readBytes()))
        assertEquals(null, ranges.first())
        assertTrue(ranges.toString(), ranges.drop(1).all { it != null && it != "bytes=0-" })
        assertFalse(File(dir, "brain.gguf.part").exists())
        dir.deleteRecursively()
    }

    @Test fun downloadRejectsWrongChecksum() {
        val data = Random(1).nextBytes(200_000)
        val base = serve { s ->
            s.createContext("/f") { ex ->
                val bad = data.copyOf().also { it[5] = (it[5] + 1).toByte() }
                ex.sendResponseHeaders(200, bad.size.toLong()); ex.responseBody.use { it.write(bad) }
            }
        }
        val dir = createTempDir()
        try {
            Downloader(maxAttempts = 2, sleeper = {}).download(spec(data, listOf("$base/f")), File(dir, "x.gguf"), CancelToken()) {}
            fail()
        } catch (e: Downloader.DownloadException) {
            assertTrue(e.message!!, e.message!!.contains("SHA-256"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun repoCatalogIsValid() {
        val file = listOf(File("../brains/catalog.json"), File("brains/catalog.json")).first { it.exists() }
        val brains = BrainCatalog.parse(file.readText())
        assertTrue(brains.size >= 3)
        assertTrue("repo katalog musí mít SHA-256 u všech mozků", brains.all { it.sha256.length == 64 })
        assertEquals(brains, BrainCatalog.parse(BrainCatalog.toJson(brains)).map { it.copy(recommended = brains.first { b -> b.id == it.id }.recommended) })
        assertTrue(brains.any { it.sha256 == "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a" }) // AXIOM 2.1 mozek
        // 8GB telefon hlásí ~7,5 GB → musí dostat Gemma 4 E4B, ne slabší mozek
        assertEquals(8, BrainCatalog.nominalRamGb(7.5))
        assertEquals(12, BrainCatalog.nominalRamGb(11.3))
        assertEquals(6, BrainCatalog.nominalRamGb(5.6))
        assertEquals("omega-core-gemma4-e4b-q4km", BrainCatalog.recommend(brains, 7.5, 50_000_000_000)!!.id)
        assertEquals("omega-ultra-gemma4-26b-a4b-iq2m", BrainCatalog.recommend(brains, 11.4, 50_000_000_000)!!.id)
        val pick = BrainCatalog.recommend(brains, totalRamGb = 5.5, freeBytes = 10_000_000_000)!!
        assertTrue(pick.minRamGb <= 6)
        assertEquals(brains.filter { it.kind == "chat" }.minBy { it.sizeBytes }, BrainCatalog.recommend(brains, 2.0, 100))
        assertTrue(brains.any { it.kind == "embedding" })
    }

    // ---------------------------------------------------------------- Orchestrator

    private class FakeEngine(val replies: MutableList<String>, val failFirst: Boolean = false, override val kind: EngineKind = EngineKind.LOCAL) : LlmEngine {
        override val id = "fake"; override val label = "Fake"
        override val contextBudgetChars = 20_000
        val seen = mutableListOf<List<ChatMessage>>()
        var calls = 0
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: cz.axiom.omega.core.llm.CancelToken, onDelta: (Delta) -> Unit): GenResult {
            calls++
            if (failFirst && calls == 1) throw EngineException("dočasná chyba", retryable = true)
            seen += messages
            val t = replies.removeAt(0)
            onDelta(Delta(t))
            return GenResult(t, model = "fake-1")
        }
    }

    private class Mem : MemoryStore {
        val items = mutableListOf<MemoryItem>()
        override fun all() = items.toList()
        override fun remember(item: MemoryItem): Long { items += item.copy(id = items.size + 1L); return items.size.toLong() }
        override fun forget(query: String): Int { val n = items.count { query in it.text }; items.removeAll { query in it.text }; return n }
    }

    private val noKb = object : KnowledgeSource {
        override fun search(query: String, limit: Int) = listOf(Snippet(9, "Ceník", "Mikrocement 20 m2 stojí…", "test"))
    }

    @Test fun orchestratorRunsRandyPassesAndLearns() {
        val eng = FakeEngine(mutableListOf("Návrh A", "Kritika B", "Finále C\n⟨SEBEREFLEXE⟩\njistota: 77 %\npoučení: Kontroluj prořez materiálu."), kind = EngineKind.KIMI_SERVER)
        val mem = Mem()
        val o = Orchestrator(noKb, mem, { SelfState("Fake", "test", false) }, sleeper = {})
        val r = o.run(AskRequest("L4: Najdi rizika: 20 m2 mikrocement, 12*3, pamatuj si, že preferuji Topciment"), eng, CancelToken())

        assertEquals(RandyLevel.L4_NEMESIS_REDTEAM, r.level)
        assertEquals(3, eng.calls)
        assertEquals("Finále C", r.answer)
        assertEquals(77, r.confidence)
        assertTrue(mem.items.any { it.kind == MemoryKind.LESSON && it.text.contains("prořez") })
        assertTrue(mem.items.any { it.kind == MemoryKind.FACT })
        val firstUser = eng.seen[0].first { it.role == Role.USER }.content
        assertTrue(firstUser, firstUser.contains("STEP-0 FAKTA"))
        assertTrue(firstUser, firstUser.contains("12*3 = 36"))
        assertTrue(firstUser, firstUser.contains("[1] Ceník"))
        // 3. průchod vidí návrh i kritiku
        val third = eng.seen[2]
        assertTrue(third.any { it.role == Role.ASSISTANT && it.content == "Návrh A" })
        assertTrue(third.any { it.role == Role.ASSISTANT && it.content == "Kritika B" })
        assertTrue(third.last().content.contains("SEBEREFLEXE"))
        assertTrue(eng.seen[0].first().content.contains("SEBEMODEL"))
    }

    /** Telefon: systémový prompt je bajtově stejný mezi dotazy (KV cache), proměnlivé věci jdou do dotazu. */
    @Test fun phonePromptKeepsStablePrefix() {
        val eng = FakeEngine(mutableListOf("Odpověď 1", "Odpověď 2"))
        val mem = Mem()
        val o = Orchestrator(noKb, mem, { SelfState("Fake", "test", false, batteryPct = (10..90).random()) }, sleeper = {})
        o.run(AskRequest("Kolik stojí mikrocement?", maxPasses = 1), eng, CancelToken())
        mem.remember(MemoryItem(kind = MemoryKind.FACT, text = "Uživatel bydlí v Brně a má rád mikrocement"))
        val hist = listOf(ChatMessage(Role.USER, "Kolik stojí mikrocement?"), ChatMessage(Role.ASSISTANT, "Odpověď 1"))
        o.run(AskRequest("L3: Vyplatí se mikrocement v Brně?", history = hist, maxPasses = 1), eng, CancelToken())
        assertEquals(eng.seen[0].first().content, eng.seen[1].first().content)
        assertTrue(eng.seen[1][1].content == "Kolik stojí mikrocement?" && eng.seen[1][2].content == "Odpověď 1")
        val last = eng.seen[1].last().content
        assertTrue(last, last.contains("RANDY L3") && last.contains("Brně") && !last.contains("SEBEREFLEXE") && !last.contains("ÚKOL PRŮCHODU"))
    }

    @Test fun orchestratorRetriesTransientError() {
        val eng = FakeEngine(mutableListOf("OK"), failFirst = true)
        val r = Orchestrator(noKb, Mem(), { SelfState("Fake", "test", false) }, sleeper = {})
            .run(AskRequest("Hlavní město Francie?", reflect = false), eng, CancelToken())
        assertEquals("OK", r.answer)
        assertEquals(2, eng.calls)
    }

    @Test fun councilSynthesizes() {
        val a = FakeEngine(mutableListOf("Paříž"))
        val b = FakeEngine(mutableListOf("Paris"))
        val judge = FakeEngine(mutableListOf("Shoda: Paříž.\n⟨SEBEREFLEXE⟩\njistota: 99 %\npoučení: žádné"))
        val r = Orchestrator(noKb, Mem(), { SelfState("Fake", "test", false) }, sleeper = {})
            .council(AskRequest("Hlavní město Francie?"), listOf(a, b), judge, CancelToken())
        assertEquals("Shoda: Paříž.", r.answer)
        assertEquals(99, r.confidence)
        assertEquals(3, r.trace.size)
        assertTrue(judge.seen[0].last().content.contains("=== ODPOVĚĎ"))
    }

    @Test fun historyTrimKeepsNewestAndStartsWithUser() {
        val h = listOf(
            ChatMessage(Role.USER, "a".repeat(100)), ChatMessage(Role.ASSISTANT, "b".repeat(100)),
            ChatMessage(Role.USER, "c".repeat(100)), ChatMessage(Role.ASSISTANT, "d".repeat(100)),
        )
        val t = Orchestrator.trimHistory(h, 250)
        assertEquals(2, t.size)
        assertEquals(Role.USER, t.first().role)
        assertTrue(Orchestrator.trimHistory(h, 0).isEmpty())
    }
}
