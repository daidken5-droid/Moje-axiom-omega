package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Noc 27./28. 11.: Excel píše na první řádek CSV „sep=;“ – dřív se brala jako hlavička a skutečná hlavička skončila jako „1 nepřečtený řádek“. */
class CsvSepDirectiveTest {
    private val base = "Datum;Částka;Popis\n01.11.2026;-250,00;Albert\n02.11.2026;1 000,00;Výplata\n03.11.2026;xx;Chyba\n"

    @Test fun sepLineIsNotARowAndLineNumbersStay() {
        for (pre in listOf("sep=;\n", "﻿sep=;\n", "SEP=;\r\n")) {
            val t = pre + base
            val r = Statement.process(t, Statement.detect(t).spec!!)
            assertEquals(2, r.ok, pre)
            // vadný je jen skutečně vadný řádek „xx“ – 5. řádek souboru (sep, hlavička, 3 pohyby)
            assertEquals(listOf(5), r.failed.map { it.first }, "$pre ${r.failed}")
        }
        val plain = Statement.process(base, Statement.detect(base).spec!!)
        assertEquals(listOf(4), plain.failed.map { it.first })
    }

    /** Řádek „sep=;“ není „úvodní řádek banky“ – varování o přeskočených řádcích by laika jen mátlo. */
    @Test fun noSkippedLinesWarningForSepDirective() {
        val w = Statement.detect("sep=;\n" + base).warnings
        kotlin.test.assertTrue(w.none { it.startsWith("Přeskočeno") }, w.toString())
        // skutečné úvodní řádky banky se dál hlásí
        val w2 = Statement.detect("Účet: 123/0100\nObdobí: listopad\n" + base).warnings
        kotlin.test.assertTrue(w2.any { it.startsWith("Přeskočeno 2") }, w2.toString())
    }
}
