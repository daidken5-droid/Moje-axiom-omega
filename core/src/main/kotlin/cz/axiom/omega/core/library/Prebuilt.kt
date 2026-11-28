package cz.axiom.omega.core.library

import cz.axiom.omega.core.brain.BrainSpec
import cz.axiom.omega.core.brain.Downloader
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Enumeration
import java.util.zip.GZIPInputStream

/**
 * Předpřipravená offline knihovna: celá databáze (SQLite FTS4) sestavená na GitHub Actions
 * (tools/build_library.py), zabalená gzipem a rozdělená na části < 2 GB (limit GitHub Release).
 *
 * Proč ne přímo v APK: ZIP/APK má limit 4 GB, Android by soubor při instalaci rozbalil podruhé
 * (2× místo) a každá aktualizace aplikace by znamenala stáhnout gigabajty znovu.
 * Takhle jde o jedno klepnutí: ~1 GB stažení → ~3 GB hotová databáze, bez pomalého indexování v telefonu.
 */
data class PrebuiltPart(val name: String, val size: Long, val sha256: String)

data class PrebuiltManifest(
    val lib: String,
    val config: String = "",
    val format: String,
    val articles: Int,
    val dbSize: Long,
    val dbSha256: String,
    val parts: List<PrebuiltPart>,
    val license: String = "",
) {
    val downloadBytes get() = parts.sumOf { it.size }
}

object Prebuilt {
    const val RELEASES = "https://github.com/daidken5-droid/Moje-axiom-omega/releases/download"

    fun tag(spec: LibrarySpec) = "library-${spec.id}-${spec.config.substringBefore('.')}"
    fun manifestUrl(spec: LibrarySpec) = "$RELEASES/${tag(spec)}/manifest.json"
    fun partUrl(spec: LibrarySpec, part: PrebuiltPart) = "$RELEASES/${tag(spec)}/${part.name}"

    fun parse(text: String): PrebuiltManifest {
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Manifest bez pole $k")
        val m = PrebuiltManifest(
            lib = str("lib"), config = o["config"]?.jsonPrimitive?.content ?: "", format = str("format"),
            articles = str("articles").toInt(), dbSize = str("dbSize").toLong(), dbSha256 = str("dbSha256"),
            parts = (o["parts"]?.jsonArray ?: throw IllegalArgumentException("Manifest bez částí.")).map { e ->
                val p = e.jsonObject
                PrebuiltPart(p["name"]!!.jsonPrimitive.content, p["size"]!!.jsonPrimitive.long, p["sha256"]!!.jsonPrimitive.content)
            },
            license = o["license"]?.jsonPrimitive?.content ?: "",
        )
        require(m.format == "sqlite-fts4-gzip") { "Neznámý formát knihovny: ${m.format}" }
        require(m.parts.isNotEmpty()) { "Manifest bez částí." }
        require(m.parts.all { it.name.matches(Regex("[A-Za-z0-9._-]+")) }) { "Neplatný název části." }
        return m
    }

    /** Manifest z GitHub Release; null = knihovna zatím nebyla sestavena (404) → použije se postupné stahování. */
    fun fetchManifest(spec: LibrarySpec): PrebuiltManifest? {
        var url = manifestUrl(spec)
        repeat(6) {
            val c = Http.open(url, connectMs = 20_000, readMs = 60_000)
            c.instanceFollowRedirects = false
            try {
                when (val code = c.responseCode) {
                    in 300..399 -> url = java.net.URL(java.net.URL(url), c.getHeaderField("Location") ?: return null).toString()
                    200 -> return parse(c.inputStream.bufferedReader().readText())
                    404 -> return null
                    else -> throw IOException("HTTP $code při čtení manifestu knihovny")
                }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /** Potřebné místo: všechny části + hotová databáze + rezerva (části se během rozbalování mažou). */
    fun requiredBytes(m: PrebuiltManifest) = m.downloadBytes + m.dbSize + 300_000_000L

    data class Step(val phase: String, val bytes: Long, val total: Long, val bytesPerSec: Long = 0)

    /**
     * Stáhne části (s navazováním a SHA-256 každé z nich), pak je proudově rozbalí do [target]
     * a ověří SHA-256 výsledné databáze. Spotřebovanou část hned maže, aby se šetřilo místo.
     */
    fun install(
        spec: LibrarySpec,
        m: PrebuiltManifest,
        workDir: File,
        target: File,
        cancel: CancelToken,
        downloader: Downloader = Downloader(),
        urlOf: (PrebuiltPart) -> String = { partUrl(spec, it) },
        onStep: (Step) -> Unit,
    ): File {
        workDir.mkdirs()
        val files = ArrayList<File>()
        var before = 0L
        for ((i, p) in m.parts.withIndex()) {
            cancel.check()
            val f = File(workDir, p.name)
            val ps = BrainSpec(
                id = p.name, name = p.name, family = "library", description = "", urls = listOf(urlOf(p)),
                sizeBytes = p.size, sha256 = p.sha256, minRamGb = 0, contextTokens = 0, license = m.license, thinking = false, kind = "library",
            )
            val base = before
            downloader.download(ps, f, cancel) { pr ->
                val phase = if (pr.phase.startsWith("Stahuji")) "Stahuji část ${i + 1}/${m.parts.size}" else pr.phase
                onStep(Step(phase, base + pr.bytes, m.downloadBytes, pr.bytesPerSec))
            }
            before += p.size
            files += f
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.parentFile?.mkdirs()
        val sha = MessageDigest.getInstance("SHA-256")
        val parts = files.iterator()
        var previous: File? = null
        val stream = SequenceInputStream(object : Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean {
                if (!parts.hasNext()) previous?.delete()
                return parts.hasNext()
            }
            override fun nextElement(): InputStream {
                previous?.delete() // předchozí část je dočtená → pryč
                val f = parts.next()
                previous = f
                return FileInputStream(f)
            }
        })
        var written = 0L
        var lastTick = 0L
        GZIPInputStream(stream, 1 shl 16).use { gz ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 18)
                while (true) {
                    cancel.check()
                    val n = gz.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 700) { onStep(Step("Rozbaluji databázi", written, m.dbSize)); lastTick = now }
                }
                out.fd.sync()
            }
        }
        files.forEach { it.delete() }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (written != m.dbSize || !hex.equals(m.dbSha256, ignoreCase = true)) {
            tmp.delete()
            throw IOException("Rozbalená databáze nesouhlasí (velikost nebo SHA-256) – zkus stažení znovu.")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Nelze přejmenovat databázi knihovny.")
        return target
    }
}
