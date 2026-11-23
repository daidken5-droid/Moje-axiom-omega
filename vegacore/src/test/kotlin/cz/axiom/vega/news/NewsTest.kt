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
        assertEquals("sledovací parametry pryč", "https://www.idnes.cz/x", items[1].link)
        assertEquals("https://a.cz/c?id=5", News.untrack("https://a.cz/c?utm_source=rss&id=5&utm_medium=feed"))
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
        assertTrue(News.render(java.time.LocalDate.of(2026, 11, 23), topics.map { News.Summary(it, emptyList()) }, false).contains("_(3 zdroje)_"))
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
        assertTrue(plain, plain.contains("bez jazykového modelu") && plain.contains("Číst: ") && plain.contains("[ČT24](https://ct24.cz/"))
        // prázdno
        assertTrue(News.render(java.time.LocalDate.of(2026, 11, 23), emptyList(), true).contains("nic nenašel"))
    }

    /** Obsáhlejší souhrn bez modelu: věty z perexů různých webů, opakování jen jednou, každá s odkazem na svůj článek. */
    @Test fun extractiveSummaryFromSeveralSources() {
        val d = at("2026-11-22T15:00:00").toInstant()
        val a = News.Item("novinky", "Jednání na klimatické konferenci zkolabovala po neshodě na deklaraci", "https://www.novinky.cz/a1", d,
            "Jednání na nejdéle trvající klimatické konferenci OSN v historii zkolabovala, uvedla agentura Reuters. Stalo se tak poté, co se státy neshodly na společné deklaraci, jak již dříve řekl předseda konference.")
        val b = News.Item("ct24", "Jednání na nejdelší klimatické konferenci OSN v historii zkolabovala", "https://ct24.ceskatelevize.cz/a/1", d,
            "Jednání na klimatické konferenci COP31 v turecké Antalyi v neděli podle agentury Reuters zkolabovala. Oproti plánovanému konci se konference protáhla o 44 hodin a překonala tak rekord v délce trvání. Irské předsednictví EU uvedlo, že Unie přijetí nepodpoří.")
        val t = News.topics(listOf(a, b), News.window(at("2026-11-23T05:20:00"))).single()
        val ex = News.extract(t)
        assertTrue(ex.toString(), ex.size in 3..News.SENTENCES_PER_TOPIC)
        assertTrue("obě redakce", ex.map { it.second.source }.toSet() == setOf("novinky", "ct24"))
        assertTrue("44 hodin z ČT24", ex.any { it.first.contains("44 hodin") && it.second.source == "ct24" })
        val md = News.render(java.time.LocalDate.of(2026, 11, 23), listOf(News.Summary(t, emptyList())), withModel = false)
        assertTrue(md, md.contains("[(ČT24)](https://ct24.ceskatelevize.cz/a/1)") && md.contains("Číst: "))
        assertTrue("žádná věta dvakrát", ex.map { it.first }.distinct().size == ex.size)
    }

    /** H4: titulek / perex z kanálu nesmí vytvořit odkaz ani formátování v přehledu. */
    @Test fun feedTextCannotInjectMarkdown() {
        val rss = "<rss><channel><item><title>[Klikni zde](https://zly.example/phish) **VYHRÁL JSI** `kód`</title><link>https://www.novinky.cz/a</link>" +
            "<pubDate>Sun, 22 Nov 2026 15:00:00 +0100</pubDate><description>## Nadpis _kurzíva_ [x](javascript:alert(1)) | tabulka |</description></item></channel></rss>"
        val it = News.parse(rss, "novinky").single()
        assertFalse(it.title, Regex("[\\[\\]`*]").containsMatchIn(it.title))
        assertFalse(it.perex, Regex("[\\[\\]#|]").containsMatchIn(it.perex))
        val md = News.render(java.time.LocalDate.of(2026, 11, 23), listOf(News.Summary(News.Topic(listOf(it)), emptyList())), false)
        assertFalse("jediný odkaz je na článek: $md", md.contains("](https://zly.example") || md.contains("](javascript"))
        assertTrue(md.contains("[Novinky.cz](https://www.novinky.cz/a)"))
    }

    /** H7: kanál ve windows-1250 (starší české weby) i UTF-8 s BOM – diakritika v titulku správně. */
    @Test fun windows1250AndBom() {
        val xml = "<?xml version=\"1.0\" encoding=\"windows-1250\"?><rss><channel><item><title>Žluťoučký kůň úpěl ďábelské ódy</title><link>https://a.cz/1</link><pubDate>Sun, 22 Nov 2026 15:00:00 +0100</pubDate></item></channel></rss>"
        for (bytes in listOf(xml.toByteArray(charset("windows-1250")), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.replace("windows-1250", "utf-8").toByteArray()))
            assertEquals("Žluťoučký kůň úpěl ďábelské ódy", News.parse(cz.axiom.vega.core.Encoding.decode(bytes), "a").single().title)
    }

    /** H9: 7 zdrojů × 50 položek – sloučení do témat rychle (na telefonu je CPU ~5× pomalejší). */
    @Test fun clusteringIsFast() {
        val words = "vláda sněmovna rozpočet klima konference volby nehoda vlak dálnice počasí sníh soud policie nemocnice škola ceny energie benzín inflace banka sport fotbal hokej Ukrajina Rusko Izrael USA Trump Babiš Praha Brno Ostrava".split(' ')
        val r = java.util.Random(1)
        val items = (0 until 350).map { i -> item("s${i % 7}", (0 until 9).joinToString(" ") { words[r.nextInt(words.size)] } + " $i", "2026-11-22T12:00:00") }
        val t0 = System.nanoTime(); val ts = News.topics(items, News.window(at("2026-11-23T05:20:00"))); val ms = (System.nanoTime() - t0) / 1_000_000
        println("NEWS|350 položek → ${ts.size} témat za $ms ms")
        assertTrue("$ms ms", ms < 200)
    }

    /** H17: skutečné titulky z 22. 11. – stejná událost jinými slovy se sloučí, podobně znějící jiná událost ne. */
    @Test fun realWorldMergeAndNoFalseMerge() {
        assertTrue(News.similar("Ohnivé peklo v Benghází. Dronový útok zničil moderní ruskou válečnou loď", "V Libyi po dronovém útoku explodovala ruská válečná loď"))
        assertTrue(News.similar("V Libyi po dronovém útoku explodovala ruská válečná loď", "Mohutná exploze zničila ruskou válečnou loď v Libyi"))
        assertTrue(News.similar("Pavel vyznamenal historika Snydera a politologa Krasteva", "Prezident Pavel vyznamenal politologa Krasteva a historika Snydera"))
        assertFalse(News.similar("Mimořádný úder. Ukrajinci v Libyi zasáhli další dvě ruská plavidla s vojenským nákladem", "Ruská záchranná loď začala odtahovat zasažené nákladní plavidlo z finských vod"))
        assertFalse(News.similar("Vláda schválila rozpočet na příští rok", "Sněmovna odmítla zákon o rozpočtové kázni"))
    }
}

