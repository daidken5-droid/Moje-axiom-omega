package cz.axiom.vega.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VÝBĚR REAKCE PODLE ZÁMĚRU na příkladech, které při ladění pravidel NEBYLY použity (sada „held-out“, sepsaná předem).
 * Ladicí příklady jsou ve VegaCoreTest; tady se kontroluje, že změna funguje obecně.
 */
class RouterHeldOutTest {
    private val none = Router.Ctx(false, null, false, false, null)
    private val proj = Router.Ctx(false, null, true, false, null)
    private val res = Router.Ctx(false, null, false, true, null)

    private fun kind(i: Router.Intent) = when (i) {
        is Router.Intent.Research -> "research"; is Router.Intent.Hypothesis -> "hypothesis"
        is Router.Intent.Talk -> if (i.creative) "creative" else if (i.thinkingAloud) "aloud" else "talk"
        is Router.Intent.Tool, is Router.Intent.Code, is Router.Intent.NewProject, Router.Intent.BuildStatementTool, Router.Intent.Demand, Router.Intent.Nfc -> "tool"
        Router.Intent.Continue -> "continue"; Router.Intent.MakeButton -> "button"; is Router.Intent.FixColumn -> "fix"
        is Router.Intent.Remember -> "remember:" + (i.scope ?: "?"); else -> i.javaClass.simpleName
    }

    private val cases = listOf(
        // holý název → identifikace a zdroje
        Triple("operation Prato", none, "research"), Triple("Projekt Manhattan", none, "research"), Triple("kauza Dozimetr", none, "research"),
        Triple("Bitva u Slavkova", none, "research"), Triple("operace Anthropoid", none, "research"),
        // neobvyklá hypotéza → opora + alternativy
        Triple("Je možné, že pyramidy postavili s pomocí nějaké ztracené technologie?", none, "hypothesis"),
        Triple("Mám teorii, že fluor ve vodě nás dělá poslušnými", none, "hypothesis"),
        Triple("Co když přistání na Měsíci bylo natočené ve studiu?", none, "hypothesis"),
        Triple("ve skutečnosti za inflací stojí jen chamtivost obchodníků, ne?", none, "hypothesis"),
        // kreativní → odlišné návrhy
        Triple("vymysli mi pět názvů pro kavárnu u řeky", none, "creative"), Triple("napiš básničku k narozeninám pro babičku", none, "creative"),
        Triple("potřebuju slogan pro cyklistický obchod", none, "creative"), Triple("brainstorm témat na firemní teambuilding", none, "creative"),
        // jednoznačný pracovní pokyn → nástroj
        Triple("spočítej sha256 tohohle souboru", none, "tool"), Triple("porovnej tyhle dvě verze smlouvy", none, "tool"),
        Triple("najdi v mých souborech fakturu od Alzy", none, "tool"), Triple("přidej do projektu export do CSV", proj, "tool"),
        Triple("spusť testy", proj, "tool"), Triple("založ projekt kalkulačka hypotéky", none, "tool"),
        Triple("pošli dlužníkovi předžalobní výzvu", none, "tool"), Triple("přečti https://example.org/clanek a shrň ho", none, "tool"),
        // navázání, tlačítko, oprava sloupce, pravidla
        Triple("navaž na minulou práci", none, "continue"), Triple("pokračujme v projektu", none, "continue"),
        Triple("ulož mi to jako tlačítko", res, "button"), Triple("z toho udělej tlačítko", res, "button"),
        Triple("datum je ve sloupci Splatnost", res, "fix"), Triple("ne, částka je v sloupci „Objem v Kč“", res, "fix"),
        Triple("v tomhle projektu piš komentáře anglicky", proj, "remember:project"), Triple("všude používej desetinnou čárku", none, "remember:global"),
        // obyčejný rozhovor a přemýšlení nahlas nesmí spustit akci
        Triple("jak se máš?", none, "talk"), Triple("přemýšlím, jestli si vzít hypotéku teď, nebo počkat", none, "aloud"),
        Triple("co bys dělal na mém místě, když mi šéf nedal přidání?", none, "talk"),
    )

    @Test fun heldOutIntents() {
        val bad = cases.mapNotNull { (t, c, want) -> val got = kind(Router.route(t, c)); if (got == want) null else "„$t“: čekáno $want, je $got" }
        val acc = (cases.size - bad.size) * 100 / cases.size
        println("Held-out záměry: ${cases.size - bad.size}/${cases.size} ($acc %)\n" + bad.joinToString("\n"))
        assertTrue("přesnost $acc % < 90 %:\n" + bad.joinToString("\n"), acc >= 90)
    }
}
