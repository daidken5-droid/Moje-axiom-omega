package cz.axiom.vega.core

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * OVĚŘOVAČ OPORY VE ZDROJI: pro dvojici (atomické české tvrzení, původní pasáž cs/en) řekne PODPORUJE / ODPORUJE /
 * NEDOSTAČUJE – o obsahu TÉTO pasáže, ne o pravdivosti světa. Provozní stav NEVYHODNOCENO = chybí váhy, poškozený
 * balíček, chyba výpočtu nebo se rozhodující kontext nevešel do okna modelu. Původní chat funguje i bez balíčku.
 * Model: vícejazyčný cross-encoder (pasáž = premisa, tvrzení = hypotéza) v ONNX; tokenizer XLM-R Unigram napsaný
 * zde (shoda s originálem se ověřuje testovacími vektory z exportu).
 */
object Grounding {
    enum class Label(val cz: String) { PODPORUJE("podporuje"), NEDOSTACUJE("nedostačuje"), ODPORUJE("odporuje"), NEVYHODNOCENO("nevyhodnoceno") }

    data class Verdict(val label: Label, val probs: FloatArray? = null, val reason: String = "", val windowStart: Int = 0, val windowEnd: Int = 0) {
        val support get() = probs?.getOrNull(0) ?: 0f
    }

    /** Atomické tvrzení s citacemi [n]; souvětí s příčinou se rozdělí (datum/událost × motiv se hodnotí zvlášť). */
    data class Claim(val text: String, val cites: List<Int>, val kind: String = "tvrzení")

    private val causal = Regex("(?i),?\\s+(protože|neboť|jelikož|aby|kvůli tomu, že|kvůli|z důvodu|za účelem)\\s+")
    private val ABBR = setOf("např", "tzv", "tj", "č", "str", "resp", "mj", "apod", "atd", "aj", "př", "sv", "ing", "mgr", "dr", "prof", "p", "s", "r", "o", "vč", "cca", "max", "min", "mil", "mld", "tis", "kč", "e", "g", "i", "vs", "jr", "st", "no", "fig", "etc")

    /** Délka „ [12]“ začínajícího přesně na [j] (mezery + 1–2 číslice v hranatých závorkách), jinak 0. */
    private fun citeAt(t: String, j: Int): Int {
        var k = j; while (k < t.length && t[k].isWhitespace() && t[k] != '\n') k++
        if (k >= t.length || t[k] != '[') return 0
        var d = k + 1; while (d < t.length && d - k <= 2 && t[d].isDigit()) d++
        return if (d > k + 1 && d < t.length && t[d] == ']') d + 1 - j else 0
    }
    /** Věty: konec jen na . ! ? následované velkým písmenem / koncem; citace [n] hned za tečkou patří k předchozí větě. */
    fun sentences(text: String): List<String> {
        val t = text.replace(Regex("\\*\\*|__|`"), "")
        val out = ArrayList<String>(); var start = 0; var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch == '\n') { if (i > start) out += t.substring(start, i); start = i + 1; i++; continue }
            if (ch == '.' || ch == '!' || ch == '?') {
                var j = i + 1
                while (true) { val n = citeAt(t, j); if (n == 0) break; j += n }  // [n] za tečkou → patří sem (kontrola jen na místě, lineárně)
                var k = j; while (k < t.length && t[k] == ' ') k++
                var w0 = i; while (w0 > start && t[w0 - 1].isLetterOrDigit()) w0--
                val word = t.substring(w0, i)
                val nextUpper = k >= t.length || t[k] == '\n' || t[k].isUpperCase() || t[k] == '„' || t[k] == '"'
                val domain = ch == '.' && k == i + 1 && i + 1 < t.length && t[i + 1].isLetterOrDigit()
                val ordinal = ch == '.' && word.all { it.isDigit() } && word.isNotEmpty() && !(k < t.length && t[k].isUpperCase() && word.length > 2)
                val abbr = ch == '.' && word.lowercase() in ABBR
                if (nextUpper && !domain && !ordinal && !abbr) { out += t.substring(start, j); start = k; i = k; continue }
            }
            i++
        }
        if (start < t.length) out += t.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun claims(answer: String): List<Claim> {
        val out = ArrayList<Claim>()
        sentences(answer).forEach { s ->
            val cites = Regex("\\[(\\d{1,2})]").findAll(s).map { it.groupValues[1].toInt() }.distinct().toList()
            if (cites.isEmpty() || s.length < 12) return@forEach
            val clean = s.replace(Regex("\\s*\\[\\d{1,2}]"), "").trim()
            val c = causal.find(clean)
            if (c != null && c.range.first > 8) {
                out += Claim(clean.substring(0, c.range.first).trim().trimEnd(',') + ".", cites, "událost")
                out += Claim(clean.trimEnd('.') + ".", cites, "motiv / důvod")
            } else out += Claim(clean, cites)
        }
        return out
    }

    interface Checker { val ready: Boolean; val status: String; fun check(claim: String, passage: String): Verdict }
    /** Bez balíčku: vše NEVYHODNOCENO s důvodem, chat beze změny. */
    class Missing(override val status: String) : Checker {
        override val ready = false
        override fun check(claim: String, passage: String) = Verdict(Label.NEVYHODNOCENO, reason = status)
    }
}

