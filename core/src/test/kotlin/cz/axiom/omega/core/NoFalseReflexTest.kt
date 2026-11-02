package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Otázky, které MUSÍ jít mozku – reflex (ani po normalizaci) na ně nesmí odpovědět. */
class NoFalseReflexTest {
    @Test fun brainQuestionsStayWithBrain() {
        val qs = listOf(
            "prosím tě, napiš mi esej o válce", "řekni mi něco o praze", "kolik stojí nové auto", "jak dlouho trvá cesta do brna",
            "kolik je hodin v new yorku", "jaké bude zítra počasí", "spočítej mi hypotéku na 3 miliony na 30 let", "kdo vyhraje mistrovství světa",
            "jaký je kurz bitcoinu", "kolik stál dolar v roce 1990", "víš co je to láska?", "napiš básničku o kočce",
            "kolik obyvatel má praha a jak se to změnilo za 50 let", "kdo napsal tenhle kód", "kdo je nejlepší fotbalista", "proč je nebe modré",
            "vysvětli mi teorii relativity", "kdo byl lepší, havel nebo klaus", "co si myslíš o babišovi", "porovnej babičku a máj",
            "kolik je hodin práce na stavbu domu", "jak napsat životopis", "co je nového", "přelož mi tenhle text do angličtiny",
            "kolik je 5 lidí z 20 v procentech a proč", "jaký je smysl života", "doporuč mi knihu", "kdo je autor tohoto obrázku",
        )
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        val wrong = qs.mapNotNull { q -> Reflex.answer(q, c)?.let { "$q  →  ${it.kind}: ${it.text.take(70)}" } }
        wrong.forEach { println("FALSE  $it") }
        org.junit.Assert.assertTrue("reflex odpověděl místo mozku:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
