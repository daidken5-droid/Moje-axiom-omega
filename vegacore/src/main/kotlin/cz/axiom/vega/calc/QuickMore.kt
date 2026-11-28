package cz.axiom.vega.calc

import cz.axiom.vega.base.Text
import kotlin.random.Random

/** Další rychlé odpovědi bez modelu (sobota 28. 11., D14): BMI, římská čísla, kostka / mince / náhodné číslo. Volá je Quick. */
object QuickMore {
    fun answer(raw: String, rnd: Random = Random.Default): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('?', '.', '!', ' ').replace(Regex("\\s+"), " ")
        return bmi(t) ?: roman(raw.trim().trimEnd('?', '.', '!', ' '), t) ?: chance(t, rnd)
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
        // „co je XLII“ jen když je číslo napsané velkými písmeny (jinak „co je mix“ → slovo, ne 1009)
        Regex("^(?i:co je|kolik je|co znamená|co znamena) ([MDCLXVI]{2,})$").find(raw)?.let { m ->
            val v = fromRoman(m.groupValues[1]) ?: return null; return "${m.groupValues[1]} je římské číslo $v." }
        return null
    }

    private fun chance(t: String, rnd: Random): String? {
        if (Regex("^(?:hod|hodit|hodis|hodte) (?:kostkou|kostku)$|^kostka$").matches(t)) return "Padla ${rnd.nextInt(1, 7)}."
        if (Regex("^(?:hod|hodit|hodis) minci$|^panna nebo orel$|^orel nebo panna$").matches(t)) return if (rnd.nextBoolean()) "Padl orel." else "Padla panna."
        Regex("^(?:dej mi |vyber |vylosuj |vygeneruj )?(?:nahodne|nejake) cislo (?:od|mezi) (-?\\d{1,9}) (?:do|a) (-?\\d{1,9})$").find(t)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val lo = minOf(a, b); val hi = maxOf(a, b)
            return "Náhodné číslo od $lo do $hi: ${rnd.nextLong(lo, hi + 1)}." }
        return null
    }
}
