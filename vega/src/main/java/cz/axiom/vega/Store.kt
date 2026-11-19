package cz.axiom.vega

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.FileProvider
import cz.axiom.vega.core.Kv
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Kv nad soubory (files/vega/kv/…) s atomickým zápisem – přežije pád uprostřed zápisu. */
class FileKv(private val root: File) : Kv {
    init { root.mkdirs() }
    private fun f(key: String): File {
        require(!key.contains("..")) { "Neplatný klíč" }
        return File(root, key.replace(Regex("[^A-Za-z0-9/_.-]"), "_"))
    }
    @Synchronized override fun read(key: String) = f(key).takeIf { it.isFile }?.readText()
    @Synchronized override fun write(key: String, value: String) {
        val t = f(key); t.parentFile?.mkdirs()
        val tmp = File(t.parentFile, ".${t.name}.tmp"); tmp.writeText(value)
        if (!tmp.renameTo(t)) { t.writeText(value); tmp.delete() }
    }
    @Synchronized override fun delete(key: String) { f(key).delete() }
    @Synchronized override fun keys(prefix: String): List<String> {
        val dir = f(prefix).let { if (prefix.endsWith("/")) it else it.parentFile } ?: return emptyList()
        return dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(root).path.replace('\\', '/') }.filter { it.startsWith(prefix) }.toList()
    }
}

/** Nastavení: rozpočet v Kč (výchozí 0 = žádná automatická placená spotřeba), iniciativa, air-gap. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("vega", Context.MODE_PRIVATE)

    var budgetKc: Int get() = p.getInt("budgetKc", 0); set(v) = p.edit().putInt("budgetKc", v.coerceIn(0, 100_000)).apply()
    var usdKc: Float get() = p.getFloat("usdKc", 23.5f); set(v) = p.edit().putFloat("usdKc", v).apply()
    /** Iniciativa a připomínky (návrh dalšího kroku u projektu, návrhy preferencí). */
    var initiative: Boolean get() = p.getBoolean("initiative", true); set(v) = p.edit().putBoolean("initiative", v).apply()
    var airgap: Boolean get() = p.getBoolean("airgap", false); set(v) { p.edit().putBoolean("airgap", v).apply(); cz.axiom.omega.core.llm.Http.airgap = v }
    var activeProject: String? get() = p.getString("activeProject", null); set(v) = p.edit().putString("activeProject", v).apply()
    var pinned: List<String>
        get() = p.getString("pinned", null)?.split(',')?.filter { it.isNotBlank() } ?: listOf("statement.process", "csv.inspect", "text.diff", "hash", "log.crash", "json.inspect")
        set(v) = p.edit().putString("pinned", v.joinToString(",")).apply()
    var claudeModel: String get() = p.getString("claudeModel", "claude-sonnet-5-5")!!; set(v) = p.edit().putString("claudeModel", v).apply()
    /** Poskytovatel, kterému uživatel povolil placenou spotřebu v rámci rozpočtu (jinak jen schválení po náhledu). */
    fun allowed(provider: String) = p.getBoolean("allow.$provider", false)
    fun setAllowed(provider: String, v: Boolean) = p.edit().putBoolean("allow.$provider", v).apply()

    private fun month() = java.time.YearMonth.now().toString()
    fun spentUsd(provider: String) = p.getFloat("spent.${month()}.$provider", 0f).toDouble()
    fun addSpent(provider: String, usd: Double) = p.edit().putFloat("spent.${month()}.$provider", (spentUsd(provider) + usd).toFloat()).apply()
    fun spentKcTotal() = listOf("claude", "kimi", "deepseek", "chutes", "nanogpt", "ollama", "synthetic").sumOf { spentUsd(it) } * usdKc
}

/** API klíče: AES-256-GCM s klíčem v Android Keystore; do WebView se nikdy nevrací. */
class Vault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vega-vault", Context.MODE_PRIVATE)

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    fun save(name: String, secret: String) {
        require(name.matches(Regex("[a-z0-9_]{2,32}")))
        if (secret.isBlank()) { remove(name); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key("vega-$name"))
        val data = c.doFinal(secret.trim().toByteArray())
        prefs.edit().putString("$name.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).putString("$name.data", Base64.encodeToString(data, Base64.NO_WRAP)).commit()
    }

    fun read(name: String): String? {
        val data = prefs.getString("$name.data", null) ?: return null
        val iv = prefs.getString("$name.iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key("vega-$name"), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) { remove(name); null }
    }

    fun has(name: String) = prefs.contains("$name.data")
    fun remove(name: String) { prefs.edit().remove("$name.iv").remove("$name.data").apply() }
}

/** Výstupy (CSV, DOCX, PDF, ZIP): Stažené/VEGA + soukromá kopie pro sdílení přes FileProvider. */
class Out(private val context: Context) {
    private val dir = File(context.filesDir, "vega/out").apply { mkdirs() }

    data class Saved(val name: String, val mime: String, val local: File, val public: Uri?)

    fun save(name: String, mime: String, bytes: ByteArray): Saved {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val local = File(dir, safe).apply { writeBytes(bytes) }
        val pub = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = context.contentResolver
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VEGA"); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                cr.openOutputStream(uri)!!.use { it.write(bytes) }
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            } else null
        }.getOrNull()
        return Saved(safe, mime, local, pub)
    }

    fun uri(local: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", local)
    fun file(name: String) = File(dir, name).takeIf { it.exists() && it.parentFile == dir }
}
