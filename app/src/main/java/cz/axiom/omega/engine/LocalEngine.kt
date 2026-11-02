package cz.axiom.omega.engine

import android.content.Context
import cz.axiom.omega.BuildConfig
import cz.axiom.omega.core.brain.BrainSpec
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.CancelledException
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.Delta
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.GenResult
import cz.axiom.omega.core.llm.LlmEngine
import cz.axiom.omega.core.llm.Role
import cz.axiom.omega.core.llm.ThinkSplitter
import java.io.File

/** Callback z C++: kus UTF-8 (vždy celé znaky). Vrátí false = zastavit. */
fun interface TokenSink {
    fun onBytes(bytes: ByteArray): Boolean
}

/** JNI rozhraní k libomega.so (llama.cpp). */
object NativeLlama {
    @Volatile private var loaded: Boolean? = null
    var loadError: String? = null
        private set

    fun available(context: Context): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = BuildConfig.WITH_NATIVE && runCatching {
                System.loadLibrary("omega")
                nativeInit(context.applicationInfo.nativeLibraryDir)
            }.onFailure { loadError = it.message }.isSuccess
            loaded = ok
            return ok
        }
    }

    external fun nativeInit(libDir: String)
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeInfo(handle: Long): String
    external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int,
        temp: Float, topP: Float, minP: Float, repeatPenalty: Float, thinking: Boolean, sink: TokenSink,
    ): Int
    external fun nativeCancel(handle: Long)
    external fun nativeStats(handle: Long): String
    external fun nativeFree(handle: Long)
    external fun nativeSetLora(handle: Long, path: String, scale: Float): String
    external fun nativeLoadEmbedder(path: String, nThreads: Int): Long
    external fun nativeEmbed(handle: Long, text: String): FloatArray?
}

/** Sémantický embedder (rozšíření OMEGA Sense). Vrací L2-normalizované vektory. */
object Embedder {
    @Volatile private var handle = 0L
    @Volatile private var file: File? = null

    @Synchronized
    fun open(context: Context, f: File, nThreads: Int = 0): Boolean {
        if (handle != 0L && file == f) return true
        close()
        if (!NativeLlama.available(context) || !f.exists()) return false
        val threads = if (nThreads > 0) nThreads else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        handle = NativeLlama.nativeLoadEmbedder(f.absolutePath, threads)
        file = f
        return handle != 0L
    }

    fun ready() = handle != 0L

    fun embed(text: String): FloatArray? {
        val h = handle
        if (h == 0L) return null
        val v = NativeLlama.nativeEmbed(h, text) ?: return null
        var n = 0.0
        for (x in v) n += x * x
        val norm = kotlin.math.sqrt(n).toFloat().takeIf { it > 0f } ?: return null
        for (i in v.indices) v[i] /= norm
        return v
    }

    /** Qwen3-Embedding: dotazy dostávají instrukci, dokumenty ne. */
    fun embedQuery(q: String) = embed("Instruct: Given a question, retrieve passages that answer it\nQuery: $q")

    @Synchronized
    fun close() {
        if (handle != 0L) NativeLlama.nativeFree(handle)
        handle = 0L
        file = null
    }
}

/**
 * Lokální mozek v telefonu. Model zůstává načtený mezi dotazy (mmap), uvolní se při změně
 * mozku nebo když systém hlásí nedostatek paměti.
 */
