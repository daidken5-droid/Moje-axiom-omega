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
            println("PROBE|$q|$r|${a?.replace("\n", " ⏎ ")?.take(150) ?: "-"}")
        }
    }
}
