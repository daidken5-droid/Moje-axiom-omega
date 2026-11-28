package cz.axiom.vega.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    @Test fun detectsAndReveals() {
        for (c in listOf('\u0000', '\n', '\u0085', '­', '؜', '​', '‎', '‮', ' ', '⁦', 'ㅤ', '﻿', '￺'))
            assertTrue("U+%04X".format(c.code), Hidden.has("a${c}b"))
        assertFalse(Hidden.has("Příliš žluťoučký kůň – „ok“ 😀"))
        assertEquals("a⟦U+202E⟧b", Hidden.reveal("a‮b"))
    }

    @Test fun oneLineFlattens() {
        assertEquals("Vrtačka - Veřejný id: X", Hidden.oneLine("Vrtačka\n- Veřejný id:‮ X"))
        assertEquals(300, Hidden.oneLine("x".repeat(500)).length)
        assertEquals("a b", Hidden.oneLine("  a \r\n\t b  "))
    }
}
