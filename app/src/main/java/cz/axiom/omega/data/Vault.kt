package cz.axiom.omega.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API klíče šifrované AES-256-GCM klíčem v Android Keystore (klíč nikdy neopustí hardware).
 * Převzato z AXIOM 2.1 (to bylo dobře), doplněno: klíč se nikdy nevrací do WebView,
 * poškozený záznam se smaže místo pádu, jeden soubor místo vault-* pro každého poskytovatele.
 */
class Vault(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(name: String, secret: String) {
        require(name.matches(Regex("[a-z0-9_]{2,32}")))
        if (secret.isBlank()) { remove(name); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key("omega-$name"))
        val data = cipher.doFinal(secret.trim().toByteArray(Charsets.UTF_8))
        val ok = prefs.edit()
            .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$name.data", Base64.encodeToString(data, Base64.NO_WRAP))
            .commit()
        check(ok) { "Uložení klíče selhalo." }
    }

    fun read(name: String): String? {
        val data = prefs.getString("$name.data", null) ?: return null
        val iv = prefs.getString("$name.iv", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key("omega-$name"), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore mohl být zneplatněn (změna zámku obrazovky, obnova zálohy) → klíč zahodit, ne spadnout
            remove(name)
            null
        }
    }

    fun has(name: String) = prefs.contains("$name.data")

    fun remove(name: String) {
        prefs.edit().remove("$name.iv").remove("$name.data").apply()
    }

    companion object {
        const val KIMI = "kimi"
        const val CLAUDE = "claude"
        const val SERVER = "server"
        const val NODE = "node"
        const val HF = "hf"
        const val GROQ = "groq"
        const val CEREBRAS = "cerebras"
        const val OPENROUTER = "openrouter"
        /** Všechny klíče (nouzové smazání, „zapomenout klíče“). */
        val ALL = listOf(KIMI, CLAUDE, SERVER, NODE, HF, GROQ, CEREBRAS, OPENROUTER)
    }
}
