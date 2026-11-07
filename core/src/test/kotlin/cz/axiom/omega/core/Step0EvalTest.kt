package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HYPERFORGE M5 – STEP-0 ADVERSARIAL EVAL přes celou cestu reflexu (Reflex.answer, práh Auto 85).
 * Data: test/resources/step0eval/<split>.jsonl z tools/step0eval/generate.py (nezávislé orákulum v Pythonu).
 * dev + validation běží vždy; HOLDOUT jen s -Dstep0.holdout=true (nesmí se podle něj ladit).
 * Zpráva: build/step0eval/<split>.md
 */
class Step0EvalTest {
    data class Case(val id: String, val split: String, val cat: String, val q: String, val expect: String, val contains: List<String>)

    // minimální čtečka JSON řetězců pro známé schéma (core nemá JSON knihovnu)
    private fun str(line: String, key: String): String {
        val i = line.indexOf("\"$key\": \"").takeIf { it >= 0 } ?: line.indexOf("\"$key\":\"")
        var p = line.indexOf('"', line.indexOf(':', i) + 1) + 1
        val sb = StringBuilder()
        while (line[p] != '"') {
            if (line[p] == '\\') { p++; when (line[p]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(line.substring(p + 1, p + 5).toInt(16).toChar()); p += 4 }; else -> sb.append(line[p]) } }
            else sb.append(line[p]); p++
        }
        return sb.toString()
    }
    private fun list(line: String, key: String): List<String> {
        val s = line.indexOf('[', line.indexOf("\"$key\"")); val e = line.indexOf(']', s)
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line.substring(s, e + 1)).map { it.groupValues[1].replace("\\\"", "\"") }.toList()
    }
    private fun load(split: String): List<Case> =
        (javaClass.classLoader.getResourceAsStream("step0eval/$split.jsonl") ?: error("chybí $split")).bufferedReader().readLines().filter { it.isNotBlank() }
            .map { Case(str(it, "id"), str(it, "split"), str(it, "cat"), str(it, "q"), str(it, "expect"), list(it, "contains")) }

    private fun norm(s: String) = s.replace(" ", "").replace(" ", "")
    private fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return 0.0 to 0.0
        val p = k.toDouble() / n; val d = 1 + z * z / n; val c = p + z * z / (2 * n); val m = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))
        return (c - m) / d to (c + m) / d
    }

    data class Result(val split: String, val n: Int, val answerable: Int, val accepted: Int, val correctAccepted: Int, val wrongConfident: Int,
                      val abstainCases: Int, val abstainCorrect: Int, val p50: Double, val p95: Double, val p99: Double, val failures: List<String>)

    fun run(split: String): Result {
        val cases = load(split)
        // pevný čas → reprodukovatelné kalendářní / kurzové odpovědi
        val ctx = Reflex.Ctx(Instant.Ctx(now = java.time.ZonedDateTime.of(LocalDateTime.of(2026, 11, 3, 12, 0), java.time.ZoneId.of("Europe/Prague"))), today = LocalDate.of(2026, 11, 3), threshold = 85)
        repeat(200) { Reflex.answer(cases[it % cases.size].q, ctx) } // zahřátí JIT
        val lat = ArrayList<Long>(cases.size); val fails = ArrayList<String>()
        var accepted = 0; var correct = 0; var wrong = 0; var abst = 0; var abstOk = 0; var answerable = 0
        for (c in cases) {
            val t = System.nanoTime(); val a = Reflex.answer(c.q, ctx); lat += System.nanoTime() - t
            when (c.expect) {
                "answer" -> {
                    answerable++
                    if (a != null) {
                        accepted++
                        if (c.contains.all { norm(it) in norm(a.text) }) correct++ else { wrong++; fails += "ŠPATNĚ  ${c.id} [${c.cat}] ${c.q} ⇒ ${a.text.take(140).replace('\n', ' ')} | čekal ${c.contains}" }
                    } else fails += "MLČEL   ${c.id} [${c.cat}] ${c.q}"
                }
                "abstain" -> { abst++; if (a == null) abstOk++ else { accepted++; wrong++; fails += "FALEŠNĚ ${c.id} [${c.cat}] ${c.q} ⇒ ${a.kind}: ${a.text.take(140).replace('\n', ' ')}" } }
                "caveat" -> { abst++; if (a == null || c.contains.all { it in a.text }) abstOk++ else { accepted++; wrong++; fails += "BEZ VÝHRADY ${c.id} ${c.q} ⇒ ${a.text.take(120)}" } }
            }
        }
        lat.sort(); fun pct(p: Double) = lat[((lat.size - 1) * p).toInt()] / 1000.0
        val r = Result(split, cases.size, answerable, accepted, correct, wrong, abst, abstOk, pct(0.5), pct(0.95), pct(0.99), fails)
        report(r); return r
    }

    private fun report(r: Result) {
        val prec = if (r.accepted == 0) 0.0 else (r.accepted - r.wrongConfident).toDouble() / r.accepted
        val (lo, hi) = wilson(r.accepted - r.wrongConfident, r.accepted)
        val txt = buildString {
            appendLine("## STEP-0 eval · ${r.split} · ${r.n} případů")
            appendLine("| metrika | hodnota |"); appendLine("|---|---|")
            appendLine("| přijato (reflex odpověděl) | ${r.accepted} |")
            appendLine("| precision accepted | ${"%.2f".format(prec * 100)} % (95% Wilson ${"%.2f".format(lo * 100)}–${"%.2f".format(hi * 100)} %) |")
            appendLine("| coverage (správně / odpověditelné) | ${r.correctAccepted} / ${r.answerable} = ${"%.1f".format(100.0 * r.correctAccepted / r.answerable)} % |")
            appendLine("| wrong confident answers | ${r.wrongConfident} |")
            appendLine("| abstention correctness | ${r.abstainCorrect} / ${r.abstainCases} = ${"%.1f".format(100.0 * r.abstainCorrect / r.abstainCases)} % |")
            appendLine("| latence p50 / p95 / p99 | ${"%.0f".format(r.p50)} / ${"%.0f".format(r.p95)} / ${"%.0f".format(r.p99)} µs (JVM, ne telefon) |")
            val bad = r.failures.filterNot { it.startsWith("MLČEL") }
            appendLine(); appendLine("### Falešné jistoty podle kategorie")
            bad.groupingBy { Regex("\\[([a-z_]+)]").find(it)?.groupValues?.get(1) ?: "?" }.eachCount().entries.sortedByDescending { it.value }.forEach { appendLine("- ${it.key}: ${it.value}") }
            appendLine(); appendLine("### Selhání (falešné jistoty první, max. 80)"); (bad + r.failures.filter { it.startsWith("MLČEL") }).take(80).forEach { appendLine("- $it") }
        }
        File("build/step0eval").mkdirs(); File("build/step0eval/${r.split}.md").writeText(txt)
        println(txt.lines().take(10).joinToString("\n"))
    }

    @Test fun devAndValidation() {
        val dev = run("dev"); val va = run("validation")
        if (System.getProperty("step0.holdout") == "true") run("holdout")
        // RELEASE GATE (stanoveno předem): přijaté odpovědi ≥ 99 % správně; regrese nad tento práh blokuje
        listOf(dev, va).forEach { r -> assertTrue(r.accepted == 0 || (r.accepted - r.wrongConfident) * 100 >= 99 * r.accepted, "precision accepted < 99 % (${r.split}): ${r.wrongConfident} falešných jistot") }
    }
}
