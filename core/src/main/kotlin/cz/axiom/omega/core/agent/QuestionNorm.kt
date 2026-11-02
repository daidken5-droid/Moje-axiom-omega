package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

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
        "^(?:avior|omega|hej|hele|helejte|ahoj|cau|nazdar|zdravim|dobry den|prosim te|prosim vas|prosim|pls|please|" +
            "rekni mi|rekni|reknes mi|muzes mi rict|muzes mi prosim rict|muzes rict|mohl bys mi rict|mohla bys mi rict|mohl bys|mohla bys|dokazes mi rict|" +
            "nevis|vis|nevite|vite|zajimalo by me|zajima me|potrebuju vedet|potrebuji vedet|chci vedet|chtel bych vedet|chtela bych vedet|" +
            "rad bych vedel|rada bych vedela|zjisti mi|zjisti|napis mi|jen|tak|a|no|takze|otazka)\\b[\\s,:]*",
    )
    private val tail = Regex("[\\s,]*(?:prosim(?: te| vas)?|pls|please|diky|dik|dekuji|dekuju|avior)$")

    /** Hovorová čeština a překlepy → spisovně (celá slova). */
    private val words = mapOf(
        "dneska" to "dnes", "dnesk" to "dnes", "tedka" to "ted", "tedko" to "ted", "zejtra" to "zitra", "vcerejsi" to "vcera",
        "jakej" to "jaky", "kterej" to "ktery", "velkej" to "velky", "kolikatyho" to "kolikateho", "kolikaty" to "kolikaty",
        "hodin" to "hodin", "hodyn" to "hodin", "hdin" to "hodin", "hodn" to "hodin",
        "vterin" to "sekund", "vteriny" to "sekundy", "vterina" to "sekunda", "vterinu" to "sekundu",
        "kila" to "kg", "kilo" to "kg", "kil" to "kg", "deka" to "dag", "metropole" to "hlavni mesto", "metropoli" to "hlavni mesto",
        "cesku" to "cesku", "cr" to "cr", "ceska" to "ceska",
    )

    private val ones = mapOf(
        "nula" to 0, "jedna" to 1, "jeden" to 1, "jedno" to 1, "dva" to 2, "dve" to 2, "tri" to 3, "ctyri" to 4, "pet" to 5, "sest" to 6,
        "sedm" to 7, "osm" to 8, "devet" to 9, "deset" to 10, "jedenact" to 11, "dvanact" to 12, "trinact" to 13, "ctrnact" to 14,
        "patnact" to 15, "sestnact" to 16, "sedmnact" to 17, "osmnact" to 18, "devatenact" to 19,
    )
    private val tens = mapOf("dvacet" to 20, "tricet" to 30, "ctyricet" to 40, "padesat" to 50, "sedesat" to 60, "sedmdesat" to 70, "osmdesat" to 80, "devadesat" to 90)
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
        if (prompt.length > 200 || prompt.contains('\n')) return emptyList()
        val out = LinkedHashSet<String>()
        // tečku na konci smazat, ale ne za číslem data („24. 12.“)
        var f = TextUtil.fold(prompt).replace(Regex("[?!…]+"), " ").replace(Regex("\\s+"), " ").trim().replace(Regex("(?<!\\d)\\.+$"), "").trim()
        // °C / °F → slova, která převodník zná
        f = f.replace(Regex("(\\d)\\s*°?\\s*c\\b"), "$1 stupnu celsia").replace(Regex("(\\d)\\s*°?\\s*f\\b"), "$1 stupnu fahrenheita").replace('°', ' ')
        // 1) obaly pryč (opakovaně: „prosím tě, víš, kolik…“)
        var prev: String
        do { prev = f; f = f.replace(lead, "").replace(tail, "").trim().trimStart(',', ' ') } while (f != prev && f.isNotEmpty())
        if (f.isEmpty()) return emptyList()
        out += f
        // 2) hovorová slova a překlepy
        f = f.split(' ').joinToString(" ") { words[it] ?: it }
            .replace(Regex("\\bhl\\.? ?mest"), "hlavni mest")
            .replace(Regex("\\bkolik mame hodin\\b"), "kolik je hodin").replace(Regex("^kolik je ted hodin$"), "kolik je hodin")
            .replace(Regex("^(cas|kolik je cas|jaky je cas|jaky mame cas)$"), "kolik je hodin")
        out += f
        // 3) přeformulování na tvary, které reflexy znají
        val r = rephrase(f)
        out += r
        // 4) čísla slovy → číslice (jen jako další varianta – „jeden“ může být i zájmeno)
        val n = numbers(r)
        if (n != r) { out += n; out += rephrase(n) }
        out.remove(TextUtil.fold(prompt).trim())
        return out.filter { it.isNotBlank() }
    }

    private fun rephrase(s: String): String {
        var f = s
        // „kolik metrů je 5 km“ / „kolik gramů má kilo“ → „5 km na m“
        Regex("^kolik (\\w+) (?:je|ma|maji|obsahuje|da|dela) (\\d+(?:[.,]\\d+)?)? ?(\\w+)$").find(f)?.let { m ->
            val to = unitGen[m.groupValues[1]]; val from = unitNom[m.groupValues[3]] ?: unitGen[m.groupValues[3]]
            if (to != null && from != null) return "${m.groupValues[2].ifBlank { "1" }} $from na $to"
        }
        // „kolik je 3 kg v gramech“ už převodník zná; „3 kila v gramech“ díky slovníku
        // zlomky: „třetina z 90“ → „90 / 3“, „polovina z 250“ → „250 / 2“
        Regex("^(?:kolik je |kolik dela )?(\\w+) (?:z|ze) (\\d+(?:[.,]\\d+)?)$").find(f)?.let { m ->
            fractions.entries.firstOrNull { m.groupValues[1].startsWith(it.key) }?.let { return "kolik je ${m.groupValues[2]} / ${it.value}" }
        }
        // mocniny řadovou číslovkou: „2 na desátou“ → „2 na 10“
        f = f.replace(Regex("\\bna (druhou|treti|ctvrtou|patou|sestou|sedmou|osmou|devatou|desatou)\\b")) { "na ${ordinalPow[it.groupValues[1]]}" }
        f = f.replace(Regex("\\bdruha odmocnina\\b"), "odmocnina")
        // kurzy: „za kolik je euro“, „kolik stojí dolar“, „kolik je euro“ → „kurz eura“
        Regex("^(?:za kolik je|kolik stoji|kolik je|kolik dela|jak stoji|jaky je kurz|kurz) (euro|eura|eur|dolar|dolaru|libra|libry|frank|franku|zloty|zlotych|jen|jenu)(?: v korunach| v kc)?$").find(f)?.let { m ->
            val w = m.groupValues[1]
            return "kurz " + when { w.startsWith("eur") -> "eura"; w.startsWith("dol") -> "dolaru"; w.startsWith("lib") -> "libry"; w.startsWith("fra") -> "franku"; w.startsWith("zlot") -> "zlote"; else -> "jenu" }
        }
        // kalendář
        Regex("^za jak dlouho (?:jsou|budou|je|bude|mame) (.+)$").find(f)?.let { m ->
            val t = m.groupValues[1].replace(Regex("^vanoce$"), "vanoc").replace(Regex("^silvestr$"), "silvestra").replace(Regex("^novy rok$"), "noveho roku")
            return "kolik dni do $t"
        }
        f = f.replace(Regex("^kolik (dnu|dni) (zbyva )?do "), "kolik dni do ")
        f = f.replace(Regex("^(jaky|ktery) den (je|bude|byl|bylo) "), "$1 $2 den ")
        f = f.replace(Regex("\\b(slavi svatek|slavi jmeniny|ma jmeniny)\\b"), "ma svatek")
        f = f.replace(Regex("^kdo slavi (dnes |zitra )?(svatek|jmeniny)"), "kdo ma $1svatek").replace(Regex("^svatek dnes$"), "kdo ma dnes svatek")
        f = f.replace(Regex("^jake je dnes datum$|^dnesni datum$|^jaky mame dnes den$|^co je dnes za den$|^kolikateho je dnes$|^jake mame dnes datum$"), "jaky je dnes den")
        // politika a stát: „prezidentem USA“ → „prezident usa“, „český prezident“ → „prezident cr“
        f = f.replace(Regex("\\b(prezidentem|prezidentkou)\\b"), "prezident").replace(Regex("\\b(premierem|premierkou)\\b"), "premier")
            .replace(Regex("\\bprvnim prezident"), "prvni prezident")
            .replace(Regex("^kdo je (cesky|nas) prezident$"), "kdo je prezident").replace(Regex("^kdo je (cesky|nas) premier$"), "kdo je premier")
            .replace(Regex("^kdo je premier (ceska|cr|ceske republiky)$"), "kdo je premier")
        Regex("^kolik (?:ma|je v) (nato|eu|evropska unie|osn) (clenu|statu|zemi|clenskych statu)$").find(f)?.let { return "kolik ${it.groupValues[2].substringBefore(' ')} ma ${it.groupValues[1]}" }
        f = f.replace(Regex("^kdy jsme vstoupili do "), "kdy vstoupila cr do ").replace(Regex("^kdy cesko vstoupilo do "), "kdy vstoupila cr do ")
        f = f.replace(Regex("^(vyznam|co znamena) zkratk[ay] "), "co znamena ").replace(Regex("^co je zkratka "), "co znamena ")
        // identita a náhoda
        f = f.replace(Regex("^(jsi|ty jsi|jsi ty) (ai|umela inteligence|robot|chatbot|bot|clovek)$"), "kdo jsi")
        f = f.replace(Regex("^(vyber|vylosuj|rekni|dej mi) (nahodne )?cislo od "), "nahodne cislo od ")
        f = f.replace(Regex("^hod (si )?(minci|kostkou)$"), "hod $2")
        // „kolik je hodin prosím“ – zbytky zdvořilosti uvnitř
        f = f.replace(Regex("\\s+(prosim|prosim te)$"), "")
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
                ones[t] != null && !(t == "jeden" || t == "jedna" || t == "jedno") -> out += ones.getValue(t).toString()
                (t == "jeden" || t == "jedna" || t == "jedno") && i + 1 < toks.size && toks[i + 1].matches(Regex("krat|plus|minus|deleno|procent\\w*|na")) -> out += "1"
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
