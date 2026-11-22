package cz.axiom.vega

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Návody v hláškách („Nastavení → Pro pokročilé → Ověřovač citací“) musí vést na popisky, které v UI opravdu jsou.
 * Dřív odkazovaly na neexistující „Mám předplatné Google AI Pro“, „Menu → Vylepšení“ nebo interní „model-pill“.
 */
class HintPathsTest {
    private fun src(p: String) = File(p).takeIf { it.isFile }?.readText() ?: File("vega/$p").readText()

    @Test fun everySettingsPathLeadsToExistingLabel() {
        val ui = src("src/main/assets/ui/app.js")
        val texts = listOf("src/main/java/cz/axiom/vega/Ops.kt", "src/main/java/cz/axiom/vega/MainActivity.kt", "src/main/assets/ui/app.js").joinToString("\n") { src(it) }
        val bad = ArrayList<String>()
        Regex("Nastavení → ([^\"“)<.]+)").findAll(texts).forEach { m ->
            // každý článek cesty (do „ a “, „ – “ nebo konce) musí být popiskem v UI
            m.groupValues[1].split(" → ").map { it.substringBefore(" a ").substringBefore(" – ").substringBefore(",").substringBefore(" (").trim() }.filter { it.isNotEmpty() }.forEach { seg ->
                if (!ui.contains(seg, ignoreCase = true)) bad += "„$seg“ v: ${m.value.take(80)}"
            }
        }
        assertTrue("Návody na neexistující popisky:\n" + bad.distinct().joinToString("\n"), bad.isEmpty())
        for (jargon in listOf("model-pill", "Menu → Vylepšení", "Bez internetu"))
            assertTrue("zastaralý odkaz „$jargon“", !texts.contains(jargon))
    }
}
