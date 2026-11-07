package cz.axiom.omega.core.brain

import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.CancelledException
import cz.axiom.omega.core.llm.Http
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Stahování mozku, které přežije výpadek sítě.
 *
 * Chyby AXIOM 2.1, které řeší:
 *  1. žádné navazování – každý výpadek Wi-Fi = stahovat 1,83 GB znovu od nuly,
 *  2. 60 s read-timeout bez opakování – na mobilních datech spolehlivě selhalo,
 *  3. jediná URL bez zrcadla,
 *  4. "připraveno" = přesná velikost souboru napevno v kódu.
 *
 * Postup: `.part` soubor + HTTP Range, při každé chybě exponenciální čekání
 * a navázání, střídání zrcadel, po dokončení SHA-256 a atomické přejmenování.
 */
class Downloader(
    private val maxAttempts: Int = 8,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class Progress(val bytes: Long, val total: Long, val bytesPerSec: Long, val attempt: Int, val phase: String) {
        val percent get() = if (total > 0) (bytes * 100 / total).toInt() else 0
    }

    class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun download(spec: BrainSpec, target: File, cancel: CancelToken, onProgress: (Progress) -> Unit): File {
        val checkSha = spec.sha256.isNotBlank()
        if (target.exists() && target.length() == spec.sizeBytes && (!checkSha || verify(target, spec.sha256, cancel, null))) return target
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val free = target.parentFile?.usableSpace ?: Long.MAX_VALUE
        val needed = spec.sizeBytes - (if (part.exists()) part.length() else 0L)
        if (free < needed + 200_000_000L) {
            throw DownloadException("Nedostatek místa: potřeba ${fmt(needed + 200_000_000L)}, volno ${fmt(free)}.")
        }

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            cancel.check()
            val url = spec.urls[attempt % spec.urls.size]
            attempt++
            try {
                fetch(url, spec, part, attempt, cancel, onProgress)
                if (part.length() != spec.sizeBytes) throw DownloadException("Neúplný soubor (${part.length()} z ${spec.sizeBytes} B).")
                if (checkSha) {
                    onProgress(Progress(part.length(), spec.sizeBytes, 0, attempt, "Ověřuji SHA-256…"))
                    if (!verify(part, spec.sha256, cancel, onProgress)) {
                        part.delete()
                        throw DownloadException("Kontrolní součet SHA-256 nesouhlasí – soubor byl smazán a stáhne se znovu.")
                    }
                } else if (!isGguf(part)) {
                    part.delete()
                    throw DownloadException("Stažený soubor není GGUF model.")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw DownloadException("Nelze přejmenovat staženou část na ${target.name}.")
                return target
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                if (cancel.cancelled) throw CancelledException()
                lastError = e
                val wait = minOf(60_000L, 2_000L shl (attempt - 1).coerceAtMost(5))
                onProgress(Progress(part.length(), spec.sizeBytes, 0, attempt, "Výpadek (${e.message}). Navazuji za ${wait / 1000} s…"))
                sleeper(wait)
            }
        }
        throw DownloadException("Stažení se nepodařilo ani po $maxAttempts pokusech: ${lastError?.message}", lastError)
    }

    private fun fetch(startUrl: String, spec: BrainSpec, part: File, attempt: Int, cancel: CancelToken, onProgress: (Progress) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have > spec.sizeBytes) { part.delete(); have = 0 }
        if (have == spec.sizeBytes) return

        var url = startUrl
        var conn: HttpURLConnection? = null
        try {
            // ruční redirecty: HF přesměrovává na CDN (jiný host), HttpURLConnection mezi protokoly nepřesměruje
            for (hop in 0..8) {
                val c = Http.open(url, connectMs = 30_000, readMs = 90_000)
                c.instanceFollowRedirects = false
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw DownloadException("Přesměrování bez cíle.")
                    url = URL(URL(url), loc).toString()
                    c.disconnect()
                    continue
                }
                conn = c
                break
            }
            val c = conn ?: throw DownloadException("Příliš mnoho přesměrování.")
            cancel.onCancel { runCatching { c.disconnect() } }
            when (c.responseCode) {
                206 -> Unit
                200 -> if (have > 0) { have = 0; part.delete() } // server ignoroval Range → začni znovu
                416 -> { part.delete(); throw DownloadException("Server odmítl rozsah – začínám znovu.") }
                else -> throw DownloadException("HTTP ${c.responseCode} z ${URL(url).host}")
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(have)
                val buf = ByteArray(256 * 1024)
                var bytes = have
                var lastTick = System.currentTimeMillis()
                var lastBytes = bytes
                c.inputStream.use { input ->
                    while (true) {
                        cancel.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (bytes + n > spec.sizeBytes) throw DownloadException("Server posílá víc dat, než má mozek mít.")
                        raf.write(buf, 0, n)
                        bytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 700) {
                            val speed = (bytes - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)
                            onProgress(Progress(bytes, spec.sizeBytes, speed, attempt, "Stahuji mozek"))
                            lastTick = now
                            lastBytes = bytes
                        }
                    }
                }
                raf.fd.sync()
            }
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun isGguf(f: File): Boolean = runCatching {
            FileInputStream(f).use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
        }.getOrDefault(false)

        /** Velikost souboru na URL (HEAD, sleduje přesměrování) – pro vlastní odkaz bez katalogu. */
        fun remoteSize(url: String): Long {
            var u = url
            repeat(8) {
                val c = Http.open(u, "HEAD", readMs = 20_000)
                c.instanceFollowRedirects = false
                try {
                    val code = c.responseCode
                    if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: throw DownloadException("Přesměrování bez cíle.")).toString(); return@repeat }
                    if (code !in 200..299) throw DownloadException("HTTP $code")
                    val len = c.getHeaderFieldLong("X-Linked-Size", -1).takeIf { it > 0 } ?: c.contentLengthLong
                    if (len <= 0) throw DownloadException("Server neuvádí velikost souboru.")
                    return len
                } finally { c.disconnect() }
            }
            throw DownloadException("Příliš mnoho přesměrování.")
        }

        fun verify(file: File, sha256: String, cancel: CancelToken, onProgress: ((Progress) -> Unit)?): Boolean {
            val md = MessageDigest.getInstance("SHA-256")
            val total = file.length()
            var done = 0L
            var last = 0L
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    cancel.check()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - last > 700) {
                        onProgress(Progress(done, total, 0, 0, "Ověřuji SHA-256"))
                        last = now
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
        }

        fun fmt(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1e9).replace('.', ',')
    }
}
