package cz.axiom.omega.core.security

import cz.axiom.omega.core.hub.Web
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.log2

/**
 * Bezpečnostní sada pro ETICKÉ testování a obranu: pouze pasivní průzkum veřejných informací,
 * audity konfigurace a kryptografické pomůcky. Žádné exploity, žádné aktivní skenování cizích systémů.
 * Testuj jen systémy, které vlastníš nebo k nimž máš písemné povolení.
 */
object SecTools {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- kryptografické pomůcky (offline)

    fun hash(text: String): String = listOf("MD5", "SHA-1", "SHA-256", "SHA-512").joinToString("\n") { alg ->
        "$alg: " + MessageDigest.getInstance(alg).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Odhad typu hashe podle délky a abecedy. */
    fun identifyHash(h: String): String {
        val s = h.trim()
        return when {
            s.startsWith("\$2a$") || s.startsWith("\$2b$") || s.startsWith("\$2y$") -> "bcrypt"
            s.startsWith("\$argon2") -> "Argon2"
            s.startsWith("\$6$") -> "SHA-512 crypt (Linux /etc/shadow)"
            s.startsWith("\$5$") -> "SHA-256 crypt"
            s.startsWith("\$1$") -> "MD5 crypt (zastaralé)"
            s.matches(Regex("[0-9a-fA-F]{32}")) -> "MD5 nebo NTLM (32 hex)"
            s.matches(Regex("[0-9a-fA-F]{40}")) -> "SHA-1 (40 hex)"
            s.matches(Regex("[0-9a-fA-F]{56}")) -> "SHA-224 (56 hex)"
            s.matches(Regex("[0-9a-fA-F]{64}")) -> "SHA-256 nebo SHA3-256 (64 hex)"
            s.matches(Regex("[0-9a-fA-F]{96}")) -> "SHA-384 (96 hex)"
            s.matches(Regex("[0-9a-fA-F]{128}")) -> "SHA-512 nebo SHA3-512 (128 hex)"
            else -> "neznámý formát"
        }
    }

    fun encode(mode: String, text: String): String = when (mode.lowercase()) {
        "base64" -> Base64.getEncoder().encodeToString(text.toByteArray())
        "base64d", "unbase64" -> String(Base64.getMimeDecoder().decode(text.trim()))
        "hex" -> text.toByteArray().joinToString("") { "%02x".format(it) }
        "hexd", "unhex" -> String(text.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        "url" -> URLEncoder.encode(text, "UTF-8")
        "urld", "unurl" -> java.net.URLDecoder.decode(text, "UTF-8")
        else -> throw IllegalArgumentException("Režim: base64, base64d, hex, hexd, url, urld")
    }

    /** Dekóduje JWT (BEZ ověření podpisu) a upozorní na slabá místa. */
    fun jwt(token: String): String {
        val parts = token.trim().split('.')
        require(parts.size in 2..3) { "Není to JWT (očekávám header.payload.signature)." }
        val dec = { p: String -> String(Base64.getUrlDecoder().decode(p.padEnd((p.length + 3) / 4 * 4, '='))) }
        val header = dec(parts[0])
        val payload = dec(parts[1])
        val warn = mutableListOf<String>()
        val h = runCatching { json.parseToJsonElement(header).jsonObject }.getOrNull()
        val alg = (h?.get("alg") as? JsonPrimitive)?.contentOrNull
        if (alg.equals("none", true)) warn += "alg=none – token bez podpisu (kritické)"
        if (alg?.startsWith("HS") == true) warn += "symetrický podpis $alg – tajemství musí být dlouhé a náhodné"
        val p = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val exp = (p?.get("exp") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        if (exp == null) warn += "chybí exp – token nikdy nevyprší"
        else if (exp * 1000 < System.currentTimeMillis()) warn += "token vypršel ${Date(exp * 1000)}"
        return "HEADER: $header\nPAYLOAD: $payload\nPODPIS: ${if (parts.size == 3 && parts[2].isNotEmpty()) "přítomen (neověřen)" else "chybí"}" +
            if (warn.isEmpty()) "" else "\nUPOZORNĚNÍ:\n- " + warn.joinToString("\n- ")
    }

    /** Odhad síly hesla (entropie + běžné vzory). Heslo se nikam neposílá. */
    fun passwordStrength(pw: String): String {
        var pool = 0
        if (pw.any { it.isLowerCase() }) pool += 26
        if (pw.any { it.isUpperCase() }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 33
        val bits = if (pool == 0) 0.0 else pw.length * log2(pool.toDouble())
        val issues = mutableListOf<String>()
        if (pw.length < 12) issues += "kratší než 12 znaků"
        if (Regex("(.)\\1{2,}").containsMatchIn(pw)) issues += "opakované znaky"
        if (Regex("(?i)(1234|abcd|qwer|asdf|heslo|password|admin|letmein)").containsMatchIn(pw)) issues += "běžný vzor nebo slovo"
        if (Regex("(19|20)\\d{2}").containsMatchIn(pw)) issues += "obsahuje rok"
        val verdict = when {
            bits < 40 || issues.size >= 2 -> "SLABÉ"
            bits < 60 -> "STŘEDNÍ"
            bits < 80 -> "SILNÉ"
            else -> "VELMI SILNÉ"
        }
        return "Síla: $verdict (~${bits.toInt()} bitů entropie)" + if (issues.isEmpty()) "" else "\nSlabiny: ${issues.joinToString()}"
    }

    /** Have I Been Pwned (k-anonymita): odešle se jen prvních 5 znaků SHA-1. */
    fun pwnedCount(pw: String, base: String = "https://api.pwnedpasswords.com"): Int {
        val sha = MessageDigest.getInstance("SHA-1").digest(pw.toByteArray()).joinToString("") { "%02X".format(it) }
        val (_, body) = Web.get("$base/range/${sha.take(5)}", accept = "text/plain")
        return parsePwned(body, sha.drop(5))
    }

    fun parsePwned(body: String, suffix: String): Int =
        body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(suffix, true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

    private val secretPatterns = listOf(
        "AWS Access Key" to Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        "GitHub token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}\b"""),
        "GitLab token" to Regex("""\bglpat-[A-Za-z0-9_-]{20,}\b"""),
        "Slack token" to Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}\b"""),
        "Google API key" to Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
        "Stripe secret" to Regex("""\bsk_live_[0-9A-Za-z]{20,}\b"""),
        "OpenAI/Anthropic-like key" to Regex("""\bsk-(?:ant-)?[A-Za-z0-9_-]{20,}\b"""),
        "Hugging Face token" to Regex("""\bhf_[A-Za-z0-9]{30,}\b"""),
        "Soukromý klíč" to Regex("""-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"""),
        "JWT" to Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*"""),
        "Heslo v konfiguraci" to Regex("""(?i)\b(?:password|passwd|pwd|secret|api[_-]?key)\s*[:=]\s*['"]?[^\s'"]{6,}"""),
    )

    /** Hledá uniklé klíče a hesla v textu (kód, konfigurace, logy). */
    fun secretScan(text: String): String {
        val hits = secretPatterns.flatMap { (name, rx) -> rx.findAll(text).map { "$name: ${mask(it.value)}" }.toList() }
        return if (hits.isEmpty()) "Žádná tajemství nenalezena." else "Nalezeno ${hits.size}:\n- " + hits.distinct().joinToString("\n- ") +
            "\nDoporučení: klíče okamžitě zneplatnit (rotovat) a odstranit z historie repozitáře."
    }

    private fun mask(s: String) = if (s.length <= 10) "****" else s.take(6) + "…" + s.takeLast(4)

    // ---------------------------------------------------------------- pasivní audity (online)

    data class HeaderAudit(val score: Int, val findings: List<String>)

    /** Vyhodnotí bezpečnostní hlavičky odpovědi (čistá funkce – testovatelná). */
    fun auditHeaders(headers: Map<String, List<String>>, https: Boolean): HeaderAudit {
        val h = headers.mapKeys { it.key.lowercase() }
        fun has(k: String) = h[k]?.firstOrNull()
        val f = mutableListOf<String>()
        var score = 100
        fun miss(k: String, pts: Int, why: String) { if (has(k) == null) { score -= pts; f += "CHYBÍ $k – $why" } else f += "OK $k: ${has(k)!!.take(120)}" }
        if (!https) { score -= 30; f += "Web neběží přes HTTPS" }
        miss("strict-transport-security", 15, "HSTS chrání před downgrade na HTTP")
        miss("content-security-policy", 20, "CSP omezuje XSS")
        miss("x-content-type-options", 10, "nosniff brání MIME sniffingu")
        if (has("x-frame-options") == null && has("content-security-policy")?.contains("frame-ancestors") != true) { score -= 10; f += "CHYBÍ x-frame-options / frame-ancestors – riziko clickjackingu" }
        miss("referrer-policy", 5, "omezuje únik URL")
        miss("permissions-policy", 5, "omezuje přístup ke kameře, poloze…")
        has("server")?.let { if (Regex("\\d").containsMatchIn(it)) { score -= 5; f += "Hlavička server prozrazuje verzi: $it" } }
        has("x-powered-by")?.let { score -= 5; f += "x-powered-by prozrazuje technologii: $it" }
        h["set-cookie"]?.forEach { c ->
            val l = c.lowercase()
            val name = c.substringBefore('=')
            if (!l.contains("secure")) { score -= 3; f += "Cookie $name bez Secure" }
            if (!l.contains("httponly")) { score -= 3; f += "Cookie $name bez HttpOnly" }
            if (!l.contains("samesite")) { score -= 2; f += "Cookie $name bez SameSite" }
        }
        return HeaderAudit(score.coerceIn(0, 100), f)
    }

    fun httpAudit(url: String): String {
        Web.requirePublicHttps(url)
        val c = Http.open(url, "GET", readMs = 20_000)
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            val audit = auditHeaders(c.headerFields.filterKeys { it != null }, url.startsWith("https"))
            val tls = (c as? HttpsURLConnection)?.let { tlsSummary(it) } ?: ""
            return "HTTP $code · skóre hlaviček ${audit.score}/100\n" + audit.findings.joinToString("\n") + if (tls.isNotEmpty()) "\n\nTLS:\n$tls" else ""
        } finally {
            c.disconnect()
        }
    }

    private fun tlsSummary(c: HttpsURLConnection): String {
        val cert = c.serverCertificates.firstOrNull() as? X509Certificate ?: return ""
        val days = ((cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L)
        val sans = runCatching { cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }?.take(12) }.getOrNull().orEmpty()
        val df = SimpleDateFormat("d. M. yyyy", Locale("cs"))
        return "Šifra: ${c.cipherSuite}\nSubjekt: ${cert.subjectX500Principal.name}\nVydavatel: ${cert.issuerX500Principal.name}\n" +
            "Platnost do: ${df.format(cert.notAfter)} (${if (days < 0) "PROŠLÝ" else "$days dní"})\nSAN: ${sans.joinToString()}" +
            if (days in 0..20) "\nUPOZORNĚNÍ: certifikát brzy vyprší" else ""
    }

    /** DNS přes DNS-over-HTTPS (Cloudflare) + kontrola SPF a DMARC. */
    fun dns(domain: String, base: String = "https://cloudflare-dns.com/dns-query"): String {
        val d = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        require(d.matches(Regex("[a-z0-9.-]+\\.[a-z]{2,}"))) { "Neplatná doména." }
        val out = StringBuilder()
        var spf = false
        for (type in listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "CAA")) {
            val ans = dohQuery(base, d, type)
            if (ans.isNotEmpty()) out.append("$type: ${ans.joinToString(" | ")}\n")
            if (type == "TXT" && ans.any { it.contains("v=spf1") }) spf = true
        }
        val dmarc = dohQuery(base, "_dmarc.$d", "TXT").firstOrNull { it.contains("v=DMARC1", true) }
        out.append("\nE-mailová ochrana: SPF ${if (spf) "ano" else "CHYBÍ"}, DMARC ${dmarc ?: "CHYBÍ (riziko podvržených e-mailů)"}")
        return out.toString().trim()
    }

