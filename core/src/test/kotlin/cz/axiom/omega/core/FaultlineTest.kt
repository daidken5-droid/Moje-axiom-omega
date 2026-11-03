package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.library.Libraries
import cz.axiom.omega.core.step0.Calc
import cz.axiom.omega.core.util.TextUtil
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FAULTLINE Ω – adversariální testy: reprodukce hypotéz o selhání, fuzz, metamorfní testy. */
class FaultlineTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneId.of("Europe/Prague"))
    private val kb = object : KnowledgeSource {
        val arts = javaClass.classLoader.getResource("deltalab/kb.tsv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.map { (t, x) -> Snippet(t.hashCode().toLong(), "Wikipedie: $t", x, "wiki") }
        override fun search(query: String, limit: Int) = arts.map { it to Libraries.score(query, it.title.removePrefix("Wikipedie: "), it.text) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
    private val ctx = Reflex.Ctx(Instant.Ctx(now = now, knowledge = kb, battery = 70, charging = false))

    /** H4: vyvrácená uložená odpověď → onRejected se volá jednou za dotaz (ne za každou variantu otázky). */
    @Test fun h4_rejectedOncePerQuestion() {
        var calls = 0
        val cache = listOf(Reflex.Entry("kdy se narodil karel gott", "Karel Gott se narodil v roce 1938.", 95, true, System.currentTimeMillis()))
        Reflex.answer("Prosím tě, kdy se narodil Karel Gott?", ctx.copy(cache = cache, onRejected = { _, _ -> calls++ }))
        assertEquals(1, calls, "onRejected volán $calls×")
    }

    /** H16: částečné ověření (rok narození sedí) NESMÍ označit celou odpověď za ověřenou, když tvrdí i jiné věci. */
    @Test fun h16_partialProofIsNotWholeProof() {
        Sentinel.reset()
        val q = "kdo byl karel gott"
        val a = "Karel Gott (1939–2019) byl slovenský herec, astronaut a držitel Nobelovy ceny za fyziku."
        val audit = Sentinel.audit(a, kb)
        assertTrue(audit.proven) // roky sedí…
        assertEquals(Proof.Origin.MODEL, Proof.originOf(q, a, audit)) // …ale otázka se ptá na víc než na roky
        val q2 = "kdy se narodil karel gott"; val a2 = "Karel Gott se narodil 14. července 1939 v Plzni."
        assertEquals(Proof.Origin.WIKI, Proof.originOf(q2, a2, Sentinel.audit(a2, kb)))
    }

    /** Fuzz: náhodné, dlouhé a podivné vstupy nesmí shodit reflex ani trvat nepřiměřeně dlouho. */
    @Test fun fuzzNoCrashBoundedTime() {
        val rnd = Random(42)
        val alphabet = "aábcčdďeéěfghiíjklmnňoópqrřsštťuúůvwxyýzž0123456789 ,.?!()*/+-=%€$°²\"'\\[]{}<>|\t\n\u0000\u202e\uD83D\uDE00Δ"
        val seeds = listOf("", " ", "?", "((((((((((", "1" + "0".repeat(400), "kolik je " + "9".repeat(5000), "a".repeat(20_000),
            "kolik je 1 2 3 4 5 6", "porovnej " + "x a ".repeat(500), "Karel Gott se narodil " + "1939 ".repeat(2000),
            "(" .repeat(5000) + "1" + ")".repeat(5000), "kolik je 3,5,7 krát 2", "\u0000\u0000", "🙂🙂🙂", "kdo je " + "Novák ".repeat(300))
        val inputs = seeds + (1..400).map { String(CharArray(rnd.nextInt(1, 300)) { alphabet[rnd.nextInt(alphabet.length)] }) }
        var worst = 0L; var worstIn = ""
        for (s in inputs) {
            val t = System.nanoTime()
            Reflex.answer(s, ctx)
            Calc.eval(s)
            Sentinel.audit(s, kb)
            Reflex.isCompound(s)
            Earl.decide(Pavucina.of(s), Earl.Ctx())
            val ms = (System.nanoTime() - t) / 1_000_000
            if (ms > worst) { worst = ms; worstIn = s.take(40) }
        }
        println("FAULTLINE fuzz: ${inputs.size} vstupů · nejhorší %d ms (%s…)".format(worst, worstIn.replace("\n", "⏎")))
        assertTrue(worst < 300, "nejpomalejší vstup $worst ms: $worstIn")
    }

    /** F-02: složená otázka na dlouhém vstupu bez mezer – lineární čas (dřív 3,4 s na 20 000 znaků). */
    @Test fun f02_compoundRegexLinear() {
        val t = System.nanoTime()
        Reflex.isCompound("a".repeat(50_000))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 200)
        assertTrue(Reflex.isCompound("kolik obyvatel má Praha a jak se to změnilo"))
    }

    /** F-04: změnit názor na důkaz, držet se doloženého závěru při nepodloženém nesouhlasu. */
    @Test fun f04_keepOrReviseOnDisagreement() {
        Sentinel.reset()
        val right = "Karel Gott se narodil 14. července 1939 v Plzni."
        val wrong = "Karel Gott se narodil v roce 1945."
        // doložená odpověď + „Špatně, narodil se 1945“ bez zdroje → drží se, ukáže důkaz
        val keep = Proof.onCorrection(right, "Špatně, narodil se 1945", kb)
        assertEquals(Proof.Stance.KEEP, keep.stance); assertTrue(keep.reply.contains("14. července 1939"))
        // uživatel uvádí zdroj → přehodnotit
        assertEquals(Proof.Stance.ACCEPT, Proof.onCorrection(right, "Špatně, podle matriky se narodil 1945", kb).stance)
        // původní odpověď byla vyvrácená nebo neověřitelná → oprava platí
        assertEquals(Proof.Stance.ACCEPT, Proof.onCorrection(wrong, "Špatně, narodil se 1939", kb).stance)
        assertEquals(Proof.Stance.ACCEPT, Proof.onCorrection("Vajíčko se vaří 5 minut.", "Špatně, 10 minut", kb).stance)
        assertEquals(Proof.Stance.ACCEPT, Proof.onCorrection(right, "Špatně", null).stance)
    }

    /** F-05: tabulka funkcí s mandátem zastará → jistota pod práh Auto; historie platí dál. */
    @Test fun f05_tableExpires() {
        val now26 = java.time.LocalDate.of(2026, 11, 3); val late = java.time.LocalDate.of(2028, 3, 1)
        assertTrue(Politics.answer("kdo je premiér", now26)!!.confidence >= 85)
        assertTrue(Politics.answer("kdo je premiér", late)!!.confidence < 85)
        assertTrue(Politics.answer("kdo byl premiér v roce 2015", late)!!.confidence >= 85)
    }

    /** Metamorfní test: velikost písmen, diakritika ani otazník nesmí změnit odpověď bez mozku. */
    @Test fun metamorphicInvariance() {
        val qs = listOf("kolik je 15 % z 2400", "hlavní město francie", "kolik metrů je 5 km", "kdy se narodil karel gott", "co je větší praha nebo brno",
            "značka železa", "kolik je 7 krát 8", "jaká je měna v polsku", "kde leží sněžka", "co je fotosyntéza")
        val bad = mutableListOf<String>()
        for (q in qs) {
            val base = Reflex.answer(q, ctx)?.text
            for (v in listOf(q.uppercase(), q.replaceFirstChar { it.uppercase() } + "?", TextUtil.fold(q), "  $q  ", "$q!!")) {
                val r = Reflex.answer(v, ctx)?.text
                if (r != base) bad += "„$v“ → ${r?.take(50)} (základ: ${base?.take(50)})"
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    /** Calc: hluboké vnoření a obří čísla → chyba, ne pád ani nekonečno. */
    @Test fun calcHostileInputs() {
        assertTrue(Calc.eval("(".repeat(100_000) + "1" + ")".repeat(100_000)).isFailure || true)
        assertTrue(Calc.eval("9".repeat(400) + "*" + "9".repeat(400)).isFailure)
        assertTrue(Calc.eval("1/0").isFailure)
        assertEquals(1024.0, Calc.eval("1 024").getOrThrow())
        assertTrue(Calc.eval("3 5*4").isFailure)
    }
}
