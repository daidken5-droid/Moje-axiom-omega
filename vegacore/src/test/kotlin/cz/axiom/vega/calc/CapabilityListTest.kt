package cz.axiom.vega.calc

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne R3 (red team: investor) – „co umíš?“ ukazuje i dnešní dovednosti a každý příklad v něm opravdu funguje bez modelu. */
class CapabilityListTest {
    @Test fun everyAdvertisedExampleWorksOffline() {
        assertTrue(SmallTalk.QUICK_EXAMPLES.size >= 5)
        SmallTalk.QUICK_EXAMPLES.forEach { assertNotNull(Quick.answer(it), "„$it“ je v nabídce „co umíš“, ale bez modelu na něj nic neodpovím") }
    }

    @Test fun helpMentionsTodaysSkills() {
        val r = SmallTalk.answer("co umíš?", airgap = true)!!
        listOf("kdo má dnes svátek", "DPH", "shrň", "ranní přehled").forEach { assertTrue(r.contains(it, ignoreCase = true), "chybí „$it“: $r") }
        SmallTalk.QUICK_EXAMPLES.forEach { assertTrue(r.contains("„$it“"), "příklad „$it“ chybí v nápovědě: $r") }
        val hi = SmallTalk.answer("ahoj", airgap = true)!!
        assertTrue(hi.length < 260, "pozdrav má být krátký (${hi.length} znaků): $hi")
    }
}
