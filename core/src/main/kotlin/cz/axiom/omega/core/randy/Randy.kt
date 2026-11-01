package cz.axiom.omega.core.randy

import cz.axiom.omega.core.util.TextUtil

/**
 * RANDY — hloubkový protokol AVIORu (DNA z Titanium 5.5), v Omeze povýšený
 * z pouhého štítku na řídicí systém: úroveň určuje instrukce, počet průchodů
 * agenta, úsilí cloudového modelu i délku odpovědi.
 */
enum class RandyLevel(
    val code: String,
    val title: String,
    val label: String,
    /** Průchody: každý průchod je samostatné volání modelu (návrh → audit → syntéza…). */
    val passes: List<RandyPass>,
    /** Úsilí pro modely s nastavitelným přemýšlením (Claude effort / Kimi reasoning_effort). */
    val effort: String,
) {
    L0_DIRECT(
        "L0", "DIRECT", "Okamžitý faktický výstup bez omáčky",
        listOf(RandyPass.ANSWER), "low",
    ),
    L1_EXPERT(
        "L1", "EXPERT", "Technické parametry, normy a přesné hodnoty",
        listOf(RandyPass.ANSWER), "medium",
    ),
    L2_DEEP(
        "L2", "DEEP", "Hloubková syntéza z prvních principů",
        listOf(RandyPass.DRAFT, RandyPass.REFINE), "high",
    ),
    L3_STRATEGIC_EV(
        "L3", "STRATEGIC +EV", "Kvantitativní výpočet marže, ROI a +EV",
        listOf(RandyPass.DRAFT, RandyPass.QUANT_CHECK), "high",
    ),
    L4_NEMESIS_REDTEAM(
        "L4", "NEMESIS RED-TEAM", "Útočný audit, hledání slabin a rizik",
        listOf(RandyPass.DRAFT, RandyPass.RED_TEAM, RandyPass.SYNTHESIS), "high",
    ),
    L5_FORENSIC(
        "L5", "FORENSIC", "Forenzní rekonstrukce stop a integrity dat",
        listOf(RandyPass.EVIDENCE, RandyPass.RED_TEAM, RandyPass.SYNTHESIS), "high",
    ),
    L6_OMNISCIENT(
        "L6", "OMNISCIENT", "Multioborová syntéza s extrapolací dopadů",
        listOf(RandyPass.DRAFT, RandyPass.RED_TEAM, RandyPass.SYNTHESIS), "max",
    );

    val badge get() = "$code · $title"

    companion object {
        fun fromCode(code: String?): RandyLevel? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) || it.name.equals(code?.trim(), ignoreCase = true) }
    }
}

/** Jeden průchod agenta – každý má vlastní instrukci. */
enum class RandyPass(val label: String, val instruction: String) {
    ANSWER(
        "Odpověď",
        "Odpověz přímo a úplně. Začni výsledkem, pak jen nezbytné zdůvodnění.",
    ),
    DRAFT(
        "Návrh",
        "Vytvoř první úplný návrh odpovědi. Vyznač předpoklady a nejistoty.",
    ),
    REFINE(
        "Zpřesnění",
        "Zkontroluj předchozí návrh: oprav chyby, doplň chybějící kroky z prvních principů, zestručni. Vrať finální odpověď.",
    ),
    QUANT_CHECK(
        "Kvantitativní kontrola",
        "Přepočítej všechna čísla v návrhu proti faktům STEP-0. Nesoulad oprav. Vrať finální odpověď s čísly, ROI/EV a doporučením.",
    ),
    EVIDENCE(
        "Stopy",
        "Vypiš všechny stopy a artefakty (data, časy, částky, identifikátory) jako časovou osu. Odliš doložené od odvozeného.",
    ),
    RED_TEAM(
        "Red-team",
        "Zaútoč na předchozí výstup: najdi nejslabší předpoklady, chyby, rizika a alternativní vysvětlení. Buď nemilosrdný, ale věcný. Nevymýšlej si ověření.",
    ),
    SYNTHESIS(
        "Syntéza",
        "Spoj návrh a red-team kritiku do opravené finální odpovědi. Zachovej nejistoty, uveď míru jistoty a co by závěr změnilo.",
    ),
}

object Randy {

    /** Klíčová slova (bez diakritiky, prefixy slov) → úroveň a váha. */
    private val signals: Map<RandyLevel, List<Pair<String, Int>>> = mapOf(
        RandyLevel.L5_FORENSIC to listOf(
            "forenz" to 3, "stop" to 1, "hash" to 2, "integrit" to 2, "rekonstru" to 2, "metadat" to 2,
            "dukaz" to 2, "podvod" to 2, "casova osa" to 3, "log " to 1, "falsifik" to 3,
        ),
        RandyLevel.L4_NEMESIS_REDTEAM to listOf(
            "red team" to 3, "redteam" to 3, "utok" to 2, "exploit" to 3, "slabin" to 2, "zranitel" to 3,
            "rizik" to 1, "hrozb" to 2, "nemesis" to 3, "kritizuj" to 2, "napadni" to 2, "audit" to 2, "devil" to 2,
        ),
        RandyLevel.L3_STRATEGIC_EV to listOf(
            "+ev" to 3, "kelly" to 3, "marz" to 2, "roi " to 3, "kurz" to 2, "bankroll" to 3, "sazk" to 2,
            "zisku" to 1, "ziskovost" to 2, "investic" to 2, "navratnost" to 2, "cashflow" to 2, "cena" to 1, "nakup" to 1, "vynos" to 2,
        ),
        RandyLevel.L6_OMNISCIENT to listOf(
            "multiobor" to 3, "dopad" to 1, "extrapol" to 3, "budoucnost" to 2, "scenar" to 2, "civilizac" to 3,
            "systemov" to 2, "omniscient" to 3, "celkov" to 1, "strategie" to 1, "geopolit" to 3,
        ),
        RandyLevel.L2_DEEP to listOf(
            "proc " to 2, "vysvetli" to 2, "syntez" to 2, "princip" to 2, "derive" to 2, "odvod" to 2,
            "jak funguje" to 3, "do hloubky" to 3, "pochop" to 1, "teorie" to 2,
        ),
        RandyLevel.L1_EXPERT to listOf(
            "parametr" to 2, "norma" to 2, "csn " to 3, "iso " to 2, "spec" to 2, "tabulk" to 1, "hodnot" to 1,
            "rozmer" to 2, "tolerance" to 2, "datasheet" to 3, "technick" to 2,
        ),
    )

