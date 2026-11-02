package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx

/**
 * ROZPOZNÁVÁNÍ OTÁZEK – převede otázku tak, jak ji člověk napíše, na kanonický tvar, kterému rozumí reflexy.
 *
 *  „Prosím tě, můžeš mi říct, kolik je dneska hoďin?“  →  „kolik je dnes hodin“
 *  „kolik metrů je 5 km“                               →  „5 km na m“
 *  „kolik je třetina z 90“                             →  „90 / 3“
 *  „kolik je sedm krát osm“                            →  „kolik je 7 krat 8“
 *
 * Deterministické a rychlé (desítky µs) – žádný model. Reflexy dostanou nejdřív původní znění,
 * normalizované varianty až když původní neprojde → nic, co už fungovalo, se nerozbije.
 */
object QuestionNorm {

    /** Obaly na začátku: oslovení, zdvořilost, „řekni mi“, „víš“, „spočítej“… (opakovaně, v libovolném pořadí). */
    private val lead = Regex(
        "^(?:avior|omega|hej|hele|helejte|ahoj|cau|cus|cauky|nazdar|zdar|zdarec|zdravim|dobry den|prosim te|prosim vas|prosimte|prosim|pls|plz|please|" +
            "rekni mi|rekni|reknes mi|muzes mi rict|muzes mi prosim rict|muzes rict|mohl bys mi rict|mohla bys mi rict|mohl bys|mohla bys|dokazes mi rict|" +
            "nevis|vis|nevite|vite|zajimalo by me|zajima me|potrebuju vedet|potrebuji vedet|chci vedet|chtel bych vedet|chtela bych vedet|" +
            "rad bych vedel|rada bych vedela|zjisti mi|zjisti|napis mi|dyt mi|dyt|vo|jen|tak|a|no|no a|takze|otazka|hm+|ehm|cus)\\b[\\s,:]*",
    )
    private val tail = Regex("[\\s,]*(?:prosim(?: te| vas)?|prosimte|pls|plz|please|thx|diky|dik|dekuji|dekuju|avior|jo|ne|ze|ve|vole|kamo|bracho|brasko|kamosi|" +
        "esi vis|jestli vis|vis|vlastne|teda|sak|gde|pak|jako|ok|okej)$")
    /** Výplňová slova kdekoli ve větě (nesou emoci, ne obsah). */
    private val fillers = setOf("vole", "kamo", "kamosi", "bracho", "brasko", "sak", "teda", "vlastne", "zrovna", "presne", "prosimte", "pls", "plz",
        "kurna", "sakra", "jakoze", "proste", "fakt", "vubec", "asi", "aktualne", "momentalne", "dyt")

    /** Celé anglické fráze → česky (krátké běžné dotazy). */
    private val english = listOf(
        "^what time is it( now)?$|^what s the time$|^time$|^current time$" to "kolik je hodin",
        "^what s the date( today)?$|^what is the date( today)?$|^today s date$|^what day is (it|today)( today)?$|^date$" to "jaky je dnes den",
        "^how are you( doing)?$|^how s it going$" to "jak se mas", "^who are you$|^what are you$|^are you (an )?ai$" to "kdo jsi",
        "^(thanks|thank you|thx|ty)( a lot| very much)?$" to "diky", "^(hello|hi|hey)$" to "ahoj", "^good morning$" to "dobre rano", "^good night$" to "dobrou noc",
        "^flip a coin$|^heads or tails$" to "hod minci", "^roll (a )?(dice|die)$" to "hod kostkou", "^tell me a joke$|^joke$" to "rekni vtip",
        "^what can you do$" to "co umis",
    ).map { Regex(it.first) to it.second }
    private val enCountry = mapOf(
        "france" to "francie", "germany" to "nemecka", "italy" to "italie", "spain" to "spanelska", "poland" to "polska", "austria" to "rakouska",
        "slovakia" to "slovenska", "hungary" to "madarska", "england" to "anglie", "uk" to "velke britanie", "united kingdom" to "velke britanie",
        "britain" to "velke britanie", "usa" to "usa", "america" to "usa", "united states" to "usa", "japan" to "japonska", "china" to "ciny", "russia" to "ruska",
        "ukraine" to "ukrajiny", "czechia" to "ceska", "czech republic" to "ceska", "croatia" to "chorvatska", "greece" to "recka", "portugal" to "portugalska",
        "netherlands" to "nizozemska", "switzerland" to "svycarska", "sweden" to "svedska", "norway" to "norska", "canada" to "kanady", "australia" to "australie",
        "india" to "indie", "brazil" to "brazilie", "egypt" to "egypta", "turkey" to "turecka", "ireland" to "irska", "denmark" to "danska", "finland" to "finska",
    )

