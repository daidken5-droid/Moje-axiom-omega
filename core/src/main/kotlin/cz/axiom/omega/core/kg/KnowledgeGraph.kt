package cz.axiom.omega.core.kg

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.facts.FactStore
import cz.axiom.omega.core.facts.IsoReflex
import cz.axiom.omega.core.util.TextUtil

/**
 * ZERO-BRAIN 2.0 – GRAF ZNALOSTÍ + DETERMINISTICKÉ ODVOZOVÁNÍ (bez jazykového modelu).
 *
 * Vztahy (každý s podkladem): Stát —hlavní_město→ Město, Stát —leží_v→ Kontinent (facts/staty.tsv),
 * Stát —měna→ Kód (factstore/iso.tsv, Unicode CLDR). Odvozené vztahy jsou VÝSLOVNĚ označené:
 *  • inverze: Město —je_hlavním_městem→ Stát,
 *  • tranzitivita části celku: Město ⊂ Stát ⊂ Kontinent ⇒ Město ⊂ Kontinent (jen pro vztah „část celku“).
 * Co se NIKDY neodvozuje: kauzalita („A před B“ ≠ „A způsobilo B“), vlastnosti přes nesouvisející vztahy.
 * Každá odpověď nese řetězec kroků a jejich podklady; chybějící článek řetězu = zdržet se.
 */
object KnowledgeGraph {
    data class Edge(val s: String, val p: String, val o: String, val source: String)

