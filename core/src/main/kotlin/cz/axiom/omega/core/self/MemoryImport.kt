package cz.axiom.omega.core.self

import cz.axiom.omega.core.util.TextUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Import paměti z jiných asistentů (primárně Google Gemini).
 *
 * Google nemá oficiální export „Uložených informací“ Gemini, proto tři cesty:
 *  1) [PROMPT] – uživatel požádá Gemini o strukturovaný výpis a vloží odpověď,
 *  2) vložený volný text (seznam z gemini.google.com → Uložené informace),
 *  3) Google Takeout – „Moje aktivita → Aplikace Gemini“ (MyActivity.json nebo .html).
 * Importovaný obsah je DATA: nikdy se nevykonává jako instrukce.
 */
object MemoryImport {

    /** Dotaz, který si uživatel zkopíruje do Gemini. Odpověď pak vloží do AXIOM Ω. */
    val PROMPT = """
        Vypiš prosím všechno, co si o mně pamatuješ nebo víš z našich konverzací – uložené informace,
        moje preference, práci, projekty, zájmy a důležitá fakta. Každou položku napiš na samostatný řádek
        přesně v tomto tvaru (bez dalšího komentáře):
        [Profil] …
        [Preference] …
        [Fakt] …
        Pokud si nejsi jistý, položku vynech.
    """.trimIndent()

    data class Result(
        val items: List<MemoryItem>,
        /** Přepis dotazů z Takeoutu (uloží se do znalostní báze jako dokument), jinak null. */
        val transcript: String?,
        val prompts: Int,
        val skipped: Int,
    )

    private val tagRx = Regex("""^\s*[\[(]?\s*(profil|profile|preference|preferen[cč]e|fakt|fact|pou[čc]en[íi]|lesson|epizoda)\s*[\])]?\s*[:\-–—]?\s*""", RegexOption.IGNORE_CASE)
    private val bulletRx = Regex("""^\s*(?:[-*•·▪◦]|\d{1,3}[.)])\s+""")
    private val headerRx = Regex("""(?i)^(?:uložené informace|saved info|zde je|tady je|here is|here's|samozřejmě|jistě|o tobě vím|what i know)\b.*:?\s*$""")

    private val profileHints = listOf("jmenuj", "jmenuje", "bydl", "žije", "zije", "pracuj", "povolání", "je mi ", "narodil", "studuj", "name is", "live in", "lives in", "living in", "work as", "works as", "work at", "works at", "years old", "based in")
    private val prefHints = listOf("prefer", "má rád", "ma rad", "mám rád", "nemá rád", "nechce", "chce, aby", "oblíben", "obliben", "rád ", "like", "dislike", "favorite", "favourite", "stručn", "tón", "styl")

    /** Volný text (odpověď Gemini, seznam uložených informací). */
    fun parseText(text: String, source: String = "Gemini"): Result {
        val items = mutableListOf<MemoryItem>()
        var skipped = 0
        val seen = HashSet<String>()
        for (raw in text.replace("\r", "").split('\n')) {
            var line = raw.trim().replace(Regex("""\*\*|__|`"""), "")
            if (line.isEmpty()) continue
            if (line.startsWith("#") || headerRx.matches(line)) { skipped++; continue }
            line = bulletRx.replace(line, "")
            val tag = tagRx.find(line)
            val kind = when (tag?.groupValues?.get(1)?.lowercase()?.let(TextUtil::fold)) {
                "profil", "profile" -> MemoryKind.PROFILE
                "preference" -> MemoryKind.PREFERENCE
                "fakt", "fact" -> MemoryKind.FACT
                "pouceni", "lesson" -> MemoryKind.LESSON
                "epizoda" -> MemoryKind.EPISODE
                else -> null
            }
            if (tag != null) line = line.substring(tag.range.last + 1)
            line = line.trim().trimEnd(',', ';')
            if (line.length < 4 || line.length > 400 || !line.any { it.isLetter() }) { skipped++; continue }
            val key = TextUtil.fold(line)
            if (!seen.add(key)) { skipped++; continue }
            val k = kind ?: classify(line)
            val text1 = (if (line.endsWith('.') || line.endsWith('!') || line.endsWith('?')) line else "$line.")
            items += MemoryItem(kind = k, text = "$text1 (import: $source)".take(600), weight = if (k == MemoryKind.PROFILE) 1.3 else 1.0)
            if (items.size >= 500) break
        }
        return Result(items, null, 0, skipped)
    }

