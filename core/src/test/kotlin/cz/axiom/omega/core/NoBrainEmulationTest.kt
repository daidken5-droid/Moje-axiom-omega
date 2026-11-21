package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.util.TextUtil
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * EMULACE UŽIVATELŮ – modul bez mozku. Tisíce realisticky formulovaných dotazů s náhodnými čísly a hovorovými
 * obměnami (bez diakritiky, oslovení, „prosím“, otazník, malá/velká písmena). Správný výsledek spočítá NEZÁVISLE
 * test (orákulum) – takže se pozná i CHYBNÁ odpověď, ne jen chybějící.
 *   správně  = odpověď obsahuje orákulum,
 *   CHYBNĚ   = odpověděl, ale číslo nesedí (halucinace reflexu – musí být 0),
 *   mlčel    = předal mozku (měří se jako pokrytí).
 */
class NoBrainEmulationTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneId.of("Europe/Prague"))
    private val ctx = Reflex.Ctx(Instant.Ctx(now = now, battery = 70, charging = false), threshold = 85)
    private val rnd = Random(2026)

    data class Case(val family: String, val q: String, val oracle: Double, val text: String? = null)

    private val words = listOf("nula", "jedna", "dva", "tři", "čtyři", "pět", "šest", "sedm", "osm", "devět", "deset", "jedenáct", "dvanáct")
    private val dows = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun cz(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

    private val wraps = listOf<(String) -> String>(
        { it }, { "$it?" }, { TextUtil.fold(it) }, { "prosím tě, $it" }, { "hele $it" }, { it.replaceFirstChar { c -> c.uppercase() } + "?" },
        { "$it prosím" }, { "Ahoj, $it?" },
    )

    private fun cases(): List<Case> {
        val out = mutableListOf<Case>()
        repeat(120) {
            val a = rnd.nextInt(2, 99); val b = rnd.nextInt(2, 99)
            out += Case("násobení", listOf("kolik je $a krát $b", "kolik je $a x $b", "spočítej $a * $b", "$a krát $b").random(rnd), (a * b).toDouble())
            out += Case("sčítání", listOf("kolik je $a plus $b", "kolik je $a + $b", "$a + $b").random(rnd), (a + b).toDouble())
            val p = listOf(5, 10, 12, 15, 20, 25, 30, 50, 75).random(rnd); val n = rnd.nextInt(1, 200) * 10
            out += Case("procenta", listOf("kolik je $p % z $n", "$p procent z $n", "kolik je $p procent z $n").random(rnd), p * n / 100.0)
            val km = rnd.nextInt(1, 50)
            out += Case("km→m", listOf("kolik metrů je $km km", "převeď $km km na metry", "$km km na m").random(rnd), km * 1000.0)
            val kg = rnd.nextInt(1, 30)
            out += Case("kg→g", listOf("kolik gramů je $kg kg", "převeď $kg kg na gramy").random(rnd), kg * 1000.0)
            val h = rnd.nextInt(1, 24)
            out += Case("h→min", listOf("kolik minut je $h hodin", "převeď $h h na minuty").random(rnd), h * 60.0)
            val d = LocalDate.of(2026, 11, 3).plusDays(rnd.nextLong(1, 300))
            out += Case("dny do data", "kolik dní zbývá do ${d.dayOfMonth}. ${d.monthValue}. ${d.year}", ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 3), d).toDouble())
            val net = rnd.nextInt(1, 100) * 100
            out += Case("DPH", "21 % dph z $net", net * 0.21)
        }
        // ---- TĚŽŠÍ RODINY
        repeat(60) {
            val a = rnd.nextInt(1, 99) + rnd.nextInt(1, 9) / 10.0; val b = rnd.nextInt(2, 20)
            out += Case("desetinná", listOf("kolik je ${cz(a)} krát $b", "${cz(a)} * $b", "kolik je ${cz(a)} plus $b").random(rnd).let { q -> q },
                0.0).let { c -> c.copy(oracle = if ("plus" in c.q) a + b else a * b) }
            val x = rnd.nextInt(10, 999); val y = listOf(3, 4, 6, 7, 8, 9).random(rnd)
            out += Case("dělení", listOf("kolik je $x děleno $y", "$x / $y", "spočítej $x : $y").random(rnd), x.toDouble() / y)
            val m = rnd.nextInt(1, 50); val n2 = m + rnd.nextInt(1, 50)
            out += Case("záporné", "kolik je $m minus $n2", (m - n2).toDouble())
            val w1 = rnd.nextInt(2, 13); val w2 = rnd.nextInt(2, 13)
            out += Case("slovy", "kolik je ${words[w1]} krát ${words[w2]}", (w1 * w2).toDouble())
            val eur = rnd.nextInt(1, 100) * 10
            out += Case("měna", listOf("kolik je $eur eur v korunách", "$eur eur na koruny", "převeď $eur EUR na CZK").random(rnd), eur * 24.30)
            val c = rnd.nextInt(-20, 40)
            out += Case("°C→°F", "$c °C na fahrenheity", c * 9.0 / 5 + 32)
            val d = LocalDate.of(2026, 11, 3).plusDays(rnd.nextLong(1, 400))
            out += Case("den v týdnu", "jaký den bude ${d.dayOfMonth}. ${d.monthValue}. ${d.year}", -1.0, dows[d.dayOfWeek.value - 1])
            val price = rnd.nextInt(1, 100) * 100; val pct = listOf(10, 20, 25, 30, 40, 50).random(rnd)
            out += Case("sleva", "sleva $pct % z $price", price * (1 - pct / 100.0))
            val by = rnd.nextInt(1950, 2010); val bm = rnd.nextInt(1, 13); val bd = rnd.nextInt(1, 29)
            out += Case("věk", "kolik mi je let, narodil jsem se $bd. $bm. $by", ChronoUnit.YEARS.between(LocalDate.of(by, bm, bd), LocalDate.of(2026, 11, 3)).toDouble())
        }
        return out.map { c -> c.copy(q = wraps.random(rnd)(c.q)) }
    }

    /** Otázky s čísly, které PATŘÍ MOZKU – reflex nesmí vytrhnout čísla a odpovědět (falešný reflex). */
    @Test fun emulatedBrainQuestionsWithNumbers() {
        val t = listOf(
            "mám {a} děti a {b} psy, jak mám zvládnout ráno", "kolik stojí {a} kg jablek v Kauflandu", "jak dlouho jede vlak z Prahy do Brna v {a} hodin",
            "porovnej dva notebooky za {a} 000 korun", "napiš mi {a} tipů na dárek pro tátu", "co dělat, když má dítě {a} stupňů horečku",
            "je lepší hypotéka na {a} let nebo na {b} let", "vymysli {a} otázek do kvízu o Praze", "jaký je rozdíl mezi {a} % a {b} % tukem v mléce",
            "doporuč mi {a} knih na léto", "kolik kalorií spálím za {a} minut běhu", "proč mi telefon po {a} letech zpomaluje",
            "mám {a} tisíc na účtu, kam je investovat", "shrň mi {a} hlavních bodů z knihy Babička", "naplánuj mi {a} denní výlet do Itálie",
        )
        val wrong = mutableListOf<String>()
        repeat(300) {
            val q = wraps.random(rnd)(t.random(rnd).replace("{a}", rnd.nextInt(2, 40).toString()).replace("{b}", rnd.nextInt(2, 40).toString()))
            Reflex.answer(q, ctx)?.let { wrong += "$q → ${it.text.take(80)} [${it.kind}]" }
        }
        println("EMULACE mozkových otázek s čísly: 300 · falešný reflex ${wrong.size}\n" + wrong.distinct().take(30).joinToString("\n"))
        assertTrue(wrong.isEmpty(), "${wrong.size} falešných reflexů")
    }

    /** Čísla v odpovědi („5 000“, „1 024,5“, „**360**“) → hodnoty. */
    private fun numbers(t: String): List<Double> =
        Regex("[-−]?(?:\\d{1,3}(?:[  ]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)").findAll(t.replace("**", ""))
            .mapNotNull { it.value.replace('−', '-').replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull() }.toList()

    @Test fun emulatedUsers() {
        val all = cases()
        val byFam = linkedMapOf<String, IntArray>() // správně, chybně, mlčel
        val wrong = mutableListOf<String>(); val silent = mutableListOf<String>()
        for (c in all) {
            val a = Reflex.answer(c.q, ctx)
            val s = byFam.getOrPut(c.family) { IntArray(3) }
            when {
                a == null -> { s[2]++; silent += "[${c.family}] ${c.q}" }
                c.text != null -> if (TextUtil.fold(a.text).lowercase().contains(TextUtil.fold(c.text))) s[0]++ else { s[1]++; wrong += "[${c.family}] ${c.q} → ${a.text.take(100)} (správně ${c.text})" }
                numbers(a.text).any { kotlin.math.abs(it - c.oracle) < 0.01 * maxOf(1.0, kotlin.math.abs(c.oracle)) * 0.001 + 0.005 } -> s[0]++
                else -> { s[1]++; wrong += "[${c.family}] ${c.q} → ${a.text.take(100)} (správně ${c.oracle})" }
            }
        }
        val sb = StringBuilder("\nEMULACE bez mozku: ${all.size} dotazů\n")
        byFam.forEach { (f, s) -> sb.append("  %-12s správně %4d · CHYBNĚ %3d · mlčel %3d\n".format(f, s[0], s[1], s[2])) }
        wrong.take(40).forEach { sb.append("  CHYBNĚ $it\n") }
        silent.distinct().take(25).forEach { sb.append("  mlčel  $it\n") }
        println(sb)
        assertTrue(wrong.isEmpty(), "${wrong.size} chybných odpovědí bez mozku")
    }
}
