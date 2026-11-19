package cz.axiom.vega.web

import cz.axiom.vega.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Podklad pro odpověď: titul, zdroj (URL nebo „lokální index“) a text. */
data class Snippet(val id: Long, val title: String, val text: String, val source: String)

/**
 * VEŘEJNÝ WEB: stažení stránky jako textu a vyhledání podkladů (Wikipedie cs/en + DuckDuckGo).
 * Vše přes [Http] s publicOnly = stránka nesmí přesměrovat do lokální sítě. Text stránek jsou data, ne pokyny.
 */
object Web {
    fun fetchText(url: String, maxChars: Int = 20_000): String {
        val r = Http.get(url, maxBytes = 3_000_000)
        if (r.code !in 200..299) throw cz.axiom.vega.net.NetException("Stránka vrátila HTTP ${r.code}.")
        val raw = decode(r.body, r.contentType)
        val text = if (r.contentType.contains("html", true) || raw.trimStart().startsWith("<")) htmlToText(raw) else raw
        return text.take(maxChars)
    }

    private fun decode(b: ByteArray, ct: String): String {
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return runCatching { String(b, charset(cs ?: "UTF-8")) }.getOrElse { String(b, Charsets.UTF_8) }
    }

    /** HTML → čitelný text: bez skriptů, stylů a navigace; bloky na řádky; entity rozepsané. */
    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|iframe)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/article|/section)[^>]*>"), "\n").replace(Regex("<[^>]+>"), " ")
        s = entities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
    private fun entities(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toInt().let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } }
        .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> m.groupValues[1].toInt(16).let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun json(url: String) = Http.get(url, maxBytes = 1_500_000).takeIf { it.code in 200..299 }?.let { Json.parseToJsonElement(String(it.body, Charsets.UTF_8)).jsonObject }
    private fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull

    /** Wikipedie: vyhledání stránek a jejich shrnutí (úvodní odstavec) s odkazem na článek. */
    fun wikipedia(query: String, lang: String, n: Int): List<Snippet> {
        val pages = (json("https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=${enc(query)}&limit=$n")?.get("pages") as? JsonArray) ?: return emptyList()
        return pages.mapNotNull { p -> val key = str(p as? JsonObject, "key") ?: return@mapNotNull null
            val s = runCatching { json("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(key).replace("+", "%20")}") }.getOrNull() ?: return@mapNotNull null
            val text = str(s, "extract")?.takeIf { it.length > 40 } ?: return@mapNotNull null
            val url = ((s["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.let { str(it, "page") } ?: "https://$lang.wikipedia.org/wiki/$key"
            Snippet(0, "${str(s, "title") ?: key} – Wikipedie ($lang)", text, url) }
    }

    /**
     * DuckDuckGo (HTML verze bez JavaScriptu): titul, cílová adresa a úryvek. Jeden opakovaný pokus při výpadku
     * spojení; stránka „anomaly“ (ochrana proti botům, HTTP 202) = žádné výsledky, ne chyba.
     */
    fun duck(query: String, n: Int): List<Snippet> {
        val url = "https://html.duckduckgo.com/html/?q=${enc(query)}"
        val r = runCatching { Http.get(url, maxBytes = 1_500_000) }.recoverCatching { Thread.sleep(600); Http.get(url, maxBytes = 1_500_000) }.getOrThrow()
        if (r.code != 200) return emptyList()
        return duckParse(String(r.body, Charsets.UTF_8), n)
    }

    /** Výsledky z HTML DuckDuckGo (odděleno kvůli testu na uloženém vzorku). */
    fun duckParse(html: String, n: Int): List<Snippet> {
        if (html.contains("anomaly-modal") || html.contains("challenge-form")) return emptyList()
        val out = ArrayList<Snippet>()
        Regex("(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>").findAll(html).forEach { m ->
            var href = entities(m.groupValues[1]); if (href.startsWith("//")) href = "https:$href"
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) return@forEach
            val title = entities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim(); val snip = entities(m.groupValues[3].replace(Regex("<[^>]+>"), "")).trim()
            if (title.isNotBlank() && snip.length > 30) out += Snippet(0, title, snip, href)
            if (out.size >= n) return out
        }
        return out
    }

    data class Gathered(val snippets: List<Snippet>)

    /** Podklady k dotazu: Wikipedie (cs, en) + web; u prvních dvou webových výsledků i text stránky. Selhání zdroje = jen méně podkladů. */
    fun gather(query: String, n: Int = 6): Gathered {
        val all = ArrayList<Snippet>()
        for (lang in listOf("cs", "en")) runCatching { wikipedia(query, lang, 2) }.getOrNull()?.let { all += it }
        runCatching { duck(query, n) }.getOrNull()?.forEachIndexed { i, s ->
            val full = if (i < 2) runCatching { fetchText(s.source, 4_000) }.getOrNull()?.takeIf { it.length > s.text.length } else null
            all += if (full != null) s.copy(text = full) else s
        }
        return Gathered(all.distinctBy { it.source.substringBefore('#') }.take(n + 2).mapIndexed { i, s -> s.copy(id = i.toLong() + 1) })
    }
}
