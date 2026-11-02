package cz.axiom.omega.core.agent

import cz.axiom.omega.core.self.Identity
import cz.axiom.omega.core.step0.Calc
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.core.step0.Units
import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx
import cz.axiom.omega.core.util.TextUtil.num
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * BĚŽNÉ – nejčastější jednoduché otázky bez mozku: drobná konverzace, počty slovy („7 krát 8“,
 * „odmocnina ze 144“, „15 procent z 200“), převody v přirozené řeči („5 km v metrech“, „100 eur na koruny“),
 * kurzy ČNB, den/měsíc/rok, věk a roky od události, délky časových jednotek, vtip, mince, kostka, náhodné číslo.
 */
object Everyday {

    private fun ans(t: String, kind: String, conf: Int = 98) = Instant.Answer(t, kind, confidence = conf)
    private val days = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private val monthsNom = listOf("leden", "únor", "březen", "duben", "květen", "červen", "červenec", "srpen", "září", "říjen", "listopad", "prosinec")
    private val monthsGen = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")

    private val jokes = listOf(
        "Víte, proč programátoři nemají rádi přírodu? Má moc bugů.",
        "„Pane doktore, všichni mě přehlížejí.“ – „Další, prosím!“",
        "Potkají se dva magneti. Jeden povídá: „Nevím, co na tobě vidím, ale něco mě k tobě táhne.“",
        "Proč kostlivec nešel na večírek? Neměl s kým jít – nikoho neměl na těle.",
        "Jak se jmenuje medvěd bez zubů? Gumový medvídek.",
        "„Mami, můžu jít ven?“ – „Až dojíš tu polévku.“ – „A můžu ji vzít s sebou?“",
        "Optimista vidí sklenici poloplnou, pesimista poloprázdnou a inženýr dvakrát větší, než je potřeba.",
        "Proč matematik nikdy nespí? Protože počítá ovečky až do nekonečna.",
        "Říká žena muži: „Zítra bude výročí, co si dáme?“ On: „Minutu ticha.“",
        "Co dělá Wi-Fi na zahradě? Stahuje jahody.",
    )

    /** České slovní výrazy → výraz pro kalkulačku. */
    fun toExpr(f: String): String? {
        var e = " " + f.replace(rx("^(kolik je|kolik dela|spocitej( mi)?|vypocitej|spocti|kolik|co je)\\s+"), "").trim() + " "
        e = e.replace(rx("(druha|treti) odmocnina (z |ze )?(\\d+(?:[.,]\\d+)?)")) { m -> if (m.groupValues[1] == "druha") " sqrt(${m.groupValues[3]}) " else " (${m.groupValues[3]})^(1/3) " }
        e = e.replace(rx("odmocnin\\w* (z |ze )?(\\d+(?:[.,]\\d+)?)")) { " sqrt(${it.groupValues[2]}) " }
        e = e.replace(rx("(\\d+(?:[.,]\\d+)?) na (druhou|treti|(\\d+))\\b")) { m -> " ${m.groupValues[1]}^" + when (m.groupValues[2]) { "druhou" -> "2"; "treti" -> "3"; else -> m.groupValues[3] } + " " }
        e = e.replace(rx("(druha|treti) mocnin\\w* (z |ze |cisla )?(\\d+(?:[.,]\\d+)?)")) { m -> " ${m.groupValues[3]}^" + (if (m.groupValues[1] == "druha") "2" else "3") + " " }
        e = e.replace(rx("\\b(sin|cos|tan|tg)\\s*\\(?\\s*(-?\\d+(?:[.,]\\d+)?)\\s*\\)?\\s*(°|stupn\\w*)?")) { m -> " ${if (m.groupValues[1] == "tg") "tan" else m.groupValues[1]}(${m.groupValues[2]}*pi/180) " }
        e = e.replace(rx("\\b(krat|x|nasobeno)\\b"), "*").replace(rx("\\b(deleno|lomeno|delene)\\b"), "/")
            .replace(rx("\\b(plus|a)\\b"), "+").replace(rx("\\b(minus|bez)\\b"), "-")
        e = e.trim().trimEnd('?', '=', '!', '.').trim()
        if (!e.any { it.isDigit() } || !e.matches(rx("[\\d\\s+\\-*/^().,a-z]+")) || e.contains(rx("[a-z]{5,}"))) return null
        if (!rx("[+\\-*/^]|sqrt|sin|cos|tan").containsMatchIn(e)) return null
        return e
    }

