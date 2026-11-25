package cz.axiom.vega.files
import org.junit.Assert.assertTrue
import org.junit.Test
/** Noc 24./25. 11., kolo NN11 (bezpečnost): podvržený „výpis.xlsx“ – malý soubor, který se rozbalí na stovky MB, nesmí vyčerpat paměť telefonu. */
class ZipBombTest {
    private fun bomb(entry: String, mb: Int): ByteArray { val o = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { z -> z.setLevel(9); z.putNextEntry(java.util.zip.ZipEntry(entry)); val chunk = ByteArray(1 shl 20) { ' '.code.toByte() }; repeat(mb) { z.write(chunk) }; z.closeEntry() }
        return o.toByteArray() }
    @Test fun xlsxBombIsBounded() {
        val b = bomb("xl/worksheets/sheet1.xml", 400)
        assertTrue("soubor má být malý: ${b.size}", b.size < 2_000_000)
        val rt = Runtime.getRuntime(); System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val rows = Extract.xlsxRows(b)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val used = (rt.totalMemory() - rt.freeMemory() - before) / 1_000_000
        assertTrue("prázdný list: $rows", rows == null || rows.isEmpty())
        assertTrue("trvalo $ms ms", ms < 15_000)
        assertTrue("paměť navíc ~$used MB (limit 150)", used < 150)
    }
}
