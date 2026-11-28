package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Průzkum (ne regresní test): VEGA_PROBE=soubor s dotazy → vypíše, co laik dostane bez sítě a modelu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsProbe {
    @Test fun probe() {
        val file = System.getenv("VEGA_PROBE"); assumeTrue(file != null)
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o = Ops(RuntimeEnvironment.getApplication()) { t, d -> ev += t to d }.also { it.settings.airgap = System.getenv("VEGA_PROBE_NET") == null; it.settings.webAllowed = false }
        val c = o.newChat()
        java.io.File(file).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { q ->
            val before = ev.size; o.chat(c, q, emptyList())
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
            val err = ev.drop(before).firstOrNull { it.first == "error" }?.second?.optString("text")
            val m = ev.drop(before).lastOrNull { it.first == "message" }?.second?.optJSONObject("msg")
            println("OPS|$q|${err?.let { "CHYBA $it" } ?: m?.optString("text")?.replace("\n", " ⏎ ")?.take(160)}|${m?.optJSONObject("card")?.optString("type") ?: ""}")
        }
    }
}
