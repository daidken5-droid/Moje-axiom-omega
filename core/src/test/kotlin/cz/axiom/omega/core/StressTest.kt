package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.self.SelfState
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZÁTĚŽOVÉ TESTY všech tří režimů:
 *  1) BEZ MOZKU – desítky tisíc otázek, souběžně z 8 vláken (bezpečnost Pavučiny a cache), latence p50/p99, paměť,
 *  2) S MOZKEM OFFLINE – orchestrace kolem mozku (znalosti, paměť, RANDY, streaming, rušení) se simulovaným mozkem,
 *     který streamuje tokeny se zpožděním (skutečný llama.cpp v tomto prostředí neběží),
 *  3) CLOUD – řetěz poskytovatelů proti lokálním serverům, které náhodně vrací 429 / 503 / pomalé odpovědi.
 */
class StressTest {

    private val questions = listOf(
        "kolik je hodin", "Dobrý den, kolik je 15 % z 2000?", "kolik je 7 krát 8", "hlavní město francie", "kdo je prezident", "kolik dní do vánoc",
        "kdy je den matek", "kolik je 100 eur v korunách", "kolik metrů je 5 km", "kdo napsal babičku", "co je inflace", "kolik je hodin v tokiu",
        "21 % dph z 1000", "kolik je mi let narodil jsem se 1. 5. 1990", "hoď kostkou", "jak se máš", "kolik kalorií má banán", "co znamená btw",
        "porovnej výhody a nevýhody tepelného čerpadla pro starý dům", "napiš mi básničku o kočce", "proč je obloha modrá", "jak funguje blockchain v praxi",
        "kolik je hodim", "koľko je 7 krát 8", "what time is it", "Ahoj. Kolik je hodin? Děkuji moc!", "kolik deka je půl kila", "kdo vládne v česku",
    )

