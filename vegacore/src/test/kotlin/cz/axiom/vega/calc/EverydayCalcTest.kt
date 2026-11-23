package cz.axiom.vega.calc

import cz.axiom.vega.core.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan S26 (22. 11.): běžné laické výpočty, které dřív skončily bez odpovědi nebo dokonce webovou rešerší. */
class EverydayCalcTest {
    private fun a(q: String) = Quick.answer(q)

    @Test fun everyday() {
        assertEquals("Z 25 000 na 28 000 je to nárůst o 12 % (o 3 000).", a("zvýšení z 25 000 na 28 000 je kolik procent"))
        assertEquals("Z 120 Kč na 90 Kč je to pokles o 25 % (o 30 Kč).", a("o kolik procent klesla cena ze 120 Kč na 90 Kč"))
        assertEquals("1 500 ÷ 4 = 375 na každého.", a("rozděl 1500 mezi 4 lidi"))
        assertEquals("Průměr 4, 8, 15 je 9 (součet 27 ÷ 3).", a("průměr z 4, 8 a 15"))
        assertEquals("Průměr 4,5, 5,5 je 5 (součet 10 ÷ 2).", a("průměr 4,5; 5,5"))
        assertEquals("Třetina z 900 je 300.", a("třetina z 900"))
        assertEquals("Polovina z 1 234 je 617.", a("polovina z 1 234"))
        assertEquals("2× 450 = 900.", a("dvojnásobek 450"))
        assertEquals("3 × 4,5 Kč = 13,5 Kč.", a("kolik stojí 3 rohlíky po 4,50"))
        assertEquals("2,5 kg × 89 Kč/kg = 222,5 Kč.", a("2,5 kg za 89 Kč/kg"))
        assertEquals("Únor 2028 má 29 dní.", a("kolik dní má únor 2028"))
        assertEquals("Rok 2027 má 365 dní.", a("kolik dní má rok 2027"))
        assertEquals("Den má 86 400 vteřin.", a("kolik vteřin má den"))
        assertEquals("1 galon = 3,7854 l", a("kolik litrů je 1 galon"))
        // slovensky / hovorově (scan S31)
        assertEquals("1 500 ÷ 4 = 375 na každého.", a("rozdel 1500 medzi 4 ludi"))
        assertEquals("Polovina z 800 je 400.", a("polovicu z 800"))
        assertTrue(a("kolik dní má letos únor")!!.matches(Regex("Únor \\d{4} má 2[89] dní\\.")))
        assertEquals("4 × 59 Kč = 236 Kč.", a("4 piva po 59"))
        val h = a("splátka hypotéky 3 000 000 na 30 let s úrokem 5 %")!!
        assertTrue(h, h.contains("16 104,65 Kč měsíčně"))
    }

    @Test fun noFalsePositivesAndNoResearch() {
        assertTrue(a("sleva 30 % z 899 na 629")!!.contains("po slevě zaplatíš 629,3 Kč"))  // sleva má vlastní výpočet, ne „pokles o %“
        assertNull(a("kolik lidí je 5 rodin"))
        assertNull("procentní body, ne výpočet", a("o kolik procent klesla inflace z 15 na 10 %"))
        val ctx = Router.Ctx(false, null, false, false, null)
        for (q in listOf("třetina z 900", "dvojnásobek 450", "polovina z 1 234"))
            assertTrue(q, Router.route(q, ctx) !is Router.Intent.Research)
    }
}

/** Scan S38: cizí měna bez cílové („kolik je 20 liber“, „50 usd“, „kurz eura“) = na koruny; ne webová rešerše. */
class ForeignOnlyCurrencyTest {
    @Test fun toCzk() {
        for ((q, want) in listOf("kolik je 20 liber" to "GBP", "50 usd" to "USD", "100 € kolik Kč" to "EUR", "kurz eura" to "EUR", "kolik stojí euro" to "EUR")) {
            val r = CnbRates.query(q)!!; assertEquals(q, want, r.from); assertEquals(q, "CZK", r.to)
        }
        assertNull(CnbRates.query("100 kč"))
        assertNull(CnbRates.query("kurz angličtiny"))
        assertTrue(Router.route("kurz eura", Router.Ctx(false, null, false, false, null)) !is Router.Intent.Research)
    }
}

