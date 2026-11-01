package cz.axiom.omega.core.forge

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Kam AXIOM ukládá vytvořené soubory. Na Androidu: složka Stažené/AXIOM (MediaStore),
 * PDF kreslí systémový PdfDocument, APK vyrábí [ApkForge] s klíčem z Android Keystore.
 */
interface FileSink {
    /** Uloží soubor a vrátí popis umístění pro uživatele. */
    fun save(name: String, mime: String, bytes: ByteArray): String
    fun pdf(title: String, text: String): ByteArray
    /** Vyrobí a uloží APK; vrátí popis (umístění, balíček). */
    fun apk(appName: String, files: Map<String, ByteArray>): String
}

object FileForge {
    /** Textové formáty, které AXIOM umí vytvořit přímo (kód, data, dokumenty, kalendář, kontakty…). */
    val TEXT_TYPES = linkedMapOf(
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "ts" to "text/plain", "svg" to "image/svg+xml", "xml" to "text/xml",
        "yaml" to "text/yaml", "yml" to "text/yaml", "toml" to "text/plain", "ini" to "text/plain",
        "py" to "text/x-python", "kt" to "text/plain", "java" to "text/x-java", "c" to "text/x-c", "cpp" to "text/x-c",
        "h" to "text/x-c", "rs" to "text/plain", "go" to "text/plain", "sh" to "text/x-shellscript", "bat" to "text/plain",
        "ps1" to "text/plain", "sql" to "text/plain", "ics" to "text/calendar", "vcf" to "text/vcard", "tex" to "text/x-tex",
        "log" to "text/plain", "srt" to "text/plain", "gpx" to "application/gpx+xml", "kml" to "application/vnd.google-earth.kml+xml",
    )
    val BINARY_TYPES = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
    )

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return TEXT_TYPES[ext] ?: BINARY_TYPES[ext] ?: "application/octet-stream"
    }

    /** Bezpečný název souboru: bez cest, s povolenou příponou (jinak se doplní [defaultExt]). */
    fun safeName(name: String, defaultExt: String = "txt"): String {
        var base = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "").trim().trim('.')
        if (base.isBlank()) base = "axiom-soubor"
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext !in TEXT_TYPES && ext !in BINARY_TYPES) base = "$base.$defaultExt"
        if (base.length > 100) base = base.take(90) + "." + base.substringAfterLast('.')
        return base
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        require(files.isNotEmpty()) { "ZIP potřebuje aspoň jeden soubor." }
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            for ((path, bytes) in files) {
                z.putNextEntry(ZipEntry(ApkForge.safePath(path)))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return o.toByteArray()
    }

    /**
     * Jednoduchý, ale platný Word dokument (.docx) z textu s lehkým Markdownem:
     * `# nadpis`, `## podnadpis`, `- odrážka`, `**tučně**`, prázdný řádek = nový odstavec.
     */
    fun docx(title: String, text: String): ByteArray {
        val body = StringBuilder()
        if (title.isNotBlank()) body.append(par(title, style = "Title"))
        for (raw in text.replace("\r", "").split('\n')) {
            val l = raw.trimEnd()
            when {
                l.isBlank() -> continue
                l.startsWith("### ") -> body.append(par(l.drop(4), style = "Heading3"))
                l.startsWith("## ") -> body.append(par(l.drop(3), style = "Heading2"))
                l.startsWith("# ") -> body.append(par(l.drop(2), style = "Heading1"))
                Regex("^\\s*[-*•]\\s+").containsMatchIn(l) -> body.append(par("• " + l.replace(Regex("^\\s*[-*•]\\s+"), ""), indent = true))
                else -> body.append(par(l))
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="cs-CZ"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="44"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
</w:styles>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            for ((n, c) in listOf("[Content_Types].xml" to contentTypes, "_rels/.rels" to rels, "word/document.xml" to document,
                "word/styles.xml" to styles, "word/_rels/document.xml.rels" to docRels)) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
        return o.toByteArray()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

    private fun par(text: String, style: String? = null, indent: Boolean = false): String {
        val ppr = buildString {
            if (style != null || indent) {
                append("<w:pPr>")
                if (style != null) append("<w:pStyle w:val=\"$style\"/>")
                if (indent) append("<w:ind w:left=\"360\"/>")
                append("</w:pPr>")
            }
        }
        // **tučně** → tučný run
        val runs = text.split("**").mapIndexed { i, part ->
            if (part.isEmpty()) "" else "<w:r>${if (i % 2 == 1) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">${xml(part)}</w:t></w:r>"
        }.joinToString("")
        return "<w:p>$ppr$runs</w:p>"
    }
}
