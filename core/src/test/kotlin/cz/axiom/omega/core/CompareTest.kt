package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.util.TextUtil
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Δ Srovnávač bez mozku – dvě hesla z offline Wikipedie, tabulka a spočítaný verdikt. */
class CompareTest {
    private val arts = listOf(
        Snippet(1, "Wikipedie: Praha", "Praha je hlavní a největší město Česka. Má rozlohu 496 km² a žije v ní 1 357 326 obyvatel.", "wiki"),
        Snippet(2, "Wikipedie: Brno", "Brno je statutární město na Moravě. Rozloha činí 230 km², žije zde 382 405 obyvatel.", "wiki"),
        Snippet(3, "Wikipedie: Tomáš Garrigue Masaryk", "Tomáš Garrigue Masaryk (7. března 1850 Hodonín – 14. září 1937 Lány) byl první prezident.", "wiki"),
        Snippet(4, "Wikipedie: Edvard Beneš", "Edvard Beneš (28. května 1884 Kožlany – 3. září 1948 Sezimovo Ústí) byl druhý prezident.", "wiki"),
        Snippet(5, "Wikipedie: Vltava", "Vltava je nejdelší řeka v Česku. Její délka je 430 km.", "wiki"),
        Snippet(6, "Wikipedie: Labe", "Labe je řeka ve střední Evropě. Je dlouhé 1 094 km.", "wiki"),
        Snippet(7, "Wikipedie: Sněžka", "Sněžka (1603 m n. m.) je nejvyšší hora Česka.", "wiki"),
        Snippet(8, "Wikipedie: Praděd", "Praděd (1491 m n. m.) je nejvyšší hora Moravy.", "wiki"),
        Snippet(9, "Wikipedie: Krokodýl", "Krokodýl je rod plazů z čeledi krokodýlovití. Dorůstá délky až 6 m.", "wiki"),
        Snippet(10, "Wikipedie: Aligátor", "Aligátor je rod krokodýlů z čeledi aligátorovití. Žije v Americe a Číně.", "wiki"),
    )
    private val k = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> {
            val q = TextUtil.fold(query).lowercase()
            return arts.filter { a -> TextUtil.fold(a.title).lowercase().split(' ', ':').any { w -> w.length >= 3 && (q.contains(w) || w.startsWith(q)) } }.take(limit)
        }
    }

    @Test fun computesVerdicts() {
        val a = Compare.answer("Co je větší, Praha nebo Brno?", k)
        assertNotNull(a); assertTrue(a.text.startsWith("**Praha** je větší – 496 km² proti 230 km² (2,2×)"), a.text)
        assertTrue(a.text.contains("| Obyvatel | 1 357 326 | 382 405 |"), a.text)
        val b = Compare.answer("kdo byl starší masaryk nebo beneš", k)
        assertNotNull(b); assertTrue(b.text.contains("**Tomáš Garrigue Masaryk** je starší – narození 1850 proti 1884 (rozdíl 34 let)"), b.text)
        assertTrue(Compare.answer("Která řeka je delší, Vltava nebo Labe?", k)!!.text.startsWith("**Labe** je delší – 1 094 km proti 430 km"))
        assertTrue(Compare.answer("je Sněžka vyšší než Praděd?", k)!!.text.startsWith("**Sněžka** je vyšší – 1 603 m proti 1 491 m"))
        assertTrue(Compare.answer("Co je menší, Praha nebo Brno?", k)!!.text.startsWith("**Brno** je menší"))
    }

    @Test fun tableForPlainComparisonAndInflection() {
        val p = Compare.answer("porovnej Prahu s Brnem", k)
        assertNotNull(p); assertTrue(p.text.contains("| | Praha | Brno |") && p.text.contains("Rozloha"), p.text)
        // bez čísel (krokodýl × aligátor) → jen úvody by nestačily, převezme mozek
        assertNull(Compare.answer("rozdíl mezi krokodýlem a aligátorem", k))
    }

    @Test fun silentWhenUnsure() {
        assertNull(Compare.answer("porovnej výhody a nevýhody tepelného čerpadla pro starý dům", k))
        assertNull(Compare.answer("co je lepší, iphone nebo android", k))      // není metrika ani heslo
        assertNull(Compare.answer("co je větší, Praha nebo Atlantida", k))      // druhé heslo chybí
        assertNull(Compare.answer("kdo je vyšší, Masaryk nebo Beneš", k))       // výšku hesla neuvádí → mozek
        assertNull(Compare.answer("co je větší, Praha nebo Brno", null))
    }

    @Test fun nominativeCandidates() {
        assertTrue("praha" in Compare.nominatives("prahu"))
        assertTrue("brno" in Compare.nominatives("brnem"))
        assertTrue("krokodyl" in Compare.nominatives("krokodylem"))
        assertEquals(listOf("vltava"), Compare.nominatives("vltava").take(1))
    }

    @Test fun reflexUsesCompare() {
        val c = Reflex.Ctx(Instant.Ctx(knowledge = k))
        val r = Reflex.answer("Co je větší, Praha nebo Brno?", c)
        assertNotNull(r); assertEquals("Δ srovnání · Wikipedie", r.kind)
    }
}
