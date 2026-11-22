package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Kolo testů T4: typické tvary exportů českých bank → správné příjmy a výdaje (dřív: částka bez znaménka + sloupec Debet = vše „příjem“). */
class StatementBanksTest {
    private fun sums(text: String): Pair<Double, Double> {
        val d = Statement.detect(text); val spec = assertNotNull("formát nerozpoznán", d.spec).let { d.spec!! }
        val r = Statement.process(text, spec); return r.income to r.expense
    }
    private fun eq(exp: Pair<Double, Double>, text: String) { val (i, e) = sums(text); assertEquals("příjmy", exp.first, i, 0.001); assertEquals("výdaje", exp.second, e, 0.001) }

    @Test fun signedAmountsAndFormats() {
        eq(25000.0 to -1734.5, "Datum;Částka;Měna;Popis\n01.03.2026;25 000,00;CZK;Výplata\n02.03.2026;−1 234,50;CZK;Albert\n03.03.2026;-500,00;CZK;Benzín")
        eq(3000.0 to -450.0, "Číslo účtu: 123456789/0800\nObdobí: 1.3.2026 - 31.3.2026\n\nDatum zaúčtování;Částka;Protistrana;Zpráva\n05.03.2026;3 000,00;Jan Novák;dárek\n06.03.2026;-450,00;Lidl;\nCelkem;2 550,00;;")
        eq(1200.0 to -300.0, "Datum;Příjem;Výdaj;Popis\n01.03.2026;1 200,00;;Vratka\n02.03.2026;;300,00;Kino")
        eq(100.5 to -20.25, "Date,Amount,Description\n2026-03-01,100.50,Refund\n2026-03-02,-20.25,Coffee")
        eq(0.0 to -1234.5, "Datum;Částka;Popis\n01.03.2026;1 234,50-;Platba")
        eq(0.0 to -99.0, "Datum;Částka;Popis\n01.03.2026;(99,00);Poplatek")
        eq(1000.0 to -250.0, "Datum;Částka;Popis\n01.03.2026;1 000,00 CZK;Příjem\n02.03.2026;-250,00 CZK;Nákup")
        eq(1234567.89 to 0.0, "Datum;Částka;Popis\n01.03.2026;1.234.567,89;Prodej bytu")
        eq(0.0 to -10.0, "Datum;Částka;Popis\n01.03.2026;\"-10,00\";\"Platba; kartou\"")
        eq(1000.0 to 0.0, Encoding.decode("Datum;Částka;Popis\n01.03.2026;1 000,00;Výplata žluťoučký".toByteArray(charset("windows-1250"))))
    }

    @Test fun unsignedAmountWithDirectionColumn() {
        eq(100.0 to -40.0, "Datum;Částka;Typ pohybu;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;40,00;Debet;B")
        eq(100.0 to -40.0, "Datum;Částka;Směr;Popis\n01.03.2026;100,00;Příchozí;A\n02.03.2026;40,00;Odchozí;B")
        eq(100.0 to -40.0, "Datum;Částka;D/C;Popis\n01.03.2026;100,00;C;A\n02.03.2026;40,00;D;B")
        // záporná částka + směr: znaménko se neotáčí dvakrát
        eq(100.0 to -40.0, "Datum;Částka;Typ;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;-40,00;Debet;B")
        // popis „Platba“ u příjmu není směr – příjem zůstane příjmem
        eq(100.0 to 0.0, "Datum;Částka;Popis\n01.03.2026;100,00;Platba")
    }

    @Test fun savedProcedureWithDirectionSurvivesReorderedColumns() {
        val spec = Statement.detect("Datum;Částka;Směr;Popis\n01.03.2026;100,00;Příchozí;A\n02.03.2026;40,00;Odchozí;B").spec!!
        val r = Statement.process("Popis;Směr;Datum;Částka\nX;Odchozí;03.03.2026;70,00\nY;Příchozí;04.03.2026;10,00", spec)
        assertEquals(10.0, r.income, 0.001); assertEquals(-70.0, r.expense, 0.001)
        // starý uložený postup (bez directionCol) se načte beze změny
        assertEquals(-1, Statement.ParserSpec.parse(Statement.ParserSpec(";", true, 0, 1, listOf(2)).json().replace(",\"directionCol\":-1", "")).directionCol)
    }
}

