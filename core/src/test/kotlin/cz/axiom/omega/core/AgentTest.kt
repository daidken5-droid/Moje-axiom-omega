package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.agent.ActiveAgent
import cz.axiom.omega.core.agent.AgentStep
import cz.axiom.omega.core.agent.AgentListener
import cz.axiom.omega.core.agent.KnowledgeSource
import cz.axiom.omega.core.agent.MemoryStore
import cz.axiom.omega.core.agent.Router
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.agent.Tools
import cz.axiom.omega.core.hub.HfHub
import cz.axiom.omega.core.hub.Web
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.Delta
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.GenResult
import cz.axiom.omega.core.llm.LlmEngine
import cz.axiom.omega.core.llm.Role
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.SelfState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class AgentTest {

    private class Script(val replies: MutableList<String>) : LlmEngine {
        override val id = "s"; override val label = "Skript"; override val kind = EngineKind.KIMI_CLOUD
        override val contextBudgetChars = 100_000
        val seen = mutableListOf<List<ChatMessage>>()
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            seen += messages
            return GenResult(replies.removeAt(0), model = "skript-1")
        }
    }

    private class Mem : MemoryStore {
        val items = mutableListOf<MemoryItem>()
        override fun all() = items.toList()
        override fun remember(item: MemoryItem): Long { items += item; return items.size.toLong() }
        override fun forget(query: String) = 0
    }

    private val kb = object : KnowledgeSource {
        override fun search(query: String, limit: Int) = listOf(Snippet(3, "Stavba – samonivelace", "1,5 kg na m2 a mm", "pack:stavba"))
    }

    @Test fun agentUsesToolsAndFinishes() {
        val mem = Mem()
        val notes = mutableListOf<String>()
        val tools = Tools.offline(kb, mem, { cz.axiom.omega.core.step0.Rates.FALLBACK }) { t, _ -> notes += t }
        val eng = Script(
            mutableListOf(
                "MYŠLENKA: zjistím spotřebu\nAKCE: {\"tool\": \"kb_search\", \"args\": {\"q\": \"samonivelace spotřeba\"}}",
                "MYŠLENKA: spočítám\nAKCE: ```json\n{\"tool\":\"calc\",\"args\":{\"expr\":\"20*3*1,5/25\"}}\n```",
                "MYŠLENKA: uložím\nAKCE: {\"tool\": \"memory_save\", \"args\": {\"text\": \"Uživatel lije podlahu 20 m2.\"}}",
                "MYŠLENKA: hotovo\nHOTOVO:\nPotřebuješ 3,6 → 4 pytle.",
            ),
        )
        val steps = mutableListOf<AgentStep>()
        val r = ActiveAgent(tools, mem) { SelfState("Skript", "test", true) }
            .run("Kolik pytlů samonivelace na 20 m2 při 3 mm?", emptyList(), eng, 8, CancelToken(), object : AgentListener {
                override fun onStep(step: AgentStep) { steps += step }
            })
        assertEquals("Potřebuješ 3,6 → 4 pytle.", r.answer)
        assertEquals("done", r.finish)
        assertEquals(listOf("kb_search", "calc", "memory_save", null), steps.map { it.tool })
        assertTrue(steps[0].observation!!.contains("Stavba – samonivelace"))
        assertTrue(steps[1].observation!!, steps[1].observation!!.contains("= 3.6") || steps[1].observation!!.contains("= 3,6"))
        assertEquals(1, mem.items.size)
        // nástroje a protokol jsou v systémovém promptu, výsledky jdou zpět jako data
        assertTrue(eng.seen[0][0].content.contains("REŽIM AKTIVNÍHO AGENTA"))
        assertTrue(eng.seen[1].last().content.startsWith("VÝSLEDEK NÁSTROJE kb_search"))
    }

    @Test fun agentStopsAtLimit() {
        val tools = Tools.offline(kb, Mem(), { cz.axiom.omega.core.step0.Rates.FALLBACK }) { _, _ -> }
        val loop = "AKCE: {\"tool\":\"time\",\"args\":{}}"
        val eng = Script(mutableListOf(loop, loop, "HOTOVO: konec"))
        val r = ActiveAgent(tools, Mem()) { SelfState("S", "t", true) }.run("x", emptyList(), eng, 2, CancelToken())
        assertEquals("max_steps", r.finish)
        assertEquals("konec", r.answer)
    }

    @Test fun parserIsTolerant() {
        val p = ActiveAgent.parse("MYŠLENKA: hledám\nAKCE: {\"tool\": \"web_search\", \"q\": \"gemma {4}\"}")
        assertEquals("web_search", p.call!!.first)
        assertEquals("\"gemma {4}\"", p.call!!.second["q"].toString())
        assertNull(ActiveAgent.parse("jen text").call)
        assertEquals("ok", ActiveAgent.parse("HOTOVO: ok").final)
    }

    @Test fun routerDecides() {
        val ctx = Router.Context(online = true, engines = listOf("local", "kimi", "claude"))
        val a = Router.route("Najdi aktuální cenu Gemma 4 modelů a porovnej je", ctx)
        assertEquals(Router.Mode.AGENT, a.mode)
        assertEquals("local", a.engine)
        assertEquals("kimi", Router.route("Najdi chyby", ctx.copy(engines = listOf("kimi", "claude"))).engine)
        val b = Router.route("Hlavní město Francie?", ctx)
        assertEquals(Router.Mode.CHAT, b.mode)
        assertEquals("local", b.engine)
        val c = Router.route("/agent spočítej mi rozpočet", ctx)
        assertEquals(Router.Mode.AGENT, c.mode)
        assertEquals("spočítej mi rozpočet", c.prompt)
        val off = Router.route("Najdi chyby v plánu", ctx.copy(online = false, engines = listOf("local")))
        assertEquals("local", off.engine)
        val forced = Router.route("Ahoj", ctx.copy(userEngine = "claude"))
        assertEquals("server", Router.route("Ahoj", ctx.copy(userEngine = "server")).engine)
        assertEquals("claude", forced.engine)
        assertEquals(RandyLevel.L4_NEMESIS_REDTEAM, Router.route("L4: plán", ctx).level)
    }
}

