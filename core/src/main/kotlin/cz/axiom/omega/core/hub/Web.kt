package cz.axiom.omega.core.hub

import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.core.util.TextUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

/** Webové nástroje bez API klíčů: stažení stránky jako textu, vyhledávání, Wikipedie. */
object Web {

    private val json = Json { ignoreUnknownKeys = true }

    fun get(url: String, maxBytes: Int = 2_000_000, accept: String = "text/html,application/json,text/plain;q=0.9,*/*;q=0.5"): Pair<String, String> {
        requirePublicHttps(url)
        val c = Http.open(url, readMs = 25_000)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "cs,en;q=0.8")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw EngineException("HTTP $code z ${URI(url).host}")
            val type = c.contentType.orEmpty()
            if (!(type.isEmpty() || type.contains("text") || type.contains("json") || type.contains("xml"))) {
                throw EngineException("Obsah typu $type nelze číst jako text.")
            }
            return type to Http.readAll(c.inputStream, maxBytes)
        } finally {
            c.disconnect()
        }
    }

    /** Agent smí jen na veřejné https adresy (ne do lokální sítě telefonu). */
    fun requirePublicHttps(url: String) {
        val u = URI(url)
        if (u.scheme?.lowercase() != "https") throw EngineException("Agent smí otevírat jen https:// adresy.")
        val host = u.host ?: throw EngineException("Neplatná adresa.")
        if (Http.isPrivateHost(host)) throw EngineException("Adresy v lokální síti agent neotevírá.")
        runCatching { InetAddress.getAllByName(host) }.getOrNull()?.forEach {
            if (it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress) {
                throw EngineException("Adresa vede do lokální sítě – zablokováno.")
            }
        }
    }

    /** HTML → čitelný text (bez skriptů, stylů a navigace). */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n• ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        return s.lines().map { TextUtil.normalize(it) }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }

    fun fetchText(url: String, maxChars: Int = 12_000): String {
        val (type, body) = get(url)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }
        val text = if (type.contains("html") || body.trimStart().startsWith("<")) htmlToText(body) else body
        return TextUtil.clip((title?.let { "$it\n\n" } ?: "") + text, maxChars)
    }

    data class Hit(val title: String, val url: String, val snippet: String)

    /** Vyhledávání na webu přes DuckDuckGo (HTML verze, bez klíče). */
    fun search(query: String, limit: Int = 6, base: String = "https://html.duckduckgo.com/html/?q="): List<Hit> {
        val (_, html) = get(base + enc(query))
        val out = mutableListOf<Hit>()
        val rx = Regex("""(?is)<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?(?:class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>)?""")
        for (m in rx.findAll(html)) {
            var href = decodeEntities(m.groupValues[1])
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("https://")) continue
            out += Hit(htmlToText(m.groupValues[2]), href, htmlToText(m.groupValues[3]))
            if (out.size >= limit) break
        }
        return out
    }

    // ---------------------------------------------------------------- Wikipedie

    data class WikiPage(val title: String, val url: String, val text: String)

    fun wikiSearch(query: String, lang: String = "cs", limit: Int = 5, base: String = "https://$lang.wikipedia.org"): List<String> {
        val url = "$base/w/api.php?action=query&list=search&format=json&formatversion=2&srlimit=$limit&srsearch=${enc(query)}"
        val root = json.parseToJsonElement(get(url, accept = "application/json").second) as JsonObject
        val arr = (root["query"] as? JsonObject)?.get("search") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull }
    }

    fun wikiPage(title: String, lang: String = "cs", base: String = "https://$lang.wikipedia.org"): WikiPage? {
        val url = "$base/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&format=json&formatversion=2&titles=${enc(title)}"
        val root = json.parseToJsonElement(get(url, accept = "application/json").second) as JsonObject
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonArray ?: return null
        val p = pages.firstOrNull() as? JsonObject ?: return null
        if (p.containsKey("missing")) return null
        val t = (p["title"] as? JsonPrimitive)?.contentOrNull ?: title
        val text = (p["extract"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text.isBlank()) return null
        return WikiPage(t, "https://$lang.wikipedia.org/wiki/" + enc(t.replace(' ', '_')), text)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
