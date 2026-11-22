package cz.axiom.vega.probe

import cz.axiom.vega.core.Grounding
import org.junit.Assume.assumeTrue
import org.junit.Test

class SentenceProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val texts = listOf(
            "Zákon platí od 1. ledna 2026 [1]. Týká se všech firem [2].",
            "Podle J. Nováka je inflace 3,5 % [1]. Ministr T. G. Masaryk to potvrdil [2].",
            "Web www.seznam.cz uvádí cenu 1.5 milionu [1]. Další věta [2].",
            "Např. Praha a tzv. Brno mají víc obyvatel [1]. To je fakt [2].",
            "Stalo se to v 19. století [1]. Bylo to v r. 1920 [2].",
            "Prezident řekl: „Zvýšíme daně.“ [1] Opozice nesouhlasila [2].",
            "Je to pravda? [1] Ano, je [2]! Opravdu [3].",
            "Cena je 100 Kč… možná víc [1]. Uvidíme [2].",
            "Firma Apple Inc. vydala iPhone [1]. Prodej rostl [2].",
            "Společnost ČEZ, a.s. zvýšila zisk o 20 % [1]. Akcie vzrostly [2].",
            "Teplota dosáhla 35 °C. [1] Rekord padl [2].",
            "1. Prvním bodem je cena [1].\n2. Druhým bodem kvalita [2].",
            "Plán počítal se stavbou mostu, protože doprava rostla [1].",
            "Výsledek: 3.14 [1]",
        )
        for (t in texts) {
            println("PROBE|TEXT|${t.replace("\n", "⏎")}")
            Grounding.claims(t).forEach { println("PROBE|   claim|${it.cites}|${it.kind}|${it.text}") }
        }
    }
}