    /**
     * Slovník: obecná čeština, slang, nářečí (Haná, Slezsko, Valašsko), slovenština, chatové zkratky → spisovná čeština (bez diakritiky).
     * Jen celá slova – „vosum“ ano, ale „vosa“ se nemění.
     */
    private val words = mapOf(
        // obecná čeština a slang
        "dneska" to "dnes", "dnesk" to "dnes", "dneskam" to "dnes", "tedka" to "ted", "tedko" to "ted", "tedy" to "ted", "zejtra" to "zitra", "zitrek" to "zitra",
        "vcerejsi" to "vcera", "jakej" to "jaky", "jaky" to "jaky", "kterej" to "ktery", "velkej" to "velky", "kolikatyho" to "kolikateho", "kolikatyho" to "kolikateho",
        "hodek" to "hodin", "hodky" to "hodin", "hoda" to "hodin", "hodyn" to "hodin", "hdin" to "hodin", "hodn" to "hodin", "hodinek" to "hodin",
        "vterin" to "sekund", "vteriny" to "sekundy", "vterina" to "sekunda", "vterinu" to "sekundu", "sekundy" to "sekundy",
        "kila" to "kg", "kilo" to "kg", "kil" to "kg", "deka" to "dag", "kilak" to "km", "kilaky" to "km", "kilacku" to "km", "kilometrak" to "km",
        "metropole" to "hlavni mesto", "metropoli" to "hlavni mesto", "hlavak" to "hlavni mesto", "hlavaku" to "hlavni mesto",
        "prezos" to "prezident", "prezik" to "prezident", "premos" to "premier", "premierka" to "premier",
        "sedum" to "sedm", "vosum" to "osm", "vosm" to "osm", "vosmi" to "osmi", "devet" to "devet", "jeden" to "jeden",
        "stovka" to "100 korun", "stovku" to "100 korun", "pade" to "50 korun", "litr" to "litr", "kilo" to "kg",
        "bejt" to "byt", "bejvat" to "byvat", "mlejn" to "mlyn", "dobrej" to "dobry", "novej" to "novy",
        "diky" to "diky", "dik" to "diky", "dekuju" to "dekuji", "dk" to "diky", "dekec" to "diky", "thx" to "diky", "dikec" to "diky", "dikes" to "diky",
        // nářečí
        "kolek" to "kolik", "kolike" to "kolik", "kere" to "ktere", "kery" to "ktery", "kera" to "ktera", "keremu" to "kteremu", "gde" to "kde", "esi" to "jestli",
        "dnes" to "dnes", "dnuska" to "dnes", "dnesky" to "dnes", "zitrek" to "zitra", "hodinu" to "hodinu",
        // slovenština
        "kolko" to "kolik", "aky" to "jaky", "aka" to "jaka", "ake" to "jake", "akeho" to "jakeho", "kto" to "kdo", "kedy" to "kdy", "ako" to "jak",
        "sa" to "se", "som" to "jsem", "si" to "si", "dnesny" to "dnesni", "dnesne" to "dnesni", "zajtra" to "zitra", "zajtrajsi" to "zitrejsi",
        "hlavne" to "hlavni", "mesto" to "mesto", "meniny" to "svatek", "meninam" to "svatek", "vianoc" to "vanoc", "vianoce" to "vanoce",
        "vianocam" to "vanocum", "dakujem" to "dekuji", "dakujeme" to "dekujeme", "dakujes" to "dekujes", "percent" to "procent", "percenta" to "procent",
        "napisal" to "napsal", "napisala" to "napsala", "vojna" to "valka", "vojny" to "valky", "svetova" to "svetova", "francuzska" to "francie",
        "talianska" to "italie", "spanielska" to "spanelska", "rakuska" to "rakouska", "svajciarska" to "svycarska", "grecka" to "recka",
        "anglicka" to "anglie", "nemecka" to "nemecka", "polsku" to "polsku", "mena" to "mena", "krat" to "krat", "co" to "co", "ktory" to "ktery",
        "ktora" to "ktera", "ktore" to "ktere", "ked" to "kdyz", "teraz" to "ted", "vcera" to "vcera", "rok" to "rok", "ano" to "ano", "nie" to "ne",
        "datum" to "datum", "den" to "den", "hodin" to "hodin", "dem" to "den", "dne" to "den",
    )

