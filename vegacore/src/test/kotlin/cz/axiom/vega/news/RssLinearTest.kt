package cz.axiom.vega.news

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rozbitý / zlomyslný feed (otevřené značky bez konce) nesmí zastavit ranní přehled – zpracování je lineární. */
class RssLinearTest {
    private fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun unclosedTagsAreFast() {
        val cases = mapOf(
            "item bez konce" to "<rss><channel>" + "<item>".repeat(150 * 1024 / 6),
            "item bez >" to "<rss>" + "<item".repeat(150 * 1024 / 5),
            "titulek bez konce" to "<rss>" + "<item><title>a</title><link>https://a.cz/".repeat(150 * 1024 / 40),
            "CDATA bez konce" to "<rss><item><title>" + "<![CDATA[<a ".repeat(150 * 1024 / 12) + "</title><link>https://a.cz</link></item>",
        )
        val slow = cases.mapNotNull { (k, xml) -> ms { News.parse(xml, "x") }.takeIf { it > 2_000 }?.let { "$k: $it ms" } }
        assertEquals(emptyList(), slow)
    }

    @Test fun normalFeedStillParses() {
        val xml = """<rss><channel>
            <item><title><![CDATA[Vláda schválila rozpočet]]></title><link>https://a.cz/1?utm_source=x</link><guid isPermaLink="false">1</guid></item>
            <item><title>Bez odkazu, jen guid</title><guid>https://a.cz/2</guid></item>
            <entry><title>Atom se nemá míchat</title></entry>
            </channel></rss>"""
        val items = News.parse(xml, "zdroj")
        assertEquals(listOf("Vláda schválila rozpočet" to "https://a.cz/1", "Bez odkazu, jen guid" to "https://a.cz/2"), items.map { it.title to it.link })
        val atom = News.parse("""<feed><entry><title>Atom</title><link href="https://b.cz/x"/></entry></feed>""", "z")
        assertTrue(atom.single().link == "https://b.cz/x")
    }
}
