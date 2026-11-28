package cz.axiom.vega.base

/** Typ souboru podle přípony – jediné místo pro „Sdílet“ i „Otevřít“ (dřív dvě kopie, každá s jinými chybějícími typy). */
object Mime {
    private val BY_EXT = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "zip" to "application/zip",
        "txt" to "text/plain", "md" to "text/markdown", "diff" to "text/x-diff", "log" to "text/plain",
        "json" to "application/json", "html" to "text/html",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
    )
    /** „Výpis.XLSX“ → typ XLSX; neznámá přípona → „*&#47;*“ (systém nabídne všechny aplikace). */
    fun of(name: String): String = BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "*/*"
}