class LocalEngine private constructor(
    private val handle: Long,
    val spec: BrainSpec?,
    val file: File,
    val ctxTokens: Int,
    /** kontext, o který se žádalo (skutečný může být menší kvůli RAM) – klíč pro znovupoužití */
    private val requestedCtx: Int = ctxTokens,
) : LlmEngine {

    override val id = "local:${file.name}"
    override val label get() = (spec?.name ?: file.nameWithoutExtension) + (loraLabel?.let { " + $it" } ?: "")
    override val kind = EngineKind.LOCAL
    /** čeština ≈ 2,5 znaku/token; část kontextu nechat na odpověď */
    override val contextBudgetChars = (ctxTokens * 2.5 * 0.6).toInt()

    val info: String get() = runCatching { NativeLlama.nativeInfo(handle) }.getOrDefault("")

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val started = System.currentTimeMillis()
        cancel.onCancel { NativeLlama.nativeCancel(handle) }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val splitter = ThinkSplitter { d ->
            if (d.reasoning) reasoning.append(d.text) else text.append(d.text)
            onDelta(d)
        }
        val roles = messages.map { when (it.role) { Role.SYSTEM -> "system"; Role.USER -> "user"; Role.ASSISTANT -> "assistant" } }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        val rc = NativeLlama.nativeGenerate(
            handle, roles, contents, options.maxTokens,
            temp = if (options.temperature != 0.6f) options.temperature else if (options.effort == "low") 0.4f else 0.6f, topP = 0.95f, minP = 0.05f, repeatPenalty = 1.08f,
            thinking = options.thinking && spec?.thinking != false,
        ) { bytes ->
            splitter.feed(String(bytes, Charsets.UTF_8))
            !cancel.cancelled
        }
        splitter.finish()
        when (rc) {
            0, 2 -> Unit
            1 -> throw CancelledException()
            -2 -> throw EngineException("Lokální mozek: chyba šablony chatu modelu.")
            -3 -> throw EngineException("Lokální mozek: zadání je příliš dlouhé pro kontext ${ctxTokens} tokenů. Zkrať ho nebo zvětši kontext v Systému.")
            else -> throw EngineException("Lokální mozek: chyba výpočtu (kód $rc).")
        }
        var answer = text.toString().trim()
        if (answer.isBlank() && reasoning.isNotBlank()) {
            // model skončil uvnitř <think> (malý limit tokenů) – vrať aspoň úvahu
            answer = reasoning.toString().trim()
        }
        if (answer.isBlank()) throw EngineException("Lokální mozek nevrátil text. Zkus to znovu nebo vypni přemýšlení.")
        if (rc == 2) answer += "\n\n[Zkráceno limitem délky lokálního mozku.]"
        val stats = runCatching { NativeLlama.nativeStats(handle).split('|') }.getOrDefault(emptyList())
        return GenResult(
            text = answer,
            reasoning = reasoning.toString().trim(),
            model = "$label · v telefonu",
            stopReason = if (rc == 2) "length" else "stop",
            inputTokens = stats.getOrNull(0)?.toLongOrNull(),
            outputTokens = stats.getOrNull(2)?.toLongOrNull(),
            millis = System.currentTimeMillis() - started,
        )
    }

    @Volatile private var loraKey = ""
    /** Popis nasazeného LoRA adaptéru (doladění), nebo null. */
    @Volatile var loraLabel: String? = null
        private set

    /** Nasadí/odebere LoRA adaptér; při chybě adaptér vypne a ohlásí důvod. */
    fun applyLora(file: File?, scale: Float) {
        val key = if (file != null && file.exists()) "${file.path}@$scale" else ""
        if (key == loraKey) return
        val err = runCatching { NativeLlama.nativeSetLora(handle, if (key.isEmpty()) "" else file!!.path, scale) }.getOrElse { it.message ?: "chyba" }
        if (err.isEmpty()) {
            loraKey = key
            loraLabel = if (key.isEmpty()) null else file!!.nameWithoutExtension
        } else {
            runCatching { NativeLlama.nativeSetLora(handle, "", 0f) }
            loraKey = ""; loraLabel = null
            cz.axiom.omega.Events.note("Doladění (LoRA) se nepodařilo nasadit: $err")
        }
    }

    /** Výkon posledního běhu: čtení (prefill) a psaní v tokenech/s, kolik se ušetřilo z cache. */
    fun perf(): org.json.JSONObject? = runCatching {
        val p = NativeLlama.nativeStats(handle).split('|')
        val prompt = p[0].toInt(); val reused = p[1].toInt(); val gen = p[2].toInt()
        val pms = p[3].toDouble(); val gms = p[4].toDouble()
        org.json.JSONObject().put("prompt", prompt).put("reused", reused).put("gen", gen)
            .put("readTps", if (pms > 0) (prompt - reused).coerceAtLeast(0) * 1000.0 / pms else 0.0)
            .put("writeTps", if (gms > 0) gen * 1000.0 / gms else 0.0)
    }.getOrNull()

    fun stats(): String = runCatching { NativeLlama.nativeStats(handle) }.getOrDefault("")

    fun close() = NativeLlama.nativeFree(handle)

    companion object {
        @Volatile private var current: LocalEngine? = null

        @Synchronized
        fun obtain(context: Context, file: File, spec: BrainSpec?, ctxTokens: Int, lora: File? = null, loraScale: Float = 1f, nThreads: Int = 0): LocalEngine {
            current?.let { if (it.file == file && it.requestedCtx == ctxTokens) { it.applyLora(lora, loraScale); return it } }
            release()
            if (!NativeLlama.available(context)) {
                throw EngineException("Lokální mozek není v tomto sestavení dostupný (${NativeLlama.loadError ?: "bez nativní knihovny"}).")
            }
            if (!file.exists()) throw EngineException("Soubor mozku chybí – stáhni ho v sekci Mozky.")
            // vlákna jen na výkonných jádrech (profil zařízení – Pixel 9a: 4); úsporná jádra by generování brzdila
            val threads = if (nThreads > 0) nThreads else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            var ctx = minOf(ctxTokens, spec?.contextTokens?.times(2) ?: ctxTokens)
            // 8GB telefony: při málo volné RAM menší kontext (KV cache), aby systém mozek nezabil
            val freeMb = cz.axiom.omega.sys.Introspect.freeRamMb(context)
            if (freeMb in 1..2_500 && ctx > 4096) ctx = 4096
            if (freeMb in 1..1_200 && ctx > 2048) ctx = 2048
            val h = NativeLlama.nativeLoad(file.absolutePath, ctx, threads)
            if (h == 0L) throw EngineException("Mozek se nepodařilo načíst (poškozený soubor nebo málo paměti RAM).")
            return LocalEngine(h, spec, file, ctx, ctxTokens).also { current = it; it.applyLora(lora, loraScale) }
        }

        @Synchronized
        fun release() {
            current?.close()
            current = null
        }

        fun loadedLabel(): String? = current?.label
    }
}
