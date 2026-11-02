package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * POSLEDNÍ ZÁCHRANA: žádný mozek (offline, nestažený) nebo cloud selhal → odpověď z offline knihovny
 * (Wikipedie, vlastní dokumenty, balíčky) místo chybové hlášky. AI tak odpoví VŽDY, v každém režimu.
 *
 * Vybírá věty, které obsahují nejvíc obsahových slov otázky (extraktivní shrnutí – nic si nevymýšlí),
 * a vždy uvádí zdroj.
 */
object OfflineAnswer {

    private val stop = setOf("jak", "jaky", "jaka", "jake", "kde", "kdy", "kdo", "co", "se", "je", "to", "ten", "ta", "na", "v", "ve", "z", "ze", "a", "i",
        "o", "pro", "proc", "neco", "mi", "me", "rekni", "vysvetli", "popis", "prosim", "jsou", "byl", "byla", "bylo", "funguje", "znamena")

    fun compose(question: String, snippets: List<Snippet>, reason: String): Instant.Answer? {
        val q = TextUtil.words(question, 3).map { it.take(6) }.filter { it !in stop }.toSet()
        if (q.isEmpty() || snippets.isEmpty()) return null
        // nejlepší zdroj = nejvíc slov otázky v názvu + textu
        val scored = snippets.map { s ->
            val t = TextUtil.fold(s.title + " " + s.text)
            s to q.count { t.contains(it) } + 2 * q.count { TextUtil.fold(s.title).contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.first ?: return null
        val coverage = q.count { TextUtil.fold(best.title + " " + best.text).contains(it) } * 100 / q.size
        if (coverage < 50) return null
        // věty s nejvyšší shodou, v původním pořadí, max ~600 znaků
        val sentences = best.text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+(?=[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ0-9])")).filter { it.length in 20..400 }
        val ranked = sentences.withIndex().map { (i, s) -> Triple(i, s, q.count { TextUtil.fold(s).contains(it) } + if (i == 0) 1 else 0) }
            .filter { it.third > 0 }.sortedByDescending { it.third }.take(3).sortedBy { it.first }
        var body = ranked.joinToString(" ") { it.second }
        if (body.isBlank()) body = best.text.take(400)
        if (body.length > 650) body = body.take(640).substringBeforeLast(' ') + "…"
        val others = scored.drop(1).take(2).joinToString(", ") { "„${it.first.title}“" }
        val text = "$body\n\n— *z offline zdroje „${best.title}“*" + (if (others.isNotEmpty()) " · další: $others" else "") +
            "\n\n_Odpovídám bez mozku ($reason) – výňatek, ne úvaha._"
        return Instant.Answer(text, "offline knihovna", confidence = coverage.coerceAtMost(90))
    }
}
