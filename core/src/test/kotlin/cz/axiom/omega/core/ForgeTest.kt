package cz.axiom.omega.core

import com.android.apksig.ApkVerifier
import cz.axiom.omega.core.agent.ActiveAgent
import cz.axiom.omega.core.agent.AgentRole
import cz.axiom.omega.core.agent.Variant
import cz.axiom.omega.core.forge.ApkForge
import cz.axiom.omega.core.forge.BinaryXml
import cz.axiom.omega.core.forge.FileForge
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class ForgeTest {

    private val shell = File(System.getProperty("user.dir")).resolve("../shell/build/outputs/apk/release/shell-release-unsigned.apk").normalize()

    /** Testovací klíč z JDK keytool (v telefonu se použije Android Keystore). */
    private fun key(dir: File): Pair<PrivateKey, X509Certificate> {
        val ks = File(dir, "t.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=AXIOM Test", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "testpass", "-keypass", "testpass")
            .redirectErrorStream(true).start()
        p.inputStream.readBytes(); check(p.waitFor() == 0)
        val k = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "testpass".toCharArray()) } }
        return k.getKey("t", "testpass".toCharArray()) as PrivateKey to k.getCertificate("t") as X509Certificate
    }

    @Test fun forgesSignedInstallableApk() {
        assumeTrue("šablona :shell není sestavená (./gradlew :shell:assembleRelease)", shell.exists())
        val dir = Files.createTempDirectory("forge").toFile()
        val (pk, cert) = key(dir)
        val out = File(dir, "kalkulacka.apk")
        val html = "<!doctype html><title>Kalkulačka</title><h1>2+2=4</h1>"
        val r = shell.inputStream().use { ApkForge.build(it, "Kalkulačka Ω", mapOf("index.html" to html.toByteArray(), "data/a.json" to "{}".toByteArray()), pk, listOf(cert), out) }
        out.copyTo(File("build/forge-test.apk"), true)
        assertEquals(26, r.packageName.length)
        assertTrue(r.packageName.matches(Regex("cz\\.axiom\\.gen\\.app[a-z0-9]{10}")))
        val v = ApkVerifier.Builder(out).build().verify()
        assertTrue("podpis: ${v.errors} ${v.v2SchemeSigners.flatMap { it.errors }}", v.isVerified)
        assertTrue(v.isVerifiedUsingV2Scheme)
        ZipFile(out).use { z ->
            assertEquals(html, z.getInputStream(z.getEntry("assets/www/index.html")).readBytes().decodeToString())
            assertTrue(z.getEntry("assets/www/data/a.json") != null)
            val manifest = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            assertTrue(manifest.indexOf(r.packageName.toByteArray(Charsets.UTF_16LE)) >= 0)
            assertTrue(manifest.indexOf("Kalkulačka Ω".toByteArray(Charsets.UTF_16LE)) >= 0)
            assertTrue(manifest.indexOf(ApkForge.PACKAGE_PLACEHOLDER.toByteArray(Charsets.UTF_16LE)) < 0)
            assertEquals(java.util.zip.ZipEntry.STORED, z.getEntry("resources.arsc").method)
        }
        // stejný název = stejný balíček (aktualizace), jiný název = jiná aplikace
        assertEquals(ApkForge.packageFor("Kalkulačka Ω"), ApkForge.packageFor("kalkulacka ω "))
        assertFalse(ApkForge.packageFor("Poznámky") == r.packageName)
    }

    private fun ByteArray.indexOf(p: ByteArray): Int {
        outer@ for (i in 0..size - p.size) { for (j in p.indices) if (this[i + j] != p[j]) continue@outer; return i }
        return -1
    }

    @Test fun binaryXmlPatchKeepsLength() {
        val src = ("xx" + "AB_PLACE" + "yy").toByteArray(Charsets.UTF_16LE)
        val out = BinaryXml.patch(src, mapOf("AB_PLACE" to "Čau"))
        assertEquals(src.size, out.size)
        assertEquals("xxČau     yy", out.toString(Charsets.UTF_16LE))
    }

    @Test fun pathsAndNamesAreSafe() {
        assertTrue(runCatching { ApkForge.safePath("../x.html") }.isFailure)
        assertEquals("js/app.js", ApkForge.safePath("/js//app.js"))
        assertEquals("rozpocet.csv", FileForge.safeName("../../rozpocet.csv"))
        assertEquals("zprava.txt", FileForge.safeName("zprava"))
        assertEquals("text/csv", FileForge.mimeOf("a.CSV"))
    }

    @Test fun docxIsValidZip() {
        val f = Files.createTempFile("t", ".docx").toFile()
        f.writeBytes(FileForge.docx("Nabídka", "# Úvod\nText **tučně** & <tag>\n- bod"))
        ZipFile(f).use { z ->
            val doc = z.getInputStream(z.getEntry("word/document.xml")).readBytes().decodeToString()
            assertTrue(doc.contains("&amp; &lt;tag&gt;"))
            assertTrue(doc.contains("<w:b/>"))
            assertTrue(z.getEntry("[Content_Types].xml") != null)
        }
    }

    @Test fun agentParsesContentBlock() {
        val p = ActiveAgent.parse("MYŠLENKA: vytvořím aplikaci\nAKCE: {\"tool\": \"build_apk\", \"args\": {\"name\": \"Poznámky\"}}\nOBSAH:\n```html\n<!doctype html>\n<p>\"ahoj\" {x}</p>\n```")
        assertEquals("build_apk", p.call!!.first)
        assertEquals("<!doctype html>\n<p>\"ahoj\" {x}</p>", (p.call!!.second["content"] as JsonPrimitive).content)
        assertEquals("Poznámky", (p.call!!.second["name"] as JsonPrimitive).content)
    }

    @Test fun variantsAndRoles() {
        assertEquals(Variant.AUTO, Variant.of(null))
        assertEquals(AgentRole.APPS, Variant.of("studio").role)
        assertTrue(AgentRole.entries.size >= 14)
        assertTrue(Variant.entries.all { it.title.startsWith("Ω") })
    }
}

class RouterFilesTest {
    private val ctx = cz.axiom.omega.core.agent.Router.Context(online = false, engines = listOf("local"))

    @Test fun fileRequestsGoToAgentWithRole() {
        val R = cz.axiom.omega.core.agent.Router
        assertEquals(cz.axiom.omega.core.agent.Router.Mode.AGENT, R.route("Vytvoř mi aplikaci APK na poznámky", ctx).mode)
        assertEquals(AgentRole.APPS, R.autoRole("Vytvoř mi aplikaci APK na poznámky"))
        assertEquals(AgentRole.OFFICE, R.autoRole("Připrav výpověď z nájmu jako Word"))
        assertEquals(AgentRole.FACTCHECK, R.autoRole("Je pravda, že chemtrails existují?"))
        assertEquals(cz.axiom.omega.core.agent.Router.Mode.CHAT, R.route("Jak funguje tepelné čerpadlo?", ctx).mode)
    }
}
