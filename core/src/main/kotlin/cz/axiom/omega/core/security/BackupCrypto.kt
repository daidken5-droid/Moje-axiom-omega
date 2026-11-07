package cz.axiom.omega.core.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * HYPERFORGE M2-D: koncové (end-to-end) autentizované šifrování cloudové zálohy.
 *
 * Dřív: gzip + base64 = čitelné pro každého, kdo dataset otevře (Hugging Face, únik tokenu, chybně veřejný repozitář).
 * Teď: AES-256-GCM, klíč z hesla uživatele (PBKDF2-HMAC-SHA256, 310 000 iterací, náhodná sůl 16 B), náhodné IV 12 B.
 * Hlavička (formát, verze, parametry KDF) je součástí autentizovaných dat (AAD) → změna hlavičky = odmítnutí.
 * Heslo nikdy neopouští telefon a neukládá se; bez hesla zálohu nikdo (ani AVIOR) neobnoví.
 */
object BackupCrypto {
    const val FORMAT = "AVIOR-E2EE"
    const val VERSION = 2
    private const val ITER = 310_000
    const val MIN_PASSPHRASE = 10

    class BadPassphraseOrTampered : Exception("Záloha nejde dešifrovat: špatné heslo, nebo byla poškozena či změněna.")

    private val rnd = SecureRandom()

    private fun key(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    /** Zašifruje [plain]; výsledek: řádek hlavičky (= AAD) + řádek šifrovaného textu v base64. */
    fun seal(plain: ByteArray, passphrase: CharArray, iter: Int = ITER): String {
        require(passphrase.size >= MIN_PASSPHRASE) { "Heslo zálohy musí mít aspoň $MIN_PASSPHRASE znaků." }
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val b64 = Base64.getEncoder()
        val header = "$FORMAT;v=$VERSION;kdf=PBKDF2-HMAC-SHA256;iter=$iter;salt=${b64.encodeToString(salt)};iv=${b64.encodeToString(iv)};cipher=AES-256-GCM"
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iter), GCMParameterSpec(128, iv))
        c.updateAAD(header.toByteArray())
        return header + "\n" + b64.encodeToString(c.doFinal(plain))
    }

    fun isSealed(blob: String): Boolean = blob.trimStart().startsWith("$FORMAT;")

    /** Dešifruje; špatné heslo, změněná hlavička nebo data → [BadPassphraseOrTampered]. */
    fun open(blob: String, passphrase: CharArray): ByteArray {
        val lines = blob.trim().split('\n', limit = 2)
        require(lines.size == 2 && isSealed(lines[0])) { "Neznámý formát zálohy." }
        val header = lines[0].trim()
        val h = header.split(';').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        val v = h["v"]?.toIntOrNull() ?: throw BadPassphraseOrTampered()
        require(v <= VERSION) { "Záloha je z novější verze AVIORu (formát v$v) – aktualizuj aplikaci." }
        val iter = h["iter"]?.toIntOrNull()?.takeIf { it in 100_000..5_000_000 } ?: throw BadPassphraseOrTampered()
        val d = Base64.getDecoder()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(passphrase, d.decode(h.getValue("salt")), iter), GCMParameterSpec(128, d.decode(h.getValue("iv"))))
            c.updateAAD(header.toByteArray())
            c.doFinal(d.decode(lines[1].trim()))
        } catch (e: AEADBadTagException) { throw BadPassphraseOrTampered() }
        catch (e: IllegalArgumentException) { throw BadPassphraseOrTampered() }
        catch (e: NoSuchElementException) { throw BadPassphraseOrTampered() }
    }
}
