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
