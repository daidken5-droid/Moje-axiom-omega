package cz.axiom.omega.core.rag

import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.library.LibrarySpec
import cz.axiom.omega.core.library.Prebuilt
import cz.axiom.omega.core.library.PrebuiltManifest
import cz.axiom.omega.core.library.PrebuiltPart
import cz.axiom.omega.core.llm.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * RAG INDEX offline knihovny (formát AVRAG1, viz tools/rag/merge.py): vektory významu všech článků,
 * předpočítané v CI stejným modelem jako v telefonu (OMEGA Sense · Qwen3-Embedding 0.6B),
 * zkrácené na [dim] složek (Matryoshka) a kvantované na int8 → ~140 MB pro celou českou Wikipedii.
 *
 * Soubor je namapovaný do paměti (mmap) – nic se nenačítá do haldy, systém stránky uvolní, když je potřeba.
 * Hledání = skalární součin int8 přes všechny články (≈ 137 mil. operací na 534 tis. článků).
 */
class RagIndex private constructor(private val buf: ByteBuffer, val dim: Int, val count: Int) {

    private val idsOff = 16
    private val scalesOff = idsOff + 4 * count
    private val vecsOff = scalesOff + 4 * count

    fun id(i: Int) = buf.getInt(idsOff + 4 * i)
    private fun scale(i: Int) = buf.getFloat(scalesOff + 4 * i)

    /**
     * Nejpodobnější články k dotazu. [query] = vektor dotazu z embedderu (libovolné délky ≥ dim);
     * zkrátí se na [dim], znormalizuje a kvantuje stejně jako vektory v indexu.
     * @return (rowid článku, kosinová podobnost) seřazené sestupně
     */
    fun search(query: FloatArray, k: Int, minScore: Float = 0f): List<Pair<Int, Float>> {
        if (query.size < dim || count == 0 || k <= 0) return emptyList()
        val (q, qs) = quantize(query)
        // rychlá cesta v C++ (telefon): int8 skalární součiny s NEON, ~20–50 ms na 534 tis. článků
        accel?.let { f ->
            runCatching { f(buf, dim, count, q, k) }.getOrNull()?.let { packed ->
                return packed.map { (it ushr 32).toInt() to Float.fromBits(it.toInt()) * qs }
                    .filter { it.second >= minScore }.sortedByDescending { it.second }
            }
        }
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy { it.second })
        // po blocích do ByteArray: rychlejší než ByteBuffer.get() po bajtech
        val block = 4096
        val tmp = ByteArray(block * dim)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < count) {
            val n = minOf(block, count - i)
            view.position(vecsOff + i * dim)
            view.get(tmp, 0, n * dim)
            for (j in 0 until n) {
                var dot = 0
                val off = j * dim
                for (d in 0 until dim) dot += q[d] * tmp[off + d]
                val s = dot * qs * scale(i + j)
                if (s < minScore) continue
                if (heap.size < k) heap.add(id(i + j) to s)
                else if (s > heap.peek().second) { heap.poll(); heap.add(id(i + j) to s) }
            }
            i += n
        }
        return heap.sortedByDescending { it.second }
    }

    /** Dotaz: zkrátit na dim, L2 normalizovat, int8 (symetricky). */
    fun quantize(query: FloatArray): Pair<IntArray, Float> {
        var n = 0.0
        for (d in 0 until dim) n += query[d].toDouble() * query[d]
        val norm = sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
        var amax = 0f
        for (d in 0 until dim) amax = maxOf(amax, abs(query[d] / norm))
        val s = if (amax > 0f) amax / 127f else 1f
        return IntArray(dim) { d -> (query[d] / norm / s).roundToInt().coerceIn(-127, 127) } to s
    }

    companion object {
        /**
         * Volitelná nativní akcelerace (nastaví aplikace, má-li libomega): (mmap buffer, dim, count, int8 dotaz, k)
         * → pole (rowid << 32 | bity float skóre bez měřítka dotazu), nebo null.
         */
        @Volatile var accel: ((ByteBuffer, Int, Int, IntArray, Int) -> LongArray?)? = null

        val MAGIC = "AVRAG1\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        fun open(file: File): RagIndex {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return of(map)
            }
        }

        fun of(buffer: ByteBuffer): RagIndex {
            val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            b.duplicate().get(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("Soubor není RAG index AVIOR (AVRAG1).")
            val dim = b.getInt(8)
            val count = b.getInt(12)
            require(dim in 4..4096 && count >= 0) { "Poškozená hlavička RAG indexu." }
            val need = 16L + count * 8L + count.toLong() * dim
            if (b.capacity() < need) throw IOException("RAG index je neúplný ($need B očekáváno, ${b.capacity()} B).")
            return RagIndex(b, dim, count)
        }

        /** Sestaví index v paměti (testy, malé lokální indexy). Vektory musí mít aspoň [dim] složek. */
        fun build(dim: Int, rows: List<Pair<Int, FloatArray>>): RagIndex {
            val sorted = rows.sortedBy { it.first }
            val b = ByteBuffer.allocate(16 + sorted.size * (8 + dim)).order(ByteOrder.LITTLE_ENDIAN)
            b.put(MAGIC).putInt(dim).putInt(sorted.size)
            sorted.forEach { b.putInt(it.first) }
            val tmp = RagIndex(ByteBuffer.allocate(16 + dim + 8).order(ByteOrder.LITTLE_ENDIAN), dim, 0)
            val qs = sorted.map { tmp.quantize(it.second) }
            qs.forEach { b.putFloat(it.second) }
            qs.forEach { (q, _) -> q.forEach { b.put(it.toByte()) } }
            b.rewind()
            return of(b)
        }
    }
}

