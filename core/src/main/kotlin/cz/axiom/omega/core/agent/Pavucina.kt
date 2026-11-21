package cz.axiom.omega.core.agent

import cz.axiom.omega.core.randy.Randy
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.util.TextUtil

/**
 * NEURÁLNÍ PAVUČINA – sdílené signály jednoho dotazu napříč celým AVIORem.
 *
 * Každý modul (reflexy, E-ARL, RANDY, Supervisor, znalosti, offline knihovna, nápověda v UI) se ptá
 * na stejné věci: jak zní otázka bez diakritiky, jaké jsou její kanonické varianty, jak je těžká (RANDY),
 * jestli je časově citlivá, jestli je složená, co k ní najde knihovna. Dřív si to každý počítal sám –
 * i několikrát za jeden dotaz. Pavučina je uzel, přes který tečou: každý signál se spočítá
 * **nejvýš jednou a líně** (jen když ho někdo opravdu potřebuje) a sdílí se.
 *
 *  • [Signal] = jeden dotaz; pole jsou `lazy` → co nikdo nepotřebuje, to se nepočítá vůbec,
 *  • krátká paměť posledních dotazů (LRU 48) – nápověda při psaní, E-ARL, reflex i odpověď
 *    tak sdílí práci i mezi sebou (stejný text = stejný uzel),
 *  • [memo] pro drahé dotazy do znalostí (fulltext, vektory) s krátkou platností.
 *
 * Nic nepřidává do RAM víc než desítky kB a nic neběží na pozadí – pavučina jen odebírá práci.
 */
object Pavucina {

    class Signal internal constructor(val prompt: String) {
        val folded: String by lazy { TextUtil.fold(prompt).lowercase().replace(Regex("\\s+"), " ").trim() }
        val words: List<String> by lazy { TextUtil.words(prompt, 3) }
        val wordCount: Int by lazy { folded.split(' ').count { it.isNotBlank() } }
        /** Kanonické varianty (slang, nářečí, překlepy…) – sdílí je reflex i E-ARL. */
        val variants: List<String> by lazy { QuestionNorm.variants(prompt) }
        /** Nejvíc „učesaná“ podoba otázky (pro rozpoznání typu). */
        val canonical: String by lazy { variants.lastOrNull { it.isNotBlank() } ?: folded.trimEnd('?', '.', '!') }
        val level: RandyLevel by lazy { Randy.detect(prompt).level }
        val timeSensitive: Boolean by lazy { Reflex.timeSensitive(prompt) }
        val compound: Boolean by lazy { Reflex.isCompound(prompt) }
        val hasCode: Boolean by lazy { prompt.contains("```") || Regex("[{};]\\s*$|\\bfun |\\bdef |\\bclass |=>|\\bimport ", RegexOption.MULTILINE).containsMatchIn(prompt) }

        private val memo = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Libovolný sdílený výsledek (např. odpověď reflexu) – spočítá se jednou pro tento dotaz. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> shared(key: String, compute: () -> T?): T? {
            memo[key]?.let { return (if (it === NONE) null else it) as T? }
            val v = compute()
            memo[key] = v ?: NONE
            return v
        }
    }

    private object NONE

    private val recent = object : LinkedHashMap<String, Signal>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Signal>?) = size > 48
    }

    /** Uzel pro tento dotaz (sdílený s ostatními moduly, které se ptají na stejný text). */
    fun of(prompt: String): Signal = synchronized(recent) { recent.getOrPut(prompt) { Signal(prompt) } }

    // ------------------------------------------------------------------ paměť drahých výsledků (znalosti)

    private class Timed(val at: Long, val value: Any)
    private val cache = object : LinkedHashMap<String, Timed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed>?) = size > 64
    }

    /** Výsledek drahého výpočtu se stejným klíčem (např. hledání ve znalostech) se znovu použije po dobu [ttlMs]. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> memo(key: String, ttlMs: Long = 90_000, now: Long = System.currentTimeMillis(), compute: () -> T): T {
        synchronized(cache) { cache[key]?.takeIf { now - it.at < ttlMs }?.let { return it.value as T } }
        val v = compute()
        synchronized(cache) { cache[key] = Timed(now, v) }
        return v
    }

    /** Po změně znalostí (import, smazání) – staré výsledky hledání neplatí. */
    fun invalidateKnowledge() = synchronized(cache) { cache.keys.removeAll { it.startsWith("kb:") } }

    /** Znalostní zdroj, který sdílí výsledky mezi reflexem, E-ARL, orchestrátorem a offline odpovědí. */
    fun shared(name: String, source: KnowledgeSource): KnowledgeSource = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> = memo("kb:$name:$limit:$query") { source.search(query, limit) }
    }

    fun clear() { synchronized(recent) { recent.clear() }; synchronized(cache) { cache.clear() } }
}
