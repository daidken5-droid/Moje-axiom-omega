package cz.axiom.omega.core.step0

import cz.axiom.omega.core.util.TextUtil

/**
 * Kurzy měn vůči CZK (kolik Kč stojí 1 jednotka měny).
 *
 * AXIOM 2.1/Titanium měl kurzy natvrdo (EUR 25,30; JPY 0,155) bez data → výstupy
 * tiše zastarávaly. Omega nese datum a zdroj, a online je obnovuje z ČNB.
 */
data class Rates(
    val czkPerUnit: Map<String, Double>,
    val date: String,
    val source: String,
) {
    fun of(code: String): Double? = if (code.uppercase() == "CZK") 1.0 else czkPerUnit[code.uppercase()]

    fun label(): String = "$source $date"

    companion object {
        /** Záložní orientační kurzy (použijí se, dokud se nepodaří stáhnout ČNB). */
        val FALLBACK = Rates(
            mapOf("EUR" to 24.30, "USD" to 21.60, "JPY" to 0.142, "GBP" to 28.40, "CHF" to 26.20, "PLN" to 5.70),
            date = "orientační",
            source = "vestavěné",
        )

        const val CNB_URL =
            "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt"

        /**
         * Parser denního kurzovního lístku ČNB:
         * ```
         * 31.10.2026 #211
         * země|měna|množství|kód|kurz
         * EMU|euro|1|EUR|24,305
         * Japonsko|jen|100|JPY|14,215
         * ```
         */
        fun parseCnb(text: String): Rates {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.size >= 3) { "Kurzovní lístek je neúplný" }
            val date = lines[0].substringBefore('#').trim()
            val map = LinkedHashMap<String, Double>()
            for (line in lines.drop(2)) {
                val p = line.split('|')
                if (p.size < 5) continue
                val amount = TextUtil.parseNumber(p[2]) ?: continue
                val rate = TextUtil.parseNumber(p[4]) ?: continue
                if (amount <= 0) continue
                map[p[3].trim().uppercase()] = rate / amount
            }
            require(map.containsKey("EUR")) { "Kurzovní lístek neobsahuje EUR" }
            return Rates(map, date, "ČNB")
        }
    }
}
