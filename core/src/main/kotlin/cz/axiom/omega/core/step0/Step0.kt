package cz.axiom.omega.core.step0

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.TextUtil.num

/**
 * STEP-0: deterministická analýza zadání PŘED tím, než se zapojí jazykový model.
 * Čísla počítá kód, ne model – model je dostane jako ověřená fakta (DNA AXIOM 3.0 × ASTRAEA 5.0).
 */
object Step0 {

    enum class Kind(val label: String) {
        MATH("STEP-0 MATH"),
        PERCENT("PROCENTA"),
        EV("ASTRAEA +EV"),
        UNIT("PŘEVOD"),
        BUILD("STAVEBNÍ KALKULACE"),
        IMPORT("IMPORT JAPONSKO"),
        FORENSIC("FORENSIC"),
    }

    data class Fact(val kind: Kind, val text: String) {
        override fun toString() = "${kind.label}: $text"
    }

    private val mathRx = Regex(
        """(?<![\p{L}\d.,])(?:[(−-]\s*)*(?:\d+(?:[.,]\d+)?|pi)(?:\s*[-+*/^×÷−]\s*[(]*\s*-?\s*(?:\d+(?:[.,]\d+)?|pi)\s*[)]*)+""",
    )
    private val dateLike = Regex("""^\d{1,4}\s*[-./]\s*\d{1,2}\s*[-./]\s*\d{1,4}$|^\d{1,2}\s*/\s*\d{4}$""")
    private val phoneLike = Regex("""^\+?\d{3}-\d{3}-\d{3,4}$""")

    fun analyze(text: String, rates: Rates = Rates.FALLBACK): List<Fact> {
        val out = mutableListOf<Fact>()
        out += math(text)
        out += percent(text)
        ev(text)?.let { out += it }
        out += Units.scan(text, rates).map { Fact(Kind.UNIT, it) }
        build(text)?.let { out += it }
        importJp(text, rates)?.let { out += it }
        out += Forensic.scan(text).map { Fact(Kind.FORENSIC, "[${it.kind}] ${it.value}") }
        return out.distinct()
    }

    fun math(text: String): List<Fact> {
        val seen = LinkedHashSet<String>()
        for (m in mathRx.findAll(text)) {
            val raw = m.value.trim().trimEnd('(').trim()
            if (dateLike.matches(raw) || phoneLike.matches(raw)) continue
            // samotné "-" bez mezer mezi čísly je typicky rozsah/kód (2020-2024, 123-456), ne odečítání
            val ops = raw.filter { it in "+*/^×÷" }
            if (ops.isEmpty() && !Regex("""\s[-−]\s""").containsMatchIn(raw)) continue
            if (!raw.any { it.isDigit() }) continue
            Calc.eval(raw).onSuccess { seen += "$raw = ${num(it, 6)}" }
        }
        return seen.map { Fact(Kind.MATH, it) }
    }

