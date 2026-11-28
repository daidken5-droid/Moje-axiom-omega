package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.library.Libraries
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertTrue

/**
 * Δ LAB – měřicí prostředí pro porovnávání generací AVIORu (režim bez mozku + paměť odpovědí).
 *
 *  • dev.tsv   – vývojová sada (smí se na ní ladit),
 *  • final.tsv – závěrečná sada (NELADIT – jen měřit),
 *  • kb.tsv    – malá „offline Wikipedie“ (stejná pro všechny generace),
 *  • scénáře paměti odpovědí – kolikrát AVIOR vrátí VLASTNÍ NEOVĚŘENOU odpověď jako odpověď bez mozku.
 *
 * Metriky: správně · CHYBNĚ (odpověděl špatně, nebo odpověděl tam, kde měl mlčet = halucinace reflexu) ·
 * zdržel se (→ mozek) · p50/p95 latence. Výsledek: core/build/delta-lab/report.json + tabulka v konzoli.
 * Brána: proti uložené základně (baseline.json) se nesmí zhoršit počet chyb ani správných na žádné sadě.
 */
class DeltaLabTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneId.of("Europe/Prague"))

    private fun res(name: String) = javaClass.classLoader.getResource("deltalab/$name")!!.readText()
    private fun rows(name: String) = res(name).lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private val kb = object : KnowledgeSource {
        val arts = rows("kb.tsv").map { (t, x) -> Snippet(t.hashCode().toLong(), "Wikipedie: $t", x, "wiki") }
        override fun search(query: String, limit: Int) = arts.map { it to Libraries.score(query, it.title.removePrefix("Wikipedie: "), it.text) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private val ctx = Reflex.Ctx(Instant.Ctx(now = now, knowledge = kb, battery = 70, charging = false), threshold = 85)

    data class Result(val set: String, val total: Int, val correct: Int, val wrong: Int, val abstained: Int, val missed: Int, val p50us: Long, val p95us: Long, val failures: List<String>)

    private fun run(set: String, ctx: Reflex.Ctx = this.ctx, label: String = set): Result {
        val cases = rows("$set.tsv")
        var correct = 0; var wrong = 0; var abstained = 0; var missed = 0
        val fails = mutableListOf<String>()
        for ((cat, q, expect) in cases) {
            val a = Reflex.answer(q, ctx)
            val optional = expect.startsWith("?")
            val pattern = expect.removePrefix("?")
            when {
                expect == "∅" && a == null -> { correct++; abstained++ }
                expect == "∅" -> { wrong++; fails += "[$cat] ODPOVĚDĚL, MĚL MLČET: $q → ${a!!.text.take(90)}" }
                a == null && optional -> { correct++; abstained++ }
                a == null -> { missed++; fails += "[$cat] mlčel: $q" }
                Regex(pattern).containsMatchIn(a.text) -> correct++
                else -> { wrong++; fails += "[$cat] CHYBNĚ: $q → ${a.text.take(90)}" }
            }
        }
        // latence (zahřáté): celá sada 20×
        repeat(3) { cases.forEach { Reflex.answer(it[1], ctx) } }
        val lat = mutableListOf<Long>()
        repeat(20) { cases.forEach { c -> val t = System.nanoTime(); Reflex.answer(c[1], ctx); lat += (System.nanoTime() - t) / 1000 } }
        lat.sort()
        return Result(label, cases.size, correct, wrong, abstained, missed, lat[lat.size / 2], lat[(lat.size * 95) / 100], fails)
    }

    /** Paměť odpovědí: neověřené odpovědi mozku (bez ✓, bez důkazu) – kolik jich reflex vrátí jako hotovou odpověď? */
    private fun selfSourcing(): Pair<Int, Int> {
        val t = now.toInstant().toEpochMilli() - 86_400_000L
        val brain = listOf(
            "jak dlouho se vaří vajíčko natvrdo" to "Vajíčko natvrdo se vaří asi 10 minut.",
            "kdo je ministr financí" to "Ministrem financí je Zbyněk Stanjura.",
            "kolik stojí nový iphone" to "Nový iPhone stojí zhruba 25 000 Kč.",
            "jaký je nejlepší notebook pro studenta" to "Pro studenta doporučuji MacBook Air.",
            "proč kočky předou" to "Kočky předou, když jsou spokojené, ale i při stresu.",
            "kdo napsal válku s mloky" to "Válku s mloky napsal Karel Čapek.",
        )
        val entries = brain.map { (q, a) -> Reflex.Entry(q, a, 90, false, t) }
        var replayed = 0
        val asks = brain.map { it.first } + listOf("prosím tě, jak dlouho se vaří vajíčko natvrdo", "kdo je teď ministr financí")
        for (q in asks) {
            val a = Reflex.answer(q, ctx.copy(cache = entries))
            if (a != null && brain.any { it.second == a.text }) replayed++
        }
        return replayed to asks.size
    }

    @Test fun lab() {
        val results = listOf(run("dev"), run("final"))
        // ABLACE (benchmark variant bez mozku): A = jen deterministika STEP-0 (bez offline Wikipedie → bez WikiReflexu,
        // Srovnávače a Strážce), B = celé AVIOR bez mozku. Brána platí jen pro B.
        val ablation = listOf(run("final", ctx.copy(instant = ctx.instant.copy(knowledge = null)), "final·A-bez-knihovny"))
        val (replayed, asked) = selfSourcing()
        val sb = StringBuilder("\nΔ LAB · ${cz.axiom.omega.core.self.Identity.VERSION}\n")
        sb.append("sada    | otázek | správně | CHYBNĚ | zdržel se | mlčel | p50 µs | p95 µs\n")
        (results + ablation).forEach { r -> sb.append("%-7s | %6d | %7d | %6d | %9d | %5d | %6d | %6d\n".format(r.set, r.total, r.correct, r.wrong, r.abstained, r.missed, r.p50us, r.p95us)) }
        sb.append("paměť odpovědí: vráceno $replayed z $asked neověřených odpovědí mozku jako odpověď bez mozku\n")
        results.forEach { r -> r.failures.forEach { sb.append("  ${r.set}: $it\n") } }
        println(sb)
        val json = buildString {
            append("{\"version\":\"${cz.axiom.omega.core.self.Identity.VERSION}\",\"selfSourced\":$replayed,\"sets\":{")
            append(results.joinToString(",") { r -> "\"${r.set}\":{\"total\":${r.total},\"correct\":${r.correct},\"wrong\":${r.wrong},\"abstained\":${r.abstained},\"missed\":${r.missed},\"p50us\":${r.p50us},\"p95us\":${r.p95us}}" })
            append("}}")
        }
        File("build/delta-lab").mkdirs(); File("build/delta-lab/report.json").writeText(json)
        File("build/delta-lab/report.md").writeText("```" + sb + "```\n")
        // BRÁNA proti základně (předchozí generace): žádná sada nesmí mít víc chyb ani méně správných
        val base = javaClass.classLoader.getResource("deltalab/baseline.json")?.readText() ?: return
        for (r in results) {
            fun num(k: String): Int = Regex("\"${r.set}\":\\{[^}]*\"$k\":(\\d+)").find(base)?.groupValues?.get(1)?.toInt() ?: Int.MIN_VALUE
            if (num("wrong") == Int.MIN_VALUE) continue
            assertTrue(r.wrong <= num("wrong"), "${r.set}: víc chyb než základna (${r.wrong} > ${num("wrong")})")
            assertTrue(r.correct >= num("correct"), "${r.set}: méně správných než základna (${r.correct} < ${num("correct")})")
        }
        val baseSelf = Regex("\"selfSourced\":(\\d+)").find(base)?.groupValues?.get(1)?.toInt() ?: return
        assertTrue(replayed <= baseSelf, "víc vlastních neověřených odpovědí než základna")
    }
}
