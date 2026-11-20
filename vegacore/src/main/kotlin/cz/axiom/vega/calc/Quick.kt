package cz.axiom.vega.calc

import cz.axiom.vega.base.Text
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * RYCHLÉ ODPOVĚDI BEZ MODELU (přesně, offline): aritmetika s českým zápisem čísel, procenta, odmocnina, převody
 * jednotek, počet dní mezi daty, čas a datum. Jen když si je odpověď jistá – jinak null (rozhodne model nebo nic).
 */
object Quick {
    private fun fmt(v: Double): String {
        val r = Math.round(v * 1e6) / 1e6
        val s = if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) String.format(java.util.Locale.ROOT, "%.0f", r) else String.format(java.util.Locale.ROOT, "%.6f", r).trimEnd('0').trimEnd('.')
        val neg = s.startsWith("-"); val (i, f) = s.removePrefix("-").split('.').let { it[0] to it.getOrNull(1) }
        return (if (neg) "−" else "") + i.reversed().chunked(3).joinToString(" ").reversed() + (f?.let { ",$it" } ?: "")
    }

    fun answer(raw: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('?', '.', '!', ' ')
        if (Regex("^(kolik je hodin|kolik je cas|jaky je cas)$").matches(t)) return "Je %02d:%02d.".format(now.hour, now.minute)
        if (Regex("^(jake je )?(dnes|dneska) (je )?(datum|za den)?$|^jake je (dnes )?datum$|^kolikateho je( dnes)?$|^jaky (je )?dnes den$|^jaky den je dnes$").matches(t))
            return "Dnes je ${now.dayOfMonth}. ${now.monthValue}. ${now.year}."
        days(t, now.toLocalDate())?.let { return it }
        vat(t)?.let { return it }
        percent(t)?.let { return it }
        dateOffset(t, now.toLocalDate())?.let { return it }
        weekday(t)?.let { return it }
        age(t, now.toLocalDate())?.let { return it }
        convert(t)?.let { return it }
        Regex("^(?:kolik je |spocitej |vypocitej )?(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:z|ze)\\s*(\\d[\\d ]*(?:[.,]\\d+)?)$").find(t)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null; return "${fmt(p)} % z ${fmt(b)} je ${fmt(b * p / 100)}." }
        Regex("^(?:kolik je |spocitej )?(?:druha )?odmocnin[ay] (?:z |ze )?(\\d+(?:[.,]\\d+)?)$").find(t)?.let { m -> val v = num(m.groupValues[1]) ?: return null; return "√${fmt(v)} = ${fmt(Math.sqrt(v))}" }
        val expr = t.replace(Regex("^(kolik je|kolik dela|spocitej|vypocitej|vysledek)\\s+"), "").trim()
        if (Regex("^[\\d\\s.,+\\-*/×·:÷^()%x]+$").matches(expr) && Regex("\\d\\s*[+\\-*/×·:÷^x]\\s*[\\d(]").containsMatchIn(expr)) {
            val v = runCatching { Expr(expr).parse() }.getOrNull() ?: return null
            if (v.isNaN() || v.isInfinite()) return null
            return "${raw.trim().replace(Regex("(?i)^(kolik je|kolik dělá|spočítej|vypočítej)\\s+"), "").trimEnd('?', ' ')} = ${fmt(v)}"
        }
        return null
    }

    private const val N = "(\\d[\\d ]*(?:[.,]\\d+)?)"
    private fun money(v: Double) = fmt(Math.round(v * 100) / 100.0) + " Kč"
    private val DAYS = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun dmy(d: LocalDate) = "${d.dayOfMonth}. ${d.monthValue}. ${d.year} (${DAYS[d.dayOfWeek.value - 1]})"

    /** DPH: „1000 bez DPH“, „1 210 s DPH“, „DPH z 1000“, sazba 21 % (nebo 12 % / 0 %, když je v dotazu). */
    private fun vat(t: String): String? {
        if (!t.contains("dph")) return null
        val rate = Regex("(?<!\\d)(21|12|0)\\s*%").find(t)?.groupValues?.get(1)?.toDouble() ?: 21.0
        val tx = t.replace(Regex("(?<!\\d)(21|12|0)\\s*%\\s*(dph)?"), " ").replace(Regex("\\s+"), " ")
        Regex("^(?:kolik (?:je|cini) )?dph (?:z|ze|u) $N(?: kc)?$").find(tx.trim())?.let { m -> val b = num(m.groupValues[1]) ?: return null
            return "DPH ${fmt(rate)} % z ${money(b)} je ${money(b * rate / 100)}; s DPH ${money(b * (1 + rate / 100))}." }
        Regex("$N\\s*(?:kc )?(?:bez dph|\\+ ?dph)").find(tx)?.let { m -> val b = num(m.groupValues[1]) ?: return null
            return "${money(b)} bez DPH = ${money(b * (1 + rate / 100))} s DPH ${fmt(rate)} % (DPH ${money(b * rate / 100)})." }
        Regex("$N\\s*(?:kc )?(?:s dph|vcetne dph|vc\\. dph)").find(tx)?.let { m -> val g = num(m.groupValues[1]) ?: return null
            val net = g / (1 + rate / 100); return "${money(g)} s DPH ${fmt(rate)} % = ${money(net)} bez DPH (DPH ${money(g - net)})." }
        return null
    }

    /** Procenta: „o kolik % je 120 víc než 100“, „změna z 100 na 120 v %“, „kolik procent je 30 ze 120“. */
    private fun percent(t: String): String? {
        if (!Regex("%|procent").containsMatchIn(t)) return null
        Regex("^(?:zmena |o kolik (?:%|procent)[a-z ]{0,20} )?z $N na $N(?: (?:je|to|v|o kolik|kolik)[a-z %]*)?$").find(t)?.let { m -> val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null
            if (a == 0.0) return null; val p = (b - a) / a * 100
            return "Z ${fmt(a)} na ${fmt(b)} je to ${if (p >= 0) "nárůst" else "pokles"} o ${fmt(Math.round(kotlin.math.abs(p) * 100) / 100.0)} %." }
        Regex("o kolik (?:%|procent) je $N (vic|vice|min|mene) nez $N").find(t)?.let { m -> val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[3]) ?: return null
            if (b == 0.0) return null; val p = (a - b) / b * 100
            return "${fmt(a)} je o ${fmt(Math.round(kotlin.math.abs(p) * 100) / 100.0)} % ${if (p >= 0) "víc" else "míň"} než ${fmt(b)}." }
        Regex("kolik (?:%|procent) (?:je|tvori|dela) $N (?:z|ze) $N").find(t)?.let { m -> val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null
            if (b == 0.0) return null; return "${fmt(a)} je ${fmt(Math.round(a / b * 10000) / 100.0)} % z ${fmt(b)}." }
        return null
    }

    /** „za 30 dní“, „co bude za 3 týdny“, „před 10 dny“, „za 2 měsíce“ → datum a den v týdnu. */
    private fun dateOffset(t: String, today: LocalDate): String? {
        val m = Regex("^(?:jake datum bude |jaky den bude |co bude |kdy bude |jake bude datum )?(za|pred) (\\d{1,4}) (dn\\w*|den|tyd\\w*|mesic\\w*|rok\\w*|let)$").find(t) ?: return null
        val n = m.groupValues[2].toLong(); val sign = if (m.groupValues[1] == "za") 1 else -1; val u = m.groupValues[3]
        val d = when { u.startsWith("d") -> today.plusDays(sign * n); u.startsWith("t") -> today.plusWeeks(sign * n); u.startsWith("m") -> today.plusMonths(sign * n); else -> today.plusYears(sign * n) }
        fun cz(one: String, few: String, many: String, inst: String) = if (sign < 0) inst else when (n) { 1L -> one; in 2..4 -> few; else -> many }
        val unit = when { u.startsWith("d") -> cz("den", "dny", "dní", if (n == 1L) "dnem" else "dny"); u.startsWith("t") -> cz("týden", "týdny", "týdnů", if (n == 1L) "týdnem" else "týdny")
            u.startsWith("m") -> cz("měsíc", "měsíce", "měsíců", if (n == 1L) "měsícem" else "měsíci"); else -> cz("rok", "roky", "let", if (n == 1L) "rokem" else "lety") }
        return "${if (sign > 0) "Za" else "Před"} $n $unit ${if (sign > 0) "bude" else "bylo"} ${dmy(d)}."
    }

    /** „jaký den v týdnu je 24. 12. 2026“, „na co připadá 1. 1. 2027“. */
    private fun weekday(t: String): String? {
        if (!Regex("^(jaky den v tydnu (je|byl|bude)|na (co|jaky den) pripada|jaky den (je|byl|bude)) ").containsMatchIn(t)) return null
        val d = date(t) ?: return null
        return "${d.dayOfMonth}. ${d.monthValue}. ${d.year} je ${DAYS[d.dayOfWeek.value - 1]}."
    }

    /** „kolik let je od 5. 3. 1990“, „kolik mi je, když jsem se narodil 5. 3. 1990“. */
    private fun age(t: String, today: LocalDate): String? {
        if (!Regex("^(kolik (mi |mu |ji |mu bylo |je )?(je )?let|kolik let (je |uplynulo )?od|jaky (je )?vek|kolik je mi|kolik mi bude)(?![a-z])").containsMatchIn(t)) return null
        val d = date(t)?.takeIf { it.isBefore(today) } ?: return null
        val y = java.time.Period.between(d, today).years
        val next = d.withYear(today.year).let { if (!it.isAfter(today)) it.plusYears(1) else it }
        return "Od ${d.dayOfMonth}. ${d.monthValue}. ${d.year} uplynulo $y ${if (y == 1) "rok" else if (y in 2..4) "roky" else "let"}; další výročí ${dmy(next)} (za ${ChronoUnit.DAYS.between(today, next)} dní)."
    }

    private fun num(s: String) = s.replace(" ", "").replace(',', '.').toDoubleOrNull()

    private val UNITS = mapOf(
        "km" to ("m" to 1000.0), "m" to ("m" to 1.0), "cm" to ("m" to 0.01), "mm" to ("m" to 0.001), "mi" to ("m" to 1609.344), "mile" to ("m" to 1609.344), "mil" to ("m" to 1609.344),
        "ft" to ("m" to 0.3048), "stop" to ("m" to 0.3048), "in" to ("m" to 0.0254), "palcu" to ("m" to 0.0254), "palec" to ("m" to 0.0254),
        "kg" to ("kg" to 1.0), "g" to ("kg" to 0.001), "t" to ("kg" to 1000.0), "lb" to ("kg" to 0.45359237), "liber" to ("kg" to 0.45359237), "oz" to ("kg" to 0.028349523125), "unci" to ("kg" to 0.028349523125),
        "l" to ("l" to 1.0), "ml" to ("l" to 0.001), "gal" to ("l" to 3.785411784), "galonu" to ("l" to 3.785411784),
        "km/h" to ("m/s" to 1 / 3.6), "m/s" to ("m/s" to 1.0), "mph" to ("m/s" to 0.44704),
        // plocha
        "m2" to ("m2" to 1.0), "km2" to ("m2" to 1e6), "ha" to ("m2" to 1e4), "ar" to ("m2" to 100.0), "aru" to ("m2" to 100.0), "cm2" to ("m2" to 1e-4), "ft2" to ("m2" to 0.09290304), "akr" to ("m2" to 4046.8564224), "akru" to ("m2" to 4046.8564224),
        // objem v kuchyni
        "dl" to ("l" to 0.1), "cl" to ("l" to 0.01), "hrnek" to ("l" to 0.24), "hrnku" to ("l" to 0.24), "hrnky" to ("l" to 0.24), "cup" to ("l" to 0.24), "cups" to ("l" to 0.24),
        "lzice" to ("l" to 0.015), "lzic" to ("l" to 0.015), "lzicka" to ("l" to 0.005), "lzicek" to ("l" to 0.005), "m3" to ("l" to 1000.0),
        // data (desítkové jako výrobci disků; KiB… binární)
        "b" to ("B" to 1.0), "kb" to ("B" to 1e3), "mb" to ("B" to 1e6), "gb" to ("B" to 1e9), "tb" to ("B" to 1e12), "kib" to ("B" to 1024.0), "mib" to ("B" to 1048576.0), "gib" to ("B" to 1073741824.0), "tib" to ("B" to 1099511627776.0),
        // čas
        "s" to ("s" to 1.0), "sekund" to ("s" to 1.0), "min" to ("s" to 60.0), "minut" to ("s" to 60.0), "h" to ("s" to 3600.0), "hod" to ("s" to 3600.0), "hodin" to ("s" to 3600.0), "den" to ("s" to 86400.0), "dni" to ("s" to 86400.0), "dnu" to ("s" to 86400.0), "tydnu" to ("s" to 604800.0),
        // energie
        "kcal" to ("J" to 4184.0), "kj" to ("J" to 1000.0), "j" to ("J" to 1.0), "kwh" to ("J" to 3.6e6), "wh" to ("J" to 3600.0))
    private fun convert(t: String): String? {
        val m = Regex("^(?:preved |kolik je |prevod )?(-?\\d+(?:[.,]\\d+)?)\\s*(°?c|°?f|[a-z/]+[23]?)\\s+(?:na|do|v|=|->)\\s+(°?c|°?f|[a-z/]+[23]?)$").find(t.replace("²", "2").replace("³", "3")) ?: return null
        val v = num(m.groupValues[1]) ?: return null; val a = m.groupValues[2].removePrefix("°"); val b = m.groupValues[3].removePrefix("°")
        if (a in setOf("c", "f") && b in setOf("c", "f") && a != b) {
            val r = if (a == "c") v * 9 / 5 + 32 else (v - 32) * 5 / 9
            return "${fmt(v)} °${a.uppercase()} = ${fmt(Math.round(r * 100) / 100.0)} °${b.uppercase()}"
        }
        val ua = UNITS[a] ?: return null; val ub = UNITS[b] ?: return null
        if (ua.first != ub.first) return null
        val r = v * ua.second / ub.second
        return "${fmt(v)} ${m.groupValues[2]} = ${fmt(Math.round(r * 10000) / 10000.0)} ${m.groupValues[3]}"
    }

    private fun date(s: String): LocalDate? = Regex("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})").find(s)?.let { runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull() }
    private fun days(t: String, today: LocalDate): String? {
        val m = Regex("kolik (?:je )?dni (?:od|mezi) (.+?) (?:do|a) (.+)$").find(t) ?: return null
        val a = date(m.groupValues[1]) ?: (if (m.groupValues[1].startsWith("dnes")) today else null) ?: return null
        val b = date(m.groupValues[2]) ?: (if (m.groupValues[2].startsWith("dnes")) today else null) ?: return null
        return "Mezi ${a.dayOfMonth}. ${a.monthValue}. ${a.year} a ${b.dayOfMonth}. ${b.monthValue}. ${b.year} je ${kotlin.math.abs(ChronoUnit.DAYS.between(a, b))} dní."
    }

    /** Rekurzivní parser výrazu: + − × ÷ ^ % a závorky; české desetinné čárky a mezery v tisících. */
    private class Expr(src: String) {
        private val s = src.replace("×", "*").replace("·", "*").replace("x", "*").replace(":", "/").replace("÷", "/")
            .replace(Regex("(?<=\\d) (?=\\d{3}(\\D|$))"), "").replace(" ", "").replace(Regex("(?<=\\d),(?=\\d)"), ".")
        private var i = 0
        fun parse(): Double { val v = sum(); require(i == s.length); return v }
        private fun sum(): Double { var v = prod(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = prod(); v = if (op == '+') v + r else v - r }; return v }
        private fun prod(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = pow(); v = if (op == '*') v * r else v / r }; return v }
        private fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = sum(); require(i < s.length && s[i] == ')'); i++; return pct(v) }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > st); return pct(s.substring(st, i).toDouble())
        }
        private fun pct(v: Double) = if (i < s.length && s[i] == '%') { i++; v / 100 } else v
    }
}