    /** Slova, proti kterým se opravují překlepy (klíčová slova reflexů + státy). Krátká slova (≤ 3) se neopravují. */
    private val vocab: Set<String> by lazy {
        val core = ("kolik kolikateho kolikaty hodin hodiny datum dnes zitra vcera pozitri jaky jaka jake ktery ktera ktere kdo kdy kde jak proc " +
            "hlavni mesto mena svatek jmeniny vanoc vanoce velikonoce silvestra prezident premier napsal slozil namaloval vynalezl objevil zalozil " +
            "babicku procent procenta krat deleno plus minus odmocnina mocnina stupnu celsia fahrenheita kilometru metru centimetru gramu litru " +
            "planet planeta nejvyssi nejdelsi nejvetsi hora reka ocean obyvatel rozloha kurz eura dolaru korun sekund minut tydnu mesicu roku " +
            "zacala skoncila valka svetova vznikla bitva revoluce nato clenu statu vtip minci kostkou nahodne cislo baterie svitilnu zapni vypni " +
            "dekuji diky ahoj jmenujes umis delas vytvoril znacka prvek vzorec vody vzdalenost daleko mesic slunce teplota kruhu").split(' ')
        val states = runCatching {
            QuestionNorm::class.java.classLoader?.getResourceAsStream("facts/staty.tsv")?.bufferedReader()?.use { r ->
                r.readLines().flatMap { l -> l.split('\t').take(2).map { TextUtil.fold(it).lowercase() } }
            }
        }.getOrNull().orEmpty()
        (core + states.flatMap { it.split(' ') }).filter { it.length >= 4 }.toSet()
    }

