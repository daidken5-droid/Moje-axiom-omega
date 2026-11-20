package cz.axiom.vega.core

import cz.axiom.vega.llm.CancelToken
import cz.axiom.vega.llm.ChatMessage
import cz.axiom.vega.llm.GenOptions
import cz.axiom.vega.llm.OpenAiEngine
import cz.axiom.vega.llm.Pairing
import cz.axiom.vega.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Vlastní server (tools/vega-server): párovací kód + volitelně živý test proti běžícímu serveru. */
class OwnServerTest {
    private fun code(json: String) = "VEGA-PAIR:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun pairingCodeRoundTripAndRejections() {
        // stejný kód, jaký vypíše tools/vega-server/pair.py
        val p = Pairing.parse(" VEGA-PAIR:eyJ2IjoxLCJ1IjoiaHR0cHM6Ly9qbWVuby12ZWdhLmhmLnNwYWNlL3YxIiwibSI6InZlZ2EtcXdlbjMtNGIifQ\n")
        assertEquals("https://jmeno-vega.hf.space/v1", p.url); assertEquals("vega-qwen3-4b", p.model)
        assertEquals("http://192.168.1.20:7860/v1", Pairing.parse(code("""{"v":1,"u":"http://192.168.1.20:7860/v1/","m":""}""")).url)
        for (bad in listOf("ahoj", code("""{"v":2,"u":"https://a.cz/v1","m":"x"}"""), code("""{"v":1,"u":"http://example.com/v1","m":"x"}"""),
                code("""{"v":1,"u":"http://IP-TVEHO-PC:7860/v1","m":"x"}"""), code("""{"v":1,"u":"https://a.cz/v1","m":"x\"; rm"}"""), "VEGA-PAIR:%%%"))
            assertFailsWith<Exception>(bad) { Pairing.parse(bad) }
    }

    @Test fun hintsExplainTypicalFailures() {
        assertTrue(Pairing.hint(cz.axiom.vega.llm.EngineException("server HTTP 401: Invalid API Key")).contains("VEGA_KEY"))
        assertTrue(Pairing.hint(cz.axiom.vega.llm.EngineException("server HTTP 503: <html>Space is building")).contains("probouzí"))
        assertTrue(Pairing.hint(RuntimeException("x", java.net.SocketTimeoutException("read timed out"))).contains("probouzí"))
        assertTrue(Pairing.hint(java.net.ConnectException("refused")).contains("Wi-Fi"))
        assertTrue(Pairing.hint(cz.axiom.vega.llm.EngineException("server HTTP 404: not found")).contains("/v1"))
    }

    /** Živý test: VEGA_OWN_URL=http://127.0.0.1:7860/v1 VEGA_OWN_KEY=… ./gradlew :vegacore:test --tests '*OwnServerTest*' */
    @Test fun liveServerAnswersInCzech() {
        val url = System.getenv("VEGA_OWN_URL") ?: return
        val key = System.getenv("VEGA_OWN_KEY")
        val models = OpenAiEngine.listModels(url, key)
        assertTrue(models.isNotEmpty(), "server nenabízí model")
        assertFailsWith<Exception>("bez klíče musí server odmítnout") { OpenAiEngine.listModels(url, null) }
        val sb = StringBuilder()
        val r = OpenAiEngine(url, key, models.first(), "own").generate(listOf(ChatMessage(Role.SYSTEM, "Odpovídej česky, jednou větou."), ChatMessage(Role.USER, "Jaké je hlavní město Česka?")),
            GenOptions(maxTokens = 60), CancelToken()) { d -> if (!d.reasoning) sb.append(d.text) }
        println("ŽIVÝ SERVER: ${models.first()} · ${r.millis} ms · ${r.outputTokens} tokenů · „${r.text.trim()}“")
        assertTrue(r.text.contains("Praha"), r.text); assertEquals(r.text, sb.toString())
    }
}

/** Bod 52: DNS rebinding – připojený soket do lokální sítě se zavře před TLS. */
class RebindingTest {
    @Test fun peerCheckRefusesLocalSocketBeforeTls() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { srv ->
            val s = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), srv.localPort)
            val f = cz.axiom.vega.net.Http.PeerCheck(javax.net.ssl.SSLContext.getDefault().socketFactory)
            val e = assertFailsWith<java.io.IOException> { f.createSocket(s, "evil.example", 443, true) }
            assertTrue(e.message!!.contains("lokální sítě"), e.message); assertTrue(s.isClosed, "soket se zavřel")
        }
    }
}
