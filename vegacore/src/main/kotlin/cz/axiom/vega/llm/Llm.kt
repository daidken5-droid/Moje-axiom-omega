package cz.axiom.vega.llm

import cz.axiom.vega.net.NetException

enum class Role { SYSTEM, USER, ASSISTANT }
data class ChatMessage(val role: Role, val content: String)
data class GenOptions(val maxTokens: Int = 4096, val temperature: Float = 0.4f, val effort: String = "medium")
/** Kousek streamované odpovědi; [reasoning] = myšlenkový text modelu (nezobrazuje se jako odpověď). */
data class Delta(val text: String, val reasoning: Boolean = false)
data class GenResult(val text: String, val reasoning: String = "", val model: String = "", val finish: String? = null,
                     val inputTokens: Long? = null, val outputTokens: Long? = null, val millis: Long = 0)

class CancelledException : Exception("Zrušeno")

/** Zrušení běžící práce (tlačítko Stop): kontroluje se mezi kroky a při čtení streamu; může zavřít spojení. */
class CancelToken {
    @Volatile var cancelled = false; private set
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun cancel() { cancelled = true; hooks.forEach { runCatching { it() } } }
    fun check() { if (cancelled) throw CancelledException() }
    fun onCancel(h: () -> Unit) { hooks += h; if (cancelled) runCatching { h() } }
}

/** Chyba modelu/poskytovatele (HTTP, formát, limit). */
open class EngineException(msg: String, retryable: Boolean = false, billed: Boolean = false, cause: Throwable? = null) : NetException(msg, retryable, billed, cause)

interface LlmEngine {
    val id: String
    val label: String
    /** Kolik znaků vstupu snese (orientačně – podle okna modelu). */
    val contextChars: Int get() = 24_000
    fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit = {}): GenResult
}
