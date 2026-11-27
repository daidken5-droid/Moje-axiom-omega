package cz.axiom.vega.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 27. 11.: titulek z cizího RSS nesmí skrytými znaky (U+202E obrátí text, nulová šířka) změnit, jak vypadá. */
class NewsHiddenTest {
    @Test fun cleanRemovesHiddenAndBidi() {
        for (c in listOf('‮', '​', '⁦', '﻿', '؜', ' ')) {
            val t = News.clean("Vláda schválila${c} rozpočet")
            assertFalse("U+%04X zůstal: $t".format(c.code), t.contains(c))
        }
        assertEquals("Vláda schválila rozpočet", News.clean("Vláda‮ schválila ​rozpočet"))
    }
}
