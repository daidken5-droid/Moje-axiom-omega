package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * HOLÝ NÁZEV TÉMATU („Operation Prato“, „Fotosyntéza“, „Karel Gott“, „černá díra“) = otázka „co je / kdo je X“.
 * Dřív takový vstup spadl do „obecné“ → lehký mozek bez hledání → u neznámého tématu chyba nebo vymýšlení.
 * Konzervativně: 1–6 slov, žádné otázkové slovo, sloveso pokynu, pozdrav, výpočet ani povel.
 */
object Topic {
    private val notTopic = Regex(
        "\\b(co|kdo|kdy|kde|jak|proc|kolik|ktery|ktera|ktere|jaky|jaka|jake|je|jsou|byl|byla|bylo|ma|mam|mas|muze|muzu|lze|bude|chci|potrebuju|" +
            "napis|vytvor|udelej|navrhni|spocitej|vypocitej|preved|preloz|shrn|vysvetli|porovnej|zapni|vypni|nastav|otevri|zavolej|posli|pust|najdi|hledej|" +
            "ahoj|cau|dik|diky|dekuji|dekuju|ok|okej|super|jasne|dobre|dobry|dobra|ano|ne|test|pomoc|help|hi|hello|thanks|nevim|aha|hmm|jo)\\b",
    )
    private val bad = Regex("[0-9+*/=<>@#{}\\[\\]|\\\\]")

    /** Téma, nebo null. Vrací znění bez koncové interpunkce (původní velikost písmen). */
    fun bare(prompt: String): String? {
        val t = prompt.trim().trimEnd('?', '!', '.', '…', ' ').trim('"', '„', '“', '\'', ' ')
        if (t.length !in 3..60 || t.startsWith("/") || bad.containsMatchIn(t) || '\n' in t) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 1..6) return null
        val f = TextUtil.fold(t).lowercase()
        if (notTopic.containsMatchIn(f)) return null
        // jedno krátké obecné slovo bez velkého písmene („auto“, „vlak“) – téma ano, ale aspoň 3 písmena
        if (words.size == 1 && words[0].length < 3) return null
        return t
    }
}