class JsonToolEdgeTest {
    @org.junit.Test fun hugeAndDeepJsonDoNotCrash() {
        // dřív StackOverflowError (Error, ne Exception → pád aplikace) – regex nad zprávou knihovny se vstupem
        for (s in listOf("[1]".repeat(3_000), "[".repeat(100_000), "{\"a\":".repeat(50_000), "\"".repeat(5_000), "[1," + "1,".repeat(200_000) + "1]")) {
            val (el, err) = JsonTool.parse(s)
            org.junit.Assert.assertTrue("„${s.take(20)}“: ${err?.take(80)}", el != null || err != null)
        }
        org.junit.Assert.assertTrue(JsonTool.parse("[".repeat(300) + "]".repeat(300)).second!!.contains("vnořený"))
        org.junit.Assert.assertNotNull(JsonTool.parse("[".repeat(150) + "]".repeat(150)).first)
    }
}

/** Scan S4 (22. 11.): české cenovky a velká čísla. */
class NumbersEdgeTest {
    @org.junit.Test fun czechPriceTagIsNotNegative() {
        org.junit.Assert.assertEquals(1000.0, CzNum.parse("1.000,-")!!, 0.0)
        org.junit.Assert.assertEquals(1000.0, CzNum.parse("1.000,-", false)!!, 0.0)
        org.junit.Assert.assertEquals(100.0, CzNum.parse("100,–")!!, 0.0)
        org.junit.Assert.assertEquals(5.0, CzNum.parse("5,- Kč")!!, 0.0)
        // bankovní „250-“ (mínus na konci) zůstává záporné
        org.junit.Assert.assertEquals(-250.0, CzNum.parse("250-")!!, 0.0)
        org.junit.Assert.assertEquals(-1000.0, CzNum.parse("(1 000)")!!, 0.0)
    }
    @org.junit.Test fun hugeResultsAreNotGarbage() {
        val a = cz.axiom.vega.calc.Quick.answer("999999999999 * 999999999999")!!
        org.junit.Assert.assertTrue(a, a.endsWith("= 9,99999999998 × 10^23"))
        org.junit.Assert.assertEquals("1 000 000 * 1 000 000 = 1 000 000 000 000", cz.axiom.vega.calc.Quick.answer("kolik je 1 000 000 * 1 000 000"))
        org.junit.Assert.assertEquals("12345678901 * 1000 = 12 345 678 901 000", cz.axiom.vega.calc.Quick.answer("12345678901 * 1000"))
    }
}

/** Scan S5 (22. 11.): formáty data ve výpisech. */
class DateFormatsTest {
    @org.junit.Test fun formats() {
        val d = mapOf("12.11.99" to "1999-11-12", "01.03.26" to "2026-03-01", "2026/03/01" to "2026-03-01", "01-03-2026" to "2026-03-01",
            "1. března 2026" to "2026-03-01", "5. června 2026" to "2026-06-05", "5. července 2026" to "2026-07-05", "5 červen 2026" to "2026-06-05",
            "2026-03-01T14:22:00" to "2026-03-01", "29.02.2028" to "2028-02-29")
        d.forEach { (raw, iso) -> org.junit.Assert.assertEquals(raw, iso, CzNum.date(raw)) }
        for (bad in listOf("31.02.2026", "29.02.2027", "01.13.2026", "20250115", "1.3.")) org.junit.Assert.assertNull(bad, CzNum.date(bad))
    }
    @org.junit.Test fun statementWithOldTwoDigitYears() {
        val r = Statement.process("Datum;Částka;Popis\n15.12.99;100,00;A\n02.01.00;-40,00;B", Statement.detect("Datum;Částka;Popis\n15.12.99;100,00;A\n02.01.00;-40,00;B").spec!!)
        org.junit.Assert.assertEquals("1999-12-15", r.table.rows[0][0]); org.junit.Assert.assertEquals("2000-01-02", r.table.rows[1][0])
    }
}