    private val explicitRx = Regex("""^\s*/?(?:randy\s*)?l([0-6])\b[:\s]*""", RegexOption.IGNORE_CASE)

    data class Detection(val level: RandyLevel, val explicit: Boolean, val cleanedPrompt: String, val scores: Map<RandyLevel, Int>)

    /**
     * Určí úroveň. Uživatel ji může vynutit prefixem "L4:" nebo "/l4 ...".
     * Titanium porovnával holé podřetězce ("roi" se chytalo i uvnitř jiných slov,
     * "riziko" vždy přebilo vše) – Omega skóruje celá slova/prefixy a vybere maximum.
     */
    fun detect(prompt: String): Detection {
        explicitRx.find(prompt)?.let { m ->
            val lvl = RandyLevel.entries[m.groupValues[1].toInt()]
            return Detection(lvl, true, prompt.substring(m.range.last + 1).trim(), emptyMap())
        }
        val folded = " " + TextUtil.fold(prompt).replace(Regex("[^\\p{L}\\p{N}+ ]+"), " ").replace(Regex("\\s+"), " ") + " "
        val scores = LinkedHashMap<RandyLevel, Int>()
        for ((level, list) in signals) {
            var s = 0
            for ((kw, w) in list) {
                // shoda na začátku slova (prefix), víceslovné fráze přímo
                if (folded.contains(" $kw")) s += w
            }
            if (s > 0) scores[level] = s
        }
        // EV: kurz + procenta je silný signál
        if (Regex("""kurz\w*\s*:?\s*\d""").containsMatchIn(folded) && prompt.contains('%')) {
            scores[RandyLevel.L3_STRATEGIC_EV] = (scores[RandyLevel.L3_STRATEGIC_EV] ?: 0) + 3
        }
        val best = scores.entries.maxWithOrNull(compareBy<Map.Entry<RandyLevel, Int>> { it.value }.thenBy { it.key.ordinal })
        val level = when {
            best == null || best.value < 2 -> if (prompt.length > 600) RandyLevel.L2_DEEP else RandyLevel.L0_DIRECT
            else -> best.key
        }
        return Detection(level, false, prompt.trim(), scores)
    }

    /** RANDY-direct filtr: odstraní zdvořilostní vatu z výstupu (ne ze vstupu uživatele). */
    private val fillerRx = Regex(
        """(?im)^\s*(?:jistě|samozřejmě|rád(?:a)? pomohu|skvělá otázka|dobrá otázka|určitě)[!,.]?\s*(?:zde je[^\n]*?:)?\s*""",
    )

    fun direct(output: String): String = fillerRx.replace(output, "").trim()

    /** Instrukce úrovně pro systémový prompt. */
    fun protocol(level: RandyLevel): String = buildString {
        append("RANDY ${level.badge} — ${level.label}.\n")
        append(
            when (level) {
                RandyLevel.L0_DIRECT -> "Výsledek hned v první větě. Žádný úvod, žádné opakování otázky, žádná vata. Max. pár vět, pokud nejde o výpočet nebo kód."
                RandyLevel.L1_EXPERT -> "Uveď konkrétní parametry, jednotky, normy a rozsahy. Kde hodnota závisí na výrobci nebo verzi normy, řekni to. Formát: přehledné body nebo tabulka."
                RandyLevel.L2_DEEP -> "Odvoď odpověď z prvních principů: mechanismus → důsledky → praktický závěr. Vysvětluj tak, aby šlo každý krok ověřit."
                RandyLevel.L3_STRATEGIC_EV -> "Kvantifikuj: náklady, výnos, marže, ROI, pravděpodobnosti, EV, Half-Kelly. Čísla ber z faktů STEP-0; nepočítej je znovu odhadem. Zakonči jasným doporučením (ano/ne/za jakých podmínek)."
                RandyLevel.L4_NEMESIS_REDTEAM -> "Mysli jako útočník a nejtvrdší oponent. Hledej slabiny, selhání, zneužití a skrytá rizika. Každé riziko: pravděpodobnost × dopad → protiopatření. Pouze obranný a etický rámec."
                RandyLevel.L5_FORENSIC -> "Pracuj jako forenzní analytik: stopy → časová osa → hypotézy → test hypotéz → závěr s mírou jistoty. Odliš doložené od odvozeného. Nic si nedomýšlej."
                RandyLevel.L6_OMNISCIENT -> "Propoj všechny relevantní obory (technika, ekonomie, právo, psychologie, historie). Extrapoluj dopady v čase (krátce/středně/dlouhodobě) a uveď scénáře s pravděpodobnostmi."
            },
        )
    }

    /** Doporučené tokeny výstupu pro lokální mozek (malé modely se rády zacyklí). */
    fun localMaxTokens(level: RandyLevel): Int = when (level) {
        RandyLevel.L0_DIRECT -> 384
        RandyLevel.L1_EXPERT -> 640
        else -> 896
    }
}
