package cz.axiom.vega.base

import java.text.Normalizer

/** Práce s českým textem: bez diakritiky pro porovnávání, slova pro vyhledávání. */
object Text {
    /** České skloňování po čísle: 1 řádek · 2–4 řádky · 0, 5+ řádků („4 pohybů“ v hlášení laik vidí jako chybu). */
    fun plural(n: Number, one: String, few: String, many: String): String = "$n " + when (n.toLong()) { 1L -> one; in 2L..4L -> few; else -> many }
    private val marks = Regex("\\p{Mn}+")
    /** „Příliš žluťoučký“ → „Prilis zlutoucky“ (stejná délka u češtiny, bezpečné pro posuny v textu). Čisté ASCII beze změny a bez normalizace (rychle). */
    fun fold(s: String): String = if (s.all { it.code < 128 }) s else marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").let { if (it.length == s.length) it else foldByChar(s) }
    private fun foldByChar(s: String): String = buildString(s.length) { s.forEach { c -> append(marks.replace(Normalizer.normalize(c.toString(), Normalizer.Form.NFD), "").firstOrNull() ?: c) } }

    /** Slova (písmena a číslice) delší než [min] znaků, malými písmeny. */
    fun words(s: String, min: Int = 3): List<String> = Regex("[\\p{L}\\p{Nd}]+").findAll(s.lowercase()).map { it.value }.filter { it.length >= min }.toList()
}
