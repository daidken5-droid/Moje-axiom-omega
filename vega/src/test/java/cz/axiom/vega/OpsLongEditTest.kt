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

/** Den 25. 11., kolo 5: „Upravit“ / „Znovu“ u dlouhého zadání (> 6 000 znaků) vrátí CELÝ text, ne náhled 2 000 znaků + „…(N znaků)“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsLongEditTest {
    private val ctx = RuntimeEnvironment.getApplication()
    @Test fun editReturnsFullLongText() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val c = o.newChat()
        val long = (1..400).joinToString("\n") { "Odstavec $it smlouvy: nájemce platí zálohy a služby podle přílohy." } + "\n\nOtázky: 1) Jaká je výpovědní lhůta? 2) Kdo platí opravy?"
        assertTrue(long.length > 6000)
        val before = ev.size; o.longTask(c, long); val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
        val shown = o.messages(c).getJSONObject(0).getString("text")
        assertTrue("bublina je náhled", shown.length < 2100 && shown.contains("znaků)"))
        val back = o.takeLastUser(c)
        assertEquals("Upravit vrátí celý text", long, back)
    }
}
