package cz.axiom.omega.core.agent

import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.util.TextUtil

/**
 * E-ARL – Early Answer Routing Layer („Efektivní Adaptivní Rozhodování: Lze bez mozku?“).
 *
 * Sedí PŘED RANDYm a u každé otázky během zlomku milisekundy rozhodne, kolik „myšlení“ je opravdu potřeba:
 *
 *   REFLEX    – odpověď už zná deterministický reflex (čas, výpočet, fakta…)          → 0 ms mozku
 *   RETRIEVE  – stačí výňatek z offline knihovny (definice / „kdo byl X“ s přesnou shodou hesla),
 *               a načítat 3–5 GB mozek by bylo plýtvání                                   → 0 ms mozku
 *   LIGHT     – jednoduchá otázka pro mozek: 1 průchod, bez přemýšlení, krátce, rychlý model
 *   BRAIN     – běžná práce mozku (RANDY volí hloubku)
 *   DEEP      – analýza, strategie, kód, složené otázky → RANDY smí naplno (řetězce, agent)
 *
 * RANDY tak nemusí plánovat triviality a mozek se nespouští zbytečně. E-ARL se UČÍ: když uživatel
 * označí odpověď z cesty RETRIEVE/LIGHT jako špatnou (✗), zpřísní práh pro danou kategorii otázek;
 * dobré odpovědi ho pomalu uvolňují. Signály bere z [Pavucina] (nic nepočítá dvakrát).
 */
object Earl {

    enum class Path(val label: String) { REFLEX("reflex"), RETRIEVE("výňatek z knihovny"), LIGHT("lehký mozek"), BRAIN("mozek"), DEEP("hluboké myšlení") }

    data class Decision(val path: Path, val confidence: Int, val category: String, val reasons: List<String>, val entity: String? = null)

    data class Ctx(
        /** reflex už odpověděl (nebo by odpověděl) */
        val reflexHit: Boolean = false,
        /** hledání v offline znalostech (sdílené přes Pavučinu); null = knihovna není */
        val knowledge: ((String) -> List<Snippet>)? = null,
        /** mozek už je načtený v RAM (pak je i LIGHT levný) */
        val brainLoaded: Boolean = false,
        /** je dostupný cloud (rychlý a silný) */
        val cloud: Boolean = false,
        /** naučené prahy RETRIEVE pro kategorie (výchozí 85) */
        val thresholds: Map<String, Int> = emptyMap(),
    )

    private val define = Regex("^(?:co (?:je|jsou|byl|byla|bylo)(?: to)?|kdo (?:je|byl|byla|jsou|byli)|co znamena|definuj|vysvetli pojem|co je pojem) (.{2,60})$")
    private val create = Regex("^(napis|vytvor|navrhni|sestav|vymysli|naprogramuj|udelej|priprav|zformuluj|preloz|prepis|uprav|oprav|shrn)\\b")
    private val analyze = Regex("\\b(proc|porovnej|srovnej|zhodnot|analyzuj|vysvetli|rozeber|jaky je rozdil|vyplati se|mam|doporuc|poradit?|co si myslis|strategi|plan|rizik)\\b")
    private val fact = Regex("^(kdy|kde|kolik|ktery|ktera|ktere|jak velk|jak daleko|jak dlouh|v jakem roce|kdo)\\b")
    private val chat = Regex("^(ahoj|cau|dik|diky|dekuji|ok|super|jasne|dobre|jak se mas|dobrou)\\b")
    private val live = Regex("\\b(aktualni|dnesni|nejnovejsi|zpravy|pocasi|cena|kurz|vysledek|zapas|letos|ted)\\b")

