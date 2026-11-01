package cz.axiom.omega.data

import android.content.Context
import android.net.Uri
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.core.packs.PackSpec
import cz.axiom.omega.core.packs.Packs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Lokální offline databáze:
 *  • znalostní balíčky (vestavěné v APK → fungují offline hned po instalaci; nové verze z GitHubu, ověřené SHA-256),
 *  • záloha a obnova celé databáze (vlákna, paměť, znalosti) do souboru v telefonu.
 * API klíče se nezálohují – zůstávají v Android Keystore.
 */
class PackManager(private val context: Context) {

    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val cachedCatalog = File(dir, "catalog.json")

    private fun bundled(): List<PackSpec> = Packs.parseCatalog(context.assets.open("packs/catalog.json").bufferedReader().use { it.readText() })

    fun catalog(): List<PackSpec> {
        val remote = runCatching { if (cachedCatalog.exists()) Packs.parseCatalog(cachedCatalog.readText()) else null }.getOrNull()
        val local = bundled()
        if (remote == null) return local
        // sjednocení: novější verze vyhrává, balíčky jen v jednom zdroji zůstávají
        val map = LinkedHashMap<String, PackSpec>()
        (local + remote).forEach { p -> val cur = map[p.id]; if (cur == null || p.version > cur.version) map[p.id] = p }
        return map.values.toList()
    }

    /** Při startu: nainstaluje/aktualizuje vestavěné balíčky označené autoInstall (bez sítě). */
    fun ensureAutoInstalled() {
        val db = Db.get(context)
        val installed = db.installedPacks()
        for (p in bundled().filter { it.autoInstall }) {
            if ((installed[p.id] ?: 0) >= p.version) continue
            runCatching { install(p) }
        }
    }

    fun install(id: String) {
        val p = catalog().firstOrNull { it.id == id } ?: throw IOException("Neznámý balíček $id")
        install(p)
    }

    private fun install(p: PackSpec) {
        val bytes = bytesFor(p)
        val sections = Packs.sections(String(bytes, Charsets.UTF_8))
        require(sections.isNotEmpty()) { "Balíček ${p.name} je prázdný." }
        Db.get(context).installPack(p.id, p.name, p.version, sections.map { it.title to it.body })
    }

    /** Vestavěný soubor, pokud sedí SHA-256; jinak stažený z repozitáře (také ověřený). */
    private fun bytesFor(p: PackSpec): ByteArray {
        runCatching { context.assets.open("packs/${p.file}").use { it.readBytes() } }.getOrNull()?.let { if (Packs.verify(p, it)) return it }
        val local = File(dir, p.file)
        if (local.exists()) local.readBytes().let { if (Packs.verify(p, it)) return it }
        val c = Http.open(Packs.REMOTE_BASE + p.file, readMs = 30_000)
        try {
            if (c.responseCode != 200) throw IOException("Balíček ${p.name}: HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { it.readBytes() }
            if (!Packs.verify(p, bytes)) throw IOException("Balíček ${p.name}: kontrolní součet SHA-256 nesouhlasí.")
            local.writeBytes(bytes)
            return bytes
        } finally {
            c.disconnect()
        }
    }

    fun remove(id: String) = Db.get(context).removePack(id)

    fun refreshCatalog(): Int {
        val c = Http.open(Packs.REMOTE_BASE + "catalog.json", readMs = 20_000)
        try {
            if (c.responseCode != 200) throw IOException("Katalog balíčků: HTTP ${c.responseCode}")
            val text = Http.readAll(c.inputStream, 500_000)
            val n = Packs.parseCatalog(text).size
            cachedCatalog.writeText(text)
            return n
        } finally {
            c.disconnect()
        }
    }

    fun toJson(): JSONObject {
        val db = Db.get(context)
        val installed = db.installedPacks()
        val list = JSONArray()
        for (p in catalog()) {
            val v = installed[p.id]
            list.put(
                JSONObject().put("id", p.id).put("name", p.name).put("description", p.description).put("version", p.version)
                    .put("sections", p.sections).put("kb", (p.bytes + 1023) / 1024).put("installed", v != null)
                    .put("update", v != null && v < p.version).put("auto", p.autoInstall),
            )
        }
        return JSONObject().put("packs", list).put("stats", db.stats())
    }

    // ------------------------------------------------------------------ záloha / obnova

    fun export(uri: Uri) {
        val db = Db.get(context)
        db.checkpoint()
        context.contentResolver.openOutputStream(uri, "w")?.use { out -> db.file().inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Soubor zálohy nelze vytvořit.")
    }

    fun restore(uri: Uri) {
        val tmp = File(context.cacheDir, "restore.db")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Zálohu nelze otevřít.")
        val header = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") { tmp.delete(); throw IOException("Soubor není záloha AXIOM Ω (SQLite).") }
        val target = Db.path(context)
        Db.get(context).checkpoint()
        Db.reset()
        val previous = File(target.path + ".pred-obnovou")
        target.copyTo(previous, overwrite = true)
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
        // ověření, že jde o naši databázi; jinak vrátit původní stav
        val ok = runCatching { Db.get(context).stats() }.isSuccess
        if (!ok) {
            Db.reset()
            previous.copyTo(target, overwrite = true)
            throw IOException("Záloha je poškozená nebo z jiné aplikace – původní data zůstala zachována.")
        }
    }
}
