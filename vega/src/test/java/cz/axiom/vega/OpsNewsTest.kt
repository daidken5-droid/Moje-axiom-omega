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
    private fun rss(vararg items: Triple<String, String, String>) = ("<rss><channel>" + items.joinToString("") { (t, l, d) -> "<item><title>$t</title><link>$l</link><pubDate>$d</pubDate><description>Událost se podle redakce odehrála v neděli a úřady ji dál prověřují. Doplňující podrobnosti k tématu: $t.</description></item>" } + "</channel></rss>").toByteArray()
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
        assertTrue(txt.contains("bez jazykového modelu"))
        assertTrue("věty z perexů s odkazem na článek", txt.contains("Událost se podle redakce odehrála v neděli a úřady ji dál prověřují. [(Novinky.cz)](https://www.novinky.cz/a1)") || txt.contains("prověřují. [(iDNES.cz)](https://www.idnes.cz/b1)"))
        assertTrue(txt.contains("Číst: [Novinky.cz](https://www.novinky.cz/a1) · [iDNES.cz](https://www.idnes.cz/b1)") || txt.contains("Číst: [iDNES.cz](https://www.idnes.cz/b1) · [Novinky.cz](https://www.novinky.cz/a1)"))
        assertTrue(o.newsDoneFor(java.time.LocalDate.of(2026, 11, 23)))
        assertTrue(o.chats().toString().contains("Ranní přehled 23. 11."))
    }

    /** Na žádost 22. 11.: přehled je nezávislý na jazykovém modelu i cloud AI – model se nezavolá, ani když je připojený. */
    @Test fun newsNeverCallsModel() {
        val o = ops { u -> if ("novinky" in u) novinky else idnes }
        val f = Fake("T1: cokoli [S1]."); o.engines.testEngine = f
        val r = o.newsDigest(now)
        assertTrue(r.ok); assertEquals(0, f.calls)
        assertTrue(o.messages(r.chat!!).getJSONObject(0).getString("text").contains("[Novinky.cz](https://www.novinky.cz/a1)"))
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

    /** Plán: vypnuto = žádný alarm; zapnuto = pokus 05:20 + notifikace 05:30 příštího dne; po 05:20 jen zbývající pokus. */
    @Test fun alarmsAreScheduled() {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        val sh = org.robolectric.Shadows.shadowOf(am)
        val z = cz.axiom.vega.news.News.ZONE
        NewsAlarms.schedule(ctx, enabled = false, now = LocalDateTime.parse("2026-11-22T20:00:00").atZone(z))
        assertEquals(0, sh.scheduledAlarms.size)
        NewsAlarms.schedule(ctx, enabled = true, now = LocalDateTime.parse("2026-11-22T20:00:00").atZone(z))
        val times = sh.scheduledAlarms.map { it.triggerAtTime }.sorted()
        assertEquals(listOf(NewsAlarms.at(java.time.LocalDate.of(2026, 11, 23), 20), NewsAlarms.at(java.time.LocalDate.of(2026, 11, 23), 30)), times)
        // vypnutí zruší
        NewsAlarms.schedule(ctx, enabled = false, now = LocalDateTime.parse("2026-11-22T20:00:00").atZone(z))
        assertEquals(0, sh.scheduledAlarms.size)
        // 05:25 (po prvním pokusu): pokus 05:28 + notifikace 05:30 téhož dne
        NewsAlarms.schedule(ctx, enabled = true, now = LocalDateTime.parse("2026-11-23T05:25:00").atZone(z))
        assertEquals(listOf(NewsAlarms.at(java.time.LocalDate.of(2026, 11, 23), 28), NewsAlarms.at(java.time.LocalDate.of(2026, 11, 23), 30)), sh.scheduledAlarms.map { it.triggerAtTime }.sorted())
    }

    /** H10: „Vyzkoušet teď“ = posledních 24 h do teď (ne zprávy do 05:20) a ranní přehled tím neoznačí jako hotový. */
    @Test fun manualRunUsesLast24hAndKeepsSchedule() {
        val later = LocalDateTime.parse("2026-11-23T17:00:00").atZone(cz.axiom.vega.news.News.ZONE)
        val feed = rss(Triple("Odpolední zpráva o rozpočtu", "https://www.novinky.cz/pm", "Mon, 23 Nov 2026 15:00:00 +0100"),
            Triple("Předvčerejší zpráva", "https://www.novinky.cz/old", "Sat, 21 Nov 2026 15:00:00 +0100"))
        val o = ops { feed }
        val r = o.newsDigest(later, manual = true)
        val txt = o.messages(r.chat!!).getJSONObject(0).getString("text")
        assertTrue(txt, txt.startsWith("**Přehled zpráv – posledních 24 hodin** (do 17:00)") && txt.contains("Odpolední zpráva") && !txt.contains("Předvčerejší"))
        assertFalse("ranní plán nedotčen", o.newsDoneFor(java.time.LocalDate.of(2026, 11, 23)))
    }
}
