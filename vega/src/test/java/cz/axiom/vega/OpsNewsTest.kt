package cz.axiom.vega

import cz.axiom.vega.llm.ChatMessage
import cz.axiom.vega.llm.Delta
import cz.axiom.vega.llm.GenOptions
import cz.axiom.vega.llm.GenResult
import cz.axiom.vega.llm.LlmEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Ranní přehled zpráv – celá cesta v Ops (falešné RSS a falešný model; síť se nevolá). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsNewsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val now: ZonedDateTime = LocalDateTime.parse("2026-11-23T05:20:00").atZone(cz.axiom.vega.news.News.ZONE)
    private fun rss(vararg items: Triple<String, String, String>) = ("<rss><channel>" + items.joinToString("") { (t, l, d) -> "<item><title>$t</title><link>$l</link><pubDate>$d</pubDate><description>Perex: $t</description></item>" } + "</channel></rss>").toByteArray()
    private val novinky = rss(Triple("Jednání na klimatické konferenci zkolabovala po neshodě na deklaraci", "https://www.novinky.cz/a1", "Sun, 22 Nov 2026 15:00:00 +0100"),
        Triple("Hraniční zpráva přesně v 05:20", "https://www.novinky.cz/edge", "Mon, 23 Nov 2026 05:20:00 +0100"),
        Triple("Těsně před koncem okna", "https://www.novinky.cz/last", "Mon, 23 Nov 2026 05:19:59 +0100"))
    private val idnes = rss(Triple("Klimatická konference v Antalyi zkolabovala, státy se neshodly na deklaraci", "https://www.idnes.cz/b1", "Sun, 22 Nov 2026 14:10:00 +0100"))
    private fun ops(fetch: (String) -> ByteArray): Ops = Ops(ctx) { _, _ -> }.also { it.settings.airgap = false; it.settings.newsEnabled = true; it.newsFetch = fetch }
    private class Fake(val out: String) : LlmEngine { override val id = "f"; override val label = "F"; var calls = 0
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: cz.axiom.vega.llm.CancelToken, onDelta: (Delta) -> Unit): GenResult { calls++; return GenResult(out) } }

    @Test fun withoutModelTitlesAndLinksAndWindowEdges() {
        val o = ops { u -> if ("novinky" in u) novinky else idnes }
        val r = o.newsDigest(now)
        assertTrue(r.text, r.ok && r.chat != null)
        val txt = o.messages(r.chat!!).getJSONObject(0).getString("text")
        assertTrue(txt, txt.contains("_(2 zdroje)_") && txt.contains("[Novinky.cz](https://www.novinky.cz/a1)") && txt.contains("[iDNES.cz](https://www.idnes.cz/b1)"))
        assertTrue("05:19:59 patří do okna", txt.contains("Těsně před koncem okna"))
        assertFalse("05:20:00 už ne", txt.contains("Hraniční zpráva"))
        assertTrue(txt.contains("Bez modelu"))
        assertTrue(o.newsDoneFor(java.time.LocalDate.of(2026, 11, 23)))
        assertTrue(o.chats().toString().contains("Ranní přehled 23. 11."))
    }

    @Test fun modelSentencesNeedSources() {
        val o = ops { u -> if ("novinky" in u) novinky else idnes }
        val f = Fake("T1: Klimatický summit v Antalyi skončil bez dohody o deklaraci [S1].\nT2: Tahle věta nemá zdroj."); o.engines.testEngine = f
        val txt = o.messages(o.newsDigest(now).chat!!).getJSONObject(0).getString("text")
        assertEquals(1, f.calls)
        assertTrue(txt, txt.contains("Klimatický summit v Antalyi skončil bez dohody o deklaraci. [Novinky.cz](https://www.novinky.cz/a1)") || txt.contains("skončil bez dohody o deklaraci. [iDNES.cz]"))
        assertFalse("věta bez zdroje vyřazena", txt.contains("nemá zdroj"))
        assertFalse(txt.contains("Bez modelu"))
    }

    @Test fun offlineBrokenAndPartialSources() {
        // Bez sítě: nic se nestahuje
        var fetched = 0
        val off = ops { fetched++; novinky }.also { it.settings.airgap = true }
        val r0 = off.newsDigest(now); assertFalse(r0.ok); assertEquals(0, fetched); assertTrue(r0.text.contains("Bez sítě"))
        // všechny zdroje nedostupné → neúspěch (notifikace „Zkusit znovu“)
        val r1 = ops { throw java.net.UnknownHostException(it) }.newsDigest(now)
        assertFalse(r1.ok); assertTrue(r1.text, r1.text.contains("internet"))
        // vadné / prázdné RSS → úspěch, ale „nic nového“; jeden zdroj padá → ostatní stačí a výpadek se řekne
        val r2 = ops { "<html>503</html>".toByteArray() }.newsDigest(now)
        assertTrue(r2.ok); assertTrue(r2.text, r2.text.contains("nic nového"))
        val r3 = ops { u -> if ("idnes" in u) throw java.net.SocketTimeoutException() else novinky }.newsDigest(now)
        assertTrue(r3.ok); assertTrue(r3.text, r3.text.contains("nedostupné: iDNES.cz"))
        // žádný zapnutý zdroj
        val r4 = ops { novinky }.also { it.settings.newsSources = emptySet() }.newsDigest(now)
        assertFalse(r4.ok); assertTrue(r4.text.contains("žádný zdroj"))
    }

    @Test fun nextDayAndAlarmTimes() {
        val z = cz.axiom.vega.news.News.ZONE
        assertEquals(java.time.LocalDate.of(2026, 11, 23), NewsAlarms.nextDay(LocalDateTime.parse("2026-11-23T05:29:59").atZone(z)))
        assertEquals(java.time.LocalDate.of(2026, 11, 24), NewsAlarms.nextDay(LocalDateTime.parse("2026-11-23T05:30:00").atZone(z)))
        // přechod na letní čas: 05:20 místního času = 03:20 UTC
        assertEquals(java.time.Instant.parse("2026-03-29T03:20:00Z").toEpochMilli(), NewsAlarms.at(java.time.LocalDate.of(2026, 3, 29), 20))
        assertEquals(java.time.Instant.parse("2026-10-25T04:30:00Z").toEpochMilli(), NewsAlarms.at(java.time.LocalDate.of(2026, 10, 25), 30))
    }
}
