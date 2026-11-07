package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.SelfState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REBIRTH test A – incident Chutes / Kimi-K3-TEE: model poslal požadavek web_search ve svém formátu, aplikace ukázala
 * surový zápis a úlohu označila „done“. Teď: rozpoznat → provést → vrátit výsledek → pokračovat → korektní konec.
 */
class AgentToolCallIncidentTest {
    private class Script(val replies: MutableList<GenResult>) : LlmEngine {
        override val id = "s"; override val label = "Kimi-K3-TEE (skript)"; override val kind = EngineKind.KIMI_CLOUD
        override val contextBudgetChars = 200_000
        val seen = mutableListOf<List<ChatMessage>>(); val opts = mutableListOf<GenOptions>()
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            seen += messages; opts += options; return replies.removeAt(0)
        }
    }
    private fun txt(t: String) = GenResult(t, model = "kimi-k3")
    private val mem = object : MemoryStore { override fun all() = emptyList<MemoryItem>(); override fun remember(item: MemoryItem) = 0L; override fun forget(query: String) = 0 }
    private val queries = mutableListOf<String>()
    private val search = object : AgentTool {
        override val name = "web_search"; override val description = "web"; override val args = """{"q": "dotaz"}"""
        override fun run(args: JsonObject, cancel: CancelToken): String {
            val q = ((args["q"] ?: args["query"]) as? JsonPrimitive)?.contentOrNull ?: error("chybí q")
            queries += q; return "- Operation Prato — https://example.org/prato\n  úryvek o operaci"
        }
    }
    private fun agent() = ActiveAgent(listOf(search), mem) { SelfState("Kimi", "test", true) }

    private val incident = "Hledám informace.\n<|tool_calls_section_begin|><|tool_call_begin|>functions.web_search:0<|tool_call_argument_begin|>{\"query\": \"Operation Prato\"}<|tool_call_end|><|tool_calls_section_end|>" +
        " Nejdřív si dohledám zdroje, abych mohl odpovědět podrobně a přesně, protože o tomto tématu nemám spolehlivé údaje ve své paměti a nechci si nic vymýšlet ani spekulovat bez podkladů z webu, které teprve získám nástrojem.".repeat(2)

    @Test fun kimiSpecialTokensAreExecutedNotShown() {
        val eng = Script(mutableListOf(txt(incident), txt("HOTOVO:\nOperation Prato je podle [example.org] operace … (zdroj: https://example.org/prato)")))
        val r = agent().run("Co byla Operation Prato?", emptyList(), eng, 5, CancelToken())
        assertEquals(listOf("Operation Prato"), queries)                          // nástroj SKUTEČNĚ proběhl
        assertEquals(AgentState.COMPLETED, r.state); assertEquals("done", r.finish)
        assertTrue("tool_call" !in r.answer && "<|" !in r.answer, r.answer)       // žádný surový zápis
        assertTrue(eng.seen[1].any { it.content.contains("VÝSLEDEK NÁSTROJE web_search") })  // výsledek šel modelu
    }

    @Test fun nativeStreamedToolCallWithEmptyText() {
        val eng = Script(mutableListOf(GenResult("", model = "k", toolCalls = listOf(ToolCall("c1", "web_search", "{\"q\":\"Blue Beam\"}"))),
            txt("HOTOVO: Shrnutí podle zdrojů.")))
        val r = agent().run("Blue Beam", emptyList(), eng, 5, CancelToken())
        assertEquals(listOf("Blue Beam"), queries); assertEquals(AgentState.COMPLETED, r.state)
        assertTrue(eng.opts[0].tools.any { it.name == "web_search" && "\"q\"" in it.parametersJson })  // cloud dostal nativní schéma
    }

    @Test fun hermesAndBareJsonAndAliases() {
        val eng = Script(mutableListOf(txt("<tool_call>{\"name\": \"search\", \"arguments\": {\"query\": \"Georgia Guidestones\"}}</tool_call>"),
            txt("{\"name\": \"google_search\", \"arguments\": \"{\\\"q\\\": \\\"zlatá miliarda\\\"}\"}"), txt("HOTOVO: hotovo.")))
        val r = agent().run("x", emptyList(), eng, 5, CancelToken())
        assertEquals(listOf("Georgia Guidestones", "zlatá miliarda"), queries); assertEquals(AgentState.COMPLETED, r.state)
    }

    @Test fun brokenToolCallIsNeverDone() {
        val broken = "<|tool_call_begin|>functions.web_search:0<|tool_call_argument_begin|>{\"query\": \"Prato\"<|tool_call_end|>"
        val eng = Script(mutableListOf(txt(broken), txt(broken), txt(broken)))
        val r = agent().run("Operation Prato", emptyList(), eng, 6, CancelToken())
        assertTrue(r.state != AgentState.COMPLETED && r.finish != "done", "stav ${r.state}")
        assertTrue("<|" !in r.answer && "tool_call" !in r.answer, r.answer)
        assertTrue(eng.seen[1].last().content.contains("nešlo přečíst"))     // model dostal výzvu k opravě
    }

    @Test fun unknownToolAndToolFailureContinue() {
        val eng = Script(mutableListOf(txt("AKCE: {\"tool\": \"teleport\", \"args\": {}}"), txt("AKCE: {\"tool\": \"web_search\", \"args\": {}}"), txt("HOTOVO: bez výsledku, ale poctivě.")))
        val r = agent().run("x", emptyList(), eng, 5, CancelToken())
        assertEquals(AgentState.COMPLETED, r.state)
        assertTrue(eng.seen[1].last().content.contains("neexistuje")); assertTrue(eng.seen[2].last().content.contains("CHYBA nástroje"))
    }

    @Test fun finalAnswerWithLeftoverMarkupIsPartialAndClean() {
        val eng = Script(mutableListOf(txt("x"), txt("x"), txt("HOTOVO: část odpovědi <|tool_call_begin|>functions.web_search:0<|tool_call_argument_begin|>{\"q\": \"a\"")))
        val r = agent().run("y", emptyList(), eng, 1, CancelToken())
        assertTrue(r.state in setOf(AgentState.PARTIAL_RESULT, AgentState.BUDGET_EXCEEDED), "${r.state}")
        assertTrue("<|" !in r.answer, r.answer)
    }

    @Test fun stateMachineForbidsShortcutToCompleted() {
        assertTrue(!AgentState.canGo(AgentState.RUNNING, AgentState.COMPLETED))
        assertTrue(!AgentState.canGo(AgentState.TOOL_PENDING, AgentState.COMPLETED))
        assertTrue(AgentState.canGo(AgentState.FINALIZING, AgentState.COMPLETED))
    }

    // ---------------------------------------------------------------- skutečný SSE stream (lokální server)
    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    @Test fun kimiEngineAssemblesStreamedToolCallChunks() {
        var body = ""
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }
        s.createContext("/v1/chat/completions") { ex ->
            body = ex.requestBody.readBytes().decodeToString()
            val chunks = listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\": \"Oper"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ation Prato\"}"}}]},"finish_reason":"tool_calls"}]}""",
            )
            val out = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            ex.responseHeaders.add("Content-Type", "text/event-stream"); ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(out.toByteArray()) }
        }
        s.start()
        val eng = KimiEngine("http://127.0.0.1:${s.address.port}/v1", "k", "kimi-k3", EngineKind.KIMI_CLOUD)
        val r = eng.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(tools = listOf(ActiveAgent.toolSpec(search))), CancelToken()) {}
        assertEquals(listOf(ToolCall("call_1", "web_search", "{\"q\": \"Operation Prato\"}")), r.toolCalls)
        assertTrue("\"tools\"" in body && "\"web_search\"" in body, body)
    }
}
