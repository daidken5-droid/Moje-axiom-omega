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
    /** Veřejné formátování čísla (mezery v tisících, desetinná čárka) pro jiné moduly. */
    fun fmtPublic(v: Double) = fmt(v)
    private fun fmt(v: Double): String {
        // Math.round(v * 1e6) přetéká Long nad ~9,2 bilionu → dřív nesmysl „9 223 372 036 854,775“; velká čísla bez zaokrouhlení na miliontiny
        if (kotlin.math.abs(v) >= 1e15) return String.format(java.util.Locale.ROOT, "%.12e", v).let { e -> val (m, x) = e.split('e'); "${m.trimEnd('0').trimEnd('.').replace('.', ',')} × 10^${x.toInt()}" }
        val r = if (kotlin.math.abs(v) >= 1e9) Math.rint(v * 100) / 100 else Math.round(v * 1e6) / 1e6
        val s = if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) String.format(java.util.Locale.ROOT, "%.0f", r) else String.format(java.util.Locale.ROOT, "%.6f", r).trimEnd('0').trimEnd('.')
        val neg = s.startsWith("-"); val (i, f) = s.removePrefix("-").split('.').let { it[0] to it.getOrNull(1) }
        return (if (neg) "−" else "") + i.reversed().chunked(3).joinToString(" ").reversed() + (f?.let { ",$it" } ?: "")
    }

    fun answer(raw: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('?', '.', '!', ' ').let { if (Regex("^[\\d\\s.,+\\-*/×·:÷^()x]+=$").matches(it)) it.dropLast(1).trim() else it }
            .replace(Regex("^kolko "), "kolik ").replace(Regex("(?<![a-z])percent"), "procent").replace("vianoc", "vanoc") // slovensky
            .replace(Regex("(?<=\\d)\\s*(procent[a-z]*|procenta)(?![a-z])"), " %")
        // příkaz k psaní / překladu / úpravě textu nebo žádost o vysvětlení patří modelu, ne kalkulačce
        // („přelož: rozděl 1500 mezi 4 lidi“, „z 10 na 12 je kolik procent a jak se to počítá“)
        if (Regex("^(preloz\\w*|napis\\w*|oprav\\w*|shrn\\w*|prepis\\w*|preformuluj\\w*|vysvetli\\w*|translate)(\\s|:)").containsMatchIn(t) ||
            Regex("(^|\\s)(a jak se to pocita|jak se to pocita|a proc|a vysvetli|vysvetli (mi )?(proc|jak|postup)|a jak na to|krok za krokem)(\\s|$)").containsMatchIn(t)) return null
        if (Regex("^(kolik je hodin|kolik je cas|jaky je cas)$").matches(t)) return "Je %02d:%02d.".format(now.hour, now.minute)
        if (Regex("^(jake je )?(dnes|dneska) (je )?(datum|za den)?$|^jake je (dnes )?datum$|^kolikateho je( dnes)?$|^jaky (je )?dnes den$|^jaky den je dnes$").matches(t))
            return "Dnes je ${now.dayOfMonth}. ${now.monthValue}. ${now.year}."
        days(t, now.toLocalDate())?.let { return it }
        untilDay(t, now.toLocalDate())?.let { return it }
        loan(t)?.let { return it }
        savings(t)?.let { return it }
        tip(t)?.let { return it }
        everyday(t)?.let { return it }
        vat(t)?.let { return it }
        percent(t)?.let { return it }
        dateOffset(t, now.toLocalDate())?.let { return it }
        weekday(t)?.let { return it }
        age(t, now.toLocalDate())?.let { return it }
        convert(t, raw.lowercase().trim().trimEnd('?', '.', '!', ' '))?.let { return it }
        currency(t)?.let { return it }
        discount(t)?.let { return it }
        // „299 měsíčně, kolik ročně?“ / „roční plat 600 000, kolik je to měsíčně?“ (bez nájmu s navýšením apod.)
        if (!Regex("%|navys|zdraz|zlevn|inflac").containsMatchIn(t)) {
            Regex("$N\\s*(?:kc|korun\\w*)?\\s*(?:mesicne|za mesic|mesicni\\w*|/\\s*mesic)[^0-9]{0,30}?kolik (?:je to |to je |to dela |bude |zaplatim |dam )?(?:rocne|za rok)$").find(t)?.let { m ->
                val v = num(m.groupValues[1]) ?: return null; return "${money(v)} měsíčně je ${money(v * 12)} za rok." }
            Regex("(?:rocni\\w*|za rok|rocne)[^0-9]{0,25}?$N\\s*(?:kc|korun\\w*)?[^0-9]{0,30}?kolik (?:je to |to je |to dela |vychazi |mam )?(?:mesicne|za mesic|na mesic)$").find(t)?.let { m ->
                val v = num(m.groupValues[1]) ?: return null; return "${money(v)} ročně je ${money(v / 12)} měsíčně (÷ 12)." }
        }
        // jednoduchý úrok: „úrok 5 % z 100 000 za rok“, „kolik je úrok 3 % ze 200000 za 2 roky“
        Regex("urok\\w*\\s*(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:z|ze|na)\\s*$N(?:\\s*(?:kc|korun\\w*))?(?:\\s*(?:za|na)\\s*(?:(\\d{1,2})\\s*)?(rok\\w*|let|mesic\\w*))?$").find(t)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null
            val n = m.groupValues[3].toIntOrNull() ?: 1; val months = m.groupValues[4].startsWith("mesic")
            val yrs = if (months) n / 12.0 else n.toDouble(); val v = b * p / 100 * yrs
            val per = if (m.groupValues[4].isBlank() || m.groupValues[3].isBlank() && !months) "za rok" else if (m.groupValues[3].isBlank()) "za měsíc" else "za $n ${if (months) (if (n == 1) "měsíc" else if (n in 2..4) "měsíce" else "měsíců") else (if (n == 1) "rok" else if (n in 2..4) "roky" else "let")}"
            return "Úrok ${fmt(p)} % ročně z ${money(b)} $per je ${money(v)} (jednoduchý úrok, bez daně z úroku a poplatků)." }
        Regex("^(?:kolik je |spocitej |vypocitej )?(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:z|ze)\\s*(\\d[\\d ]*(?:[.,]\\d+)?)(\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null
            return if (m.groupValues[3].isNotBlank()) "${fmt(p)} % z ${money(b)} je ${money(b * p / 100)}." else "${fmt(p)} % z ${fmt(b)} je ${fmt(b * p / 100)}." }
        // zlomek z čísla: „1/3 z 900“, „kolik je 3/4 ze 120“
        Regex("^(?:kolik je |spocitej )?(\\d{1,4})\\s*/\\s*(\\d{1,4}) (?:z|ze) $N(\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble(); val v = num(m.groupValues[3]) ?: return null
            if (b == 0.0) return null; val r = v * a / b
            return "${m.groupValues[1]}/${m.groupValues[2]} z ${if (m.groupValues[4].isNotBlank()) money(v) else fmt(v)} je ${if (m.groupValues[4].isNotBlank()) money(r) else fmt(Math.round(r * 1e4) / 1e4)}." }
        // mocnina: „kolik je 2 na 10“, „3 na druhou“, „2 na desátou“ (bez „kolik je“ jen se slovem řádu – „3 na 4“ může být rozměr)
        val ord = mapOf("druhou" to 2, "treti" to 3, "ctvrtou" to 4, "patou" to 5, "sestou" to 6, "sedmou" to 7, "osmou" to 8, "devatou" to 9, "desatou" to 10)
        Regex("^(?:kolik je |spocitej )?(\\d+(?:[.,]\\d+)?) na (\\d{1,3}|druhou|treti|ctvrtou|patou|sestou|sedmou|osmou|devatou|desatou)$").find(t)?.let { m ->
            val pref = t.startsWith("kolik je ") || t.startsWith("spocitej ")
            val e = ord[m.groupValues[2]] ?: m.groupValues[2].toIntOrNull()?.takeIf { pref } ?: return@let
            val b = num(m.groupValues[1]) ?: return null; val v = Math.pow(b, e.toDouble())
            if (v.isInfinite() || v > 1e15) return null
            return "${fmt(b)}^$e = ${fmt(v)}" }
        Regex("^(?:kolik je |spocitej )?(?:druha )?odmocnin[ay] (?:z |ze )?(\\d+(?:[.,]\\d+)?)$").find(t)?.let { m -> val v = num(m.groupValues[1]) ?: return null; return "√${fmt(v)} = ${fmt(Math.sqrt(v))}" }
        // slovní operace („5 mínus 8“, „12 krát 199“, „100 děleno 4“) → znaky; měna na konci se jen připíše k výsledku
        val money = Regex("\\s*(kc|korun\\w*)$").containsMatchIn(t)
        val expr = t.replace(Regex("^(kolik je|kolik dela|spocitej|vypocitej|vysledek)\\s+"), "").replace(Regex("\\s*(kc|korun\\w*)$"), "")
            .replace(Regex(" (plus) "), " + ").replace(Regex(" (minus) "), " - ").replace(Regex(" (krat|nasobeno) "), " * ").replace(Regex(" (deleno|lomeno) "), " / ").trim()
        if (Regex("^[\\d\\s.,+\\-*/×·:÷^()%x]+$").matches(expr) && Regex("\\d\\s*[+\\-*/×·:÷^x]\\s*[\\d(-]").containsMatchIn(expr)) {
            val v = runCatching { Expr(expr).parse() }.getOrNull() ?: return null
            if (v.isInfinite() && Regex("[/:÷]\\s*0+(?:[.,]0+)?\\s*(?:$|[+\\-*/)])").containsMatchIn(expr)) return "Nulou dělit nejde – výsledek není definovaný."
            if (v.isNaN() || v.isInfinite()) return null
            if (money || expr != t.replace(Regex("^(kolik je|kolik dela|spocitej|vypocitej|vysledek)\\s+"), "").trim()) return "${expr.replace("*", "×")} = ${fmt(v)}${if (money) " Kč" else ""}"
            return "${raw.trim().replace(Regex("(?i)^(kolik je|kolik dělá|spočítej|vypočítej)\\s+"), "").trimEnd('?', ' ', '=')} = ${fmt(v)}"
        }
        return null
    }

    private fun daysCz(n: Long) = "$n " + when { n == 1L -> "den"; n in 2..4 -> "dny"; else -> "dní" }
    private const val N = "(\\d[\\d ]*(?:[.,]\\d+)?)"
    private fun money(v: Double) = fmt(Math.round(v * 100) / 100.0) + " Kč"
    private val DAYS = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun dmy(d: LocalDate) = "${d.dayOfMonth}. ${d.monthValue}. ${d.year} (${DAYS[d.dayOfWeek.value - 1]})"

    /**
     * Běžné laické výpočty (scan S26): změna v %, rozdělení mezi lidi, průměr, polovina/třetina/dvojnásobek, cena za kusy a kila,
     * dny v měsíci/roce, kolik sekund/minut/hodin má den/týden/rok a obrácený převod „kolik litrů je 1 galon“.
     */
    private fun everyday(t: String): String? {
        val kc = Regex("\\bkc\\b|korun").containsMatchIn(t)
        fun out(v: Double) = if (kc) money(v) else fmt(Math.round(v * 1e4) / 1e4)
        // „zvýšení z 25 000 na 28 000 je kolik procent“, „o kolik procent klesla cena ze 120 na 90“
        if (Regex("%|procent").containsMatchIn(t) && Regex("kolik|spocitej|vypocitej").containsMatchIn(t)) Regex("(?:^|\\s)(?:z|ze) $N\\s*(?:kc|korun\\w*)? na $N(?:\\s*(?:kc|korun\\w*))?(?:\\s|$)(?!\\s*%)").find(t)?.let { m ->
            val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[2]) ?: return null
            if (a == 0.0 || Regex("dph|slev|spropit").containsMatchIn(t)) return null
            val pct = (b - a) / a * 100; val d = b - a
            return if (d == 0.0) "Z ${out(a)} na ${out(b)} – beze změny (0 %)." else
                "Z ${out(a)} na ${out(b)} je to ${if (d > 0) "nárůst" else "pokles"} o ${fmt(Math.round(kotlin.math.abs(pct) * 100) / 100.0)} % (o ${out(kotlin.math.abs(d))})."
        }
        // „rozděl 1500 mezi 4 lidi“, „1200 Kč na 3 osoby“
        Regex("(?:rozdel\\w*|vydel\\w*) $N\\s*(?:kc|korun\\w*)? (?:mezi|medzi|na) (\\d{1,4}) ?(lid\\w*|lud\\w*|osob\\w*|kamarad\\w*|deti|cast\\w*|dil\\w*|kus\\w*)?$").find(t)?.let { m ->
            val v = num(m.groupValues[1]) ?: return null; val n = m.groupValues[2].toInt(); if (n == 0) return "Na nula dílů rozdělit nejde."
            val who = if (m.groupValues[3].matches(Regex("lid\\w*|lud\\w*|osob\\w*|kamarad\\w*|deti"))) "na každého" else "na jeden díl"
            return "${out(v)} ÷ $n = ${out(v / n)} $who."
        }
        // „průměr z 4, 8 a 15“ (čárka s mezerou = oddělovač, „4,5“ = desetinné číslo)
        Regex("^(?:kolik je |spocitej |vypocitej )?(?:aritmeticky )?prumer\\w*(?: (?:z|ze|cisel|hodnot))? (.+)$").find(t)?.let { m ->
            val body = m.groupValues[1]
            if (!Regex("^[\\d\\s.,;a+]+(?:kc|korun\\w*)?$").matches(body)) return@let
            val cleaned = body.replace(Regex("(?<=\\d) (?=\\d{3}(?!\\d))"), "")
            // „10,20,30“ je seznam, ne 10,2 a 30 (dřív průměr 20,1 místo 20): víc čárek mezi celými čísly = oddělovače;
            // stejně „10,20“, když by jinak zbylo jediné číslo (průměr jednoho čísla nedává smysl)
            val commaList = Regex("^\\s*\\d+(,\\d+){2,}\\s*$").matches(cleaned) || (Regex("^\\s*\\d+,\\d+\\s*$").matches(cleaned))
            val xs = (if (commaList) cleaned.split(',').mapNotNull { num(it.trim()) }
                else Regex("\\d+(?:[.,]\\d+)?").findAll(cleaned).mapNotNull { num(it.value) }.toList())
            if (xs.size < 2) return@let
            return "Průměr ${xs.joinToString(", ") { fmt(it) }} je ${out(xs.sum() / xs.size)} (součet ${fmt(xs.sum())} ÷ ${xs.size})."
        }
        // „třetina z 900“, „polovina z 1 234“, „dvojnásobek 450“
        val parts = mapOf("polovin" to 2, "polovic" to 2, "tretin" to 3, "ctvrtin" to 4, "petin" to 5, "sestin" to 6, "osmin" to 8, "desetin" to 10, "setin" to 100)
        Regex("^(?:kolik je |spocitej )?(polovin|polovic|tretin|ctvrtin|petin|sestin|osmin|desetin|setin)\\w* (?:z|ze) $N(\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val v = num(m.groupValues[2]) ?: return null; val d = parts.getValue(m.groupValues[1])
            return "${m.groupValues[1].replaceFirstChar { it.uppercase() }.let { mapOf("Polovin" to "Polovina", "Polovic" to "Polovina", "Tretin" to "Třetina", "Ctvrtin" to "Čtvrtina", "Petin" to "Pětina", "Sestin" to "Šestina", "Osmin" to "Osmina", "Desetin" to "Desetina", "Setin" to "Setina").getValue(it) }} z ${out(v)} je ${out(v / d)}."
        }
        Regex("^(?:kolik je |spocitej )?(dvoj|troj|ctyr|pet|deseti)nasob\\w* (?:z |ze |od )?$N(\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val k = mapOf("dvoj" to 2, "troj" to 3, "ctyr" to 4, "pet" to 5, "deseti" to 10)[m.groupValues[1]] ?: return@let
            val v = num(m.groupValues[2]) ?: return null
            return "${k}× ${out(v)} = ${out(v * k)}."
        }
        // „kolik stojí 3 rohlíky po 4,50“, „5 lístků po 120 Kč“
        Regex("(?:^|kolik (?:stoji|zaplatim|dam|to bude|je) |za kolik )(\\d{1,4}) (?!eur|usd|dolar|libr|frank|zlot|kc\\b|korun)[a-z]+(?: [a-z]+)? po $N(?:\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val n = m.groupValues[1].toInt(); val p = num(m.groupValues[2]) ?: return null
            return "$n × ${money(p)} = ${money(n * p)}."
        }
        // „2,5 kg za 89 Kč/kg“, „1,5 kila po 40 korunách za kilo“
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(kg|kil[oa]?|l|litr\\w*|m|metr\\w*) (?:za|po) $N\\s*(?:kc|korun\\w*)?\\s*(?:/|za|na) ?(kg|kilo|l|litr|m|metr)$").find(t)?.let { m ->
            val q = num(m.groupValues[1]) ?: return null; val p = num(m.groupValues[3]) ?: return null
            val u = when { m.groupValues[2].startsWith("k") -> "kg"; m.groupValues[2].startsWith("l") -> "l"; else -> "m" }
            return "${fmt(q)} $u × ${money(p)}/$u = ${money(q * p)}."
        }
        // „kolik dní má únor 2028“, „kolik dní má rok 2028“
        Regex("^kolik (?:ma )?(?:dni|dnu) (?:ma |mel |bude mit )?(?:letos |tento rok )?(leden|unor|brezen|duben|kveten|cerven|cervenec|srpen|zari|rijen|listopad|prosinec|rok)(?: (\\d{4})| letos)?$").find(t)?.let { m ->
            val y = m.groupValues[2].toIntOrNull() ?: LocalDate.now().year
            if (m.groupValues[1] == "rok") return "Rok $y má ${java.time.Year.of(y).length()} dní${if (java.time.Year.isLeap(y.toLong())) " (přestupný)" else ""}."
            val mi = listOf("leden", "unor", "brezen", "duben", "kveten", "cerven", "cervenec", "srpen", "zari", "rijen", "listopad", "prosinec").indexOf(m.groupValues[1]) + 1
            val names = listOf("Leden", "Únor", "Březen", "Duben", "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec")
            return "${names[mi - 1]} $y má ${java.time.YearMonth.of(y, mi).lengthOfMonth()} dní."
        }
        // „kolik vteřin má den“, „kolik minut má týden“, „kolik hodin je v roce“
        Regex("^kolik (vterin|sekund|minut|hodin|dni) (?:ma|je v|je za|maji) (den|dnu|tyden|tydnu|hodina|hodine|hodinu|rok|roce|mesic|mesici)$").find(t)?.let { m ->
            val unit = mapOf("vterin" to 1.0, "sekund" to 1.0, "minut" to 60.0, "hodin" to 3600.0, "dni" to 86400.0).getValue(m.groupValues[1])
            val span = when (m.groupValues[2].take(3)) { "den", "dnu" -> 86400.0; "tyd" -> 604800.0; "hod" -> 3600.0; "rok", "roc" -> 365 * 86400.0; else -> return@let }
            if (span < unit) return@let
            val names = mapOf("vterin" to "vteřin", "sekund" to "sekund", "minut" to "minut", "hodin" to "hodin", "dni" to "dní")
            val sp = mapOf("den" to "Den", "dnu" to "Den", "tyd" to "Týden", "hod" to "Hodina", "rok" to "Rok", "roc" to "Rok")[m.groupValues[2].take(3)]
            return "$sp má ${fmt(span / unit)} ${names[m.groupValues[1]]}${if (sp == "Rok") " (běžný rok, 365 dní)" else ""}."
        }
        // „1 míle kolik km“, „5 liber kolik kg“
        Regex("^$N ?(°?[a-z]+[23²³]?) (?:je )?(?:kolik|na kolik|v kolika) (°?[a-z]+[23²³]?)$").find(t)?.let { m -> val q = "${m.groupValues[1]} ${m.groupValues[2]} na ${m.groupValues[3]}"; convert(q, q)?.let { return unfold(it) } }
        // „o kolik je 450 víc než 300“, „o kolik je 80 méně než 100“
        Regex("^o kolik (?:je )?$N (vic|vice|mene|min) nez $N$").find(t)?.let { m ->
            val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[3]) ?: return null; val d = a - b
            return "${out(a)} je o ${out(kotlin.math.abs(d))} ${if (d >= 0) "víc" else "méně"} než ${out(b)}" + (if (b != 0.0) " (o ${fmt(Math.round(kotlin.math.abs(d) / b * 10000) / 100.0)} %)." else ".") }
        // „je rok 2100 přestupný“
        Regex("^(?:je |byl |bude )?(?:rok )?(\\d{4}) (?:je |byl |bude )?prestupn\\w*$|^je (?:rok )?(\\d{4}) prestupn\\w*$").find(t)?.let { m ->
            val y = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt(); val leap = java.time.Year.isLeap(y.toLong())
            return "Rok $y ${if (leap) "je" else "není"} přestupný (${java.time.Year.of(y).length()} dní)." + if (!leap && y % 4 == 0) " Dělitelný 100, ale ne 400." else "" }
        // obrácený převod: „kolik litrů je 1 galon“ → „1 galon na litry“
        Regex("^kolik ([a-z]+) (?:je|ma|maji|dela) $N ?([a-z]+)$").find(t)?.let { m ->
            val q = "${m.groupValues[2]} ${m.groupValues[3]} na ${m.groupValues[1]}"
            return convert(q, q)?.let { unfold(it) }
        }
        return null
    }

    /** Spropitné: „15 % spropitné z 540“, „kolik dát dýško 10 % z 1200“ → částka spropitného + celkem. */
    private fun tip(t: String): String? {
        if (!Regex("spropit|dysk|tuzer").containsMatchIn(t)) return null
        val p = Regex("(\\d+(?:[.,]\\d+)?)\\s*%").find(t)?.groupValues?.get(1)?.let { num(it) } ?: return null
        // částka: za „z/ze“, jinak největší jiné číslo v textu
        val b = Regex("(?:z|ze)\\s*$N").find(t)?.groupValues?.get(1)?.let { num(it) }
            ?: Regex(N).findAll(t).mapNotNull { num(it.value) }.filter { it != p }.maxOrNull() ?: return null
        val tipv = b * p / 100
        return "Spropitné ${fmt(p)} % z ${money(b)} je ${money(tipv)}; celkem zaplatíš ${money(b + tipv)}."
    }

    /** Sleva: „sleva 30 % z 899“, „899 se slevou 30 %“, „899 Kč minus 30 %“ → výše slevy a cena po slevě. */
    private fun discount(t: String): String? {
        if (!Regex("slev|zlevn|minus|mene o|levnejsi o").containsMatchIn(t)) return null
        val m = Regex("(?:sleva|slevou|zlevneni|zlevnene)\\s*(?:o\\s*)?(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:z|ze|na)\\s*$N").find(t)?.let { num(it.groupValues[1]) to num(it.groupValues[2]) }
            ?: Regex("$N\\s*(?:kc|korun\\w*)?\\s*(?:se slevou|zlevnene o|minus|mene o|levnejsi o)\\s*(\\d+(?:[.,]\\d+)?)\\s*%").find(t)?.let { num(it.groupValues[2]) to num(it.groupValues[1]) }
            ?: return null
        val p = m.first ?: return null; val b = m.second ?: return null
        if (p < 0 || p > 100) return null
        return "Sleva ${fmt(p)} % z ${money(b)} je ${money(b * p / 100)}; po slevě zaplatíš ${money(b - b * p / 100)}."
    }

    /** DPH: „1000 bez DPH“, „1 210 s DPH“, „DPH z 1000“, sazba 21 % (nebo 12 % / 0 %, když je v dotazu). */
    private fun vat(t: String): String? {
        if (!t.contains("dph")) return null
        val rate = Regex("(?<!\\d)(21|12|0)\\s*%").find(t)?.groupValues?.get(1)?.toDouble() ?: 21.0
        // „21 % DPH z 1000“ → „dph z 1000“ (sazba už je přečtená)
        val tx = t.replace(Regex("(?<!\\d)(21|12|0)\\s*%\\s*dph"), "dph").replace(Regex("(?<!\\d)(21|12|0)\\s*%"), " ").replace(Regex("\\s+"), " ")
        Regex("^(?:kolik (?:je|cini) )?dph (?:z|ze|u) $N(?: kc)?$").find(tx.trim())?.let { m -> val b = num(m.groupValues[1]) ?: return null
            return "DPH ${fmt(rate)} % z ${money(b)} je ${money(b * rate / 100)}; s DPH ${money(b * (1 + rate / 100))}." }
        // „cena bez dph, když s dph je 1210“ → cena s DPH je známá
        Regex("s dph (?:je|stoji|=|delaji?|cini) $N").find(tx)?.let { m -> val g = num(m.groupValues[1]) ?: return null
            val net = g / (1 + rate / 100); return "${money(g)} s DPH ${fmt(rate)} % = ${money(net)} bez DPH (DPH ${money(g - net)})." }
        Regex("$N\\s*(?:kc )?(?:bez dph|\\+ ?dph)").find(tx)?.let { m -> val b = num(m.groupValues[1]) ?: return null
            val net = b / (1 + rate / 100)
            // „1210 bez DPH“ je dvojznačné (přičíst, nebo odečíst?) → oba směry, ať laik nedostane špatnou polovinu
            return "${money(b)} bez DPH = ${money(b * (1 + rate / 100))} s DPH ${fmt(rate)} % (DPH ${money(b * rate / 100)}). " +
                "Pokud je ${money(b)} cena už S DPH, bez DPH je ${money(net)} (DPH ${money(b - net)})." }
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
        val m = Regex("^(?:jake datum bude |jaky den bude |co bude |kdy bude |jake bude datum )?(za|pred) (\\d{1,4}) (dn\\w*|den|tyd\\w*|mesic\\w*|rok\\w*|let)(?: (?:bude|bylo|je))?(?: (?:jake datum|jaky den|co))?$").find(t) ?: return null
        val n = m.groupValues[2].toLong(); val sign = if (m.groupValues[1] == "za") 1 else -1; val u = m.groupValues[3]
        val d = when { u.startsWith("d") -> today.plusDays(sign * n); u.startsWith("t") -> today.plusWeeks(sign * n); u.startsWith("m") -> today.plusMonths(sign * n); else -> today.plusYears(sign * n) }
        fun cz(one: String, few: String, many: String, inst: String) = if (sign < 0) inst else when (n) { 1L -> one; in 2..4 -> few; else -> many }
        val unit = when { u.startsWith("d") -> cz("den", "dny", "dní", if (n == 1L) "dnem" else "dny"); u.startsWith("t") -> cz("týden", "týdny", "týdnů", if (n == 1L) "týdnem" else "týdny")
            u.startsWith("m") -> cz("měsíc", "měsíce", "měsíců", if (n == 1L) "měsícem" else "měsíci"); else -> cz("rok", "roky", "let", if (n == 1L) "rokem" else "lety") }
        return "${if (sign > 0) "Za" else "Před"} $n $unit ${if (sign > 0) "bude" else "bylo"} ${dmy(d)}."
    }

    /** „jaký den v týdnu je 24. 12. 2026“, „na co připadá 1. 1. 2027“. */
    private fun weekday(t: String): String? {
        if (!Regex("^(jaky den v tydnu (je|byl|bude)|na (co|jaky den) pripada|jaky den (je|byl|bude)|co je za den|co (je|bude|bylo) to za den|jaky je den) ").containsMatchIn(t)) return null
        val d = date(t) ?: return null
        return "${d.dayOfMonth}. ${d.monthValue}. ${d.year} je ${DAYS[d.dayOfWeek.value - 1]}."
    }

    /** „kolik let je od 5. 3. 1990“, „kolik mi je, když jsem se narodil 5. 3. 1990“. */
    private fun age(t: String, today: LocalDate): String? {
        if (!Regex("^(kolik (mi |mu |ji |mu bylo |je )?(je )?let|kolik let (je |uplynulo )?od|jaky (je )?vek|kolik je mi|kolik mi je|kolik mu je|kolik ji je|kolik mi bude)(?![a-z])").containsMatchIn(t)) return null
        val d = date(t)?.takeIf { it.isBefore(today) } ?: return null
        val y = java.time.Period.between(d, today).years
        // výročí v roce [yr]: narozený 29. 2. ho v nepřestupném roce slaví 28. 2., v přestupném 29. 2.
        fun on(yr: Int) = if (d.monthValue == 2 && d.dayOfMonth == 29 && !java.time.Year.isLeap(yr.toLong())) LocalDate.of(yr, 2, 28) else d.withYear(yr)
        fun yrs(n: Int) = "$n ${if (n == 1) "rok" else if (n in 2..4) "roky" else "let"}"
        val years = yrs(y)
        // výročí dnes (i 28. 2. u narozených 29. 2.): celé roky = rozdíl letopočtů (Period by u 29. 2. ukázal o rok méně)
        if (on(today.year) == today) return "Od ${d.dayOfMonth}. ${d.monthValue}. ${d.year} uplynulo právě ${yrs(today.year - d.year)} – výročí je dnes."
        var yr = today.year; while (!on(yr).isAfter(today)) yr++
        val next = on(yr)
        return "Od ${d.dayOfMonth}. ${d.monthValue}. ${d.year} uplynulo $years; další výročí ${dmy(next)} (za ${daysCz(ChronoUnit.DAYS.between(today, next))})."
    }

    fun num(s: String) = s.replace(" ", "").replace(',', '.').toDoubleOrNull()

    private val UNITS = mapOf(
        "km" to ("m" to 1000.0), "m" to ("m" to 1.0), "cm" to ("m" to 0.01), "mm" to ("m" to 0.001), "mi" to ("m" to 1609.344), "mile" to ("m" to 1609.344), "mil" to ("m" to 1609.344),
        "ft" to ("m" to 0.3048), "stop" to ("m" to 0.3048), "in" to ("m" to 0.0254), "palcu" to ("m" to 0.0254), "palec" to ("m" to 0.0254), "palce" to ("m" to 0.0254), "palcich" to ("m" to 0.0254),
        "kg" to ("kg" to 1.0), "g" to ("kg" to 0.001), "t" to ("kg" to 1000.0), "lb" to ("kg" to 0.45359237), "liber" to ("kg" to 0.45359237), "libry" to ("kg" to 0.45359237), "librach" to ("kg" to 0.45359237), "libra" to ("kg" to 0.45359237), "oz" to ("kg" to 0.028349523125), "unci" to ("kg" to 0.028349523125),
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
    /** Měna: kurz offline neznám – s kurzem v dotazu („100 eur po 25,2“) spočítám, jinak řeknu, jak se zeptat. */
    private fun currency(t: String): String? {
        val cur = "(eur|euro|eura|eur[ua]?|usd|dolar[uy]?|dolaru|gbp|liber|libry|chf|frank[uy]?|pln|zlot[ye]ch|zlotych|kc|korun[ay]?|czk)"
        Regex("^(?:kolik je |preved |prevod )?$N\\s*$cur\\s+(?:po|za|pri kurzu|kurz)\\s+$N(?:\\s*(?:kc|korun\\w*))?$").find(t)?.let { m ->
            val a = num(m.groupValues[1]) ?: return null; val r = num(m.groupValues[3]) ?: return null
            return "${fmt(a)} ${m.groupValues[2].uppercase().take(3)} po ${fmt(r)} = ${money(a * r)}."
        }
        if (Regex("^(?:kolik je |preved |prevod )?$N\\s*$cur\\s+(?:na|v|do)\\s+$cur$").containsMatchIn(t) || CnbRates.query(t) != null)
            return CnbRates.query(t).let { q -> val code = q?.from?.takeIf { it != "CZK" } ?: q?.to?.takeIf { it != "CZK" } ?: "EUR"
                val rate = mapOf("EUR" to "25,2", "USD" to "23,5", "GBP" to "29,5", "CHF" to "26", "PLN" to "5,8", "HUF" to "0,064", "JPY" to "0,16")[code] ?: "25"
                "Aktuální kurz offline neznám. Napiš ho do dotazu, třeba „${q?.takeIf { it.from == code }?.let { fmt(it.value) } ?: "100"} ${code.lowercase()} po $rate“, a spočítám to přesně. (S modelem a zapnutým webem ti kurz dohledám.)" }
        return null
    }

    private fun convert(t: String, orig: String = t): String? {
        // „30 stupňů Celsia na Fahrenheita“ → „30 c na f“ (jen pro teplotu; ostatní jednotky beze změny délky textu)
        val tt = t.replace(Regex("(?:stupn\\w* )?celsi\\w*"), "c").replace(Regex("(?:stupn\\w* )?fahrenheit\\w*"), "f").replace(Regex("(?<=\\d) ?stupn\\w* (?=na |do |v )"), " c ")
        val m = Regex("^(?:preved |kolik je |prevod )?(-?\\d+(?:[.,]\\d+)?)\\s*(°?c|°?f|[a-z/]+[23]?)\\s+(?:na|do|v|ve|=|->)\\s+(°?c|°?f|[a-z/]+[23]?)$").find(tt.replace("²", "2").replace("³", "3")) ?: return null
        val v = num(m.groupValues[1]) ?: return null; val a = m.groupValues[2].removePrefix("°"); val b = m.groupValues[3].removePrefix("°")
        if (a in setOf("c", "f") && b in setOf("c", "f") && a != b) {
            val r = if (a == "c") v * 9 / 5 + 32 else (v - 32) * 5 / 9
            return "${fmt(v)} °${a.uppercase()} = ${fmt(Math.round(r * 100) / 100.0)} °${b.uppercase()}"
        }
        val ka = unitKey(a) ?: return null; val kb = unitKey(b) ?: return null
        val ua = UNITS[ka] ?: return null; val ub = UNITS[kb] ?: return null
        if (ua.first != ub.first) return null
        val r = v * ua.second / ub.second
        // jednotky v odpovědi tak, jak je uživatel napsal (s diakritikou: „palců“, ne „palcu“) – fold zachovává délku textu
        val o = orig.replace("²", "2").replace("³", "3")
        fun shown(g: Int) = m.groups[g]?.range?.takeIf { tt == t && o.length == t.length && it.last < o.length }?.let { o.substring(it) } ?: m.groupValues[g]
        // cílová jednotka v 6. pádě („v librách“, „v palcích“) se v odpovědi vypíše v 2. pádě množného čísla
        val loc = mapOf("librach" to "liber", "palcich" to "palců", "kilogramech" to "kg", "metrech" to "m", "centimetrech" to "cm", "litrech" to "l")
        // cílová jednotka napsaná slovem v jiném pádě („v gramech“, „na minuty“) → zkratka, ať nevznikne „= 2 000 gramech“
        val target = loc[b] ?: if (b == kb) shown(3) else (UNIT_SHOW[kb] ?: kb)
        return "${fmt(v)} ${shown(2)} = ${fmt(Math.round(r * 10000) / 10000.0)} $target"
    }

    /** „31. 2. 2027“ – tvar data sedí, ale den neexistuje → srozumitelně říct (ne mlčet). */
    private fun invalidDate(s: String): String? = Regex("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})?").find(s)?.let { m ->
        val y = m.groupValues[3].toIntOrNull() ?: 2024
        if (runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.isFailure) "Datum ${m.groupValues[1]}. ${m.groupValues[2]}.${m.groupValues[3].let { if (it.isNotEmpty()) " $it" else "" }} neexistuje – zkontroluj den a měsíc." else null }
    /** Tvar slova → jednotka: „metry/metrů“ → m, „kila/kilogramů“ → kg, „hodiny“ → h, „týdny“ → týden … (text je bez diakritiky). */
    /** Obrácené převody dostávají text bez diakritiky – v odpovědi vrátit české tvary („mile“ → „míle“). */
    private fun unfold(r: String) = r.replace(Regex("(?<=\\d) mile(?= )"), " míle").replace(Regex("(?<=\\d) mil(?= =)"), " mil").replace(Regex("(?<=\\d) vterin"), " vteřin")
    private fun unitKey(w: String): String? = UNITS[w]?.let { w } ?: when {
        w.startsWith("kilometr") -> "km"; w.startsWith("centimetr") -> "cm"; w.startsWith("milimetr") -> "mm"; w.startsWith("metr") -> "m"
        w.startsWith("kilogram") || w in setOf("kilo", "kila", "kil", "kilech", "kilum") -> "kg"; w.startsWith("gram") -> "g"; w.startsWith("tun") -> "t"
        w.startsWith("mililitr") -> "ml"; w.startsWith("litr") -> "l"; w.startsWith("galon") -> "gal"; w.startsWith("decilitr") || w == "dcl" || w == "deci" -> "dl"
        w.startsWith("hodin") -> "h"; w.startsWith("minut") -> "min"; w.startsWith("sekund") || w.startsWith("vterin") || w.startsWith("vterin") -> "s"; w.startsWith("tyd") -> "tydnu"; w in setOf("dny", "dnu", "dnech", "dni", "den") -> "den"
        w.startsWith("libr") -> "lb"; w.startsWith("unc") -> "oz"; w.startsWith("palc") -> "palec"; w.startsWith("stop") -> "ft"; w in setOf("mile", "mil", "milich") -> "mi"
        else -> null
    }
    private val UNIT_SHOW = mapOf("tydnu" to "týdnů", "den" to "dní", "palec" to "palců", "gal" to "gal")
    private fun date(s: String): LocalDate? = Regex("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})").find(s)?.let { runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull() }
    /** Velikonoční neděle (gregoriánský výpočet). */
    private fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, (h + l - 7 * m + 114) % 31 + 1)
    }
    /** „kolik dní do Vánoc / Silvestra / Velikonoc / 24. 12.“, „za kolik dní jsou Vánoce“. */
    private fun untilDay(t: String, today: LocalDate): String? {
        val m = Regex("^(?:kolik (?:je |zbyva |jeste )?(dni|dnu|tydnu|tydny) (?:zbyva |zbyvaji |je )?do|za (?:kolik dni|jak dlouho|kdy) (?:jsou|je|budou|bude)) (.+)$").find(t) ?: return null
        val weeks = m.groupValues[1].startsWith("tyd")
        val what = m.groupValues[2].trim()
        fun next(f: (Int) -> LocalDate) = f(today.year).let { if (it.isBefore(today)) f(today.year + 1) else it }
        val (name, d) = when {
            what.startsWith("vanoc") || what.startsWith("stedr") || what.startsWith("stedreho") -> "Štědrého dne" to next { LocalDate.of(it, 12, 24) }
            what.startsWith("silvestr") -> "Silvestra" to next { LocalDate.of(it, 12, 31) }
            what.startsWith("konce roku") || what == "konce letoska" -> "konce roku" to LocalDate.of(today.year, 12, 31)
            what.startsWith("konce mesice") -> "konce měsíce" to today.withDayOfMonth(today.lengthOfMonth())
            what.startsWith("nov") && what.contains("rok") -> "Nového roku" to next { LocalDate.of(it, 1, 1) }
            what.startsWith("velikonoc") -> "Velikonoční neděle" to next { easter(it) }
            what.startsWith("leta") || what.startsWith("prazdnin") -> (if (what.startsWith("leta")) "léta (21. 6.)" else "prázdnin (1. 7.)") to next { LocalDate.of(it, if (what.startsWith("leta")) 6 else 7, if (what.startsWith("leta")) 21 else 1) }
            else -> date(what)?.let { "${it.dayOfMonth}. ${it.monthValue}. ${it.year}" to it }
                ?: Regex("^(\\d{1,2})\\.\\s?(\\d{1,2})\\.?$").find(what)?.let { m -> runCatching { val dd = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
                    LocalDate.of(today.year, mm, dd); next { LocalDate.of(it, mm, dd) } }.getOrNull()?.let { "${it.dayOfMonth}. ${it.monthValue}." to it } }
                ?: invalidDate(what)?.let { return it } ?: return null
        }
        val n = ChronoUnit.DAYS.between(today, d)
        return when {
            n == 0L -> "Dnes je to tady – ${dmy(d)}."
            n < 0 -> "To už bylo před ${-n} ${if (n == -1L) "dnem" else "dny"} (${dmy(d)})."
            weeks -> { val w = n / 7; val r = n % 7
                "Do $name zbývá $w ${if (w == 1L) "týden" else if (w in 2..4) "týdny" else "týdnů"}${if (r > 0) " a $r ${if (r == 1L) "den" else if (r in 2..4) "dny" else "dní"}" else ""} ($n dní, ${dmy(d)})." }
            else -> "Do $name zbývá $n ${if (n == 1L) "den" else if (n in 2..4) "dny" else "dní"} (${dmy(d)})."
        }
    }

    /** Splátka úvěru/hypotéky: „hypotéka 3 miliony na 30 let s úrokem 5 %“ → měsíční splátka (anuita), celkem zaplaceno. */
    private fun loan(t: String): String? {
        if (!Regex("hypotek|uver|pujck|splatk").containsMatchIn(t)) return null
        // částka i s mezerami v tisících („3 000 000 na 30 let“ dřív chytlo jen „000“ → žádná odpověď)
        val amt = Regex("(\\d{1,3}(?: \\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)\\s*(milion\\w*|mil\\.?|m(?= )|tisic\\w*|tis\\.?)?(?:\\s*kc)?\\s+(?:na|za|splatn\\w* za)\\s+(\\d{1,2})\\s*(let|roky|roku|rok)").find(t) ?: return null
        val base = num(amt.groupValues[1]) ?: return null
        val mult = when { amt.groupValues[2].startsWith("mil") || amt.groupValues[2] == "m" -> 1e6; amt.groupValues[2].startsWith("tis") -> 1e3; else -> 1.0 }
        val p = base * mult; val years = amt.groupValues[3].toInt()
        val rate = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:%|procent)").find(t)?.groupValues?.get(1)?.let { num(it) } ?: return null
        if (p < 1000 || years !in 1..40 || rate !in 0.0..30.0) return null
        val n = years * 12; val r = rate / 100 / 12
        val pay = if (r == 0.0) p / n else p * r / (1 - Math.pow(1 + r, -n.toDouble()))
        return "Splátka ${money(p)} na $years ${if (years == 1) "rok" else if (years in 2..4) "roky" else "let"} při úroku ${fmt(rate)} % ročně: ${money(pay)} měsíčně; celkem zaplatíš ${money(pay * n)} (z toho úroky ${money(pay * n - p)}). " +
            "Orientačně – bez poplatků a pojištění; přesnou nabídku dá banka."
    }

    /** Spoření: „kolik ušetřím, když budu měsíčně dávat 2000 na 5 let s úrokem 4 %“ – vklad na konci měsíce, měsíční úročení. */
    private fun savings(t: String): String? {
        if (!Regex("usetri|nasporim|naspori|sporeni|sporit|odkladat|budu davat|budu ukladat|stavebk").containsMatchIn(t)) return null
        val dep = Regex("(?:mesicne|kazdy mesic|mesicni\\w*)\\s*(?:davat |ukladat |odkladat |vklad\\w* |po )?$N|$N\\s*(?:kc|korun\\w*)?\\s*(?:mesicne|kazdy mesic|za mesic|/\\s*mesic)").find(t) ?: return null
        val d = num(dep.groupValues[1].ifEmpty { dep.groupValues[2] }) ?: return null
        val years = Regex("(?:(?:na|za|po)\\s+|\\s)(\\d{1,2})\\s*(let|roky|roku|rok)(?![a-z])").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val rate = Regex("(\\d+(?:[.,]\\d+)?)\\s*%").find(t)?.groupValues?.get(1)?.let { num(it) } ?: 0.0
        if (d <= 0 || years !in 1..50 || rate !in 0.0..20.0) return null
        val n = years * 12; val i = rate / 100 / 12
        val fv = if (i == 0.0) d * n else d * (Math.pow(1 + i, n.toDouble()) - 1) / i
        val paid = d * n
        return "Když budeš ${years} ${if (years == 1) "rok" else if (years in 2..4) "roky" else "let"} měsíčně ukládat ${money(d)}${if (rate > 0) " s úrokem ${fmt(rate)} % ročně" else ""}, budeš mít ${money(fv)} – vložíš ${money(paid)}, úroky ${money(fv - paid)}. " +
            "Orientačně: bez daně z úroku (15 %), poplatků a inflace."
    }

    private fun days(t: String, today: LocalDate): String? {
        // „kolik je to dní od 1. 9. 2026“, „kolik dní uplynulo od 1.9.“ (do dneška)
        Regex("^kolik (?:je to |uz )?(?:dni|dnu) (?:je |uplynulo |ubehlo |uz )?od (.+?)(?: do dnes(?:ka)?)?$").find(t)?.takeIf { !it.groupValues[1].contains(" do ") }?.let { m ->
            val d = date(m.groupValues[1]) ?: Regex("^(\\d{1,2})\\.\\s?(\\d{1,2})\\.?$").find(m.groupValues[1])?.let { r -> runCatching { LocalDate.of(today.year, r.groupValues[2].toInt(), r.groupValues[1].toInt()).let { if (it.isAfter(today)) it.minusYears(1) else it } }.getOrNull() } ?: return@let
            if (d.isAfter(today)) return null
            val n = ChronoUnit.DAYS.between(d, today)
            return "Od ${dmy(d)} ${if (n in 2..4) "uplynuly" else "uplynulo"} ${daysCz(n)} (do dneška ${today.dayOfMonth}. ${today.monthValue}.)."
        }
        // „kolik let je od roku 1989“, „kolik let uplynulo od 1989“
        Regex("^kolik (?:je |uz )?let (?:je |uplynulo |ubehlo |uz je )?od (?:roku )?(\\d{4})$").find(t)?.let { m ->
            val y = m.groupValues[1].toInt(); if (y > today.year || y < 1) return null
            val n = today.year - y
            return "Od roku $y ${if (n in 2..4) "uplynuly" else "uplynulo"} $n ${if (n == 1) "rok" else if (n in 2..4) "roky" else "let"} (${today.year} − $y)."
        }
        val m = Regex("kolik (?:je )?(?:dni|dnu) (?:je |uplyne |ubehne )?(?:od|mezi) (.+?) (?:do|a) (.+)$").find(t)
            ?: Regex("^(?:jaky je )?rozdil (?:mezi )?(?:daty )?(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{4}|dneskem|dnesk[ae]m?) a (\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{4}|dneskem|dnesk[ae]m?)$").find(t) ?: return null
        val a = date(m.groupValues[1]) ?: (if (m.groupValues[1].startsWith("dnes")) today else null) ?: return null
        val b = date(m.groupValues[2]) ?: (if (m.groupValues[2].startsWith("dnes")) today else null) ?: return null
        val n = kotlin.math.abs(ChronoUnit.DAYS.between(a, b))
        return "Mezi ${a.dayOfMonth}. ${a.monthValue}. ${a.year} a ${b.dayOfMonth}. ${b.monthValue}. ${b.year} ${if (n in 2..4) "jsou" else "je"} ${daysCz(n)}."
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