/** Manifest RAG indexu z GitHub Release knihovny (rag-manifest.json, viz .github/workflows/build-rag.yml). */
data class RagManifest(
    val lib: String, val dim: Int, val count: Int, val size: Long, val sha256: String,
    val file: String, val fileSize: Long, val fileSha256: String, val model: String,
) {
    /** Stažení a rozbalení přes stejný ověřený mechanismus jako databáze knihovny (Prebuilt.install). */
    fun asPrebuilt() = PrebuiltManifest(lib, "", "sqlite-fts4-gzip", count, size, sha256, listOf(PrebuiltPart(file, fileSize, fileSha256)))

    companion object {
        fun parse(text: String): RagManifest {
            val o = Json.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: throw IllegalArgumentException("RAG manifest bez pole $k")
            require(s("format") == "avior-rag-int8") { "Neznámý formát RAG indexu." }
            val m = RagManifest(
                lib = s("lib"), dim = o["dim"]!!.jsonPrimitive.int, count = o["count"]!!.jsonPrimitive.int,
                size = o["size"]!!.jsonPrimitive.long, sha256 = s("sha256"), file = s("file"),
                fileSize = o["fileSize"]!!.jsonPrimitive.long, fileSha256 = s("fileSha256"), model = s("model"),
            )
            require(m.file.matches(Regex("[A-Za-z0-9._-]+"))) { "Neplatný název souboru indexu." }
            return m
        }

        fun url(spec: LibrarySpec, name: String) = "${Prebuilt.RELEASES}/${Prebuilt.tag(spec)}/$name"

        /** null = index pro tuto knihovnu zatím nebyl sestaven (404). */
        fun fetch(spec: LibrarySpec): RagManifest? {
            var url = url(spec, "rag-manifest.json")
            repeat(6) {
                val c = Http.open(url, connectMs = 20_000, readMs = 60_000)
                c.instanceFollowRedirects = false
                try {
                    when (val code = c.responseCode) {
                        in 300..399 -> url = java.net.URL(java.net.URL(url), c.getHeaderField("Location") ?: return null).toString()
                        200 -> return parse(c.inputStream.bufferedReader().readText())
                        404 -> return null
                        else -> throw IOException("HTTP $code při čtení RAG manifestu")
                    }
                } finally { c.disconnect() }
            }
            return null
        }
    }
}

/**
 * Hybridní výběr (Reciprocal Rank Fusion): spojí pořadí z fulltextu a z vektorů.
 * Článek, který je vysoko v obou, vyhraje; dobrý jen v jednom se dostane také, ale níž.
 */
object Fusion {
    fun rrf(lists: List<List<Snippet>>, limit: Int, k: Int = 60): List<Snippet> {
        val score = LinkedHashMap<String, Double>()
        val byKey = HashMap<String, Snippet>()
        for (l in lists) l.forEachIndexed { rank, s ->
            val key = s.title.lowercase()
            score[key] = (score[key] ?: 0.0) + 1.0 / (k + rank + 1)
            byKey.putIfAbsent(key, s)
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { byKey[it.key]!! }
    }
}
