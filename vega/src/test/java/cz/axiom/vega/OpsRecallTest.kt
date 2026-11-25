package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** „Kde bydlím?“ v režimu Bez sítě: odpověď z paměti, ne „na tohle potřebuji model v cloudu“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsRecallTest {
    @Test fun ownFactFromMemoryOffline() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o = Ops(RuntimeEnvironment.getApplication()) { t, d -> ev += t to d }.also { it.settings.airgap = true; it.settings.webAllowed = false }
        val c = o.newChat()
        fun ask(q: String): String {
            val before = ev.size; o.chat(c, q, emptyList())
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
            return ev.drop(before).lastOrNull { it.first == "message" }?.second?.optJSONObject("msg")?.optString("text") ?: ""
        }
        ask("pamatuj si, že bydlím v Brně")
        val a = ask("kde bydlím?")
        assertTrue(a, a.contains("Bydlím v Brně"))
    }
}
