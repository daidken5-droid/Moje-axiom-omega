package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Režie modulů bez mozku u otázek, které stejně jdou mozku (nejhorší případ) – musí být zanedbatelná. */
class OverheadTest {
    @Test fun worstCase() {
        val brain = listOf(
            "porovnej výhody a nevýhody tepelného čerpadla a plynového kotle pro rodinný dům v horách s ohledem na cenu energie v příštích deseti letech a navrhni nejlepší řešení",
            "napiš mi podrobný plán tréninku na maraton pro začátečníka na 16 týdnů včetně stravy a regenerace",
            "vysvětli mi teorii relativity", "jak napsat životopis", "kdo je nejlepší fotbalista", "doporuč mi knihu na dovolenou",
            "jaký je rozdíl mezi akciemi a dluhopisy a co je pro mě v 35 letech lepší", "přelož mi tenhle text do angličtiny: Dobrý den, posílám fakturu",
        )
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        repeat(3) { brain.forEach { Pavucina.clear(); Reflex.answer(it, c) } } // zahřátí JIT
        val t0 = System.nanoTime(); val n = 20
        repeat(n) { brain.forEach { Pavucina.clear(); Reflex.answer(it, c); Earl.decide(Pavucina.of(it), Earl.Ctx()) } }
        val ms = (System.nanoTime() - t0) / 1e6 / (n * brain.size)
        println("OVERHEAD %.2f ms/otázka (reflex + varianty + E-ARL, bez paměti)".format(ms))
        org.junit.Assert.assertTrue("režie $ms ms", ms < 2.0)
    }
}