/** Denní scan 23. 11., kolo 10 (živé RSS): perex se lámal po datu „10.“ → věta „července na hlavní scéně…“. */
class NewsSentenceTest {
    @Test fun dateAndAbbreviationDoNotSplitSentences() {
        val p = "Festival ohlašuje další jméno. Kapela vystoupí 10. července na hlavní scéně v Dolních Vítkovicích. Lístky jsou v prodeji."
        org.junit.Assert.assertEquals(listOf("Festival ohlašuje další jméno.", "Kapela vystoupí 10. července na hlavní scéně v Dolních Vítkovicích.", "Lístky jsou v prodeji."), News.sentences(p))
        org.junit.Assert.assertEquals(listOf("Firma tzv. vklady přijímala bez licence.", "ČNB ji pokutovala."), News.sentences("Firma tzv. vklady přijímala bez licence. ČNB ji pokutovala."))
        org.junit.Assert.assertEquals(listOf("Bez tečky na konci"), News.sentences("Bez tečky na konci"))
        org.junit.Assert.assertEquals(emptyList<String>(), News.sentences(""))
    }
}

/** Denní scan 23. 11., kolo 13: 16 tvarů pubDate z reálných CMS; 4 se dřív tiše zahodily (položka vypadla z přehledu). */
class NewsDateFormatsTest {
    @Test fun realWorldPubDateShapes() {
        val exp = java.time.Instant.parse("2026-11-23T04:20:00Z")
        listOf("Mon, 23 Nov 2026 05:20:00 +0100", "Mon, 23 Nov 2026 04:20:00 GMT", "Mon, 23 Nov 2026 05:20:00 CET", "Mon, 23 Nov 2026 05:20 +0100",
            "23 Nov 2026 05:20:00 +0100", "Mon, 23 nov 2026 05:20:00 +0100", "2026-11-23T05:20:00+01:00", "2026-11-23T04:20:00.000Z",
            "2026-11-23 05:20:00 +0100", "2026-11-23 05:20:00 +01:00", "2026-11-23 04:20:00Z", "Mon,  23 Nov 2026 05:20:00 +0100",
            "Monday, 23 Nov 2026 05:20:00 +0100", "Mon, 23 November 2026 05:20:00 +0100", " Mon, 23 Nov 2026 05:20:00 +0100\n")
            .forEach { org.junit.Assert.assertEquals(it, exp, News.date(it)) }
        listOf("", "včera", "2026-13-45 99:99:99 +0100", "Mon, 32 Nov 2026 05:20:00 +0100").forEach { org.junit.Assert.assertNull(it, News.date(it)) }
    }
}

