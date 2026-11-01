package cz.axiom.omega.core.step0

import cz.axiom.omega.core.util.TextUtil

/** Převody jednotek (DNA z AXIOM Titanium) + měny přes [Rates]. */
object Units {

    private val length = mapOf(
        "mm" to 0.001, "cm" to 0.01, "dm" to 0.1, "m" to 1.0, "km" to 1000.0,
        "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344,
    )
    private val area = mapOf("mm2" to 1e-6, "cm2" to 1e-4, "m2" to 1.0, "ha" to 10_000.0, "km2" to 1e6, "ft2" to 0.09290304)
    private val volume = mapOf("ml" to 0.001, "l" to 1.0, "m3" to 1000.0, "gal" to 3.785411784)
    private val weight = mapOf(
        "mg" to 0.001, "g" to 1.0, "kg" to 1000.0, "t" to 1_000_000.0,
        "lb" to 453.59237, "oz" to 28.349523125,
    )
    private val speed = mapOf("kmh" to 1.0, "km/h" to 1.0, "mph" to 1.609344, "ms" to 3.6, "m/s" to 3.6, "kn" to 1.852)
    private val power = mapOf("w" to 1.0, "kw" to 1000.0, "hp" to 745.69987, "ps" to 735.49875, "k" to 735.49875)
    private val torque = mapOf("nm" to 1.0, "lbft" to 1.3558179483)
    private val pressure = mapOf("pa" to 1.0, "kpa" to 1000.0, "bar" to 100_000.0, "psi" to 6894.757293, "atm" to 101_325.0)
    private val energy = mapOf("j" to 1.0, "kj" to 1000.0, "kwh" to 3_600_000.0, "kcal" to 4184.0, "cal" to 4.184)

    private val groups = listOf(length, area, volume, weight, speed, power, torque, pressure, energy)
    private val currencies = setOf("czk", "eur", "usd", "jpy", "gbp", "chf", "pln", "kč", "kc")

    fun convert(value: Double, fromUnit: String, toUnit: String, rates: Rates = Rates.FALLBACK): Result<Double> = runCatching {
        val f = canon(fromUnit)
        val t = canon(toUnit)
        if (f == t) return@runCatching value
        if (f in setOf("c", "°c") && t in setOf("f", "°f")) return@runCatching value * 9.0 / 5.0 + 32.0
        if (f in setOf("f", "°f") && t in setOf("c", "°c")) return@runCatching (value - 32.0) * 5.0 / 9.0
        if (f in setOf("c", "°c") && t == "k") return@runCatching value + 273.15
        if (f == "k" && t in setOf("c", "°c")) return@runCatching value - 273.15
        for (g in groups) {
            if (f in g && t in g) return@runCatching value * g.getValue(f) / g.getValue(t)
        }
        if (f in currencies && t in currencies) {
            val fr = rates.of(currencyCode(f)) ?: error("Chybí kurz ${currencyCode(f)}")
            val tr = rates.of(currencyCode(t)) ?: error("Chybí kurz ${currencyCode(t)}")
            return@runCatching value * fr / tr
        }
        throw IllegalArgumentException("Nepodporovaný převod: $fromUnit → $toUnit")
    }

    private fun canon(u: String): String = u.trim().lowercase().replace("²", "2").replace("³", "3")
        .let { if (it == "kc" || it == "kč") "czk" else it }

    private fun currencyCode(u: String) = canon(u).uppercase()

    private const val UNIT =
        "km/h|m/s|kwh|kcal|lbft|km2|cm2|mm2|ft2|m2|m3|mph|kpa|bar|psi|atm|gal|kw|hp|ps|nm|mm|cm|dm|km|in|ft|yd|mi|ml|mg|kg|lb|oz|ha|kj|°c|°f|czk|eur|usd|jpy|gbp|chf|pln|kč|g|t|l|m|c|f|w|j"

    private val regex = Regex(
        """(\d+(?:[.,]\d+)?)\s*($UNIT)\s*(?:na|do|to|->|→|v|ve|=>)\s*($UNIT)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )

    fun scan(text: String, rates: Rates = Rates.FALLBACK): List<String> =
        regex.findAll(text).mapNotNull { m ->
            val v = TextUtil.parseNumber(m.groupValues[1]) ?: return@mapNotNull null
            val from = m.groupValues[2]
            val to = m.groupValues[3]
            convert(v, from, to, rates).getOrNull()?.let { r ->
                val note = if (canon(from) in currencies || canon(to) in currencies) " (kurz ${rates.label()})" else ""
                "${TextUtil.num(v)} ${from.uppercase()} = ${TextUtil.num(r, 4)} ${to.uppercase()}$note"
            }
        }.toList()
}
