package cz.axiom.omega.core.packs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest

/**
 * Offline znalostní balíčky: Markdown soubory rozdělené podle nadpisů "## " na samostatné
 * dokumenty, které se zaindexují do lokální fulltextové databáze (SQLite FTS4).
 * Vestavěné v APK (fungují bez internetu od prvního spuštění) a aktualizovatelné z GitHubu.
 */
data class PackSpec(
    val id: String,
    val name: String,
    val description: String,
    val file: String,
    val version: Int,
    val bytes: Long,
    val sha256: String,
    val sections: Int,
    val autoInstall: Boolean,
)

data class PackSection(val title: String, val body: String)

object Packs {
    const val REMOTE_BASE = "https://raw.githubusercontent.com/daidken5-droid/Moje-axiom-omega/main/packs/"

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCatalog(text: String): List<PackSpec> =
        json.parseToJsonElement(text).jsonObject["packs"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            PackSpec(
                id = s("id").also { require(Regex("[a-z0-9][a-z0-9-]{1,48}").matches(it)) { "Neplatné id balíčku $it" } },
                name = s("name"),
                description = s("description"),
                file = s("file").also { require(Regex("[a-z0-9][a-z0-9.-]{1,60}\\.md").matches(it)) { "Neplatný soubor $it" } },
                version = o["version"]?.jsonPrimitive?.intOrNull ?: 1,
                bytes = o["bytes"]!!.jsonPrimitive.long.also { require(it in 1..20_000_000) },
                sha256 = s("sha256").lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) },
                sections = o["sections"]?.jsonPrimitive?.int ?: 0,
                autoInstall = o["autoInstall"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }

    fun verify(spec: PackSpec, bytes: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == spec.sha256

    /** Rozdělí Markdown na sekce podle "## nadpis"; titul balíčku "# …" se přidá jako kontext. */
    fun sections(markdown: String): List<PackSection> {
        val lines = markdown.replace("\r", "").lines()
        val packTitle = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()
        val out = mutableListOf<PackSection>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val t = title ?: return
            val b = body.toString().trim()
            if (b.isNotEmpty()) out += PackSection(if (packTitle.isNotEmpty()) "$packTitle – $t" else t, b)
            body.setLength(0)
        }
        for (l in lines) {
            when {
                l.startsWith("## ") -> { flush(); title = l.removePrefix("## ").trim() }
                l.startsWith("# ") -> Unit
                title != null -> body.append(l).append('\n')
            }
        }
        flush()
        return out
    }
}
