package cz.axiom.omega.core

import cz.axiom.omega.core.device.Governor
import cz.axiom.omega.core.device.Governor.Input
import cz.axiom.omega.core.device.Power
import cz.axiom.omega.core.randy.RandyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorTest {
    private fun d(p: String, l: RandyLevel = RandyLevel.L0_DIRECT, user: Power? = null, bat: Int? = 80, thermal: Int = 0, tps: Double? = null) =
        Governor.decide(Input(p, l, user, batteryPct = bat, charging = false, thermal = thermal, thermalCaution = 2, writeTps = tps))

    @Test fun autoMatchesDemand() {
        val easy = d("Kolik stojí chleba?")
        assertEquals(Power.BASIC, easy.power); assertTrue(easy.maxTokens <= 320); assertFalse(easy.allowPipeline)
        val mid = d("Vysvětli, proč se v zimě rosí okna a jak tomu zabránit", RandyLevel.L2_DEEP)
        assertEquals(Power.ADVANCED, mid.power)
        val hard = d("Analyzuj rizika a navrhni strategii pro rozšíření firmy do Německa, pro a proti", RandyLevel.L4_NEMESIS_REDTEAM)
        assertEquals(Power.SHERLOCK, hard.power); assertTrue(hard.allowPipeline && hard.thinking)
    }

    @Test fun userChoiceIsCeilingButSherlockIsHonored() {
        assertEquals(Power.ADVANCED, d("Analyzuj rizika a navrhni strategii", RandyLevel.L4_NEMESIS_REDTEAM, user = Power.ADVANCED).power)
        assertEquals(Power.BASIC, d("Kolik je hodin v Praze?", user = Power.ADVANCED).power)
        assertEquals(Power.SHERLOCK, d("Vysvětli, proč padla Římská říše", RandyLevel.L2_DEEP, user = Power.SHERLOCK).power)
    }

    @Test fun deviceSafeguardsWin() {
        val low = d("Analyzuj rizika a navrhni strategii", RandyLevel.L4_NEMESIS_REDTEAM, user = Power.SHERLOCK, bat = 8)
        assertEquals(Power.BASIC, low.power); assertTrue(low.limited); assertTrue(low.reasons.any { "baterie" in it })
        val hot = d("Analyzuj rizika a navrhni strategii", RandyLevel.L4_NEMESIS_REDTEAM, thermal = 2)
        assertEquals(Power.ADVANCED, hot.power); assertTrue(hot.threads < 4); assertFalse(hot.thinking)
        assertEquals(Power.BASIC, d("Analyzuj rizika", RandyLevel.L4_NEMESIS_REDTEAM, thermal = 4).power)
        val slow = d("Vysvětli, proč se rosí okna", RandyLevel.L2_DEEP, tps = 3.0)
        assertTrue(slow.maxTokens < 560); assertFalse(slow.allowPipeline)
    }
}
