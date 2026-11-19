package cz.axiom.vega.net

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Chyba volání služby. [retryable] = má smysl zkusit znovu / jiného poskytovatele; [billed] = požadavek mohl být účtován. */
open class NetException(msg: String, val retryable: Boolean = false, val billed: Boolean = false, cause: Throwable? = null) : Exception(msg, cause)

/**
 * JEDINÉ místo, kudy VEGA chodí na síť.
 *  - air-gap: nic neodejde,
 *  - https kamkoli, http jen do lokální sítě (vlastní server s modelem),
 *  - přesměrování se NIKDY nenásledují automaticky: každý cíl se znovu ověří (http → veřejná adresa se odmítne),
 *  - veřejný web ([publicOnly]) se nesmí dostat do lokální sítě (ochrana proti SSRF ze stažené stránky).
 */
object Http {
    @Volatile var airgap = false
    const val USER_AGENT = "VEGA/1.3 (Android; osobni asistent)"

    fun requireAllowed(url: String, publicOnly: Boolean = false) {
        val u = runCatching { URI(url) }.getOrNull() ?: throw NetException("Neplatná adresa.")
        val host = u.host ?: throw NetException("Adresa bez serveru.")
        when (u.scheme?.lowercase()) {
            "https" -> {}
            "http" -> if (!isPrivateHost(host)) throw NetException("Nešifrované http:// jen pro server v lokální síti (192.168.x.x, 10.x.x.x, *.local). Pro internet použij https://.")
            else -> throw NetException("Podporováno je jen https:// (nebo http:// v lokální síti).")
        }
        if (publicOnly && isPrivateHost(host)) throw NetException("Veřejný web nesmí mířit do lokální sítě ($host).")
    }

    /** Lokální síť: localhost, *.local/.lan/.home.arpa, privátní a link-local IPv4/IPv6 (u jmen i podle skutečné adresy). */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        val ip = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
        if (ip != null) {
            val (a, b) = ip.destructured.toList().take(2).map { it.toInt() }
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127) || a == 0
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        return false
    }

    /** Otevře spojení (bez automatického přesměrování). */
    fun open(url: String, method: String = "GET", connectMs: Int = 20_000, readMs: Int = 120_000, publicOnly: Boolean = false): HttpURLConnection {
        if (airgap) throw NetException("Air-gap: síť je vypnutá.")
        requireAllowed(url, publicOnly)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = connectMs; readTimeout = readMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            if (method == "POST") doOutput = true
        }
    }

    data class Response(val code: Int, val url: String, val contentType: String, val body: ByteArray)

    /** GET s ručně ověřovaným přesměrováním (max. 5) a stropem velikosti odpovědi. */
    fun get(url: String, maxBytes: Int = 3_000_000, publicOnly: Boolean = true, headers: Map<String, String> = emptyMap(), readMs: Int = 25_000): Response {
        var cur = url
        repeat(6) {
            val c = open(cur, readMs = readMs, publicOnly = publicOnly)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw NetException("Přesměrování bez cíle.")
                    cur = URI(cur).resolve(loc).toString(); return@repeat
                }
                val body = readAll(if (code in 200..299) c.inputStream else c.errorStream, maxBytes)
                return Response(code, cur, c.contentType.orEmpty(), body)
            } finally { c.disconnect() }
        }
        throw NetException("Příliš mnoho přesměrování.")
    }

    fun readAll(input: InputStream?, maxBytes: Int = 2_000_000): ByteArray {
        if (input == null) return ByteArray(0)
        input.use { s ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() >= maxBytes) break }
            return out.toByteArray()
        }
    }
    fun readText(input: InputStream?, maxBytes: Int = 64_000) = String(readAll(input, maxBytes), Charsets.UTF_8)
}

/** Server-Sent Events (stream odpovědi modelu): volá [onData] pro každý „data:“ řádek, dokud nevrátí false. */
object Sse {
    fun read(input: InputStream, cancel: cz.axiom.vega.llm.CancelToken, onData: (String) -> Boolean) {
        input.bufferedReader(Charsets.UTF_8).use { r ->
            val data = StringBuilder()
            while (true) {
                cancel.check()
                val line = r.readLine() ?: break
                when {
                    line.isEmpty() -> { if (data.isNotEmpty()) { val d = data.toString(); data.setLength(0); if (!onData(d)) return } }
                    line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }
}
