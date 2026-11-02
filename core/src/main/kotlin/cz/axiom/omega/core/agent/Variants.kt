package cz.axiom.omega.core.agent

import cz.axiom.omega.core.randy.RandyLevel

/**
 * Ω VARIANTY – „podmodely“ AVIORu. Stejný mozek, jiné nastavení myšlení: inspirované tím, jak přední AI
 * nabízejí rychlé, přemýšlivé, rešeršní, programátorské a kreativní režimy. Každá varianta určuje
 * režim (odpověď/agent), roli agenta, hloubku RANDY, počet průchodů, přemýšlení, teplotu a styl.
 * Výslovná volba uživatele (L4:, /agent, ruční role) má vždy přednost.
 */
enum class Variant(
    val title: String,
    val icon: String,
    val tagline: String,
    /** Jaký typ špičkových modelů varianta napodobuje (popis přístupu, ne konkrétní produkt). */
    val inspiredBy: String,
    val mode: Router.Mode? = null,
    val role: AgentRole? = null,
    val level: RandyLevel? = null,
    val passes: Int? = null,
    val thinking: Boolean? = null,
    val temperature: Float? = null,
    val steps: Int? = null,
    val style: String? = null,
) {
    AUTO("Ω Auto", "Ω", "Sám zvolí hloubku, nástroje i agenta", "adaptivní směrování"),
    FLASH(
        "Ω Flash", "ϟ", "Jednoduché otázky okamžitě bez mozku, jinak stručně", "rychlé „flash/mini“ modely",
        mode = Router.Mode.CHAT, passes = 1, thinking = false, temperature = 0.5f,
        style = "Odpovídej okamžitě a stručně: jádro věci v 1–4 větách, bez úvodu. Podrobnosti jen na vyžádání.",
    ),
    THINK(
        "Ω Think", "◎", "Hluboké přemýšlení, ověřené kroky", "„reasoning“ modely s dlouhou úvahou",
        mode = Router.Mode.CHAT, passes = 3, thinking = true, temperature = 0.5f,
        style = "Před odpovědí důkladně promysli problém: rozlož ho, zvaž alternativy a protipříklady, ověř každý výpočet. " +
            "V odpovědi ukaž klíčové kroky úvahy a jasný závěr s mírou jistoty.",
    ),
    RESEARCH(
        "Ω Deep Research", "◈", "Víc zdrojů, report s citacemi", "režimy „deep research“",
        mode = Router.Mode.AGENT, role = AgentRole.RESEARCHER, steps = 20,
        style = "Projdi aspoň 4–6 zdrojů (offline knihovna, Wikipedie, web), porovnej je a napiš strukturovaný report: " +
            "shrnutí, hlavní zjištění s citacemi [1], [2]…, rozpory mezi zdroji, co zůstává nejisté, seznam zdrojů.",
    ),
    CODE(
        "Ω Code", "</>", "Programuje a ukládá soubory", "programátorští agenti",
        mode = Router.Mode.AGENT, role = AgentRole.CODER, temperature = 0.2f, steps = 15,
    ),
    STUDIO(
        "Ω Studio", "▣", "Vyrobí instalovatelnou aplikaci (APK)", "generátory aplikací z popisu",
        mode = Router.Mode.AGENT, role = AgentRole.APPS, temperature = 0.3f, steps = 8,
    ),
    OFFICE(
        "Ω Office", "▧", "Dokumenty Word, PDF, tabulky", "kancelářští asistenti",
        mode = Router.Mode.AGENT, role = AgentRole.OFFICE, temperature = 0.3f, steps = 8,
    ),
    VERITAS(
        "Ω Veritas", "✓", "Ověří tvrzení a teorie", "fact-checking s citacemi",
        mode = Router.Mode.AGENT, role = AgentRole.FACTCHECK, temperature = 0.2f, steps = 12,
    ),
    QUANT(
        "Ω Quant", "Σ", "Čísla, +EV, finance", "analytické modely",
        mode = Router.Mode.CHAT, level = RandyLevel.L3_STRATEGIC_EV, passes = 2, temperature = 0.2f,
        style = "Vše kvantifikuj: tabulka vstupů, výpočty (čísla ze STEP-0 ber jako správná), citlivost, doporučení s čísly.",
    ),
    NEMESIS(
        "Ω Nemesis", "⚔", "Red-team: najde slabiny", "kritické „red-team“ režimy",
        mode = Router.Mode.CHAT, level = RandyLevel.L4_NEMESIS_REDTEAM, passes = 3,
    ),
    MUSE(
        "Ω Muse", "✦", "Kreativní psaní a nápady", "kreativní modely",
        mode = Router.Mode.CHAT, passes = 1, temperature = 0.95f,
        style = "Buď kreativní a originální: živý jazyk, nečekané nápady, několik variant. Fakta nevymýšlej – fikci jasně odliš.",
    ),
    MENTOR(
        "Ω Mentor", "?", "Naučí tě to krok za krokem", "výukové režimy",
        mode = Router.Mode.CHAT, passes = 2, temperature = 0.4f,
        style = "Vysvětluj jako trpělivý učitel: od intuice k detailům, příklad, analogie, na konci 2 kontrolní otázky.",
    ),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.name.equals(id, true) } ?: AUTO
    }
}
