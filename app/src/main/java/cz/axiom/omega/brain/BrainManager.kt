package cz.axiom.omega.brain

import android.content.Context
import android.net.Uri
import cz.axiom.omega.core.brain.BrainCatalog
import cz.axiom.omega.core.brain.BrainSpec
import cz.axiom.omega.core.brain.Downloader
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.data.Settings
import cz.axiom.omega.sys.Introspect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Správa mozků: katalog (vestavěný + z GitHubu), stažení, import vlastního GGUF, aktivace. */
class BrainManager(private val context: Context) {

    private val dir = File(context.filesDir, "brains").apply { mkdirs() }
    private val catalogCache = File(dir, "catalog.json")
    private val settings = Settings(context)

    private val customCatalog = File(dir, "custom-catalog.json")

    fun catalog(): List<BrainSpec> {
        val remote = runCatching { if (catalogCache.exists()) BrainCatalog.parse(catalogCache.readText()) else null }.getOrNull()
        val base = remote ?: BrainCatalog.parse(context.assets.open("brains/catalog.json").bufferedReader().use { it.readText() })
        return base + custom().filter { c -> base.none { it.id == c.id } }
    }

    private fun custom(): List<BrainSpec> = runCatching { if (customCatalog.exists()) BrainCatalog.parse(customCatalog.readText()) else emptyList() }.getOrDefault(emptyList())

    /** Přidá mozek z Hugging Face nebo vlastního odkazu (JSON {id,name,url,size,sha256}) do vlastního katalogu. */
    @Synchronized
    fun addCustom(json: String): BrainSpec {
        val o = org.json.JSONObject(json)
        val url = o.getString("url")
        require(url.startsWith("https://")) { "Odkaz musí začínat https://" }
        val size = o.optLong("size", 0).takeIf { it > 0 } ?: Downloader.remoteSize(url)
        require(size in 50_000_000..60_000_000_000) { "Neobvyklá velikost souboru (${Downloader.fmt(size)})." }
        val name = o.optString("name").ifBlank { url.substringAfterLast('/').substringBefore('?').removeSuffix(".gguf") }
        val id = o.optString("id").ifBlank { "url-" + name.lowercase().replace(Regex("[^a-z0-9._-]"), "-") }.take(60).trimEnd('-')
        val spec = BrainSpec(
            id = id, name = name.take(80), family = o.optString("family", "vlastní"),
            description = o.optString("description", "Vlastní mozek z odkazu $url"), urls = listOf(url), sizeBytes = size,
            sha256 = o.optString("sha256", "").lowercase(), minRamGb = kotlin.math.ceil(size * 1.35 / 1e9).toInt().coerceAtLeast(2),
            contextTokens = 4096, license = o.optString("license", "viz zdroj"), thinking = true,
            kind = if (o.optString("kind") == "embedding") "embedding" else "chat",
        )
        BrainCatalog.parse(BrainCatalog.toJson(listOf(spec))) // validace
        customCatalog.writeText(BrainCatalog.toJson(custom().filter { it.id != spec.id } + spec))
        return spec
    }

