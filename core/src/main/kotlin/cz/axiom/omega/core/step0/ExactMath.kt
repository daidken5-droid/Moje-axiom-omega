package cz.axiom.omega.core.step0

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.util.TextUtil
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HYPERFORGE M4 – EXACT ENGINE bez mozku: statistika, teorie čísel, geometrie, číselné soustavy, datové jednotky, BMI
 * a osobní finance s VÝSLOVNÝMI vstupy. Každá odpověď = vstupy + výsledek + postup (vzorec) + předpoklady.
 * Zásada: nejednoznačný vstup (desetinná čárka × oddělovač, chybějící sazba) → zdržet se / říct, co chybí. Nic se nedosazuje.
 * Limity: seznam ≤ 200 čísel, prvočísla do 10¹², faktoriál do 200, kombinace do n = 1000 → žádná zbytečná zátěž.
 */
object ExactMath {
    private const val CONF = 97

    private fun exact(text: String, kind: String, steps: String, assumptions: List<String> = emptyList()) = Instant.Answer(
        "$text\n\n_Postup: ${steps}${if (assumptions.isNotEmpty()) " · Předpoklady: " + assumptions.joinToString("; ") else ""}_", kind, confidence = CONF,
        contract = AnswerContract(AnswerContract.Type.EXACT, "deterministický výpočet ($kind)", listOf("vzorec: $steps"), assumptions = assumptions),
    )

    /** ABSTENCE: jistota 50 = pod prahem Flash (70) i Auto (85) → dotaz převezme mozek / zdroj; text slouží jako nápověda. */
    private fun abstain(text: String, kind: String) = Instant.Answer(text, kind, confidence = 50,
        contract = AnswerContract(AnswerContract.Type.ABSTAIN, "chybí nebo je nejednoznačný vstup ($kind)"))