/** Denní scan 23. 11., kolo 1: laické dotazy – gramatika splátky, příklad kurzu v měně dotazu, nové formulace. */
class DailyScanMon1Test {
    private val now = java.time.ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, java.time.ZoneId.of("Europe/Prague"))
    private fun a(q: String) = Quick.answer(q, now)
    @Test fun fixes() {
        assertTrue(a("splátka úvěru 200 000 na 4 roky s úrokem 6,9 %")!!.contains("na 4 roky při úroku"))
        assertTrue(a("hypotéka 1 000 000 na 1 rok 5 %")!!.contains("na 1 rok při"))
        assertTrue("dolar → příklad v USD", a("200 dolarů v korunách")!!.contains("„200 usd po 23,5“"))
        assertTrue(a("kurz dolaru")!!.contains("usd po"))
        assertEquals("450 je o 150 víc než 300 (o 50 %).", a("o kolik je 450 víc než 300"))
        assertEquals("Do konce měsíce zbývá 7 dní (30. 11. 2026 (pondělí)).", a("kolik dní zbývá do konce měsíce"))
        assertTrue(a("kolik je to dní od 1. 9. 2026")!!.startsWith("Od 1. 9. 2026 (úterý) uplynulo 83 dní"))
        assertEquals("24. 12. 2026 je čtvrtek.", a("co je za den 24. 12. 2026"))
        assertTrue(a("za 100 dní bude jaké datum")!!.startsWith("Za 100 dní bude 3. 3. 2027"))
        assertTrue(a("1 míle kolik km")!!.contains("1,6093 km"))
        assertEquals("3 dcl = 0,3 l", a("3 dcl v litrech"))
        assertTrue(a("je rok 2100 přestupný")!!.startsWith("Rok 2100 není přestupný"))
        assertTrue(a("je rok 2028 přestupný")!!.startsWith("Rok 2028 je přestupný"))
    }
}

/** Denní scan 23. 11., kolo 2: obrácené převody („1 míle kolik km“) s diakritikou, °C/°F, m², vteřiny, „ve“. */
class DailyScanMon2Test {
    @Test fun conversions() {
        assertEquals("1 míle = 1,6093 km", Quick.answer("1 míle kolik km"))
        assertEquals("100 °F = 37,78 °C", Quick.answer("100 °F kolik °C"))
        assertEquals("30 °C = 86 °F", Quick.answer("kolik je 30 °C ve fahrenheitech"))
        assertEquals("1 ha = 10 000 m2", Quick.answer("1 ha kolik m2"))
        assertEquals("5 minut = 300 s", Quick.answer("kolik vteřin je 5 minut"))
    }
}

/** Denní scan 23. 11., kolo 3: kalkulačka nesmí „ukrást“ dotaz modelu (překlad, psaní, žádost o vysvětlení). */
class DailyScanMon3Test {
    @Test fun modelRequestsAreNotCalculated() {
        for (q in listOf("přelož: rozděl 1500 mezi 4 lidi", "napiš e-mail: kolik dní zbývá do konce měsíce", "z 10 na 12 je kolik procent a jak se to počítá",
                "vysvětli mi, kolik je 15 % z 2400", "napiš 3 věty po 5 slovech", "kolik stojí 3 lístky po 250 a jak je koupit"))
            assertNull(q, Quick.answer(q))
        // čisté výpočty dál fungují
        assertEquals("1 500 ÷ 4 = 375 na každého.", Quick.answer("rozděl 1500 mezi 4 lidi"))
        assertEquals("Z 10 na 12 je to nárůst o 20 % (o 2).", Quick.answer("z 10 na 12 je kolik procent"))
    }
}

