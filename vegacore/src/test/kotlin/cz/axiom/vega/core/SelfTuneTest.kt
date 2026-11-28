package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfTuneTest {
    @Test fun correctionAndRephraseAreAttributedToPreviousArea() {
        var t = 1_700_000_000_000L
        val kv = MemKv { t }; val st = SelfTune(kv, "1.4.0", { t })
        assertNull(st.userTurn("spočítej mi výdaje za říjen")); st.routed("záměr:Talk")
        t += 20_000
        assertEquals(SelfTune.Kind.CORRECTION, st.userTurn("ne, myslel jsem jen výdaje z výpisu")); st.routed("nástroj:statement.process")
        t += 30_000
        assertEquals(SelfTune.Kind.REPHRASE, st.userTurn("myslím jen výdaje z výpisu za říjen")) // přeformulováno do 90 s
        t += 600_000
        assertNull(st.userTurn("jaké je počasí"))
        val g = st.groups()
        assertEquals(setOf("záměr:Talk" to SelfTune.Kind.CORRECTION, "nástroj:statement.process" to SelfTune.Kind.REPHRASE), g.map { it.area to it.kind }.toSet())
        assertEquals("1.4.0", st.signals().first().version)
    }

    @Test fun anonymizesAndRotates() {
        val a = SelfTune.anonymize("klíč sk-abcdefghijklmnop12, mail jan.novak@example.cz, účet 123456789/0100, https://banka.cz/x?token=1 a CZ65 0800 0000 1920 0014 5399")
        assertFalse(a.contains("abcdefgh") || a.contains("novak") || a.contains("123456789") || a.contains("token=") || a.contains("1920"), a)
        assertTrue(a.contains("‹url banka.cz›") && a.contains("‹klíč›") && a.contains("‹e-mail›"), a)
        val b = SelfTune.anonymize("heslo je Tajne123, pin 4821, mail jiří.dvořák@seznam.cz, Bearer abcdefghijkl, eyJhbGciOi.eyJzdWIi.sig")
        assertFalse(b.contains("Tajne") || b.contains("4821") || b.contains("jiří") || b.contains("dvořák") || b.contains("abcdefghijkl") || b.contains("eyJ"), b)

        var t = 1_700_000_000_000L
        val kv = MemKv { t }; val st = SelfTune(kv, clock = { t }, keepDays = 30)
        st.observe(SelfTune.Kind.ERROR, "nástroj:csv.inspect", detail = "IllegalStateException")
        t += 31L * 86_400_000
        st.observe(SelfTune.Kind.STOP, "záměr:Research")
        assertEquals(1, kv.keys("selftune/journal/").size, "starý den smazán")
        assertEquals(listOf("STOP"), st.signals().map { it.kind })
        repeat(SelfTune.MAX_PER_DAY + 10) { st.observe(SelfTune.Kind.SLOW, "x") }
        assertEquals(SelfTune.MAX_PER_DAY, st.signals().size)
    }
}
