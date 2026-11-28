package cz.axiom.omega.forge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import cz.axiom.omega.Events
import cz.axiom.omega.core.forge.ApkForge
import cz.axiom.omega.core.forge.FileSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Výroba souborů v telefonu: Stažené/AVIOR (viditelné ve Správci souborů), PDF přes PdfDocument,
 * APK přes [ApkForge] s podpisovým klíčem v Android Keystore (klíč nikdy neopustí telefon).
 * Seznam vytvořených souborů je v files/forge/index.json.
 */
class AndroidForge(private val context: Context) : FileSink {

    private val dir = File(context.filesDir, "forge").apply { mkdirs() }
    private val index = File(dir, "index.json")

    /** Soubory vytvořené během aktuální úlohy (pro kartu u odpovědi). */
    val session = mutableListOf<JSONObject>()

    override fun save(name: String, mime: String, bytes: ByteArray): String {
        require(bytes.size <= 60_000_000) { "Soubor je příliš velký." }
        val uri = publish(name, mime, bytes)
        val e = JSONObject().put("name", name).put("mime", mime).put("size", bytes.size).put("uri", uri.toString()).put("time", System.currentTimeMillis())
        record(e)
        return "Soubor $name (${kb(bytes.size)}) uložen do složky Stažené/AVIOR."
    }

    override fun apk(appName: String, files: Map<String, ByteArray>): String {
        val (key, cert) = signingKey()
        val label = ApkForge.cleanLabel(appName)
        val fileName = cz.axiom.omega.core.forge.FileForge.safeName(
            java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "aplikace" } + ".apk", "apk",
        )
        val apkDir = File(dir, "apk").apply { mkdirs() }
        val out = File(apkDir, fileName)
        val r = context.assets.open("forge/shell.apk").use { ApkForge.build(it, label, files, key, listOf(cert), out, File(context.cacheDir, "forge")) }
        val bytes = out.readBytes()
        val uri = runCatching { publish(fileName, "application/vnd.android.package-archive", bytes) }.getOrNull()
        record(
            JSONObject().put("name", fileName).put("mime", "application/vnd.android.package-archive").put("size", bytes.size)
                .put("uri", uri?.toString() ?: "").put("local", out.path).put("pkg", r.packageName).put("label", r.label).put("time", System.currentTimeMillis()),
        )
        return "APK „${r.label}“ vyrobeno (${kb(bytes.size)}, balíček ${r.packageName}, podepsáno v2+v3). Uloženo do Stažené/AVIOR/$fileName. " +
            "Instalace: tlačítko Instalovat u odpovědi nebo v Agenti → Vytvořené soubory (Android se jednou zeptá na povolení instalace z AVIORu)."
    }

    override fun pdf(title: String, text: String): ByteArray {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 50
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SERIF }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = Color.BLACK; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
        val clean = text.replace("\r", "").replace(Regex("(?m)^#{1,6}\\s*"), "").replace("**", "")
        val layout = StaticLayout.Builder.obtain(clean, 0, clean.length, body, w - 2 * margin).setLineSpacing(2f, 1.15f).build()
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, head, w - 2 * margin).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        var line = 0
        var pageNo = 0
        while (line < layout.lineCount || pageNo == 0) {
            pageNo++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page.canvas
            var top = margin.toFloat()
            if (pageNo == 1 && title.isNotBlank()) {
                c.save(); c.translate(margin.toFloat(), top); titleLayout.draw(c); c.restore()
                top += titleLayout.height + 18
            }
            val avail = h - margin - 20 - top
            val startY = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startY <= avail) end++
            if (end == line && line < layout.lineCount) end = line + 1
            if (line < layout.lineCount) {
                c.save()
                c.translate(margin.toFloat(), top - startY)
                c.clipRect(0f, startY.toFloat(), (w - 2 * margin).toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(c)
                c.restore()
            }
            c.drawText("AVIOR · strana $pageNo", margin.toFloat(), (h - margin / 2).toFloat(), foot)
            doc.finishPage(page)
            line = end
            if (layout.lineCount == 0) break
        }
        val o = java.io.ByteArrayOutputStream()
        doc.writeTo(o); doc.close()
        return o.toByteArray()
    }

    // ------------------------------------------------------------------ seznam a akce

    @Synchronized
    fun list(): JSONArray = runCatching { JSONArray(index.readText()) }.getOrDefault(JSONArray())

    @Synchronized
    private fun record(e: JSONObject) {
        val arr = list()
        val out = JSONArray().put(e)
        for (i in 0 until minOf(arr.length(), 199)) out.put(arr.getJSONObject(i))
        index.writeText(out.toString())
        synchronized(session) { session += e }
        Events.emit("file", e)
    }

    fun entry(i: Int): JSONObject? = list().optJSONObject(i)

    fun uriOf(e: JSONObject): Uri {
        val local = e.optString("local")
        if (local.isNotBlank() && File(local).exists()) return FileProvider.getUriForFile(context, context.packageName + ".files", File(local))
        return Uri.parse(e.getString("uri"))
    }

    fun openIntent(e: JSONObject): Intent {
        val apk = e.optString("mime") == "application/vnd.android.package-archive"
        return Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(e), e.optString("mime", "*/*"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { if (apk) it.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true) }
    }

    fun shareIntent(e: JSONObject): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(e.optString("mime", "*/*")).putExtra(Intent.EXTRA_STREAM, uriOf(e)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        e.optString("name"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ nízká úroveň

    private fun publish(name: String, mime: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AVIOR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Nelze vytvořit soubor ve Stažených.")
            try {
                cr.openOutputStream(uri)!!.use { it.write(bytes) }
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null); throw e
            }
            return uri
        }
        // Android 8–9: složka aplikace (bez oprávnění k úložišti)
        val d = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "AVIOR").apply { mkdirs() }
        val f = uniqueFile(d, name)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    private fun uniqueFile(d: File, name: String): File {
        var f = File(d, name); var n = 2
        while (f.exists()) { f = File(d, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')); n++ }
        return f
    }

    private fun signingKey(): Pair<java.security.PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val from = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val to = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=AVIOR Omega App Forge, O=AVIOR"))
                        .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                        .setCertificateNotBefore(from.time)
                        .setCertificateNotAfter(to.time)
                        .build(),
                )
                generateKeyPair()
            }
        }
        val e = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return e.privateKey to (e.certificate as X509Certificate)
    }

    private fun kb(n: Int) = if (n >= 1_048_576) "%.1f MB".format(n / 1_048_576.0) else "${(n + 1023) / 1024} kB"

    companion object { const val ALIAS = "axiom-app-forge" }

    /** Nouzové smazání: soubory, které AVIOR vytvořil v soukromé složce aplikace. */
    fun wipe() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "AVIOR").deleteRecursively() }
        synchronized(session) { session.clear() }
    }

}
