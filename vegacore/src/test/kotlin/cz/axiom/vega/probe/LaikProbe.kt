package cz.axiom.vega.probe

import cz.axiom.vega.calc.Quick
import cz.axiom.vega.core.Router
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Průzkum (ne regresní test): spustí se jen s -Dprobe=1 a vypíše směrování + odpověď kalkulačky. */
class LaikProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val now = java.time.ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
        val ctx = Router.Ctx(false, null, false, false, null)
        java.io.File(System.getProperty("probeFile")).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { q ->
            val r = runCatching { Router.route(q, ctx) }.fold({ it.javaClass.simpleName + (if (it is Router.Intent.Tool) ":" + it.id else if (it is Router.Intent.Talk) "" else "") }, { "PÁD " + it })
            val a = runCatching { Quick.answer(q, now) }.getOrElse { "PÁD $it" }
            val E = cz.axiom.vega.core.Econ
            val e = runCatching { E.simpleProduct(q) ?: (if (E.isUnitQuestion(q)) E.unitAnswer(E.parseUnit(q), E.claim(q))?.text ?: "unit?neúplné:" + E.parseUnit(q) else null)
                ?: (if (E.isTariffQuestion(q)) E.parseTariff(q)?.let { E.tariffAnswer(it) } ?: "tarif?nerozpoznán" else null) }.getOrElse { "PÁD $it" }
            val ctxs = listOf("příl" to Router.Ctx(true, "vypis.csv", false, false, null), "proj" to Router.Ctx(false, null, true, false, null), "výsl" to Router.Ctx(false, null, false, true, null))
            val rc = ctxs.joinToString(" ") { (n, c) -> n + "=" + runCatching { Router.route(q, c).let { it.javaClass.simpleName + (if (it is Router.Intent.Code) ":" + it.kind else "") } }.getOrElse { "PÁD" } }
            println("PROBE|$q|$r|${a?.replace("\n", " ⏎ ")?.take(110) ?: "-"}|${e?.replace("\n", " ")?.take(110) ?: "-"}|$rc")
        }
    }
}