    private fun dohQuery(base: String, name: String, type: String): List<String> {
        val (_, body) = Web.get("$base?name=${Web.enc(name)}&type=$type", accept = "application/dns-json")
        val ans = (json.parseToJsonElement(body) as JsonObject)["Answer"] as? JsonArray ?: return emptyList()
        return ans.mapNotNull { ((it as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull?.trim('"') }.distinct().take(15)
    }

    /** Pasivní zjištění subdomén z Certificate Transparency logů (crt.sh). */
    fun subdomains(domain: String, base: String = "https://crt.sh"): String {
        val d = domain.trim().lowercase()
        require(d.matches(Regex("[a-z0-9.-]+\\.[a-z]{2,}"))) { "Neplatná doména." }
        val (_, body) = Web.get("$base/?q=${Web.enc("%.$d")}&output=json", maxBytes = 8_000_000, accept = "application/json")
        val names = parseCrtSh(body, d)
        return if (names.isEmpty()) "V CT logech nic." else "Nalezeno ${names.size} jmen (pasivně z CT logů):\n" + names.take(150).joinToString("\n")
    }

    fun parseCrtSh(body: String, domain: String): List<String> {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.flatMap { e -> ((e as? JsonObject)?.get("name_value") as? JsonPrimitive)?.contentOrNull?.split('\n').orEmpty() }
            .map { it.trim().lowercase().removePrefix("*.") }
            .filter { it == domain || it.endsWith(".$domain") }
            .distinct().sorted()
    }

    /** RDAP (moderní WHOIS) – vlastník domény/IP, registrátor, data. */
    fun rdap(query: String, base: String = "https://rdap.org"): String {
        val q = query.trim()
        val path = if (q.matches(Regex("[0-9.:a-fA-F]+")) && (q.contains('.') && q.count { it == '.' } == 3 || q.contains(':'))) "ip/$q" else "domain/$q"
        val (_, body) = Web.get("$base/$path", accept = "application/rdap+json,application/json")
        val o = json.parseToJsonElement(body) as JsonObject
        val events = (o["events"] as? JsonArray)?.mapNotNull { e ->
            val j = e as? JsonObject ?: return@mapNotNull null
            "${(j["eventAction"] as? JsonPrimitive)?.contentOrNull}: ${(j["eventDate"] as? JsonPrimitive)?.contentOrNull?.take(10)}"
        }.orEmpty()
        val ns = (o["nameservers"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("ldhName") as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val status = (o["status"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val name = (o["ldhName"] as? JsonPrimitive)?.contentOrNull ?: (o["name"] as? JsonPrimitive)?.contentOrNull ?: q
        return "Objekt: $name\nStav: ${status.joinToString()}\nUdálosti: ${events.joinToString(" · ")}\nNS: ${ns.joinToString()}"
    }

    /** Zranitelnosti z OSV.dev: buď podle ID (CVE-…, GHSA-…), nebo balíček + verze. */
    fun vulns(query: String, ecosystem: String?, version: String?, base: String = "https://api.osv.dev"): String {
        val q = query.trim()
        if (q.matches(Regex("(?i)(CVE|GHSA|PYSEC|GO|RUSTSEC)-[A-Za-z0-9-]+"))) {
            val (_, body) = Web.get("$base/v1/vulns/${q.uppercase()}", accept = "application/json")
            val o = json.parseToJsonElement(body) as JsonObject
            return formatVuln(o)
        }
        require(!ecosystem.isNullOrBlank()) { "Pro balíček zadej ecosystem (npm, PyPI, Maven, Go, crates.io, Debian…)." }
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("package", kotlinx.serialization.json.buildJsonObject { put("name", JsonPrimitive(q)); put("ecosystem", JsonPrimitive(ecosystem)) })
            if (!version.isNullOrBlank()) put("version", JsonPrimitive(version))
        }.toString()
        Web.requirePublicHttps("$base/v1/query")
        val c = Http.open("$base/v1/query", "POST", readMs = 20_000)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(payload.toByteArray()) }
            if (c.responseCode != 200) throw EngineException("OSV: HTTP ${c.responseCode}")
            val o = json.parseToJsonElement(Http.readAll(c.inputStream, 8_000_000)) as JsonObject
            val list = (o["vulns"] as? JsonArray).orEmpty()
            if (list.isEmpty()) return "OSV: pro $q ${version ?: ""} ($ecosystem) nejsou známé zranitelnosti."
            return "OSV: ${list.size} zranitelností pro $q ${version ?: ""}:\n" + list.take(15).joinToString("\n\n") { formatVuln(it as JsonObject) }
        } finally {
            c.disconnect()
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private fun formatVuln(o: JsonObject): String {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val aliases = (o["aliases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val sev = (o["severity"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("score") as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return "${s("id")} ${if (aliases.isNotEmpty()) "(${aliases.joinToString()})" else ""}\n${s("summary") ?: s("details")?.take(400) ?: ""}" +
            (if (sev.isNotEmpty()) "\nZávažnost: ${sev.joinToString()}" else "") + "\nPublikováno: ${s("published")?.take(10) ?: "?"}"
    }

    fun host(url: String): String = URI(if (url.contains("://")) url else "https://$url").host ?: url
}
