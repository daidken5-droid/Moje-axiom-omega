package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTuningTest {
    private val none = RouterTuning.NONE

    /** Uživatel dvakrát narazí: dotaz skončí v rozhovoru, hned ho opraví na konkrétní nástroj → pravidlo se naučí a samo zapne. */
    @Test fun learnsFromCorrectionsAndAppliesOnlyAsFallback() {
        var t = 1_700_000_000_000L
        val kv = MemKv { t }; val st = SelfTune(kv, clock = { t }); val tune = RouterTuning(kv) { t }
        val first = "jaký je otisk té fotky"
        assertEquals("záměr:Talk", SelfTune.area(Router.route(first, none)), "výchozí stav: router to nepozná")
        repeat(2) {
            st.userTurn(first); st.routed(SelfTune.area(Router.route(first, none)))
            t += 15_000
            val fix = "ne, spočítej sha256 té fotky"
            st.userTurn(fix); st.routed(SelfTune.area(Router.route(fix, none)))
            t += 86_400_000 // další den = nezávislý doklad
        }
        val sig = st.signals().filter { it.kind == "CORRECTION" }
        assertEquals(2, sig.size); assertTrue(sig.all { it.area == "záměr:Talk" && it.resolved == "nástroj:hash" }, sig.toString())

        val changed = tune.refresh(st.signals())
        val r = changed.single(); assertEquals("ACTIVE", r.status, r.reason); assertEquals("nástroj:hash", r.target)
        // dlouhá nesouvisející věta se stejnými slovy pravidlo nespustí
        assertEquals("záměr:Talk", SelfTune.area(Router.route("jaký formát fotky je nejlepší a jak se pozná otisk prstu na displeji mobilu, který mám od loňska", none.copy(learned = tune.active()))))
        val ctx = none.copy(learned = tune.active())
        assertEquals("nástroj:hash", SelfTune.area(Router.route("jaký je otisk té fotky z dovolené", ctx)))
        // pevná pravidla a rozhovor beze změny
        assertEquals("záměr:Talk", SelfTune.area(Router.route("jak se máš?", ctx)))
        assertEquals("nástroj:text.diff", SelfTune.area(Router.route("porovnej tyhle dvě verze smlouvy", ctx)))

        // vrácení: pravidlo přestane platit a samo se znovu nezapne
        tune.revert(r.id)
        assertTrue(tune.active().isEmpty())
        tune.refresh(st.signals()); assertTrue(tune.active().isEmpty())
        assertEquals(listOf("návrh", "zapnuto samo", "vráceno uživatelem"), tune.rules().single().history.map { it.action })
    }

    @Test fun guardRejectsRuleThatWouldHijackConversation() {
        val kv = MemKv(); val tune = RouterTuning(kv)
        // „jak se máš“ → nástroj: změnilo by kontrolní případ → nesmí se zapnout
        val bad = RouterTuning.Rule("x", listOf("jak", "mas"), "nástroj:hash", listOf("jak se máš"), 5, "PROPOSED")
        val why = tune.validate(bad, emptyList())
        assertNotNull(why); assertTrue(why.contains("kontrolní případ"), why)
        // akce, která v kontextu nejde spustit (oprava kódu bez otevřeného projektu) → odmítnuto
        val code = RouterTuning.Rule("y", listOf("sleva", "nesedi"), "záměr:Code", listOf("sleva nesedí"), 3, "PROPOSED")
        assertNotNull(tune.validate(code, emptyList()))
        assertNull(tune.validate(code, emptyList()) { Router.Ctx(false, null, true, false, null) })
        assertEquals(setOf("otisk", "fotky", "jaky"), RouterTuning.tokensOf("jaký je otisk té fotky", "ne, spočítej sha256 té fotky"))
    }

    @Test fun sameDayRepeatsAndRiskyTargetsDoNotAutoActivate() {
        val t = 1_700_000_000_000L
        val sig = { at: Long, target: String -> SelfTune.Signal("CORRECTION", "záměr:Talk", "ne, to chci jinak", prevExcerpt = "založ mi věc na faktury", at = at, resolved = target) }
        val tune = RouterTuning(MemKv())
        val a = tune.refresh(listOf(sig(t, "nástroj:hash"), sig(t + 60_000, "nástroj:hash"))).single()
        assertEquals("PROPOSED", a.status, "dvakrát v jedné chvíli = jeden doklad"); assertEquals(1, a.evidence)
        val b = tune.refresh(listOf(sig(t, "záměr:NewProject"), sig(t + 2 * 86_400_000L, "záměr:NewProject"))).single()
        assertEquals("PROPOSED", b.status, "akce s vedlejšími účinky jen jako návrh")
    }
}
