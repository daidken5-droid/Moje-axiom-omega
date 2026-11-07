package cz.axiom.omega.core.hub

import cz.axiom.omega.core.brain.BrainSpec
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil

/**
 * Hugging Face Hub bez SDK: vyhledání GGUF modelů, výpis souborů s velikostí a SHA-256
 * (LFS oid), převod na [BrainSpec] pro ověřené stažení, a datasety přes datasets-server.
 */
class HfHub(
    private val base: String = "https://huggingface.co",
    private val datasetsBase: String = "https://datasets-server.huggingface.co",
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Model(val id: String, val downloads: Long, val likes: Long, val updated: String, val gated: Boolean)
    data class GgufFile(val repo: String, val path: String, val size: Long, val sha256: String) {
        val quant: String get() = Regex("""(?i)(UD-)?(IQ\d_[A-Z]+|Q\d_K_[A-Z]+|Q\d_K|Q\d_\d|BF16|F16|F32)""").find(path)?.value ?: "?"
    }
    data class DatasetSplit(val config: String, val split: String)

    private fun getJson(url: String): JsonElement {
        val c = Http.open(url, readMs = 30_000)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val body = Http.readAll(if (code in 200..299) c.inputStream else c.errorStream, 8_000_000)
            if (code !in 200..299) {
                val msg = runCatching { ((json.parseToJsonElement(body) as JsonObject)["error"] as JsonPrimitive).content }.getOrNull()
                throw EngineException("Hugging Face: HTTP $code ${msg ?: ""}".trim())
            }
            return json.parseToJsonElement(body)
        } finally {
            c.disconnect()
        }
    }

    fun searchModels(query: String, limit: Int = 20): List<Model> {
        val url = "$base/api/models?search=${Web.enc(query)}&filter=gguf&sort=downloads&direction=-1&limit=$limit"
        return (getJson(url) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Model(
                id = o.s("id") ?: o.s("modelId") ?: return@mapNotNull null,
                downloads = (o["downloads"] as? JsonPrimitive)?.longOrNull ?: 0,
                likes = (o["likes"] as? JsonPrimitive)?.longOrNull ?: 0,
                updated = (o.s("lastModified") ?: o.s("createdAt")).orEmpty().take(10),
                gated = (o["gated"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull != "false") } ?: false,
            )
        }
    }

    fun ggufFiles(repo: String): List<GgufFile> {
        require(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(repo)) { "Neplatné jméno repozitáře." }
        val arr = getJson("$base/api/models/$repo/tree/main") as JsonArray
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = o.s("path") ?: return@mapNotNull null
            if (!path.endsWith(".gguf") || path.contains("mmproj", true)) return@mapNotNull null
            val lfs = o["lfs"] as? JsonObject
            val sha = lfs?.s("oid") ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: (lfs["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            GgufFile(repo, path, size, sha.removePrefix("sha256:"))
        }.sortedBy { it.size }
    }

    /** Ze souboru na HF udělá ověřitelný mozek (SHA-256 z LFS metadat). */
    fun toBrain(f: GgufFile): BrainSpec {
        val id = ("hf-" + f.repo.substringAfter('/') + "-" + f.quant).lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(60)
        return BrainSpec(
            id = id,
            name = f.repo.substringAfter('/').removeSuffix("-GGUF").removeSuffix("-gguf") + " · " + f.quant,
            family = f.repo.substringBefore('/'),
            description = "Staženo z Hugging Face: ${f.repo}/${f.path}",
            urls = listOf("$base/${f.repo}/resolve/main/${f.path}"),
            sizeBytes = f.size,
            sha256 = f.sha256,
            minRamGb = ceil(f.size * 1.35 / 1e9).toInt().coerceAtLeast(2),
            contextTokens = 4096,
            license = "viz huggingface.co/${f.repo}",
            thinking = true,
        )
    }

    // ---------------------------------------------------------------- datasety

    fun splits(dataset: String): List<DatasetSplit> {
        val root = getJson("$datasetsBase/splits?dataset=${Web.enc(dataset)}") as JsonObject
        return (root["splits"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            DatasetSplit(o.s("config") ?: return@mapNotNull null, o.s("split") ?: return@mapNotNull null)
        }
    }

    /** Řádky datasetu (max 100 na stránku); když je zadán dotaz, použije fulltext datasets-serveru. */
    fun rows(dataset: String, config: String, split: String, offset: Int, length: Int = 100, query: String? = null): List<JsonObject> {
        val q = "dataset=${Web.enc(dataset)}&config=${Web.enc(config)}&split=${Web.enc(split)}&offset=$offset&length=${length.coerceIn(1, 100)}"
        val url = if (query.isNullOrBlank()) "$datasetsBase/rows?$q" else "$datasetsBase/search?$q&query=${Web.enc(query)}"
        val root = getJson(url) as JsonObject
        return (root["rows"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("row")) as? JsonObject }
    }

    companion object {
        private val textKeys = listOf("title", "question", "instruction", "input", "prompt", "text", "content", "context", "answer", "output", "response", "summary")

        /** Řádek datasetu → (název, text) pro znalostní bázi. */
        fun rowToDoc(row: JsonObject, fallbackTitle: String): Pair<String, String>? {
            val parts = mutableListOf<String>()
            var title: String? = null
            for (k in textKeys) {
                val v = (row[k] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
                if (v.isEmpty()) continue
                if (k == "title" && v.length < 200) title = v else parts += if (k in setOf("text", "content")) v else "${k.replaceFirstChar { it.uppercase() }}: $v"
            }
            if (parts.isEmpty()) {
                row.entries.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.length > 20 }?.let { parts += "$k: $it" } }
            }
            val body = parts.joinToString("\n\n").trim()
            if (body.length < 20) return null
            return (title ?: fallbackTitle) to body
        }

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
