package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Noc 28. 11. (bezpečnost / odolnost): řídicí znak ve jménu nebo adrese (zkopírováno z PDF / e-mailu) udělal z DOCX výzvy
 * neplatné XML – Word by soubor odmítl jako poškozený. Každá XML část musí jít načíst a text zůstat čitelný.
 */
class DocxXmlValidTest {
    @Test fun controlCharsDoNotBreakDocx() {
        val d = Docx.build("Věřitel: Firma <b>&\"s.r.o.\"\u0001\u000B\u001F konec\nDruhý řádek ￾ \uD800 x\tTab", "Výzva \u0007 FV-1")
        val zip = java.util.zip.ZipInputStream(d.inputStream()); var e = zip.nextEntry; var n = 0
        while (e != null) { val b = zip.readBytes()
            if (e.name.endsWith(".xml")) { n++; javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(b.inputStream()) }
            e = zip.nextEntry }
        assertTrue(n >= 3)
        val t = Docx.text(d)
        assertTrue(t.contains("Firma <b>&\"s.r.o.\"") && t.contains("konec") && t.contains("Druhý řádek"), t)
        assertEquals(false, t.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it == '￾' || it.isSurrogate() }, t)
    }
}