    /** Kategorie otázky – podle ní se učí prahy. */
    fun category(s: Pavucina.Signal): String {
        val c = s.canonical
        // tvorbu a analýzu poznat z PŮVODNÍHO znění – normalizátor „napiš mi“ odstraní jako zdvořilostní obal
        val raw = s.folded.trimEnd('?', '.', '!').replace(Regex("^(prosim( te)?|ahoj|hele|avior)[ ,]+"), "")
        return when {
            s.hasCode -> "kód"
            create.containsMatchIn(raw) || create.containsMatchIn(c) -> "tvorba"
            define.matches(c) && s.wordCount <= 8 -> "definice"
            chat.containsMatchIn(c) && s.wordCount <= 6 -> "konverzace"
            analyze.containsMatchIn(raw) || analyze.containsMatchIn(c) -> "analýza"
            fact.containsMatchIn(c) && s.wordCount <= 10 -> "fakt"
            else -> "obecné"
        }
    }

    fun decide(s: Pavucina.Signal, c: Ctx): Decision {
        val cat = category(s)
        val why = mutableListOf<String>()
        if (c.reflexHit) return Decision(Path.REFLEX, 95, cat, listOf("odpověď zná reflex"))
        // těžké věci rovnou do hloubky
        val deep = s.compound || s.hasCode && s.wordCount > 12 || s.level.ordinal >= RandyLevel.L3_STRATEGIC_EV.ordinal ||
            (cat == "analýza" && s.wordCount > 14) || (cat == "tvorba" && s.wordCount > 18)
        if (deep) {
            if (s.compound) why += "složená otázka"
            if (s.level.ordinal >= RandyLevel.L3_STRATEGIC_EV.ordinal) why += "RANDY ${s.level.code}"
            if (why.isEmpty()) why += "náročná $cat"
            return Decision(Path.DEEP, 80, cat, why)
        }
        // definice: přesná shoda hesla v offline knihovně → stačí výňatek (když mozek není po ruce levně)
        if (cat == "definice" && !s.timeSensitive && c.knowledge != null) {
            val entity = define.find(s.canonical)?.groupValues?.get(1)?.trim()?.trimEnd('?', '.') ?: ""
            val hits = runCatching { c.knowledge.invoke(entity) }.getOrDefault(emptyList())
            val e = TextUtil.fold(entity).lowercase()
            val exact = hits.firstOrNull { val t = TextUtil.fold(it.title).lowercase(); t == e || t.startsWith("$e (") || t.startsWith("$e,") }
            if (exact != null && exact.text.length >= 120) {
                var conf = 88
                if (c.brainLoaded) conf -= 6          // mozek je v RAM → lehká odpověď mozkem je levná a bohatší
                if (c.cloud) conf -= 8                // cloud odpoví rychle a lépe
                val th = c.thresholds["definice"] ?: 85
                if (conf >= th) return Decision(Path.RETRIEVE, conf, cat, listOf("přesné heslo „${exact.title}“ v offline knihovně", "mozek netřeba"), entity)
                why += "heslo v knihovně, ale mozek/cloud je po ruce"
            }
        }
        // krátké, jasné otázky → lehký mozek (1 průchod, bez přemýšlení)
        val light = (cat in setOf("fakt", "definice", "konverzace") && s.wordCount <= 12 && s.level.ordinal <= RandyLevel.L1_EXPERT.ordinal) ||
            (cat == "obecné" && s.wordCount <= 6 && s.level.ordinal == 0)
        // naučeno: lehká cesta v této kategorii dostávala ✗ → raději plný mozek
        val lightOk = (c.thresholds["light:$cat"] ?: 85) <= 92
        if (light && lightOk && !(live.containsMatchIn(s.canonical) && c.cloud)) {
            why += "krátká jasná otázka ($cat)"
            return Decision(Path.LIGHT, 80, cat, why)
        }
        why += if (cat == "obecné") "běžná otázka" else cat
        return Decision(Path.BRAIN, 70, cat, why)
    }

    /** Učení z hodnocení: špatná odpověď z levné cesty zpřísní práh kategorie, dobrá ho pomalu uvolní. */
    fun learn(thresholds: MutableMap<String, Int>, category: String, path: Path, good: Boolean) {
        val key = when (path) { Path.RETRIEVE -> category; Path.LIGHT -> "light:$category"; else -> return }
        val cur = thresholds[key] ?: 85
        thresholds[key] = if (good) maxOf(78, cur - 1) else minOf(99, cur + 5)
    }
}
