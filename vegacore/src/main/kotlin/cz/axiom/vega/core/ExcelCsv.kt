package cz.axiom.vega.core

import cz.axiom.vega.base.Text

/**
 * Export tabulky pro Excel (refaktoring 9, noc 27./28. 11. – vyjmuto z Ops.kt): oddělovač podle pravidel z paměti
 * a čísla pro český Excel. Čisté funkce, bez stavu; Ops jen dodá pravidla a uloží soubor.
 */
object ExcelCsv {
    /** Oddělovač z jednoho pravidla („vždy používej čárku“, „tady středník“, „tabulátor“), nebo null když o oddělovači nemluví. */
    fun delimiterOf(rule: String): Char? {
        val x = Text.fold(rule).lowercase()
        if (!Regex("oddelova|csv|export|stredni|carku|carka|tabulator").containsMatchIn(x) || Regex("nepouziv|\\bne\\s+(strednik|carku)|desetinn").containsMatchIn(x)) return null
        return when { Regex("strednik").containsMatchIn(x) -> ';'; Regex("tabulator").containsMatchIn(x) -> '\t'; Regex("\\bcark[uay]\\b|\\bcarkou\\b").containsMatchIn(x) -> ','; else -> null }
    }

    /** Pravidlo projektu má přednost před globálním; bez pravidla středník (český Excel). */
    fun delimiter(projectRules: List<String>, globalRules: List<String>): Char =
        projectRules.firstNotNullOfOrNull { delimiterOf(it) } ?: globalRules.firstNotNullOfOrNull { delimiterOf(it) } ?: ';'

    /** Tabulka → CSV (UTF-8 s BOM). Se středníkem = český Excel: desetinná ČÁRKA (jinak z „1.05“ udělá datum a „1234.50“ nejde sečíst). */
    fun bytes(v: Value.Table, d: Char): ByteArray {
        val t = if (d == ';') v.copy(rows = v.rows.map { r -> r.map { c -> when {
            Regex("^-?\\d+\\.\\d+$").matches(c) -> c.replace('.', ',')
            // zobrazené částky „−1 250,50“ (minus U+2212, nezlomitelné mezery) → „-1250,50“, aby je Excel sečetl
            Regex("^[−-]?\\d{1,3}(?:[   ]\\d{3})*,\\d+$").matches(c) -> c.replace('−', '-').replace(Regex("[   ]"), "")
            else -> c } } }) else v
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + Csv.write(t, d, excel = true).toByteArray()
    }
}