/** Scan S9 (22. 11.): dělení odpovědi na ověřitelná tvrzení. */
class ClaimSplitTest {
    private fun c(t: String) = Grounding.claims(t).map { it.cites to it.text }
    @org.junit.Test fun initialsQuotesUnits() {
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Podle J. Nováka je inflace 3,5 %.", listOf(2) to "Ministr T. G. Masaryk to potvrdil."),
            c("Podle J. Nováka je inflace 3,5 % [1]. Ministr T. G. Masaryk to potvrdil [2]."))
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Prezident řekl: „Zvýšíme daně.“", listOf(2) to "Opozice nesouhlasila."),
            c("Prezident řekl: „Zvýšíme daně.“ [1] Opozice nesouhlasila [2]."))
        org.junit.Assert.assertEquals(listOf(listOf(1) to "Teplota dosáhla 35 °C.", listOf(2) to "Rekord padl."), c("Teplota dosáhla 35 °C. [1] Rekord padl [2]."))
        org.junit.Assert.assertEquals(2, c("Zákon platí od 1. ledna 2026 [1]. Týká se všech firem [2].").size)
        org.junit.Assert.assertEquals(2, c("Např. Praha a tzv. Brno mají víc obyvatel [1]. To je fakt [2].").size)
    }
}

/** Scan S15 (22. 11.): paměť si nesmí uložit PIN, CVV, SMS kód ani heslo v laickém tvaru. */
class SecretMemoryTest {
    @org.junit.Test fun laikSecretsRejectedButNormalTextAllowed() {
        for (s in listOf("můj PIN 4321", "pin kód od trezoru 0000", "CVV 123", "kód z SMS 482913", "ověřovací kód 123456", "heslo k e-mailu: Pes", "přihlašovací jméno jan.novak a heslo xyz12345"))
            org.junit.Assert.assertTrue(s, Mem.looksSecret(s))
        for (s in listOf("mám psa Rexe", "zapomněl jsem heslo k bance, co mám dělat", "jak si vytvořit silné heslo", "PIN kód nefunguje, co s tím", "pinMode(13, OUTPUT)", "const PIN_LENGTH = 4", "kód programu je v souboru main.kt"))
            org.junit.Assert.assertFalse(s, Mem.looksSecret(s))
    }
}

/** Scan S17 (22. 11.): kontrola síly WiFi hesla – tahy po klávesnici a „slovo + rok“ jsou slabé; vlastní návrhy nikdy. */
class WifiPasswordTest {
    @org.junit.Test fun weakPatterns() {
        for (p in listOf("1qaz2wsx3edc", "zxcvbnm123", "qwertzuiop", "asdfghjkl1", "Monika1990", "Praha2024!"))
            org.junit.Assert.assertTrue(p, WifiTools.checkPassword(p).score <= 1)
        for (p in listOf("Xk9#mQ2\$vL7pR4", "kočka-strom-okno-mrak", "correct horse battery staple"))
            org.junit.Assert.assertEquals(p, 4, WifiTools.checkPassword(p).score)
    }
    @org.junit.Test fun ownSuggestionsAreNeverFlaggedWeak() {
        val rnd = java.util.Random(7)
        repeat(300) { val p = WifiTools.passphrase(rnd); org.junit.Assert.assertTrue("$p → ${WifiTools.checkPassword(p)}", WifiTools.checkPassword(p).score >= 3) }
    }
}

/** Scan S18 (22. 11.): WiFi QR (escapování, hex hodnoty) a čtení zabezpečení. */
class WifiQrSecurityTest {
    @org.junit.Test fun qrPayloads() {
        org.junit.Assert.assertEquals("""WIFI:T:WPA;S:Doma\;5G;P:a\:b\,c\\d\";;""", WifiTools.qrPayload("Doma;5G", """a:b,c\d""" + "\""))
        org.junit.Assert.assertEquals("WIFI:T:WPA;S:Doma;P:\"ABCD1234\";;", WifiTools.qrPayload("Doma", "ABCD1234"))
        org.junit.Assert.assertEquals("WIFI:T:nopass;S:Kavarna;H:true;;", WifiTools.qrPayload("Kavarna", "", hidden = true))
    }
    @org.junit.Test fun securityWarnings() {
        org.junit.Assert.assertTrue(WifiScan.security("[WPA2-PSK-TKIP][ESS]").second!!.contains("TKIP"))
        org.junit.Assert.assertNull(WifiScan.security("[WPA2-PSK-CCMP][ESS]").second)
        org.junit.Assert.assertNull(WifiScan.security("[WPA2-PSK-CCMP+TKIP][ESS]").second)
        org.junit.Assert.assertEquals("WPA3", WifiScan.security("[RSN-SAE-CCMP][ESS]").first)
        org.junit.Assert.assertTrue(WifiScan.security("[WEP][ESS]").second!!.contains("WEP"))
        org.junit.Assert.assertEquals("Otevřená", WifiScan.security("[ESS]").first)
    }
}

