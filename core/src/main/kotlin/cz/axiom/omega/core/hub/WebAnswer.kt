package cz.axiom.omega.core.hub

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.util.TextUtil

/**
 * ODPOVĚĎ Z WEBU BEZ MOZKU: když v telefonu není mozek / cloud selže, ale je internet – výňatek z Wikipedie
 * (první věty hesla) nebo přehled výsledků hledání s odkazy. Žádný jazykový model, nic se nevymýšlí.
 * Místo surové chyby dostane uživatel aspoň ověřitelné zdroje.
 */
object WebAnswer {
    fun compose(query: String, ground: (String) -> List<Snippet>): Instant.Answer? {
        val q = cz.axiom.omega.core.agent.Topic.bare(query) ?: query.trim()
        val hits = ground(q)
        if (hits.isEmpty()) return null
        val wiki = hits.firstOrNull { it.title.startsWith("Wikipedie:") && it.text.length >= 80 }
        val text = buildString {
            if (wiki != null) {
                val sents = wiki.text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+(?=[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ])")).take(3).joinToString(" ")
                append(TextUtil.clip(sents, 700)).append("\n\n— *").append(wiki.title.removePrefix("Wikipedie: ").let { "Wikipedie – $it" }).append("*: ").append(wiki.source)
                val more = hits.filter { it !== wiki && !it.title.startsWith("Wikipedie:") }.take(3)
                if (more.isNotEmpty()) { append("\n\n**Další zdroje:**\n"); more.forEach { append("- [").append(it.title).append("](").append(it.source).append(")\n") } }
            } else {
                append("Na webu jsem k „$q“ našel:\n\n")
                hits.take(4).forEach { h -> append("- **[").append(h.title).append("](").append(h.source).append(")**").append(if (h.text != h.title) " – " + TextUtil.clip(h.text, 220) else "").append('\n') }
                append("\n_Výňatek z výsledků hledání (bez mozku) – ověř si podrobnosti u zdroje._")
            }
        }
        return Instant.Answer(text.trim(), if (wiki != null) "web · Wikipedie" else "web · výsledky hledání", confidence = 80,
            contract = AnswerContract(if (wiki != null) AnswerContract.Type.VERIFIED_FACT else AnswerContract.Type.ESTIMATE,
                if (wiki != null) "výňatek z online Wikipedie (bez mozku)" else "přehled výsledků hledání (bez mozku)", hits.map { it.source }.take(5)))
    }
}
