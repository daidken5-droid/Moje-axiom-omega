package cz.axiom.vega.core
import org.junit.Test
/** Noční kolo N6: 17 tvarů data z výpisů; dřív selhalo 4 („2 lis 2026“, „02-Nov-2026“, „Nov 2, 2026“, „2 Nov 2026“). */
class StatementDatesTest { @Test fun seventeenShapes() {
    listOf("02.11.2026", "2.11.2026", "2. 11. 2026", "02.11.26", "2026-11-02", "2026-11-02 10:12:33", "2026-11-02T10:12:33Z", "02-11-2026", "02/11/2026", "2026/11/02",
        "02.11.2026 10:15", "2 lis 2026", "2. listopadu 2026", "02-Nov-2026", "Nov 2, 2026", "2 Nov 2026", "02.11.2026 0:00:00").forEach { org.junit.Assert.assertEquals(it, "2026-11-02", CzNum.date(it)) }
    listOf("20261102", "20261332", "12345678", "2 xyz 2026", "Foo 2, 2026", "99999999").forEach { org.junit.Assert.assertNull(it, CzNum.date(it)) }
} }
