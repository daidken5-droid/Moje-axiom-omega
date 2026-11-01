package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Orchestrator
import cz.axiom.omega.core.randy.Randy
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.randy.RandyPass
import cz.axiom.omega.core.self.MemoryExtractor
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.self.MemoryRanker
import cz.axiom.omega.core.self.Reflection
import cz.axiom.omega.core.self.SelfState
import cz.axiom.omega.core.self.SystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandySelfTest {

    @Test fun ev() = assertEquals(RandyLevel.L3_STRATEGIC_EV, Randy.detect("Sport: kurz 1,85 a odhad 60 %, kolik vsadit podle Kelly?").level)
    @Test fun forensic() = assertEquals(RandyLevel.L5_FORENSIC, Randy.detect("Udělej forenzní rekonstrukci časové osy z logu").level)
    @Test fun redTeam() = assertEquals(RandyLevel.L4_NEMESIS_REDTEAM, Randy.detect("Najdi slabiny a zranitelnosti v mém plánu, red team").level)
    @Test fun deep() = assertEquals(RandyLevel.L2_DEEP, Randy.detect("Proč a jak funguje tepelné čerpadlo? Vysvětli princip.").level)
    @Test fun expert() = assertEquals(RandyLevel.L1_EXPERT, Randy.detect("Jaké jsou parametry a norma ČSN pro schodiště?").level)
    @Test fun direct() = assertEquals(RandyLevel.L0_DIRECT, Randy.detect("Hlavní město Francie?").level)

    @Test fun percentDoesNotTriggerDeep() {
        // "procenta" začíná na "proc" – Titanium by to chybně označil jako L2
        assertEquals(RandyLevel.L0_DIRECT, Randy.detect("Kolik procent je 5 z 20").level)
    }

    @Test fun explicitOverride() {
        val d = Randy.detect("L4: zhodnoť můj podnikatelský plán")
        assertEquals(RandyLevel.L4_NEMESIS_REDTEAM, d.level)
        assertTrue(d.explicit)
        assertEquals("zhodnoť můj podnikatelský plán", d.cleanedPrompt)
        assertEquals(RandyLevel.L6_OMNISCIENT, Randy.detect("/l6 budoucnost energetiky").level)
    }

    @Test fun directFilter() {
        assertEquals("Paříž.", Randy.direct("Jistě! Paříž."))
        assertEquals("Výsledek je 4.", Randy.direct("Skvělá otázka! Výsledek je 4."))
    }

    @Test fun passesPlan() {
        assertEquals(listOf(RandyPass.ANSWER), Orchestrator.planPasses(RandyLevel.L4_NEMESIS_REDTEAM, 1))
        assertEquals(listOf(RandyPass.DRAFT, RandyPass.SYNTHESIS), Orchestrator.planPasses(RandyLevel.L4_NEMESIS_REDTEAM, 2))
        assertEquals(3, Orchestrator.planPasses(RandyLevel.L5_FORENSIC, 3).size)
        assertEquals(listOf(RandyPass.ANSWER), Orchestrator.planPasses(RandyLevel.L0_DIRECT, 3))
    }

    @Test fun memoryExtract() {
        val ops = MemoryExtractor.extract("Ahoj, jmenuji se Daniel. ")
        val r = ops.single() as MemoryExtractor.Op.Remember
        assertEquals(MemoryKind.PROFILE, r.item.kind)
        assertTrue(r.item.text.contains("Daniel"))

        val f = MemoryExtractor.extract("Pamatuj si, že pracuji s mikrocementem Topciment.").single() as MemoryExtractor.Op.Remember
        assertEquals(MemoryKind.FACT, f.item.kind)
        assertTrue(f.item.text.startsWith("Pracuji s mikrocementem"))

        val g = MemoryExtractor.extract("Zapomeň na mikrocement.").single()
        assertTrue(g is MemoryExtractor.Op.Forget)
        assertTrue(MemoryExtractor.extract("Jaké je počasí?").isEmpty())
    }

    @Test fun memoryRanking() {
        val now = 1_000_000_000_000L
        val items = listOf(
            MemoryItem(1, MemoryKind.FACT, "Uživatel dělá mikrocementové podlahy v Brně.", createdAt = now, lastUsedAt = now),
            MemoryItem(2, MemoryKind.FACT, "Oblíbená barva je modrá.", createdAt = now, lastUsedAt = now),
            MemoryItem(3, MemoryKind.PROFILE, "Uživatel se jmenuje Daniel.", createdAt = now, lastUsedAt = now),
        )
        val r = MemoryRanker.rank("spočítej materiál na mikrocementové podlahy", items, 5, now)
        assertEquals(1L, r.first().id)
        assertTrue(r.any { it.id == 3L })            // profil je relevantní vždy
        assertFalse(r.any { it.id == 2L })
    }

    @Test fun reflectionParse() {
        val p = Reflection.parse("Odpověď je 42.\n\n⟨SEBEREFLEXE⟩\njistota: 85 %\npoučení: U výpočtů vždy uváděj jednotky.")
        assertEquals("Odpověď je 42.", p.answer)
        assertEquals(85, p.confidence)
        assertEquals("U výpočtů vždy uváděj jednotky.", p.lesson)

        val none = Reflection.parse("Jen odpověď.")
        assertNull(none.confidence)
        assertEquals("Jen odpověď.", none.answer)

        val zadne = Reflection.parse("A\n⟨SEBEREFLEXE⟩\njistota: 120\npoučení: žádné")
        assertEquals(100, zadne.confidence)
        assertNull(zadne.lesson)
    }

    @Test fun systemPromptContainsSelfModel() {
        val sp = SystemPrompt.build(
            RandyLevel.L3_STRATEGIC_EV,
            SelfState("OMEGA Core", "lokální", online = false, brainName = "Qwen3.5 4B", memoryCount = 7, batteryPct = 64),
            listOf(MemoryItem(1, MemoryKind.PROFILE, "Uživatel se jmenuje Daniel.")),
            compact = true,
        )
        assertTrue(sp.contains("AXIOM Ω"))
        assertTrue(sp.contains("SEBEMODEL"))
        assertTrue(sp.contains("Baterie 64 %"))
        assertTrue(sp.contains("RANDY L3"))
        assertTrue(sp.contains("Daniel"))
    }
}
