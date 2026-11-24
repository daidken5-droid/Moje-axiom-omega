package cz.axiom.vega.core
import org.junit.Assert.assertTrue
import org.junit.Test
/** Den 24. 11., kolo 14: útrata u konkrétního obchodníka („v Lidlu“, „za Netflix“) a největší jednotlivé platby – bez modelu. */
class MerchantQueryTest {
    private val t = Statement.process("Datum;Částka;Popis\n02.10.2026;-1 000,00;Platba kartou LIDL PRAHA 5\n15.10.2026;-259,00;NETFLIX.COM\n20.10.2026;30 000,00;Mzda\n" +
        "02.11.2026;-200,50;LIDL DEKUJEME\n05.11.2026;-12 500,00;Najem byt\n15.11.2026;-259,00;NETFLIX.COM\n18.11.2026;-3 400,00;ALZA.CZ\n", Statement.detect("Datum;Částka;Popis\n02.11.2026;-1,00;x\n03.11.2026;-1,00;x\n").spec!!).table
    private fun a(q: String) = Statement.spendQuery(t, q)?.replace(' ', ' ')?.replace(' ', ' ') ?: "null"
    @Test fun merchantsAndBiggest() {
        a("kolik jsem utratil v Lidlu?").let { assertTrue(it, it.contains("**−1 200,50 Kč**") && it.contains("2 platby") && it.contains("LIDL")) }
        a("kolik jsem zaplatil za Netflix").let { assertTrue(it, it.contains("**−518,00 Kč**") && it.contains("2 platby")) }
        a("kolik jsem v listopadu utratil v lidlu").let { assertTrue(it, it.contains("listopad 2026") && it.contains("**−200,50 Kč**")) }
        a("jaká byla moje největší platba?").let { assertTrue(it, it.contains("Najem byt") && it.contains("−12 500,00") && it.indexOf("Najem") < it.indexOf("ALZA")) }
        a("co jsem nejvíc zaplatil v říjnu").let { assertTrue(it, it.contains("LIDL") && !it.contains("Najem")) }
        // neznámé zůstává modelu
        org.junit.Assert.assertEquals("null", a("kolik jsem utratil za ponožky"))
    }
}