    /** Stáhne aktuální katalog z repozitáře (nové mozky bez aktualizace aplikace). Při chybě zůstane vestavěný. */
    fun refreshCatalog(): Int {
        val conn = Http.open(BrainCatalog.REMOTE_URL, readMs = 20_000)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val text = Http.readAll(conn.inputStream, 500_000)
            val parsed = BrainCatalog.parse(text) // validace před uložením
            catalogCache.writeText(text)
            return parsed.size
        } finally {
            conn.disconnect()
        }
    }

    fun fileFor(spec: BrainSpec) = File(dir, spec.fileName)

    fun customFiles(): List<File> {
        val known = catalog().map { it.fileName }.toSet()
        return dir.listFiles { f -> f.name.endsWith(".gguf") && f.name !in known }?.sortedBy { it.name } ?: emptyList()
    }

    fun isInstalled(spec: BrainSpec): Boolean = fileFor(spec).let { it.exists() && it.length() == spec.sizeBytes }

    /** Aktivní mozek: zvolený, jinak první nainstalovaný (katalog → vlastní soubory). */
    fun active(): Pair<File, BrainSpec?>? {
        val cat = catalog()
        settings.activeBrain?.let { id ->
            cat.firstOrNull { it.id == id && it.kind == "chat" }?.let { s -> if (isInstalled(s)) return fileFor(s) to s }
            File(dir, id).takeIf { it.exists() && id.endsWith(".gguf") }?.let { return it to null }
        }
        cat.firstOrNull { it.kind == "chat" && isInstalled(it) }?.let { return fileFor(it) to it }
        return customFiles().firstOrNull()?.let { it to null }
    }

    fun activate(id: String) {
        settings.activeBrain = id
    }

    fun delete(id: String) {
        val spec = catalog().firstOrNull { it.id == id }
        val f = if (spec != null) fileFor(spec) else File(dir, id).takeIf { id.endsWith(".gguf") && !id.contains('/') }
        cz.axiom.omega.engine.LocalEngine.release()
        f?.delete()
        f?.let { File(it.parentFile, it.name + ".part").delete() }
        if (settings.activeBrain == id) settings.activeBrain = null
        if (spec != null && custom().any { it.id == id }) customCatalog.writeText(BrainCatalog.toJson(custom().filter { it.id != id }))
        if (spec?.kind == "embedding") cz.axiom.omega.engine.Embedder.close()
    }

    fun download(id: String, cancel: CancelToken, onProgress: (Downloader.Progress) -> Unit): File {
        val spec = catalog().firstOrNull { it.id == id } ?: throw IOException("Neznámý mozek $id")
        val f = Downloader().download(spec, fileFor(spec), cancel, onProgress)
        if (spec.kind == "chat") settings.activeBrain = spec.id
        return f
    }

    /** Import vlastního .gguf ze souborů telefonu (SAF). Kontroluje GGUF hlavičku. */
    fun import(uri: Uri, cancel: CancelToken, onProgress: (Long) -> Unit): File {
        val name = (uri.lastPathSegment ?: "vlastni").substringAfterLast('/').substringAfterLast(':')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf")) it else "$it.gguf" }.take(80)
        val target = File(dir, "custom-$name")
        val part = File(dir, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") throw IOException("Soubor není GGUF model.")
            part.outputStream().use { out ->
                out.write(magic)
                val buf = ByteArray(1 shl 20)
                var total = 4L
                while (true) {
                    cancel.check()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        } ?: throw IOException("Soubor nelze otevřít.")
        if (!part.renameTo(target)) throw IOException("Import se nepodařilo dokončit.")
        settings.activeBrain = target.name
        return target
    }

    fun toJson(): JSONObject {
        val ram = Introspect.totalRamGb(context)
        val free = dir.usableSpace
        val cat = catalog()
        val rec = BrainCatalog.recommend(cat.filter { it.sha256.isNotEmpty() }, ram, free)
        val activePair = active()
        val list = JSONArray()
        for (s in cat) {
            val part = File(dir, s.fileName + ".part")
            list.put(
                JSONObject().put("id", s.id).put("name", s.name).put("family", s.family).put("description", s.description)
                    .put("size", s.sizeGbLabel).put("sizeBytes", s.sizeBytes).put("minRamGb", s.minRamGb).put("license", s.license)
                    .put("thinking", s.thinking).put("installed", isInstalled(s))
                    .put("partial", if (part.exists()) part.length() else 0)
                    .put("recommended", rec?.id == s.id).put("fits", BrainCatalog.fits(s, ram))
                    .put("active", activePair?.second?.id == s.id).put("url", s.urls.first()).put("sha256", s.sha256)
                    .put("kind", s.kind).put("verified", s.sha256.isNotEmpty()),
            )
        }
        for (f in customFiles()) {
            list.put(
                JSONObject().put("id", f.name).put("name", f.nameWithoutExtension.removePrefix("custom-")).put("family", "vlastní GGUF")
                    .put("description", "Importovaný model (bez ověření SHA-256).").put("size", Downloader.fmt(f.length()))
                    .put("installed", true).put("custom", true).put("active", activePair?.first == f).put("fits", true),
            )
        }
        return JSONObject().put("brains", list).put("ramGb", BrainCatalog.nominalRamGb(ram).toString()).put("freeGb", Downloader.fmt(free))
            .put("recommended", rec?.id ?: JSONObject.NULL).put("active", activePair?.second?.name ?: activePair?.first?.name ?: JSONObject.NULL)
    }
}
