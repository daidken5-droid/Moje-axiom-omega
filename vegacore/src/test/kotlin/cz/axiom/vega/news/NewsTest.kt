package cz.axiom.vega.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

class NewsTest {
    private fun at(s: String): ZonedDateTime = LocalDateTime.parse(s).atZone(News.ZONE)
    private fun item(src: String, title: String, t: String, link: String = "https://$src.cz/${title.hashCode()}") = News.Item(src, title, link, at(t).toInstant(), "perex $title")

    @Test fun windowBoundaries() {
        val w = News.window(at("2026-11-23T05:20:00"))
        assertEquals(at("2026-11-22T05:20:00").toInstant(), w.first)
        assertEquals(at("2026-11-23T05:20:00").toInstant(), w.second)
        assertTrue("05:19:59 dnes patří do okna", News.inWindow(at("2026-11-23T05:19:59").toInstant(), w))
        assertFalse("05:20:00 dnes už ne (příští okno)", News.inWindow(at("2026-11-23T05:20:00").toInstant(), w))
        assertTrue("05:20:00 včera ano", News.inWindow(at("2026-11-22T05:20:00").toInstant(), w))
        assertFalse("05:19:59 včera ne", News.inWindow(at("2026-11-22T05:19:59").toInstant(), w))
        assertFalse("bez data se nepočítá", News.inWindow(null, w))
        // pokus v 05:28 a „Zkusit znovu“ v 9:00 = stejné okno; ve 3:00 ráno = předchozí okno
        assertEquals(w, News.window(at("2026-11-23T05:28:00")))
        assertEquals(w, News.window(at("2026-11-23T09:00:00")))
        assertEquals(at("2026-11-22T05:20:00").toInstant(), News.window(at("2026-11-23T03:00:00")).second)
    }

    @Test fun daylightSavingTransitions() {
        // jaro 29. 3. 2026: noc má 23 h; podzim 25. 10. 2026: 25 h – okno je vždy 05:20 → 05:20 místního času
        val spring = News.window(at("2026-03-29T05:20:00"))
        assertEquals(Duration.ofHours(23), Duration.between(spring.first, spring.second))
        assertEquals(Instant.parse("2026-03-28T04:20:00Z"), spring.first)   // CET +1
        assertEquals(Instant.parse("2026-03-29T03:20:00Z"), spring.second)  // CEST +2
        val autumn = News.window(at("2026-10-25T05:20:00"))
        assertEquals(Duration.ofHours(25), Duration.between(autumn.first, autumn.second))
        assertTrue(News.inWindow(Instant.parse("2026-10-25T04:19:59Z"), autumn))   // 05:19:59 CET
        assertFalse(News.inWindow(Instant.parse("2026-10-25T04:20:00Z"), autumn))
    }

    @Test fun parseRealShapesAndDates() {
        val rss = """<?xml version="1.0" encoding="utf-8"?><rss><channel><title>X</title>
            <item><title><![CDATA[Jednání na nejdelší klimatické konferenci OSN v historii zkolabovala]]></title><link>https://ct24.ceskatelevize.cz/a/1</link>
            <description><![CDATA[<p>Jednání v&nbsp;turecké Antalyi &amp; COP31</p>]]></description><pubDate>Sun, 22 Nov 2026 12:57:31 GMT</pubDate></item>
            <item><title>Bez odkazu</title><pubDate>Sun, 22 Nov 2026 12:57:31 +0100</pubDate></item>
            <item><title>Na přejezdu se srazil vlak s autem</title><link>https://www.idnes.cz/x#utm_source=rss&amp;utm_medium=feed</link><pubDate>Sun, 22 Nov 2026 15:41:32 +0100</pubDate><description>${"slovo ".repeat(200)}</description></item>
            </channel></rss>"""
        val items = News.parse(rss, "ct24")
        assertEquals(2, items.size)
        assertEquals("Jednání na nejdelší klimatické konferenci OSN v historii zkolabovala", items[0].title)
        assertEquals("Jednání v turecké Antalyi & COP31", items[0].perex)
        assertEquals(Instant.parse("2026-11-22T12:57:31Z"), items[0].published)
        assertEquals(Instant.parse("2026-11-22T14:41:32Z"), items[1].published)
        assertTrue("perex zkrácený, ne celý článek", items[1].perex.length <= News.PEREX_MAX + 1)
        // Atom
        val atom = "<feed><entry><title>Atom zpráva</title><link href=\"https://x.cz/a\"/><updated>2026-11-22T10:25:01Z</updated></entry></feed>"
        assertEquals(Instant.parse("2026-11-22T10:25:01Z"), News.parse(atom, "x").single().published)
    }

    @Test fun emptyOrBrokenRss() {
        for (bad in listOf("", "   ", "<html><body>503 Service Unavailable</body></html>", "<rss><channel><item><title>useknuté", "\u0000\u0001binár", "<item><title></title><link>https://a.cz</link></item>",
                "<item><title>X</title><link>javascript:alert(1)</link></item>"))
            assertEquals(bad.take(20), 0, News.parse(bad, "s").size)
        assertEquals(null, News.date("včera večer"))
    }

