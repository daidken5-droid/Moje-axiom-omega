package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sobota 28. 11. odpoledne R6 (red team: prompter) – dlouhý dotaz s popisem problému není „vložený článek“ k výtahu. */
class TextDigestQuestionTest {
    private val story = "Minulý týden mi přestala fungovat pračka. Při praní se zastaví a bliká kontrolka. Servis mi řekl, že oprava bude stát čtyři tisíce. Pračka je stará šest let a koupil jsem ji za deset tisíc. Nová pračka podobné třídy stojí kolem dvanácti tisíc. Záruka už dávno skončila a jiný servis v okolí bohužel nemám."

    @Test fun storyIsLongEnough() = assertTrue(story.length >= 300, "${story.length}")

    @Test fun questionsAreNotArticles() {
        assertFalse(TextDigest.isPastedText("$story Mám ji opravit, nebo koupit novou? Díky za radu."))
        assertFalse(TextDigest.isPastedText("$story Poraďte mi prosím, co mám dělat."))
        assertFalse(TextDigest.isPastedText("$story Co bys na mém místě udělal ty."))
    }

    @Test fun articleWithRhetoricalQuestionEarlyStaysArticle() {
        val art = "Proč rostou ceny energií? Vláda dnes schválila nový rozpočet na příští rok. Schodek rozpočtu má být 230 miliard korun. " +
            "Opozice kritizuje výdaje na dopravu a vysoký schodek rozpočtu. Rozpočet ještě musí schválit sněmovna, hlasování je naplánováno na prosinec. Ministr financí řekl, že rozpočet počítá s růstem ekonomiky."
        assertTrue(TextDigest.isPastedText(art))
    }
}
