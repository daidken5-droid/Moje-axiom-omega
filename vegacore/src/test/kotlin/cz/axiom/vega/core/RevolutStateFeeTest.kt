package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N4: Revolut – stav REVERTED/DECLINED se nepočítá, sloupec Fee se odečte (dřív −530 € navíc a chybějící poplatky). */
class RevolutStateFeeTest { @Test fun voidedSkippedAndFeeCounted() {
    val txt = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\nCARD_PAYMENT,Current,2026-11-02 10:12:33,2026-11-03 08:01:02,Lidl,-12.50,0.00,EUR,COMPLETED,87.50\nCARD_PAYMENT,Current,2026-11-03 10:00:00,,Alza,-500.00,0.00,EUR,REVERTED,\nCARD_PAYMENT,Current,2026-11-04 10:00:00,,Steam,-30.00,0.00,EUR,DECLINED,\nCARD_PAYMENT,Current,2026-11-05 10:00:00,,Bolt,-8.00,0.00,EUR,PENDING,"
    val d = Statement.detect(txt); val r = Statement.process(txt, d.spec!!)
    org.junit.Assert.assertEquals(-20.5, r.expense, 0.001); org.junit.Assert.assertEquals(2, r.ok)
    org.junit.Assert.assertTrue(r.table.notes.toString(), r.table.notes.any { it.startsWith("Vynecháno 2 zrušené") })
    val fee = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\nEXCHANGE,Current,2026-11-02 10:12:33,2026-11-02 10:12:34,Exchanged to CZK,-100.00,-1.00,EUR,COMPLETED,0"
    val r2 = Statement.process(fee, Statement.detect(fee).spec!!); org.junit.Assert.assertEquals(-101.0, r2.expense, 0.001)
    // uložený postup s přehozenými sloupci: stav a poplatek se najdou podle názvu
    val spec = Statement.detect(txt).spec!!; org.junit.Assert.assertEquals("state", spec.columns.entries.first { it.value == "state" }.key)
} }
