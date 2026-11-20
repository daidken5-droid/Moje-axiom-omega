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

/**
 * Jakýkoli server s OpenAI API (/chat/completions se streamem): Gemini, Groq, Cerebras, OpenRouter, DeepSeek i vlastní
 * llama.cpp / Ollama / vLLM. Myšlenkový text (reasoning_content nebo <think>…</think>) se odděluje od odpovědi.
 */
class OpenAiEngine(private val base: String, private val key: String?, val model: String, private val vendor: String,
                   override val contextChars: Int = 24_000) : LlmEngine {
    override val id = "$vendor/$model"
    override val label = "$vendor · $model"

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject {
            put("model", model); put("stream", true); put("max_tokens", options.maxTokens); put("temperature", options.temperature.toDouble())
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("messages", buildJsonArray { messages.forEach { m -> add(buildJsonObject { put("role", m.role.name.lowercase()); put("content", m.content) }) } })
        }
        val c = Http.open(base.trimEnd('/') + "/chat/completions", "POST", readMs = 300_000)
        cancel.onCancel { runCatching { c.disconnect() } }
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "text/event-stream")
        if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw httpError(vendor, code, Http.readText(c.errorStream))
            val text = StringBuilder(); val think = StringBuilder()
            var inThink = false; var finish: String? = null; var inTok: Long? = null; var outTok: Long? = null; var served = model
            var carry = ""
            fun emit(s0: String) {
                // <think>…</think> přímo v textu (llama.cpp, některé otevřené modely); značka rozdělená mezi části streamu se skládá
                var s = carry + s0; carry = ""
                val tag = if (inThink) "</think>" else "<think>"
                for (k in minOf(tag.length - 1, s.length) downTo 1) if (tag.startsWith(s.takeLast(k))) { carry = s.takeLast(k); s = s.dropLast(k); break }
                var rest = s
                while (rest.isNotEmpty()) {
                    if (inThink) { val e = rest.indexOf("</think>"); if (e < 0) { think.append(rest); onDelta(Delta(rest, true)); rest = "" } else { think.append(rest, 0, e); onDelta(Delta(rest.substring(0, e), true)); rest = rest.substring(e + 8); inThink = false } }
                    else { val b = rest.indexOf("<think>"); if (b < 0) { text.append(rest); onDelta(Delta(rest)); rest = "" } else { if (b > 0) { text.append(rest, 0, b); onDelta(Delta(rest.substring(0, b))) }; rest = rest.substring(b + 7); inThink = true } }
                }
            }
            var done = false
            Sse.read(c.inputStream, cancel) { data ->
                if (data.trim() == "[DONE]") { done = true; return@read false }
                val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@read true
                (o["error"] as? JsonObject)?.let { throw EngineException("$vendor: ${(it["message"] as? JsonPrimitive)?.contentOrNull ?: "chyba streamu"}", retryable = true, billed = true) }
                (o["model"] as? JsonPrimitive)?.contentOrNull?.let { served = it }
                val ch = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val d = ch?.get("delta") as? JsonObject
                ((d?.get("reasoning_content") ?: d?.get("reasoning")) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { think.append(it); onDelta(Delta(it, true)) }
                (d?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                (ch?.get("finish_reason") as? JsonPrimitive)?.contentOrNull?.let { finish = it }
                (o["usage"] as? JsonObject)?.let { u ->
                    inTok = (u["prompt_tokens"] as? JsonPrimitive)?.longOrNull
                    outTok = (u["completion_tokens"] as? JsonPrimitive)?.longOrNull
                    // myšlenkové tokeny bývají jen v total_tokens – účtují se jako výstup
                    val tot = (u["total_tokens"] as? JsonPrimitive)?.longOrNull
                    if (tot != null && inTok != null && tot > inTok!! + (outTok ?: 0)) outTok = tot - inTok!!
                }
                true
            }
            if (carry.isNotEmpty()) { if (inThink) think.append(carry) else text.append(carry); carry = "" }
            cancel.check()
            if (text.isBlank()) throw EngineException("$vendor nevrátil text (finish=${finish ?: "?"}).", retryable = true, billed = true)
            // stream skončil bez [DONE] i bez finish_reason = spojení se přerušilo; neúplný text nevydávat za celou odpověď
            if (!done && finish == null) throw EngineException("$vendor: spojení se přerušilo uprostřed odpovědi.", retryable = true, billed = true, cause = java.io.IOException("stream closed"))
            if (finish == "length") text.append("\n\n[Odpověď byla zkrácena limitem délky.]")
            return GenResult(text.toString().trim(), think.toString().trim(), served, finish, inTok, outTok, System.currentTimeMillis() - t0)
        } catch (e: CancelledException) { throw e
        } catch (e: EngineException) { throw e
        } catch (e: Exception) { if (cancel.cancelled) throw CancelledException(); throw EngineException("$vendor: ${e.message ?: e.javaClass.simpleName}", retryable = true, cause = e)
        } finally { c.disconnect() }
    }

    companion object {
        fun httpError(vendor: String, code: Int, body: String): EngineException {
            val msg = runCatching { ((Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: body.take(300)
            return EngineException("$vendor HTTP $code: $msg", retryable = code == 429 || code >= 500 || code == 404)
        }

        /** Modely, které server nabízí (bez útraty): OpenAI tvar {"data":[{"id"}]} i llama.cpp {"models":[{"name"}]}. */
        fun listModels(base: String, key: String?): List<String> {
            val c = Http.open(base.trimEnd('/') + "/models", readMs = 30_000)
            if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            try {
                val code = c.responseCode
                if (code !in 200..299) throw httpError("server", code, Http.readText(c.errorStream))
                val o = Json.parseToJsonElement(Http.readText(c.inputStream, 4_000_000)).jsonObject
                val a = (o["data"] as? JsonArray) ?: (o["models"] as? JsonArray) ?: JsonArray(emptyList())
                return a.mapNotNull { e -> (e as? JsonObject)?.let { ((it["id"] ?: it["name"]) as? JsonPrimitive)?.contentOrNull } }.map { it.removePrefix("models/") }
            } finally { c.disconnect() }
        }
    }
}
