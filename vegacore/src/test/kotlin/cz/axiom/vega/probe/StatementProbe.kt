package cz.axiom.vega.probe

import cz.axiom.vega.core.Statement
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Průzkum výpisů (jen -Dprobe=1): vypíše detekci a souhrn pro typické tvary exportů českých bank. */
class StatementProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val samples = linkedMapOf(
            // očekávání v názvu: příjmy / výdaje
            "A středník, záporné −, mezery [P 25000 V 1734,5]" to "Datum;Částka;Měna;Popis\n01.03.2026;25 000,00;CZK;Výplata\n02.03.2026;−1 234,50;CZK;Albert\n03.03.2026;-500,00;CZK;Benzín",
            "B úvodní řádky + souhrn [P 3000 V 450]" to "Číslo účtu: 123456789/0800\nObdobí: 1.3.2026 - 31.3.2026\n\nDatum zaúčtování;Částka;Protistrana;Zpráva\n05.03.2026;3 000,00;Jan Novák;dárek\n06.03.2026;-450,00;Lidl;\nCelkem;2 550,00;;",
            "C Příjem/Výdaj zvlášť [P 1200 V 300]" to "Datum;Příjem;Výdaj;Popis\n01.03.2026;1 200,00;;Vratka\n02.03.2026;;300,00;Kino",
            "D čárka + tečka desetinná [P 100.5 V 20.25]" to "Date,Amount,Description\n2026-03-01,100.50,Refund\n2026-03-02,-20.25,Coffee",
            "E mínus na konci [P 0 V 1234,5]" to "Datum;Částka;Popis\n01.03.2026;1 234,50-;Platba",
            "F závorky = záporné [P 0 V 99]" to "Datum;Částka;Popis\n01.03.2026;(99,00);Poplatek",
            "G CZK u částky [P 1000 V 250]" to "Datum;Částka;Popis\n01.03.2026;1 000,00 CZK;Příjem\n02.03.2026;-250,00 CZK;Nákup",
            "H tabulátor [P 500 V 0]" to "Datum\tČástka\tPopis\n01.03.2026\t500,00\tX",
            "I uvozovky se středníkem v popisu [P 0 V 10]" to "Datum;Částka;Popis\n01.03.2026;\"-10,00\";\"Platba; kartou\"",
            "J tisíce tečkou [P 1234567,89 V 0]" to "Datum;Částka;Popis\n01.03.2026;1.234.567,89;Prodej bytu",
            "K datum 1.3.2026 bez nul [P 50 V 0]" to "Datum;Částka;Popis\n1.3.2026;50;X",
            "M kladná částka + Typ Debet/Kredit [P 100 V 40]" to "Datum;Částka;Typ pohybu;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;40,00;Debet;B",
            "N Směr Příchozí/Odchozí [P 100 V 40]" to "Datum;Částka;Směr;Popis\n01.03.2026;100,00;Příchozí;A\n02.03.2026;40,00;Odchozí;B",
            "O cp1250 [P 1000 V 0]" to cz.axiom.vega.core.Encoding.decode("Datum;Částka;Popis\n01.03.2026;1 000,00;Výplata žluťoučký".toByteArray(charset("windows-1250"))),
            "L Obrat + Debet/Kredit indikátor (KB styl) [P 100 V 40]" to "Datum splatnosti;Částka;Typ;Popis\n01.03.2026;100,00;Kredit;A\n02.03.2026;-40,00;Debet;B",
        )
        for ((name, text) in samples) {
            val dr = runCatching { Statement.detect(text) }
            if (dr.isFailure) { println("PROBE|$name|DETECT PÁD ${dr.exceptionOrNull()}"); continue }
            val d = dr.getOrThrow()
            val r = d.spec?.let { runCatching { Statement.process(text, it) }.getOrElse { e -> null.also { println("PROBE|$name|PROCESS PÁD $e") } } }
            println("PROBE|$name|spec=${d.spec?.let { "d='${it.delimiter}' dc=${it.decimalComma} date=${it.dateCol} amt=${it.amountCol} deb=${it.debitCol}" }}|" +
                "P=${r?.income} V=${r?.expense} ok=${r?.ok} fail=${r?.failed?.map { it.second.take(40) }}|warn=${d.warnings.map { it.take(50) }}")
        }
    }
}
