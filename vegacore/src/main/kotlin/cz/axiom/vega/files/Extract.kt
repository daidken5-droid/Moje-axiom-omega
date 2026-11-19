package cz.axiom.vega.files

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * TEXT Z DOKUMENTŮ bez knihoven: DOCX, XLSX, PPTX, ODT/ODS/ODP (ZIP + XML). PDF a obrázky řeší aplikace (PDF, OCR).
 * Ochrana proti „zip bombě“: každý záznam i součet se čtou jen do stropu, nic se nerozbaluje celé do paměti.
 */
object Extract {
    enum class Kind(val label: String) { PDF("PDF"), IMAGE("obrázek"), DOCX("Word"), XLSX("Excel"), PPTX("PowerPoint"), ODF("OpenDocument"), TEXT("text"), OTHER("soubor") }
    data class Result(val kind: Kind, val text: String, val note: String? = null)

    private const val ENTRY_CAP = 40_000_000; private const val TOTAL_CAP = 120_000_000

    fun kindOf(name: String, mime: String?, head: ByteArray): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> Kind.PDF
            ext == "pdf" || mime == "application/pdf" -> Kind.PDF
            ext in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif") || mime?.startsWith("image/") == true -> Kind.IMAGE
            ext == "docx" -> Kind.DOCX; ext == "xlsx" -> Kind.XLSX; ext == "pptx" -> Kind.PPTX
            ext in setOf("odt", "ods", "odp") -> Kind.ODF
            ext in setOf("txt", "csv", "tsv", "md", "json", "log", "xml", "html", "htm", "kt", "java", "js", "py") || mime?.startsWith("text/") == true -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /** Text dokumentu, nebo null (neznámý formát → aplikace zkusí dekódovat jako text). */
    fun extract(name: String, mime: String?, bytes: ByteArray, maxChars: Int = 400_000): Result? {
        val kind = kindOf(name, mime, bytes.copyOf(minOf(bytes.size, 8)))
        val want: (String) -> Boolean = when (kind) {
            Kind.DOCX -> { n -> n == "word/document.xml" || n.startsWith("word/footnotes") }
            Kind.XLSX -> { n -> n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) }
            Kind.PPTX -> { n -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml") }
            Kind.ODF -> { n -> n == "content.xml" }
            else -> return null
        }
        val parts = sortedMapOf<String, String>(); var total = 0L; var truncated = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !want(e.name)) continue
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
                while (true) { val n = z.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n
                    if (out.size() > ENTRY_CAP || total > TOTAL_CAP) { truncated = true; break } }
                parts[e.name] = String(out.toByteArray(), Charsets.UTF_8)
                if (truncated) break
            }
        }
        val text = when (kind) {
            Kind.XLSX -> xlsx(parts)
            Kind.DOCX -> parts.values.joinToString("\n") { xmlText(it, para = Regex("</w:p>"), run = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>")) }
            Kind.PPTX -> parts.entries.sortedBy { Regex("\\d+").find(it.key)?.value?.toIntOrNull() ?: 0 }.joinToString("\n\n") { xmlText(it.value, Regex("</a:p>"), Regex("<a:t>([^<]*)</a:t>")) }
            else -> parts.values.joinToString("\n") { xmlText(it, Regex("</text:(?:p|h)>"), null) }
        }
        return Result(kind, text.take(maxChars), when { truncated -> "Dokument je příliš velký – přečtena jen část."; text.length > maxChars -> "Text zkrácen na $maxChars znaků."; else -> null })
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun xmlText(xml: String, para: Regex, run: Regex?): String {
        val withBreaks = xml.replace(para, "\n")
        val t = if (run != null) withBreaks.split('\n').joinToString("\n") { line -> run.findAll(line).joinToString("") { unescape(it.groupValues[1]) } }
                else unescape(withBreaks.replace(Regex("<text:tab/>"), "\t").replace(Regex("<[^>]+>"), ""))
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
    private fun xlsx(parts: Map<String, String>): String {
        val shared = parts["xl/sharedStrings.xml"]?.let { x -> Regex("(?s)<si>(.*?)</si>").findAll(x).map { si -> Regex("<t[^>]*>([^<]*)</t>").findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList() } ?: emptyList()
        return parts.filterKeys { it.startsWith("xl/worksheets/") }.entries.joinToString("\n\n") { (name, x) ->
            "## " + name.substringAfterLast('/').removeSuffix(".xml") + "\n" + Regex("(?s)<row[^>]*>(.*?)</row>").findAll(x).joinToString("\n") { row ->
                Regex("(?s)<c([^>]*)>(.*?)</c>").findAll(row.groupValues[1]).joinToString(";") { c ->
                    val v = Regex("<v>([^<]*)</v>").find(c.groupValues[2])?.groupValues?.get(1) ?: Regex("<t[^>]*>([^<]*)</t>").find(c.groupValues[2])?.groupValues?.get(1) ?: ""
                    if (c.groupValues[1].contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1) ?: "" else unescape(v) } } }
    }
}