/** XLM-RoBERTa Unigram (SentencePiece) tokenizer: NFKC, Metaspace „▁“, Viterbi nad skóre kusů, sloučené <unk>. */
class UnigramTokenizer(lines: Sequence<String>) {
    private val ids = HashMap<String, Int>(300_000); private val scores: FloatArray; private val maxLen: Int; private val unkScore: Float
    init {
        val sc = ArrayList<Float>(260_000); var ml = 1; var i = 0
        lines.forEach { l -> val tab = l.lastIndexOf('\t'); val p = l.substring(0, tab); ids.putIfAbsent(p, i); sc += l.substring(tab + 1).toFloat(); if (p.length > ml) ml = p.length; i++ }
        scores = sc.toFloatArray(); maxLen = minOf(ml, 48); unkScore = (sc.minOrNull() ?: 0f) - 10f
    }
    val size get() = scores.size
    companion object { const val BOS = 0; const val PAD = 1; const val EOS = 2; const val UNK = 3 }

    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("[\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F\\u200B\\uFEFF]"), "").trim()

    fun tokenize(text: String): IntArray {
        val out = ArrayList<Int>()
        normalize(text).split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { w -> viterbi("▁$w", out) }
        return out.toIntArray()
    }

    private fun viterbi(s: String, out: MutableList<Int>) {
        val n = s.length; val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }; val from = IntArray(n + 1); val tok = IntArray(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            for (l in 1..minOf(maxLen, n - i)) {
                val id = ids[s.substring(i, i + l)] ?: continue
                val v = best[i] + scores[id]
                if (v > best[i + l]) { best[i + l] = v; from[i + l] = i; tok[i + l] = id }
            }
            // znak bez kusu ve slovníku → <unk> (penalizovaný), jako v originálu
            val cl = if (Character.isHighSurrogate(s[i]) && i + 1 < n) 2 else 1
            val v = best[i] + unkScore
            if (v > best[i + cl] && ids[s.substring(i, i + cl)] == null) { best[i + cl] = v; from[i + cl] = i; tok[i + cl] = UNK }
        }
        val seq = ArrayList<Int>(); var j = n
        while (j > 0) { seq += tok[j]; j = from[j] }
        seq.reverse()
        for (t in seq) if (!(t == UNK && out.lastOrNull() == UNK)) out += t // sloučené <unk>
    }

    /** <s> A </s></s> B </s>; zkracuje se jen pasáž A. Vrací (ids, zkráceno). */
    fun encodePair(a: String, b: String, maxLen: Int): Pair<IntArray, Boolean> {
        val ta = tokenize(a); val tb = tokenize(b)
        val room = maxLen - 4 - tb.size
        val cut = ta.size > room
        val aa = if (cut) ta.copyOf(maxOf(0, room)) else ta
        return (intArrayOf(BOS) + aa + intArrayOf(EOS, EOS) + tb + intArrayOf(EOS)) to cut
    }
}

