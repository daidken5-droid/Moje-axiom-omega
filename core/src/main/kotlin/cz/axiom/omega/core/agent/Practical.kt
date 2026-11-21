package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * PRAKTICKÉ – otázky z běžného dne, na které uživatel narazí hned první týden a které nepotřebují mozek:
 *  • světový čas („kolik je hodin v New Yorku“) – časová pásma z java.time (vč. letního času),
 *  • DPH a procenta („21 % DPH z 1000“, „1000 bez DPH“, „100 + 20 %“, „o kolik % je 120 víc než 100“),
 *  • kalendář („kolikátý den v roce“, „kdy je příští pondělí“, „kolik dní mezi 1. 1. a 1. 3.“, věk z data narození),
 *  • dělení nulou (věcná odpověď místo chyby).
 */
object Practical {

    private fun ans(t: String, kind: String, conf: Int = 97) = Instant.Answer(t, kind, confidence = conf)

    /** Čitelné číslo: tisíce oddělené mezerou, rozumný počet desetinných míst. */
    fun pretty(v: Double, maxDecimals: Int = 2): String {
        val dec = if (abs(v) >= 1000) minOf(maxDecimals, 2) else maxDecimals
        val s = TextUtil.num(v, dec)
        val neg = s.startsWith("-")
        val body = s.removePrefix("-")
        val int = body.substringBefore(','); val frac = body.substringAfter(',', "")
        val grouped = int.reversed().chunked(3).joinToString(" ").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ",$frac" else ""
    }

    private fun n(s: String): Double? = s.replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull()

    // ------------------------------------------------------------------ světový čas

    private val zones = linkedMapOf(
        "new york|newyork|ny\\b|nyc" to ("America/New_York" to "New Yorku"), "washington" to ("America/New_York" to "Washingtonu"),
        "florid" to ("America/New_York" to "na Floridě"), "chicag" to ("America/Chicago" to "Chicagu"), "texas|dallas|houston" to ("America/Chicago" to "Texasu"),
        "los angeles|kaliforni|san francisk|san francisc|silicon" to ("America/Los_Angeles" to "Kalifornii"), "las vegas" to ("America/Los_Angeles" to "Las Vegas"),
        "toront|kanad" to ("America/Toronto" to "Torontu"), "vancouver" to ("America/Vancouver" to "Vancouveru"), "mexik" to ("America/Mexico_City" to "Mexiku"),
        "havaj|honolul" to ("Pacific/Honolulu" to "na Havaji"), "rio|brazili|sao paul" to ("America/Sao_Paulo" to "Brazílii"), "buenos|argentin" to ("America/Argentina/Buenos_Aires" to "Argentině"),
        "londyn|anglii|britani|uk\\b|velke britanie" to ("Europe/London" to "Londýně"), "irsk|dublin" to ("Europe/Dublin" to "Irsku"), "portugal|lisabon" to ("Europe/Lisbon" to "Portugalsku"),
        "island|reykjav" to ("Atlantic/Reykjavik" to "na Islandu"), "pariz|franci" to ("Europe/Paris" to "Paříži"), "berlin|nemeck" to ("Europe/Berlin" to "Berlíně"),
        "rim|itali" to ("Europe/Rome" to "Římě"), "madrid|spanel" to ("Europe/Madrid" to "Madridu"), "recko|athen|atenach" to ("Europe/Athens" to "Řecku"),
        "kyjev|ukrajin" to ("Europe/Kyiv" to "Kyjevě"), "finsk|helsink" to ("Europe/Helsinki" to "Finsku"), "istanbul|turec" to ("Europe/Istanbul" to "Istanbulu"),
        "moskv|rusk" to ("Europe/Moscow" to "Moskvě"), "dubaj|emirat" to ("Asia/Dubai" to "Dubaji"), "izrael|jeruzalem|tel aviv" to ("Asia/Jerusalem" to "Izraeli"),
        "egypt|kahir" to ("Africa/Cairo" to "Egyptě"), "indi|dilli|bombaj|mumbai" to ("Asia/Kolkata" to "Indii"), "thajsk|bangkok" to ("Asia/Bangkok" to "Thajsku"),
        "vietnam|hanoj" to ("Asia/Ho_Chi_Minh" to "Vietnamu"), "singapur" to ("Asia/Singapore" to "Singapuru"), "hongkong|hong kong" to ("Asia/Hong_Kong" to "Hongkongu"),
        "pekin|cin|sanghaj" to ("Asia/Shanghai" to "Číně"), "tchaj|taiwan" to ("Asia/Taipei" to "Tchaj-wanu"), "korej|soul" to ("Asia/Seoul" to "Koreji"),
        "tokio|tokyo|japon" to ("Asia/Tokyo" to "Tokiu"), "sydney|austral" to ("Australia/Sydney" to "Sydney"), "melbourne" to ("Australia/Melbourne" to "Melbourne"),
        "zeland|auckland" to ("Pacific/Auckland" to "na Novém Zélandu"), "kapsk|jihoafri" to ("Africa/Johannesburg" to "Jihoafrické republice"),
        "keni|nairob" to ("Africa/Nairobi" to "Keni"), "maledivy|malediv" to ("Indian/Maldives" to "na Maledivách"), "kanar|tenerif" to ("Atlantic/Canary" to "na Kanárech"),
    ).map { (k, v) -> rx("\\b($k)") to v }