    private val pctOf = Regex("""(\d+(?:[.,]\d+)?)\s*%\s*(?:z|ze|of|from)\s+(\d[\d\s]*(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    fun percent(text: String): List<Fact> = pctOf.findAll(text).mapNotNull { m ->
        val p = TextUtil.parseNumber(m.groupValues[1]) ?: return@mapNotNull null
        val base = TextUtil.parseNumber(m.groupValues[2].trim()) ?: return@mapNotNull null
        Fact(Kind.PERCENT, "${num(p)} % z ${num(base)} = ${num(base * p / 100.0)}")
    }.toList()

    private val oddsRx = Regex("""(?:kurz(?:em|u)?|odds|@)\s*:?\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val probKeyRx = Regex(
        """(?:pravd[eě]podobnost(?:í)?|odhad(?:em)?|šance|sance|p)\s*[:=]?\s*(\d+(?:[.,]\d+)?)\s*%""",
        RegexOption.IGNORE_CASE,
    )
    private val anyPctRx = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
    private val bankrollRx = Regex("""bank(?:roll)?\w*\s*:?\s*(\d[\d\s]*(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    /** +EV engine s Half-Kelly (ASTRAEA DNA). */
    fun ev(text: String): Fact? {
        val odds = oddsRx.find(text)?.groupValues?.get(1)?.let(TextUtil::parseNumber) ?: return null
        val pctMatch = probKeyRx.find(text) ?: anyPctRx.find(text) ?: return null
        val p = (TextUtil.parseNumber(pctMatch.groupValues[1]) ?: return null) / 100.0
        if (odds <= 1.0 || p !in 0.001..0.999) return null
        val b = odds - 1.0
        val implied = 1.0 / odds
        val evPct = (p * odds - 1.0) * 100.0
        val kelly = ((b * p - (1.0 - p)) / b).coerceAtLeast(0.0) * 100.0
        val half = kelly / 2.0
        val status = when {
            evPct >= 3.0 -> "SILNÉ +EV"
            evPct > 0.0 -> "MÍRNÉ +EV (tenká hrana)"
            else -> "-EV → NEVSÁZET"
        }
        val bankroll = bankrollRx.find(text)?.groupValues?.get(1)?.trim()?.let(TextUtil::parseNumber)
        val stake = if (bankroll != null && half > 0) " | vklad ≈ ${num(bankroll * half / 100.0)} z bankrollu ${num(bankroll)}" else ""
        return Fact(
            Kind.EV,
            "kurz ${num(odds)} | tvůj odhad ${num(p * 100)} % vs. implikovaná ${num(implied * 100)} % | " +
                "férový kurz ${num(1.0 / p)} | EV ${num(evPct)} % [$status] | " +
                "Kelly ${num(kelly)} % → Half-Kelly ${num(half)} % bankrollu$stake",
        )
    }

    private val m2Rx = Regex("""(\d+(?:[.,]\d+)?)\s*(?:m2|m²|metr\w*\s+čtvereční\w*)""", RegexOption.IGNORE_CASE)

    /** Mikrocement / stěrka / podlahy (DNA Titanium) – spotřeba materiálu. */
    fun build(text: String): Fact? {
        val folded = TextUtil.fold(text)
        if (listOf("mikrocement", "sterk", "podlah", "vinyl", "nivel").none { it in folded }) return null
        val m2 = m2Rx.find(text)?.groupValues?.get(1)?.let(TextUtil::parseNumber) ?: return null
        if (m2 <= 0) return null
        val waste = m2 * 1.10
        val bags = kotlin.math.ceil((m2 * 1.5 * 3.0) / 25.0).toInt()
        return Fact(
            Kind.BUILD,
            "${num(m2)} m² (s 10% prořezem ${num(waste)} m²): penetrace ${num(waste * 0.15)} kg, " +
                "base ${num(waste * 1.60)} kg, fine ${num(waste * 0.80)} kg, 2K PU lak ${num(waste * 0.22)} kg; " +
                "samonivelace 3 mm ≈ $bags pytlů à 25 kg (1,5 kg/m²/mm). Spotřeby jsou typické – ověř technický list výrobce.",
        )
    }

    private val jpyRx = Regex("""(\d[\d\s]*(?:[.,]\d+)?)\s*(?:jpy|jen[ůu]?|yen|¥)""", RegexOption.IGNORE_CASE)

    /** Import z Japonska (DNA Titanium) – DPH se počítá ze základu VČETNĚ dopravy (Titanium ji vynechávalo). */
    fun importJp(text: String, rates: Rates, shippingCzk: Double = 450.0): Fact? {
        val jpy = jpyRx.find(text)?.groupValues?.get(1)?.trim()?.let(TextUtil::parseNumber) ?: return null
        if (jpy <= 0) return null
        val rate = rates.of("JPY") ?: return null
        val goods = jpy * rate
        val base = goods + shippingCzk
        val vat = base * 0.21
        return Fact(
            Kind.IMPORT,
            "${num(jpy)} JPY ≈ ${num(goods)} Kč (kurz ${num(rate, 4)} Kč/JPY, ${rates.label()}) | " +
                "doprava (předpoklad) ${num(shippingCzk)} Kč | DPH 21 % ze ${num(base)} Kč = ${num(vat)} Kč | " +
                "celkem ≈ ${num(base + vat)} Kč + případný poplatek dopravce. Clo dle zařazení zboží (např. hrací karty TARIC 9504 40 00 00 = 0 %) – ověř aktuální pravidla.",
        )
    }
}
