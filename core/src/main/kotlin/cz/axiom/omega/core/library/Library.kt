package cz.axiom.omega.core.library

import cz.axiom.omega.core.util.TextUtil

/**
 * Offline knihovna: velké encyklopedické databáze stažené z Hugging Face (datasets-server)
 * do samostatné SQLite FTS databáze v telefonu. Funguje bez internetu, prohledává se fulltextem.
 */
data class LibrarySpec(
    val id: String,
    val name: String,
    val description: String,
    val dataset: String,
    val config: String,
    val split: String,
    val rows: Int,
    /** Odhad místa na disku včetně fulltextového indexu. */
    val approxGb: Double,
    val lang: String,
    val license: String,
)

object Libraries {
    val ALL = listOf(
        LibrarySpec(
            "wiki-cs", "Česká Wikipedie – celá", "534 tisíc článků: věda, technika, historie, zeměpis, osobnosti, právo, medicína…",
            "wikimedia/wikipedia", "20231101.cs", "train", 534_044, 5.0, "cs", "CC BY-SA 4.0",
        ),
        LibrarySpec(
            "wiki-sk", "Slovenská Wikipedie – celá", "242 tisíc článků ve slovenštině.",
            "wikimedia/wikipedia", "20231101.sk", "train", 242_235, 1.8, "sk", "CC BY-SA 4.0",
        ),
        LibrarySpec(
            "wiki-simple", "Simple English Wikipedia", "242 tisíc článků v jednoduché angličtině – srozumitelné výklady pojmů.",
            "wikimedia/wikipedia", "20231101.simple", "train", 241_787, 1.2, "en", "CC BY-SA 4.0",
        ),
    )

    fun of(id: String) = ALL.firstOrNull { it.id == id }

    /**
     * FTS dotaz s jednoduchým „stemmingem“ pro češtinu: slova delší než 5 znaků se hledají
     * podle prvních 5 písmen (prefix index), takže „čerpadlo/čerpadla/čerpadlem“ najdou totéž.
     */
    fun ftsQuery(query: String, and: Boolean): String? {
        val words = TextUtil.words(query, 3).filter { it !in STOP }.distinct().take(8)
        if (words.isEmpty()) return null
        val terms = words.map { w -> if (w.length > 5) "\"${w.take(5)}\"*" else "\"$w\"" }
        return terms.joinToString(if (and) " " else " OR ")
    }

    /** Skóre výsledku: shoda v názvu váží víc než v textu. */
    fun score(query: String, title: String, text: String): Int {
        val q = TextUtil.words(query, 3).filter { it !in STOP }.map { it.take(5) }.distinct()
        val t = TextUtil.fold(title)
        val b = TextUtil.fold(text)
        return q.sumOf { w -> (if (t.contains(w)) 4 else 0) + (if (b.contains(w)) 1 else 0) } + if (TextUtil.fold(query).contains(t) && t.length > 3) 6 else 0
    }

    private val STOP = setOf(
        "jak", "jaky", "jaka", "jake", "kde", "kdy", "kdo", "co", "proc", "neni", "jsou", "byl", "byla", "bylo", "pro", "nebo", "ale", "the",
        "and", "for", "with", "what", "how", "who", "kolik", "muze", "mohu", "jsem", "jsi", "tak", "take", "jeho", "jeji", "ktery", "ktera", "ktere",
    )
}
