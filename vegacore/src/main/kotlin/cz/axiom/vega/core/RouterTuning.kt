package cz.axiom.vega.core

import cz.axiom.vega.base.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * AUTONOMIE, VRSTVA A – NAUČENÁ PRAVIDLA ROUTERU. Z deníku slabin: zpráva skončila v obecném rozhovoru / rešerši,
 * uživatel ji hned opravil nebo přeformuloval a navazující zpráva patřila konkrétní akci → návrh pravidla
 * „tyto výrazy = tato akce“.
 *
 * Bezpečnost:
 *  - pravidlo smí jen nahradit obecný rozhovor / rešerši (Router: learnedMatch až po všech pevných pravidlech),
 *  - aktivuje se jen když NEZMĚNÍ výsledek žádného případu kontrolní sady [GUARD] a své příklady pošle správně,
 *  - samo se zapne až po [AUTO_EVIDENCE] nezávislých dokladech; jinak čeká jako návrh na klepnutí,
 *  - každá změna má historii a jde vrátit (stav REVERTED; vrácené pravidlo se samo znovu nezapne).
 */
class RouterTuning(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Status { PROPOSED, ACTIVE, REJECTED, REVERTED }
    @Serializable data class Event(val at: Long, val action: String, val note: String = "")
    @Serializable data class Rule(val id: String, val tokens: List<String>, val target: String, val examples: List<String>, val evidence: Int,
                                  val status: String, val reason: String = "", val history: List<Event> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = "selftune/router-rules.json"
    private val lock = Any()
    @Volatile private var activeCache: List<Router.Learned>? = null

    fun rules(): List<Rule> = kv.read(key)?.let { runCatching { json.decodeFromString(ListSerializer(Rule.serializer()), it) }.getOrNull() } ?: emptyList()
    private fun save(r: List<Rule>) { kv.write(key, json.encodeToString(ListSerializer(Rule.serializer()), r.takeLast(200))); activeCache = null }

    fun active(): List<Router.Learned> = activeCache ?: rules().filter { it.status == Status.ACTIVE.name }.map { it.learned() }.also { activeCache = it }
    private fun Rule.learned() = Router.Learned(id, tokens.toSet(), target, examples)

    /** Projde deník, doplní / aktualizuje návrhy a ty s dost doklady po úspěšné kontrole zapne. Vrací změněná pravidla. */
    fun refresh(signals: List<SelfTune.Signal>, ctxFor: (String) -> Router.Ctx = { NONE }): List<Rule> = synchronized(lock) {
        val cands = signals.filter { it.kind in setOf("CORRECTION", "REPHRASE", "WRONG_INTENT") && it.area in FALLBACK && it.resolved.isNotBlank() &&
                it.resolved != it.area && it.resolved !in FALLBACK && !it.resolved.startsWith("přímý") && it.prevExcerpt.isNotBlank() }
            .mapNotNull { s -> tokensOf(s.prevExcerpt, s.excerpt)?.let { Cand(it, s.resolved, s.prevExcerpt, s.at / 86_400_000L) } }
            .groupBy { it.tokens.sorted().joinToString(" ") + "→" + it.target }
        val rules = rules().toMutableList(); val changed = ArrayList<Rule>()
        for ((id, group) in cands) {
            val tokens = group.first().tokens; val target = group.first().target; val examples = group.map { it.example }.distinct().takeLast(5)
            val days = group.map { it.day }.distinct().size // doklad = jiný den (opakování v jedné chvíli není nezávislé)
            val i = rules.indexOfFirst { it.id == id }
            var r = if (i >= 0) rules[i].copy(examples = (rules[i].examples + examples).distinct().takeLast(5), evidence = maxOf(rules[i].evidence, days))
                    else Rule(id, tokens.sorted(), target, examples, days, Status.PROPOSED.name, history = listOf(Event(clock(), "návrh", "z deníku slabin")))
            if (r.status == Status.PROPOSED.name && r.evidence >= AUTO_EVIDENCE && !needsConsent(r.target)) {
                val v = validate(r, rules.filter { it.status == Status.ACTIVE.name && it.id != r.id }, ctxFor)
                r = if (v == null) r.copy(status = Status.ACTIVE.name, reason = "", history = r.history + Event(clock(), "zapnuto samo", "${r.evidence} doklady, kontrolní sada beze změny"))
                    else r.copy(reason = v)
            }
            if (i >= 0) { if (rules[i] != r) { rules[i] = r; changed += r } } else { rules += r; changed += r }
        }
        if (changed.isNotEmpty()) save(rules)
        changed
    }

    /** Ruční zapnutí (návrh s jedním dokladem) – i tak jen po kontrole. Vrací null = zapnuto, jinak důvod. */
    fun activate(id: String, ctxFor: (String) -> Router.Ctx = { NONE }): String? = synchronized(lock) {
        val rules = rules().toMutableList(); val i = rules.indexOfFirst { it.id == id }; if (i < 0) return "Pravidlo neexistuje."
        val v = validate(rules[i], rules.filter { it.status == Status.ACTIVE.name && it.id != id }, ctxFor)
        rules[i] = if (v == null) rules[i].copy(status = Status.ACTIVE.name, reason = "", history = rules[i].history + Event(clock(), "zapnuto uživatelem"))
                   else rules[i].copy(reason = v)
        save(rules); v
    }
    fun revert(id: String) = setStatus(id, Status.REVERTED, "vráceno uživatelem")
    fun reject(id: String) = setStatus(id, Status.REJECTED, "zamítnuto uživatelem")
    private fun setStatus(id: String, st: Status, note: String) = synchronized(lock) {
        val rules = rules().map { if (it.id == id) it.copy(status = st.name, history = it.history + Event(clock(), note)) else it }; save(rules)
    }

    /** null = pravidlo je bezpečné; jinak čitelný důvod odmítnutí. */
    fun validate(r: Rule, others: List<Rule>, ctxFor: (String) -> Router.Ctx = { NONE }): String? {
        if (r.tokens.size < 2) return "příliš obecné (méně než 2 výrazy)"
        val with = others.map { it.learned() } + r.learned()
        val base = others.map { it.learned() }
        for (g in GUARD) {
            val c = g.ctx; val before = SelfTune.area(Router.route(g.text, c.copy(learned = base))); val after = SelfTune.area(Router.route(g.text, c.copy(learned = with)))
            if (before != after) return "změnilo by kontrolní případ „${g.text}“ ($before → $after)"
        }
        val miss = r.examples.firstOrNull { ex -> val c = ctxFor(ex); SelfTune.area(Router.route(ex, c.copy(learned = with))) != r.target }
        return miss?.let { "příklad „$it“ by pořád nešel na ${r.target} (akce v tomto kontextu nejde spustit)" }
    }

    private data class Cand(val tokens: Set<String>, val target: String, val example: String, val day: Long)

    companion object {
        const val AUTO_EVIDENCE = 2
        /** Akce s vedlejšími účinky (model, kód, dokumenty, NFC, vlastní rozšíření) se samy nezapínají – jen návrh na klepnutí. */
        fun needsConsent(target: String) = target.startsWith("nástroj:ext.") || target.startsWith("postup:") ||
            target in setOf("záměr:NewProject", "záměr:Code", "záměr:Demand", "záměr:Nfc", "záměr:Hypothesis", "záměr:CompareFiles", "záměr:MakeButton")
        private val FALLBACK = setOf("záměr:Talk", "záměr:Research")
        val NONE = Router.Ctx(false, null, false, false, null)
        private val STOP = setOf("prosim", "muzes", "mohl", "mohla", "bys", "chci", "potrebuju", "jsem", "myslel", "myslela", "myslim", "jen", "tak", "taky", "ale",
            "tohle", "tento", "tato", "tyto", "toho", "tomu", "tom", "ten", "ta", "to", "pro", "mi", "me", "mne", "nam", "jak", "co", "kde", "kdy", "nebo", "jsi", "jsme")

        /** Výrazy pravidla: obsahová slova původní věty, přednostně ta, která zopakoval i uživatel v opravě. */
        fun tokensOf(original: String, followUp: String): Set<String>? {
            fun content(s: String) = Text.words(Text.fold(s.replace(Regex("‹[^›]*›"), " "))).filter { it !in STOP }
            val a = content(original); val b = content(followUp).toSet()
            val shared = a.filter { it in b }.distinct()
            val pick = (if (shared.size >= 2) shared else a.distinct()).take(4)
            return pick.toSet().takeIf { it.size >= 2 }
        }

        data class Case(val text: String, val ctx: Router.Ctx)
        private val PROJ = Router.Ctx(false, null, true, false, null); private val RES = Router.Ctx(false, null, false, true, null)
        /** KONTROLNÍ SADA: naučené pravidlo nesmí změnit výsledek žádného z těchto případů (rozhovor, rešerše, akce). */
        val GUARD: List<Case> = listOf(
            "jak se máš?", "co bys dělal na mém místě, když mi šéf nedal přidání?", "přemýšlím, jestli si vzít hypotéku teď, nebo počkat",
            "operation Prato", "Projekt Manhattan", "kauza Dozimetr", "Bitva u Slavkova", "operace Anthropoid",
            "Je možné, že pyramidy postavili s pomocí nějaké ztracené technologie?", "Co když přistání na Měsíci bylo natočené ve studiu?",
            "vymysli mi pět názvů pro kavárnu u řeky", "napiš básničku k narozeninám pro babičku", "potřebuju slogan pro cyklistický obchod",
            "spočítej sha256 tohohle souboru", "porovnej tyhle dvě verze smlouvy", "najdi v mých souborech fakturu od Alzy", "založ projekt kalkulačka hypotéky",
            "pošli dlužníkovi předžalobní výzvu", "přečti https://example.org/clanek a shrň ho", "navaž na minulou práci", "pokračujme v projektu",
            "všude používej desetinnou čárku", "co si o mně pamatuješ", "vysvětli mi rozdíl mezi úrokem a RPSN", "jaké bude zítra počasí v Brně",
            "kolik stojí nový pas", "doporuč mi knihu na dovolenou", "jak napsat výpověď z nájmu", "co je to inflace", "proč je obloha modrá",
            "pomoz mi napsat e-mail šéfovi", "shrň mi výhody a nevýhody tepelného čerpadla", "jaký je rozdíl mezi ETF a podílovým fondem",
        ).map { Case(it, NONE) } + listOf(
            Case("přidej do projektu export do CSV", PROJ), Case("spusť testy", PROJ), Case("Průměr vychází špatně, oprav to prosím", PROJ),
            Case("v tomhle projektu piš komentáře anglicky", PROJ), Case("vysvětli, co dělá funkce parse", PROJ),
            Case("ulož mi to jako tlačítko", RES), Case("datum je ve sloupci Splatnost", RES), Case("tohle je divné, proč tam je ta částka", RES),
        )
    }
}
