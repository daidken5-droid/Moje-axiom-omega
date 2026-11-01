package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Variant
import cz.axiom.omega.core.randy.Supervisor
import cz.axiom.omega.core.self.Cognition
import cz.axiom.omega.core.self.Cognition.Domain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitionTest {
    @Test fun classifiesDomains() {
        assertEquals(Domain.FINANCE, Cognition.domainOf("Kurz 2,1 a bankroll 10 000 – vsadit?"))
        assertEquals(Domain.BUILD, Cognition.domainOf("Kolik mikrocementu na koupelnu?"))
        assertEquals(Domain.TECH, Cognition.domainOf("Napiš Python skript na zálohu"))
        assertEquals(Domain.HISTORY, Cognition.domainOf("Co byla operace StB Akce Kámen?"))
        assertEquals(Domain.GENERAL, Cognition.domainOf("Ahoj"))
    }

    @Test fun learnsCompetenceAndCalibration() {
        var c: Cognition.Competence? = null
        repeat(5) { c = Cognition.learn(c, 90, insight = it == 0) }
        assertEquals(5, c!!.questions); assertEquals(90, c!!.avgConfidence); assertEquals(1, c!!.insights)
        // uživatel hodnotí 2 z 4 jako špatné → skutečnost 50 % vs. deklarovaných 90 % → přehnaná jistota
        c = Cognition.rate(c, true, 90); c = Cognition.rate(c, false, 90); c = Cognition.rate(c, true, 90); c = Cognition.rate(c, false, 90)
        assertEquals(-25, c!!.calibration)
        assertEquals(78, Cognition.calibrated(90, c))
        assertTrue(Cognition.metacognition(Domain.FINANCE, c, 5).contains("PŘEHNANÉ jistotě"))
        assertTrue(c!!.mastery in 1..100)
    }

    @Test fun crystallizesOnlyGoodAnswers() {
        val good = "Tepelné čerpadlo odebírá teplo z okolního vzduchu nebo země a pomocí kompresoru ho převádí na vyšší teplotu. Topný faktor bývá 3–5, tedy z 1 kWh elektřiny vznikne 3–5 kWh tepla."
        val ins = Cognition.distill("Jak funguje tepelné čerpadlo?", good, 85, Domain.ENERGY)
        assertNotNull(ins)
        assertTrue(ins!!.title.startsWith("Vlastní poznatek"))
        assertNull(Cognition.distill("x", good, 40, Domain.ENERGY))
        assertNull(Cognition.distill("x", "Nevím, nejsem si jistý. " + good, 80, Domain.ENERGY))
        assertEquals(Cognition.key("Jak funguje tepelné čerpadlo?"), Cognition.key("tepelné čerpadlo – jak funguje"))
        assertTrue(Cognition.isOpen("Nevím přesně, nelze ověřit.", 70))
        assertFalse(Cognition.isOpen(good, 85))
    }

    @Test fun milestonesAndRandyUsesWeakness() {
        val c = Cognition.learn(null, 70, false)
        assertTrue(Cognition.milestones(1, Domain.LAW, c, 0).any { it.contains("Poprvé") })
        val p = Supervisor.plan(Supervisor.Request("Hlavní město Austrálie", weakDomain = "Zeměpis"), Supervisor.Device(), emptyMap(), Supervisor.Authority.GOVERN)
        assertEquals(Variant.THINK, p.variant)
    }
}

class UpdatesTest {
    @Test fun comparesVersions() {
        val U = cz.axiom.omega.core.update.Updates
        assertTrue(U.newer("avior-v4.1.0", "4.0.0"))
        assertTrue(U.newer("avior-v4.10.0", "4.9.3"))
        assertFalse(U.newer("avior-v4.1.0", "4.1.0"))
        assertFalse(U.newer("avior-v4.0.9", "4.1.0-debug"))
        assertEquals(listOf(4, 1, 0), U.parse("avior-v4.1.0"))
    }
}