/** Denní scan 23. 11., kolo 4: spoření, „ukaž mi paměť“, „smaž historii“, „jak se máš dnes“. */
class DailyScanMon4Test {
    @Test fun savingsAndRouting() {
        val s = Quick.answer("spočítej mi kolik ušetřím když budu měsíčně dávat 2000 na 5 let s úrokem 4 %")!!
        assertTrue(s, s.contains("132 597,96 Kč") && s.contains("vložíš 120 000 Kč"))
        assertTrue(Quick.answer("kolik naspořím když budu odkládat 1000 Kč měsíčně 10 let")!!.contains("120 000 Kč"))  // bez úroku
        assertNull("splátka úvěru není spoření", Quick.answer("splátka úvěru 200 000 na 4 roky s úrokem 6,9 %")?.takeIf { it.contains("ukládat") })
        assertEquals(cz.axiom.vega.core.Router.Intent.WhatDoYouRemember, cz.axiom.vega.core.Router.route("ukaž mi paměť", cz.axiom.vega.core.Router.Ctx(false, null, false, false, null)))
        assertTrue(SmallTalk.answer("smaž historii", true)!!.contains("Smazat"))
        assertEquals("Díky, běžím. S čím pomůžu?", SmallTalk.answer("jak se máš dnes", true))
    }
}

/** Denní scan 23. 11., kolo 5: „prumer 10,20,30“ dával 20,1 (čárka brána jako desetinná); „5*5=“ bez odpovědi. */
class DailyScanMon5Test {
    @Test fun commaListsAndTrailingEquals() {
        assertEquals("Průměr 10, 20, 30 je 20 (součet 60 ÷ 3).", Quick.answer("prumer 10,20,30"))
        assertEquals("Průměr 10, 20 je 15 (součet 30 ÷ 2).", Quick.answer("prumer 10,20"))
        assertEquals("Průměr 4,5, 5,5 je 5 (součet 10 ÷ 2).", Quick.answer("průměr 4,5; 5,5"))   // desetinná čárka dál funguje
        assertEquals("Průměr 1,5, 2,5 je 2 (součet 4 ÷ 2).", Quick.answer("průměr 1,5 2,5"))
        assertEquals("5*5 = 25", Quick.answer("5*5="))
        assertEquals("12+8 = 20", Quick.answer("12+8 ="))
    }
}

/** Denní scan 23. 11., kolo 6: finanční výpočty proti nezávislému vzorci (náhodná zadání, pevné semínko) – na haléř. */
class FinanceCrossCheckTest {
    private fun n(s: String) = s.replace(" ", "").replace(" ", "").replace(',', '.').replace("−", "-").toDouble()
    @Test fun loansAndSavingsMatchFormula() {
        val r = java.util.Random(2311)
        repeat(40) {
            val p = listOf(150_000, 300_000, 1_200_000, 2_500_000)[r.nextInt(4)]; val y = listOf(1, 3, 5, 10, 30)[r.nextInt(5)]; val rate = listOf(0.0, 2.5, 4.9, 6.9)[r.nextInt(4)]
            val q = "splátka úvěru ${Quick.fmtPublic(p.toDouble())} na $y let s úrokem ${Quick.fmtPublic(rate)} %"
            val a = Quick.answer(q) ?: error("bez odpovědi: $q")
            val nn = y * 12; val i = rate / 1200
            val exp = if (i == 0.0) p.toDouble() / nn else p * i / (1 - Math.pow(1 + i, -nn.toDouble()))
            val got = n(Regex(": ([\\d  ,]+) Kč měsíčně").find(a)!!.groupValues[1])
            assertEquals(q, Math.round(exp * 100) / 100.0, got, 0.011)
            val d = listOf(500, 1000, 2500)[r.nextInt(3)]
            val qs = "kolik naspořím když budu měsíčně ukládat $d na $y let s úrokem ${Quick.fmtPublic(rate)} %"
            val fv = if (i == 0.0) d.toDouble() * nn else d * (Math.pow(1 + i, nn.toDouble()) - 1) / i
            assertEquals(qs, Math.round(fv * 100) / 100.0, n(Regex("budeš mít ([\\d  ,]+) Kč").find(Quick.answer(qs)!!)!!.groupValues[1]), 0.011)
        }
    }
}
