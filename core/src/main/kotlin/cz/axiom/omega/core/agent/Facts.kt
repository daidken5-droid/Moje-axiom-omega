package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * OFFLINE DATABÁZE FAKTŮ – odpovědi bez mozku z ověřených, v čase neměnných dat (resources/facts):
 * jmeniny a státní svátky ČR (i pohyblivé Velikonoce), hlavní města všech států, 118 chemických prvků,
 * kraje ČR, planety, fyzikální konstanty, předpony SI, tísňová čísla; převodníky (římské číslice,
 * Morseova abeceda, hláskování NATO, přestupný rok). Rozumí českému skloňování („hlavní město Německa“).
 * Jistota 97 – data jsou kurátorovaná, otázka musí odpovídat vzoru.
 */
object Facts {

    private const val CONF = 97
    private fun ans(text: String, kind: String) = Instant.Answer(text, kind, confidence = CONF)

    private fun res(name: String): List<List<String>> {
        val stream = Facts::class.java.classLoader?.getResourceAsStream("facts/$name") ?: return emptyList()
        return stream.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() }.map { it.split('\t') } }
    }

    private val nameDays by lazy { res("jmeniny.tsv").associate { it[0] to it[1] } }
    private val states by lazy { res("staty.tsv") }
    private val elements by lazy { res("prvky.tsv") }
    private val countries by lazy { res("zeme.tsv") }
    private val records by lazy { res("rekordy.tsv").map { Regex(it[0]) to it[1] } }
    private val history by lazy { res("dejiny.tsv").map { Triple(Regex(it[0]), it[1], it[2]) } }
    private val abbrevs by lazy { res("zkratky.tsv").associate { it[0] to it[1] } }
    /** Obecné znalosti: vzor otázky → odpověď (autoři, vynálezy, vesmír, tělo, obyvatelstvo…). */
    private val general by lazy { res("obecne.tsv").map { Regex(it[0]) to it[1] } }

    private val months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private val days = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun fmt(d: LocalDate) = "${d.dayOfMonth}. ${months[d.monthValue - 1]}"

    // ------------------------------------------------------------------ skloňování: shoda kmene slov

    private fun words(s: String) = TextUtil.fold(s).split(rx("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Obsahuje otázka víceslovný název (v libovolném pádě)? Každé slovo porovná podle kmene. */
    private fun mentions(q: List<String>, name: String): Boolean {
        val n = words(name).filter { it !in setOf("a", "republika") || words(name).size == 1 }
        if (n.isEmpty()) return false
        fun stem(w: String) = if (w.length <= 4) w.take(maxOf(3, w.length - 1)) else w.take(w.length - 2)
        for (i in 0..q.size - n.size) {
            if (n.indices.all { k -> val s = stem(n[k]); q[i + k].startsWith(s) && q[i + k].length <= n[k].length + 3 }) return true
        }
        return false
    }

    // ------------------------------------------------------------------ státní svátky a Velikonoce

    /** Velikonoční neděle (gregoriánský kalendář, anonymní algoritmus). */
    fun easter(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    fun holidays(year: Int): List<Pair<LocalDate, String>> {
        val e = easter(year)
        return listOf(
            LocalDate.of(year, 1, 1) to "Den obnovy samostatného českého státu, Nový rok",
            e.minusDays(2) to "Velký pátek", e.plusDays(1) to "Velikonoční pondělí",
            LocalDate.of(year, 5, 1) to "Svátek práce", LocalDate.of(year, 5, 8) to "Den vítězství",
            LocalDate.of(year, 7, 5) to "Den slovanských věrozvěstů Cyrila a Metoděje",
            LocalDate.of(year, 7, 6) to "Den upálení mistra Jana Husa",
            LocalDate.of(year, 9, 28) to "Den české státnosti", LocalDate.of(year, 10, 28) to "Den vzniku samostatného československého státu",
            LocalDate.of(year, 11, 17) to "Den boje za svobodu a demokracii a Mezinárodní den studentstva",
            LocalDate.of(year, 12, 24) to "Štědrý den", LocalDate.of(year, 12, 25) to "1. svátek vánoční", LocalDate.of(year, 12, 26) to "2. svátek vánoční",
        ).sortedBy { it.first }
    }

    // ------------------------------------------------------------------ převodníky

    private val roman = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    fun toRoman(n: Int): String { var x = n; return buildString { for ((v, s) in roman) while (x >= v) { append(s); x -= v } } }
    fun fromRoman(s: String): Int? {
        val t = s.uppercase(); if (!t.matches(rx("[MDCLXVI]+"))) return null
        val v = mapOf('M' to 1000, 'D' to 500, 'C' to 100, 'L' to 50, 'X' to 10, 'V' to 5, 'I' to 1)
        var sum = 0
        for (i in t.indices) { val c = v[t[i]]!!; val n = if (i + 1 < t.length) v[t[i + 1]]!! else 0; sum += if (c < n) -c else c }
        return sum.takeIf { it in 1..3999 && toRoman(it) == t }
    }

    private val morse = mapOf(
        'a' to ".-", 'b' to "-...", 'c' to "-.-.", 'd' to "-..", 'e' to ".", 'f' to "..-.", 'g' to "--.", 'h' to "....", 'i' to "..", 'j' to ".---",
        'k' to "-.-", 'l' to ".-..", 'm' to "--", 'n' to "-.", 'o' to "---", 'p' to ".--.", 'q' to "--.-", 'r' to ".-.", 's' to "...", 't' to "-",
        'u' to "..-", 'v' to "...-", 'w' to ".--", 'x' to "-..-", 'y' to "-.--", 'z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    )
    fun toMorse(s: String) = TextUtil.fold(s).split(' ').filter { it.isNotEmpty() }.joinToString(" / ") { w -> w.mapNotNull { morse[it] }.joinToString(" ") }
    fun fromMorse(s: String): String? {
        val rev = morse.entries.associate { it.value to it.key }
        val words = s.trim().split(rx("\\s*/\\s*|\\s{3,}"))
        val out = words.joinToString(" ") { w -> w.trim().split(rx("\\s+")).joinToString("") { rev[it]?.toString() ?: "?" } }
        return out.takeIf { '?' !in it && it.isNotBlank() }
    }

    private val nato = mapOf(
        'a' to "Alfa", 'b' to "Bravo", 'c' to "Charlie", 'd' to "Delta", 'e' to "Echo", 'f' to "Foxtrot", 'g' to "Golf", 'h' to "Hotel", 'i' to "India",
        'j' to "Juliett", 'k' to "Kilo", 'l' to "Lima", 'm' to "Mike", 'n' to "November", 'o' to "Oscar", 'p' to "Papa", 'q' to "Quebec", 'r' to "Romeo",
        's' to "Sierra", 't' to "Tango", 'u' to "Uniform", 'v' to "Victor", 'w' to "Whiskey", 'x' to "X-ray", 'y' to "Yankee", 'z' to "Zulu",
        '0' to "Zero", '1' to "One", '2' to "Two", '3' to "Three", '4' to "Four", '5' to "Five", '6' to "Six", '7' to "Seven", '8' to "Eight", '9' to "Nine",
    )

    // ------------------------------------------------------------------ malé tabulky

    private val regions = listOf(
        "Hlavní město Praha" to "Praha", "Středočeský" to "Praha (sídlo úřadu)", "Jihočeský" to "České Budějovice", "Plzeňský" to "Plzeň",
        "Karlovarský" to "Karlovy Vary", "Ústecký" to "Ústí nad Labem", "Liberecký" to "Liberec", "Královéhradecký" to "Hradec Králové",
        "Pardubický" to "Pardubice", "Vysočina" to "Jihlava", "Jihomoravský" to "Brno", "Olomoucký" to "Olomouc", "Zlínský" to "Zlín",
        "Moravskoslezský" to "Ostrava",
    )
    private val planets = listOf("Merkur", "Venuše", "Země", "Mars", "Jupiter", "Saturn", "Uran", "Neptun")
    private val constants = listOf(
        listOf("rychlost svetla", "Rychlost světla ve vakuu: **c = 299 792 458 m/s** (přesně, definice metru)."),
        listOf("gravitacni zrychleni", "Normální tíhové zrychlení: **g = 9,806 65 m/s²** (konvenční hodnota)."),
        listOf("gravitacni konstanta", "Gravitační konstanta: **G ≈ 6,674 30 × 10⁻¹¹ m³·kg⁻¹·s⁻²**."),
        listOf("planckova konstanta", "Planckova konstanta: **h = 6,626 070 15 × 10⁻³⁴ J·s** (přesně)."),
        listOf("avogadrova konstanta", "Avogadrova konstanta: **Nₐ = 6,022 140 76 × 10²³ mol⁻¹** (přesně)."),
        listOf("elementarni naboj", "Elementární náboj: **e = 1,602 176 634 × 10⁻¹⁹ C** (přesně)."),
        listOf("boltzmannova konstanta", "Boltzmannova konstanta: **k = 1,380 649 × 10⁻²³ J/K** (přesně)."),
        listOf("absolutni nula", "Absolutní nula: **0 K = −273,15 °C**."),
        listOf("rychlost zvuku", "Rychlost zvuku ve vzduchu (20 °C): **≈ 343 m/s**."),
        listOf("ludolfovo cislo", "Ludolfovo číslo: **π ≈ 3,141 592 653 59**."),
        listOf("eulerovo cislo", "Eulerovo číslo: **e ≈ 2,718 281 828 46**."),
    )
    private val prefixes = listOf(
        "kvetta" to "10³⁰", "ronna" to "10²⁷", "yotta" to "10²⁴", "zetta" to "10²¹", "exa" to "10¹⁸", "peta" to "10¹⁵", "tera" to "10¹²", "giga" to "10⁹",
        "mega" to "10⁶", "kilo" to "10³", "hekto" to "10²", "deka" to "10¹", "deci" to "10⁻¹", "centi" to "10⁻²", "mili" to "10⁻³", "mikro" to "10⁻⁶",
        "nano" to "10⁻⁹", "piko" to "10⁻¹²", "femto" to "10⁻¹⁵", "atto" to "10⁻¹⁸", "zepto" to "10⁻²¹", "yokto" to "10⁻²⁴", "ronto" to "10⁻²⁷", "kvekto" to "10⁻³⁰",
    )

    // ------------------------------------------------------------------ odpověď

    fun answer(prompt: String, today: LocalDate = LocalDate.now()): Instant.Answer? = runCatching { match(prompt, today) }.getOrNull()

    private fun match(prompt: String, today: LocalDate): Instant.Answer? {
        val raw = prompt.trim()
        if (raw.isEmpty() || raw.length > 160) return null
        val f = TextUtil.fold(raw).replace(rx("\\s+"), " ").trim().trimEnd('?', '.', '!', ' ')
        val q = words(raw)

        // ---- obecné znalosti (krátké otázky; vzory jsou úzké, aby nechytaly cizí dotazy)
        if (f.split(' ').size <= 14) general.firstOrNull { it.first.containsMatchIn(f) }?.let { return ans(it.second, "znalosti") }

        // ---- jmeniny
        if (rx("\\b(svatek|jmeniny|slavi)\\b").containsMatchIn(f)) {
            val day = when {
                rx("\\bpozitri\\b").containsMatchIn(f) -> today.plusDays(2)
                rx("\\bzitra\\b").containsMatchIn(f) -> today.plusDays(1)
                rx("\\bvcera\\b").containsMatchIn(f) -> today.minusDays(1)
                else -> rx("(\\d{1,2})\\.\\s*(\\d{1,2})\\.?").find(f)?.let { m -> runCatching { LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }
                    ?: if (rx("\\b(dnes|dneska)\\b").containsMatchIn(f) || f.startsWith("kdo ma svatek") || f == "svatek" || f == "jmeniny") today else null
            }
            if (day != null && rx("^(kdo|komu|jake|jaky|ma dnes|svatek|jmeniny)").containsMatchIn(f)) {
                val nd = nameDays["${day.dayOfMonth}.${day.monthValue}"] ?: return null
                val hol = holidays(day.year).firstOrNull { it.first == day }?.second
                val rel = when (ChronoUnit.DAYS.between(today, day)) { 0L -> "Dnes"; 1L -> "Zítra"; -1L -> "Včera"; 2L -> "Pozítří"; else -> "${fmt(day)}" }
                return ans("$rel (${days[day.dayOfWeek.value - 1]} ${fmt(day)}) má svátek **$nd**." + (hol?.let { if (it !in nd) "\nStátní svátek: **$it**." else "" } ?: ""), "jmeniny")
            }
            // kdy má svátek Jana / kdy slaví Petr
            rx("^(?:kdy )?(?:ma svatek|slavi|ma jmeniny|svatek ma|jmeniny ma)\\s+(\\p{L}+)|^kdy (?:ma )?(?:svatek|jmeniny) (?:ma )?(\\p{L}+)").find(f)?.let { m ->
                val name = (m.groupValues[1].ifBlank { m.groupValues[2] })
                val hits = nameDays.filter { (_, v) -> v.split(rx("[,–]")).any { TextUtil.fold(it.trim()) == name } }.keys
                if (hits.isNotEmpty()) {
                    val dates = hits.map { k -> k.split('.').let { LocalDate.of(today.year, it[1].toInt(), it[0].toInt()) } }
                    val nice = name.replaceFirstChar { it.uppercase() }
                    return ans("**$nice** má svátek **" + dates.joinToString(" a ") { fmt(it) } + "**" +
                        dates.firstOrNull()?.let { d -> val dd = if (d.isBefore(today)) d.plusYears(1) else d; " (příště ${days[dd.dayOfWeek.value - 1]}, za ${ChronoUnit.DAYS.between(today, dd)} dní)." } ?: ".", "jmeniny")
                }
            }
        }

        // ---- státní svátky, Velikonoce
        rx("\\b(velikonoc\\w*|velky patek|velikonocni pondeli)\\b").find(f)?.let {
            if (!rx("^(kdy|v kolik|jake datum|datum)").containsMatchIn(f)) return@let
            val y = Regex("\\b(19|20|21)\\d\\d\\b").find(f)?.value?.toInt() ?: today.year.let { y0 -> if (easter(y0).plusDays(1).isBefore(today)) y0 + 1 else y0 }
            val e = easter(y)
            return ans("Velikonoce $y: Velký pátek **${fmt(e.minusDays(2))}**, Velikonoční neděle **${fmt(e)}**, Velikonoční pondělí **${fmt(e.plusDays(1))}**.", "svátky")
        }
        if (rx("^(je|bude|byl)( dnes| zitra| vcera)? (statni )?svatek|^je (dnes|zitra) volno").containsMatchIn(f)) {
            val day = if ("zitra" in f) today.plusDays(1) else if ("vcera" in f) today.minusDays(1) else today
            val h = holidays(day.year).firstOrNull { it.first == day }
            return ans(if (h != null) "Ano – ${fmt(day)} je **${h.second}** (státní svátek / den pracovního klidu)." else "Ne, ${fmt(day)} není státní svátek.", "svátky")
        }
        if (rx("^(seznam |jake jsou |vypis )?(statni )?svatky( v cr| v cesku)?( (19|20)\\d\\d)?$").containsMatchIn(f) || f.startsWith("kdy je pristi statni svatek") || f.startsWith("pristi statni svatek")) {
            val y = Regex("\\b(19|20)\\d\\d\\b").find(f)?.value?.toInt() ?: today.year
            val list = holidays(y)
            if (f.contains("pristi")) {
                val n = (list + holidays(y + 1)).first { !it.first.isBefore(today) }
                return ans("Příští státní svátek: **${n.second}** – ${days[n.first.dayOfWeek.value - 1]} ${fmt(n.first)} ${n.first.year} (za ${ChronoUnit.DAYS.between(today, n.first)} dní).", "svátky")
            }
            return ans("**Státní a ostatní svátky ČR $y:**\n" + list.joinToString("\n") { "- ${fmt(it.first)} (${days[it.first.dayOfWeek.value - 1]}) – ${it.second}" }, "svátky")
        }

        // ---- hlavní města
        if (rx("\\bhlavni(m)? mest").containsMatchIn(f)) {
            // „Praha je hlavní město čeho?“ / „čí hlavní město je Paříž“
            rx("^(?:ci|ktereho statu|ktere zeme) hlavni mesto je (.+)$|^(.+?) je hlavni(?:m)? mest\\w* (?:ceho|ktereho statu|ktere zeme)$").find(f)?.let { m ->
                val city = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                states.firstOrNull { TextUtil.fold(it[1]).startsWith(city) }?.let { return ans("**${it[1]}** je hlavní město státu **${it[0]}** (${it[2]}).", "zeměpis") }
            }
            val aliases = mapOf("usa" to "Spojené státy americké", "spojen\\w* stat" to "Spojené státy americké", "spojen\\w* kralovstv" to "Spojené království", "ameriky" to "Spojené státy americké", "britanie" to "Spojené království", "velke britanie" to "Spojené království",
                "anglie" to "Spojené království", "holandsk" to "Nizozemsko", "ceske republiky" to "Česko", "cr" to "Česko", "ceska" to "Česko", "cine" to "Čína", "ciny" to "Čína")
            val alias = aliases.entries.firstOrNull { (k, _) -> rx("\\b$k").containsMatchIn(f) }?.value
            val st = states.firstOrNull { it[0] == alias } ?: states.sortedByDescending { it[0].length }.firstOrNull { mentions(q, it[0]) }
            if (st != null) return ans("Hlavní město státu **${st[0]}** je **${st[1]}** (${st[2]}).", "zeměpis")
        }
        // na jakém kontinentu je X
        rx("^(?:na jakem|na kterem|kde je|ve kterem) (?:kontinentu|svetadilu)?\\s*(?:je|lezi)?\\s*(.+)$").find(f)?.let { m ->
            if (!f.contains("kontinent") && !f.contains("svetadil")) return@let
            val st = states.sortedByDescending { it[0].length }.firstOrNull { mentions(words(m.groupValues[1]), it[0]) } ?: return@let
            return ans("**${st[0]}** leží: **${st[2]}** (hlavní město ${st[1]}).", "zeměpis")
        }

        // ---- chemické prvky
        if (rx("\\b(znack\\w*|prvek|prvku|protonov\\w*|atomov\\w*|chemick\\w*|kolikat\\w*|cislo prvku|relativni atomov\\w*)\\b").containsMatchIn(f)) {
            // podle značky („co je Fe“, „prvek Au“)
            rx("\\b([A-Z][a-z]?)\\b").findAll(raw).map { it.value }.toList().let { syms ->
                elements.firstOrNull { e -> e[1] in syms && rx("(?i)\\b(prvek|znack|co je|chemick)").containsMatchIn(raw) && raw.split(rx("\\W+")).contains(e[1]) }
            }?.let { e -> return ans("**${e[1]}** je **${e[2]}** – protonové číslo **${e[0]}**, relativní atomová hmotnost **${e[3]}**.", "chemie") }
            elements.sortedByDescending { it[2].length }.firstOrNull { mentions(q, it[2]) }?.let { e ->
                return ans("**${e[2].replaceFirstChar { it.uppercase() }}**: značka **${e[1]}**, protonové číslo **${e[0]}**, relativní atomová hmotnost **${e[3]}**.", "chemie")
            }
            rx("prvek (?:s|cislo)? ?(?:protonov\\w* cisl\\w*)? ?(\\d{1,3})\\b").find(f)?.let { m ->
                elements.firstOrNull { it[0] == m.groupValues[1] }?.let { e -> return ans("Prvek číslo **${e[0]}** je **${e[2]} (${e[1]})**, relativní atomová hmotnost ${e[3]}.", "chemie") }
            }
        }

        // ---- kraje ČR
        if (rx("\\bkraj").containsMatchIn(f)) {
            if (rx("kolik (ma |je )?(cr |cesko |ceska republika )?kraj").containsMatchIn(f) || f.startsWith("kraje cr") || f.startsWith("seznam kraju"))
                return ans("Česko má **14 krajů** (13 krajů + hlavní město Praha):\n" + regions.joinToString("\n") { "- ${it.first}${if (it.first.startsWith("Hlavní") || it.first == "Vysočina") "" else " kraj"} – ${it.second}" }, "zeměpis")
            regions.firstOrNull { mentions(q, it.first) && it.first != "Hlavní město Praha" }?.let { r ->
                if (rx("krajsk|sidlo|hlavni mesto|mesto").containsMatchIn(f))
                    return ans("Krajské město kraje **${r.first}${if (r.first == "Vysočina") "" else " kraj"}** je **${r.second}**.", "zeměpis")
            }
        }

        // ---- planety
        if (rx("\\bplanet").containsMatchIn(f)) {
            if (rx("^(kolik|vyjmenuj|seznam|jake|ktere)").containsMatchIn(f))
                return ans("Sluneční soustava má **8 planet** (od Slunce): " + planets.withIndex().joinToString(", ") { "${it.index + 1}. ${it.value}" } + ". Pluto je od roku 2006 trpasličí planeta.", "astronomie")
            planets.withIndex().firstOrNull { mentions(q, it.value) }?.let { p ->
                if (rx("kolikat|poradi").containsMatchIn(f)) return ans("**${p.value}** je **${p.index + 1}. planeta** od Slunce.", "astronomie")
            }
            if (rx("(nejvetsi|najvetsi)").containsMatchIn(f)) return ans("Největší planeta Sluneční soustavy je **Jupiter**.", "astronomie")
            if (rx("nejmensi").containsMatchIn(f)) return ans("Nejmenší planeta Sluneční soustavy je **Merkur**.", "astronomie")
        }

        // ---- konstanty
        if (rx("^(jaka je |kolik je |hodnota )?").containsMatchIn(f)) constants.firstOrNull { f.contains(it[0]) && f.length <= it[0].length + 25 }?.let { return ans(it[1], "fyzika") }

        // ---- předpony SI
        rx("(?:predpon\\w* |co znamena |kolik je )?\\b(kvetta|ronna|yotta|zetta|exa|peta|tera|giga|mega|kilo|hekto|deka|deci|centi|mili|mikro|nano|piko|femto|atto|zepto|yokto|ronto|kvekto)\\b").find(f)?.let { m ->
            if (!rx("predpon|co znamena|kolik je (jedno )?(\\w+)$").containsMatchIn(f) || f.split(' ').size > 5) return@let
            val p = prefixes.first { it.first == m.groupValues[1] }
            return ans("Předpona **${p.first}** = **${p.second}**.", "jednotky")
        }

        // ---- česká politika (jen stálá fakta; aktuální vláda a výsledky voleb jdou mozku s balíčkem a ověřením)
        // „kdo je prezident“ bez uvedení státu = český prezident
        val foreign = Regex("usa|americ|spojen|rusk|franc|nemec|slovens|polsk|ukrajin|cin|izrael|rakous|madar|turec|brazil|indie").containsMatchIn(f)
        if (rx("\\bprezident").containsMatchIn(f) && (rx("\\b(cr|cesk\\w*|ceske republiky)\\b").containsMatchIn(f) || !foreign)) {
            val list = "1. Václav Havel (1993–2003)\n2. Václav Klaus (2003–2013)\n3. Miloš Zeman (2013–2023, první přímo zvolený)\n4. Petr Pavel (od 9. 3. 2023)"
            if (rx("^(seznam|vsichni|vyjmenuj|jaci|kteri|prehled)").containsMatchIn(f)) return ans("**Prezidenti České republiky:**\n$list", "politika")
            if (rx("prvni prezident").containsMatchIn(f)) return ans("Prvním prezidentem České republiky byl **Václav Havel** (1993–2003); předtím byl posledním prezidentem ČSFR.", "politika")
            if (rx("^kdo je (soucasny |aktualni |nynejsi )?prezident").containsMatchIn(f)) return ans("Prezidentem ČR je **Petr Pavel** – v úřadu od 9. 3. 2023, funkční období 5 let.", "politika")
        }
        if (rx("^kolik (ma |je )?(poslanc|clenu snemovn|snemovna)").containsMatchIn(f)) return ans("Poslanecká sněmovna má **200 poslanců**, volených na 4 roky poměrným systémem.", "politika")
        if (rx("^kolik (ma |je )?(senator|senat)").containsMatchIn(f)) return ans("Senát má **81 senátorů**, volených na 6 let; každé 2 roky se volí třetina.", "politika")
        rx("^kdy (vstoupila |vstoupilo |je |se stala )?(cr|cesko|ceska republika)? ?(do |clenem )?(nato|eu|evropske unie|schengen\\w*)").find(f)?.let { m ->
            val what = m.groupValues[4]
            return ans(when {
                what.startsWith("nato") -> "Česko je členem **NATO od 12. 3. 1999** (spolu s Polskem a Maďarskem)."
                what.startsWith("schengen") -> "Česko je v **Schengenu od 21. 12. 2007** (letiště od 30. 3. 2008)."
                else -> "Česko je členem **EU od 1. 5. 2004** (referendum 2003, 77 % pro)."
            }, "politika")
        }

        // ---- měna a telefonní předvolba státu
        if (rx("\\b(men\\w*|plati\\w*|predvolb\\w*|volaci kod|kod zeme)\\b").containsMatchIn(f)) {
            rx("(\\+\\d{1,3})\\b").find(raw)?.let { m ->
                val hits = countries.filter { it[3] == m.groupValues[1] }
                if (hits.isNotEmpty()) return ans("Předvolba **${m.groupValues[1]}** patří: **${hits.joinToString(", ") { it[0] }}**.", "zeměpis")
            }
            val aliases = mapOf("usa" to "Spojené státy americké", "ameri" to "Spojené státy americké", "britani" to "Spojené království", "angli" to "Spojené království", "holandsk" to "Nizozemsko", "cesk" to "Česko")
            val c = aliases.entries.firstOrNull { (k, _) -> rx("\\b$k").containsMatchIn(f) }?.value?.let { a -> countries.firstOrNull { it[0] == a } }
                ?: countries.sortedByDescending { it[0].length }.firstOrNull { mentions(q, it[0]) }
            if (c != null) return ans(if (rx("predvolb|volaci|kod zeme").containsMatchIn(f)) "Telefonní předvolba státu **${c[0]}** je **${c[3]}**."
                else "Ve státě **${c[0]}** se platí: **${c[1]}** (${c[2]}).", "zeměpis")
        }

        // ---- zeměpisné a přírodní rekordy
        if (rx("^(jaka|jaky|jake|kde|ktera|ktery|ktere|co je|ma)?\\s*(je |ma )?(nej)").containsMatchIn(f) || f.startsWith("nej"))
            records.firstOrNull { it.first.containsMatchIn(f) }?.let { return ans(it.second, "zeměpis") }

        // ---- české dějiny
        if (rx("^(kdy|co (se stalo|bylo|byla|byl|je|znamena)|v jakem roce|ktereho roku|kdo byl)").containsMatchIn(f) || f.split(' ').size <= 4)
            history.firstOrNull { it.first.containsMatchIn(f) }?.let { return ans("**${it.second}** – ${it.third}", "dějiny") }

        // ---- zkratky
        rx("^(?:co znamena|co je|co je to|vyznam|zkratka)\\s+(?:zkratka\\s+)?([a-z0-9]{2,6})$").find(f)?.let { m ->
            abbrevs[m.groupValues[1]]?.let { return ans("**${raw.substringAfterLast(' ').trimEnd('?', '.').uppercase()}** = $it.", "zkratky") }
        }

        // ---- tísňová čísla
        if (rx("(tisnov\\w*|nouzov\\w*) (cisl|link)|cislo (na )?(zachrank|hasic|polici|zachrannou)|^(zachranka|hasici|policie) cislo").containsMatchIn(f))
            return ans("**Tísňová čísla v ČR:** 112 (jednotné evropské), 155 záchranná služba, 150 hasiči, 158 policie, 156 městská policie, 1210 horská služba.", "bezpečnost")

        // ---- převodníky
        rx("^(?:prevod |preved |zapis |napis )?(\\d{1,4}) (?:rimsky|rimskymi cislicemi|na rimske cislo|do rimskych cislic)$|^(?:rimsky|rimske cislo)\\s+(\\d{1,4})$").find(f)?.let { m ->
            val n = (m.groupValues[1].ifBlank { m.groupValues[2] }).toInt()
            if (n in 1..3999) return ans("$n = **${toRoman(n)}**", "převod")
        }
        rx("^(?i:kolik je|co je|preved|prevod|arabsky)?\\s*([MDCLXVI]{2,15})(?i: arabsky| na arabske cislo| je kolik)?$").find(raw.trimEnd('?', ' '))?.let { m ->
            if (!rx("(?i)kolik|arabsk|prevod|preved|rimsk").containsMatchIn(f)) return@let
            fromRoman(m.groupValues[1])?.let { return ans("${m.groupValues[1].uppercase()} = **$it**", "převod") }
        }
        rx("^(?:prevod |preved |zapis )?(?:do |v )?morse\\w*:?\\s+(.+)$|^(.+) (?:do|v) morse\\w*$").find(f)?.let { m ->
            val t = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (t.all { it in ".-/ " }) fromMorse(t)?.let { return ans("Morse → text: **${it.uppercase()}**", "převod") }
            return ans("**${t.uppercase()}** v Morseově abecedě:\n`${toMorse(t)}`", "převod")
        }
        if (raw.matches(rx("[.\\-/ ]{5,}"))) fromMorse(raw)?.let { return ans("Morse → text: **${it.uppercase()}**", "převod") }
        rx("^(?:hlaskuj|vyhlaskuj|nato)\\s+(.{1,30})$").find(f)?.let { m ->
            val t = m.groupValues[1].filter { it.isLetterOrDigit() }
            return ans("Hláskování NATO: " + t.mapNotNull { nato[it] }.joinToString(" – "), "převod")
        }
        rx("^(?:je|bude|byl) (?:rok )?(\\d{4}) prestupn\\w*(?: rok)?$|^(\\d{4}) prestupny( rok)?$").find(f)?.let { m ->
            val y = (m.groupValues[1].ifBlank { m.groupValues[2] }).toInt()
            val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
            return ans(if (leap) "Ano, rok $y **je přestupný** (366 dní, 29. února)." else "Ne, rok $y **není přestupný** (365 dní).", "kalendář")
        }
        return null
    }
}
