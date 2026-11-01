package cz.axiom.omega.core.forge

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * APK KOVÁRNA – AXIOM Ω vyrobí skutečnou instalovatelnou Android aplikaci přímo v telefonu.
 *
 * Princip (bez kompilátoru, bez SDK): v APK AXIOMu je malá předpřipravená šablona (modul :shell –
 * WebView aplikace bez knihoven). Kovárna:
 *  1. přepíše v binárním manifestu balíček a název aplikace (řetězce stejné délky → struktura zůstane platná),
 *  2. vloží HTML/CSS/JS aplikace do assets/www,
 *  3. podepíše APK schématem v2+v3 (apksig) klíčem z Android Keystore.
 * Výsledek se dá nainstalovat; stejný název = stejný balíček → nová verze se nainstaluje jako aktualizace.
 */
object ApkForge {
    const val PACKAGE_PLACEHOLDER = "cz.axiom.gen.app0000000000"
    const val LABEL_PLACEHOLDER = "AXIOM_APP_LABEL_PLACEHOLDER_00000000000"
    private const val PKG_PREFIX = "cz.axiom.gen.app"
    private const val MAX_FILES_BYTES = 20_000_000

    data class Result(val file: File, val packageName: String, val label: String, val files: Int)

    /** Deterministický balíček z názvu: přegenerovaná aplikace stejného jména = aktualizace. */
    fun packageFor(appName: String): String {
        val norm = java.text.Normalizer.normalize(appName.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val h = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray())
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val tail = (0 until PACKAGE_PLACEHOLDER.length - PKG_PREFIX.length).map { alphabet[(h[it].toInt() and 0xff) % alphabet.length] }.joinToString("")
        return PKG_PREFIX + tail
    }

    fun cleanLabel(appName: String): String =
        appName.replace(Regex("[\\p{Cntrl}<>\"&]"), "").trim().take(LABEL_PLACEHOLDER.length).ifBlank { "AXIOM App" }

    fun build(
        template: InputStream,
        appName: String,
        files: Map<String, ByteArray>,
        key: PrivateKey,
        certs: List<X509Certificate>,
        out: File,
        workDir: File = out.parentFile ?: File("."),
    ): Result {
        require(files.containsKey("index.html")) { "Aplikace musí mít index.html." }
        require(files.values.sumOf { it.size } <= MAX_FILES_BYTES) { "Soubory aplikace jsou příliš velké (max. 20 MB)." }
        val clean = files.mapKeys { (k, _) -> safePath(k) }
        val pkg = packageFor(appName)
        val label = cleanLabel(appName)
        workDir.mkdirs()
        val unsigned = File(workDir, out.name + ".unsigned")
        ZipInputStream(template).use { zin ->
            ZipOutputStream(unsigned.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory || name.startsWith("assets/www/") || isSignatureFile(name)) continue
                    var bytes = zin.readBytes()
                    if (name == "AndroidManifest.xml") {
                        bytes = BinaryXml.patch(bytes, mapOf(PACKAGE_PLACEHOLDER to pkg, LABEL_PLACEHOLDER to label))
                    }
                    put(zout, name, bytes, stored = name == "resources.arsc")
                }
                for ((path, bytes) in clean) put(zout, "assets/www/$path", bytes, stored = false)
            }
        }
        out.parentFile?.mkdirs()
        val signer = ApkSigner.SignerConfig.Builder("AXIOM", key, certs).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        unsigned.delete()
        return Result(out, pkg, label, clean.size)
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val n = name.uppercase()
        return n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
    }

    /** Relativní cesta bez „..“, absolutních cest a podivných znaků. */
    fun safePath(p: String): String {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "Neplatná cesta souboru: $p" }
        require(parts.all { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }) { "Neplatný název souboru: $p (jen písmena bez diakritiky, čísla, . _ -)" }
        return parts.joinToString("/")
    }

    private fun put(zout: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        e.time = 315_532_800_000L // 1. 1. 1980 – reprodukovatelné sestavení
        if (stored) {
            val crc = CRC32().apply { update(bytes) }
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong(); e.crc = crc.value
        }
        zout.putNextEntry(e)
        zout.write(bytes)
        zout.closeEntry()
    }
}

/**
 * Minimální úprava binárního Android XML (AXML): nahradí řetězce ve string poolu jinými řetězci
 * STEJNÉ délky (kratší se doplní mezerami). Funguje pro UTF-16 i UTF-8 pool.
 */
object BinaryXml {
    fun patch(src: ByteArray, replacements: Map<String, String>): ByteArray {
        val b = src.copyOf()
        val utf8 = isUtf8Pool(b)
        for ((from, to0) in replacements) {
            require(to0.length <= from.length) { "Náhrada „$to0“ je delší než zástupný řetězec." }
            val to = to0.padEnd(from.length, ' ')
            var hits = 0
            if (!utf8) {
                val f = from.toByteArray(Charsets.UTF_16LE)
                val t = to.toByteArray(Charsets.UTF_16LE)
                hits += replaceAll(b, f) { at -> System.arraycopy(t, 0, b, at, t.size) }
            } else {
                val f = from.toByteArray(Charsets.UTF_8)
                // UTF-8 pool: [délka ve znacích][délka v bajtech][data]; bajtová délka musí zůstat stejná
                var t = to0.toByteArray(Charsets.UTF_8)
                var text = to0
                while (t.size > f.size) { text = text.dropLast(1); t = text.toByteArray(Charsets.UTF_8) }
                val pad = f.size - t.size
                val padded = t + ByteArray(pad) { ' '.code.toByte() }
                val chars = text.length + pad
                hits += replaceAll(b, f) { at ->
                    System.arraycopy(padded, 0, b, at, padded.size)
                    if (at >= 2 && (b[at - 2].toInt() and 0xff) == from.length && (b[at - 1].toInt() and 0xff) == f.size && chars < 0x80) {
                        b[at - 2] = chars.toByte()
                    }
                }
            }
            require(hits > 0) { "Šablona APK neobsahuje zástupný řetězec $from." }
        }
        return b
    }

    private fun isUtf8Pool(b: ByteArray): Boolean {
        // hlavička XML (8 B), pak ResStringPool_header: type(2) headerSize(2) size(4) stringCount(4) styleCount(4) flags(4)
        if (b.size < 32) return false
        val flags = (b[24].toInt() and 0xff) or ((b[25].toInt() and 0xff) shl 8) or ((b[26].toInt() and 0xff) shl 16) or ((b[27].toInt() and 0xff) shl 24)
        return flags and 0x100 != 0
    }

    private fun replaceAll(b: ByteArray, pattern: ByteArray, onHit: (Int) -> Unit): Int {
        var n = 0
        var i = 0
        outer@ while (i <= b.size - pattern.size) {
            for (j in pattern.indices) if (b[i + j] != pattern[j]) { i++; continue@outer }
            onHit(i); n++; i += pattern.size
        }
        return n
    }
}

/** Pomocník pro testy a CLI: obsah zipu jako mapa. */
internal fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val m = LinkedHashMap<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) m[e.name] = z.readBytes() } }
    return m
}