    private val unitWords = listOf(
        "kilometr\\w*" to "km", "metr\\w*" to "m", "centimetr\\w*" to "cm", "milimetr\\w*" to "mm", "mililitr\\w*" to "ml", "mil|mile|mili|milich|milim|milemi" to "mi", "palc\\w*|palec" to "in", "stop\\w*" to "ft",
        "kilogram\\w*|kil\\b" to "kg", "dekagram\\w*|deka|dkg" to "dag", "gram\\w*" to "g", "tun\\w*" to "t", "liber\\w*|libr\\w*" to "lb", "unc\\w*" to "oz",
        "litr\\w*" to "l", "mililitr\\w*" to "ml", "galon\\w*" to "gal", "hektar\\w*" to "ha", "akr\\w*" to "ac",
        "metr\\w* ctverecn\\w*|ctverecn\\w* metr\\w*" to "m2", "kilometr\\w* ctverecn\\w*|ctverecn\\w* kilometr\\w*" to "km2",
        "joul\\w*" to "j", "kilowatthodin\\w*" to "kwh", "minut\\w*" to "min", "hodin\\w*|hodina|hodinu" to "h", "sekund\\w*|vterin\\w*" to "s",
        "den|dny|dni|dnu|dnech|dnum" to "d", "tyd\\w*" to "wk",
        "stupn\\w* celsia|celsi\\w*" to "c", "stupn\\w* fahrenheita|fahrenheit\\w*" to "f", "kelvin\\w*" to "k",
        "kilowatt\\w*" to "kw", "kon\\w*|koni" to "ps", "kalori\\w*" to "kcal",
        "eur\\w*" to "eur", "dolar\\w*" to "usd", "korun\\w*|kc|czk" to "czk", "libr\\w* sterlink\\w*" to "gbp", "frank\\w*" to "chf", "zlot\\w*" to "pln", "jen\\w*" to "jpy",
    )
    private val abbrev = setOf("km", "m", "cm", "mm", "dm", "mi", "in", "ft", "yd", "kg", "g", "mg", "t", "lb", "oz", "l", "ml", "gal", "ha", "kw", "hp", "ps", "kwh", "kcal", "mph", "m2", "km2", "ac", "dag", "min", "j", "kj", "h", "wk", "kmh", "km/h", "m/s", "bar", "psi", "eur", "usd", "czk", "gbp", "chf", "pln", "jpy")
    private fun unit(w: String): String? = w.trim().let { t -> t.takeIf { it in abbrev } ?: unitWords.firstOrNull { rx("^(${it.first})$").matches(t) }?.second }

    /** Krátké ověřené zajímavosti („řekni mi něco zajímavého“). */
    private val funFacts = listOf(
        "Chobotnice má tři srdce a modrou krev.", "Med se nekazí – jedlý med se našel i v egyptských hrobkách.",
        "Světlo ze Slunce letí k Zemi asi 8 minut a 20 sekund.", "Na Zemi je víc stromů (asi 3 biliony) než hvězd v Mléčné dráze (100–400 miliard).",
        "Kontaktní čočky v dnešní podobě vynalezl Čech Otto Wichterle – první vyrobil na dětské stavebnici Merkur.",
        "Slovo „robot“ vymyslel Josef Čapek pro hru R.U.R. svého bratra Karla.", "Venuše se otáčí opačně než většina planet – Slunce tam vychází na západě.",
        "Banány jsou bobule, jahody botanicky ne.", "Člověk má v těle asi 37 bilionů buněk.", "Olympus Mons na Marsu je skoro třikrát vyšší než Mount Everest.",
        "Pražský orloj je z roku 1410 a je nejstarším dosud fungujícím orlojem svého typu.", "Sloni jsou jediní savci, kteří neumí skákat.",
        "Neutronová hvězda je tak hustá, že lžička její hmoty by vážila miliardy tun.",
    )

