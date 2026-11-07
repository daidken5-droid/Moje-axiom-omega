package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import org.junit.Test
import kotlin.test.assertTrue

/** Výkon paměti odpovědí při plném limitu (2 000 záznamů) – reflex ji prochází u KAŽDÉHO dotazu. */
class AnswerMemoryPerfTest {
    @Test fun fullAnswerMemoryStaysFast() {
        val now = System.currentTimeMillis()
        val topics = listOf("vaření", "zahrada", "auto", "počítač", "zdraví", "cestování", "peníze", "škola", "sport", "hudba")
        val cache = (1..2000).map { i -> Reflex.Entry("jak se dělá věc číslo $i v oboru ${topics[i % 10]} a proč", "Odpověď $i.", 90, i % 7 == 0, now - i * 60_000L) }
        val c = Reflex.Ctx(Instant.Ctx(), cache = cache)
        val qs = listOf("jak se dělá věc číslo 77 v oboru auto a proč", "napiš mi básničku o podzimu", "kolik je 7 krát 8", "jak funguje tepelné čerpadlo v zimě", "co je inflace")
        repeat(30) { qs.forEach { Reflex.answer(it, c) } }
        val lat = mutableListOf<Long>()
        repeat(40) { qs.forEach { q -> val t = System.nanoTime(); Reflex.answer(q, c); lat += (System.nanoTime() - t) / 1000 } }
        lat.sort()
        val p50 = lat[lat.size / 2]; val p95 = lat[lat.size * 95 / 100]
        println("PERF paměť odpovědí 2000 záznamů: p50 $p50 µs · p95 $p95 µs")
        assertTrue(p95 < 20_000, "p95 $p95 µs (před optimalizací 139 ms)")
    }
}
