package cz.axiom.omega.data

import android.content.Context
import android.util.Base64
import cz.axiom.omega.core.llm.Http
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Cloudová databáze zdarma: záloha celé lokální databáze do SOUKROMÉHO datasetu na Hugging Face
 * (účet uživatele, jeho token). API klíče se nezálohují nikdy.
 *
 * HYPERFORGE M2-D: záloha je ŠIFROVANÁ KONCOVĚ (AES-256-GCM, klíč z hesla uživatele – [BackupCrypto]).
 * Dřív gzip + base64 = čitelné pro každého s přístupem k datasetu. Stará nešifrovaná záloha (`axiom-omega-backup.b64`)
 * se NEPŘEVÁDÍ ani nemaže potichu: obnova z ní jde dál, ale s výslovným upozorněním, a nová záloha jde vedle ní.
 */
class CloudBackup(private val context: Context, private val token: String) {

    private val base = "https://huggingface.co"
    private val legacyFile = "axiom-omega-backup.b64"
    private val file = "avior-backup.e2ee"

    /** Výsledek obnovy: dočasná DB a zda šlo o starou NEŠIFROVANOU zálohu. */
    class Restored(val file: File, val legacyUnencrypted: Boolean)

    private fun req(method: String, url: String, body: ByteArray? = null, type: String = "application/json"): Pair<Int, String> {
        val c = Http.open(url, method, readMs = 120_000)
        c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        if (body != null) {
            c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body) }
        }
        try {
            val code = c.responseCode
            return code to Http.readAll(if (code in 200..299) c.inputStream else c.errorStream, 60_000_000)
        } finally {
            c.disconnect()
        }
    }

    fun whoami(): String {
        val (code, body) = req("GET", "$base/api/whoami-v2")
        if (code == 401) throw IOException("Hugging Face token je neplatný.")
        if (code != 200) throw IOException("Hugging Face: HTTP $code")
        return JSONObject(body).getString("name")
    }

    /** Zajistí soukromý dataset a vrátí jeho id (uživatel/jméno). */
    fun ensureRepo(preferred: String): String {
        val repo = preferred.ifBlank { "${whoami()}/axiom-omega-backup" }
        val (owner, name) = repo.split('/').let { it[0] to it[1] }
        val payload = JSONObject().put("type", "dataset").put("name", name).put("private", true)
        if (owner != whoami()) payload.put("organization", owner)
        val (code, body) = req("POST", "$base/api/repos/create", payload.toString().toByteArray())
        if (code !in 200..299 && code != 409) throw IOException("Nelze vytvořit dataset $repo: HTTP $code ${body.take(200)}")
        return repo
    }

    fun upload(repo: String, db: Db, passphrase: CharArray): Long {
        db.checkpoint()
        val raw = db.file().readBytes()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        val text = cz.axiom.omega.core.security.BackupCrypto.seal(gz, passphrase)
        if (text.length > 9_500_000) throw IOException("Záloha je po kompresi ${text.length / 1_000_000} MB – limit cloudu je 9,5 MB. Použij místní zálohu do souboru.")
        val content = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val ndjson = buildString {
            append(JSONObject().put("key", "header").put("value", JSONObject().put("summary", "AVIOR záloha").put("description", "")).toString()).append('\n')
            append(JSONObject().put("key", "file").put("value", JSONObject().put("content", content).put("path", file).put("encoding", "base64")).toString()).append('\n')
        }
        val (code, body) = req("POST", "$base/api/datasets/$repo/commit/main", ndjson.toByteArray(), "application/x-ndjson")
        if (code !in 200..299) throw IOException("Nahrání zálohy selhalo: HTTP $code ${body.take(200)}")
        return gz.size.toLong()
    }

    /** Stáhne zálohu a vrátí ji jako dočasný soubor SQLite (obnovu provede PackManager.restoreFile). */
    fun download(repo: String, passphrase: CharArray): Restored {
        var (code, body) = req("GET", "$base/datasets/$repo/resolve/main/$file")
        var legacy = false
        if (code == 404) { req("GET", "$base/datasets/$repo/resolve/main/$legacyFile").let { code = it.first; body = it.second }; legacy = true }
        if (code == 404) throw IOException("V datasetu $repo zatím není žádná záloha.")
        if (code != 200) throw IOException("Stažení zálohy selhalo: HTTP $code")
        val gz = if (legacy) Base64.decode(body.trim(), Base64.DEFAULT)
            else try { cz.axiom.omega.core.security.BackupCrypto.open(body, passphrase) }
                catch (e: cz.axiom.omega.core.security.BackupCrypto.BadPassphraseOrTampered) { throw IOException(e.message) }
        val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        // obnovuje se jen platná SQLite – poškozený soubor nesmí přepsat data
        if (raw.size < 16 || String(raw, 0, 15, Charsets.US_ASCII) != "SQLite format 3") throw IOException("Záloha není platná databáze – obnova zrušena, data beze změny.")
        val tmp = File(context.cacheDir, "cloud-restore.db")
        tmp.writeBytes(raw)
        return Restored(tmp, legacy)
    }
}
