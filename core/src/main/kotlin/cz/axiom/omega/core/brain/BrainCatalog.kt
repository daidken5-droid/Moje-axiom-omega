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
) {
    val fileName get() = "$id.gguf"
    val sizeGbLabel get() = String.format(java.util.Locale.ROOT, "%.2f GB", sizeBytes / 1e9).replace('.', ',')
}

object BrainCatalog {

    const val REMOTE_URL = "https://raw.githubusercontent.com/daidken5-droid/Moje-axiom-omega/main/brains/catalog.json"

    private val json = Json { ignoreUnknownKeys = true }

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
                sha256 = s("sha256").lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) { "Neplatné SHA-256" } },
                minRamGb = o["minRamGb"]!!.jsonPrimitive.int,
                contextTokens = o["contextTokens"]?.jsonPrimitive?.intOrNull ?: 4096,
                license = o["license"]?.jsonPrimitive?.content ?: "",
                thinking = b("thinking"),
                recommended = b("recommended"),
            )
        }
    }

    /** Nejlepší mozek, který se do zařízení vejde (RAM i úložiště). */
    fun recommend(brains: List<BrainSpec>, totalRamGb: Double, freeBytes: Long): BrainSpec? =
        brains.filter { it.minRamGb <= totalRamGb + 0.3 && it.sizeBytes * 1.05 < freeBytes }
            .sortedWith(compareByDescending<BrainSpec> { it.recommended }.thenByDescending { it.sizeBytes })
            .firstOrNull()
            ?: brains.minByOrNull { it.sizeBytes }
}

