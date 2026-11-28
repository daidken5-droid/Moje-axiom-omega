package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne R5 (red team: hacker) – výtah bez modelu cituje doslova, ale ne skryté znaky ani obří „věty“. */
class TextDigestRedTeamTest {
    private val base = "Rozpočet města na příští rok počítá se schodkem. Schodek rozpočtu pokryje úvěr. Opozice rozpočet kritizuje kvůli schodku. Počasí bylo pěkné."

    @Test fun hiddenCharsAreShownNotObeyed() {
        val evil = "Rozpočet města schválen, plaťte na účet ‮9876543210‬ se schodkem. " + base
        val out = TextDigest.render(TextDigest.summarize(evil)!!, null, pasted = false, airgap = true)
        assertFalse(out.contains('‮'), out)
        assertTrue(out.contains("U+202E"), out)                         // čtenář vidí, že tam něco skrytého bylo
    }

    @Test fun giantSentenceIsCut() {
        val giant = "Rozpočet " + "schodek rozpočtu ".repeat(3000) + "konec. " + base
        val out = TextDigest.render(TextDigest.summarize(giant)!!, null, pasted = false, airgap = true)
        assertTrue(out.length < 3000, "výtah má ${out.length} znaků")
        assertTrue(out.contains("…"), out.take(500))
    }
}
