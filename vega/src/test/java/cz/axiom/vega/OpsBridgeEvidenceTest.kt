package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Doporučení 25. 11., sprint 2 – přejímací úloha „most“: nad třemi místními texty v režimu Bez sítě zjistit, zda je doloženo
 * datum uzavření mostu a proč k němu došlo; z odpovědi otevřít pasáž s datem; příčinu bez doložení nechat otevřenou.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsBridgeEvidenceTest {
    private val docs = listOf(
        "rozhodnuti.txt" to "Rozhodnutí odboru dopravy č. j. 45/2026. Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.",
        "zpravy.txt" to "Most v Ústí uzavřeli 3. 11. 2026, informovala ČTK. Řidiči objíždějí přes Mariánský most.",
        "blog.txt" to "Lidé na sociálních sítích spekulují o tom, co bude s mostem dál. Objížďky jsou dlouhé.",
    )

    @Test fun dateDocumentedWithOpenablePassageCauseOpen() {
        val h = OpsHarness(airgap = true); val c = h.o.newChat()
        val ids = docs.map { (n, t) -> h.o.attach(n, "text/plain", t.toByteArray()).getString("id") }
        val r = h.say(c, "Je doložené, kdy zavřeli most a proč?", ids)
        val parts = r.card!!.getJSONArray("parts")
        val date = (0 until parts.length()).map { parts.getJSONObject(it) }.first { it.getString("label").startsWith("datum") }
        val motive = (0 until parts.length()).map { parts.getJSONObject(it) }.first { it.getString("label").startsWith("motiv") }
        // pasáž s datem jde otevřít: soubor + přesná věta z něj
        val ps = date.getJSONArray("passages")
        val p = (0 until ps.length()).map { ps.getJSONObject(it) }.first { it.getString("doc") == "rozhodnuti.txt" }
        assertEquals("Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.", p.getString("text"))
        assertTrue(p.toString(), docs.first().second.contains(p.getString("text")))  // doslovná citace, ne parafráze
        assertTrue(date.getString("status"), date.getString("status").startsWith("potvrzeno"))
        // příčina: žádná pasáž ji nedokládá → otevřená, bez pasáží
        assertEquals(0, motive.getJSONArray("passages").length())
        // výchozí přehled = odpověď + hlavní rozpor; „otevřené“ je v podrobném auditu karty
        val open = r.card!!.getJSONObject("audit").getJSONArray("open").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(open.toString(), open.any { it.startsWith("Motiv / důvod: nedoloženo – zůstává otevřené") })
        assertTrue(r.text, r.text.contains("**Hlavní rozpor:**") && r.text.contains("motiv"))
    }

    /** Stejná otázka nad předem uloženými webovými stránkami (bez živého webu): stejný verdikt, pasáž bez HTML, u webu datum vydání a zvlášť načtení. */
    @Test fun sameQuestionOverSavedWebPages() {
        fun page(title: String, body: String, url: String, published: String?) = """<!DOCTYPE html><!-- saved from url=(0040)$url --><html><head><title>$title</title>
            <link rel="canonical" href="$url">${published?.let { "<meta property=\"article:published_time\" content=\"$it\">" } ?: ""}
            <script>var x = "3. 1. 2020 kvůli reklamě";</script><style>.a{}</style></head><body><nav>Menu Domů Zprávy</nav><article><h1>$title</h1><p>$body</p></article>
            <footer>© 2026</footer></body></html>"""
        val pages = listOf(
            "rozhodnuti.html" to page("Rozhodnutí", docs[0].second, "https://doprava.example.cz/rozhodnuti-45", "2026-11-02T09:00:00+01:00"),
            "zpravy.html" to page("Most zavřen", docs[1].second, "https://zpravy.example.cz/most", "2026-11-03T07:12:00+01:00"),
            "blog.html" to page("Co s mostem", docs[2].second, "https://blog.example.cz/most", null),
        )
        fun ask(d: List<Pair<String, String>>, mime: String): OpsHarness.Reply {
            val h = OpsHarness(airgap = true); val c = h.o.newChat()
            return h.say(c, "Je doložené, kdy zavřeli most a proč?", d.map { (n, t) -> h.o.attach(n, mime, t.toByteArray()).getString("id") })
        }
        fun statuses(r: OpsHarness.Reply) = r.card!!.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> p.getString("label") + "=" + p.getString("status") } } }
        val local = ask(docs, "text/plain"); val web = ask(pages, "text/html")
        assertEquals(statuses(local), statuses(web))
        val date = web.card!!.getJSONArray("parts").getJSONObject(0)
        val p = date.getJSONArray("passages").let { ps -> (0 until ps.length()).map { ps.getJSONObject(it) } }.first { it.getString("doc") == "rozhodnuti.html" }
        assertEquals("Most přes Labe se uzavírá 3. 11. 2026 od 6:00 pro veškerou dopravu.", p.getString("text"))
        assertEquals("https://doprava.example.cz/rozhodnuti-45", p.getString("url"))
        assertEquals("2026-11-02", p.getString("published"))
        assertEquals(java.time.LocalDate.now().toString(), p.getString("fetched"))
        // stránka bez data vydání: datum vydání se nevymýšlí
        val blogDoc = web.card!!.getJSONArray("parts").let { a -> (0 until a.length()).flatMap { i -> a.getJSONObject(i).getJSONArray("passages").let { ps -> (0 until ps.length()).map { ps.getJSONObject(it) } } } }.firstOrNull { it.getString("doc") == "blog.html" }
        if (blogDoc != null) assertTrue(blogDoc.toString(), !blogDoc.has("published"))
    }
}
