package cz.axiom.omega.core

import com.sun.net.httpserver.HttpServer
import cz.axiom.omega.core.brain.Downloader
import cz.axiom.omega.core.library.Libraries
import cz.axiom.omega.core.library.Prebuilt
import cz.axiom.omega.core.library.PrebuiltManifest
import cz.axiom.omega.core.library.PrebuiltPart
import cz.axiom.omega.core.llm.CancelToken
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class PrebuiltTest {
    private var server: HttpServer? = null

    @After fun stop() { server?.stop(0) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun downloadsPartsAndUnpacksVerifiedDatabase() {
        val db = ByteArray(300_000) { (it % 251).toByte() } + Random(7).nextBytes(50_000)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(db) } }.toByteArray()
        val chunks = gz.toList().chunked(gz.size / 3 + 1).map { it.toByteArray() }
        val parts = chunks.mapIndexed { i, c -> PrebuiltPart("wiki-cs.db.gz.part%02d".format(i), c.size.toLong(), sha(c)) }
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        chunks.forEachIndexed { i, c ->
            s.createContext("/" + parts[i].name) { ex -> ex.sendResponseHeaders(200, c.size.toLong()); ex.responseBody.use { it.write(c) } }
        }
        s.start(); server = s
        val base = "http://127.0.0.1:${s.address.port}"
        val m = PrebuiltManifest("wiki-cs", "20231101.cs", "sqlite-fts4-gzip", 3, db.size.toLong(), sha(db), parts)
        val dir = Files.createTempDirectory("pre").toFile()
        val target = File(dir, "lib-wiki-cs.db")
        val steps = ArrayList<String>()
        Prebuilt.install(Libraries.of("wiki-cs")!!, m, File(dir, "parts"), target, CancelToken(), Downloader(sleeper = {}), { "$base/${it.name}" }) { steps += it.phase }
        assertArrayEquals(db, target.readBytes())
        assertTrue(File(dir, "parts").listFiles()!!.isEmpty()) // části po rozbalení smazány
        assertFalse(File(dir, "lib-wiki-cs.db.tmp").exists())
    }

    @Test fun manifestParsingAndUrls() {
        val m = Prebuilt.parse("""{"lib":"wiki-cs","config":"20231101.cs","format":"sqlite-fts4-gzip","articles":5,"dbSize":10,"dbSha256":"ab","parts":[{"name":"wiki-cs.db.gz.part00","size":4,"sha256":"cd"}],"extra":1}""")
        assertEquals(4, m.downloadBytes)
        assertEquals(
            "https://github.com/daidken5-droid/Moje-axiom-omega/releases/download/library-wiki-cs-20231101/manifest.json",
            Prebuilt.manifestUrl(Libraries.of("wiki-cs")!!),
        )
        assertTrue(runCatching { Prebuilt.parse(m.toString().let { """{"lib":"x","format":"sqlite-fts4-gzip","articles":1,"dbSize":1,"dbSha256":"a","parts":[{"name":"../evil","size":1,"sha256":"a"}]}""" }) }.isFailure)
    }
}