/** Scan S20 (22. 11.): offline hledání v dokumentech česky – koncovky, stop-slova, název dokumentu. */
class RagCzechTest {
    private val idx = Rag.build(mapOf(
        "smlouva.pdf" to "Nájemní smlouva. Výpověď z nájmu musí být písemná. Výpovědní lhůta činí tři měsíce. Nájemné je splatné vždy do 5. dne v měsíci.",
        "faktura.pdf" to "Faktura č. 2026-001. Odběratel: Jan Novák. Celkem k úhradě 12 100 Kč včetně DPH.",
        "recept.txt" to "Svíčková na smetaně. Maso naložte přes noc do zeleniny. Pečte dvě hodiny."))
    private fun top(q: String) = idx.query(q, 1).firstOrNull()?.passage?.source
    @org.junit.Test fun czechQueries() {
        org.junit.Assert.assertEquals("smlouva.pdf", top("do kdy platim najem"))
        org.junit.Assert.assertEquals("faktura.pdf", top("kolik mam zaplatit za fakturu"))
        org.junit.Assert.assertEquals("recept.txt", top("jak dlouho péct svíčkovou"))
        org.junit.Assert.assertEquals("recept.txt", top("co je v receptu"))
        org.junit.Assert.assertNull(top("zcela nesouvisející dotaz o fotbale"))
    }
}

/** Scan S22 (22. 11.): „Kč“ a „CZK“ v jednom výpisu jsou jedna měna, „€“ a „EUR“ taky. */
class StatementCurrencyTest {
    @Test fun sameCurrencyDifferentSpelling() {
        val csv = "Datum;Částka;Měna;Popis\n01.03.2026;1 000,00;Kč;Výplata\n02.03.2026;-250,00;CZK;Nákup\n03.03.2026;-50,00;kč;Lidl\n04.03.2026;-10,00;€;Káva\n05.03.2026;-5,00;EUR;Voda"
        val d = Statement.detect(csv); val r = Statement.process(csv, d.spec!!)
        assertEquals(1000.0, r.income, 0.001); assertEquals(-300.0, r.expense, 0.001)
        val curs = r.table.rows.map { it[2] }.toSet()
        assertEquals(setOf("CZK", "EUR"), curs)
        assertEquals("USD", Statement.currency("$")); assertEquals("CZK", Statement.currency(" ")); assertEquals("PLN", Statement.currency("pln"))
    }
}

/** Scan S34 (22. 11.): název konverzace v menu – jeden řádek, celé slovo, žádné heslo, příloha místo prázdna. */
class ChatTitleTest {
    @Test fun titles() {
        assertEquals("Kolik je 15 % z 2400", Mem.chatTitle("Kolik je 15 % z 2400"))
        assertEquals("Napiš mi omluvný e-mail šéfovi, že…", Mem.chatTitle("Napiš mi omluvný e-mail šéfovi, že zítra nepřijdu do práce kvůli nemoci"))
        assertEquals("Ahoj jak se máš", Mem.chatTitle("Ahoj\n\njak   se\tmáš"))
        assertEquals("📎 vypis.csv", Mem.chatTitle("  ", listOf("vypis.csv")))
        assertEquals("Citlivá zpráva", Mem.chatTitle("moje heslo: Tajne123 si zapiš"))
        assertEquals("Nový chat", Mem.chatTitle(""))
        val emoji = Mem.chatTitle("😀".repeat(30))
        assertEquals("rozpůlené emoji", false, emoji.dropLast(1).lastOrNull()?.let { Character.isHighSurrogate(it) } ?: false)
        org.junit.Assert.assertTrue(emoji.length <= 41)
    }
}