    /** Damerau–Levenshtein ≤ max (s ranným ukončením) – překlep, vynechané / přidané / prohozené písmeno. */
    private fun close(a: String, b: String, max: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > max) return false
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return false
        }
        return d[a.length][b.length] <= max
    }

    /** Slovník rozdělený podle délky – porovnává se jen se slovy podobné délky (místo celého slovníku). */
    private val vocabByLen: Map<Int, List<String>> by lazy { vocab.groupBy { it.length } }
    /** Paměť oprav: stejné slovo se podruhé neopravuje (běžné překlepy se opakují). */
    private val typoMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Oprava překlepů: neznámé slovo → nejbližší známé (1 chyba do 7 písmen, 2 chyby od 8). */
    fun fixTypos(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.length < 4 || w.any { it.isDigit() } || w in vocab || w in words) w
        else typoMemo[w] ?: run {
            val max = if (w.length >= 8) 2 else 1
            val hit = (w.length - max..w.length + max).asSequence().flatMap { vocabByLen[it].orEmpty().asSequence() }
                .filter { it[0] == w[0] || it.length >= 6 }.firstOrNull { close(w, it, max) } ?: w
            if (typoMemo.size < 5000) typoMemo[w] = hit
            hit
        }
    }

    private val ones = mapOf(
        "nula" to 0, "jedna" to 1, "jeden" to 1, "jedno" to 1, "dva" to 2, "dve" to 2, "tri" to 3, "ctyri" to 4, "pet" to 5, "sest" to 6,
        "sedm" to 7, "osm" to 8, "devet" to 9, "deset" to 10, "jedenact" to 11, "dvanact" to 12, "trinact" to 13, "ctrnact" to 14,
        "patnact" to 15, "sestnact" to 16, "sedmnact" to 17, "osmnact" to 18, "devatenact" to 19,
    )
    private val tens = mapOf("dvacet" to 20, "tricet" to 30, "ctyricet" to 40, "padesat" to 50, "sedesat" to 60, "sedmdesat" to 70, "osmdesat" to 80, "devadesat" to 90)
    private val hundreds = mapOf("dveste" to 200, "dvesta" to 200, "trista" to 300, "ctyrista" to 400, "petset" to 500, "sestset" to 600,
        "sedmset" to 700, "osmset" to 800, "devetset" to 900)
    private val big = mapOf("sto" to 100, "set" to 100, "tisic" to 1000, "tisice" to 1000, "milion" to 1_000_000, "milionu" to 1_000_000)
    private val ordinalPow = mapOf("druhou" to 2, "treti" to 3, "ctvrtou" to 4, "patou" to 5, "sestou" to 6, "sedmou" to 7, "osmou" to 8, "devatou" to 9, "desatou" to 10)
    private val fractions = mapOf("polovin" to 2, "pul" to 2, "tretin" to 3, "ctvrtin" to 4, "petin" to 5, "desetin" to 10, "setin" to 100)

    /** Jednotka v 2. pádě množného čísla („kolik METRŮ je…“) → značka, které rozumí převodník. */
    private val unitGen = mapOf(
        "metru" to "m", "centimetru" to "cm", "milimetru" to "mm", "kilometru" to "km", "decimetru" to "dm", "gramu" to "g", "kilogramu" to "kg",
        "kg" to "kg", "dag" to "dag", "litru" to "l", "mililitru" to "ml", "decilitru" to "dl", "mil" to "mi", "stop" to "ft", "palcu" to "in", "liber" to "lb",
        "unci" to "oz", "tun" to "t", "sekund" to "s", "minut" to "min", "hodin" to "h", "dni" to "d", "dnu" to "d",
    )
    private val unitNom = mapOf(
        "metr" to "m", "km" to "km", "kilometr" to "km", "centimetr" to "cm", "milimetr" to "mm", "kg" to "kg", "kilogram" to "kg", "gram" to "g",
        "litr" to "l", "mile" to "mi", "mil" to "mi", "stopa" to "ft", "palec" to "in", "libra" to "lb", "unce" to "oz", "tuna" to "t",
        "m" to "m", "cm" to "cm", "mm" to "mm", "g" to "g", "l" to "l", "ml" to "ml", "mi" to "mi", "ft" to "ft", "in" to "in", "lb" to "lb", "t" to "t",
        "galon" to "gal", "gal" to "gal",
    )

    /** Kanonické varianty otázky (bez původní), od nejmenší změny po největší. */
    fun variants(prompt: String): List<String> {
        if (prompt.length > 160 || prompt.contains('\n')) return emptyList()
        // ÚSPORA: otázky, na které stačí reflex, jsou krátké → dlouhé zadání (pro mozek) se vůbec nenormalizuje
        if (prompt.count { it == ' ' } > 13) return emptyList()
        val out = LinkedHashSet<String>()
        // tečku na konci smazat, ale ne za číslem data („24. 12.“); apostrof (what's) → mezera
        var f = TextUtil.fold(prompt).lowercase().replace(rx("[?!…]+"), " ").replace('\'', ' ').replace('’', ' ')
            .replace(rx("\\s+"), " ").trim().replace(rx("(?<!\\d)\\.+$"), "").trim().trimEnd(',', ' ')
        // °C / °F → slova, která převodník zná
        f = f.replace(rx("(\\d)\\s*°?\\s*c\\b"), "$1 stupnu celsia").replace(rx("(\\d)\\s*°?\\s*f\\b"), "$1 stupnu fahrenheita").replace('°', ' ')
        // chat: „2x3“ → „2 krat 3“, „sqrt 81“ → „odmocnina z 81“
        f = f.replace(rx("(\\d)\\s*x\\s*(\\d)"), "$1 krat $2").replace(rx("\\bsqrt\\s*\\(?(\\d+(?:[.,]\\d+)?)\\)?"), "odmocnina z $1")
        // 0) angličtina
        english(f)?.let { out += it; return out.toList() }
        // 1) obaly pryč (opakovaně: „prosím tě, víš, kolik…“)
        f = stripWrap(f)
        if (f.isEmpty()) return emptyList()
        out += f
        // 2) slovník (slang, nářečí, slovenština) + výplňová slova pryč
        f = f.replace(",", " ").split(' ').filter { it.isNotBlank() && it !in fillers }.joinToString(" ") { words[it] ?: it }
            .replace(rx("\\bhl\\.? ?mest"), "hlavni mest")
            .replace(rx("^kolik (mame|je ted|je to|mame ted) hodin$"), "kolik je hodin")
            .replace(rx("^(cas|kolik je cas|jaky je cas|jaky mame cas|presny cas|aktualni cas|kolik je h|kolik je hod|kolik hodin)$"), "kolik je hodin")
        f = stripWrap(f)
        out += f
        // 3) přeformulování na tvary, které reflexy znají
        val r = rephrase(f)
        out += r
        // 4) čísla slovy → číslice (jen jako další varianta – „jeden“ může být i zájmeno)
        val n = numbers(r)
        if (n != r) { out += n; out += rephrase(n) }
        // 5) překlepy (až nakonec: nejdražší a nejodvážnější krok)
        val t = if (f.count { it == ' ' } <= 8) fixTypos(f) else f
        if (t != f) { val tr = rephrase(t); out += tr; numbers(tr).let { if (it != tr) out += rephrase(it) } }
        out.remove(TextUtil.fold(prompt).trim())
        return out.filter { it.isNotBlank() }
    }

    /** Obaly a dovětky pryč – opakovaně, ale nikdy ne až do prázdna („díky“ samo je platná zpráva). */
    private fun stripWrap(s: String): String {
        var f = s
        while (true) {
            val n = f.replace(lead, "").replace(tail, "").trim().trim(',', ' ')
            if (n == f || n.isBlank()) return f
            f = n
        }
    }

    private fun english(f: String): String? {
        english.firstOrNull { it.first.matches(f) }?.let { return it.second }
        rx("^(?:what is |what s )?(?:the )?capital (?:city )?of (.+)$").find(f)?.let { m -> enCountry[m.groupValues[1].removePrefix("the ")]?.let { return "hlavni mesto $it" } }
        rx("^(?:how much is |what is |what s )?(\\d+(?:[.,]\\d+)?) ?(?:%|percent) of (\\d+(?:[.,]\\d+)?)$").find(f)?.let { m -> return "${m.groupValues[1]} procent z ${m.groupValues[2]}" }
        rx("^(?:how much is |what is |what s |calculate )?(\\d+(?:[.,]\\d+)?) (times|plus|minus|divided by) (\\d+(?:[.,]\\d+)?)$").find(f)?.let { m ->
            val op = mapOf("times" to "krat", "plus" to "plus", "minus" to "minus", "divided by" to "deleno").getValue(m.groupValues[2])
            return "kolik je ${m.groupValues[1]} $op ${m.groupValues[3]}"
        }
        rx("^(?:give me a |pick a )?random number (?:from|between) (\\d+) (?:to|and) (\\d+)$").find(f)?.let { m -> return "nahodne cislo od ${m.groupValues[1]} do ${m.groupValues[2]}" }
        return null
    }

    private fun rephrase(s: String): String {
        var f = s
        // „kolik metrů je 5 km“ / „kolik gramů má kilo“ → „5 km na m“
        rx("^kolik (\\w+) (?:je|ma|maji|obsahuje|da|dela) (\\d+(?:[.,]\\d+)?)? ?(\\w+)$").find(f)?.let { m ->
            val to = unitGen[m.groupValues[1]]; val from = unitNom[m.groupValues[3]] ?: unitGen[m.groupValues[3]]
            if (to != null && from != null) return "${m.groupValues[2].ifBlank { "1" }} $from na $to"
        }
        // „kolik je 3 kg v gramech“ už převodník zná; „3 kila v gramech“ díky slovníku
        // zlomky: „třetina z 90“ → „90 / 3“, „polovina z 250“ → „250 / 2“
        rx("^(?:kolik je |kolik dela )?(\\w+) (?:z|ze) (\\d+(?:[.,]\\d+)?)$").find(f)?.let { m ->
            fractions.entries.firstOrNull { m.groupValues[1].startsWith(it.key) }?.let { return "kolik je ${m.groupValues[2]} / ${it.value}" }
        }
        // mocniny řadovou číslovkou: „2 na desátou“ → „2 na 10“
        f = f.replace(rx("\\bna (druhou|treti|ctvrtou|patou|sestou|sedmou|osmou|devatou|desatou)\\b")) { "na ${ordinalPow[it.groupValues[1]]}" }
        f = f.replace(rx("\\bdruha odmocnina\\b"), "odmocnina")
        // kurzy: „za kolik je euro“, „kolik stojí dolar“, „kolik je euro“ → „kurz eura“
        rx("^(?:za kolik je|kolik stoji|kolik je|kolik dela|jak stoji|jaky je kurz|kurz) (euro|eura|eur|dolar|dolaru|libra|libry|frank|franku|zloty|zlotych|jen|jenu)(?: v korunach| v kc)?$").find(f)?.let { m ->
            val w = m.groupValues[1]
            return "kurz " + when { w.startsWith("eur") -> "eura"; w.startsWith("dol") -> "dolaru"; w.startsWith("lib") -> "libry"; w.startsWith("fra") -> "franku"; w.startsWith("zlot") -> "zlote"; else -> "jenu" }
        }
        // kalendář
        rx("^za jak dlouho (?:jsou|budou|je|bude|mame) (.+)$").find(f)?.let { m ->
            val t = m.groupValues[1].replace(rx("^vanoce$"), "vanoc").replace(rx("^silvestr$"), "silvestra").replace(rx("^novy rok$"), "noveho roku")
            return "kolik dni do $t"
        }
        f = f.replace(rx("^kolik (dnu|dni) (zbyva |je |ostava |chyby )?do "), "kolik dni do ")
        // „kolik je to korun 50 eur“, „kolik korun je 50 eur“ → „50 eur na korun“
        rx("^kolik (?:je to |je )?(korun|kc|eur|dolaru) (?:je |dela |za |to je )?(\\d+(?:[.,]\\d+)?) (eur\\w*|dolar\\w*|korun\\w*|kc|liber|frank\\w*)$").find(f)?.let { m ->
            return "${m.groupValues[2]} ${m.groupValues[3]} na ${m.groupValues[1]}"
        }
        rx("^kolik (?:je to |stoji )?(?:korun|kc) (?:za|na) (euro|eura|dolar|dolaru|libru)$").find(f)?.let { m -> return "kurz " + if (m.groupValues[1].startsWith("eur")) "eura" else if (m.groupValues[1].startsWith("dol")) "dolaru" else "libry" }
        f = f.replace(rx("^jak se (mas|mate|vede)( ty)?$"), "jak se mas").replace(rx("^jake je dnes datum$|^jaky je dnes datum$"), "jaky je dnes den")
        f = f.replace(rx("^(jaky|jake) je dnesni (datum|den)$"), "jaky je dnes den")
        f = f.replace(rx("^(jaky|ktery) den (je|bude|byl|bylo) "), "$1 $2 den ")
        f = f.replace(rx("\\b(slavi svatek|slavi jmeniny|ma jmeniny)\\b"), "ma svatek")
        f = f.replace(rx("^kdo slavi (dnes |zitra )?(svatek|jmeniny)"), "kdo ma $1svatek").replace(rx("^svatek dnes$"), "kdo ma dnes svatek")
        f = f.replace(rx("^jake je dnes datum$|^dnesni datum$|^jaky mame dnes den$|^co je dnes za den$|^kolikateho je dnes$|^jake mame dnes datum$"), "jaky je dnes den")
        // politika a stát: „prezidentem USA“ → „prezident usa“, „český prezident“ → „prezident cr“
        f = f.replace(rx("\\b(prezidentem|prezidentkou)\\b"), "prezident").replace(rx("\\b(premierem|premierkou)\\b"), "premier")
            .replace(rx("\\bprvnim prezident"), "prvni prezident")
            .replace(rx("^kdo je (cesky|nas) prezident$"), "kdo je prezident").replace(rx("^kdo je (cesky|nas) premier$"), "kdo je premier")
            .replace(rx("^kdo je premier (ceska|cr|ceske republiky)$"), "kdo je premier")
        rx("^kolik (?:ma|je v) (nato|eu|evropska unie|osn) (clenu|statu|zemi|clenskych statu)$").find(f)?.let { return "kolik ${it.groupValues[2].substringBefore(' ')} ma ${it.groupValues[1]}" }
        f = f.replace(rx("^kdy jsme vstoupili do "), "kdy vstoupila cr do ").replace(rx("^kdy cesko vstoupilo do "), "kdy vstoupila cr do ")
        f = f.replace(rx("^(vyznam|co znamena) zkratk[ay] "), "co znamena ").replace(rx("^co je zkratka "), "co znamena ")
        // holá fráze bez otázky („Prezident České republiky“, „premiér“, „hlavní město Polska“) → otázka
        rx("^(prezident|prezidentka|premier|premierka|predseda vlady|kancler|kral|kralovna|papez|generalni tajemnik)( .{0,40})?$").find(f)?.let { return "kdo je ${it.value}" }
        rx("^(hlavni mesto|mena|rozloha|pocet obyvatel|nejvyssi hora|nejdelsi reka) .{2,40}$").find(f)?.let { if (!f.startsWith("jak")) return if (f.startsWith("hlavni")) "jake je ${it.value}" else "jaka je ${it.value}" }
        // identita a náhoda
        f = f.replace(rx("^(jsi|ty jsi|jsi ty) (ai|umela inteligence|robot|chatbot|bot|clovek)$"), "kdo jsi")
        f = f.replace(rx("^(vyber|vylosuj|rekni|dej mi) (nahodne )?cislo od "), "nahodne cislo od ")
        f = f.replace(rx("^hod (si )?(minci|kostkou)$"), "hod $2")
        // „kolik je hodin prosím“ – zbytky zdvořilosti uvnitř
        f = f.replace(rx("\\s+(prosim|prosim te)$"), "")
        return f.trim()
    }

    /** „sedm krát osm“ → „7 krát 8“, „dvacet pět“ → „25“, „tisíc“ → „1000“. */
    fun numbers(s: String): String {
        val toks = s.split(' ')
        val out = ArrayList<String>(toks.size)
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val tv = tens[t]
            when {
                tv != null && i + 1 < toks.size && (ones[toks[i + 1]] ?: 0) in 1..9 -> { out += (tv + ones.getValue(toks[i + 1])).toString(); i += 2; continue }
                tv != null -> out += tv.toString()
                hundreds[t] != null -> out += hundreds.getValue(t).toString()
                ones[t] != null && !(t == "jeden" || t == "jedna" || t == "jedno") -> out += ones.getValue(t).toString()
                (t == "jeden" || t == "jedna" || t == "jedno") && i + 1 < toks.size && toks[i + 1].matches(rx("krat|plus|minus|deleno|procent\\w*|na")) -> out += "1"
                big[t] != null -> {
                    // „pět set“, „dva tisíce“ → násobek předchozího čísla
                    val p = out.lastOrNull()?.toIntOrNull()
                    if (p != null && p < 1000) { out[out.lastIndex] = (p * big.getValue(t)).toString() } else out += big.getValue(t).toString()
                }
                else -> out += t
            }
            i++
        }
        return out.joinToString(" ")
    }
}
