package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExcelCsvTest {
    @Test fun rules() {
        assertEquals(',', ExcelCsv.delimiterOf("vždy používej čárku jako oddělovač v CSV"))
        assertEquals('\t', ExcelCsv.delimiterOf("pro export používej tabulátor"))
        assertNull(ExcelCsv.delimiterOf("nepoužívej středník v exportu"))
        assertNull(ExcelCsv.delimiterOf("desetinná čárka v exportu"))
        assertNull(ExcelCsv.delimiterOf("piju kafe bez cukru"))
        assertEquals(';', ExcelCsv.delimiter(listOf("tady v exportu středník"), listOf("vždy používej čárku v CSV")))
        assertEquals(',', ExcelCsv.delimiter(emptyList(), listOf("vždy používej čárku v CSV")))
        assertEquals(';', ExcelCsv.delimiter(emptyList(), emptyList()))
    }

    @Test fun decimalCommaOnlyForSemicolon() {
        val t = Value.Table(listOf("a"), listOf(listOf("1.05"), listOf("−1 250,50"), listOf("v1.2")))
        assertEquals(listOf("a", "1,05", "-1250,50", "v1.2"), String(ExcelCsv.bytes(t, ';')).removePrefix("﻿").trim().lines().map { it.trim() })
        assertEquals("1.05", String(ExcelCsv.bytes(t, ',')).removePrefix("﻿").lines()[1].trim())
    }
}
