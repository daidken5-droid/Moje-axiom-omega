package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N4 (23. 11.): 9 tvarů exportů (Revolut, Fio, Air Bank, KB, ČSOB, mBank, George, Moneta, Raiffeisen) – součty na haléř. */
class BankFormatsTest { @Test fun nineBankExports() {
    val banks = linkedMapOf(
        "revolut" to ("Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\nCARD_PAYMENT,Current,2026-11-02 10:12:33,2026-11-03 08:01:02,Lidl,-12.50,0.00,EUR,COMPLETED,87.50\nTOPUP,Current,2026-11-04 09:00:00,2026-11-04 09:00:05,Top-up by *1234,100.00,0.00,EUR,COMPLETED,187.50\nCARD_PAYMENT,Current,2026-11-05 18:30:00,2026-11-05 18:31:00,Uber,-7.25,0.00,EUR,COMPLETED,180.25" to (100.0 to -19.75)),
        "fio" to ("\"Účet\";\"2000123456/2010\"\n\"Období\";\"01.11.2026 - 30.11.2026\"\n\n\"ID pohybu\";\"Datum\";\"Objem\";\"Měna\";\"Protiúčet\";\"Název protiúčtu\";\"Zpráva pro příjemce\"\n\"1\";\"02.11.2026\";\"-1 250,00\";\"CZK\";\"123/0100\";\"ČEZ\";\"záloha\"\n\"2\";\"05.11.2026\";\"35 000,00\";\"CZK\";\"999/0300\";\"Zaměstnavatel\";\"mzda\"\n\"3\";\"07.11.2026\";\"-489,90\";\"CZK\";\"\";\"Albert\";\"\"" to (35000.0 to -1739.9)),
        "airbank" to ("Datum provedení;Směr úhrady;Typ úhrady;Název protistrany;Částka v měně účtu;Měna účtu;Poznámka pro mne\n02.11.2026;Odchozí;Platba kartou;Billa;-312,40;CZK;\n03.11.2026;Příchozí;Došlá platba;Jan Novák;1 500,00;CZK;vrácení\n04.11.2026;Odchozí;Trvalý příkaz;Nájem;-12 000,00;CZK;" to (1500.0 to -12312.4)),
        "kb" to ("Datum splatnosti;Protiúčet a kód banky;Název protiúčtu;Částka;Originální částka;Originální měna;Popis příkazce\n02.11.2026;;Tesco;-899,00;-36,00;EUR;nákup\n10.11.2026;1234/0800;Firma s.r.o.;42 100,50;;;mzda" to (42100.5 to -899.0)),
        "csob" to ("číslo účtu;datum zaúčtování;částka;měna;zůstatek;číslo účtu protiúčtu;název účtu protiúčtu;poznámka\n123456789/0300;03.11.2026;-2500,00;CZK;10000,00;;;pojištění\n123456789/0300;12.11.2026;800,00;CZK;10800,00;;;vratka" to (800.0 to -2500.0)),
        "mbank" to ("mBank S.A.\n#Za období:;01.11.2026;30.11.2026;\n\n#Datum uskutečnění transakce;#Datum zaúčtování transakce;#Popis transakce;#Zpráva pro příjemce;#Plátce/Příjemce;#Číslo účtu plátce/příjemce;#Částka transakce;#Účetní zůstatek po transakci;\n02-11-2026;02-11-2026;PLATBA KARTOU;;ROHLIK.CZ;;-1 023,50;5 000,00;\n06-11-2026;06-11-2026;PŘÍCHOZÍ PLATBA;;JAN;123/0100;2 000,00;7 000,00;" to (2000.0 to -1023.5)),
        "george" to ("Datum zaúčtování,Částka,Měna,Název protistrany,Poznámka\n\"02.11.2026\",\"-1 234,56\",\"CZK\",\"O2\",\"\"\n\"09.11.2026\",\"5 000,00\",\"CZK\",\"Petr\",\"dárek\"" to (5000.0 to -1234.56)),
        "moneta" to ("Číslo účtu;Splatnost;Částka;Měna;Protiúčet;Název protiúčtu;Zpráva pro příjemce\n1234/0600;02.11.2026;-500,00;CZK;;Benzina;\n1234/0600;20.11.2026;1 000,00;CZK;;Babička;" to (1000.0 to -500.0)),
        "raiffeisen" to ("Datum provedení;Datum zaúčtování;Číslo účtu;Název účtu;Kategorie transakce;Číslo protiúčtu;Název protiúčtu;Typ transakce;Zpráva;Poznámka;VS;KS;SS;Zaúčtovaná částka;Měna účtu;Původní částka a měna;Poplatek\n02.11.2026;02.11.2026;111/5500;Účet;Potraviny;;Penny;Platba kartou;;;;;;-245,00;CZK;-245,00 CZK;0,00\n15.11.2026;15.11.2026;111/5500;Účet;Příjem;222/0100;Firma;Příchozí platba;mzda;;;;;30 000,00;CZK;30 000,00 CZK;0,00" to (30000.0 to -245.0)))
    banks.forEach { (name, v) -> val (txt, exp) = v
        val d = Statement.detect(txt)
        val r = d.spec?.let { runCatching { Statement.process(txt, it) }.getOrNull() }
        val ok = r != null && kotlin.math.abs(r.income - exp.first) < 0.01 && kotlin.math.abs(r.expense - exp.second) < 0.01 && r.failed.isEmpty()
        org.junit.Assert.assertTrue("$name: příjmy ${r?.income} výdaje ${r?.expense} nezpracováno ${r?.failed}", ok) }
} }
