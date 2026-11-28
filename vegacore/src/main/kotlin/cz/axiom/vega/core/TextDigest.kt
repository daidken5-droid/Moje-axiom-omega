package cz.axiom.vega.core

import cz.axiom.vega.base.Text

/**
 * Výtah bez modelu (sobota 28. 11. – uživatel zvyklý na ChatGPT): „Shrň mi tohle: <text>“ v režimu Bez sítě / bez klíče
 * dřív skončilo „na tohle potřebuji model v cloudu“. Teď: klíčové věty DOSLOVNĚ a v původním pořadí (žádné převyprávění,
 * nic se nevymýšlí). Důležitost věty = součet četnosti jejích slov, která se v textu opakují (téma textu).
 */
object TextDigest {
    private val SPLIT = Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}0-9„\"])")
    private val STOP = setOf("jsem", "jsme", "jste", "jsou", "bylo", "byla", "byl", "bude", "budou", "jako", "které", "který", "která", "kteri", "ktery", "ktera", "ktere",
        "tento", "tato", "toto", "tyto", "jeho", "její", "jeji", "jejich", "nebo", "také", "take", "ale", "aby", "když", "kdyz", "proto", "protože", "protoze", "podle",
        "před", "pred", "mezi", "jsou", "není", "neni", "ještě", "jeste", "však", "vsak", "řekl", "rekl", "uvedl", "dnes", "který", "také", "this", "that", "with", "from")
    private val CMD = Regex("^(?:prosim\\s+)?(?:shrn\\w*|shrnuti|vytah\\w*|zkrat\\w*|udelej\\s+(?:mi\\s+)?(?:vytah|shrnuti)\\w*|o cem je)(?!\\p{L})")

    /** Text ke zkrácení ze zadání „Shrň mi tohle: …“ (dvojtečka / pomlčka do 60 znaků), nebo null. Krátký text (< 150 znaků, < 3 věty) nemá smysl. */
    fun request(raw: String): String? {
        val t = raw.trim()
        if (!CMD.containsMatchIn(Text.fold(t).lowercase())) return null
        // dvojtečka, pomlčka nebo nový řádek (uživatel ChatGPT píše „Shrň mi tohle“ a text pod to)
        val cut = Regex("[:–—\\n-]").find(t.take(60))?.range?.last ?: return null
        val body = t.substring(cut + 1).trim()
        return body.takeIf { it.length >= 150 && sentences(it).size >= 3 }
    }

    /** „shrň“, „shrň to“, „zkrať to“, „udělej výtah“ BEZ textu – vztahuje se k předchozí zprávě (vloženému textu). */
    private val FOLLOW = Regex("^(?:prosim\\s+)?(?:shrn\\w*|zkrat\\w*|udelej\\s+(?:mi\\s+)?(?:vytah|shrnuti)|vytah)(?:\\s+(?:mi\\s+)?(?:to|tohle|ten text|ten clanek|text|clanek|predchozi|to nahore))?(?:\\s+prosim)?$")
    fun followUp(raw: String) = FOLLOW.matches(Text.fold(raw).lowercase().trim().trimEnd('.', '!', '?', ' '))

    /** Vložený delší text bez pokynu (žádná otázka, ≥ 300 znaků, ≥ 4 věty) – bez modelu nabídnout výtah. */
    // dlouhý POKYN („Napiš e-mail šéfovi, že…“ + popis) není vložený článek – výtah z vlastního zadání by byl nesmysl
    private val ORDER = Regex("^(?:prosim\\s+)?(?:napis|vytvor|preloz|oprav|pomoz|udelej|vysvetli|navrhni|potrebuju|potrebuji|chci|chtel|chtela|muzes|mohl|mohla|poradte|porad|zjisti|najdi|spocitej|vymysli|sestav|priprav|zkontroluj)(?!\\p{L})")
    fun isPastedText(raw: String) = raw.length >= 300 && !raw.trim().endsWith("?") && request(raw) == null && sentences(raw).size >= 4 &&
        !ORDER.containsMatchIn(Text.fold(raw.trim()).lowercase())

    fun sentences(text: String): List<String> = text.replace(Regex("\\s+"), " ").trim().split(SPLIT).map { it.trim() }.filter { it.length >= 3 }

    private fun words(s: String) = Regex("\\p{L}{4,}").findAll(Text.fold(s).lowercase()).map { it.value }.filter { it !in STOP }.toList()

    /** Nejvýš 3 klíčové věty (aspoň 2 u textu ze 4+ vět), doslovně, v původním pořadí; null, když není z čeho vybírat. */
    fun summarize(text: String, max: Int = 3): List<String>? {
        val ss = sentences(text)
        if (ss.size < 3) return null
        val freq = ss.flatMap { words(it).distinct() }.groupingBy { it }.eachCount()
        val score = ss.map { s -> val w = words(s); if (w.isEmpty()) 0.0 else w.sumOf { (freq[it] ?: 1).let { f -> if (f > 1) f.toDouble() else 0.0 } } / Math.sqrt(w.size.toDouble()) }
        val n = minOf(max, maxOf(if (ss.size >= 4) 2 else 1, (ss.size + 2) / 3))
        val picked = ss.indices.filter { score[it] > 0 }.sortedByDescending { score[it] }.take(n).sorted()
        return picked.map { ss[it] }.takeIf { it.isNotEmpty() }
    }
}
