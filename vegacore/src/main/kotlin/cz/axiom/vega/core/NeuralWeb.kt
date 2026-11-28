package cz.axiom.vega.core

/**
 * Neurální pavučina: asociativní graf propojující PAMĚTI, TÉMATA a NÁSTROJE/MODULY. Hrany se posilují společným
 * výskytem v jednom tahu a časem slábnou. Při dotazu „rozsvítí“ (spreading activation) související paměti a nástroje,
 * takže VEGA nepřipomíná jen nejnovější vzpomínky, ale i věcně související. Čistý Kotlin nad Kv, testovatelné, offline.
 *
 * Uzly: `topic:<slovo>`, `mem:<id>`, `tool:<id>`. Hrany neorientované (klíč "a\u0001b", a<b).
 */
class NeuralWeb(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        fun topic(w: String) = "topic:$w"
        fun mem(id: String) = "mem:$id"
        fun tool(id: String) = "tool:$id"
        private const val MAX_EDGES = 4000
        private const val HALF_LIFE_MS = 45L * 24 * 3600_000 // ~45 dní
    }

    private val lock = Any()
    private data class G(val w: HashMap<String, Double>, var at: Long)

    // formát (prostý text, ať nezávisí na org.json): 1. řádek = čas; další řádky „klíč\tváha“ (klíč nemá \t ani \n)
    private fun load(): G = synchronized(lock) {
        val raw = kv.read("web/graph.tsv") ?: return G(HashMap(), clock())
        return runCatching {
            val lines = raw.split('\n'); val at = lines.firstOrNull()?.toLongOrNull() ?: clock(); val m = HashMap<String, Double>()
            lines.drop(1).forEach { l -> val t = l.lastIndexOf('\t'); if (t > 0) l.substring(t + 1).toDoubleOrNull()?.let { m[l.substring(0, t)] = it } }
            G(m, at)
        }.getOrDefault(G(HashMap(), clock()))
    }

    private fun save(g: G) {
        kv.write("web/graph.tsv", buildString { append(g.at); g.w.forEach { (k, v) -> append('\n').append(k).append('\t').append(v) } })
    }

    private fun key(a: String, b: String) = if (a <= b) "$a\u0001$b" else "$b\u0001$a"

    /** Globální útlum podle času od posledního zápisu (polovina za HALF_LIFE); hrany pod prahem zmizí. */
    private fun decay(g: G) {
        val dt = clock() - g.at; if (dt <= 0) return
        val f = Math.pow(0.5, dt.toDouble() / HALF_LIFE_MS)
        if (f >= 0.999) return
        val it = g.w.entries.iterator()
        while (it.hasNext()) { val en = it.next(); val v = en.value * f; if (v < 0.05) it.remove() else en.setValue(v) }
        g.at = clock()
    }

    /** Propojí všechny uzly tahu (téma+téma, téma+nástroj, téma+paměť). [strength] = jak silně. */
    fun observe(nodes: List<String>, strength: Double = 1.0) {
        val ns = nodes.distinct().filter { it.isNotBlank() }; if (ns.size < 2) return
        synchronized(lock) {
            val g = load(); decay(g)
            for (i in ns.indices) for (j in i + 1 until ns.size) g.w.merge(key(ns[i], ns[j]), strength) { a, b -> minOf(a + b, 50.0) }
            if (g.w.size > MAX_EDGES) { val cut = g.w.values.sorted()[g.w.size - MAX_EDGES]; g.w.entries.removeIf { it.value < cut } }
            save(g)
        }
    }

    private fun neighbors(g: G, node: String): List<Pair<String, Double>> = g.w.entries.mapNotNull { (k, v) ->
        val p = k.split('\u0001'); when (node) { p[0] -> p[1] to v; p.getOrNull(1) -> p[0] to v; else -> null }
    }

    /**
     * Rozsvícení: ze [seeds] rozšíří aktivaci do [hops] skoků (tlumeně) a vrátí top [k] souvisejících uzlů
     * (bez samotných seedů). Prefix uzlu říká typ (topic:/mem:/tool:).
     */
    fun related(seeds: List<String>, k: Int = 8, hops: Int = 2, decayPerHop: Double = 0.5): List<Pair<String, Double>> {
        val g = synchronized(lock) { load().also { decay(it); save(it) } }
        if (g.w.isEmpty()) return emptyList()
        val score = HashMap<String, Double>()
        var front = seeds.associateWith { 1.0 }
        val seedSet = seeds.toSet()
        repeat(hops) {
            val next = HashMap<String, Double>()
            for ((node, act) in front) {
                val nb = neighbors(g, node); val tot = nb.sumOf { it.second }
                if (tot <= 0) continue // uzel bez sousedů přeskoč, neukončuj celý skok
                for ((n, w) in nb) { val add = act * decayPerHop * (w / tot); if (n !in seedSet) { score.merge(n, add, Double::plus); next.merge(n, add, Double::plus) } }
            }
            front = next
        }
        return score.entries.sortedByDescending { it.value }.take(k).map { it.key to it.value }
    }

    /** Související PAMĚTI (jen uzly mem:) k zadaným tématům/nástrojům. */
    fun relatedMemories(seeds: List<String>, k: Int = 5): List<String> =
        related(seeds, k = k * 3).filter { it.first.startsWith("mem:") }.take(k).map { it.first.removePrefix("mem:") }

    /** Související NÁSTROJE (uzly tool:). */
    fun relatedTools(seeds: List<String>, k: Int = 3): List<String> =
        related(seeds, k = k * 3).filter { it.first.startsWith("tool:") }.take(k).map { it.first.removePrefix("tool:") }
}
