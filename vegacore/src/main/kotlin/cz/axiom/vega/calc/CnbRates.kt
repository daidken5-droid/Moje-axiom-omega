package cz.axiom.vega.calc

/**
 * Kurzovní lístek ČNB (denní, formát „denni_kurz.txt“). Čistý parser + převod, testovatelný bez sítě.
 * Telefon stáhne text z cnb.cz; tohle ho jen rozebere a převede. Kurzy jsou ČNB fixing, ne nákup/prodej v bance.
 */
object CnbRates {
    data class Rate(val code: String, val amount: Int, val rate: Double, val name: String)
    data class Table(val date: String, val rates: Map<String, Rate>)

    /**
     * Formát:
     * ```
     * 20.11.2026 #224
     * země|měna|množství|kód|kurz
     * EMU|euro|1|EUR|25,340
     * ```
     */
    fun parse(text: String): Table {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalArgumentException("Prázdný kurzovní lístek")
        val date = Regex("^(\\d{1,2}\\.\\d{1,2}\\.\\d{4})").find(lines[0])?.groupValues?.get(1) ?: ""
        val rates = LinkedHashMap<String, Rate>()
        for (l in lines.drop(1)) {
            val p = l.split('|'); if (p.size < 5) continue
            val amount = p[2].trim().toIntOrNull() ?: continue
            val rate = p[4].trim().replace(" ", "").replace(',', '.').toDoubleOrNull() ?: continue
            val code = p[3].trim().uppercase(); if (code.length != 3 || rate <= 0) continue
            rates[code] = Rate(code, amount, rate, p[1].trim())
        }
        if (rates.isEmpty()) throw IllegalArgumentException("V lístku nejsou žádné kurzy")
        return Table(date, rates)
    }

    /** Kolik Kč je [value] jednotek měny [code] (CZK = beze změny). null = měnu neznáme. */
    fun toCzk(t: Table, value: Double, code: String): Double? {
        val c = code.uppercase(); if (c == "CZK") return value
        val r = t.rates[c] ?: return null
        return value * r.rate / r.amount
    }

    /** Převod mezi dvěma měnami přes CZK. */
    fun convert(t: Table, value: Double, from: String, to: String): Double? {
        val czk = toCzk(t, value, from) ?: return null
        val toU = to.uppercase(); if (toU == "CZK") return czk
        val r = t.rates[toU] ?: return null
        return czk * r.amount / r.rate
    }

    /** České/anglické názvy měn → ISO kód (pro „euro“, „dolar“, „libra“, „zlotý“…). */
    val ALIAS = mapOf(
        "eur" to "EUR", "euro" to "EUR", "eura" to "EUR", "euru" to "EUR", "€" to "EUR",
        "usd" to "USD", "dolar" to "USD", "dolaru" to "USD", "dolary" to "USD", "dolaru" to "USD", "\$" to "USD",
        "gbp" to "GBP", "libra" to "GBP", "libry" to "GBP", "liber" to "GBP",
        "chf" to "CHF", "frank" to "CHF", "franku" to "CHF", "franky" to "CHF",
        "pln" to "PLN", "zloty" to "PLN", "zlotych" to "PLN", "zlote" to "PLN",
        "czk" to "CZK", "kc" to "CZK", "koruna" to "CZK", "korun" to "CZK", "koruny" to "CZK",
        "huf" to "HUF", "forint" to "HUF", "forintu" to "HUF", "jpy" to "JPY", "jen" to "JPY", "jenu" to "JPY",
    )

    fun code(word: String): String? = word.lowercase().let { ALIAS[it] ?: if (Regex("^[a-z]{3}$").matches(it)) it.uppercase() else null }
}
