package cz.axiom.vega.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Ověřovač: dělení na atomická tvrzení, chování bez balíčku a s poškozeným balíčkem (vždy), shoda tokenizeru
 * s originálem a skutečná inference ONNX na HOSTITELI (jen když je balíček k dispozici: VERIFIER_BUNDLE nebo
 * tools/verifier/bundles/referencni). Telefon/emulátor tím ověřený NENÍ.
 */
class VerifierTest {
    private val bundle = (System.getenv("VERIFIER_BUNDLE") ?: listOf("../tools/verifier/bundles/referencni", "tools/verifier/bundles/referencni").firstOrNull { File(it, "manifest.json").isFile })?.let { File(it) }

    @Test fun claimsSplitDateAndMotive() {
        val c = Grounding.claims("Most byl uzavřen 12. 9. 2025, protože firma chtěla zakrýt chybu [2]. Věta bez citace. Výbor se sešel v pondělí [1].")
        assertEquals(listOf("událost", "motiv / důvod", "tvrzení"), c.map { it.kind })
        assertEquals("Most byl uzavřen 12. 9. 2025.", c[0].text); assertEquals(listOf(2), c[0].cites)
        assertTrue(c[1].text.contains("protože firma chtěla zakrýt chybu"))
    }

    @Test fun missingAndCorruptBundleNeverCrash() {
        val miss = OnnxGrounding(File("/nonexistent-vega-bundle"))
        assertFalse(miss.ready); assertEquals(Grounding.Label.NEVYHODNOCENO, miss.check("a", "b").label)
        assumeTrue(bundle != null)
        val d = kotlin.io.path.createTempDirectory("vb").toFile()
        bundle!!.listFiles()!!.filter { it.name != "model.onnx" }.forEach { it.copyTo(File(d, it.name)) }
        File(d, "model.onnx").writeBytes(ByteArray(1000) { 7 }) // poškozený soubor vah
        val bad = OnnxGrounding(d)
        assertFalse(bad.ready); assertTrue(bad.status, bad.status.contains("poškozený"))
        assertEquals(Grounding.Label.NEVYHODNOCENO, bad.check("Výbor se sešel.", "The committee met.").label)
        d.deleteRecursively()
    }

    @Test fun tokenizerMatchesOriginalAndInferenceOnHost() {
        assumeTrue("balíček ověřovače není k dispozici", bundle != null)
        val tok = UnigramTokenizer(File(bundle, "vocab.tsv").bufferedReader().lineSequence())
        val vec = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/verifier/token_vectors.json")!!.readText()) as kotlinx.serialization.json.JsonArray
        var ok = 0; val bad = ArrayList<String>()
        vec.forEach { v -> v as kotlinx.serialization.json.JsonObject
            val want = (v["ids"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() }
            val got = tok.encodePair((v["a"] as kotlinx.serialization.json.JsonPrimitive).content, (v["b"] as kotlinx.serialization.json.JsonPrimitive).content, 256).first.toList()
            if (got == want) ok++ else bad += "${(v["b"] as kotlinx.serialization.json.JsonPrimitive).content}: ${got.take(12)} × ${want.take(12)}"
        }
        println("VERIFIER tokenizer shoda $ok/${vec.size}"); bad.take(3).forEach { println("  rozdíl: $it") }
        assertEquals(bad.joinToString("\n"), vec.size, ok)
        val g = OnnxGrounding(bundle!!)
        assertTrue(g.status, g.ready)
        val times = ArrayList<Long>()
        fun chk(c: String, p: String) = System.nanoTime().let { t -> g.check(c, p).also { times += (System.nanoTime() - t) / 1_000_000 } }
        val r1 = chk("Jan Novák byl v roce 2019 zvolen starostou Lipníku.", "Petr Novák byl v roce 2019 zvolen starostou Lipníku.")
        val r2 = chk("Na pondělí bylo naplánováno jednání výboru.", "The committee was scheduled to meet on Monday.")
        val r3 = chk("Výbor se v pondělí sešel.", "The committee was scheduled to meet on Monday.")
        val r4 = chk("Výbor se v pondělí sešel.", "The committee did not meet on Monday.")
        repeat(20) { chk("Česká národní banka vznikla v roce 1993.", "Česká národní banka vznikla v roce 1993 a je právnickou osobou veřejného práva se sídlem v Praze.") }
        val s = times.sorted()
        println("VERIFIER načtení ${g.loadMillis} ms; dvojice p50 ${s[s.size / 2]} ms, p95 ${s[(s.size * 95) / 100 - 1]} ms (hostitel JVM, ne telefon)")
        println("VERIFIER záměna osoby: ${r1.label}; plán→plán: ${r2.label}; plán→uskutečnění: ${r3.label} (p=${"%.2f".format(r3.support)}); zápor: ${r4.label}")
        assertEquals(Grounding.Label.PODPORUJE, r2.label)
        assertTrue(r1.label != Grounding.Label.PODPORUJE)
        assertEquals(Grounding.Label.ODPORUJE, r4.label)
    }
}
