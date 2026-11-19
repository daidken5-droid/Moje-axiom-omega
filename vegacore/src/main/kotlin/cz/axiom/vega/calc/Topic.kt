package cz.axiom.vega.calc

import cz.axiom.vega.base.Text

/**
 * HOLÝ NÁZEV TÉMATU („operation Prato“, „Projekt Manhattan“, „kauza Dozimetr“) = chce vědět, co to je → rešerše.
 * Konzervativně: 1–6 slov, bez otázkového slova, slovesa pokynu, pozdravu, čísel a povelů.
 */
object Topic {
    private val notTopic = Regex("\\b(co|kdo|kdy|kde|jak|proc|kolik|ktery|ktera|ktere|jaky|jaka|jake|je|jsou|byl|byla|bylo|ma|mam|mas|muze|muzu|lze|bude|chci|potrebuju|" +
        "napis|vytvor|udelej|navrhni|spocitej|vypocitej|preved|preloz|shrn|vysvetli|porovnej|zapni|vypni|nastav|otevri|posli|pust|najdi|hledej|" +
        "ahoj|cau|dik|diky|dekuji|dekuju|ok|okej|super|jasne|dobre|dobry|dobra|ano|ne|test|pomoc|help|hi|hello|thanks|nevim|aha|hmm|jo)\\b")
    private val bad = Regex("[0-9+*/=<>@#{}\\[\\]|\\\\]")

    fun bare(prompt: String): String? {
        val t = prompt.trim().trimEnd('?', '!', '.', '…', ' ').trim('"', '„', '“', '\'', ' ')
        if (t.length !in 3..60 || t.startsWith("/") || bad.containsMatchIn(t) || '\n' in t) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 1..6 || (words.size == 1 && words[0].length < 3)) return null
        if (notTopic.containsMatchIn(Text.fold(t).lowercase())) return null
        return t
    }
}
