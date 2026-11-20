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
        val tmp = File(t.parentFile, ".${t.name}.tmp")
        try { tmp.writeText(value) } catch (e: java.io.IOException) { tmp.delete(); throw java.io.IOException(if (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space")) "V telefonu došlo místo – uvolni úložiště a zkus to znovu." else (e.message ?: "zápis selhal"), e) }
        if (!tmp.renameTo(t)) { tmp.delete(); throw java.io.IOException("Soubor nejde uložit (přejmenování selhalo).") } // nikdy nepřepisovat cíl napůl
    }
    @Synchronized override fun delete(key: String) { f(key).delete() }
    override fun modified(key: String) = f(key).lastModified()
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
    var airgap: Boolean get() = p.getBoolean("airgap", false); set(v) { p.edit().putBoolean("airgap", v).apply(); cz.axiom.vega.net.Http.airgap = v }
    var activeProject: String? get() = p.getString("activeProject", null); set(v) = p.edit().putString("activeProject", v).apply()
    var pinned: List<String>
        get() = p.getString("pinned", null)?.split(',')?.filter { it.isNotBlank() } ?: listOf("statement.process", "csv.inspect", "text.diff", "hash", "log.crash", "json.inspect")
        set(v) = p.edit().putString("pinned", v.joinToString(",")).apply()
    var claudeModel: String get() = p.getString("claudeModel", "claude-sonnet-5-5")!!; set(v) = p.edit().putString("claudeModel", v).apply()
    /** Poskytovatel, kterému uživatel povolil placenou spotřebu v rámci rozpočtu (jinak jen schválení po náhledu). */
    fun allowed(provider: String) = p.getBoolean("allow.$provider", false)

    // ---- VLASTNÍ MODELY (OpenAI-kompatibilní server uživatele: llama.cpp server, Ollama, vLLM…)
    var ownUrl: String get() = p.getString("ownUrl", "")!!; set(v) = p.edit().putString("ownUrl", v.trim().trimEnd('/')).apply()
    var ownModel: String get() = p.getString("ownModel", "")!!; set(v) = p.edit().putString("ownModel", v.trim()).apply()
    /** Režim „Pouze vlastní modely“: žádné komerční modelové API pro celý požadavek (i pomocné kroky a opakování). */
    var ownOnly: Boolean get() = p.getBoolean("ownOnly", false); set(v) = p.edit().putBoolean("ownOnly", v).apply()
    /** Veřejný web (rešerše, čtení URL) – samostatná volba, nezávislá na výběru modelů. */
    var webAllowed: Boolean get() = p.getBoolean("webAllowed", true); set(v) = p.edit().putBoolean("webAllowed", v).apply()
    // ---- SAMOÚPRAVY (vrstva C): repozitář se zdrojem VEGY, pracovní větev a samostatné zlepšování z deníku slabin
    var ghRepo: String get() = p.getString("ghRepo", "daidken5-droid/Moje-axiom-omega")!!; set(v) = p.edit().putString("ghRepo", v.trim()).apply()
    var ghBase: String get() = p.getString("ghBase", "claude/reverse-engineer-ai-app-o5jhyd")!!; set(v) = p.edit().putString("ghBase", v.trim()).apply()
    var autoImprove: Boolean get() = p.getBoolean("autoImprove", false); set(v) = p.edit().putBoolean("autoImprove", v).apply()
    /** Jak oslovovat uživatele na úvodní obrazovce (např. „Lukáši“). */
    var userName: String get() = p.getString("userName", "")!!; set(v) = p.edit().putString("userName", v.trim().take(30)).apply()

    /** Počítadlo požadavků na modelová API (po poskytovatelích) – důkaz „0 komerčních“ v režimu vlastních modelů. */
    @Synchronized fun countRequest(provider: String) { val k = "req.$provider"; p.edit().putLong(k, p.getLong(k, 0L) + 1).commit() }
    fun requests(provider: String) = p.getLong("req.$provider", 0L)
    fun commercialRequests() = p.all.filterKeys { it.startsWith("req.") && it != "req.own" }.values.sumOf { (it as? Long) ?: 0L }
    /** Rozpočet konkrétního poskytovatele (Kč/měsíc); bez vlastního platí společný [budgetKc]. Kredit Pro má vlastní. */
    fun budgetKc(provider: String): Int = if (p.contains("budget.$provider")) p.getInt("budget.$provider", 0) else budgetKc
    fun setBudgetKc(provider: String, kc: Int) = p.edit().putInt("budget.$provider", kc.coerceIn(0, 100_000)).apply()
    fun hasOwnBudget(provider: String) = p.contains("budget.$provider")
    /** Ověřený model poskytovatele (z „Ověřit“ nebo ze samoopravy) – příště bez zbytečného pokusu o nedostupný model. */
    fun model(provider: String): String? = p.getString("model.$provider", null)
    fun setModel(provider: String, model: String) = p.edit().putString("model.$provider", model).apply()
    fun setAllowed(provider: String, v: Boolean) = p.edit().putBoolean("allow.$provider", v).apply()

    /** Měsíc v UTC (změna časového pásma ani přetočení hodin uprostřed měsíce útratu nevynuluje dřív). */
    private fun month() = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString()
    /** Útrata v mikro-USD (Long, atomicky) – Float ztrácel přesnost a souběžný zápis by se mohl přepsat. */
    @Synchronized fun spentUsd(provider: String) = p.getLong("spentu.${month()}.$provider", 0L) / 1e6
    @Synchronized fun addSpent(provider: String, usd: Double) {
        val k = "spentu.${month()}.$provider"
        p.edit().putLong(k, (p.getLong(k, 0L) + Math.round(usd * 1e6)).coerceAtLeast(0L)).commit()
    }
    /** STÍNOVÉ MĚŘENÍ bezplatného klíče Gemini: kolik by to stálo, KDYBY byl klíč z placeného projektu (varování). */
    @Synchronized fun shadowUsd() = p.getLong("shadow.${month()}", 0L) / 1e6
    @Synchronized fun addShadow(usd: Double) { val k = "shadow.${month()}"; p.edit().putLong(k, p.getLong(k, 0L) + Math.round(usd * 1e6)).apply() }
    var shadowWarned: String get() = p.getString("shadowWarned", "")!!; set(v) = p.edit().putString("shadowWarned", v).apply()
    fun monthKey() = month()
    /** Uživatel potvrdil, že klíč ve slotu „zdarma“ je z projektu BEZ fakturace. */
    var geminiFreeConfirmed: Boolean get() = p.getBoolean("geminiFreeConfirmed", false); set(v) = p.edit().putBoolean("geminiFreeConfirmed", v).apply()
    fun spentKcTotal() = listOf("claude", "kimi", "deepseek", "chutes", "nanogpt", "ollama", "synthetic", "geminipro").sumOf { spentUsd(it) } * usdKc
}

/** API klíče: AES-256-GCM s klíčem v Android Keystore; do WebView se nikdy nevrací. */
open class Vault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vega-vault", Context.MODE_PRIVATE)

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    open fun save(name: String, secret: String) {
        require(name.matches(Regex("[a-z0-9_]{2,32}")))
        if (secret.isBlank()) { remove(name); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key("vega-$name"))
        val data = c.doFinal(secret.trim().toByteArray())
        prefs.edit().putString("$name.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).putString("$name.data", Base64.encodeToString(data, Base64.NO_WRAP)).commit()
    }

    open fun read(name: String): String? {
        val data = prefs.getString("$name.data", null) ?: return null
        val iv = prefs.getString("$name.iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key("vega-$name"), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) { if (dropOn(e)) remove(name); null }
    }

    open fun has(name: String) = prefs.contains("$name.data")

    companion object {
        /** Smazat uložený klíč jen když je trvale nečitelný (poškozená data, zneplatněný klíč) – ne při dočasné chybě Keystore. */
        fun dropOn(e: Throwable): Boolean = e is javax.crypto.AEADBadTagException || e is android.security.keystore.KeyPermanentlyInvalidatedException ||
            e is IllegalArgumentException // poškozený Base64
    }
    open fun remove(name: String) { prefs.edit().remove("$name.iv").remove("$name.data").apply() }
}

/** Výstupy (CSV, DOCX, PDF, ZIP): Stažené/VEGA + soukromá kopie pro sdílení přes FileProvider. */
class Out(private val context: Context) {
    /** Složka výstupů (testy ji můžou přesměrovat, aby vyvolaly skutečnou chybu zápisu). */
    var dir: File = File(context.filesDir, "vega/out").apply { mkdirs() }

    data class Saved(val name: String, val mime: String, val local: File, val public: Uri?)

    fun save(name: String, mime: String, bytes: ByteArray): Saved {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        // ATOMICKY: dočasný soubor → přejmenování; při chybě nezůstane rozepsaný soubor a chyba se nahlásí
        dir.mkdirs()
        val local = File(dir, safe)
        val tmp = File(dir, ".$safe.part")
        try {
            tmp.writeBytes(bytes)
            if (tmp.length() != bytes.size.toLong()) throw java.io.IOException("zapsáno ${tmp.length()} z ${bytes.size} B")
            if (!tmp.renameTo(local)) { local.delete(); if (!tmp.renameTo(local)) throw java.io.IOException("soubor nejde dokončit (přejmenování selhalo)") }
        } catch (e: Exception) { runCatching { tmp.delete() }; throw java.io.IOException("Zápis „$safe“ selhal: ${e.message ?: e.javaClass.simpleName}", e) }
        val pub = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = context.contentResolver
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VEGA"); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                try {
                    cr.openOutputStream(uri)!!.use { it.write(bytes) }
                    cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; throw e } // nenechat nedokončený záznam ve Stažených
                uri
            } else null
        }.getOrNull()
        return Saved(safe, mime, local, pub)
    }

    fun uri(local: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", local)
    fun file(name: String) = File(dir, name).takeIf { it.exists() && it.parentFile == dir }
}
