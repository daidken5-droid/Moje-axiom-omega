package cz.axiom.omega.core

import cz.axiom.omega.core.security.BackupCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HYPERFORGE M2-D: záloha je E2EE – obnova, špatné heslo, poškození, změna hlavičky. */
class BackupCryptoTest {
    private val pass = "správné-heslo-123".toCharArray()
    private val data = ("SQLite format 3\u0000" + "Moje tajná paměť: rodné číslo 123456/7890").toByteArray()

    @Test fun roundTrip() {
        val blob = BackupCrypto.seal(data, pass, iter = 100_000)
        assertTrue(BackupCrypto.isSealed(blob))
        assertFalse("tajná" in blob || "SQLite" in blob)          // nic čitelného
        assertContentEquals(data, BackupCrypto.open(blob, pass))
    }

    @Test fun wrongPassphrase() {
        val blob = BackupCrypto.seal(data, pass, iter = 100_000)
        assertFailsWith<BackupCrypto.BadPassphraseOrTampered> { BackupCrypto.open(blob, "jiné-heslo-456".toCharArray()) }
    }

    @Test fun tamperedDataOrHeader() {
        val blob = BackupCrypto.seal(data, pass, iter = 100_000)
        val (h, b) = blob.split('\n')
        val flipped = b.toCharArray().also { it[10] = if (it[10] == 'A') 'B' else 'A' }.concatToString()
        assertFailsWith<BackupCrypto.BadPassphraseOrTampered> { BackupCrypto.open("$h\n$flipped", pass) }
        // změna hlavičky (AAD) – např. podvržený počet iterací
        assertFailsWith<BackupCrypto.BadPassphraseOrTampered> { BackupCrypto.open(h.replace("iter=100000", "iter=100001") + "\n" + b, pass) }
        assertFailsWith<BackupCrypto.BadPassphraseOrTampered> { BackupCrypto.open(h + "\n" + b.dropLast(8), pass) }
    }

    @Test fun shortPassphraseRejectedAndSaltsDiffer() {
        assertFailsWith<IllegalArgumentException> { BackupCrypto.seal(data, "kratke".toCharArray()) }
        assertFalse(BackupCrypto.seal(data, pass, 100_000) == BackupCrypto.seal(data, pass, 100_000))
        assertFalse(BackupCrypto.isSealed("H4sIAAAA")) // stará záloha gzip+base64
    }
}
