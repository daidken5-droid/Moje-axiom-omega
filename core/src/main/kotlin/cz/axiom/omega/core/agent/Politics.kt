package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx

/**
 * POLITIKA BEZ MOZKU – reflexy nad offline daty (resources/facts/strany.tsv, pravice.tsv):
 *  • historie a stálá fakta (premiéři po letech, vítězové voleb, NATO/EU/OSN, klíčové události) – jistota 97,
 *  • funkce s pevným mandátem (prezident USA, předsedkyně Komise, šéf NATO, premiér ČR) – jistota 90,
 *    vždy s „stav k …“, protože se mohou změnit,
 *  • strany („Co je ODS?“) – jistota 92,
 *  • pravicový pohled na téma – jistota 90, VŽDY označený jako pohled a s hlavním protiargumentem.
 */
object Politics {

    private const val STATE = "stav k 2026"

    private fun res(name: String): List<List<String>> {
        val s = Politics::class.java.classLoader?.getResourceAsStream("facts/$name") ?: return emptyList()
        return s.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() }.map { it.split('\t') } }
    }
    private val parties by lazy { res("strany.tsv") }
    private val right by lazy { res("pravice.tsv") }

    /** Premiéři ČR: (od roku, do roku, jméno a strana). */
    private val pms = listOf(
        Triple(1993, 1998, "Václav Klaus (ODS)"), Triple(1998, 1998, "Josef Tošovský (úřednická vláda)"), Triple(1998, 2002, "Miloš Zeman (ČSSD)"),
        Triple(2002, 2004, "Vladimír Špidla (ČSSD)"), Triple(2004, 2005, "Stanislav Gross (ČSSD)"), Triple(2005, 2006, "Jiří Paroubek (ČSSD)"),
        Triple(2006, 2009, "Mirek Topolánek (ODS)"), Triple(2009, 2010, "Jan Fischer (úřednická vláda)"), Triple(2010, 2013, "Petr Nečas (ODS)"),
        Triple(2013, 2014, "Jiří Rusnok (úřednická vláda)"), Triple(2014, 2017, "Bohuslav Sobotka (ČSSD)"), Triple(2017, 2021, "Andrej Babiš (ANO)"),
        Triple(2021, 2025, "Petr Fiala (ODS)"), Triple(2025, 2099, "Andrej Babiš (ANO)"),
    )
    private val elections = mapOf(
        2025 to "Sněmovní volby 2025 (3.–4. 10.) vyhrálo **hnutí ANO** (přibližně třetina hlasů) před koalicí SPOLU; vládu sestavilo ANO se SPD a Motoristy sobě, premiérem je Andrej Babiš ($STATE).",
        2021 to "Sněmovní volby 2021 (8.–9. 10.) těsně vyhrála koalice **SPOLU** (ODS, KDU-ČSL, TOP 09, cca 27,8 %) před ANO (cca 27,1 %); vládu sestavil Petr Fiala (SPOLU + Piráti a STAN).",
        2017 to "Sněmovní volby 2017 vyhrálo **hnutí ANO** (cca 29,6 %); premiérem se stal Andrej Babiš.",
        2013 to "Předčasné sněmovní volby 2013 vyhrála **ČSSD** (cca 20,5 %); vládu ČSSD, ANO a KDU-ČSL vedl Bohuslav Sobotka.",
    )

    private fun ans(t: String, kind: String, conf: Int) = Instant.Answer(t, kind, confidence = conf)

    /** Rok, ke kterému platí funkce s mandátem (premiér, prezident USA…). */
    const val STATE_YEAR = 2026

    /**
     * REVIZNÍ SMLOUVA tabulky (FAULTLINE F-05): „stav k 2026“ dřív platil navždy s jistotou 90. Po skončení roku
     * STATE_YEAR + 1 klesne jistota proměnlivých odpovědí (90) na 80 – pod práh Auto, takže je převezme mozek / web.
     * Historie (97) platí dál.
     */
    fun answer(prompt: String, today: java.time.LocalDate = java.time.LocalDate.now()): Instant.Answer? =
        runCatching { match(prompt) }.getOrNull()?.let { a ->
            if (a.confidence <= 90 && today.year > STATE_YEAR + 1) a.copy(confidence = 80, text = a.text + "\n\n*Údaj může být zastaralý – ověřím.*") else a
        }

    private fun match(prompt: String): Instant.Answer? {
        val raw = prompt.trim()
        if (raw.isEmpty() || raw.length > 160) return null
        val f = TextUtil.fold(raw).replace(rx("\\s+"), " ").trim().trimEnd('?', '.', '!', ' ')

        // ---------------- pravicový pohled na téma (vždy označený)
        if (rx("pravic|konzervativ|pravy stred").containsMatchIn(f) && rx("pohled|nazor|postoj|mysli|vidi|stanovisk|co rika|jak se diva|pristup").containsMatchIn(f)) {
            val hit = right.firstOrNull { row -> row[0].split('|').any { rx("\\b$it").containsMatchIn(f) } }
            if (hit != null) return ans("**Pravicový pohled – ${hit[1]}** _(názorový směr, ne fakta)_:\n${hit[2]}\n\n_Hlavní protiargument:_ ${hit[3]}", "pravicový pohled", 90)
            if (rx("^(jake|co|jaky|shrn)").containsMatchIn(f) && rx("hodnot|zaklad|obecne|politik").containsMatchIn(f))
                return ans("**Pravicový pohled – základní hodnoty** _(názorový směr)_: svoboda jednotlivce a osobní odpovědnost, soukromé vlastnictví, právní stát, omezený a efektivní stát, volný trh, vyrovnané veřejné finance, u konzervativců tradiční instituce (rodina, národ, kulturní kořeny), silná obrana a spojenectví se Západem.\n\n_Hlavní protiargument:_ kritici upozorňují na nerovnosti a na to, že trh sám neřeší veřejné statky ani sociální rizika.", "pravicový pohled", 90)
        }

        // ---------------- strany
        rx("^(?:co je|co je to|kdo je|predstav|charakterizuj|jaka strana je|jake je)\\s+(?:strana |hnuti |koalice )?(.{2,25})$").find(f)?.let { m ->
            val q = m.groupValues[1].trim()
            parties.firstOrNull { q == it[0] || q.startsWith(it[0]) && q.length <= it[0].length + 3 }?.let { return ans("**${it[1]}** – ${it[2]}", "politika", 92) }
        }

        // ---------------- premiéři
        if (rx("\\b(premier|predseda vlady|predsedou vlady|predsedkyne vlady)").containsMatchIn(f)) {
            if (rx("^(seznam|vsichni|vyjmenuj|prehled|jaci|kteri)").containsMatchIn(f))
                return ans("**Předsedové vlád ČR:**\n" + pms.joinToString("\n") { "- ${it.third} (${it.first}–${if (it.second > 2090) "dosud, $STATE" else "${it.second}"})" }, "politika", 97)
            rx("\\b(19[9]\\d|20[0-4]\\d)\\b").find(f)?.value?.toInt()?.let { y ->
                val who = pms.filter { y in it.first..it.second }
                if (who.isNotEmpty() && rx("^(kdo|kdo byl|jaky|ktery)").containsMatchIn(f))
                    return ans("V roce $y byl premiérem ČR **" + who.joinToString("**, pak **") { it.third } + "**.", "politika", if (y >= 2025) 90 else 97)
            }
            if (rx("prvni premier").containsMatchIn(f)) return ans("Prvním premiérem samostatné ČR byl **Václav Klaus** (ODS), 1993 – leden 1998.", "politika", 97)
            if (rx("^kdo je (soucasny |aktualni |nynejsi |dnes )?(cesky |nas )?(premier|predseda vlady)").containsMatchIn(f))
                return ans("Premiérem ČR je **Andrej Babiš** (ANO), jmenován v prosinci 2025 – vláda ANO, SPD a Motoristů sobě ($STATE, ověř).", "politika", 90)
        }

        // ---------------- volby
        if (rx("\\b(vyhral\\w*|vitez\\w*|vysledk\\w*)\\b").containsMatchIn(f) && rx("\\bvolb").containsMatchIn(f) && !rx("prezident|senat|evrop|krajsk|komunal|americk|usa").containsMatchIn(f)) {
            val y = Regex("\\b(20\\d\\d)\\b").find(f)?.value?.toInt() ?: if (Regex("posledn|minul").containsMatchIn(f)) 2025 else null
            y?.let { elections[it] }?.let { return ans(it, "politika", if (y == 2025) 92 else 97) }
        }
        if (rx("prezidentsk\\w* volb\\w* 2023|kdo vyhral (prime )?volb\\w* prezidenta 2023").containsMatchIn(f))
            return ans("Prezidentskou volbu 2023 vyhrál **Petr Pavel** (ve 2. kole 58,3 % proti Andreji Babišovi).", "politika", 97)

        // ---------------- funkce s pevným mandátem (vždy se stavem)
        if (rx("^kdo je (soucasny |aktualni |nynejsi )?(americky )?prezident (usa|spojenych statu|ameriky)|^kdo je americky prezident|^prezident usa$").containsMatchIn(f))
            return ans("Prezidentem USA je **Donald Trump** (republikán, 47. prezident) od 20. 1. 2025; funkční období do ledna 2029 ($STATE).", "politika", 90)
        if (rx("(predsed\\w*|sef\\w*) (evropske komise|ek\\b)").containsMatchIn(f))
            return ans("Předsedkyní Evropské komise je **Ursula von der Leyenová** (2. funkční období 2024–2029, $STATE).", "politika", 90)
        if (rx("(generaln\\w* tajemnik|sef) nato").containsMatchIn(f))
            return ans("Generálním tajemníkem NATO je **Mark Rutte** (od 1. 10. 2024, $STATE).", "politika", 90)

        // ---------------- NATO, EU, OSN – stálá fakta
        if (rx("^kolik (ma |je )?(clenu|statu|zemi) (ma )?(nato|v nato)|^kolik clenu ma nato").containsMatchIn(f))
            return ans("NATO má **32 členů** – posledními se staly Finsko (2023) a Švédsko (2024).", "politika", 95)
        if (rx("^kolik (ma |je )?(clenu|statu|zemi) (ma )?(eu|evropske unie|v eu)|^kolik clenu ma eu").containsMatchIn(f))
            return ans("Evropská unie má **27 členských států** (Spojené království vystoupilo 31. 1. 2020).", "politika", 95)
        if (rx("clanek 5|clanku 5").containsMatchIn(f) && "nato" in f)
            return ans("**Článek 5** Washingtonské smlouvy: ozbrojený útok na jednoho člena NATO je útokem na všechny. Dosud uplatněn jedinkrát – po útocích 11. 9. 2001.", "politika", 97)
        if (rx("kdy (vzniklo|byla zalozena|byl zalozen|vznikla) nato").containsMatchIn(f))
            return ans("NATO vzniklo **4. 4. 1949** podpisem Washingtonské smlouvy (12 zakládajících států).", "politika", 97)
        if (rx("kdy (vznikla|byla zalozena) (eu|evropska unie)").containsMatchIn(f))
            return ans("Evropská unie vznikla **1. 11. 1993** Maastrichtskou smlouvou; navázala na Evropská společenství (Římské smlouvy 1957).", "politika", 97)
        if (rx("stal\\w* clen\\w* rady bezpecnosti|kdo ma (pravo )?veto v osn").containsMatchIn(f))
            return ans("Stálí členové Rady bezpečnosti OSN s právem veta: **USA, Rusko, Čína, Spojené království a Francie**.", "politika", 97)
        if (rx("kdy (zacala|vypukla) (valka na ukrajine|ruska invaze|invaze na ukrajinu)").containsMatchIn(f))
            return ans("Plnou invazi na Ukrajinu zahájilo Rusko **24. 2. 2022**; konflikt začal už v roce 2014 anexí Krymu a válkou na Donbasu.", "politika", 97)
        if (rx("kdy (byl|zautocil|zacal) (utok )?hamas|kdy byl utok na izrael").containsMatchIn(f))
            return ans("Hamás zaútočil na Izrael **7. 10. 2023** (asi 1 200 obětí, přes 250 rukojmí).", "politika", 97)
        if (rx("kdy (byla|probehla) sametova revoluce|kdy byl 17\\. listopad").containsMatchIn(f))
            return ans("Sametová revoluce začala **17. listopadu 1989** studentskou demonstrací v Praze; komunistický režim padl do konce roku 1989.", "politika", 97)
        if (rx("kdy (vznikla|vzniklo) (ceska republika|cesko|samostatn\\w* cesk\\w*)").containsMatchIn(f))
            return ans("Samostatná Česká republika vznikla **1. 1. 1993** rozdělením Československa.", "politika", 97)
        return null
    }
}