    fun classify(line: String): MemoryKind {
        val f = " " + TextUtil.fold(line) + " "
        return when {
            profileHints.any { TextUtil.fold(it) in f } -> MemoryKind.PROFILE
            prefHints.any { TextUtil.fold(it) in f } -> MemoryKind.PREFERENCE
            else -> MemoryKind.FACT
        }
    }

    // ---------------------------------------------------------------- Google Takeout

    /** Prefixy titulků v Moje aktivita (EN/CS/DE/…) – "Prompted …", "Zadaný dotaz: …". */
    private val promptPrefixRx = Regex("""^(?:Prompted|Asked|Zadan[ýy] (?:dotaz|pokyn):?|Zadáno:?|Dotaz:?|Gefragt|Prompt:?)\s+""", RegexOption.IGNORE_CASE)

    /** MyActivity.json z Google Takeout (Aplikace Gemini). */
    fun parseTakeoutJson(json: String): Result {
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: throw IllegalArgumentException("Soubor není export Moje aktivita (JSON pole).")
        val prompts = mutableListOf<Pair<String, String>>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val header = (o["header"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val products = (o["products"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (!header.contains("Gemini", true) && !header.contains("Bard", true) && products.none { it.contains("Gemini", true) || it.contains("Bard", true) }) continue
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            val m = promptPrefixRx.find(title) ?: continue
            val time = (o["time"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(10)
            prompts += time to title.substring(m.range.last + 1).trim()
        }
        return fromPrompts(prompts)
    }

    /** MyActivity.html z Google Takeout (když uživatel zvolil HTML). */
    fun parseTakeoutHtml(html: String): Result {
        val text = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</div>|</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val prompts = text.lines().map { it.trim() }.mapNotNull { l -> promptPrefixRx.find(l)?.let { "" to l.substring(it.range.last + 1).trim() } }
        return fromPrompts(prompts)
    }

    /** Z dotazů vytáhne výslovná sdělení o sobě („jmenuji se…“, „pamatuj si…“) a sestaví přepis. */
    private fun fromPrompts(prompts: List<Pair<String, String>>): Result {
        if (prompts.isEmpty()) throw IllegalArgumentException("V souboru nejsou žádné dotazy z Aplikace Gemini.")
        val items = mutableListOf<MemoryItem>()
        val seen = HashSet<String>()
        for ((_, p) in prompts) {
            for (op in MemoryExtractor.extract(p)) {
                if (op is MemoryExtractor.Op.Remember && seen.add(TextUtil.fold(op.item.text))) {
                    items += op.item.copy(text = op.item.text.removeSuffix(".") + ". (import: Gemini)", weight = 1.0)
                }
            }
        }
        val transcript = buildString {
            append("Historie dotazů z Google Gemini (import z Google Takeout, ${prompts.size} dotazů).\n\n")
            prompts.forEach { (t, p) -> append(if (t.isNotEmpty()) "[$t] " else "- ").append(TextUtil.clip(p, 2_000)).append("\n\n") }
        }
        return Result(items.take(500), transcript, prompts.size, 0)
    }

    /** Automatické rozpoznání formátu podle obsahu. */
    fun parseAny(content: String, fileName: String = ""): Result {
        val t = content.trimStart()
        return when {
            t.startsWith("[") -> parseTakeoutJson(t)
            t.startsWith("<") || fileName.endsWith(".html", true) -> parseTakeoutHtml(t)
            else -> parseText(t)
        }
    }
}