    fun answer(prompt: String, today: LocalDate = LocalDate.now(), rates: Rates = Rates.FALLBACK, battery: Int? = null, charging: Boolean? = null, random: Random = Random.Default): Instant.Answer? =
        runCatching { match(prompt, today, rates, battery, charging, random) }.getOrNull()

    private fun match(prompt: String, today: LocalDate, rates: Rates, battery: Int?, charging: Boolean?, rnd: Random): Instant.Answer? {
        val raw = prompt.trim()
        if (raw.isEmpty() || raw.length > 120 || raw.contains('\n')) return null
        val f = TextUtil.fold(raw).replace(rx("[,;]"), " ").replace(rx("\\s+"), " ").trim().trimEnd('?', '.', '!', ' ')

        // ---------------- drobná konverzace
        if (rx("^((ahoj|cau|nazdar|zdravim|dobry den|hej) )?(jak se mas|jak se vede|jak to jde|jak je|co ty)( ty)?( avior)?$").matches(f))
            return ans("Díky, mám se dobře – mozek připravený, paměť v pořádku. A jak se máš ty? S čím ti můžu pomoct?", "konverzace")
        if (rx("^(ok|okej|oki|dobre|jo|jj|ano|super|skvele|paradni|fajn|v pohode|rozumim|chapu|aha|jasne|diky moc|dekuji pekne)$").matches(f))
            return ans("Kdybys něco potřeboval, jsem tady.", "konverzace")
        if (rx("^(dobrou noc|dobrou|spi sladce|jdu spat)$").matches(f)) return ans("Dobrou noc – ať se ti dobře spí.", "konverzace")
        if (rx("^(jsi tam|jsi tu|halo|haloo|halo halo|posloucha[sš]|funguje[sš]|zijes)( avior)?$").matches(f))
            return ans("Jsem tady. Na co se chceš zeptat?", "konverzace")
        if (rx("^(nuda|nudim se|je mi smutno|nevim co delat|co mam delat)$").matches(f))
            return ans("Zkus třeba: „řekni mi něco zajímavého“, „řekni vtip“, „hoď kostkou“ – nebo se zeptej na cokoli, co tě napadne.", "konverzace")
        if (rx("^(rekni|napis)( mi)? (neco zajimaveho|zajimavost|zajimavy fakt|nejaky fakt)$|^zajimavost$").matches(f))
            return ans("**Zajímavost:** " + funFacts.random(rnd), "zajímavost")
        if (rx("^(vyber|dej mi|rekni|vylosuj)( mi)? (nahodne |nejake )?cislo$").matches(f))
            return ans("Náhodné číslo od 1 do 100: **${rnd.nextInt(1, 101)}**", "náhoda")
        if (rx("^(nashledanou|nashledano|nashledanou avior)$").matches(f)) return ans("Na shledanou – kdykoli se vrať.", "konverzace")
        if (rx("^(nashle|na shledanou|cau cau|mej se|mejte se|zatim|bye|papa|tak zatim)$").matches(f)) return ans("Měj se – kdykoli se vrať.", "konverzace")
        if (rx("^(co umis|co vsechno umis|co dokazes|s cim mi muzes pomoct|pomoc|help|napoveda|jak te pouzivat|jak to funguje)$").matches(f))
            return ans("Umím odpovídat a radit offline přímo v telefonu, počítat, převádět jednotky a měny, hledat v offline Wikipedii, pamatovat si, co mi řekneš, vytvářet soubory i aplikace, ovládat telefon povely („zapni svítilnu“, „budík na 6:30“) a s internetem hledat na webu. Jednoduché věci (čas, výpočty, svátky, hlavní města…) zvládnu okamžitě bez mozku.", "konverzace")
        if (rx("^(co delas|co ted delas|co delas ted)$").matches(f)) return ans("Čekám na tvou otázku – mozek mám připravený v paměti.", "konverzace")
        if (rx("^(kdo te (vytvoril|udelal|naprogramoval|stvoril)|kdo je tvuj (autor|tvurce)|odkud jsi)$").matches(f))
            return ans("Jsem ${Identity.NAME} – vznikl jsem jako tvoje vlastní AI na míru: běžím přímo v tvém telefonu na otevřených modelech (Gemma), s vlastní pamětí, reflexy a RANDY Supervisorem. Kód je v repozitáři Moje-axiom-omega.", "konverzace")
        if (rx("^(rekni (mi )?vtip|vtip|nejaky vtip|pobav me|zasmej me)$").matches(f)) return ans(jokes[rnd.nextInt(jokes.size)], "vtip")

        // ---------------- náhoda
        if (rx("^(hod|hodit|hodis|hod si|hod mi)( si)? minci|^(panna nebo orel|mince)$").containsMatchIn(f)) return ans(if (rnd.nextBoolean()) "**Panna**" else "**Orel**", "náhoda")
        rx("^(hod|hodit|hod si|hod mi)( si)? (kostkou|kostkami|(\\d) kostk\\w*)").find(f)?.let { m ->
            val n = m.groupValues[4].toIntOrNull()?.coerceIn(1, 6) ?: 1
            val r = List(n) { rnd.nextInt(1, 7) }
            return ans(r.joinToString(" + ") { "**$it**" } + if (n > 1) " = **${r.sum()}**" else "", "náhoda")
        }
        rx("nahodne cislo (od |mezi )?(-?\\d+) (do|a) (-?\\d+)").find(f)?.let { m ->
            val a = m.groupValues[2].toLong(); val b = m.groupValues[4].toLong()
            if (b > a && b - a < 1_000_000_000L) return ans("**${rnd.nextLong(a, b + 1)}** (náhodně od $a do $b)", "náhoda")
        }

        // ---------------- kalendář a čas
        if (rx("^(jaky|co) (je|bude) (zitra|pozitri)( za)? den|^(jaky den je|jaky den bude) (zitra|pozitri)|^(zitra|pozitri) je (jaky den|co)$").containsMatchIn(f)) {
            val d = if ("pozitri" in f) today.plusDays(2) else today.plusDays(1)
            return ans("${if ("pozitri" in f) "Pozítří" else "Zítra"} je **${days[d.dayOfWeek.value - 1]} ${d.dayOfMonth}. ${monthsGen[d.monthValue - 1]} ${d.year}**.", "kalendář")
        }
        if (rx("^(jaky|co) (byl|bylo) vcera|^jaky den byl vcera").containsMatchIn(f)) { val d = today.minusDays(1); return ans("Včera byl **${days[d.dayOfWeek.value - 1]} ${d.dayOfMonth}. ${monthsGen[d.monthValue - 1]}**.", "kalendář") }
        if (rx("^(co je dnes za den( v tydnu)?|jaky je dnes den v tydnu|jaky je den v tydnu)$").matches(f))
            return ans("Dnes je **${days[today.dayOfWeek.value - 1]}**.", "kalendář")
        if (rx("^(jaky|ktery) (je )?(ted |letos |dnes )?rok|^(jaky|ktery) mame rok").containsMatchIn(f)) return ans("Je rok **${today.year}**.", "kalendář")
        if (rx("^(jaky|ktery) (je )?(ted |dnes )?mesic|^(jaky|ktery) mame mesic").containsMatchIn(f)) return ans("Je **${monthsNom[today.monthValue - 1]}** (${today.monthValue}. měsíc).", "kalendář")
        rx("kolik (dni|dnu) (ma|mel|bude mit) (leden|unor|brezen|duben|kveten|cerven|cervenec|srpen|zari|rijen|listopad|prosinec)( (\\d{4}))?").find(f)?.let { m ->
            val names = listOf("leden", "unor", "brezen", "duben", "kveten", "cerven", "cervenec", "srpen", "zari", "rijen", "listopad", "prosinec")
            val mo = names.indexOf(m.groupValues[3]) + 1
            val y = m.groupValues[5].toIntOrNull() ?: today.year
            val len = java.time.YearMonth.of(y, mo).lengthOfMonth()
            return ans("${monthsNom[mo - 1].replaceFirstChar { it.uppercase() }} $y má **$len dní**.", "kalendář")
        }
        when {
            rx("^kolik (dni|dnu) (ma )?rok|^kolik ma rok dni").containsMatchIn(f) -> return ans("Rok má **365 dní**, přestupný **366** (${today.year} ${if (java.time.Year.isLeap(today.year.toLong())) "je" else "není"} přestupný).", "čas")
            rx("^kolik (dni|dnu) (ma )?tyden|^kolik ma tyden dni").containsMatchIn(f) -> return ans("Týden má **7 dní**.", "čas")
            rx("^kolik (dni|dnu) (ma )?prestupn\\w* rok").containsMatchIn(f) -> return ans("Přestupný rok má **366 dní** (29. února navíc).", "čas")
            rx("^kolik hodin (ma )?rok").containsMatchIn(f) -> return ans("Rok má **8 760 hodin** (přestupný 8 784).", "čas")
            rx("^kolik (sekund|vterin) (ma )?(minuta|minutu)").containsMatchIn(f) -> return ans("Minuta má **60 sekund**.", "čas")
            rx("^kolik minut (ma )?(hodina|hodinu)").containsMatchIn(f) -> return ans("Hodina má **60 minut**.", "čas")
            rx("^kolik hodin (ma )?(den|dni)$").containsMatchIn(f) -> return ans("Den má **24 hodin**.", "čas")
            rx("^kolik (sekund|vterin) (ma )?(hodina|hodinu)").containsMatchIn(f) -> return ans("Hodina má **3 600 sekund**.", "čas")
            rx("^kolik (minut) (ma )?(den|dni)").containsMatchIn(f) -> return ans("Den má **1 440 minut**.", "čas")
            rx("^kolik (sekund|vterin) (ma )?(den|dni)").containsMatchIn(f) -> return ans("Den má **86 400 sekund**.", "čas")
            rx("^kolik (hodin) (ma )?(tyden)").containsMatchIn(f) -> return ans("Týden má **168 hodin**.", "čas")
            rx("^kolik (tydnu|tydnu) (ma )?rok").containsMatchIn(f) -> return ans("Rok má **52 týdnů** a 1 den (přestupný 2 dny).", "čas")
        }
        rx("(narodil\\w*|narozen\\w*|ročník|rocnik)\\D{0,12}(19\\d\\d|20[0-2]\\d)").find(f)?.let { m ->
            if (!rx("kolik (je|mi je|mu je|ji je|bude|mam)|let").containsMatchIn(f)) return@let
            val y = m.groupValues[2].toInt()
            return ans("Narozený v roce $y je letos (${today.year}) **${today.year - y} let** – nebo ${today.year - y - 1}, pokud ještě neměl narozeniny.", "výpočet")
        }
        rx("kolik let (je |uplynulo |ubehlo )?(od|od roku) (\\d{3,4})").find(f)?.let { m ->
            val y = m.groupValues[3].toInt()
            if (y <= today.year) return ans("Od roku $y uplynulo **${today.year - y} let**.", "výpočet")
        }

        // ---------------- baterie
        if (rx("^(kolik (mam |ma telefon )?baterie|kolik procent mam|kolik mam procent|baterka|stav baterky)$").matches(f) && battery != null)
            return ans("Baterie má **$battery %**" + if (charging == true) " a nabíjí se." else ".", "telefon")

        // ---------------- kurzy ČNB
        rx("^(?:jaky je |aktualni )?kurz (eura|eur|dolaru|usd|libry|gbp|franku|chf|zlote\\w*|pln|jenu|jpy)$").find(f)?.let { m ->
            val code = when { m.groupValues[1].startsWith("eur") -> "EUR"; m.groupValues[1].startsWith("dol") || m.groupValues[1] == "usd" -> "USD"; m.groupValues[1].startsWith("lib") || m.groupValues[1] == "gbp" -> "GBP"
                m.groupValues[1].startsWith("fra") || m.groupValues[1] == "chf" -> "CHF"; m.groupValues[1].startsWith("zl") || m.groupValues[1] == "pln" -> "PLN"; else -> "JPY" }
            val r = rates.of(code) ?: return null
            return ans("1 $code = **${num(r, 3)} Kč** (${rates.label()}).", "kurz", 92)
        }

        // ---------------- převody v přirozené řeči: „5 km v metrech“, „100 eur na koruny“, „palec v cm“, „10 eur“
        rx("^(?:kolik je |kolik dela |preved |prevod |prepocitej )?(\\d+(?:[.,]\\d+)?)?\\s*([a-z°][a-z0-9°/ ]{0,27}?)\\s+(?:v|ve|na|do|=)\\s+([a-z°][a-z0-9°/ ]{0,27})$").find(f)?.let { m ->
            val v = m.groupValues[1].ifBlank { "1" }.replace(',', '.').toDoubleOrNull() ?: return@let
            val from = unit(m.groupValues[2]) ?: return@let
            val to = unit(m.groupValues[3]) ?: return@let
            Units.convert(v, from, to, rates).getOrNull()?.let { r ->
                fun show(u: String) = mapOf("c" to "°C", "f" to "°F", "k" to "K", "czk" to "Kč", "eur" to "EUR", "usd" to "USD", "gbp" to "GBP", "chf" to "CHF", "pln" to "PLN", "jpy" to "JPY", "ps" to "k", "in" to "palců", "mi" to "mil", "ft" to "stop")[u] ?: u
                return ans("${Practical.pretty(v, 4)} ${show(from)} = **${Practical.pretty(r, 4)} ${show(to)}**" + if (from in setOf("eur", "usd", "gbp", "chf", "pln", "jpy", "czk")) " (${rates.label()})" else "", "převod")
            }
        }
        rx("^(?:kolik je |kolik dela )?(\\d+(?:[.,]\\d+)?) (eur\\w*|dolar\\w*|usd|liber|libr\\w*|frank\\w*|zlot\\w*)$").find(f)?.let { m ->
            val v = m.groupValues[1].replace(',', '.').toDouble()
            val from = unit(m.groupValues[2]) ?: return@let
            Units.convert(v, from, "czk", rates).getOrNull()?.let { return ans("${Practical.pretty(v)} ${m.groupValues[2]} = **${Practical.pretty(it)} Kč** (${rates.label()}).", "převod", 92) }
        }

        // ---------------- procenta slovy, počty slovy
        rx("(\\d+(?:[.,]\\d+)?) ?(procent\\w*|%) (z|ze) (\\d[\\d ]*(?:[.,]\\d+)?)").find(f)?.let { m ->
            val p = m.groupValues[1].replace(',', '.').toDouble(); val base = m.groupValues[4].replace(" ", "").replace(',', '.').toDouble()
            return ans("${Practical.pretty(p)} % z ${Practical.pretty(base)} = **${Practical.pretty(base * p / 100)}**", "procenta")
        }
        toExpr(f)?.let { e -> Calc.eval(e).getOrNull()?.takeIf { it.isFinite() }?.let { return ans("${e.replace("*pi/180", "°")} = **${Practical.pretty(it, 6)}**", "výpočet") } }
        return null
    }
}
