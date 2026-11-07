package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AnswerContract
import cz.axiom.omega.core.agent.Instant
import cz.axiom.omega.core.agent.Reflex
import cz.axiom.omega.core.facts.FactStore
import cz.axiom.omega.core.facts.IsoReflex
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HYPERFORGE M4: vertikál ISO 3166-1 / 4217 z Verified Fact Store (CLDR). Orákulum = ručně známé kódy ISO. */
class IsoVerticalTest {
    private val today = LocalDate.of(2026, 11, 3)
    private fun a(q: String) = IsoReflex.answer(q, today)
    private fun has(q: String, vararg parts: String) {
        val r = assertNotNull(a(q), q)
        parts.forEach { assertTrue(it in r.text, "$q ⇒ chci „$it“, dostal: ${r.text}") }
        assertEquals(AnswerContract.Type.VERIFIED_FACT, r.contract?.type)
    }

    @Test fun storeIntegrity() {
        val s = FactStore.load("iso")
        assertEquals(FactStore.Integrity.OK, s.integrity)
        assertEquals(249, s.entities().size)
        // podvržený obsah → obchod prázdný, reflex mlčí
        val bad = FactStore.parse("factId\tx\n".toByteArray(), "0".repeat(64))
        assertEquals(FactStore.Integrity.CHECKSUM_MISMATCH, bad.integrity)
        assertTrue(bad.facts.isEmpty())
    }

    @Test fun codesWithCzechDeclension() {
        has("ISO kód Německa", "DE", "DEU", "276")
        has("jaký je kód země Česko", "CZ", "CZE", "203")
        has("třípísmenný kód Francie", "FRA")
        has("číselný kód ISO Japonska", "392")
        has("dvoupísmenný kód Spojeného království", "GB")
        has("ISO 3166 kód Spojených států", "US", "USA", "840")
        has("iso kód Rakouska", "AT", "AUT", "040")
        has("alpha-3 kód Švýcarska", "CHE")
        has("ISO kód Jižního Súdánu", "SS", "SSD")
        has("ISO kód Súdánu", "SD", "SDN")
        has("ISO kód Iráku", "IQ", "IRQ")
        has("ISO kód Íránu", "IR", "IRN")
    }

    @Test fun reverseLookup() {
        has("co je ISO 3166 kód CZ", "Česko")
        has("která země má kód DEU", "Německo")
        has("co znamená kód JP v ISO", "Japonsko")
    }

    @Test fun currency() {
        has("jakou měnu má Gruzie", "GEL")
        has("v čem se platí v Maďarsku", "HUF")
    }

    @Test fun abstainsOnOtherQuestions() {
        assertNull(a("jaký je kód na wifi"))
        assertNull(a("kód Morseovy abecedy pro písmeno A"))
        assertNull(a("PSČ Prahy"))
        // Guinea × Rovníková Guinea × Guinea-Bissau × Papua Nová Guinea: buď správně Guinea, nebo zdržet se – nikdy jiná
        a("ISO kód Guinea")?.let { assertTrue("GN" in it.text && "GNQ" !in it.text && "GNB" !in it.text, it.text) }
        has("ISO kód Rovníkové Guineje", "GQ")
        has("ISO kód Guineje", "GN")
        assertNull(a("jaké je ISO 9001"))
        assertNull(a("co je kód XYZ"))
    }

    @Test fun fullPathKeepsContract() {
        val r = assertNotNull(Reflex.answer("ISO kód Polska", Reflex.Ctx(Instant.Ctx(), today = today)))
        assertTrue("PL" in r.text && "POL" in r.text, r.text)
        assertEquals(AnswerContract.Type.VERIFIED_FACT, r.contract?.type)
        assertTrue(r.contract!!.evidenceRefs.any { "CLDR" in it })
    }
}
