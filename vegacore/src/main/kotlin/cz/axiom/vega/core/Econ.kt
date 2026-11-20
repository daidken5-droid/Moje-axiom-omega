package cz.axiom.vega.core

import cz.axiom.vega.base.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ROZHODUJÍCÍ VÝPOČTY V ROZHOVORU bez modelu: bod zvratu (cena, variabilní a fixní náklady, kusy), roční × měsíční
 * tarif a jednoduchý součin s jednotkou. Výsledek je první věta; chybný předpoklad uživatele se pojmenuje hned
 * a doloží rozhodujícím výpočtem. Parametry se ukládají k projektu (rozsah platnosti) a opravují se jednotlivě.
 */
object Econ {
    private fun f(s: String) = Text.fold(s).lowercase()
    private const val NUM = "(-?\\d{1,3}(?:[  .]\\d{3})+(?:,\\d+)?|-?\\d+(?:[,.]\\d+)?)"
    fun num(s: String): Double? = s.replace(Regex("[  ]"), "").let { t -> if (Regex("^-?\\d{1,3}(\\.\\d{3})+$").matches(t)) t.replace(".", "") else t }.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e12 }
    fun kc(v: Double): String = CzNum.format(v, if (v % 1.0 == 0.0) 0 else 2).replace(' ', ' ') + " Kč"
    fun plainKc(v: Double): String = CzNum.format(v, if (v % 1.0 == 0.0) 0 else 2).replace(' ', ' ')

    // ------------------------------------------------------------------ bod zvratu

    @Serializable data class Unit(val price: Double? = null, val variable: Double? = null, val fixed: Double? = null, val qty: Double? = null) {
        fun merge(o: Unit) = Unit(o.price ?: price, o.variable ?: variable, o.fixed ?: fixed, o.qty ?: qty)
        val complete get() = price != null && variable != null && fixed != null
        fun json() = Json.encodeToString(serializer(), this)
        companion object { fun parse(s: String) = runCatching { Json.decodeFromString(serializer(), s) }.getOrNull() }
    }

    private fun near(t: String, kw: String): Double? {
        Regex("(?:$kw)[^0-9\\n]{0,28}?$NUM").find(t)?.let { return num(it.groupValues[1]) }
        Regex("$NUM\\s*(?:kc|korun)?\\s*(?:na\\s+)?(?:$kw)").find(t)?.let { return num(it.groupValues[1]) }
        return null
    }

    /** Parametry z věty (jen ty, které v ní opravdu jsou – oprava „fixní náklady jsou 5 000“ změní jen fixní). */
    fun parseUnit(raw: String): Unit {
        // „za 1 kus“, „na 1 měsíc“, „za 12 měsíců“ jsou jednotky/období, ne hodnoty
        val t = f(raw).replace(Regex("\\b(za|na|pro)\\s+(1|jeden|jednu|jedno)\\s+(kus\\w*|ks|jednotk\\w*|mesic\\w*|rok\\w*)"), " ").replace(Regex("\\b(za|na)\\s+\\d+\\s+(mesic\\w*|let|rok\\w*|dn\\w*)"), " ")
        val variable = near(t, "variabiln\\w*(?: nakla\\w*)?|nakla\\w* na (?:kus|jednotku|vyrobek)|jednotkov\\w* nakla\\w*|(?:vyroba|vyrobeni|material|nakup|porizeni|suroviny)(?: na| jednoho| jedne)?(?: kus\\w*| vyrob\\w*| jednotk\\w*)?(?: me| mne| mi)? (?:stoji|vyjde|je|vychazi)(?: na)?|(?:jeden|jedna|jedno) (?:kus\\w* )?(?:me |mne |mi )?(?:stoji|vyjde|vychazi)(?: na)?")
        val fixed = near(t, "fixn\\w* nakla\\w*|fixn\\w*|fixy|pevn\\w* nakla\\w*|rezi\\w*")
        val price = near(t, "(?:prodejni |jednotkova )?cen[aeuy]\\w*|prodav\\w*(?: [a-z]+){0,3}? (?:za|po)|prodej\\w* (?:za|po)|(?:za kus|kus) prodavam")
        val qty = Regex("(?:po|pri|prodam|prodal\\w*|vyrobim|za)\\s+$NUM\\s*(?:ks|kus)").find(t)?.let { num(it.groupValues[1]) }
            ?: Regex("$NUM\\s*(?:ks|kus\\w*)").find(t)?.let { num(it.groupValues[1]) }
        return Unit(price, variable, fixed, qty)
    }

    fun isUnitQuestion(raw: String): Boolean { val u = parseUnit(raw); return listOfNotNull(u.price, u.variable, u.fixed).size >= 2 && (u.variable != null || u.fixed != null) }
    fun isUnitCorrection(raw: String): Boolean { val u = parseUnit(raw); return (u.price ?: u.variable ?: u.fixed ?: u.qty) != null && Regex("oprav|vlastne|ve skutecnosti|spletl|splet|preklep|maj[ii] byt|ma byt|spravne je|spravne jsou|^ne,|chyba|zmen\\w*|uprav").containsMatchIn(f(raw)) && Regex("cen|nakla|fix|variab|kus|ks\\b|rezi|material").containsMatchIn(f(raw)) }
    /** Naléhání bez nových dat („ale já jsem fakt v plusu“). */
    fun isInsisting(raw: String): Boolean { val t = f(raw); return !Regex("\\d").containsMatchIn(t) && Regex("jsem si jist|urcite|ale ja|fakt jsem|trvam|opravdu jsem|vim, ze|to neni pravda|nesouhlas|mas to spatne|jsem v plusu|vydelavam").containsMatchIn(t) }
    fun claim(raw: String): Int { val t = f(raw); return when { Regex("ve ztrate|prodelav|ztratov").containsMatchIn(t) -> -1; Regex("v plusu|vydelav|v zisku|ziskov|jsem nad vodou").containsMatchIn(t) -> 1; else -> 0 } }

    data class UnitResult(val profit: Double, val contribution: Double, val breakEven: Double?, val text: String)

    fun unitAnswer(u: Unit, claim: Int = 0, insisting: Boolean = false): UnitResult? {
        if (!u.complete) return null
        val price = u.price!!; val variable = u.variable!!; val fixed = u.fixed!!
        if (price < 0 || variable < 0 || fixed < 0 || (u.qty ?: 0.0) < 0) return UnitResult(Double.NaN, 0.0, null, "Cena, náklady i počet kusů musí být nezáporné – zkontroluj zadání.")
        // haléře: 0,3 − 0,1 = 0,19999… by jinak dalo „<“ u nulového zisku a falešný „zbytek“ u bodu zvratu
        fun r2(x: Double) = Math.round(x * 100) / 100.0
        val c = r2(price - variable)
        val bep = if (c > 0) (if (fixed > 0) maxOf(1.0, kotlin.math.ceil(fixed / c - 1e-9)) else 0.0) else null
        val q = u.qty
        val sb = StringBuilder()
        val profit = if (q != null) Math.round((q * c - fixed) * 100) / 100.0 else Double.NaN
        if (q != null) {
            val word = when { profit > 0 -> "zisk ${kc(profit)}"; profit < 0 -> "ztrátu ${kc(-profit)}"; else -> "nulový zisk (přesně bod zvratu)" }
            val wrong = claim == 1 && profit < 0 || claim == -1 && profit > 0
            sb.append("Při ${plainKc(q)} kusech máš $word").append(if (wrong) (if (claim == 1) " – nejsi v plusu." else " – nejsi ve ztrátě.") else ".")
            if (insisting) sb.append(" Bez nových čísel výsledek platí; změní ho jen jiná cena, jiné náklady nebo jiný počet kusů.")
            sb.append(" Každý kus přispěje ${kc(c)} na fixní náklady (${plainKc(price)} − ${plainKc(variable)}); ${plainKc(q)} × ${plainKc(c)} = ${kc(q * c)} ")
            sb.append(if (r2(q * c) >= r2(fixed)) "≥" else "<").append(" fixní náklady ${kc(fixed)}.")
        } else sb.append("Každý kus přispěje ${kc(c)} na fixní náklady ${kc(fixed)}.")
        when {
            bep == null -> sb.append(" Cena nepokryje ani variabilní náklad – bod zvratu neexistuje.")
            else -> sb.append(" Bod zvratu je ${plainKc(bep)} kusů").append(if (kotlin.math.abs(fixed / c - Math.rint(fixed / c)) > 1e-9) " (${plainKc(fixed / c)} → první celý kus v plusu)." else ".")
        }
        return UnitResult(profit, c, bep, sb.toString())
    }

    /** Tabulka zisku pro zadané počty kusů (české CSV: středník, desetinná čárka). */
    fun unitTable(u: Unit, qtys: List<Double>): Value.Table {
        val price = u.price!!; val variable = u.variable!!; val fixed = u.fixed!!; val c = price - variable
        fun n(v: Double) = String.format(java.util.Locale.ROOT, if (v % 1.0 == 0.0) "%.0f" else "%.2f", v).replace('.', ',')
        return Value.Table(listOf("Kusů", "Tržby (Kč)", "Variabilní náklady (Kč)", "Fixní náklady (Kč)", "Zisk (Kč)"),
            qtys.map { q -> listOf(n(q), n(q * price), n(q * variable), n(fixed), n(q * c - fixed)) }, name = "zisk-podle-kusu")
    }

    fun qtyList(raw: String): List<Double> = Regex("$NUM").findAll(f(raw).substringAfter(" pro ", f(raw)).substringBefore("kus")).mapNotNull { num(it.value) }.filter { it >= 0 && it < 1e9 }.toList()
    fun isTableRequest(raw: String) = Regex("csv|tabulk|excel|soubor").containsMatchIn(f(raw)) && Regex("kus").containsMatchIn(f(raw)) && qtyList(raw).size >= 2

    // ------------------------------------------------------------------ tarif: roční × měsíční

    @Serializable data class Tariff(val yearly: Double, val monthly: Double, val months: Double? = null, val refundable: Boolean = false, val monthlyMin: Double? = null) {
        fun json() = Json.encodeToString(serializer(), this)
        companion object { fun parse(s: String) = runCatching { Json.decodeFromString(serializer(), s) }.getOrNull() }
    }

    fun parseTariff(raw: String, prev: Tariff? = null): Tariff? {
        val t = f(raw)
        val y = near(t, "rocni\\w*(?: tarif| predplatne| platba| licence| varianta)?|za rok|rocne") ?: prev?.yearly
        val m = Regex("$NUM\\s*(?:kc)?\\s*(?:mesicne|za mesic|/\\s*mesic(?!n)|mesicni)").find(t)?.let { num(it.groupValues[1]) } ?: near(t, "mesicni\\w*(?: tarif| platba| varianta)?|mesicne") ?: prev?.monthly
        if (y == null || m == null) return null
        val months = Regex("(?:budu (?:ho |to )?(?:pouzivat|platit|potrebovat)|na|po dobu|pouziju)\\s+$NUM\\s*mesic").find(t)?.let { num(it.groupValues[1]) }
            ?: Regex("$NUM\\s*mesic\\w* (?:pouziti|vyuziti|pouzivani)").find(t)?.let { num(it.groupValues[1]) } ?: prev?.months
        val refundable = when { Regex("(?:rocni|rocne).{0,40}(?:vrat|refund|pomern|zrusit kdykoli)|vraci pomern|pomernou cast").containsMatchIn(t) && !Regex("nevrat|nejde vratit|bez vraceni").containsMatchIn(t) -> true
            Regex("nevratn|nejde vratit|bez vraceni").containsMatchIn(t) -> false; else -> prev?.refundable ?: false }
        val minM = Regex("(?:mesicni|mesicne).{0,40}(?:zavazek|minimalne|nejmene|vypovedn\\w* lhut\\w*)\\D{0,12}$NUM").find(t)?.let { num(it.groupValues[1]) } ?: prev?.monthlyMin
        return Tariff(y, m, months, refundable, minM)
    }
    fun isTariffQuestion(raw: String) = Regex("rocni|za rok|rocne").containsMatchIn(f(raw)) && Regex("mesic").containsMatchIn(f(raw)) && Regex("\\d").containsMatchIn(raw)

    private fun costMonthly(t: Tariff, n: Double) = maxOf(n, t.monthlyMin ?: 0.0) * t.monthly
    /** Roční tarif na [n] měsíců: nevratný = každý započatý rok celý; vratný = poměrná část (po letech i zbytek). */
    private fun costYearly(t: Tariff, n: Double) = if (t.refundable) t.yearly * n / 12.0 else t.yearly * kotlin.math.ceil(n / 12.0 - 1e-9).coerceAtLeast(1.0)

    fun tariffAnswer(t: Tariff): String {
        val sb = StringBuilder()
        val cond = buildList { add(if (t.refundable) "roční jde vrátit poměrnou částí" else "roční platba je nevratná"); t.monthlyMin?.let { add("měsíční má závazek ${plainKc(it)} měsíců") }; add("stejné služby") }.joinToString(", ")
        val n = t.months
        if (n != null) {
            val a = costMonthly(t, n); val b = costYearly(t, n)
            sb.append(when { a < b -> "Při ${plainKc(n)} měsících vyjde měsíční varianta o ${kc(b - a)} levněji"; b < a -> "Při ${plainKc(n)} měsících vyjde roční tarif o ${kc(a - b)} levněji"; else -> "Při ${plainKc(n)} měsících vyjdou obě varianty stejně" })
                .append(" (měsíčně ${kc(a)}, ročně ${kc(b)}; $cond).")
            return sb.toString()
        }
        if (t.refundable) {
            val per = t.yearly / 12
            return if (per < t.monthly) "Roční tarif vychází levněji při jakékoli délce využití: s vrácením poměrné části platíš ${kc(per)} za měsíc místo ${kc(t.monthly)}" + (t.monthlyMin?.let { " (a měsíční má navíc závazek ${plainKc(it)} měsíců)" } ?: "") + "."
            else "Měsíční varianta vychází levněji nebo stejně: roční s vrácením stojí ${kc(per)} za měsíc, měsíční ${kc(t.monthly)}."
        }
        if (t.monthly <= 0 || t.yearly <= 0) return "Ceny tarifu musí být kladné – napiš roční i měsíční cenu."
        val th = kotlin.math.ceil(t.yearly / t.monthly - 1e-9)
        val minNote = t.monthlyMin?.let { m -> if (m >= th) " Měsíční má ale závazek ${plainKc(m)} měsíců – zaplatíš aspoň ${kc(m * t.monthly)}, takže roční je při tomto závazku vždy levnější nebo stejný." else " Měsíční má závazek ${plainKc(m)} měsíců – kratší využití se nevyplatí ani tak." } ?: ""
        sb.append("Roční tarif se vyplatí, pokud budeš platit aspoň ${plainKc(th)} měsíců: ${plainKc(th)} × ${plainKc(t.monthly)} = ${kc(th * t.monthly)} ${if (th * t.monthly > t.yearly) ">" else "≥"} ${kc(t.yearly)} ($cond).")
        val lo = th - 2; val hi = 12.0
        if (lo > 0) { val d = t.yearly - costMonthly(t, lo); if (d > 0) sb.append(" Při ${plainKc(lo)} měsících vyjde měsíční varianta o ${kc(d)} levněji") }
        val d2 = costMonthly(t, hi) - t.yearly; if (d2 > 0) sb.append(if (lo > 0) ", při ${plainKc(hi)} měsících roční o ${kc(d2)} levněji." else " Při ${plainKc(hi)} měsících vyjde roční o ${kc(d2)} levněji.")
        sb.append(minNote)
        sb.append("\n\nKolik měsíců ho budeš opravdu používat? Podle toho řeknu jednoznačně.")
        return sb.toString()
    }

    // ------------------------------------------------------------------ jednoduchý součin s jednotkou

    /** „Kolik je 12 × 250 Kč?“ → „3 000 Kč“ (jednotka zůstane). */
    fun simpleProduct(raw: String): String? {
        val t = f(raw)
        val m = Regex("^(?:kolik je|kolik dela|spocitej|vypocitej)?\\s*$NUM\\s*(kc|korun|eur|€|\\$|usd)?\\s*[x×*·]\\s*$NUM\\s*(kc|korun|eur|€|\\$|usd|ks|kusu)?\\s*\\??$").find(t.trim()) ?: return null
        val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[3]) ?: return null
        val unit = (m.groupValues[2].ifBlank { m.groupValues[4] }).let { when (it) { "kc", "korun" -> " Kč"; "eur", "€" -> " €"; "$", "usd" -> " USD"; else -> "" } }
        return CzNum.format(a * b, if ((a * b) % 1.0 == 0.0) 0 else 2).replace(' ', ' ') + unit
    }
}
