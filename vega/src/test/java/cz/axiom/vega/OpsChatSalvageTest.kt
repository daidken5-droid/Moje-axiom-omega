package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Den 25. 11., kolo 2: useknutý soubor konverzace (vybitá baterie / plný disk při zápisu) – celé zprávy před poškozením se zachrání, ne prázdný chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsChatSalvageTest {
    private val ctx = RuntimeEnvironment.getApplication()
    @Test fun truncatedChatIsSalvaged() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o1 = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val c = o1.newChat()
        for (q in listOf("kolik je 15 % z 2400", "kolik je 2 na 10", "odmocnina z 144")) {
            val before = ev.size; o1.chat(c, q, emptyList()); val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
        }
        assertEquals(6, o1.messages(c).length())
        val f = java.io.File(ctx.filesDir, "vega/kv").walkTopDown().first { it.isFile && it.name == "$c.json" }
        val raw = f.readText(); f.writeText(raw.substring(0, raw.length - 25))  // useknutý konec poslední zprávy
        val o = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val m = o.messages(c)
        assertTrue("zachráněno ${m.length()} z 6", m.length() >= 5)
        assertTrue(m.getJSONObject(0).getString("text").contains("15 %"))
        assertTrue("záloha poškozeného souboru", f.parentFile!!.listFiles()!!.any { it.name.startsWith("$c.json.bad") })
        // další zpráva se uloží normálně za zachráněné
        val before = ev.size; o.chat(c, "kolik je 3/4 z 200", emptyList()); val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
        assertTrue(o.messages(c).length() >= 7)
    }
}
