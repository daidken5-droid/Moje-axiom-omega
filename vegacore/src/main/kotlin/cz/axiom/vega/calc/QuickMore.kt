package cz.axiom.vega.calc

import cz.axiom.vega.base.Text
import kotlin.random.Random

/** Další rychlé odpovědi bez modelu (sobota 28. 11., D14): BMI, římská čísla, kostka / mince / náhodné číslo. Volá je Quick. */
object QuickMore {
    fun answer(raw: String, rnd: Random = Random.Default): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('?', '.', '!', ' ').replace(Regex("\\s+"), " ")
        return clock(t) ?: travel(t) ?: bmi(t) ?: roman(raw.trim().trimEnd('?', '.', '!', ' '), t) ?: chance(t, rnd)
    }

    private const val N = "(\\d[\\d ]*(?:[.,]\\d+)?)"
    private fun num(s: String) = Quick.num(s)
    private fun fmt(v: Double) = Quick.fmtPublic(v)

    /** Hodiny ± doba: „17:30 mínus 2 hodiny 45 minut“, „8:15 plus 90 minut“, „23:30 + 2 h“. */
    private fun clock(t: String): String? {
        val m = Regex("^(?:kolik (?:je|bude) |vypocitej )?(\\d{1,2}):(\\d{2}) (plus|minus|\\+|-) (?:(\\d+) ?(?:h|hod\\w*))?(?: ?a)? ?(?:(\\d+) ?(?:min\\w*|m))?$").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); if (h > 23 || mi > 59) return null
        if (m.groupValues[4].isEmpty() && m.groupValues[5].isEmpty() || m.groupValues[4].length > 4 || m.groupValues[5].length > 6) return null // > 9999 h → nehádat (přetečení)
        val dh = m.groupValues[4].toIntOrNull() ?: 0; val dm = m.groupValues[5].toIntOrNull() ?: 0
        val sign = if (m.groupValues[3] == "minus" || m.groupValues[3] == "-") -1 else 1
        val tot = h * 60 + mi + sign * (dh * 60 + dm); val day = Math.floorDiv(tot, 1440); val r = Math.floorMod(tot, 1440)
        val dur = listOfNotNull(dh.takeIf { it > 0 }?.let { "$it h" }, dm.takeIf { it > 0 }?.let { "$it min" }).joinToString(" ").ifEmpty { "0 min" }
        val shift = when { day == 1 -> " (další den)"; day > 1 -> " (za $day dny)"; day == -1 -> " (předchozí den)"; day < -1 -> " (o ${-day} dny dřív)"; else -> "" }
        return "%d:%02d ${if (sign > 0) "+" else "−"} $dur = %d:%02d$shift.".format(h, mi, r / 60, r % 60)
    }

    /** Doba cesty: „jak dlouho pojedu 120 km rychlostí 90 km/h“. */
    private fun travel(t: String): String? {
        if (!Regex("jak dlouho|za jak dlouho|kolik (?:mi )?(?:to )?(?:bude )?trva").containsMatchIn(t)) return null
        val m = Regex("$N ?km (?:rychlosti|prumerem|prumernou rychlosti|v|pri|za) ?$N ?(?:km/h|km za hodinu|kmh)").find(t) ?: return null
        val km = num(m.groupValues[1]) ?: return null; val v = num(m.groupValues[2])?.takeIf { it > 0 && it < 2000 } ?: return null
        if (km <= 0 || km / v > 10_000) return null // přes 10 000 h = zjevný překlep, nehádat (a žádné přetečení Int)
        val min = Math.round(km / v * 60).toInt(); val dur = listOfNotNull((min / 60).takeIf { it > 0 }?.let { "$it h" }, (min % 60).takeIf { it > 0 || min == 0 }?.let { "$it min" }).joinToString(" ")
        return "${fmt(km)} km rychlostí ${fmt(v)} km/h trvá $dur (bez zastávek)."
    }

    private fun f1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',')

    /** „BMI 180 cm 80 kg“, „bmi, měřím 1,65 m a vážím 90 kg“ – kategorie podle WHO. */
    private fun bmi(t: String): String? {
        if (!Regex("(^|\\s)bmi(\\s|,|$)|body mass").containsMatchIn(t)) return null
        val kg = Regex("(\\d+(?:[.,]\\d+)?) ?(?:kg|kil\\w*)").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val cm = Regex("(\\d+(?:[.,]\\d+)?) ?cm").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: Regex("(\\d[.,]\\d{1,2}) ?m(?![a-z])").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.times(100) ?: return null
        if (kg !in 20.0..400.0 || cm !in 100.0..250.0) return null
        val b = kg / (cm / 100 * cm / 100)
        val cat = when { b < 18.5 -> "podváha"; b < 25 -> "normální váha"; b < 30 -> "nadváha"; b < 35 -> "obezita 1. stupně"; b < 40 -> "obezita 2. stupně"; else -> "obezita 3. stupně" }
        return "BMI pro ${f1(cm).removeSuffix(",0")} cm a ${f1(kg).removeSuffix(",0")} kg je ${f1(b)} – $cat (WHO: 18,5–24,9 normální). Orientační číslo, nezohledňuje svaly ani věk."
    }

    private val R = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    fun toRoman(n: Int): String? { if (n !in 1..3999) return null; var x = n
        return buildString { for ((v, s) in R) while (x >= v) { append(s); x -= v } } }
    /** Jen kanonický zápis (MCMXCIV ano, IIII ne) – jinak null. */
    fun fromRoman(s: String): Int? { val u = s.uppercase(); if (u.isEmpty() || !u.all { it in "MDCLXVI" }) return null
        var i = 0; var n = 0; for ((v, sym) in R) while (u.startsWith(sym, i)) { n += v; i += sym.length }
        return n.takeIf { i == u.length && toRoman(it) == u } }

    private fun roman(raw: String, t: String): String? {
        Regex("^(?:kolik je |preved |prepis |napis )?(?:rimsky |rimskymi cislicemi |rimskym cislem )?(\\d{1,5})(?: (?:rimsky|rimskymi cislicemi|rimskym cislem|na rimske(?: cislo| cislice)?|do rimskych cislic))?$").find(t)
            ?.takeIf { Regex("rimsk").containsMatchIn(t) }?.let { m -> val n = m.groupValues[1].toInt(); return toRoman(n)?.let { "$n římskými číslicemi: $it." } }
        Regex("^(?:preved |prepis )?([mdclxvi]+)(?: (?:na|do) (?:arabske|normalni|bezne)(?: cislo| cislice)?| arabsky)$").find(t)?.let { m ->
            val v = fromRoman(m.groupValues[1]) ?: return null; return "${m.groupValues[1].uppercase()} = $v." }
        // „XL římsky“, „římské číslo CD“ – výslovně římské, i krátké
        Regex("^(?:rimske cislo|rimsky|rimskymi cislicemi) ([mdclxvi]+)$|^([mdclxvi]+) (?:rimsky|je rimske cislo|jako rimske cislo)$").find(t)?.let { m ->
            val r = m.groupValues[1].ifEmpty { m.groupValues[2] }; val v = fromRoman(r) ?: return null; return "${r.uppercase()} je římské číslo $v." }
        // „co je XLII“ jen velkými písmeny a aspoň 4 znaky – kratší jsou spíš zkratky (CV, XL, CD, DIV, MIX), ty patří modelu
        Regex("^(?i:co je|kolik je|co znamená|co znamena) ([MDCLXVI]{4,})$").find(raw)?.let { m ->
            val v = fromRoman(m.groupValues[1]) ?: return null; return "${m.groupValues[1]} je římské číslo $v." }
        return null
    }

    private fun chance(t: String, rnd: Random): String? {
        if (Regex("^(?:hod|hodit|hodis|hodte) (?:kostkou|kostku)$").matches(t)) return "Padla ${rnd.nextInt(1, 7)}."
        if (Regex("^(?:hod|hodit|hodis) minci$|^panna nebo orel$|^orel nebo panna$").matches(t)) return if (rnd.nextBoolean()) "Padl orel." else "Padla panna."
        Regex("^(?:dej mi |vyber |vylosuj |vygeneruj )?(?:nahodne|nejake) cislo (?:od|mezi) (-?\\d{1,9}) (?:do|a) (-?\\d{1,9})$").find(t)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val lo = minOf(a, b); val hi = maxOf(a, b)
            return "Náhodné číslo od $lo do $hi: ${rnd.nextLong(lo, hi + 1)}." }
        return null
    }
}