/**
 * Balíček vah: manifest.json (formát, pořadí tříd, práh, otisky souborů), model.onnx, vocab.tsv.
 * Ověření otisků při načtení – poškozený balíček = NEVYHODNOCENO s důvodem, nikdy pád.
 */
object VerifierBundle {
    data class Manifest(val name: String, val labels: List<String>, val maxLen: Int, val threshold: Float, val files: Map<String, String>, val baseRepo: String, val baseRev: String)

    fun sha256(f: File): String { val md = MessageDigest.getInstance("SHA-256"); f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }; return md.digest().joinToString("") { "%02x".format(it) } }

    fun read(dir: File, verifyHashes: Boolean = true): Manifest {
        val mf = File(dir, "manifest.json"); require(mf.isFile) { "Balíček ověřovače není nainstalovaný." }
        val j = kotlinx.serialization.json.Json.parseToJsonElement(mf.readText()) as kotlinx.serialization.json.JsonObject
        fun s(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw IllegalArgumentException("manifest: chybí $k")
        require(s("format") == "vega-verifier/1") { "Neznámý formát balíčku: ${s("format")}" }
        val labels = (j["labels"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        require(labels == listOf("PODPORUJE", "NEDOSTAČUJE", "ODPORUJE")) { "Neočekávané pořadí tříd: $labels" }
        val files = (j["files"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
        require(files.keys.containsAll(listOf("model.onnx", "vocab.tsv"))) { "Balíčku chybí soubory." }
        if (verifyHashes) files.forEach { (f, h) -> val p = File(dir, f); require(p.isFile) { "Chybí $f." }; require(sha256(p) == h) { "Soubor $f je poškozený (otisk nesedí)." } }
        val th = s("threshold").toFloat(); val ml = s("max_len").toInt()
        // práh i délka okna pocházejí z balíčku (ten si otisky ručí sám) → jen rozumný rozsah, jinak odmítnout
        require(th.isFinite() && th in 0.5f..0.99f) { "Práh v balíčku mimo rozsah 0,5–0,99: $th" }
        require(ml in 32..1024) { "Délka okna v balíčku mimo rozsah: $ml" }
        return Manifest(s("name"), labels, ml, th, files, s("base_repo"), s("base_rev"))
    }
}

/** Ověřovač nad ONNX Runtime (na Androidu onnxruntime-android, v testech na hostiteli onnxruntime – stejné API). */
class OnnxGrounding(private val dir: File) : Grounding.Checker, java.io.Closeable {
    override fun close() { runCatching { session?.close() }; session = null }
    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var session: ai.onnxruntime.OrtSession? = null
    private var tok: UnigramTokenizer? = null
    var manifest: VerifierBundle.Manifest? = null; private set
    override var status: String = "nenačteno"; private set
    override val ready get() = session != null
    val loadMillis: Long

    init {
        val t0 = System.currentTimeMillis()
        try {
            val m = VerifierBundle.read(dir)
            tok = File(dir, "vocab.tsv").useLines { UnigramTokenizer(it) } // soubor se po načtení zavře
            env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            session = env!!.createSession(File(dir, "model.onnx").absolutePath, ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            manifest = m; status = "${m.name} (${m.baseRepo}@${m.baseRev.take(7)}), práh ${m.threshold}"
        } catch (e: Throwable) { session = null; status = "NEVYHODNOCENO – ${e.message ?: e.javaClass.simpleName}" }
        loadMillis = System.currentTimeMillis() - t0
    }

    /** Okno pasáže: celé, když se vejde; jinak věty s největším překryvem s tvrzením. Klíčové údaje tvrzení musí zůstat. */
    private data class Win(val text: String, val start: Int, val end: Int, val droppedRisk: Boolean = false)
    private val NEG = Regex("(?<!\\p{L})(ne\\p{L}*|nikdy|zadn\\p{L}*|popřel\\p{L}*|popiral\\p{L}*|vyvrátil\\p{L}*|vyvratil\\p{L}*|not|never|no|denied|false)(?!\\p{L})", RegexOption.IGNORE_CASE)
    private fun window(claim: String, passage: String, maxTok: Int): Win {
        val t = tok!!; val budget = maxTok - 4 - t.tokenize(claim).size
        if (t.tokenize(passage).size <= budget) return Win(passage, 0, passage.length)
        val sents = Regex("[^.!?]+[.!?]?").findAll(passage).toList()
        val cw = words(claim)
        val ranked = sents.indices.sortedByDescending { i -> words(sents[i].value).intersect(cw).size }
        val pick = sortedSetOf<Int>(); var used = 0
        for (i in ranked) { val n = t.tokenize(sents[i].value).size; if (used + n > budget) continue; pick += i; used += n }
        if (pick.isEmpty()) return Win(passage, 0, passage.length)
        val s = sents[pick.first()].range.first; val e = sents[pick.last()].range.last + 1
        // vynechaná věta s negací a se slovy tvrzení může výrok popírat → bez ní nelze poctivě říct PODPORUJE
        val risk = sents.indices.any { it !in pick && NEG.containsMatchIn(sents[it].value) && words(sents[it].value).intersect(cw).size >= 2 }
        return Win(pick.joinToString(" ") { sents[it].value.trim() }, s, e, risk)
    }
    private fun words(s: String) = Regex("[\\p{L}\\d]{3,}").findAll(s.lowercase()).map { it.value.take(5) }.toSet()
    /** Údaje, bez kterých nejde rozhodnout: čísla a vlastní jména (velké písmeno uprostřed věty). */
    private fun keyFacts(s: String) = (Regex("\\d+").findAll(s).map { it.value } + Regex("(?<=\\s)\\p{Lu}\\p{L}{3,}").findAll(s).map { it.value.lowercase().take(5) }).toSet()

    override fun check(claim: String, passage: String): Grounding.Verdict {
        val s = session ?: return Grounding.Verdict(Grounding.Label.NEVYHODNOCENO, reason = status)
        val m = manifest!!
        return try {
            val wv = window(claim, passage, m.maxLen); val win = wv.text; val ws = wv.start; val we = wv.end
            val (ids, cut) = tok!!.encodePair(win, claim, m.maxLen)
            if (wv.droppedRisk) return Grounding.Verdict(Grounding.Label.NEVYHODNOCENO, reason = "mimo okno modelu zůstala věta, která tvrzení může popírat", windowStart = ws, windowEnd = we)
            if (cut || win.length < passage.length && keyFacts(passage).let { kf -> keyFacts(claim).any { f -> f in kf && !win.lowercase().contains(f) } })
                return Grounding.Verdict(Grounding.Label.NEVYHODNOCENO, reason = "rozhodující kontext se nevešel do okna modelu (${m.maxLen} tokenů)", windowStart = ws, windowEnd = we)
            val shape = longArrayOf(1, ids.size.toLong())
            val inIds = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), shape)
            val inMask = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
            val logits = inIds.use { a -> inMask.use { b -> s.run(mapOf("input_ids" to a, "attention_mask" to b)).use { r -> (r[0].value as? Array<*>)?.firstOrNull() as? FloatArray ?: throw IllegalStateException("neočekávaný výstup modelu") } } }
            val mx = logits.max(); val ex = logits.map { kotlin.math.exp((it - mx).toDouble()) }; val sum = ex.sum()
            val p = FloatArray(3) { (ex[it] / sum).toFloat() }
            val label = when { p[0] >= m.threshold -> Grounding.Label.PODPORUJE; p[1] >= p[2] -> Grounding.Label.NEDOSTACUJE; else -> Grounding.Label.ODPORUJE }
            Grounding.Verdict(label, p, windowStart = ws, windowEnd = we)
        } catch (e: Throwable) { Grounding.Verdict(Grounding.Label.NEVYHODNOCENO, reason = "chyba výpočtu: ${e.message ?: e.javaClass.simpleName}") }
    }
}
