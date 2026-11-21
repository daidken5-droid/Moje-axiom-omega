package cz.axiom.omega.core.util

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToLong

/** Textové utility sdílené jádrem (bez závislosti na Androidu). */
object TextUtil {

    private val combining = Regex("\\p{Mn}+")
    private val wordSplit = Regex("[^\\p{L}\\p{N}]+")

    /** Sjednotí mezery a ořízne okraje. */
    fun normalize(input: String): String = input.trim().replace(Regex("\\s+"), " ")

    /** Malá písmena bez diakritiky: "Příliš Žluťoučký" -> "prilis zlutoucky". */
    fun fold(input: String): String =
        combining.replace(Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD), "")

    /** Slova (bez diakritiky, malá písmena) delší než [minLen]. */
    fun words(input: String, minLen: Int = 1): List<String> =
        fold(input).split(wordSplit).filter { it.length >= minLen }

    /** Formát čísla: max. 2 desetinná místa, bez zbytečné nuly, česká čárka. */
    fun num(value: Double, decimals: Int = 2): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        val rounded = (value * factor).roundToLong() / factor
        val asLong = rounded.toLong()
        if (rounded == asLong.toDouble() && abs(rounded) < 1e15) return asLong.toString()
        return rounded.toString().replace('.', ',')
    }

    /** Parsuje číslo zapsané česky i anglicky ("1 234,5", "1234.5", "1,5"). */
    fun parseNumber(raw: String): Double? {
        val s = raw.replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        val normalized = when {
            s.contains(',') && s.contains('.') ->
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            else -> s.replace(',', '.')
        }
        return normalized.toDoubleOrNull()
    }

    /** Zkrátí text na [max] znaků s výpustkou. */
    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}

/**
 * Zkompilovaný regulární výraz z vyrovnávací paměti. Reflexy volají stovky vzorů na každou otázku –
 * `Regex("…")` uvnitř funkce se dřív kompiloval znovu při každém volání. Teď jednou za běh aplikace.
 * Paměť je omezená (dynamické vzory nemohou růst donekonečna).
 */
private val rxCache = java.util.concurrent.ConcurrentHashMap<String, Regex>(512)
fun rx(pattern: String): Regex = rxCache[pattern] ?: Regex(pattern).also { if (rxCache.size < 4000) rxCache[pattern] = it }
