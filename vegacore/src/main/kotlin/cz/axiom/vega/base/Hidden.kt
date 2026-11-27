package cz.axiom.vega.base

/**
 * Skryté znaky v CIZÍM textu (NFC štítek, virtuální karta z jiného telefonu): řídicí znaky, zalomení řádku,
 * směrové značky (U+202E obrátí text), nulové šířky, měkký spojovník, prázdné hangul výplně.
 * Jedna sada pro celou aplikaci (refaktoring 27. 11.: dřív dvě skoro stejné kopie v Ndef.kt a Ops.kt).
 */
object Hidden {
    private const val SET = "\\u0000-\\u001F\\u007F-\\u009F\\u00AD\\u061C\\u115F\\u1160\\u180E\\u2028\\u2029\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\u3164\\uFEFF\\uFFF9-\\uFFFB"
    private val ONE = Regex("[$SET]")
    private val RUN = Regex("[$SET]+")
    private val SPACES = Regex(" {2,}")

    fun has(s: String) = ONE.containsMatchIn(s)

    /** Každý skrytý znak viditelně jako „⟦U+202E⟧“ – laik vidí, že text není, jak vypadá. */
    fun reveal(s: String) = ONE.replace(s) { "⟦U+%04X⟧".format(it.value[0].code) }

    /** Jeden řádek bez skrytých znaků (nejde podvrhnout další řádek ani přeházet text), nejvýš [max] znaků. */
    fun oneLine(s: String, max: Int = 300) = RUN.replace(s, " ").replace(SPACES, " ").trim().take(max)

    /** Buňka tabulky v markdownu z cizího názvu (Bluetooth zařízení, WiFi síť): jeden řádek, „|“ nerozdělí sloupce. */
    fun cell(s: String, max: Int = 64) = oneLine(s, max).replace("|", "¦").replace("`", "'")
}
