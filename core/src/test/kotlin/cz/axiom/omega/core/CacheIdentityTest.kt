package cz.axiom.omega.core

import cz.axiom.omega.core.agent.Proof
import cz.axiom.omega.core.agent.Reflex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** HYPERFORGE M3: paměť odpovědí nesmí přenést odpověď na otázku s jiným číslem, rokem, negací, jednotkou nebo jménem. */
class CacheIdentityTest {
    private fun e(q: String, a: String) = Reflex.Entry(q, a, 96, true, System.currentTimeMillis(), Proof.Origin.USER)

    @Test fun differentNumberYearNegationUnitOrNameIsRejected() {
        val cache = listOf(
            e("kolik obyvatel měla Praha v roce 2020", "A"),
            e("je Pluto planeta sluneční soustavy", "B"),
            e("kolik je 5 km v mílích přesně", "C"),
            e("kdo vyhrál mistrovství světa v hokeji 2024", "D"),
            e("jak daleko je z Prahy do Brna autem", "E"),
        )
        assertNull(Reflex.cached("kolik obyvatel měla Praha v roce 2010", cache))
        assertNull(Reflex.cached("není Pluto planeta sluneční soustavy", cache))
        assertNull(Reflex.cached("kolik je 50 km v mílích přesně", cache))
        assertNull(Reflex.cached("kolik je 5 m v mílích přesně", cache))
        assertNull(Reflex.cached("kdo vyhrál mistrovství světa v hokeji 2025", cache))
        assertNull(Reflex.cached("jak daleko je z Prahy do Ostravy autem", cache))
    }

    @Test fun sameQuestionWithPolitenessStillMatches() {
        val cache = listOf(e("je Pluto planeta sluneční soustavy", "Ne, trpasličí planeta."))
        assertEquals("Ne, trpasličí planeta.", assertNotNull(Reflex.cached("je Pluto planeta sluneční soustavy?", cache)).text)
    }
}
