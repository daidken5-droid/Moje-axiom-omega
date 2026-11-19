package cz.axiom.vega.llm

import cz.axiom.vega.net.Http
import cz.axiom.vega.net.Sse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Claude přes Anthropic Messages API (stream), bez SDK – stejná síťová brána jako ostatní poskytovatelé. */
class AnthropicEngine(private val key: String, val model: String) : LlmEngine {
    override val id = "claude/$model"
    override val label = "Claude · $model"
    override val contextChars = 400_000

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val t0 = System.currentTimeMillis()
        val sys = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val body = buildJsonObject {
            put("model", model); put("max_tokens", options.maxTokens); put("stream", true)
            if (sys.isNotBlank()) put("system", sys)
            put("messages", buildJsonArray { messages.filter { it.role != Role.SYSTEM }.forEach { m -> add(buildJsonObject { put("role", if (m.role == Role.USER) "user" else "assistant"); put("content", m.content) }) } })
        }
        val c = Http.open("$BASE/messages", "POST", readMs = 300_000)
        cancel.onCancel { runCatching { c.disconnect() } }
        c.setRequestProperty("x-api-key", key.trim()); c.setRequestProperty("anthropic-version", VERSION); c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw EngineException("Claude HTTP $code: ${Http.readText(c.errorStream).take(300)}", retryable = code == 429 || code >= 500 || code == 529)
            val text = StringBuilder(); val think = StringBuilder(); var inTok: Long? = null; var outTok: Long? = null; var stop: String? = null
            Sse.read(c.inputStream, cancel) { data ->
                val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@read true
                when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                    "message_start" -> inTok = (((o["message"] as? JsonObject)?.get("usage") as? JsonObject)?.get("input_tokens") as? JsonPrimitive)?.longOrNull
                    "content_block_delta" -> (o["delta"] as? JsonObject)?.let { d ->
                        (d["text"] as? JsonPrimitive)?.contentOrNull?.let { text.append(it); onDelta(Delta(it)) }
                        (d["thinking"] as? JsonPrimitive)?.contentOrNull?.let { think.append(it); onDelta(Delta(it, true)) }
                    }
                    "message_delta" -> { outTok = ((o["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.longOrNull ?: outTok
                        stop = ((o["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull }
                    "message_stop" -> return@read false
                    "error" -> throw EngineException("Claude: ${((o["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull}", retryable = true, billed = true)
                }
                true
            }
            cancel.check()
            if (text.isBlank()) throw EngineException("Claude nevrátil text.", billed = true)
            return GenResult(text.toString().trim(), think.toString(), model, stop, inTok, outTok, System.currentTimeMillis() - t0)
        } catch (e: CancelledException) { throw e
        } catch (e: EngineException) { throw e
        } catch (e: Exception) { if (cancel.cancelled) throw CancelledException(); throw EngineException("Claude: ${e.message}", retryable = true, cause = e)
        } finally { c.disconnect() }
    }

    companion object {
        const val BASE = "https://api.anthropic.com/v1"; const val VERSION = "2023-06-01"
        fun listModels(key: String): List<String> {
            val c = Http.open("$BASE/models", readMs = 30_000)
            c.setRequestProperty("x-api-key", key.trim()); c.setRequestProperty("anthropic-version", VERSION)
            try {
                val code = c.responseCode
                if (code !in 200..299) throw EngineException("Claude HTTP $code: ${Http.readText(c.errorStream).take(200)}")
                return ((Json.parseToJsonElement(Http.readText(c.inputStream, 2_000_000)).jsonObject["data"] as? JsonArray) ?: JsonArray(emptyList()))
                    .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            } finally { c.disconnect() }
        }
    }
}
