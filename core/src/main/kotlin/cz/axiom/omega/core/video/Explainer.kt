package cz.axiom.omega.core.video

import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.util.TextUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * VIDEO VYSVĚTLENÍ: téma → krátké video (30–60 s) s titulky a tichou hudbou, celé vyrobené v telefonu.
 *
 * Postup (podle šablony „explainer video“): 1) podklady – offline Wikipedie, jinak web, jinak mozek;
 * 2) storyboard 4 scén (nejvýš 8), každá 7–10 s, první se ptá, poslední odpovídá; 3) jednotný styl
 * a vykreslení scén; 4) kontrola (text mimo plochu, kontrast); 5) hudba kódem; 6) spojení do MP4.
 *
 * Podklady jsou DATA, nikdy pokyny. Každý fakt v titulku pochází z podkladu (bez mozku doslova z vět článku).
 */
enum class SceneKind { QUESTION, FACT, NUMBER, ANSWER }

data class Scene(
    val index: Int,
    val caption: String,
    val seconds: Int,
    val kind: SceneKind,
    /** Velký prvek scény: „?“, číslo/rok, „✓“. */
    val big: String? = null,
)

data class Storyboard(
    val topic: String,
    val scenes: List<Scene>,
    /** Odkud jsou fakta („Wikipedie: Fotosyntéza“, „web“, „mozek“). */
    val source: String,
    /** true = titulky složil jazykový model (ne doslovné věty zdroje). */
    val byBrain: Boolean = false,
) {
    val seconds: Int get() = scenes.sumOf { it.seconds }
}

object VideoIntent {
    private val rx = listOf(
        Regex("^/video\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:prosím\\s+)?(?:udělej|udelej|vytvoř|vytvor|natoč|natoc|vyrob|připrav|priprav|sestav|nachystej)(?:\\s+mi)?\\s+(?:krátké\\s+|kratke\\s+|vysvětlující\\s+|vysvetlujici\\s+)?(?:video|videovysvětlení|videovysvetleni|explainer|videoklip)\\s+(?:o|na\\s+téma|na\\s+tema|k\\s+tématu|k\\s+tematu|vysvětlující|vysvetlujici|ke)?\\s*(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:video|videovysvětlení|videovysvetleni|explainer)\\s*(?:o|:|–|-)\\s*(.+)$", RegexOption.IGNORE_CASE),
    )

