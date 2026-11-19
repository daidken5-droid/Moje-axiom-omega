package cz.axiom.vega.core

import cz.axiom.vega.calc.LongText
import cz.axiom.vega.calc.Quick
import cz.axiom.vega.calc.Topic
import cz.axiom.vega.files.Extract
import cz.axiom.vega.net.Http
import cz.axiom.vega.net.NetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Vlastní jádro VEGA (bez AVIORu): síť, rychlé výpočty, téma, dlouhý text, dokumenty. */
class NewCoreTest {
    @Test fun httpPolicy() {
        Http.requireAllowed("https://example.org/a")
        Http.requireAllowed("http://192.168.1.20:8080/v1")
        assertTrue(runCatching { Http.requireAllowed("http://example.org") }.exceptionOrNull() is NetException)
        assertTrue(runCatching { Http.requireAllowed("https://10.0.0.5/x", publicOnly = true) }.exceptionOrNull() is NetException)
        assertTrue(runCatching { Http.requireAllowed("file:///etc/passwd") }.exceptionOrNull() is NetException)
        // přesměrování se znovu ověří: lokální server přesměruje veřejný web do LAN / na http → odmítnuto
        val ss = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { runCatching { ss.accept().use { s -> s.getInputStream().read(ByteArray(2048)); s.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://example.org/\r\nContent-Length: 0\r\n\r\n".toByteArray()) } } }.start()
        val e = runCatching { Http.get("http://127.0.0.1:${ss.localPort}/", publicOnly = false) }.exceptionOrNull()
        ss.close()
        assertTrue(e?.message.orEmpty(), e is NetException && e.message!!.contains("http://"))
    }

    @Test fun quickAnswers() {
        assertTrue(Quick.answer("Kolik je 48 × 37?")!!.endsWith("1 776"))
        assertTrue(Quick.answer("kolik je 17 * 23")!!.endsWith("391"))
        assertTrue(Quick.answer("(2 + 3) * 4,5")!!.endsWith("22,5"))
        assertTrue(Quick.answer("15 % z 2 400")!!.contains("360"))
        assertTrue(Quick.answer("převeď 10 km na mi")!!.contains("6,2137"))
        assertTrue(Quick.answer("20 °C na F")!!.contains("68 °F"))
        assertTrue(Quick.answer("kolik dní od 1. 1. 2025 do 1. 3. 2025")!!.contains("59 dní"))
        assertNull(Quick.answer("v 10:30 mám schůzku, co si vzít?"))
        assertNull(Quick.answer("1 / 0"))
    }

    @Test fun topicAndLongText() {
        assertEquals("operation Prato", Topic.bare("operation Prato"))
        assertNull(Topic.bare("jak se máš?")); assertNull(Topic.bare("spočítej 5 + 5"))
        val subs = LongText.subQuestions("Text.\n1. Jaký je rozpočet?\nUveď termín dokončení. Kdo je odpovědný?")
        assertTrue(subs.toString(), subs.size >= 3)
        val long = (1..50).joinToString("\n\n") { "Odstavec $it. " + "slovo ".repeat(60) }
        val ch = LongText.chunks(long, 2000)
        assertEquals(long, ch.joinToString("")); assertTrue(ch.all { it.length <= 2000 })
    }

    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z -> e.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } } }.toByteArray()

    @Test fun documentsAndZipBomb() {
        val docx = zip("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>Ahoj </w:t></w:r><w:r><w:t>světe &amp; VEGA</w:t></w:r></w:p><w:p><w:r><w:t>Druhý</w:t></w:r></w:p></w:body></w:document>".toByteArray())
        assertEquals("Ahoj světe & VEGA\nDruhý", Extract.extract("a.docx", null, docx)!!.text)
        val xlsx = zip("xl/sharedStrings.xml" to "<sst><si><t>Jméno</t></si><si><t>Petr</t></si></sst>".toByteArray(),
            "xl/worksheets/sheet1.xml" to "<sheetData><row><c t=\"s\"><v>0</v></c><c><v>42</v></c></row><row><c t=\"s\"><v>1</v></c></row></sheetData>".toByteArray())
        assertTrue(Extract.extract("t.xlsx", null, xlsx)!!.text.contains("Jméno;42"))
        // zip bomba: 60 MB nul se zkomprimuje na pár desítek kB – čtení skončí na stropu, nespadne
        val bomb = zip("word/document.xml" to ByteArray(60_000_000))
        val r = Extract.extract("bomb.docx", null, bomb)!!
        assertTrue(r.note.orEmpty(), r.note!!.contains("příliš velký"))
    }
}
