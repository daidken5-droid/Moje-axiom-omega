package cz.axiom.omega.core.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Katalog "mozků" (GGUF modelů pro llama.cpp v telefonu).
 *
 * AXIOM 2.1 měl jedinou natvrdo zadanou URL + velikost + SHA v kódu, bez navazování
 * a bez alternativy. Omega má katalog (vestavěný + aktualizovatelný z GitHubu),
 * více velikostí, zrcadla a ověření SHA-256 (hodnoty převzaté z Hugging Face LFS metadat).
 */
data class BrainSpec(
    val id: String,
    val name: String,
    val family: String,
    val description: String,
    val urls: List<String>,
    val sizeBytes: Long,
    val sha256: String,
    val minRamGb: Int,
    val contextTokens: Int,
    val license: String,
    /** "qwen3" = model zná přepínač přemýšlení (enable_thinking). */
    val thinking: Boolean,
    val recommended: Boolean = false,
    /** chat = mozek pro odpovědi, embedding = rozšíření pro sémantické hledání */
    val kind: String = "chat",
) {
    val fileName get() = "$id.gguf"
    val sizeGbLabel get() = String.format(java.util.Locale.ROOT, "%.2f GB", sizeBytes / 1e9).replace('.', ',')
}

object BrainCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(list: List<BrainSpec>): String = kotlinx.serialization.json.buildJsonObject {
        put("version", kotlinx.serialization.json.JsonPrimitive(1))
        put("brains", kotlinx.serialization.json.buildJsonArray {
            list.forEach { b ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(b.id)); put("name", kotlinx.serialization.json.JsonPrimitive(b.name))
                    put("family", kotlinx.serialization.json.JsonPrimitive(b.family)); put("description", kotlinx.serialization.json.JsonPrimitive(b.description))
                    put("urls", kotlinx.serialization.json.JsonArray(b.urls.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("sizeBytes", kotlinx.serialization.json.JsonPrimitive(b.sizeBytes)); put("sha256", kotlinx.serialization.json.JsonPrimitive(b.sha256))
                    put("minRamGb", kotlinx.serialization.json.JsonPrimitive(b.minRamGb)); put("contextTokens", kotlinx.serialization.json.JsonPrimitive(b.contextTokens))
                    put("license", kotlinx.serialization.json.JsonPrimitive(b.license)); put("thinking", kotlinx.serialization.json.JsonPrimitive(b.thinking))
                    put("kind", kotlinx.serialization.json.JsonPrimitive(b.kind))
                })
            }
        })
    }.toString()

    fun parse(text: String): List<BrainSpec> {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        require(version in 1..2) { "Nepodporovaná verze katalogu $version" }
        return root["brains"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            fun b(k: String) = o[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            BrainSpec(
                id = s("id").also { require(Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(it)) { "Neplatné id mozku $it" } },
                name = s("name"),
                family = s("family"),
                description = s("description"),
                urls = o["urls"]!!.jsonArray.map { it.jsonPrimitive.content }.also { list ->
                    require(list.isNotEmpty() && list.all { it.startsWith("https://") }) { "Mozek ${s("id")}: URL musí být https" }
                },
                sizeBytes = o["sizeBytes"]!!.jsonPrimitive.long.also { require(it > 10_000_000) },
                // prázdné SHA jen u vlastních odkazů uživatele (ověří se aspoň hlavička GGUF)
                sha256 = s("sha256").lowercase().also { require(it.isEmpty() || Regex("[0-9a-f]{64}").matches(it)) { "Neplatné SHA-256" } },
                minRamGb = o["minRamGb"]!!.jsonPrimitive.int,
                contextTokens = o["contextTokens"]?.jsonPrimitive?.intOrNull ?: 4096,
                license = o["license"]?.jsonPrimitive?.content ?: "",
                thinking = b("thinking"),
                recommended = b("recommended"),
                kind = o["kind"]?.jsonPrimitive?.content?.takeIf { it in setOf("chat", "embedding") } ?: "chat",
            )
        }
    }

    /**
     * Android hlásí o něco méně RAM, než je nominální velikost (8GB telefon ≈ 7,3–7,7 GB),
     * protože část si bere jádro a GPU. Převod na nominální třídu: 7,5 → 8, 11,3 → 12, 5,6 → 6.
     */
    fun nominalRamGb(reportedGb: Double): Int =
        listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32).firstOrNull { it >= reportedGb - 0.15 } ?: kotlin.math.ceil(reportedGb).toInt()

    fun fits(spec: BrainSpec, reportedRamGb: Double) = spec.minRamGb <= nominalRamGb(reportedRamGb)

    /** Nejlepší mozek, který se do zařízení vejde (RAM i úložiště). */
    fun recommend(brains: List<BrainSpec>, totalRamGb: Double, freeBytes: Long): BrainSpec? =
        brains.filter { it.kind == "chat" && fits(it, totalRamGb) && it.sizeBytes * 1.05 < freeBytes }
            .sortedWith(compareByDescending<BrainSpec> { it.recommended }.thenByDescending { it.sizeBytes })
            .firstOrNull()
            ?: brains.filter { it.kind == "chat" }.minByOrNull { it.sizeBytes }
}