/** Denní scan 23. 11., kolo 14: perex se zakódovaným HTML, pojmenované entity a dvojí kódování (Atom type="html"). */
class NewsCleanEntitiesTest {
    @Test fun encodedHtmlNamedEntitiesAndDoubleEncoding() {
        org.junit.Assert.assertEquals("Vláda schválila rozpočet. Více", News.clean("&lt;p&gt;Vláda schválila rozpočet.&lt;/p&gt; &lt;a href=\"x\"&gt;Více&lt;/a&gt;"))
        org.junit.Assert.assertEquals("Premiér řekl „ne“ – a odešel…", News.clean("<![CDATA[Premiér řekl &bdquo;ne&ldquo; &ndash; a odešel&hellip;]]>"))
        org.junit.Assert.assertEquals("Atom – titulek", News.clean("Atom &amp;ndash; titulek"))
        org.junit.Assert.assertEquals("«Citace» 5 %", News.clean("&laquo;Citace&raquo; 5&nbsp;%"))
        org.junit.Assert.assertEquals("Text", News.clean("&lt;img src=x onerror=alert(1)&gt;Text"))
        org.junit.Assert.assertEquals("cena 100 Kč a 50", News.clean("cena &lt; 100 Kč a &gt; 50"))  // < > nevytvoří značku, obsah zůstane
        org.junit.Assert.assertEquals("rozpočet", News.clean("roz&shy;počet"))
        org.junit.Assert.assertEquals("AT&T a &neznamá;", News.clean("AT&amp;T a &neznamá;"))
        org.junit.Assert.assertEquals("odkaz", News.clean("<![CDATA[<a href=\"javascript:x\">odkaz</a>]]>"))
    }
}

