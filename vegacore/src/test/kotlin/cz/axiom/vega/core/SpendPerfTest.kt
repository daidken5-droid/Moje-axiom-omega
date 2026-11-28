package cz.axiom.vega.core
import org.junit.Assert.assertTrue
import org.junit.Test
/** Den 24. 11., kolo 15: dotazy nad velkým výpisem (20 000 pohybů, ~roky historie) do 1,5 s i na pomalém telefonu (tady s rezervou ×4). */
class SpendPerfTest {
    @Test fun bigStatementQueriesAreFast() {
        val shops = listOf("LIDL", "ALBERT", "NETFLIX.COM", "SHELL", "Najem byt", "ALZA.CZ", "Kavarna U Lva", "Platba kartou 4421 XYZ")
        val sb = StringBuilder("Datum;Částka;Popis\n"); val d0 = java.time.LocalDate.of(2024, 1, 1)
        for (i in 0 until 20_000) sb.append("${d0.plusDays((i / 20).toLong()).let { "${it.dayOfMonth}.${it.monthValue}.${it.year}" }};${if (i % 15 == 0) "30 000,00" else "-${100 + i % 900},50"};${shops[i % shops.size]} $i\n")
        val txt = sb.toString(); val t = Statement.process(txt, Statement.detect(txt).spec!!).table
        val qs = listOf("kolik jsem utratil v Lidlu", "kolik jsem utratil za jídlo v březnu", "jaká byla největší platba", "kolik jsem ušetřil minulý měsíc", "kolik mi přišlo", "jaké mám pravidelné platby", "kolik jsem celkem utratil", "kolik jsem utratil za ponožky")
        Statement.spendQuery(t, qs[0]) // zahřátí JIT
        val t0 = System.nanoTime(); qs.forEach { Statement.spendQuery(t, it) }; val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("8 dotazů nad 20 000 pohyby: $ms ms", ms < 1500 / 4 * 8)
        val one = qs.associateWith { q -> val s = System.nanoTime(); Statement.spendQuery(t, q); (System.nanoTime() - s) / 1_000_000 }
        // podruhé nad stejným výpisem (kategorie už spočítané) a neznámé slovo bez kompilace regexu na řádek
        assertTrue("jednotlivě: $one", one.values.all { it < 300 })  // časy kolísají se zátěží stroje (CI) – hlavní pojistka je řádek níže
        org.junit.Assert.assertSame("kategorie stejného výpisu se nepočítají znovu", Statement.categories(t), Statement.categories(t))
    }
}