    @Test fun deduplicationAcrossSites() {
        val day = "2026-11-22T"
        val items = listOf(
            item("novinky", "Jednání na klimatické konferenci zkolabovala po neshodě na deklaraci", "${day}15:00:00"),
            item("ct24", "Jednání na nejdelší klimatické konferenci OSN v historii zkolabovala", "${day}13:57:00"),
            item("idnes", "Klimatická konference v Antalyi zkolabovala, státy se neshodly na deklaraci", "${day}14:10:00"),
            item("idnes", "Na přejezdu v Prostějově se srazil vlak s autem. Dva lidé na místě zemřeli", "${day}15:41:00"),
            item("novinky", "Srážka vlaku s autem na přejezdu u Prostějova si vyžádala dva mrtvé", "${day}16:00:00"),
            item("aktualne", "Pseudopenalta, zuřil brněnský Svědík. V první půli se vykřičel", "${day}16:28:00"),
            item("novinky", "Stejný odkaz dvakrát", "${day}10:00:00", "https://n.cz/same#utm=1"),
            item("novinky", "Stejný odkaz dvakrát", "${day}10:00:00", "https://n.cz/same"),
            item("novinky", "Mimo okno (předevčírem)", "2026-11-21T04:00:00"),
        )
        val topics = News.topics(items, News.window(at("2026-11-23T05:20:00")))
        assertEquals("klima (3), vlak (2), fotbal, stejný odkaz", 4, topics.size)
        assertEquals(3, topics[0].sources.size); assertTrue(topics[0].title.contains("klimatick"))
        assertEquals(2, topics[1].sources.size); assertTrue(topics[1].title.contains("vlak", true) || topics[1].title.contains("přejezd"))
        assertTrue(topics.none { it.title.contains("Mimo okno") })
        // max 10 témat
        val pool = "jablko hruška švestka meruňka broskev třešně višně malina ostružina borůvka brusinka rybíz angrešt kiwi banán ananas mango papája kokos datle fíky granátové limetka citron pomeranč mandarinka grep meloun tykev cuketa okurka rajče paprika mrkev celer petržel pastinák ředkev kedluben brokolice květák zelí kapusta špenát salát rukola polníček cibule česnek pórek pažitka kopr bazalka oregano tymián rozmarýn šalvěj máta meduňka levandule heřmánek lípa bez šípek jeřabina líska ořech kaštan buk dub javor jasan bříza olše vrba topol smrk jedle borovice modřín tis jalovec cypřiš túje".split(' ')
        val many = (0 until 25).map { i -> item("s$i", "${pool[i * 3]} ${pool[i * 3 + 1]} ${pool[i * 3 + 2]}", "${day}12:00:00") }
        assertEquals(10, News.topics(many, News.window(at("2026-11-23T05:20:00"))).size)
    }

    @Test fun summaryNeedsSourcePerSentenceAndNoCopying() {
        val day = "2026-11-22T"
        val topics = News.topics(listOf(
            item("novinky", "Jednání na klimatické konferenci zkolabovala po neshodě na deklaraci", "${day}15:00:00"),
            item("ct24", "Jednání na nejdelší klimatické konferenci OSN v historii zkolabovala", "${day}13:57:00"),
            item("idnes", "Na přejezdu v Prostějově se srazil vlak s autem", "${day}15:41:00")), News.window(at("2026-11-23T05:20:00")))
        val out = """T1: Klimatický summit v Antalyi skončil bez společné deklarace [S1]. Byl nejdelší v historii [S2]. Třetí věta navíc [S1].
            T2: Věta bez zdroje se vyřadí. Vlak se u Prostějova srazil s autem, zemřeli dva lidé [S1]."""
        val s = News.validate(out, topics)
        assertEquals(2, s[0].sentences.size)                       // max 2 věty
        assertTrue(s[0].sentences.all { it.refs.isNotEmpty() })
        assertEquals("cizí zdroj (S1 patří T1) i věta bez zdroje pryč", 0, s[1].sentences.size)
        val md = News.render(java.time.LocalDate.of(2026, 11, 23), s, withModel = true)
        assertTrue(md, md.contains("[Novinky.cz](https://novinky.cz/") && md.contains("**2. Na přejezdu") && md.contains("[iDNES.cz]("))
        // bez modelu: titulky + odkazy
        val plain = News.render(java.time.LocalDate.of(2026, 11, 23), topics.map { News.Summary(it, emptyList()) }, withModel = false)
        assertTrue(plain, plain.contains("bez jazykového modelu") && plain.contains("perex Jednání") && plain.contains("[ČT24](https://ct24.cz/"))
        // prázdno
        assertTrue(News.render(java.time.LocalDate.of(2026, 11, 23), emptyList(), true).contains("nic nenašel"))
    }
}
