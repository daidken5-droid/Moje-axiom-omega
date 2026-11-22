package cz.axiom.vega.core

import cz.axiom.vega.base.Text

/**
 * Offline RAG: rozdělí dokumenty na pasáže, skóruje je proti dotazu (BM25) a vrací nejrelevantnější úseky
 * se zdrojem a pozicí pro citaci. Bez sítě, bez modelu – čistě lexikálně, s odolností vůči diakritice (fold).
 * Slouží k odpovědím nad offline databázemi (znalostní balíčky, importované dokumenty).
 */
object Rag {
    data class Passage(val source: String, val start: Int, val text: String)
    data class Hit(val passage: Passage, val score: Double)

    /** Česká stop-slova (bez diakritiky): „do kdy platím nájem“ nesmí najít recept přes „do“ („do zeleniny“). */
    private val STOP = setOf("a", "i", "v", "ve", "do", "na", "za", "z", "ze", "o", "od", "po", "pro", "k", "ke", "s", "se", "si", "je", "jsou", "byl", "byla", "bylo",
        "to", "ten", "ta", "ty", "tu", "co", "kdo", "kdy", "jak", "kde", "proc", "kolik", "mam", "mas", "ma", "jsem", "jsi", "mi", "me", "mne", "muj", "moje", "tam", "tady",
        "ale", "nebo", "ze", "aby", "by", "uz", "jeste", "jen", "taky", "take", "the", "and", "of", "to", "in", "is", "nejak", "neco", "vsechno", "vse", "pri", "u")
    /** Lehký český „stem“: prvních 5 znaků slova (faktura/fakturu, svíčková/svíčkovou, recept/receptu se potkají). */
    private fun tokens(s: String): List<String> = Text.words(Text.fold(s).lowercase(), 2).filter { it !in STOP }.map { if (it.length > 5) it.take(5) else it }

    /** Rozdělení na pasáže ~[size] znaků s překryvem [overlap], přednostně na konci věty/odstavce. */
    fun chunk(source: String, text: String, size: Int = 700, overlap: Int = 140): List<Passage> {
        val t = text.trim(); if (t.isEmpty()) return emptyList()
        if (t.length <= size) return listOf(Passage(source, 0, t))
        val out = ArrayList<Passage>(); var i = 0
        while (i < t.length) {
            var end = minOf(i + size, t.length)
            if (end < t.length) { // posuň na nejbližší hranici věty/mezery vzad
                val win = t.substring(i, end)
                val br = maxOf(win.lastIndexOf(". "), win.lastIndexOf("\n"), win.lastIndexOf("! "), win.lastIndexOf("? "))
                if (br > size / 2) end = i + br + 1
            }
            out += Passage(source, i, t.substring(i, end).trim())
            if (end >= t.length) break
            i = maxOf(end - overlap, i + 1)
        }
        return out
    }

    class Index(val passages: List<Passage>) {
        // i název dokumentu („recept.txt“, „faktura.pdf“) – laik se ptá „co je v receptu / na faktuře“
        private val tf = passages.map { p -> (tokens(p.text) + tokens(p.source.substringBeforeLast('.'))).groupingBy { it }.eachCount() }
        private val len = tf.map { it.values.sum().toDouble() }
        private val avg = len.average().takeIf { !it.isNaN() } ?: 0.0
        private val df = HashMap<String, Int>().apply { tf.forEach { m -> m.keys.forEach { merge(it, 1, Int::plus) } } }
        private val n = passages.size

        /** BM25 (k1=1.5, b=0.75); vrací top [k] pasáží se skóre > 0. */
        fun query(q: String, k: Int = 5): List<Hit> {
            val qt = tokens(q).distinct(); if (qt.isEmpty() || n == 0) return emptyList()
            val k1 = 1.5; val b = 0.75
            val hits = (0 until n).map { i ->
                var s = 0.0
                for (term in qt) {
                    val f = tf[i][term] ?: continue; val dfi = df[term] ?: continue
                    val idf = Math.log(1.0 + (n - dfi + 0.5) / (dfi + 0.5))
                    s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * (len[i] / (if (avg > 0) avg else 1.0))))
                }
                Hit(passages[i], s)
            }
            return hits.filter { it.score > 0 }.sortedByDescending { it.score }.take(k)
        }
    }

    fun build(docs: Map<String, String>, size: Int = 700, overlap: Int = 140): Index =
        Index(docs.entries.flatMap { (name, text) -> chunk(name, text, size, overlap) })

    /** Pohotové sestavení kontextu pro model: očíslované pasáže se zdrojem (pro citace [n]). */
    fun contextBlock(hits: List<Hit>, maxChars: Int = 4000): String {
        val sb = StringBuilder(); var n = 0
        for (h in hits) {
            val piece = "[${++n}] (${h.passage.source}) ${h.passage.text.replace("\n", " ").trim()}\n"
            if (sb.length + piece.length > maxChars) break
            sb.append(piece)
        }
        return sb.toString().trim()
    }
}
