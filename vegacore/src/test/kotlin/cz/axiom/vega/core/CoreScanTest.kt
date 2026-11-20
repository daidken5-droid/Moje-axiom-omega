package cz.axiom.vega.core

import cz.axiom.vega.calc.Quick
import cz.axiom.vega.llm.CostGuard
import cz.axiom.vega.net.Http
import cz.axiom.vega.net.NetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Noční scan nového jádra (bod 1): SSRF přes jméno/IPv6, souběh rozpočtu, falešné „datum“. */
class CoreScanTest {
    @Test fun privateHostVariants() {
        for (h in listOf("localhost.", "foo.localhost", "[::ffff:127.0.0.1]", "::ffff:7f00:1", "[fd00::1]", "fe80::1", "0.0.0.0", "239.1.1.1", "100.64.1.1", ""))
            assertTrue(Http.isPrivateHost(h), h)
        for (h in listOf("example.org", "8.8.8.8", "[2001:4860:4860::8888]")) assertFalse(Http.isPrivateHost(h), h)
        // jméno, které se přeloží na smyčku, veřejný web nesmí otevřít (DNS → 127.0.0.1)
        val e = runCatching { Http.requirePublicResolved("localhost") }.exceptionOrNull()
        assertTrue(e is NetException, e.toString())
        // nepřeložitelné jméno nehází (spojení samo selže)
        Http.requirePublicResolved("neexistuje.invalid")
    }

    @Test fun budgetReservationIsAtomic() {
        var spent = 0.0
        val g = CostGuard(object : CostGuard.Store {
            override fun allowed(provider: String) = true
            override fun budgetUsd(provider: String) = 1.0
            override fun spentUsd(provider: String) = spent
            override fun addSpent(provider: String, usd: Double) { spent += usd }
        })
        val one = g.estimateUsd("deepseek", "deepseek-chat", 10_000, 4096)
        val fits = (1.0 / one).toInt()
        val ok = AtomicInteger(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(16)
        repeat(fits * 3) { pool.submit { start.await(); if (g.tryReserve("deepseek", "deepseek-chat", 10_000, 4096).first is CostGuard.Decision.Allow) ok.incrementAndGet() } }
        start.countDown(); pool.shutdown(); pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(fits, ok.get(), "rezervace nesmí přesáhnout strop ani při souběhu")
    }

    @Test fun quickDoesNotHijackSentences() {
        assertNull(Quick.answer("dnes je hezky"))
        assertNull(Quick.answer("dneska je zima, co na sebe?"))
        assertTrue(Quick.answer("jaké je dnes datum?")!!.startsWith("Dnes je"))
        assertTrue(Quick.answer("převeď 3 oz na g")!!.contains("85,0486"))
    }
}

class WebParseTest {
    @Test fun duckDuckGoSample() {
        val html = javaClass.getResource("/web/ddg-sample.html")!!.readText()
        val r = cz.axiom.vega.web.Web.duckParse(html, 6)
        assertEquals(3, r.size, r.toString())
        assertEquals("https://en.wikipedia.org/wiki/Manhattan_Project", r[0].source)
        assertTrue(r[0].text.contains("Manhattan") && !r[0].text.contains("<b>"))
        assertTrue(cz.axiom.vega.web.Web.duckParse("<div class=\"anomaly-modal\">", 6).isEmpty())
    }
}

class QuickPlusTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun q(s: String) = cz.axiom.vega.calc.Quick.answer(s, now)

    @Test fun vatPercentDatesAge() {
        assertTrue(q("1000 Kč bez DPH")!!.contains("1 210 Kč s DPH"), q("1000 Kč bez DPH"))
        assertTrue(q("kolik je 1 210 s DPH bez daně")!!.contains("1 000 Kč bez DPH"), q("kolik je 1 210 s DPH bez daně"))
        assertTrue(q("500 bez DPH 12 %")!!.contains("560 Kč"), q("500 bez DPH 12 %"))
        assertTrue(q("DPH z 2000")!!.contains("420 Kč"), q("DPH z 2000"))
        assertTrue(q("o kolik procent je 120 víc než 100?")!!.contains("20 % víc"))
        assertTrue(q("změna z 200 na 150 v %")!!.contains("pokles o 25 %"))
        assertTrue(q("kolik procent je 30 ze 120")!!.contains("25 %"))
        assertEquals("Za 30 dní bude 20. 12. 2026 (neděle).", q("za 30 dní"))
        assertTrue(q("jaký den v týdnu je 24. 12. 2026")!!.endsWith("čtvrtek."))
        assertTrue(q("kolik let je od 5. 3. 1990")!!.contains("uplynulo 36 let"), q("kolik let je od 5. 3. 1990"))
        // běžné věty se nesmí chytit
        assertNull(q("zítra pojedu za babičkou na 3 dny"))
        assertNull(q("jaké je DPH na knihy v Česku"))
    }
}

class QuickUnitsTest {
    private fun q(s: String) = cz.axiom.vega.calc.Quick.answer(s)
    @Test fun moreConversions() {
        assertTrue(q("převeď 2 ha na m2")!!.contains("20 000"), q("převeď 2 ha na m2"))
        assertTrue(q("3 hrnky na dl")!!.contains("7,2"), q("3 hrnky na dl"))
        assertTrue(q("2 lžíce na ml")!!.contains("30"), q("2 lžíce na ml"))
        assertTrue(q("500 GB na GiB")!!.contains("465,66"), q("500 GB na GiB"))
        assertTrue(q("90 min na h")!!.contains("1,5"), q("90 min na h"))
        assertTrue(q("2000 kcal na kJ")!!.contains("8 368"), q("2000 kcal na kJ"))
        assertTrue(q("10 m² na ft²")!!.contains("107,6391"), q("10 m² na ft²"))
        assertNull(q("2 kg na km")) // různé veličiny
    }
}
