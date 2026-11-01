package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AgentRole
import cz.axiom.omega.core.agent.Router
import cz.axiom.omega.core.agent.Variant
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.randy.Supervisor
import cz.axiom.omega.core.randy.Supervisor.Authority
import cz.axiom.omega.core.randy.Supervisor.Device
import cz.axiom.omega.core.randy.Supervisor.Request
import cz.axiom.omega.core.randy.Supervisor.Watchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorTest {
    private val ok = Device(batteryPct = 80, charging = false, thermal = 0, freeRamMb = 3000, online = true)
    private fun plan(t: String, d: Device = ok, a: Authority = Authority.GOVERN, v: Variant = Variant.AUTO) = Supervisor.plan(Request(t, v), d, emptyMap(), a)

    @Test fun randyActivatesSubfunctions() {
        assertEquals(Variant.STUDIO, plan("Vytvoř mi aplikaci APK na poznámky").variant)
        assertEquals(Variant.OFFICE, plan("Připrav výpověď z nájmu jako Word").variant)
        assertEquals(Variant.VERITAS, plan("Je pravda, že chemtrails ovládají lidi?").variant)
        assertEquals(Variant.QUANT, plan("Kurz 2,1 a odhad 55 %, bankroll 10000 – vsadit?").variant)
        assertEquals(Variant.MUSE, plan("Napiš báseň o podzimu v Praze").variant)
        assertEquals(Variant.THINK, plan("Proč je inflace v ČR vyšší než v eurozóně a co s tím?").variant)
        assertEquals(Variant.FLASH, plan("Hlavní město Austrálie").variant)
        assertEquals(Variant.AUTO, plan("Jak funguje tepelné čerpadlo?").variant)
        assertEquals(Router.Mode.AGENT, plan("Vytvoř mi aplikaci APK na poznámky").mode)
        assertEquals(AgentRole.APPS, plan("Vytvoř mi aplikaci APK na poznámky").role)
    }

    @Test fun explicitChoiceIsRespected() {
        val p = plan("Napiš báseň", v = Variant.QUANT)
        assertEquals(Variant.QUANT, p.variant)
        assertEquals(RandyLevel.L3_STRATEGIC_EV, p.level)
        assertFalse(p.chosenByRandy)
    }

    @Test fun governsResources() {
        val low = Device(batteryPct = 12, charging = false, online = true)
        val p = plan("Proč je nebe modré a jak to souvisí s Rayleighovým rozptylem?", low)
        assertEquals(1, p.passes)
        assertEquals(false, p.thinking)
        assertTrue(p.decisions.any { it.contains("úsporný") })
        // ruční volba: řídící RANDY jen upozorní, plný ušetří i tak
        assertEquals(Variant.THINK.passes, plan("x", low, Authority.GOVERN, Variant.THINK).passes)
        assertEquals(1, plan("x", low, Authority.FULL, Variant.THINK).passes)
        // offline: webová rešerše → Think s offline knihovnou
        assertEquals(Variant.THINK, Supervisor.plan(Request("Udělej rešerši a zmapuj trh s tepelnými čerpadly", toolsOnline = false), ok.copy(online = false), emptyMap(), Authority.GOVERN).variant)
    }

    @Test fun advisoryChangesNothing() {
        val p = plan("Vytvoř mi aplikaci APK na poznámky", a = Authority.ADVISORY)
        assertEquals(Variant.AUTO, p.variant)
        assertFalse(p.mayEscalate)
    }

    @Test fun escalatesOnlyWithFullAuthority() {
        val full = plan("Hlavní město Austrálie", a = Authority.FULL)
        assertEquals(Variant.THINK, Supervisor.escalation(full, 30, 400))
        assertNull(Supervisor.escalation(full, 80, 400))
        assertNull(Supervisor.escalation(plan("Hlavní město Austrálie"), 30, 400))
    }

    @Test fun watchdogStopsLoopsAndFailingTools() {
        var t = 0L
        val w = Watchdog(60_000, Authority.GOVERN) { t }
        assertEquals(Watchdog.Verdict.Run, w.before("web_search", "{\"q\":\"x\"}"))
        w.after("web_search", "{\"q\":\"x\"}", "výsledek")
        assertTrue(w.before("web_search", "{\"q\": \"X\"}") is Watchdog.Verdict.Reuse)
        w.after("fetch_url", "{\"url\":\"a\"}", "CHYBA nástroje: 404")
        w.after("fetch_url", "{\"url\":\"b\"}", "CHYBA nástroje: 404")
        assertTrue(w.before("fetch_url", "{\"url\":\"c\"}") is Watchdog.Verdict.Block)
        assertFalse(w.overBudget())
        t = 61_000
        assertTrue(w.overBudget())
        assertEquals(3, w.interventions.size)
    }

    @Test fun learnsFromOutcomes() {
        var st = Supervisor.update(null, 10_000, 80, false)
        st = Supervisor.update(st, 30_000, 60, true)
        assertEquals(2, st.runs); assertEquals(20_000, st.avgMillis); assertEquals(1, st.escalations)
        repeat(5) { st = Supervisor.rated(st, false) }
        val stats = mapOf(Variant.FLASH to st)
        // Flash s nízkým hodnocením → RANDY ho přestane volit
        assertEquals(Variant.AUTO, Supervisor.plan(Request("Hlavní město Austrálie"), ok, stats, Authority.GOVERN).variant)
        assertTrue(st.efficiency!! in 0..100)
    }
}
