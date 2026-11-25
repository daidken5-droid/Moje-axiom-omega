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
data class Snippet(val id: Long, val title: String, val text: String, val source: String, val date: String = "")

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
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(b, 0, minOf(b.size, 16_384), Charsets.ISO_8859_1))?.groupValues?.get(1)
        // bez uvedené znakové sady: platné UTF-8, jinak česká windows-1250 (ne „�“ místo diakritiky)
        val fallback = if (runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); true }.getOrDefault(false)) Charsets.UTF_8 else charset("windows-1250")
        return runCatching { String(b, cs?.let { charset(it) } ?: fallback) }.getOrElse { String(b, fallback) }
    }

    /** HTML → čitelný text: bez skriptů, stylů a navigace; bloky na řádky; entity rozepsané. */
    fun htmlToText(html: String): String = entities(htmlStrip(html)).lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private val SKIP = setOf("script", "style", "noscript", "svg", "nav", "footer", "form", "iframe", "template")
    private val BLOCK_END = setOf("/p", "/div", "/li", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/tr", "/article", "/section")
    private val VOID = setOf("br", "img", "input", "meta", "link", "hr", "source", "wbr", "area", "base", "col", "embed", "track")
    private val HIDDEN = Regex("(?i)hidden|display:\\s*none|visibility:\\s*hidden|aria-hidden=[\"']true")

    /**
     * Značky pryč jedním průchodem. Komentáře, skryté prvky (skrytý text bývá vložený pokyn pro model) a skripty / styly /
     * navigace i s obsahem; hlavička článku zůstává (nadpis, datum). Dřív řetěz regexů „<(\\w+)[^>]*(hidden…)[^>]*>.*?</\\1>“,
     * „<!--.*?-->“, „<[^>]+>“ – u neuzavřených značek kvadraticky až hůř (40 kB = 10 s, stránka do 3 MB = rešerše stojí).
     */
    internal fun htmlStrip(html: String): String {
        val sb = StringBuilder(html.length / 2); var i = 0; val n = html.length
        var gtSeen = -1
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { sb.append(html, i, n); break }
            sb.append(html, i, lt)
            if (html.startsWith("<!--", lt)) { val e = html.indexOf("-->", lt + 4); if (e < 0) break; sb.append(' '); i = e + 3; continue }
            val c1 = html.getOrNull(lt + 1)
            if (c1 == null || !(c1.isLetter() || c1 == '/' || c1 == '!' || c1 == '?')) { sb.append('<'); i = lt + 1; continue }  // „a < b“ je text
            val gt = if (gtSeen > lt) gtSeen else html.indexOf('>', lt + 1).also { gtSeen = it }
            if (gt < 0) break  // značka bez „>“ až do konce
            val tag = html.substring(lt + 1, gt)
            val name = tag.takeWhile { it.isLetterOrDigit() || it == '/' || it == '!' || it == '-' || it == ':' }.lowercase()
            i = gt + 1
            val bare = name.removePrefix("/")
            val opening = !name.startsWith("/") && !name.startsWith("!") && !tag.endsWith("/") && bare !in VOID
            if (opening && (bare in SKIP || HIDDEN.containsMatchIn(tag.take(2_000)))) {
                // prvek i s obsahem až po první </jméno> (jako dřív); bez konce = zbytek stránky pryč
                var close = html.indexOf("</$bare", i, ignoreCase = true)
                while (close >= 0) { val e = close + 2 + bare.length; if (e >= n || html[e] == '>' || html[e].isWhitespace()) break; close = html.indexOf("</$bare", e, ignoreCase = true) }
                if (close < 0) break
                val cgt = html.indexOf('>', close); if (cgt < 0) break
                sb.append(' '); i = cgt + 1; continue
            }
            sb.append(if (name == "br" || name in BLOCK_END) '\n' else ' ')
        }
        return sb.toString()
    }
    private val NAMED = mapOf("nbsp" to " ", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "ndash" to "–", "mdash" to "—", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "hellip" to "…", "euro" to "€", "copy" to "©", "reg" to "®", "deg" to "°", "times" to "×", "minus" to "−", "middot" to "·",
        "scaron" to "š", "Scaron" to "Š", "ccaron" to "č", "Ccaron" to "Č", "rcaron" to "ř", "Rcaron" to "Ř", "zcaron" to "ž", "Zcaron" to "Ž", "ecaron" to "ě", "Ecaron" to "Ě",
        "aacute" to "á", "Aacute" to "Á", "eacute" to "é", "Eacute" to "É", "iacute" to "í", "Iacute" to "Í", "oacute" to "ó", "Oacute" to "Ó", "uacute" to "ú", "Uacute" to "Ú",
        "yacute" to "ý", "Yacute" to "Ý", "uring" to "ů", "Uring" to "Ů", "ncaron" to "ň", "Ncaron" to "Ň", "tcaron" to "ť", "dcaron" to "ď", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß")
    /** Entity: pojmenované, číselné; &amp; až jako poslední (jinak „&amp;lt;“ dekódováno dvakrát). */
    private fun entities(s: String) = s.replace(Regex("&([A-Za-z]{2,8});")) { m -> NAMED[m.groupValues[1]] ?: m.value }
        .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toInt().let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } }
        .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> m.groupValues[1].toInt(16).let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else "" } }
        .replace("&amp;", "&")

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
            Snippet(0, "${str(s, "title") ?: key} – Wikipedie ($lang)", text, url, date = str(s, "timestamp")?.take(10)?.let { "revize $it" } ?: "") }
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
