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
    /** Odhad velikosti předpřipraveného balíku ke stažení (gzip). */
    val downloadGb: Double = 0.0,
    /**
     * Jak se zdroj jmenuje v odpovědích („Wikipedie: Brno“, „Wikizdroje: Babička“). Jen česká „Wikipedie“ je
     * referenční – jí věří odpovědi BEZ MOZKU (WikiReflex) a Strážce faktů (Δ). Jiné jazyky a Wikizdroje slouží
     * jen jako podklad pro mozek (reflex by jinak odpověděl anglicky nebo úryvkem básně).
     */
    val source: String = "Wikipedie",
)

object Libraries {
    val ALL = listOf(
        LibrarySpec(
            "wiki-cs", "Česká Wikipedie – celá", "534 tisíc článků: věda, technika, historie, zeměpis, osobnosti, právo, medicína…",
            "wikimedia/wikipedia", "20231101.cs", "train", 534_044, 3.3, "cs", "CC BY-SA 4.0", 1.5,
        ),
        LibrarySpec(
            "wiki-sk", "Slovenská Wikipedie – celá", "242 tisíc článků ve slovenštině.",
            "wikimedia/wikipedia", "20231101.sk", "train", 242_235, 1.3, "sk", "CC BY-SA 4.0", 0.5, source = "Wikipedia SK",
        ),
        LibrarySpec(
            "wiki-simple", "Simple English Wikipedia", "242 tisíc článků v jednoduché angličtině – srozumitelné výklady pojmů.",
            "wikimedia/wikipedia", "20231101.simple", "train", 241_787, 0.9, "en", "CC BY-SA 4.0", 0.35, source = "Wikipedia EN",
        ),
        LibrarySpec(
            "wikisource-cs", "Wikizdroje – české texty", "43 tisíc volných textů: zákony a ústava, historické listiny, básně a próza klasiků (Erben, Němcová, Neruda, Mácha…).",
            "wikimedia/wikisource", "20231201.cs", "train", 42_735, 0.4, "cs", "CC BY-SA 4.0", 0.19, source = "Wikizdroje",
        ),
    )

    fun of(id: String) = ALL.firstOrNull { it.id == id }

    /** Předpona názvu výsledku podle knihovny (ruční importy témat jsou česká Wikipedie). */
    fun label(lib: String?) = of(lib ?: "")?.source ?: "Wikipedie"

    /**
     * FTS dotaz s jednoduchým „stemmingem“ pro češtinu: slova delší než 5 znaků se hledají
     * podle prvních 5 písmen (prefix index), takže „čerpadlo/čerpadla/čerpadlem“ najdou totéž.
     */
    fun ftsQuery(query: String, and: Boolean, titleOnly: Boolean = false): String? {
        val words = TextUtil.words(query, 3).filter { it !in STOP }.distinct().take(8)
        if (words.isEmpty()) return null
        // Pozor: FTS4 předponu v uvozovkách ("abc"*) NEpodporuje – jen holé abc*.
        // Slova z TextUtil.words obsahují jen písmena a číslice (malá), takže nejde o FTS syntaxi.
        val terms = words.map { w -> (if (titleOnly) "title:" else "") + if (w.length > 5) "${w.take(5)}*" else w }
        return terms.joinToString(if (and) " " else " OR ")
    }

    /**
     * Pořadí dotazů pro velkou knihovnu: 1) všechna slova v NÁZVU článku (najde přímo „Tepelné čerpadlo“
     * mezi 534 tisíci články), 2) všechna slova kdekoli, 3) kterékoli slovo.
     */
    fun ftsPlan(query: String): List<String> =
        listOfNotNull(ftsQuery(query, and = true, titleOnly = true), ftsQuery(query, and = true), ftsQuery(query, and = false)).distinct()

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
        "funguje", "funguji", "znamena", "vysvetli", "popis", "popiste", "rekni", "prosim", "muzes", "chci", "potrebuji", "najdi", "udelej",
        "napis", "jaky", "jakym", "zda", "jestli", "nejaky", "neco", "vsechno", "about", "does", "work", "explain", "tell",
    )
}