    /** Téma videa, nebo null (nejde o žádost o video). */
    fun parse(text: String): String? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (t.length > 160) return null
        for (r in rx) {
            val m = r.find(t) ?: continue
            val topic = m.groupValues[1].trim().trim('„', '“', '"', '\'').removePrefix("tom, ").removePrefix("tom ").trim()
            if (topic.length in 2..120) return topic
        }
        return null
    }

    /** „podrobně / delší“ → víc scén (nejvýš 8). */
    fun wantsLong(text: String) = Regex("(podrobn|delší|delsi|dlouh|detailn)", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

object Explainer {
    const val MIN_SCENE = 7
    const val MAX_SCENE = 10
    const val MAX_SCENES = 8
    const val MAX_CAPTION = 150

    /** Věty podkladu (bez nadpisů, citací [n]), v pořadí. */
    fun sentences(text: String, max: Int = 12): List<String> {
        val out = ArrayList<String>()
        var prev = ""
        for (k in 1..max) {
            val s = Instant.firstSentences(text, k, 6000) ?: break
            if (s.length <= prev.length) break
            out += s.substring(prev.length).trim()
            prev = s
        }
        return out.filter { it.length >= 20 }
    }

    /** Titulek z věty: bez závorek (výslovnost, data narození), zkrácený jen na hranici věty/vedlejší věty. */
    fun caption(sentence: String): String? {
        var s = sentence.replace(Regex("\\s*\\([^()]*\\)"), "").replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
        if (s.length <= MAX_CAPTION) return s.takeIf { it.length >= 15 }
        // delší věta: utnout za čárkou / pomlčkou / středníkem před limitem (nikdy uprostřed slova bez naznačení)
        val cut = listOf(", ", " – ", "; ", ": ").map { s.lastIndexOf(it, MAX_CAPTION - 1) }.maxOrNull() ?: -1
        if (cut < 50) return null
        s = s.substring(0, cut).trimEnd(',', ';', ':', ' ', '–') + "."
        return s
    }

    private val yearRx = Regex("(?<![\\d.,])(1[0-9]{3}|20[0-9]{2})(?![\\d])")
    private val numRx = Regex("(?<![\\d.,])(\\d{1,3}(?:[  ]\\d{3})+|\\d+(?:,\\d+)?)\\s?(%|km²|km|m|kg|t|°C|let|milion\\w*|miliard\\w*|tisíc\\w*)?(?![\\p{L}\\d])")

    /** Výrazné číslo věty (rok, množství s jednotkou) pro scénu NUMBER. */
    fun bigNumber(caption: String): String? {
        numRx.findAll(caption).firstOrNull { m -> m.groupValues[2].isNotEmpty() && m.groupValues[1].length >= 2 }?.let { return it.value.trim() }
        // číslo + slovo za ním („130 terawattů“, „3 000 druhů“) – ne rok
        Regex("(?<![\\d.,])(\\d{1,3}(?:[  ]\\d{3})+|\\d{2,3}|\\d+,\\d+)\\s(\\p{L}{2,12})(?![\\p{L}])").find(caption)?.let { m ->
            if (!yearRx.matches(m.groupValues[1])) return m.value
        }
        return yearRx.find(caption)?.value
    }

    private fun personLike(first: String) = Regex("\\(\\s*(?:\\*\\s*)?\\d{1,2}\\.\\s*\\p{L}+\\s+\\d{4}").containsMatchIn(first)
    private fun dead(first: String) = Regex("\\d{4}[^)]*[–-][^)]*\\d{4}").containsMatchIn(first.substringBefore(')'))

    fun question(topic: String, first: String?): String {
        val t = topic.trim().replaceFirstChar { it.uppercaseChar() }
        return when {
            first != null && personLike(first) -> if (dead(first)) "Kdo byl $t?" else "Kdo je $t?"
            else -> "Co je $t?"
        }
    }

    private fun seconds(caption: String) = (MIN_SCENE + TextUtil.words(caption, 1).size / 7).coerceIn(MIN_SCENE, MAX_SCENE)

    /**
     * BEZ MOZKU: storyboard přímo z článku. Scéna 1 = otázka, střed = další věty (s číslem → velké číslo),
     * poslední = definice (odpověď). Null = podklad nestačí (méně než 2 použitelné věty).
     */
    fun fromSource(topic: String, text: String, source: String, long: Boolean = false): Storyboard? {
        val sents = sentences(text)
        if (sents.isEmpty()) return null
        val answer = caption(sents[0]) ?: return null
        val wanted = if (long) MAX_SCENES - 2 else 2
        val facts = sents.drop(1).mapNotNull { caption(it) }.filter { TextUtil.fold(it) != TextUtil.fold(answer) }.distinct().take(wanted)
        if (facts.isEmpty()) return null
        val scenes = ArrayList<Scene>()
        val q = question(topic, sents[0])
        scenes += Scene(1, q, MIN_SCENE, SceneKind.QUESTION, "?")
        facts.forEach { f ->
            val n = bigNumber(f)
            scenes += Scene(scenes.size + 1, f, seconds(f), if (n != null) SceneKind.NUMBER else SceneKind.FACT, n)
        }
        scenes += Scene(scenes.size + 1, answer, seconds(answer), SceneKind.ANSWER, "✓")
        return Storyboard(topic, stretch(scenes), source)
    }

    /** Celková délka 30–60 s: krátké scény se prodlouží (nejvýš 10 s), dlouhé video se zkrátí. */
    fun stretch(scenes: List<Scene>): List<Scene> {
        val out = scenes.map { it.copy(seconds = it.seconds.coerceIn(MIN_SCENE, MAX_SCENE)) }.toMutableList()
        var i = 0
        while (out.sumOf { it.seconds } < 30 && out.any { it.seconds < MAX_SCENE }) {
            val j = i % out.size
            if (out[j].seconds < MAX_SCENE) out[j] = out[j].copy(seconds = out[j].seconds + 1)
            i++
        }
        while (out.sumOf { it.seconds } > 60 && out.any { it.seconds > MIN_SCENE }) {
            val j = out.indices.maxBy { out[it].seconds }
            out[j] = out[j].copy(seconds = out[j].seconds - 1)
        }
        return out.mapIndexed { k, s -> s.copy(index = k + 1) }
    }

    /** Zadání pro mozek (jen když chybí podklad): JSON, žádná vymyšlená čísla. */
    fun prompt(topic: String, long: Boolean): String = """
        Připrav storyboard krátkého vysvětlujícího videa (30–60 s) na téma: „$topic“.
        ${if (long) "6 až 8" else "4"} scén. První scéna položí otázku, poslední na ni odpoví jednou větou.
        Každý titulek je jedna česká věta, nejvýš 140 znaků. Uváděj jen fakta, kterými si jsi jistý; čísla jen ověřená, jinak žádná.
        Odpověz POUZE JSON bez komentáře:
        {"scenes":[{"caption":"…","kind":"question|fact|number|answer","big":"rok nebo číslo jen u number"}]}
    """.trimIndent()

    /** Storyboard z odpovědi mozku (ověřený tvar); null = nepoužitelné. */
    fun parse(topic: String, raw: String): Storyboard? = runCatching {
        val body = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
        val arr = Json.parseToJsonElement(body).jsonObject["scenes"]!!.jsonArray
        val scenes = arr.mapNotNull { e ->
            val o = e.jsonObject
            val cap = o["caption"]?.jsonPrimitive?.contentOrNull?.trim()?.let { caption(it) } ?: return@mapNotNull null
            val kind = when (o["kind"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "question" -> SceneKind.QUESTION; "number" -> SceneKind.NUMBER; "answer" -> SceneKind.ANSWER; else -> SceneKind.FACT
            }
            val big = o["big"]?.jsonPrimitive?.contentOrNull?.trim()?.take(14)?.takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
            Scene(0, cap, seconds(cap), if (kind == SceneKind.NUMBER && big == null) SceneKind.FACT else kind, big)
        }.take(MAX_SCENES)
        if (scenes.size < 3) return null
        // první vždy otázka, poslední vždy odpověď
        val fixed = scenes.mapIndexed { i, s ->
            when (i) {
                0 -> s.copy(kind = SceneKind.QUESTION, big = "?", caption = if (s.caption.endsWith("?")) s.caption else question(topic, null))
                scenes.lastIndex -> s.copy(kind = SceneKind.ANSWER, big = "✓")
                else -> if (s.kind == SceneKind.QUESTION || s.kind == SceneKind.ANSWER) s.copy(kind = SceneKind.FACT) else s
            }
        }
        Storyboard(topic, stretch(fixed), "mozek (bez podkladu – ověř fakta)", byBrain = true)
    }.getOrNull()

    /**
     * Článek pro téma videa: shoda hesla (WikiReflex), jinak shoda kmenů – „video o fotosyntéze“ → „Fotosyntéza“
     * (každé slovo tématu sdílí s názvem začátek, liší se jen koncovka). Jen česká Wikipedie a vlastní dokumenty.
     */
    fun pickArticle(topic: String, hits: List<cz.axiom.omega.core.agent.Snippet>): cz.axiom.omega.core.agent.Snippet? {
        val ok = hits.filter { !it.title.startsWith("Wikipedia ") && !it.title.startsWith("Wikizdroje") }
        cz.axiom.omega.core.agent.WikiReflex.match(topic, ok)?.let { return it }
        val tw = TextUtil.fold(topic).lowercase().split(' ').filter { it.isNotBlank() }
        return ok.firstOrNull { h ->
            val nw = cz.axiom.omega.core.agent.WikiReflex.cleanTitle(h.title).split(' ').filter { it.isNotBlank() }
            nw.size == tw.size && nw.zip(tw).all { (a, b) ->
                val p = a.commonPrefixWith(b).length
                a == b || (minOf(a.length, b.length) >= 4 && p >= maxOf(a.length, b.length) - 3 && p >= minOf(a.length, b.length) - 2)
            }
        }
    }

    /** Hezký název tématu z hesla („Wikipedie: Brno (město)“ → „Brno“). */
    fun displayTitle(title: String) = title.substringAfter(": ").replace(Regex("\\s*\\(.*\\)\\s*"), "").trim()

    /** Seznam scén pro uživatele (před a po výrobě). */
    fun describe(b: Storyboard): String = b.scenes.joinToString("\n") { "${it.index}. (${it.seconds} s) ${it.caption}" }
}

/** KONTROLA SNÍMKŮ (místo recenzenta): zalomení titulku do plochy a kontrast barev podle WCAG. */
object SceneQa {
    /** Zalomí text po slovech do šířky [maxWidth] podle měření [measure]; slovo širší než plocha = null (přeteklo by). */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String>? {
        val lines = ArrayList<String>()
        var cur = ""
        for (w in text.split(' ').filter { it.isNotEmpty() }) {
            if (measure(w) > maxWidth) return null
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (measure(next) <= maxWidth) cur = next else { lines += cur; cur = w }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /**
     * Největší velikost písma (z [sizes], sestupně), při které se titulek vejde do [maxWidth]×[maxLines].
     * [measure] dostane (text, velikost). Null = nevejde se ani nejmenší → scéna neprošla kontrolou.
     */
    fun fit(text: String, maxWidth: Float, maxLines: Int, sizes: List<Float>, measure: (String, Float) -> Float): Pair<Float, List<String>>? {
        for (size in sizes.sortedDescending()) {
            val lines = wrap(text, maxWidth) { measure(it, size) } ?: continue
            if (lines.size <= maxLines) return size to lines
        }
        return null
    }

    /**
     * Totéž, ale počet řádků určuje VÝŠKA plochy: první účaří na 0, každý další o size×[lineFactor] níž, poslední
     * účaří nejvýš [maxBaseline]. Chrání před překrytím zápatí (zdroj, průběh) dlouhým titulkem.
     */
    fun fitBox(text: String, maxWidth: Float, maxBaseline: Float, sizes: List<Float>, lineFactor: Float = 1.28f, measure: (String, Float) -> Float): Pair<Float, List<String>>? {
        for (size in sizes.sortedDescending()) {
            val maxLines = (maxBaseline / (size * lineFactor)).toInt() + 1
            val lines = wrap(text, maxWidth) { measure(it, size) } ?: continue
            if (lines.size <= maxLines) return size to lines
        }
        return null
    }

    private fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
    private fun lum(rgb: Int) = 0.2126 * lin(rgb shr 16 and 255) + 0.7152 * lin(rgb shr 8 and 255) + 0.0722 * lin(rgb and 255)

    /** Kontrastní poměr WCAG (1–21). Titulky potřebují ≥ 4,5. */
    fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
}
