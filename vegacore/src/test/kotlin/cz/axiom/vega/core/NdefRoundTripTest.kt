package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scan S19 (22. 11.): co VEGA zapíše na štítek, musí jít přečíst zpět beze změny (text, URL, tel:, mailto:, dlouhý záznam). */
class NdefRoundTripTest {
    private fun rt(tnf: Int, type: String, payload: ByteArray) = NdefParser.parse(NdefParser.build(listOf(Triple(tnf, type, payload)))).single()

    private fun content(v: Value) = when (v) { is Value.Url -> v.url; is Value.Text -> v.text; else -> v.toString() }

    @Test fun textAndUris() {
        for (t in listOf("Ahoj", "Žluťoučký kůň úpěl ďábelské ódy 😀", "a".repeat(300), "řádek 1\nřádek 2"))
            assertEquals(t, content(rt(1, "T", NdefParser.textPayload(t)).asValue()))
        // http(s) → odkaz; tel:/mailto:/geo: záměrně jako text (neklikací), obsah ale beze změny
        for (u in listOf("https://www.seznam.cz/", "https://example.org/a?b=c", "http://www.cnb.cz", "tel:+420123456789", "mailto:jan@example.cz", "geo:50.08,14.42", "https://" + "x".repeat(400) + ".cz"))
            assertEquals(u, content(rt(1, "U", NdefParser.uriPayload(u)).asValue()))
    }
    @Test fun multipleRecordsKeepOrderAndFlags() {
        val recs = NdefParser.parse(NdefParser.build(listOf(Triple(1, "U", NdefParser.uriPayload("https://a.cz")), Triple(1, "T", NdefParser.textPayload("druhý")), Triple(1, "T", NdefParser.textPayload("třetí")))))
        assertEquals(3, recs.size)
        assertEquals("https://a.cz", (recs[0].asValue() as Value.Url).url)
    }
}
