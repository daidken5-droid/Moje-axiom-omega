package cz.axiom.omega.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Kimi K3 přes OpenAI-kompatibilní Chat Completions API.
 *
 *  • cloud:   https://api.moonshot.ai/v1  (model `kimi-k3`, vždy přemýšlí, `reasoning_effort` low|high|max,
 *             teplotu si platforma určuje sama → neposíláme ji)
 *  • server:  vlastní K3 (llama-server / vLLM / SGLang) v LAN – stejné API, adresa a model volitelné.
 *
 * Kimi K3 má 2,8 bilionu parametrů (nejmenší GGUF ~594 GB) – v telefonu fyzicky neběží.
 * "Offline K3" proto znamená vlastní server bez internetu; v telefonu offline běží lokální mozek.
 */
class KimiEngine(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val kind: EngineKind,
    /** Jak převést úsilí na `reasoning_effort` (null = neposílat). */
    private val effortMapper: ((String) -> String?)? = if (kind == EngineKind.KIMI_CLOUD) ::mapEffort else null,
    /** Jméno poskytovatele do chybových hlášek a vlastní štítek (Cloud zdarma: Groq, Cerebras…). */
    private val vendor: String = "Kimi",
    private val labelOverride: String? = null,
    /** Strop výstupu – poskytovatelé s menším limitem jinak vrátí HTTP 400 (HF Inference Providers ~8k). */
    private val maxOut: Int = if (kind == EngineKind.HF) 8192 else Int.MAX_VALUE,
) : LlmEngine {

    override val id = "kimi:$model@${baseUrl.trimEnd('/')}"
    override val label = labelOverride ?: when (kind) {
        EngineKind.KIMI_SERVER -> "Vlastní server ($model)"
        EngineKind.NODE -> "AVIOR Node ($model)"
        EngineKind.HF -> "Hugging Face (${model.substringAfterLast('/')})"
        else -> "Kimi K3 · cloud ($model)"
    }
    override val contextBudgetChars = if (kind == EngineKind.HF) 60_000 else 400_000

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val started = System.currentTimeMillis()
        val body = buildRequest(messages, options)
        val conn = Http.open(baseUrl.trimEnd('/') + "/chat/completions", "POST", readMs = 300_000)
        cancel.onCancel { runCatching { conn.disconnect() } }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (!apiKey.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, Http.readAll(conn.errorStream, 64_000))

            val text = StringBuilder()
            val reasoning = StringBuilder()
            // Některé servery (llama-server) posílají úvahu inline jako <think>…</think>.
            val splitter = ThinkSplitter { d -> if (d.reasoning) reasoning.append(d.text) else text.append(d.text); onDelta(d) }
            var finish: String? = null
            var usageIn: Long? = null
            var usageOut: Long? = null
            var servedModel = model

            Sse.read(conn.inputStream, cancel) { _, data ->
                if (data.trim() == "[DONE]") return@read false
                val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@read true
                (obj["error"] as? JsonObject)?.let { throw EngineException("$vendor: ${it.str("message") ?: "chyba streamu"}", retryable = true) }
                obj.str("model")?.let { servedModel = it }
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val delta = choice?.get("delta") as? JsonObject
                (delta?.str("reasoning_content") ?: delta?.str("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it); onDelta(Delta(it, reasoning = true))
                }
                delta?.str("content")?.takeIf { it.isNotEmpty() }?.let { splitter.feed(it) }
                choice?.str("finish_reason")?.let { finish = it }
                val usage = (obj["usage"] as? JsonObject) ?: (choice?.get("usage") as? JsonObject)
                usage?.let {
                    usageIn = (it["prompt_tokens"] as? JsonPrimitive)?.longOrNull
                    usageOut = (it["completion_tokens"] as? JsonPrimitive)?.longOrNull
                }
                true
            }
            splitter.finish()
            cancel.check()
            if (text.isBlank()) throw EngineException("$vendor nevrátil text (finish_reason=${finish ?: "?"}). Zkus to znovu nebo sniž úsilí.")
            if (finish == "length") text.append("\n\n[Odpověď byla zkrácena limitem tokenů.]")
            return GenResult(text.toString().trim(), reasoning.toString().trim(), servedModel, finish, emptyList(), usageIn, usageOut, System.currentTimeMillis() - started)
        } catch (e: CancelledException) {
            throw e
        } catch (e: EngineException) {
            throw e
        } catch (e: IOException) {
            if (cancel.cancelled) throw CancelledException()
            throw EngineException(
                if (kind == EngineKind.KIMI_SERVER || kind == EngineKind.NODE) "Server ${baseUrl} neodpovídá: ${e.message}. Je zapnutý a dostupný?"
                else "Spojení s $vendor selhalo: ${e.message}",
                retryable = true, cause = e,
            )
        } finally {
            conn.disconnect()
        }
    }

    internal fun buildRequest(messages: List<ChatMessage>, options: GenOptions): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("max_tokens", options.maxTokens.coerceAtMost(maxOut))
        if (kind != EngineKind.KIMI_CLOUD) put("temperature", options.temperature.toDouble())
        effortMapper?.invoke(options.effort)?.let { put("reasoning_effort", it) }
        put("messages", buildJsonArray {
            // Sloučí systémové zprávy do jedné na začátku (některé servery víc nesnesou).
            val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
            if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
            messages.filter { it.role != Role.SYSTEM }.forEach { m ->
                add(buildJsonObject { put("role", if (m.role == Role.USER) "user" else "assistant"); put("content", m.content) })
            }
        })
    }

    private fun httpError(code: Int, body: String): EngineException {
        val msg = runCatching {
            val o = Json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.str("message") ?: o.str("message")
        }.getOrNull() ?: body.take(300)
        return when (code) {
            401, 403 -> EngineException("$vendor: neplatný nebo neoprávněný API klíč (HTTP $code). $msg")
            404 -> EngineException("$vendor: model '$model' nebo adresa nenalezena (HTTP 404). Načti seznam modelů. $msg")
            429 -> EngineException("$vendor: limit nebo kredit vyčerpán (HTTP 429). $msg", retryable = true)
            in 500..599 -> EngineException("$vendor: chyba serveru (HTTP $code). $msg", retryable = true)
            else -> EngineException("$vendor: HTTP $code. $msg")
        }
    }

    companion object {
        const val CLOUD_BASE = "https://api.moonshot.ai/v1"
        const val CLOUD_MODEL = "kimi-k3"

        /** K3 podporuje reasoning_effort low|high|max. */
        fun mapEffort(effort: String): String = when (effort.lowercase()) {
            "low" -> "low"
            "max", "xhigh" -> "max"
            else -> "high"
        }

        fun listModels(baseUrl: String, apiKey: String?): List<String> {
            val conn = Http.open(baseUrl.trimEnd('/') + "/models", readMs = 30_000)
            if (!apiKey.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            try {
                val code = conn.responseCode
                val body = Http.readAll(if (code in 200..299) conn.inputStream else conn.errorStream, 2_000_000)
                if (code !in 200..299) throw EngineException("Seznam modelů: HTTP $code ${body.take(200)}")
                val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
                return data.mapNotNull { (it as? JsonObject)?.str("id") }.sorted()
            } finally {
                conn.disconnect()
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = when (val v: JsonElement? = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.contentOrNull
    else -> null
}
