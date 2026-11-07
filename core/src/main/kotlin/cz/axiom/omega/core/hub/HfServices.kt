package cz.axiom.omega.core.hub

import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.core.llm.KimiEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * SLUŽBY HUGGING FACE (Inference Providers) – nejdůležitější „podslužby“, které AVIOR doplňují tam,
 * kde mozek v telefonu nestačí. Vše jedním HF tokenem (huggingface.co/settings/tokens, oprávnění
 * „Make calls to Inference Providers“), zdarma v rámci měsíčního kreditu HF:
 *
 *  • CHAT     – výkonné otevřené modely (Llama, Qwen, DeepSeek…) jako další volitelný mozek,
 *  • VIZE     – popis obrázku, čtení textu z fotky, rozbor grafu/dokumentu (vision-language model),
 *  • PŘEPIS   – řeč na text z nahrávky (Whisper),
 *  • OBRÁZKY  – generování obrázku z popisu (FLUX).
 * Modely jsou nastavitelné; výchozí jsou široce dostupné.
 */
object HfServices {
    const val ROUTER = "https://router.huggingface.co"
    const val CHAT_URL = "$ROUTER/v1"
    const val DEFAULT_CHAT = "meta-llama/Llama-3.3-70B-Instruct"
    const val DEFAULT_VISION = "Qwen/Qwen2.5-VL-7B-Instruct"
    const val DEFAULT_ASR = "openai/whisper-large-v3-turbo"
    const val DEFAULT_IMAGE = "black-forest-labs/FLUX.1-schnell"

    private val json = Json { ignoreUnknownKeys = true }

    fun chatEngine(token: String, model: String = DEFAULT_CHAT) = KimiEngine(CHAT_URL, token, model, EngineKind.HF)

    /** Popis / OCR / rozbor obrázku vision modelem. */
    fun describeImage(token: String, image: ByteArray, mime: String, prompt: String, model: String = DEFAULT_VISION): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(image)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1500)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:$mime;base64,$b64") }) })
                    })
                })
            })
        }
        val out = post("$CHAT_URL/chat/completions", token, "application/json", body.toString().toByteArray())
        val o = json.parseToJsonElement(String(out, Charsets.UTF_8)).jsonObject
        return (o["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
            ?: throw EngineException("Hugging Face nevrátil popis obrázku.")
    }

    /** Přepis řeči na text (Whisper). */
    fun transcribe(token: String, audio: ByteArray, mime: String, model: String = DEFAULT_ASR): String {
        val out = post("$ROUTER/hf-inference/models/$model", token, mime.ifBlank { "audio/mpeg" }, audio)
        val o = json.parseToJsonElement(String(out, Charsets.UTF_8))
        return (o as? kotlinx.serialization.json.JsonObject)?.get("text")?.jsonPrimitive?.content?.trim()
            ?: throw EngineException("Hugging Face nevrátil přepis.")
    }

    /** Obrázek z textového popisu (PNG/JPEG bajty). */
    fun generateImage(token: String, prompt: String, model: String = DEFAULT_IMAGE): ByteArray {
        val body = buildJsonObject { put("inputs", prompt) }.toString().toByteArray()
        val out = post("$ROUTER/hf-inference/models/$model", token, "application/json", body, accept = "image/png")
        if (out.size < 100 || !(isPng(out) || isJpeg(out))) throw EngineException("Hugging Face nevrátil obrázek: ${String(out.take(200).toByteArray())}")
        return out
    }

    /** Chat modely dostupné přes Inference Providers (pro výběr v nastavení). */
    fun chatModels(token: String): List<String> {
        val c = Http.open("$CHAT_URL/models", connectMs = 15_000, readMs = 30_000)
        c.setRequestProperty("Authorization", "Bearer $token")
        return try {
            if (c.responseCode != 200) emptyList()
            else (json.parseToJsonElement(Http.readAll(c.inputStream)).jsonObject["data"] as? JsonArray)
                ?.mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.content }?.sorted() ?: emptyList()
        } finally { c.disconnect() }
    }

    fun isPng(b: ByteArray) = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun post(url: String, token: String, contentType: String, body: ByteArray, accept: String? = null): ByteArray {
        val c = Http.open(url, "POST", connectMs = 20_000, readMs = 180_000)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", contentType)
        accept?.let { c.setRequestProperty("Accept", it) }
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw EngineException("Hugging Face odmítl token (HTTP $code) – v tokenu povol „Make calls to Inference Providers“.")
            if (code == 402) throw EngineException("Vyčerpaný měsíční kredit Hugging Face (HTTP 402).")
            if (code == 503) throw EngineException("Model se na Hugging Face právě načítá – zkus to za chvíli (HTTP 503).")
            if (code !in 200..299) throw EngineException("Hugging Face: HTTP $code ${Http.readAll(c.errorStream, 400)}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
