package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Scan S33 (22. 11.): extrémní zprávy (obří vložený text, prázdná, samé mezery, tisíce řádků) – odpověď do pár sekund, žádná chyba. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsExtremeTest {
    private val ctx = RuntimeEnvironment.getApplication()
    @Test fun extremeMessages() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val c = o.newChat()
        val lorem = "Toto je dlouhý vložený text smlouvy s mnoha větami a čísly 1 234,50 Kč. "
        for (q in listOf(lorem.repeat(2800), "", "    \n\n  ", (1..5000).joinToString("\n") { "řádek $it;${it * 3},50" }, "😀".repeat(3000), "kolik je " + "9".repeat(400) + " * 2")) {
            val before = ev.size; val t0 = System.currentTimeMillis()
            o.chat(c, q, emptyList())
            val end = t0 + 20_000
            while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
            val ms = System.currentTimeMillis() - t0
            val err = ev.drop(before).firstOrNull { it.first == "error" }?.second?.optString("text")
            val idle = ev.drop(before).any { it.first == "idle" }
            println("EXT|${q.length}|$ms ms|idle=$idle|err=$err|${ev.drop(before).lastOrNull { it.first == "message" }?.second?.optJSONObject("msg")?.optString("text")?.take(120)}")
            assertTrue("${q.length} znaků: UI by zůstalo viset", idle)
            assertTrue("${q.length} znaků: $ms ms", ms < 10_000)
            assertTrue("${q.length} znaků: chyba $err", err == null || !err.contains("Exception"))
        }
        // prázdné zprávy se do konverzace neuložily
        val texts = (0 until o.messages(c).length()).map { o.messages(c).getJSONObject(it) }.filter { it.optString("role") == "user" }.map { it.optString("text") }
        assertTrue(texts.map { it.length }.toString(), texts.none { it.isBlank() })
    }
}