    fun fmt(v: Double, dec: Int = 4): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val r = Math.round(v * 10.0.pow(dec)) / 10.0.pow(dec)
        val s = if (r == Math.rint(r) && abs(r) < 1e15) "%.0f".format(r) else "%.${dec}f".format(java.util.Locale.ROOT, r).trimEnd('0').trimEnd('.')
        val neg = s.startsWith("-"); val body = s.removePrefix("-")
        val int = body.substringBefore('.'); val frac = body.substringAfter('.', "")
        val grouped = int.reversed().chunked(3).joinToString(" ").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ",$frac" else ""
    }

    // ------------------------------------------------------------------ seznam čísel (desetinná čárka × oddělovač)
    /** „4, 8, 15“ / „2,5; 3,1“ / „3 1 4 1 5“. „4,8,15“ (čárka bez mezery mezi celými čísly) je nejednoznačné → null. */
    fun numbers(s: String): List<Double>? {
        val t = s.trim().trimEnd('?', '.', '!')
        if (t.isBlank()) return null
        val tokens = if (';' in t) t.split(';') else t.split(Regex(",\\s+|\\s+"))
        val out = ArrayList<Double>()
        for (raw in tokens) {
            val x = raw.trim().trim(',')
            if (x.isEmpty()) continue
            if (x.count { it == ',' } > 1) return null
            if (Regex("^-?\\d+,\\d{3}$").matches(x) && ';' !in t) return null // „1,500“ = 1,5 nebo 1500? → zdržet se
            out += x.replace(',', '.').toDoubleOrNull() ?: return null
        }
        return out.takeIf { it.size in 2..200 }
    }

    private val statRx = Regex("^(?:(?:spocitej|vypocitej|urci|jaky je|jaka je|kolik je)\\s+)?(aritmeticky prumer|prumer|median|modus|rozptyl|smerodatn\\w* odchylk\\w*|smerodatnou odchylku|soucet|minimum|maximum|rozpeti)\\s*(?:cisel|hodnot|z|ze|pro|mezi)?\\s*:?\\s*([-\\d][\\d\\s,;.\\-]*)$")

    private fun stats(f: String): Instant.Answer? {
        val m = statRx.find(f) ?: return null
        val xs = numbers(m.groupValues[2]) ?: return if (Regex("\\d,\\d").containsMatchIn(m.groupValues[2]))
            abstain("Nevím jistě, kde končí jedno číslo a začíná další (desetinná čárka × oddělovač). Odděl čísla středníkem („2,5; 3,1; 4“) nebo čárkou s mezerou („4, 8, 15“).", "statistika") else null
        val n = xs.size; val sum = xs.sum(); val mean = sum / n
        val sorted = xs.sorted()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val ss = xs.sumOf { (it - mean) * (it - mean) }
        val list = "n = $n (${xs.take(12).joinToString("; ") { fmt(it) }}${if (n > 12) "; …" else ""})"
        val what = m.groupValues[1]
        return when {
            what.startsWith("aritmeticky") || what == "prumer" -> exact("Aritmetický průměr = **${fmt(mean)}**", "statistika", "součet ${fmt(sum)} ÷ n; $list")
            what == "median" -> exact("Medián = **${fmt(median)}**", "statistika", "seřazeno, ${if (n % 2 == 1) "prostřední hodnota" else "průměr dvou prostředních"}; $list")
            what == "modus" -> {
                val freq = xs.groupingBy { it }.eachCount(); val top = freq.values.max()
                if (top == 1) exact("Modus **neexistuje** – každá hodnota je v seznamu jen jednou.", "statistika", list)
                else exact("Modus = **${freq.filterValues { it == top }.keys.sorted().joinToString(", ") { fmt(it) }}** (${top}×)", "statistika", "nejčastější hodnota; $list")
            }
            what == "rozptyl" -> exact("Rozptyl: populační σ² = **${fmt(ss / n)}**, výběrový s² = **${fmt(ss / (n - 1))}**", "statistika", "Σ(x−x̄)² = ${fmt(ss)}; σ² = Σ/n, s² = Σ/(n−1); $list")
            what.startsWith("smerodatn") -> exact("Směrodatná odchylka: populační σ = **${fmt(sqrt(ss / n))}**, výběrová s = **${fmt(sqrt(ss / (n - 1)))}**", "statistika", "průměr ${fmt(mean)}; Σ(x−x̄)² = ${fmt(ss)}; σ = √(Σ/n), s = √(Σ/(n−1)); $list")
            what == "soucet" -> exact("Součet = **${fmt(sum)}**", "statistika", list)
            what == "minimum" -> exact("Minimum = **${fmt(sorted.first())}**", "statistika", list)
            what == "maximum" -> exact("Maximum = **${fmt(sorted.last())}**", "statistika", list)
            what == "rozpeti" -> exact("Rozpětí = **${fmt(sorted.last() - sorted.first())}**", "statistika", "max ${fmt(sorted.last())} − min ${fmt(sorted.first())}; $list")
            else -> null
        }
    }

    // ------------------------------------------------------------------ teorie čísel
    private fun isPrime(n: Long): Boolean {
        if (n < 2) return false
        if (n < 4) return true
        if (n % 2 == 0L || n % 3 == 0L) return false
        var i = 5L
        while (i * i <= n) { if (n % i == 0L || n % (i + 2) == 0L) return false; i += 6 }
        return true
    }

    private fun factor(n0: Long): List<Long> {
        var n = n0; val out = ArrayList<Long>(); var p = 2L
        while (p * p <= n) { while (n % p == 0L) { out += p; n /= p }; p += if (p == 2L) 1 else 2 }
        if (n > 1) out += n
        return out
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcd(b, a % b)
    private val LIMIT = 1_000_000_000_000L
    private fun int(s: String) = s.replace(Regex("[\\s\\u00a0]"), "").toLongOrNull()

    private fun numberTheory(f: String): Instant.Answer? {
        Regex("^(?:je|jestli je|zda je)\\s+(\\d[\\d ]{0,15})\\s+prvocisl\\w*$").find(f)?.let { m ->
            val n = int(m.groupValues[1]) ?: return null; if (n > LIMIT) return null
            val fs = factor(n)
            return if (isPrime(n)) exact("Ano, **${fmt(n.toDouble())} je prvočíslo**.", "prvočísla", "zkusmé dělení do √n = ${fmt(sqrt(n.toDouble()), 1)}")
            else exact("Ne, ${fmt(n.toDouble())} **není prvočíslo**${if (n >= 2) " – ${fs.joinToString(" × ")}" else ""}.", "prvočísla", if (n < 2) "prvočíslo je ≥ 2" else "nejmenší dělitel ${fs.first()}")
        }
        Regex("^(?:rozloz|rozklad|prvocinitele|rozloz na prvocinitele)\\s+(?:cisla\\s+)?(\\d[\\d ]{0,15})(?:\\s+na prvocisla| na prvocinitele)?$").find(f)?.let { m ->
            val n = int(m.groupValues[1]) ?: return null; if (n !in 2..LIMIT) return null
            val fs = factor(n).groupingBy { it }.eachCount().entries.joinToString(" · ") { (p, k) -> if (k > 1) "$p^$k" else "$p" }
            return exact("${fmt(n.toDouble())} = **$fs**" + if (isPrime(n)) " – je to prvočíslo." else "", "prvočísla", "zkusmé dělení prvočísly")
        }
        Regex("^(?:nsd|nejvetsi spolecny delitel)\\s+(?:cisel\\s+)?(\\d{1,12})\\s*(?:a|,)\\s*(\\d{1,12})$").find(f)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); if (a == 0L && b == 0L) return null
            return exact("NSD($a, $b) = **${gcd(a, b)}**", "dělitelnost", "Eukleidův algoritmus")
        }
        Regex("^(?:nsn|nejmensi spolecny nasobek)\\s+(?:cisel\\s+)?(\\d{1,9})\\s*(?:a|,)\\s*(\\d{1,9})$").find(f)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); if (a == 0L || b == 0L) return null
            return exact("NSN($a, $b) = **${a / gcd(a, b) * b}**", "dělitelnost", "a·b ÷ NSD = $a·$b ÷ ${gcd(a, b)}")
        }
        Regex("^(?:faktorial(?: cisla)?\\s+(\\d{1,3})|(\\d{1,3})\\s*!)$").find(f)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt(); if (n > 200) return null
            val v = (1..n).fold(BigInteger.ONE) { acc, i -> acc * BigInteger.valueOf(i.toLong()) }.toString()
            return exact("$n! = **${if (v.length > 60) v.take(30) + "…" + v.takeLast(10) + " (${v.length} číslic)" else v}**", "kombinatorika", "1·2·…·$n")
        }
        Regex("^(?:kombinacni cislo\\s+)?(\\d{1,4})\\s+nad\\s+(\\d{1,4})$|^(?:kolik je )?kombinac\\w*\\s+(\\d{1,4})\\s+z\\s+(\\d{1,4})$").find(f)?.let { m ->
            val (n, k) = if (m.groupValues[1].isNotEmpty()) m.groupValues[1].toInt() to m.groupValues[2].toInt() else m.groupValues[4].toInt() to m.groupValues[3].toInt()
            if (n > 1000 || k > n) return null
            var c = BigInteger.ONE; for (i in 0 until minOf(k, n - k)) c = c * BigInteger.valueOf((n - i).toLong()) / BigInteger.valueOf((i + 1).toLong())
            return exact("C($n, $k) = $n nad $k = **${c}**", "kombinatorika", "n! ÷ (k!·(n−k)!)")
        }
        return null
    }

    // ------------------------------------------------------------------ geometrie
    private val lenUnit = "(mm|cm|dm|m|km)?"
    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    private fun geometry(f: String): Instant.Answer? {
        Regex("^(?:jaky je |kolik je )?(obsah|obvod|plocha) kruh\\w* (?:o |s )?(?:polomer\\w*|r ?=?) ?(\\d+(?:[.,]\\d+)?) ?$lenUnit$").find(f)?.let { m ->
            val r = num(m.groupValues[2]) ?: return null; val u = m.groupValues[3]
            return if (m.groupValues[1] == "obvod") exact("Obvod kruhu = **${fmt(2 * PI * r)}${if (u.isNotEmpty()) " $u" else ""}**", "geometrie", "o = 2πr, r = ${fmt(r)} $u".trim())
            else exact("Obsah kruhu = **${fmt(PI * r * r)}${if (u.isNotEmpty()) " $u²" else ""}**", "geometrie", "S = πr², r = ${fmt(r)} $u".trim())
        }
        Regex("^(?:jaky je |kolik je )?objem koul\\w* (?:o |s )?(?:polomer\\w*|r ?=?) ?(\\d+(?:[.,]\\d+)?) ?$lenUnit$").find(f)?.let { m ->
            val r = num(m.groupValues[1]) ?: return null; val u = m.groupValues[2]
            return exact("Objem koule = **${fmt(4.0 / 3 * PI * r * r * r)}${if (u.isNotEmpty()) " $u³" else ""}**", "geometrie", "V = 4/3·πr³, r = ${fmt(r)} $u".trim())
        }
        Regex("^(?:jaka je |kolik je |spocitej )?prepon\\w*(?: pravouhleho)?(?: trojuhelnik\\w*)?(?: s odvesnami| o odvesnach)? (\\d+(?:[.,]\\d+)?) a (\\d+(?:[.,]\\d+)?) ?$lenUnit$").find(f)?.let { m ->
            val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null; val u = m.groupValues[3]
            return exact("Přepona = **${fmt(sqrt(a * a + b * b))}${if (u.isNotEmpty()) " $u" else ""}**", "geometrie", "c = √(a² + b²) = √(${fmt(a * a)} + ${fmt(b * b)})", listOf("pravoúhlý trojúhelník, a a b jsou odvěsny"))
        }
        return null
    }

    // ------------------------------------------------------------------ soustavy a datové jednotky
    private fun bases(raw: String, f: String): Instant.Answer? {
        Regex("(?i)\\b(0x[0-9a-f]{1,15}|0b[01]{1,62})\\b").find(raw)?.let { m ->
            if (!Regex("\\b(na|do|v)\\b.*\\b(desitkov\\w*|dekadick\\w*|decimal\\w*)|\\bkolik je\\b|\\bprevod\\w*|\\bpreved\\w*").containsMatchIn(f)) return@let
            val s = m.value.lowercase(); val v = if (s.startsWith("0x")) s.drop(2).toLong(16) else s.drop(2).toLong(2)
            return exact("${m.value} = **$v** (desítkově)", "soustavy", if (s.startsWith("0x")) "šestnáctková → desítková" else "dvojková → desítková")
        }
        Regex("^(?:preved |prevod |kolik je )?(\\d{1,15}) (?:do|v|na) (dvojkov\\w*|binarn\\w*|sestnactkov\\w*|hexadecimaln\\w*|hex|osmickov\\w*|oktalov\\w*)(?: soustav\\w*)?$").find(f)?.let { m ->
            val v = m.groupValues[1].toLong(); val w = m.groupValues[2]
            val (r, name) = when { w.startsWith("dvoj") || w.startsWith("bin") -> 2 to "dvojkově"; w.startsWith("osm") || w.startsWith("okt") -> 8 to "osmičkově"; else -> 16 to "šestnáctkově" }
            val out = java.lang.Long.toString(v, r).uppercase()
            return exact("$v = **${if (r == 16) "0x" else if (r == 2) "0b" else "0o"}$out** ($name)", "soustavy", "opakované dělení základem $r")
        }
        Regex("^kolik (bitu|bajtu|b) (?:je|ma|maji) (\\d+(?:[.,]\\d+)?) ?(kb|mb|gb|tb|kib|mib|gib|tib)$").find(f)?.let { m ->
            val v = num(m.groupValues[2]) ?: return null; val u = m.groupValues[3]
            val si = mapOf("kb" to 1e3, "mb" to 1e6, "gb" to 1e9, "tb" to 1e12); val bin = mapOf("kib" to 1024.0, "mib" to 1048576.0, "gib" to 1073741824.0, "tib" to 1099511627776.0)
            val bitsWanted = m.groupValues[1].startsWith("bit")
            fun out(bytes: Double) = if (bitsWanted) "${fmt(bytes * 8, 0)} bitů" else "${fmt(bytes, 0)} bajtů"
            return if (u in bin) exact("${fmt(v)} ${u.uppercase().replace("I", "i")} = **${out(v * bin.getValue(u))}**", "datové jednotky", "binární předpona (1 KiB = 1024 B), 1 B = 8 bitů")
            else exact("${fmt(v)} ${u.uppercase()} = **${out(v * si.getValue(u))}** (SI: 1 kB = 1000 B). Kdyby šlo o binární ${u.uppercase().dropLast(1)}iB: ${out(v * mapOf("kb" to 1024.0, "mb" to 1048576.0, "gb" to 1073741824.0, "tb" to 1099511627776.0).getValue(u))}.",
                "datové jednotky", "1 B = 8 bitů", listOf("MB = megabajt (SI); operační systémy často zobrazují MiB"))
        }
        return null
    }

    // ------------------------------------------------------------------ BMI (orientačně, ne diagnóza)
    private fun bmi(f: String): Instant.Answer? {
        val m = Regex("^(?:jake je |spocitej |vypocitej |moje )?bmi(?: pro)?(?: vysku)? (\\d{2,3}(?:[.,]\\d)?) ?cm(?:,| a| vaha)? (\\d{2,3}(?:[.,]\\d)?) ?kg$").find(f) ?: return null
        val h = (num(m.groupValues[1]) ?: return null) / 100; val w = num(m.groupValues[2]) ?: return null
        if (h !in 1.0..2.5 || w !in 20.0..350.0) return null
        val b = w / (h * h)
        val cat = when { b < 18.5 -> "podváha"; b < 25 -> "normální rozmezí"; b < 30 -> "nadváha"; else -> "obezita" }
        return exact("BMI = **${fmt(b, 1)}** – podle tabulky WHO pro dospělé: **$cat** (normální rozmezí je 18,5–24,9).", "BMI", "BMI = hmotnost ÷ výška² = ${fmt(w)} ÷ ${fmt(h, 2)}²",
            listOf("dospělý člověk", "BMI je orientační ukazatel, ne diagnóza – nezohledňuje svaly, věk ani pohlaví"))
    }

    // ------------------------------------------------------------------ osobní finance (VÝSLOVNÉ vstupy)
    private val money = "(\\d[\\d ]*(?:[.,]\\d+)?)\\s*(?:kc|korun|czk|,-)?"

    private fun finance(f: String): Instant.Answer? {
        val loanAsk = Regex("\\b(splatk\\w*|uver\\w*|hypotek\\w*|pujck\\w*|anuit\\w*)\\b").containsMatchIn(f)
        val compAsk = Regex("\\b(slozen\\w* uroc\\w*|zhodnot\\w*|kolik budu mit|kolik bude\\w*|budouci hodnot\\w*)\\b").containsMatchIn(f)
        if (!loanAsk && !compAsk) return null
        val principal = Regex("(?:uver\\w*|hypotek\\w*|pujck\\w*|vklad\\w*|castk\\w*|uroc\\w*|zhodnot\\w*|investic\\w*|mam|na)\\s+(?:ve vysi\\s+|za\\s+)?$money(?=\\s|$)").find(f)
            ?.groupValues?.get(1)?.let { TextUtil.parseNumber(it.trim()) }
            ?: Regex("(\\d[\\d ]{3,}(?:[.,]\\d+)?)\\s*(?:kc|korun|czk)\\b").find(f)?.groupValues?.get(1)?.let { TextUtil.parseNumber(it.trim()) }
        val rate = Regex("(\\d+(?:[.,]\\d+)?)\\s*%").find(f)?.groupValues?.get(1)?.let { TextUtil.parseNumber(it) }
        val years = Regex("(?:(?:na|za|po)\\s+)?(\\d{1,2})\\s+(?:let|roku|roky|rok)\\b").find(f)?.groupValues?.get(1)?.toIntOrNull()
        val months = Regex("(?:na|za|po)\\s+(\\d{1,3})\\s+(?:mesic\\w*)\\b").find(f)?.groupValues?.get(1)?.toIntOrNull()
        val n = months ?: years?.times(12)
        val missing = buildList {
            if (principal == null || principal <= 0) add("výši ${if (loanAsk) "úvěru" else "vkladu"} (Kč)")
            if (rate == null) add("úrokovou sazbu (% ročně)")
            if (n == null) add("dobu (let / měsíců)")
        }
        val kind = if (loanAsk) "splátka úvěru" else "složené úročení"
        if (missing.isNotEmpty()) {
            if (principal == null && rate == null) return null // spíš obecná otázka („co je hypotéka“) → jiný modul / mozek
            return abstain("Pro výpočet (${kind}) mi chybí: **${missing.joinToString(", ")}**. Nic si nedosazuji – napiš je a spočítám to přesně.", kind)
        }
        val p = principal!!; val annual = rate!! / 100; val months0 = n!!
        if (p > 1e10 || annual > 1.0 || months0 > 600) return null
        if (loanAsk) {
            val r = annual / 12
            val pay = if (r == 0.0) p / months0 else p * r / (1 - (1 + r).pow(-months0))
            val total = pay * months0
            return exact("Měsíční splátka = **${fmt(pay, 2)} Kč**; zaplatíš celkem ${fmt(total, 2)} Kč, z toho úroky **${fmt(total - p, 2)} Kč**.", kind,
                "anuita M = P·r ÷ (1 − (1+r)^−n); P = ${fmt(p, 0)} Kč, r = ${fmt(rate)} % ÷ 12, n = $months0 měsíců",
                listOf("pevná sazba po celou dobu", "měsíční splácení a měsíční úročení", "bez poplatků a pojištění – RPSN bude vyšší"))
        }
        val monthly = Regex("\\bmesicn\\w*").containsMatchIn(f)
        val k = if (monthly) 12 else 1
        val periods = if (monthly) months0 else months0 / 12.0
        val fv = p * (1 + annual / k).pow(periods.toDouble())
        return exact("Za ${if (months0 % 12 == 0) "${months0 / 12} let" else "$months0 měsíců"} budeš mít **${fmt(fv, 2)} Kč** (výnos ${fmt(fv - p, 2)} Kč).", kind,
            "FV = P·(1 + i/k)^(k·t); P = ${fmt(p, 0)} Kč, i = ${fmt(rate)} % ročně, k = $k",
            listOf(if (monthly) "úročení měsíčně" else "úročení jednou ročně (napiš „měsíčně“, pokud jinak)", "bez daní, poplatků a inflace", "sazba po celou dobu stejná"))
    }

    fun answer(prompt: String): Instant.Answer? {
        if (prompt.length > 400) return null
        val f = TextUtil.fold(prompt).lowercase().trim().trimEnd('?', '!', '.').replace(Regex("\\s+"), " ")
        return runCatching { stats(f) ?: numberTheory(f) ?: geometry(f) ?: bases(prompt, f) ?: bmi(f) ?: finance(f) }.getOrNull()
    }
}
