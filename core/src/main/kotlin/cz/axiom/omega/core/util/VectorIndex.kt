package cz.axiom.omega.core.util

import java.util.PriorityQueue

/**
 * Vektorový index v paměti: všechny vektory v jednom souvislém FloatArray (rychlé pro CPU cache)
 * a výběr top-k přes haldu – bez řazení všech výsledků a bez čtení z databáze při každém dotazu.
 * Vektory jsou L2-normalizované → skalární součin = kosinová podobnost.
 */
class VectorIndex(val dim: Int, private val ids: LongArray, private val data: FloatArray) {
    val size get() = ids.size

    fun top(query: FloatArray, k: Int, minScore: Float = Float.NEGATIVE_INFINITY): List<Pair<Long, Float>> {
        if (query.size != dim || size == 0 || k <= 0) return emptyList()
        val heap = PriorityQueue<Pair<Long, Float>>(k + 1, compareBy { it.second })
        var off = 0
        for (n in 0 until size) {
            var dot = 0f
            for (i in 0 until dim) dot += query[i] * data[off + i]
            off += dim
            if (dot < minScore) continue
            if (heap.size < k) heap.add(ids[n] to dot)
            else if (dot > heap.peek().second) { heap.poll(); heap.add(ids[n] to dot) }
        }
        return heap.sortedByDescending { it.second }
    }

    companion object {
        fun build(dim: Int, rows: List<Pair<Long, FloatArray>>): VectorIndex {
            val ok = rows.filter { it.second.size == dim }
            val data = FloatArray(ok.size * dim)
            ok.forEachIndexed { n, (_, v) -> System.arraycopy(v, 0, data, n * dim, dim) }
            return VectorIndex(dim, LongArray(ok.size) { ok[it].first }, data)
        }
    }
}
