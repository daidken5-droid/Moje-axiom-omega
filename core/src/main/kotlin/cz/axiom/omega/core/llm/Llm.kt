package cz.axiom.omega.core.llm

import java.util.concurrent.atomic.AtomicBoolean

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

data class GenOptions(
    val maxTokens: Int = 4096,
    /** low | medium | high | xhigh | max – mapuje se na effort/reasoning_effort poskytovatele. */
    val effort: String = "medium",
    val temperature: Float = 0.6f,
    val webSearch: Boolean = false,
    /** Lokální modely s přepínačem přemýšlení (Qwen3/3.5): <think> ano/ne. */
    val thinking: Boolean = false,
    /** NATIVNÍ NÁSTROJE (OpenAI „tools“): když je poskytovatel podporuje, model volá nástroje strukturovaně. */
    val tools: List<ToolSpec> = emptyList(),
)

/** Popis nástroje pro nativní function calling: [parametersJson] je JSON Schema objektu argumentů. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

/** Strukturované volání nástroje od modelu (argumenty jako JSON text, složený ze streamu). */
data class ToolCall(val id: String, val name: String, val arguments: String)

data class Source(val title: String, val url: String)

data class Delta(val text: String, val reasoning: Boolean = false)

data class GenResult(
    val text: String,
    val reasoning: String = "",
    val model: String,
    val stopReason: String? = null,
    val sources: List<Source> = emptyList(),
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val millis: Long = 0,
    /** nativní volání nástrojů (prázdné, když model odpověděl textem) */
    val toolCalls: List<ToolCall> = emptyList(),
)

class CancelToken {
    private val flag = AtomicBoolean(false)
    private val hooks = mutableListOf<() -> Unit>()
    val cancelled get() = flag.get()

    fun cancel() {
        if (flag.compareAndSet(false, true)) synchronized(hooks) { hooks.toList() }.forEach { runCatching(it) }
    }

    /** Zaregistruje akci (zavření spojení, nativní stop), která proběhne při zrušení. */
    fun onCancel(action: () -> Unit) {
        synchronized(hooks) { hooks += action }
        if (cancelled) runCatching(action)
    }

    fun check() {
        if (cancelled) throw CancelledException()
    }
}

class CancelledException : Exception("Zastaveno uživatelem.")

/** Chyba poskytovatele s lidsky čitelnou zprávou. [retryable] = má smysl zkusit později. */
class EngineException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/** Společné rozhraní všech mozků: lokální llama.cpp, Kimi K3 (cloud / vlastní server), Claude. */
interface LlmEngine {
    val id: String
    val label: String
    val kind: EngineKind
    /** Kolik znaků historie+podkladů má smysl posílat (lokální mozek má malý kontext). */
    val contextBudgetChars: Int

    fun generate(
        messages: List<ChatMessage>,
        options: GenOptions,
        cancel: CancelToken,
        onDelta: (Delta) -> Unit,
    ): GenResult
}

enum class EngineKind(val label: String, val online: Boolean) {
    LOCAL("Lokální mozek v telefonu", false),
    KIMI_CLOUD("Kimi K3 · cloud", true),
    KIMI_SERVER("Kimi K3 · vlastní server", false),
    NODE("AVIOR Node · cloud server", true),
    CLAUDE("Claude · Anthropic API", true),
    HF("Hugging Face · Inference Providers", true),
    FREE("Cloud · Cerebras / Groq / OpenRouter / DeepSeek / Chutes", true),
}

/**
 * Rozdělí proud tokenů na <think>…</think> (úvaha) a běžný text.
 * Odolné vůči tomu, že značka přijde rozsekaná do více tokenů.
 */
class ThinkSplitter(private val emit: (Delta) -> Unit) {
    private val buf = StringBuilder()
    private var inThink = false
    val answer = StringBuilder()
    val reasoning = StringBuilder()

    fun feed(chunk: String) {
        buf.append(chunk)
        while (true) {
            val tag = if (inThink) "</think>" else "<think>"
            val idx = buf.indexOf(tag)
            if (idx >= 0) {
                flush(buf.substring(0, idx))
                buf.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            // podrž konec, který může být začátkem značky
            var keep = 0
            for (k in tag.length - 1 downTo 1) {
                if (buf.length >= k && tag.startsWith(buf.substring(buf.length - k))) { keep = k; break }
            }
            flush(buf.substring(0, buf.length - keep))
            buf.delete(0, buf.length - keep)
            return
        }
    }

    fun finish() {
        flush(buf.toString())
        buf.clear()
    }

    private fun flush(s: String) {
        if (s.isEmpty()) return
        if (inThink) reasoning.append(s) else answer.append(s)
        emit(Delta(s, inThink))
    }
}
