package cz.axiom.vega.net

import java.io.InputStream
import java.net.InetAddress
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

    /** Lokální síť: localhost, *.local/.lan/.home.arpa/.internal, privátní, link-local, CGNAT a ULA adresy (i IPv4 v IPv6). */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        val ip = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
        // číselná adresa v NESTANDARDNÍM tvaru (hex 0x7f000001, osmičkově 0177.0.0.1, zkrácená 127.1, desítková 2130706433):
        // Java ji nepřevede, ale systémový resolver (inet_aton) ano → mohla by mířit na 127.0.0.1; veřejný web ji nepoužívá → blokovat
        val numericish = Regex("^(0x[0-9a-f]+|\\d+)(\\.(0x[0-9a-f]+|\\d+)){0,3}$").matches(h)
        if (numericish && (ip == null || ip.groupValues.drop(1).any { it.length > 1 && it.startsWith("0") } || ip.groupValues.drop(1).any { it.toInt() > 255 })) return true
        if (ip != null) { val (a, b) = ip.destructured.toList().take(2).map { it.toInt() }; return privateV4(a, b) }
        if (h.contains(':')) return runCatching { isPrivateAddress(InetAddress.getByName(h)) }.getOrDefault(true) // literál, bez DNS
        return false
    }
    private fun privateV4(a: Int, b: Int) = a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127) || a == 0 || a >= 224
    /** Skutečná adresa (po překladu jména): smyčka, privátní, link-local, CGNAT, ULA, multicast, IPv4 zabalená v IPv6. */
    fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) return privateV4(b[0].toInt() and 255, b[1].toInt() and 255)
        if (b.size == 16) {
            if ((b[0].toInt() and 0xfe) == 0xfc) return true // fc00::/7 ULA
            val mapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
            val compat = (0 until 12).all { b[it] == 0.toByte() }
            if (mapped || compat) return privateV4(b[12].toInt() and 255, b[13].toInt() and 255)
        }
        return false
    }
    /**
     * Veřejný web: jméno se přeloží a ŽÁDNÁ z adres nesmí být v lokální síti (jinak by stránka „evil.example“ s DNS
     * na 127.0.0.1 / 192.168.x.x prošla jménem). Nepřeložitelné jméno projde – spojení pak samo selže.
     *
     * DNS rebinding (jméno se při spojení přeloží znovu, podruhé třeba na 192.168.x.x): u přímého HTTPS spojení
     * [PeerCheck] ověří SKUTEČNOU adresu už připojeného soketu ještě před TLS a před odesláním požadavku.
     * Přes proxy se ověřit nedá (soket vede na proxy, jméno překládá ona) – tam zůstává jen kontrola překladu výše.
     */
    fun requirePublicResolved(host: String) {
        val addrs = runCatching { InetAddress.getAllByName(host.trim('[', ']')) }.getOrNull() ?: return
        addrs.firstOrNull { isPrivateAddress(it) }?.let { throw NetException("Veřejný web nesmí mířit do lokální sítě ($host → ${it.hostAddress}).") }
    }

    /** Otevře spojení (bez automatického přesměrování). */
    fun open(url: String, method: String = "GET", connectMs: Int = 20_000, readMs: Int = 120_000, publicOnly: Boolean = false): HttpURLConnection {
        if (airgap) throw NetException("Air-gap: síť je vypnutá.")
        requireAllowed(url, publicOnly)
        if (publicOnly) URI(url).host?.let { requirePublicResolved(it) }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (publicOnly && this is javax.net.ssl.HttpsURLConnection && direct(url)) sslSocketFactory = PeerCheck(sslSocketFactory)
            requestMethod = method; connectTimeout = connectMs; readTimeout = readMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            if (method == "POST") doOutput = true
        }
    }

    private fun direct(url: String) = runCatching { java.net.ProxySelector.getDefault()?.select(URI(url)).orEmpty().all { it.type() == java.net.Proxy.Type.DIRECT } }.getOrDefault(false)

    /** TLS nad už připojeným soketem: pokud vede do lokální sítě (rebinding), zavřít dřív, než odejde jediný bajt. */
    class PeerCheck(private val d: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
        private fun check(s: java.net.Socket): java.net.Socket {
            val a = s.inetAddress
            if (a != null && isPrivateAddress(a)) { runCatching { s.close() }; throw java.io.IOException("Veřejný web nesmí mířit do lokální sítě (${a.hostAddress}) – spojení zrušeno.") }
            return s
        }
        override fun createSocket(s: java.net.Socket, host: String?, port: Int, autoClose: Boolean): java.net.Socket { check(s); return d.createSocket(s, host, port, autoClose) }
        override fun createSocket(host: String?, port: Int) = check(d.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, la: InetAddress?, lp: Int) = check(d.createSocket(host, port, la, lp))
        override fun createSocket(host: InetAddress?, port: Int) = check(d.createSocket(host, port))
        override fun createSocket(a: InetAddress?, port: Int, la: InetAddress?, lp: Int) = check(d.createSocket(a, port, la, lp))
        /** Nepřipojený soket zkontrolovat nejde → odmítnout; JVM HttpsClient pak použije připojenou variantu (kontrolovanou). */
        override fun createSocket(): java.net.Socket = throw java.net.SocketException("Unconnected sockets not implemented")
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    }

    data class Response(val code: Int, val url: String, val contentType: String, val body: ByteArray)

    /** GET s ručně ověřovaným přesměrováním (max. 5) a stropem velikosti odpovědi. */
    fun get(url: String, maxBytes: Int = 3_000_000, publicOnly: Boolean = true, headers: Map<String, String> = emptyMap(), readMs: Int = 25_000): Response {
        var cur = url
        repeat(6) {
            val c = open(cur, readMs = readMs, publicOnly = publicOnly)
            // vlastní hlavičky (např. klíč) jen na původní server, ne na cíl přesměrování jinam
            if (URI(cur).host.equals(URI(url).host, true)) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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

    /** Požadavek s tělem (API): bez automatického přesměrování, odpověď do [maxBytes]. */
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, maxBytes: Int = 4_000_000, readMs: Int = 60_000): Response {
        val c = open(url, if (method == "GET") "GET" else "POST", readMs = readMs)
        if (method != "GET" && method != "POST") { c.requestMethod = method }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Response(code, url, c.contentType.orEmpty(), if (code in 300..399) (c.getHeaderField("Location") ?: "").toByteArray() else readAll(stream, maxBytes))
        } finally { c.disconnect() }
    }

    /**
     * Stažení velkého souboru (APK) rovnou na disk se stropem velikosti. Přesměrování se ověřují ručně; hlavičky
     * (token) jdou jen na původní server – podepsaný odkaz úložiště je dostane bez nich.
     */
    fun download(url: String, target: java.io.File, maxBytes: Long, headers: Map<String, String> = emptyMap()) {
        var cur = url
        repeat(6) {
            val c = open(cur, readMs = 120_000, publicOnly = true)
            if (URI(cur).host.equals(URI(url).host, true)) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                val code = c.responseCode
                if (code in 300..399) { cur = URI(cur).resolve(c.getHeaderField("Location") ?: throw NetException("Přesměrování bez cíle.")).toString(); return@repeat }
                if (code !in 200..299) throw NetException("Stažení selhalo: HTTP $code")
                val tmp = java.io.File(target.parentFile, target.name + ".part")
                c.inputStream.use { s -> tmp.outputStream().use { o -> val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > maxBytes) { tmp.delete(); throw NetException("Soubor je větší než povolený limit.") }; o.write(buf, 0, n) } } }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
                return
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
