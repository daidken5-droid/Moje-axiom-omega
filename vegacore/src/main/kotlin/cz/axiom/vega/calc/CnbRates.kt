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

    data class Query(val value: Double, val from: String, val to: String, val word: String)

    /**
     * „100 eur na koruny“, „kolik je 100 euro v korunách“, „100 € v Kč“, „50 $ na Kč“ → dotaz na převod; null = není to převod.
     * (Rozpoznání je tady, aby šlo testovat bez Androidu; kurz stahuje telefon.)
     */
    fun query(text: String): Query? {
        val t = cz.axiom.vega.base.Text.fold(text).lowercase().trim().trimEnd('?', '.', '!', ' ')
            .replace("€", " eur ").replace("\$", " usd ").replace("£", " gbp ").replace(Regex("\\s+"), " ").trim()
        val cur = "(eur\\w*|usd|dolar\\w*|gbp|libr\\w*|liber|chf|frank\\w*|pln|zlot\\w*|huf|forint\\w*|jpy|jen\\w*|kc|korun\\w*|czk)"
        val m = Regex("^(?:kolik (?:je|dela|to je) |preved |prevod |spocitej |prepocitej )?(\\d[\\d ]*(?:[.,]\\d+)?)\\s*$cur\\s+(?:na|v|ve|do|je|=)\\s*$cur$").find(t) ?: return null
        val value = m.groupValues[1].replace(" ", "").replace(',', '.').toDoubleOrNull() ?: return null
        val from = code(m.groupValues[2]) ?: return null
        val to = code(m.groupValues[3]) ?: return null
        return if (from == to) null else Query(value, from, to, m.groupValues[2])
    }

    fun code(word: String): String? = word.lowercase().let { w -> ALIAS[w] ?: when {
        w.startsWith("eur") -> "EUR"; w.startsWith("dolar") -> "USD"; w.startsWith("libr") -> "GBP"; w.startsWith("korun") -> "CZK"
        w.startsWith("frank") -> "CHF"; w.startsWith("zlot") -> "PLN"; w.startsWith("forint") -> "HUF"; w.startsWith("jen") -> "JPY"
        Regex("^[a-z]{3}$").matches(w) -> w.uppercase(); else -> null } }
}
