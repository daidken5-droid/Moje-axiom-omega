package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sobota 28. 11. (uživatel zvyklý na ChatGPT): „Shrň mi tohle: …“ bez modelu = výtah klíčových vět (doslovně, v původním pořadí), ne „potřebuji model“. */
class TextDigestTest {
    private val text = "Vláda dnes schválila rozpočet na příští rok. Schodek rozpočtu má být 230 miliard korun. Ministr financí řekl, že rozpočet počítá s růstem. " +
        "Opozice kritizuje výdaje na dopravu a schodek rozpočtu. Počasí bylo slunečné. Rozpočet ještě musí schválit sněmovna, hlasování je v prosinci."

    @Test fun picksKeySentencesVerbatimInOrder() {
        val d = TextDigest.summarize(text)
        assertNotNull(d)
        assertTrue(d.size in 2..3, d.toString())
        d.forEach { assertTrue(text.contains(it), "doslovně: $it") }
        assertEquals(d, d.sortedBy { text.indexOf(it) }, "původní pořadí")
        assertTrue(d.none { it.startsWith("Počasí") }, "nesouvisející věta se nevybere: $d")
    }

    @Test fun commandDetection() {
        assertNotNull(TextDigest.request("Shrň mi tohle: $text"))
        assertNotNull(TextDigest.request("shrnutí: $text"))
        assertNotNull(TextDigest.request("Udělej mi výtah z textu: $text"))
        assertNull(TextDigest.request("Shrň mi tohle: krátké"), "krátký text nemá smysl zkracovat")
        assertNull(TextDigest.request("Co je inflace?"))
    }

    @Test fun longOrderIsNotPastedText() {
        assertTrue(TextDigest.isPastedText(text + " Další věta o rozpočtu je tady pro délku textu a ještě jedna věta o schodku."))
        assertTrue(!TextDigest.isPastedText("Napiš e-mail šéfovi, že zítra nepřijdu. Jsem nemocný a mám horečku. Práci dodělám v pondělí ráno. Prezentaci pošlu kolegovi Petrovi. Omluv mě prosím zdvořile a stručně, ať to nezní jako výmluva."))
        assertTrue(TextDigest.followUp("shrň to") && TextDigest.followUp("Zkrať mi to.") && !TextDigest.followUp("shrň výpis z banky za říjen"))
    }

    @Test fun renderKeepsWording() {
        assertEquals("**Výtah bez modelu** – vybrané věty z tvého textu (doslovně, nic nepřevyprávím ani nedomýšlím):\n• A.\n• B.\n\n_Shrnutí vlastními slovy, odpověď na otázku k textu nebo překlad umí model – vypni Bez sítě (nahoře „VEGA ▾“)._",
            TextDigest.render(listOf("A.", "B."), null, pasted = false, airgap = true))
        assertTrue(TextDigest.render(listOf("A."), "clanek.txt", false, false).contains("z „clanek.txt“") )
        assertTrue(TextDigest.render(listOf("A."), null, true, false).startsWith("Vložil/a jsi text. "))
    }

    /** Uživatel ChatGPT: „TL;DR“, „Summarize this:“, slovensky „Zhrň mi to:“. */
    @Test fun englishAndSlovakCommands() {
        for (c in listOf("TL;DR: $text", "tldr: $text", "Summarize this: $text", "Summary: $text", "Zhrň mi to: $text", "zhrnutie: $text"))
            assertNotNull(TextDigest.request(c), c.take(20))
        for (f in listOf("tldr", "TL;DR", "summarize", "summarize it", "zhrň to", "zhrň mi to"))
            assertTrue(TextDigest.followUp(f), f)
    }
}
