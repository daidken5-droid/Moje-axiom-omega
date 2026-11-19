package cz.axiom.vega.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ZADÁNÍ S REVIZEMI pro běžící úlohu. Uživatel může během práce opravit vstup („nahraď prostřední soubor“), rozsah
 * („jen výdaje“) nebo dílčí předpoklad („počítej, že poplatky nejsou výdaj“). Každá oprava = nová revize.
 *
 * Závislosti: krok nad jedním souborem závisí jen na OBSAHU (otisku) toho souboru → při výměně jiného souboru se
 * výsledek znovu použije. Součet závisí na výsledcích všech souborů a na rozsahu. Shrnutí modelem závisí na všem.
 * Když závislost nejde spolehlivě určit (volný předpoklad, neznámý rozsah), zneplatní se všechny mezivýsledky
 * starší než nová revize (přepočet v širším rozsahu).
 *
 * Výsledek se smí zveřejnit jen pro AKTUÁLNÍ revizi ([isCurrent]); opožděná odpověď ke staré revizi se zahodí
 * a zapíše do záznamu běhu. Vše je v Kv → přežije restart a navazuje na stav projektu.
 */
class RevTask(private val kv: Kv, val id: String) {
    @Serializable data class Input(val name: String, val sha: String)
    @Serializable data class Rev(val n: Int, val inputs: List<Input>, val scope: String? = null, val assumptions: List<String> = emptyList(),
                                 /** Mezivýsledky spočítané v revizi < invalidBefore neplatí (přepočet v širším rozsahu). */
                                 val invalidBefore: Int = 1, val change: String = "původní zadání", val at: Long = System.currentTimeMillis())
    @Serializable data class Entry(val step: String, val input: String, val rev: Int, val data: String)
    @Serializable data class Log(val rev: Int, val step: String, val input: String, val action: String, val at: Long = System.currentTimeMillis())
    @Serializable data class State(val revs: List<Rev> = emptyList(), val cache: Map<String, Entry> = emptyMap(), val log: List<Log> = emptyList(), val published: Int = 0)

    sealed class Change {
        data class ReplaceInput(val index: Int, val input: Input) : Change()
        data class Scope(val scope: String) : Change()
        data class Assumption(val text: String) : Change()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = "task/$id.json"
    /** Jeden zámek na úlohu napříč instancemi (most z UI i výkonné vlákno) – revize se nesmí ztratit při souběhu. */
    private val lock: Any = LOCKS.computeIfAbsent(id) { Any() }
    fun state(): State = synchronized(lock) { kv.read(key)?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State() }
    private fun save(s: State) = kv.write(key, json.encodeToString(State.serializer(), s.copy(log = s.log.takeLast(500))))

    fun start(inputs: List<Input>, scope: String? = null): Rev = synchronized(lock) {
        val s = state(); val r = Rev((s.revs.lastOrNull()?.n ?: 0) + 1, inputs, scope, invalidBefore = s.revs.lastOrNull()?.invalidBefore ?: 1)
        save(s.copy(revs = s.revs + r)); r
    }
    fun current(): Rev? = state().revs.lastOrNull()
    fun isCurrent(rev: Int) = current()?.n == rev

    /** Nová revize zadání. Rozsah je známá závislost (jen součet), předpoklad neznámá → zneplatní vše starší. */
    fun revise(c: Change): Rev = synchronized(lock) {
        val s = state(); val cur = s.revs.last()
        val r = when (c) {
            is Change.ReplaceInput -> cur.copy(inputs = cur.inputs.mapIndexed { i, x -> if (i == c.index) c.input else x }, change = "vstup ${c.index + 1}: ${cur.inputs.getOrNull(c.index)?.name} → ${c.input.name}")
            is Change.Scope -> if (knownScope(c.scope) != null) cur.copy(scope = c.scope, change = "rozsah: ${c.scope}")
                               else cur.copy(scope = c.scope, invalidBefore = cur.n + 1, change = "rozsah (neznámá závislost → přepočet všeho): ${c.scope}")
            is Change.Assumption -> cur.copy(assumptions = cur.assumptions + c.text, invalidBefore = cur.n + 1, change = "předpoklad (závislost nejde určit → přepočet všeho): ${c.text}")
        }.copy(n = cur.n + 1, at = System.currentTimeMillis())
        save(s.copy(revs = s.revs + r)); r
    }

    /** Platný mezivýsledek kroku nad vstupem (podle otisku) – nebo null, když se musí spočítat. */
    fun cached(step: String, input: String, rev: Rev): Entry? = state().cache["$step|$input"]?.takeIf { it.rev >= rev.invalidBefore }
    fun put(step: String, input: String, rev: Rev, data: String) = synchronized(lock) { val s = state(); save(s.copy(cache = s.cache + ("$step|$input" to Entry(step, input, rev.n, data)))) }
    fun log(rev: Int, step: String, input: String, action: String) = synchronized(lock) { val s = state(); save(s.copy(log = s.log + Log(rev, step, input, action))) }

    /** Zveřejnit výsledek jen pro aktuální revizi a nikdy starší přes novější. */
    fun publish(rev: Int): Boolean = synchronized(lock) {
        val s = state(); if (s.revs.lastOrNull()?.n != rev || rev <= s.published) return false
        save(s.copy(published = rev)); true
    }
    /** Je zveřejněná aktuální revize? (false = čeká revize, kterou ještě nikdo nepřepočítal) */
    fun upToDate(): Boolean = state().let { it.published == (it.revs.lastOrNull()?.n ?: 0) }

    companion object {
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
        /** Rozsahy, jejichž dopad je známý (mění jen součet, ne zpracování souborů). */
        fun knownScope(s: String): ((List<String>) -> Boolean)? {
            val f = cz.axiom.omega.core.util.TextUtil.fold(s).lowercase()
            return when {
                Regex("jen (prijm|prichoz)").containsMatchIn(f) -> { r -> (r[1].toDoubleOrNull() ?: 0.0) > 0 }
                Regex("jen (vydaj|odchoz|platb)").containsMatchIn(f) -> { r -> (r[1].toDoubleOrNull() ?: 0.0) < 0 }
                Regex("bez poplatk").containsMatchIn(f) -> { r -> !Regex("(?i)poplat").containsMatchIn(r.getOrElse(3) { "" }) }
                Regex("vse|cely|vsechno|bez omezeni").containsMatchIn(f) -> { _ -> true }
                else -> null
            }
        }
    }
}