    private const val SRC_STATES = "AVIOR fakta (staty.tsv)"
    private val states: List<Triple<String, String, String>> by lazy {
        val st = KnowledgeGraph::class.java.classLoader?.getResourceAsStream("facts/staty.tsv") ?: return@lazy emptyList()
        st.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() }.map { it.split('\t') }.filter { it.size >= 3 }.map { Triple(it[0], it[1], it[2]) } }
    }

    // ------------------------------------------------------------------ morfologie (české koncovky)
    private val endings = setOf("", "a", "u", "e", "o", "y", "i", "em", "ou", "ech", "ach", "um", "ami", "im", "ych", "eho", "emu", "ym", "ie", "ii", "ho", "mu", "m", "ch", "mi", "je", "ji", "ina", "inu", "ine", "ina")
    private fun words(s: String) = TextUtil.fold(s).lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }
    fun same(w: String, t: String): Boolean {
        if (w == t) return true
        var p = 0; while (p < w.length && p < t.length && w[p] == t[p]) p++
        return p >= 3 && p >= t.length - 2 && t.substring(p) in endings && w.substring(p) in endings
    }
    private fun mentions(prompt: List<String>, name: String): Boolean = words(name).let { n -> n.isNotEmpty() && n.all { t -> prompt.any { same(it, t) } } }

    /** Jednoznačně zmíněné hlavní město (nejdelší shoda; remíza = null). */
    private fun capitalIn(prompt: String): Triple<String, String, String>? {
        val w = words(prompt)
        val hits = states.filter { mentions(w, it.second) }
        val best = hits.maxOfOrNull { words(it.second).size } ?: return null
        return hits.filter { words(it.second).size == best }.distinctBy { it.second }.singleOrNull()
    }
    private fun countryIn(prompt: String): Triple<String, String, String>? {
        val w = words(prompt)
        val hits = states.filter { mentions(w, it.first) }
        val best = hits.maxOfOrNull { words(it.first).size } ?: return null
        return hits.filter { words(it.first).size == best }.singleOrNull()
    }

    private val continentWord = Regex("\\b(kontinent\\w*|svetadil\\w*)\\b")
    private val inWhichState = Regex("\\b(v jake zemi|ve ktere zemi|v jakem state|ve kterem state|ktereho statu|jake zeme) (je|lezi|se nachazi|hlavnim mestem)\\b|\\bje hlavnim mestem\\b|\\bhlavni mesto ktere(ho)? (zeme|statu)\\b")
    private val currencyOfCapital = Regex("\\b(men\\w*|platidl\\w*|plati se)\\b.*\\b(hlavn\\w* mest\\w*)\\b|\\b(hlavn\\w* mest\\w*)\\b.*\\b(men\\w*|platidl\\w*|plati se)\\b")
    private val isIn = Regex("^(je|lezi) (.+?) (v|na) (evrope|asii|africe|americe|severni americe|jizni americe|australii|oceanii|antarktide)$")
    private val causal = Regex("\\b(zpusobil\\w*|pricin\\w*|vedl\\w* k|kvuli|proto ze|zavinil\\w*)\\b")

    fun answer(prompt: String): Instant.Answer? {
        if (prompt.length > 200 || states.isEmpty()) return null
        val f = TextUtil.fold(prompt).lowercase().trim().trimEnd('?', '.', '!')
        if (causal.containsMatchIn(f)) return null // kauzalitu graf neodvozuje – převezme výzkum / mozek
        // 1) měna státu podle hlavního města: Město → Stát → Měna (2 kroky)
        if (currencyOfCapital.containsMatchIn(f)) {
            val c = capitalIn(prompt) ?: return null
            val e = IsoReflex.resolve(c.first) ?: return null
            val iso = FactStore.load("iso")
            val cur = iso.value(e, "currency", java.time.LocalDate.now()) ?: return null
            val curName = iso.value(e, "currency_name_cs", java.time.LocalDate.now())?.value
            return ans("Ve státě s hlavním městem **${c.second}** (${c.first}) se platí **${curName ?: cur.value} (${cur.value})**.",
                listOf(Edge(c.first, "hlavní město", c.second, SRC_STATES), Edge(c.first, "měna", cur.value, cur.source)), derived = false)
        }
        // 2) kontinent města: Město ⊂ Stát ⊂ Kontinent (tranzitivita části celku, výslovně odvozeno)
        if (continentWord.containsMatchIn(f)) {
            val cap = capitalIn(prompt)
            val ctry = countryIn(prompt)
            if (cap != null && (ctry == null || ctry.first == cap.first)) return ans("**${cap.second}** leží na kontinentu **${cap.third}** – je hlavním městem státu ${cap.first}.",
                listOf(Edge(cap.second, "je hlavním městem (inverze)", cap.first, SRC_STATES), Edge(cap.first, "leží v", cap.third, SRC_STATES)), derived = true)
            return null
        }
        // 3) ve kterém státě je hlavní město X
        if (inWhichState.containsMatchIn(f)) {
            val cap = capitalIn(prompt) ?: return null
            return ans("**${cap.second}** je hlavní město státu **${cap.first}** (${cap.third}).", listOf(Edge(cap.second, "je hlavním městem (inverze)", cap.first, SRC_STATES)), derived = true)
        }
        // 4) „je X v Evropě?“ – ano/ne jen když X v grafu je (uzavřený svět jen nad známými entitami)
        isIn.find(f)?.let { m ->
            val name = m.groupValues[2]; val asked = m.groupValues[4]
            val cap = capitalIn(name); val ctry = countryIn(name)
            val (label, continent, chain) = when {
                ctry != null -> Triple(ctry.first, ctry.third, listOf(Edge(ctry.first, "leží v", ctry.third, SRC_STATES)))
                cap != null -> Triple(cap.second, cap.third, listOf(Edge(cap.second, "je hlavním městem (inverze)", cap.first, SRC_STATES), Edge(cap.first, "leží v", cap.third, SRC_STATES)))
                else -> return null
            }
            val yes = words(continent).any { c -> words(asked).any { same(it, c) || same(c, it) } }
            return ans(if (yes) "Ano – **$label** leží v kontinentu **$continent**." else "Ne – podle tabulky leží **$label** v kontinentu **$continent**.", chain, derived = cap != null && ctry == null)
        }
        return null
    }

    private fun ans(text: String, chain: List<Edge>, derived: Boolean): Instant.Answer {
        val steps = chain.joinToString(" → ") { "${it.s} —${it.p}→ ${it.o}" }
        val srcs = chain.map { it.source }.distinct()
        return Instant.Answer("$text\n\n_Řetězec: $steps${if (derived) " · odvozeno (tranzitivita / inverze vztahu, ne přímý záznam)" else ""} · podklady: ${srcs.joinToString("; ")}_",
            "graf znalostí", confidence = 95,
            contract = AnswerContract(AnswerContract.Type.VERIFIED_FACT, if (derived) "odvozeno z ${chain.size} vztahů grafu (bez modelu)" else "${chain.size} vztahy grafu (bez modelu)", srcs))
    }
}
