package cz.axiom.omega.core.llm

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

object Http {
    const val USER_AGENT = "AXIOM-Omega/3.0 (Android; +https://github.com/daidken5-droid/Moje-axiom-omega)"

    fun open(url: String, method: String = "GET", connectMs: Int = 20_000, readMs: Int = 180_000): HttpURLConnection {
        requireAllowed(url)
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        if (method == "POST") c.doOutput = true
        return c
    }

    /**
     * HTTPS kamkoli; nešifrované HTTP jen do lokální sítě (vlastní Kimi K3 server
     * v LAN / Tailscale). Tím se zabrání omylem poslat klíč přes internet v čistém textu.
     */
    fun requireAllowed(url: String) {
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "https" -> return
            "http" -> {
                val host = uri.host ?: throw EngineException("Neplatná adresa serveru.")
                if (!isPrivateHost(host)) {
                    throw EngineException("Nešifrované http:// je povolené jen pro server v lokální síti (192.168.x.x, 10.x.x.x, *.local, Tailscale). Pro internet použij https://.")
                }
            }
            else -> throw EngineException("Podporováno je jen https:// (nebo http:// v lokální síti).")
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        if (h.contains(':')) {
            return runCatching { InetAddress.getByName(h).let { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress } }
                .getOrDefault(false) || h.startsWith("fd") || h.startsWith("fc")
        }
        return false
    }

    fun readAll(stream: InputStream?, limit: Int = 2_000_000): String {
        if (stream == null) return ""
        stream.use { s ->
            val out = StringBuilder()
            val r = BufferedReader(InputStreamReader(s, Charsets.UTF_8))
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                out.appendRange(buf, 0, n)
                if (out.length > limit) throw EngineException("Odpověď serveru je příliš velká.")
            }
            return out.toString()
        }
    }
}

/** Server-Sent Events: vrací obsah `data:` jednotlivých událostí (víceřádkové data spojí). */
object Sse {
    fun read(stream: InputStream, cancel: CancelToken, onEvent: (event: String?, data: String) -> Boolean) {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            var event: String? = null
            val data = StringBuilder()
            while (true) {
                cancel.check()
                val line = r.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val keepGoing = onEvent(event, data.toString())
                            data.clear()
                            event = null
                            if (!keepGoing) return
                        }
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).removePrefix(" "))
                    }
                }
            }
            if (data.isNotEmpty()) onEvent(event, data.toString())
        }
    }
}
