package cz.axiom.omega.core.files

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Čtení souborů „přirozeně“ – bez internetu, v čistém Kotlinu (testovatelné):
 * Word (DOCX), Excel (XLSX), PowerPoint (PPTX), OpenDocument (ODT/ODS/ODP), EPUB, HTML, RTF,
 * text/CSV/JSON/kód a ZIP archivy (rekurzivně). PDF, obrázky (OCR) a audio řeší Android vrstva.
 */
object Extract {

    enum class Kind(val label: String) {
        TEXT("text"), DOCX("Word"), XLSX("Excel"), PPTX("PowerPoint"), ODT("OpenDocument text"), ODS("OpenDocument tabulka"),
        ODP("OpenDocument prezentace"), EPUB("e-kniha"), HTML("webová stránka"), RTF("RTF"), ZIP("archiv ZIP"),
        PDF("PDF"), IMAGE("obrázek"), AUDIO("zvuk"), UNKNOWN("neznámý")
    }

    data class Result(val kind: Kind, val text: String, val note: String? = null)

    private val textExt = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "log",
        "py", "kt", "kts", "java", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql", "srt", "vtt",
        "ics", "vcf", "tex", "css", "gradle", "properties", "swift", "dart", "lua", "r", "m", "gpx", "kml")

    fun kindOf(name: String, mime: String?, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime?.lowercase().orEmpty()
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { (head[it].toInt() and 0xff) == b[it] }
        return when {
            starts(0x25, 0x50, 0x44, 0x46) || ext == "pdf" || m == "application/pdf" -> Kind.PDF
            ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif") || m.startsWith("image/") -> Kind.IMAGE
            ext in setOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac", "amr", "3gp", "webm") || m.startsWith("audio/") -> Kind.AUDIO
            ext == "docx" || m.contains("wordprocessingml") -> Kind.DOCX
            ext == "xlsx" || m.contains("spreadsheetml") -> Kind.XLSX
            ext == "pptx" || m.contains("presentationml") -> Kind.PPTX
            ext == "odt" || m.contains("opendocument.text") -> Kind.ODT
            ext == "ods" || m.contains("opendocument.spreadsheet") -> Kind.ODS
            ext == "odp" || m.contains("opendocument.presentation") -> Kind.ODP
            ext == "epub" || m.contains("epub") -> Kind.EPUB
            ext in setOf("html", "htm", "xhtml") || m.contains("html") -> Kind.HTML
            ext == "rtf" || starts(0x7b, 0x5c, 0x72, 0x74, 0x66) -> Kind.RTF
            ext == "zip" || m.contains("zip") || starts(0x50, 0x4b, 0x03, 0x04) -> Kind.ZIP
            ext in textExt || m.startsWith("text/") || m.contains("json") || looksText(head) -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    /** Text z ne-PDF/obrázku/zvuku. Pro ty vrátí null (řeší Android vrstva). */
    fun extract(name: String, mime: String?, bytes: ByteArray, maxChars: Int = 400_000, depth: Int = 0): Result? {
        val kind = kindOf(name, mime, bytes.copyOf(minOf(bytes.size, 16)))
        val text = when (kind) {
            Kind.PDF, Kind.IMAGE, Kind.AUDIO -> return null
            Kind.TEXT -> decode(bytes)
            Kind.DOCX -> zipText(bytes, { it == "word/document.xml" || it.matches(Regex("word/(header|footer|footnotes)\\d*\\.xml")) }) { xmlParagraphs(it, "w:p", "w:t", tab = "w:tab") }
            Kind.PPTX -> zipText(bytes, { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }, sortSlides = true) { xmlParagraphs(it, "a:p", "a:t") }
            Kind.XLSX -> xlsx(bytes)
            Kind.ODT, Kind.ODP -> zipText(bytes, { it == "content.xml" }) { xmlParagraphs(it, "text:p|text:h", null) }
            Kind.ODS -> zipText(bytes, { it == "content.xml" }) { ods(it) }
            Kind.EPUB -> zipText(bytes, { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }, sortSlides = true) { html(it) }
            Kind.HTML -> html(decode(bytes))
            Kind.RTF -> rtf(decode(bytes))
            Kind.ZIP -> if (depth > 1) "" else zipAny(bytes, maxChars, depth)
            Kind.UNKNOWN -> return Result(kind, "", "Formát souboru neznám (${bytes.size / 1024} kB) – neobsahuje čitelný text.")
        }
        val clean = text.replace("\u0000", "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Result(kind, if (clean.length > maxChars) clean.take(maxChars) else clean, if (clean.length > maxChars) "zkráceno na ${maxChars / 1000} tis. znaků" else null)
    }

    // ------------------------------------------------------------------ pomocníci

    fun decode(b: ByteArray): String {
        if (b.size >= 2 && (b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xff) == 0xfe) return String(b, Charsets.UTF_16LE).drop(1)
        if (b.size >= 2 && (b[0].toInt() and 0xff) == 0xfe && (b[1].toInt() and 0xff) == 0xff) return String(b, Charsets.UTF_16BE).drop(1)
        val utf = String(b, Charsets.UTF_8)
        // neplatné UTF-8 (staré soubory z Windows) → windows-1250 (čeština)
        return if (utf.count { it == '�' } > maxOf(3, utf.length / 200)) String(b, charset("windows-1250")) else utf.removePrefix("﻿")
    }

    private fun looksText(head: ByteArray): Boolean = head.isNotEmpty() && head.count { val c = it.toInt() and 0xff; c < 9 || (c in 14..31) } == 0

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                total += data.size
                require(total < 300_000_000) { "Archiv je po rozbalení příliš velký." } // ochrana proti „zip bombě“
                out[e.name] = data
            }
        }
        return out
    }

    private fun zipText(bytes: ByteArray, pick: (String) -> Boolean, sortSlides: Boolean = false, conv: (String) -> String): String {
        val e = entries(bytes).filterKeys(pick)
        val names = if (sortSlides) e.keys.sortedWith(compareBy({ Regex("\\d+").findAll(it).lastOrNull()?.value?.toIntOrNull() ?: 0 }, { it })) else e.keys.toList()
        return names.mapIndexed { i, n ->
            val t = conv(String(e.getValue(n), Charsets.UTF_8))
            if (n.startsWith("ppt/slides/")) "— Snímek ${i + 1} —\n$t" else t
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun zipAny(bytes: ByteArray, maxChars: Int, depth: Int): String {
        val sb = StringBuilder()
        for ((n, data) in entries(bytes)) {
            if (sb.length > maxChars) break
            val r = extract(n.substringAfterLast('/'), null, data, maxChars - sb.length, depth + 1) ?: continue
            if (r.text.isBlank()) continue
            sb.append("=== ").append(n).append(" (").append(r.kind.label).append(") ===\n").append(r.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("&nbsp;", " ").replace("&amp;", "&")

    /** Odstavce z XML: [para] = tagy odstavců (alternace), [run] = tag textu (null = veškerý text uvnitř odstavce). */
    private fun xmlParagraphs(xml: String, para: String, run: String?, tab: String? = null): String {
        val paraRx = Regex("<($para)\\b[^>]*>(.*?)</\\1>|<($para)\\b[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        return paraRx.findAll(xml).map { m ->
            var inner = m.groupValues[2]
            if (tab != null) inner = inner.replace(Regex("<$tab\\b[^>]*/>"), "<t>\t</t>")
            if (run != null) {
                Regex("<($run|t)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { unescape(it.groupValues[2]) }
            } else unescape(inner.replace(Regex("<text:(tab|s)\\b[^>]*/>"), " ").replace(Regex("<[^>]+>"), ""))
        }.joinToString("\n")
    }

    private fun xlsx(bytes: ByteArray): String {
        val e = entries(bytes)
        val shared = e["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(String(xml, Charsets.UTF_8)).map { si ->
                Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val sheetNames = e["xl/workbook.xml"]?.let { Regex("<sheet\\b[^>]*name=\"([^\"]*)\"").findAll(String(it, Charsets.UTF_8)).map { m -> unescape(m.groupValues[1]) }.toList() } ?: emptyList()
        val sheets = e.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sortedBy { Regex("\\d+").findAll(it).last().value.toInt() }
        return sheets.mapIndexed { i, n ->
            val xml = String(e.getValue(n), Charsets.UTF_8)
            val rows = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { row ->
                Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]; val body = c.groupValues[2]
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    when {
                        attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        attrs.contains("t=\"inlineStr\"") -> Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                        else -> unescape(v ?: "")
                    }
                }.joinToString(";")
            }.filter { it.trim(';').isNotBlank() }.joinToString("\n")
            "=== List ${sheetNames.getOrNull(i) ?: (i + 1)} ===\n$rows"
        }.joinToString("\n\n")
    }

    private fun ods(xml: String): String =
        Regex("<table:table\\b[^>]*table:name=\"([^\"]*)\"[^>]*>(.*?)</table:table>", RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString("\n\n") { t ->
            "=== List ${unescape(t.groupValues[1])} ===\n" + Regex("<table:table-row\\b[^>]*>(.*?)</table:table-row>", RegexOption.DOT_MATCHES_ALL).findAll(t.groupValues[2]).map { r ->
                Regex("<table:table-cell\\b[^>]*?(?:/>|>(.*?)</table:table-cell>)", RegexOption.DOT_MATCHES_ALL).findAll(r.groupValues[1])
                    .map { c -> unescape(c.groupValues[1].replace(Regex("<[^>]+>"), " ").trim()) }.toList().dropLastWhile { it.isBlank() }.joinToString(";")
            }.filter { it.isNotBlank() }.joinToString("\n")
        }

    fun html(src: String): String = unescape(
        src.replace(Regex("(?is)<(script|style|head|nav|footer)\\b.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>"), "\n")
            .replace(Regex("(?i)<li\\b[^>]*>"), "- ")
            .replace(Regex("<[^>]+>"), " "),
    ).replace(Regex("[ \\t\\u00a0]{2,}"), " ").lines().joinToString("\n") { it.trim() }

    fun rtf(src: String): String {
        val sb = StringBuilder()
        var i = 0; var depth = 0; val skip = ArrayDeque<Int>()
        while (i < src.length) {
            val c = src[i]
            when {
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (skip.isNotEmpty() && skip.last() == depth) skip.removeLast(); depth--; i++ }
                c == '\\' && i + 1 < src.length -> {
                    val m = Regex("\\\\([a-z]+)(-?\\d+)? ?|\\\\'([0-9a-fA-F]{2})|\\\\(.)").find(src, i)!!
                    val word = m.groupValues[1]
                    when {
                        m.groupValues[3].isNotEmpty() -> if (skip.isEmpty()) sb.append(byteArrayOf(m.groupValues[3].toInt(16).toByte()).toString(charset("windows-1250")))
                        word in setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer") -> skip.addLast(depth)
                        word == "par" || word == "line" -> if (skip.isEmpty()) sb.append('\n')
                        word == "tab" -> if (skip.isEmpty()) sb.append('\t')
                        word == "u" -> if (skip.isEmpty()) m.groupValues[2].toIntOrNull()?.let { sb.append((if (it < 0) it + 65536 else it).toChar()) }
                        m.groupValues[4].isNotEmpty() && m.groupValues[4] in listOf("\\", "{", "}") -> if (skip.isEmpty()) sb.append(m.groupValues[4])
                    }
                    i = m.range.last + 1
                    if (word == "u" && i < src.length && src[i] == '?') i++
                }
                c == '\r' || c == '\n' -> i++
                else -> { if (skip.isEmpty()) sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