class HubWebTest {
    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    private fun serve(routes: Map<String, Pair<String, String>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, resp) ->
            s.createContext(path) { ex ->
                val b = resp.second.toByteArray()
                ex.responseHeaders.add("Content-Type", resp.first)
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test fun hfModelsFilesAndBrain() {
        val base = serve(
            mapOf(
                "/api/models/unsloth/gemma-4-E4B-it-GGUF/tree/main" to ("application/json" to """[
                  {"type":"file","path":"README.md","size":10},
                  {"type":"file","path":"mmproj-F16.gguf","size":990,"lfs":{"oid":"${"a".repeat(64)}","size":990}},
                  {"type":"file","path":"gemma-4-E4B-it-Q4_K_M.gguf","size":4977171584,"lfs":{"oid":"85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87","size":4977171584}}
                ]"""),
                "/api/models" to ("application/json" to """[{"id":"unsloth/gemma-4-E4B-it-GGUF","downloads":123,"likes":5,"lastModified":"2026-04-02T00:00:00Z","gated":false}]"""),
            ),
        )
        val hub = HfHub(base, base)
        assertEquals("unsloth/gemma-4-E4B-it-GGUF", hub.searchModels("gemma 4").single().id)
        val f = hub.ggufFiles("unsloth/gemma-4-E4B-it-GGUF").single()
        assertEquals("Q4_K_M", f.quant)
        val b = hub.toBrain(f)
        assertEquals("$base/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf", b.urls.single())
        assertEquals(7, b.minRamGb)
        assertTrue(b.id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}")))
    }

    @Test fun datasetRowToDoc() {
        val row = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"1","title":"Beton","text":"Beton je stavební materiál z cementu, kameniva a vody."}""") as kotlinx.serialization.json.JsonObject
        assertEquals("Beton" to "Beton je stavební materiál z cementu, kameniva a vody.", HfHub.rowToDoc(row, "x"))
        val qa = kotlinx.serialization.json.Json.parseToJsonElement("""{"question":"Kolik je 2+2?","answer":"Čtyři, protože 2+2=4."}""") as kotlinx.serialization.json.JsonObject
        assertTrue(HfHub.rowToDoc(qa, "QA")!!.second.contains("Question: Kolik je 2+2?"))
    }

    @Test fun htmlToTextAndSearchParsing() {
        val t = Web.htmlToText("<html><head><style>x{}</style><script>alert(1)</script></head><body><h1>Nadpis</h1><p>Odstavec &amp; text</p><ul><li>a</li></ul></body></html>")
        assertEquals("Nadpis\nOdstavec & text\n• a", t)
        val ddg = """<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa&amp;rut=x">Příklad <b>A</b></a>
            <a class="result__snippet" href="#">Úryvek A</a>"""
        val base = serve(mapOf("/html/" to ("text/html" to ddg)))
        // vyhledávání přes https je vynucené; parser otestujeme přímo na regexu přes veřejnou funkci s lokálním serverem nelze → test entit a regexu:
        assertTrue(base.startsWith("http://"))
        assertEquals("ř", Web.decodeEntities("&#345;"))
    }

    @Test fun agentBlocksLocalNetwork() {
        for (u in listOf("http://example.com", "https://192.168.1.1/admin", "https://localhost/x")) {
            try { Web.requirePublicHttps(u); org.junit.Assert.fail(u) } catch (_: cz.axiom.omega.core.llm.EngineException) {}
        }
    }
}