    private fun pct(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    @Test fun noBrain_concurrentLoad() {
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        repeat(200) { questions.forEach { Reflex.answer(it, c) } } // zahřátí JIT
        val rt = Runtime.getRuntime(); System.gc(); val memBefore = rt.totalMemory() - rt.freeMemory()
        val n = 24_000
        val pool = Executors.newFixedThreadPool(8)
        val lat = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val errors = AtomicInteger(); val answered = AtomicInteger()
        val t0 = System.nanoTime()
        repeat(n) { i ->
            pool.execute {
                // každá ~5. otázka unikátní (číslo navíc) → testuje i paměť Pavučiny (LRU), ne jen cache
                val q = questions[i % questions.size] + if (i % 5 == 0) " $i" else ""
                val s = System.nanoTime()
                try { if (Reflex.answer(q, c) != null) answered.incrementAndGet() } catch (e: Throwable) { errors.incrementAndGet() }
                lat += (System.nanoTime() - s) / 1000
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        val secs = (System.nanoTime() - t0) / 1e9
        System.gc(); val memAfter = rt.totalMemory() - rt.freeMemory()
        val sorted = lat.sorted()
        println("STRESS bez mozku: $n otázek / 8 vláken za %.2f s = %.0f/s · p50 %d µs · p99 %d µs · odpověděl %d · chyby %d · paměť %+d MB"
            .format(secs, n / secs, pct(sorted, .5), pct(sorted, .99), answered.get(), errors.get(), (memAfter - memBefore) / 1_048_576))
        assertEquals(0, errors.get(), "žádná výjimka při souběhu")
        assertTrue(pct(sorted, .99) < 50_000, "p99 pod 50 ms")
        assertTrue((memAfter - memBefore) / 1_048_576 < 64, "paměť neroste (LRU drží Pavučinu malou)")
    }

    /** Simulovaný mozek: streamuje [tokens] tokenů s [msPerToken] ms, respektuje zrušení. */
    private class SimBrain(val tokens: Int = 60, val msPerToken: Long = 1) : LlmEngine {
        override val id = "sim"; override val label = "Simulovaný mozek"; override val kind = EngineKind.LOCAL; override val contextBudgetChars = 6_000
        val calls = AtomicInteger()
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            calls.incrementAndGet()
            val sb = StringBuilder()
            repeat(tokens) { i -> cancel.check(); Thread.sleep(msPerToken); val t = "slovo$i "; sb.append(t); onDelta(Delta(t)) }
            return GenResult(sb.toString().trim(), model = "sim", millis = tokens * msPerToken)
        }
    }

    private class Mem : MemoryStore {
        val items = java.util.concurrent.CopyOnWriteArrayList((1..300).map { MemoryItem(it.toLong(), MemoryKind.FACT, "Fakt číslo $it o tématu ${it % 17}", 0.5) })
        override fun all() = items.toList()
        override fun remember(item: MemoryItem): Long { items += item.copy(id = items.size + 1L); return items.size.toLong() }
        override fun forget(query: String) = 0
    }

    @Test fun offlineBrain_orchestrationOverheadAndCancel() {
        val kb = object : KnowledgeSource {
            override fun search(query: String, limit: Int) = (1..limit).map { Snippet(it.toLong(), "Článek $it", "Text článku $it ".repeat(40), "wiki") }
        }
        val brain = SimBrain(tokens = 60, msPerToken = 1)
        val o = Orchestrator(kb, Mem(), { SelfState("Sim", "local", false) }, sleeper = {})
        val n = 150
        val t0 = System.nanoTime(); var engineMs = 0L; var deltas = 0
        repeat(n) { i ->
            val r = o.run(AskRequest("Otázka číslo $i: vysvětli téma ${i % 17} podrobně", maxPasses = 1, reflect = false), brain, CancelToken(),
                object : AskListener { override fun onDelta(pass: Int, totalPasses: Int, delta: Delta) { deltas++ } })
            engineMs += 60
            assertTrue(r.answer.isNotBlank())
        }
        val totalMs = (System.nanoTime() - t0) / 1e6
        val overhead = (totalMs - engineMs) / n
        println("STRESS mozek offline (simulovaný): $n dotazů · režie orchestrace %.2f ms/dotaz (znalosti, paměť 300 položek, RANDY, prompt) · %d tokenů streamováno".format(overhead, deltas))
        assertTrue(overhead < 25.0, "režie kolem mozku pod 25 ms")
        // zrušení uprostřed psaní: musí skončit rychle a výjimkou CancelledException
        val slow = SimBrain(tokens = 5_000, msPerToken = 1)
        val cancel = CancelToken()
        Thread { Thread.sleep(80); cancel.cancel() }.start()
        val c0 = System.nanoTime()
        val thrown = runCatching { o.run(AskRequest("dlouhá odpověď", maxPasses = 1, reflect = false), slow, cancel, object : AskListener {}) }.exceptionOrNull()
        val cancelMs = (System.nanoTime() - c0) / 1e6
        println("STRESS zrušení uprostřed odpovědi: %.0f ms (%s)".format(cancelMs, thrown?.javaClass?.simpleName))
        assertTrue(thrown is CancelledException); assertTrue(cancelMs < 1_000)
    }

    @Test fun cloud_flakyProvidersUnderLoad() {
        val calls = AtomicInteger()
        fun server(failEvery: Int, status: Int, delayMs: Long): String {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.executor = Executors.newFixedThreadPool(8)
            s.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val k = calls.incrementAndGet()
                if (failEvery > 0 && k % failEvery == 0) {
                    val b = """{"error":{"message":"limit"}}""".toByteArray(); ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
                } else {
                    Thread.sleep(delayMs)
                    val b = (listOf("Odpověď", " z", " cloudu").joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n" } + "data: [DONE]\n\n").toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
                }
            }
            s.start(); servers += s
            return "http://127.0.0.1:${s.address.port}/v1"
        }
        fun m(name: String, base: String) = FreeCloud.Member(FreeCloud.Provider(name.lowercase(), name, base, listOf("m"), signup = ""), "key-12345678", "m")
        val members = listOf(m("Groq", server(3, 429, 5)), m("Cerebras", server(5, 503, 10)), m("OpenRouter", server(0, 200, 20)))
        val pool = Executors.newFixedThreadPool(6)
        val ok = AtomicInteger(); val fail = AtomicInteger(); val fallbacks = AtomicInteger()
        val n = 300
        val t0 = System.nanoTime()
        repeat(n) {
            pool.execute {
                val e = FreeCloudEngine(members, onFallback = { fallbacks.incrementAndGet() })
                runCatching { e.generate(listOf(ChatMessage(Role.USER, "x")), GenOptions(), CancelToken()) {} }
                    .onSuccess { if (it.text == "Odpověď z cloudu") ok.incrementAndGet() else fail.incrementAndGet() }.onFailure { fail.incrementAndGet() }
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        val secs = (System.nanoTime() - t0) / 1e9
        println("STRESS cloud: $n dotazů / 6 vláken za %.2f s · úspěch %d · selhání %d · přepnutí na zálohu %d (limity 429 a výpadky 503)".format(secs, ok.get(), fail.get(), fallbacks.get()))
        assertEquals(n, ok.get(), "každý dotaz dostal odpověď díky přepnutí na zálohu")
        servers.forEach { it.stop(0) }
    }

    private val servers = mutableListOf<HttpServer>()
}
