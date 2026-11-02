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
            "kolik stojí hodinky", "kdy pojede vlak do brna", "jak se jmenuje můj pes", "what is love", "explain quantum physics",
            "kto je najlepší futbalista", "aký je zmysel života", "koľko stojí auto", "hele vole napiš mi rap", "co je dneska v kině",
            "kolik je hodin cesty do prahy", "jakej je nejlepší telefon", "kdo vyhrál včera hokej", "kde je nejbližší lékárna",
            "kolik vydělává programátor", "jak uvařit svíčkovou", "napiš mail šéfovi", "vysvětli mi to jak pětiletýmu",
            "what is the meaning of life", "write me a poem", "how to cook pasta", "translate hello to spanish",
        )
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        val wrong = qs.mapNotNull { q -> Reflex.answer(q, c)?.let { "$q  →  ${it.kind}: ${it.text.take(70)}" } }
        wrong.forEach { println("FALSE  $it") }
        org.junit.Assert.assertTrue("reflex odpověděl místo mozku:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}

class OfflineAnswerTest {
    @Test fun extractsRelevantSentencesWithSource() {
        val sn = listOf(
            Snippet(1, "Tepelné čerpadlo", "Tepelné čerpadlo je zařízení, které odebírá teplo z okolí. Funguje na principu obráceného chladicího cyklu. Kompresor stlačuje chladivo a tím zvyšuje jeho teplotu. Vynalezeno bylo v 19. století.", "wiki"),
            Snippet(2, "Kompresor", "Kompresor je stroj na stlačování plynů.", "wiki"),
        )
        val a = OfflineAnswer.compose("Jak funguje tepelné čerpadlo?", sn, "offline")!!
        org.junit.Assert.assertTrue(a.text, a.text.contains("obráceného chladicího cyklu") && a.text.contains("Tepelné čerpadlo"))
        org.junit.Assert.assertNull(OfflineAnswer.compose("Kdo vyhrál mistrovství v hokeji?", sn, "offline"))
    }
}