    private val dayNames = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private val monthsGen = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private fun fmt(d: LocalDate) = "${d.dayOfMonth}. ${monthsGen[d.monthValue - 1]} ${d.year}"

    /** Nejbližší (dnešní nebo budoucí) datum pojmenovaného dne. */
    private fun namedDay(f: String, today: LocalDate): Triple<String, LocalDate, String>? {
        if (!rx("^(kdy|kolik (dni|dnu|tydnu) (zbyva )?do|za jak dlouho|v jaky den)").containsMatchIn(f)) return null
        fun nth(y: Int, month: Int, dow: DayOfWeek, n: Int) = LocalDate.of(y, month, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(n, dow))
        fun lastSun(y: Int, month: Int) = LocalDate.of(y, month, 1).with(java.time.temporal.TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY))
        fun next(make: (Int) -> LocalDate): LocalDate = make(today.year).let { if (it.isBefore(today)) make(today.year + 1) else it }
        return when {
            rx("stedr\\w* den|stedrej|\\bvanoc(e|um)?\\b").containsMatchIn(f) -> Triple("Štědrý den", next { LocalDate.of(it, 12, 24) }, "")
            rx("silvestr").containsMatchIn(f) -> Triple("Silvestr", next { LocalDate.of(it, 12, 31) }, "")
            rx("novy rok|noveho roku|novyho roku").containsMatchIn(f) -> Triple("Nový rok", next { LocalDate.of(it, 1, 1) }, "")
            rx("den matek").containsMatchIn(f) -> Triple("Den matek", next { nth(it, 5, DayOfWeek.SUNDAY, 2) }, " (2. neděle v květnu)")
            rx("den otcu").containsMatchIn(f) -> Triple("Den otců", next { nth(it, 6, DayOfWeek.SUNDAY, 3) }, " (3. neděle v červnu)")
            rx("valentyn").containsMatchIn(f) -> Triple("Valentýn", next { LocalDate.of(it, 2, 14) }, "")
            rx("halloween").containsMatchIn(f) -> Triple("Halloween", next { LocalDate.of(it, 10, 31) }, "")
            rx("mikulas").containsMatchIn(f) -> Triple("Mikuláš (večer)", next { LocalDate.of(it, 12, 5) }, "")
            rx("zmen\\w* cas|meni cas|posouva cas|posun\\w* cas|letni cas|zimni cas|cas se (meni|posouva)").containsMatchIn(f) -> {
                val toSummer = next { lastSun(it, 3) }; val toWinter = next { lastSun(it, 10) }
                if (toSummer.isBefore(toWinter)) Triple("Přechod na letní čas", toSummer, " Ve 2:00 se ručičky posunou na 3:00 (spí se o hodinu méně).")
                else Triple("Přechod na zimní (standardní) čas", toWinter, " Ve 3:00 se ručičky vrátí na 2:00 (spí se o hodinu déle).")
            }
            else -> null
        }
    }

    /** Slovníček nejběžnějších slov: česky → (anglicky, německy). */
    private val dict: Map<String, Pair<String, String>> by lazy {
        runCatching {
            Practical::class.java.classLoader?.getResourceAsStream("facts/slovnik.tsv")?.bufferedReader()?.use { r ->
                r.readLines().filter { it.isNotBlank() }.map { it.split('\t') }.filter { it.size >= 3 }
                    .associate { TextUtil.fold(it[0]).lowercase() to (it[1] to it[2]) }
            }
        }.getOrNull().orEmpty()
    }

    fun answer(prompt: String, now: ZonedDateTime = ZonedDateTime.now()): Instant.Answer? = runCatching { match(prompt, now) }.getOrNull()

    private fun match(prompt: String, now: ZonedDateTime): Instant.Answer? {
        val raw = prompt.trim()
        if (raw.isEmpty() || raw.length > 120 || raw.contains('\n')) return null
        val f = TextUtil.fold(raw).lowercase().replace(Regex("[?!]+"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
        val today = now.toLocalDate()

        // ---------------- světový čas
        if (rx("^(kolik je|kolik je ted|jaky je|kolik mame|cas)\\b.*\\b(hodin|cas)\\b.*\\b(v|ve|na)\\b|^(cas|hodiny) (v|ve|na) ").containsMatchIn(f)) {
            zones.firstOrNull { it.first.containsMatchIn(f) }?.let { (_, z) ->
                val there = now.withZoneSameInstant(ZoneId.of(z.first))
                val diffH = (there.offset.totalSeconds - now.offset.totalSeconds) / 3600.0
                val diff = when { diffH == 0.0 -> "stejně jako u nás"; diffH > 0 -> "o ${TextUtil.num(diffH, 1)} h víc než u nás"; else -> "o ${TextUtil.num(-diffH, 1)} h méně než u nás" }
                val day = if (there.toLocalDate() != today) " (${dayNames[there.dayOfWeek.value - 1]} ${there.dayOfMonth}. ${there.monthValue}.)" else ""
                return ans("V ${z.second} je teď **%d:%02d**%s – %s.".format(there.hour, there.minute, day, diff), "světový čas", 98)
            }
        }

        // ---------------- DPH (ČR: základní 21 %, snížená 12 %)
        rx("^(?:kolik je |spocitej |vypocitej )?(\\d+(?:[.,]\\d+)?) ?(?:%|procent\\w*) ?dph (?:z|ze|na) (\\d[\\d  ]*(?:[.,]\\d+)?)").find(f)?.let { m ->
            val p = n(m.groupValues[1]) ?: return@let; val base = n(m.groupValues[2]) ?: return@let
            val vat = base * p / 100
            return ans("DPH ${pretty(p)} % z ${pretty(base)} = **${pretty(vat)}**; cena s DPH **${pretty(base + vat)}**.", "DPH")
        }
        rx("^(?:kolik je |kolik dela |cena )?(\\d[\\d  ]*(?:[.,]\\d+)?) ?(?:kc |korun )?(s|vcetne|plus|\\+|bez|minus) dph(?: (\\d{1,2}) ?%)?$").find(f)?.let { m ->
            val v = n(m.groupValues[1]) ?: return@let
            val p = m.groupValues[3].toDoubleOrNull() ?: 21.0
            return if (m.groupValues[2] in setOf("bez", "minus")) {
                // z ceny S DPH vypočítat cenu BEZ DPH
                val net = v / (1 + p / 100)
                ans("${pretty(v)} s DPH ${pretty(p)} % = **${pretty(net)} bez DPH** (DPH je ${pretty(v - net)}).", "DPH")
            } else ans("${pretty(v)} bez DPH + ${pretty(p)} % = **${pretty(v * (1 + p / 100))} s DPH** (DPH ${pretty(v * p / 100)}).", "DPH")
        }

        // ---------------- přirážka / sleva: „100 + 20 %“, „2500 - 15 %“, „sleva 30 % z 1200“
        rx("^(?:kolik je |kolik dela )?(\\d[\\d  ]*(?:[.,]\\d+)?) ?(\\+|-|plus|minus) ?(\\d+(?:[.,]\\d+)?) ?(?:%|procent\\w*)$").find(f)?.let { m ->
            val v = n(m.groupValues[1]) ?: return@let; val p = n(m.groupValues[3]) ?: return@let
            val up = m.groupValues[2] in setOf("+", "plus")
            return ans("${pretty(v)} ${if (up) "+" else "−"} ${pretty(p)} % = **${pretty(v * (1 + (if (up) p else -p) / 100))}**", "procenta")
        }
        rx("^(?:kolik je |kolik dela |kolik stoji )?(?:sleva|zlevneni) (\\d+(?:[.,]\\d+)?) ?(?:%|procent\\w*) (?:z|ze|na) (\\d[\\d  ]*(?:[.,]\\d+)?)").find(f)?.let { m ->
            val p = n(m.groupValues[1]) ?: return@let; val v = n(m.groupValues[2]) ?: return@let
            return ans("Sleva ${pretty(p)} % z ${pretty(v)} = **${pretty(v * p / 100)}**; po slevě **${pretty(v * (1 - p / 100))}**.", "procenta")
        }
        // „o kolik procent je 120 víc než 100“, „kolik procent je 30 z 120“
        rx("^o kolik (?:procent|%) (?:je|bylo|bude) (\\d[\\d  ]*(?:[.,]\\d+)?) (vic|vice|mene|min|vetsi|mensi|drazsi|levnejsi) nez (\\d[\\d  ]*(?:[.,]\\d+)?)").find(f)?.let { m ->
            val a = n(m.groupValues[1]) ?: return@let; val b = n(m.groupValues[3]) ?: return@let
            if (b == 0.0) return null
            val pct = (a - b) / b * 100
            return ans("${pretty(a)} je o **${pretty(abs(pct))} %** ${if (pct >= 0) "víc" else "méně"} než ${pretty(b)}.", "procenta")
        }
        rx("^kolik (?:procent|%) (?:je|tvori|dela) (\\d[\\d  ]*(?:[.,]\\d+)?) (?:z|ze) (\\d[\\d  ]*(?:[.,]\\d+)?)$").find(f)?.let { m ->
            val a = n(m.groupValues[1]) ?: return@let; val b = n(m.groupValues[2]) ?: return@let
            if (b == 0.0) return null
            return ans("${pretty(a)} z ${pretty(b)} je **${pretty(a / b * 100)} %**.", "procenta")
        }

        // ---------------- kalendář
        if (rx("^kolikat\\w* (?:je )?(?:dnes |dneska )?den (?:v )?roce|^kolikaty den v roce").containsMatchIn(f)) {
            val left = (if (today.isLeapYear) 366 else 365) - today.dayOfYear
            return ans("Dnes je **${today.dayOfYear}. den** roku ${today.year} (do konce roku zbývá $left).", "kalendář", 99)
        }
        rx("^kdy (?:je|bude) (?:pristi|dalsi|nejblizsi) (pondeli|utery|streda|stredu|ctvrtek|patek|sobota|sobotu|nedele|nedeli)$").find(f)?.let { m ->
            val target = listOf("pondeli", "utery", "stred", "ctvrtek", "patek", "sobot", "nedel").indexOfFirst { m.groupValues[1].startsWith(it) }
            if (target < 0) return@let
            var d = today.plusDays(1)
            while (d.dayOfWeek != DayOfWeek.of(target + 1)) d = d.plusDays(1)
            val k = ChronoUnit.DAYS.between(today, d)
            return ans("Příští ${dayNames[target]} je **${fmt(d)}** (za $k ${if (k == 1L) "den" else if (k < 5) "dny" else "dní"}).", "kalendář", 99)
        }
        val dateRx = "(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(\\d{4})?"
        rx("(?:mezi|od) $dateRx (?:a|do) $dateRx").find(f)?.let { m ->
            if (!rx("kolik (dni|dnu|tydnu)").containsMatchIn(f)) return@let
            val y1 = m.groupValues[3].toIntOrNull() ?: today.year; val y2 = m.groupValues[6].toIntOrNull() ?: y1
            val a = LocalDate.of(y1, m.groupValues[2].toInt(), m.groupValues[1].toInt()); val b = LocalDate.of(y2, m.groupValues[5].toInt(), m.groupValues[4].toInt())
            val d = abs(ChronoUnit.DAYS.between(a, b))
            fun dny(k: Long) = if (k == 1L) "den" else if (k in 2..4) "dny" else "dní"
            fun tydny(k: Long) = if (k == 1L) "týden" else if (k in 2..4) "týdny" else "týdnů"
            val w = d / 7; val r = d % 7
            return ans("Mezi ${fmt(a)} a ${fmt(b)} je **$d ${dny(d)}**" + (if (w > 0) " ($w ${tydny(w)}" + (if (r > 0) " a $r ${dny(r)})" else ")") else "") + ".", "kalendář", 99)
        }
        // věk z celého data narození
        rx("(?:narodil\\w*|narozen\\w*|narozeni)\\D{0,20}?$dateRx").find(f)?.let { m ->
            if (!rx("kolik|let|vek").containsMatchIn(f) || m.groupValues[3].isEmpty()) return@let
            val born = LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
            if (born.isAfter(today)) return@let
            val years = ChronoUnit.YEARS.between(born, today)
            var next = born.withYear(today.year); if (!next.isAfter(today)) next = next.plusYears(1)
            val toBd = ChronoUnit.DAYS.between(today, next)
            val birthday = born.monthValue == today.monthValue && born.dayOfMonth == today.dayOfMonth
            return ans("Je ti **$years let** (narození ${fmt(born)})" + if (birthday) " – a dnes máš narozeniny, všechno nejlepší!" else "; další narozeniny za $toBd dní.", "výpočet", 99)
        }

        // ---------------- svátky a změna času („kdy je Den matek“, „kdy se mění čas“, „kolik týdnů do Vánoc“)
        namedDay(f, today)?.let { (name, d, note) ->
            val k = ChronoUnit.DAYS.between(today, d)
            val weeks = rx("^kolik tydnu").containsMatchIn(f)
            val left = when { k == 0L -> "je to **dnes**"; weeks -> "zbývá **${k / 7} týdnů a ${k % 7} dní**"; else -> "zbývá **$k ${if (k == 1L) "den" else if (k < 5) "dny" else "dní"}**" }
            return ans("$name: **${dayNames[d.dayOfWeek.value - 1]} ${fmt(d)}** – $left.$note", "kalendář", 99)
        }

        // ---------------- slovníček (nejběžnější slova CZ → EN / DE)
        rx("^(?:jak se (?:anglicky |nemecky )?rekne|preloz(?: mi)?|prelozit|co je|co znamena) (?:slovo )?(.{2,30}?) (?:anglicky|nemecky|do anglictiny|do nemciny|v anglictine|v nemcine|po anglicku|po nemecku)$|^(?:anglicky|nemecky|co je anglicky|co je nemecky) (.{2,30})$").find(f)?.let { m ->
            val w = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val de = rx("nemc|nemeck").containsMatchIn(f)
            dict[w]?.let { (en, ge) -> return ans("„$w“ ${if (de) "německy" else "anglicky"}: **${if (de) ge else en}**", "slovníček", 95) }
        }

        // „kolik dní má tento měsíc“
        if (rx("^kolik (dni|dnu) (ma )?(tento|tenhle|tenhleten|aktualni|soucasny|letosni|tenhle letosni)? ?mesic|^kolik ma (tento|tenhle|aktualni) mesic dni").containsMatchIn(f)) {
            val len = today.lengthOfMonth()
            return ans("${listOf("Leden", "Únor", "Březen", "Duben", "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec")[today.monthValue - 1]} ${today.year} má **$len dní**.", "kalendář", 99)
        }

        // ---------------- dělení nulou
        if (rx("^(?:kolik je |co je )?-?\\d+(?:[.,]\\d+)? ?(?:/|deleno|lomeno) ?0(?:[.,]0+)?$").matches(f))
            return ans("Nulou dělit **nelze** – výsledek není definovaný (u 0 / 0 je neurčitý).", "výpočet", 99)
        return null
    }
}
