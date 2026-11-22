package cz.axiom.vega.probe

import cz.axiom.vega.core.Rag
import org.junit.Assume.assumeTrue
import org.junit.Test

class RagProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val docs = mapOf(
            "smlouva.pdf" to "Nájemní smlouva. Výpověď z nájmu musí být písemná. Výpovědní lhůta činí tři měsíce a počíná běžet prvním dnem měsíce následujícího po doručení výpovědi. Nájemné je splatné vždy do 5. dne v měsíci.",
            "faktura.pdf" to "Faktura č. 2026-001. Dodavatel: Firma s.r.o. Odběratel: Jan Novák. Celkem k úhradě 12 100 Kč včetně DPH. Splatnost 14 dní.",
            "recept.txt" to "Svíčková na smetaně. Maso naložte přes noc do zeleniny. Pečte dvě hodiny při 160 stupních. Podávejte s knedlíkem a brusinkami.",
            "pojistka.pdf" to "Pojistná smlouva domácnosti. Pojištění kryje škody způsobené požárem, vodou z vodovodního zařízení a krádeží vloupáním. Spoluúčast činí 1 000 Kč.")
        val idx = Rag.build(docs)
        for (q in listOf("vypoved z najmu", "kolik je vypovedni lhuta", "do kdy platim najem", "kolik mam zaplatit za fakturu", "jak dlouho péct svíčkovou", "kryje pojistka vytopeni od souseda",
            "spoluucast", "DPH", "splatnost", "co je v receptu", "kdo je odběratel", "zcela nesouvisející dotaz o fotbale"))
            println("PROBE|$q|${idx.query(q, 2).joinToString(" || ") { "${it.passage.source} ${"%.2f".format(it.score)}: ${it.passage.text.take(50)}" }}")
    }
}