/** Denní scan 23. 11., kolo 15: různé události se shodou jen v obecných slovech se nesmí sloučit do jednoho tématu. */
class NewsFalseMergeTest {
    @Test fun genericWordsAloneDoNotMerge() {
        listOf("Policie v Praze zadržela muže, který ubodal souseda" to "Policie v Brně zadržela muže kvůli krádeži aut",
            "Vláda schválila zvýšení platů učitelů od ledna" to "Vláda schválila zvýšení spotřební daně z alkoholu",
            "Ceny elektřiny od ledna klesnou o deset procent" to "Ceny potravin od ledna vzrostou o pět procent",
            "Na D1 u Humpolce se srazila tři auta, jeden mrtvý" to "Na D1 u Brna se srazila dvě auta, dálnice stojí",
            "Zemřel herec Jiří Bartoška, bylo mu 78 let" to "Zemřel zpěvák Karel Gott, bylo mu 80 let")
            .forEach { (a, b) -> assertFalse("$a ~ $b", News.similar(a, b)) }
        listOf("Klimatická konference v Antalyi zkolabovala, státy se neshodly" to "Jednání na klimatické konferenci zkolabovala po neshodě",
            "Vězeň z Valdic dostal doživotí za pokus o vraždu spoluvězně" to "Muž z Valdic, který se pokusil zabít spoluvězně, dostal doživotní trest",
            "Sparta odvolala trenéra Priskeho" to "Sparta řeší mizérii radikálně. Skončil trenér Priske",
            "Vláda schválila státní rozpočet na příští rok" to "Vláda schválila rozpočet s deficitem 230 miliard")
            .forEach { (a, b) -> assertTrue("$a ~ $b", News.similar(a, b)) }
    }
}

/** Denní scan 23. 11., kolo 16 (živý případ): položka podobná dvěma skupinám je musí spojit, ne jen padnout do první. */
class NewsBridgeMergeTest {
    @Test fun itemSimilarToTwoGroupsMergesThem() {
        fun at(h: Int) = java.time.Instant.parse("2026-11-22T%02d:00:00Z".format(h))
        val ct = News.Item("ct24", "Nvidia vrátila svého spoluzakladatele Huanga do funkce ředitele", "https://ct24.cz/1", at(14), "")
        val idn = News.Item("idnes", "Nvidia vrátila Huanga do čela firmy. Skončil po aféře s údajnou špionkou", "https://idnes.cz/1", at(13), "")
        val nov = News.Item("novinky", "Nvidia vrátila Huanga po aféře s čínskou špionkou do funkce ředitele", "https://novinky.cz/1", at(12), "")
        assertFalse("předpoklad: první dvě si samy podobné nejsou", News.similar(ct.title, idn.title))
        val w = java.time.Instant.parse("2026-11-22T04:20:00Z") to java.time.Instant.parse("2026-11-23T04:20:00Z")
        val ts = News.topics(listOf(ct, idn, nov), w)
        assertEquals(1, ts.size); assertEquals(3, ts[0].sources.size)
    }
}

/** Noční kolo N1 (23. 11.): stejná událost popsaná jinými slovy (živě: paliva × pohonné hmoty, atomovka × jaderná zbraň). */
class NewsSynonymMergeTest {
    @Test fun synonymsMergeSameEvent() {
        assertTrue(News.similar("Vláda prodloužila opatření proti vysokým cenám nafty a benzinu do konce roku", "Vláda prodloužila regulaci cen paliv do konce prosince"))
        assertTrue(News.similar("Vláda prodloužila opatření proti drahým pohonným hmotám do konce roku", "Vláda prodloužila regulaci cen paliv do konce prosince"))
        assertTrue(News.similar("Rusko plánuje letos vynést do vesmíru družici s jadernou zbraní", "Rusko chce vyslat do vesmíru družici s atomovkou, varují západní rozvědky"))
        // a dál nerozlišuje to, co rozlišovat má
        assertFalse(News.similar("Ceny benzinu klesly pod 35 korun", "Vláda schválila zvýšení spotřební daně z alkoholu"))
        assertFalse(News.similar("Rusko plánuje vynést do vesmíru družici s jadernou zbraní", "Lodě, které Ukrajinci zasáhli v Libyi, vezly zbraně pro ruský Africký sbor"))
    }
}
