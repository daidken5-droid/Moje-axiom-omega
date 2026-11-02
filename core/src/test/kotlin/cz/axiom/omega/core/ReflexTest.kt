package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReflexTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 14, 5, 0, 0, ZoneId.of("Europe/Prague"))
    private val today = LocalDate.of(2026, 11, 2)
    private fun ctx(mem: List<MemoryItem> = emptyList(), cache: List<Reflex.Entry> = emptyList(), th: Int = 85) =
        Reflex.Ctx(Instant.Ctx(now = now), memories = mem, cache = cache, threshold = th)

    @Test fun calendar() {
        assertTrue(Reflex.answer("Kolik dní do Vánoc?", ctx())!!.text.contains("**52 dní**"))
        assertTrue(Reflex.answer("kolik zbývá do 1. 1. 2027", ctx())!!.text.contains("**60 dní**"))
        assertTrue(Reflex.answer("Jaký den bude 24. 12. 2026?", ctx())!!.text.contains("**čtvrtek**"))
        assertTrue(Reflex.answer("Jaké datum bude za 2 týdny?", ctx())!!.text.contains("16. listopadu 2026"))
        assertTrue(Reflex.answer("Kolikátý je týden?", ctx())!!.text.contains("**45. týden**"))
    }

    @Test fun recallFromMemory() {
        val mem = listOf(MemoryItem(1, MemoryKind.FACT, "Uživatelův pes se jmenuje Rex"), MemoryItem(2, MemoryKind.PROFILE, "Uživatel bydlí v Brně"))
        val a = Reflex.answer("Jak se jmenuje můj pes?", ctx(mem))!!
        assertTrue(a.text, a.text.contains("Tvůj pes se jmenuje Rex") && a.confidence >= 85)
        assertNull(Reflex.answer("Jak se jmenuje moje kočka?", ctx(mem)))
    }

    @Test fun cacheOnlyVerifiedAndTimeless() {
        val t = System.currentTimeMillis()
        val cache = listOf(
            Reflex.Entry("Jak funguje tepelné čerpadlo?", "Odebírá teplo z okolí…", 70, good = true, at = t),
            Reflex.Entry("Jaký je dnes kurz eura?", "25 Kč", 95, good = true, at = t),
            Reflex.Entry("Co je fotosyntéza?", "Proces v rostlinách…", 60, good = false, at = t),
        )
        assertEquals("Odebírá teplo z okolí…", Reflex.answer("Jak funguje tepelné čerpadlo", ctx(cache = cache))!!.text)
        assertNull(Reflex.answer("Jaký je dnes kurz eura?", ctx(cache = cache)))     // mění se v čase
        assertNull(Reflex.answer("Co je fotosyntéza?", ctx(cache = cache)))          // neověřená, nízká jistota
        assertNull(Reflex.answer("/chat Jak funguje tepelné čerpadlo?", ctx(cache = cache)))
    }

    @Test fun thresholdKeepsBrainForUncertain() {
        assertNull(Reflex.answer("Proč je obloha modrá?", ctx()))
        assertTrue(Reflex.answer("Kolik je 12*7?", ctx())!!.confidence == 99)
    }
}
