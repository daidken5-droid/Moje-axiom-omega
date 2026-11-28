package cz.axiom.vega

import cz.axiom.vega.core.Value
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Strážný test (refaktoring 9, noc 27./28. 11.) PŘED vyjmutím exportu pro Excel z Ops.kt: oddělovač podle pravidel a čísla pro český Excel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsCsvGuardTest {
    private val tb = Value.Table(listOf("Datum", "Částka", "Popis"), listOf(listOf("2026-11-01", "-1250.50", "Nákup; potraviny"), listOf("2026-11-02", "−1 250,50", "Řádek \"s uvozovkami\""), listOf("2026-11-03", "abc 1.5", "x")))
    private fun csv(o: Ops) = String(o.tableCsv(tb), Charsets.UTF_8)

    @Test fun defaultSemicolonWithDecimalComma() {
        val h = OpsHarness()
        assertEquals("﻿Datum;Částka;Popis\r\n2026-11-01;-1250,50;\"Nákup; potraviny\"\r\n2026-11-02;-1250,50;\"Řádek \"\"s uvozovkami\"\"\"\r\n2026-11-03;abc 1.5;x\r\n".replace("\r\n", "\n"), csv(h.o).replace("\r\n", "\n"))
    }

    @Test fun globalRuleCommaKeepsDots() {
        val h = OpsHarness(); h.o.memory.remember("vždy používej čárku jako oddělovač v CSV", "global")
        assertEquals(',', h.o.csvDelimiter())
        assertEquals("2026-11-01,-1250.50,Nákup; potraviny", csv(h.o).replace("\r\n", "\n").lines()[1])
    }

    @Test fun tabRuleAndNegatedRuleIgnored() {
        val h = OpsHarness(); h.o.memory.remember("nepoužívej středník v exportu", "global")
        assertEquals(';', h.o.csvDelimiter())
        h.o.memory.remember("pro export používej tabulátor", "global")
        assertEquals('\t', h.o.csvDelimiter())
    }
}
