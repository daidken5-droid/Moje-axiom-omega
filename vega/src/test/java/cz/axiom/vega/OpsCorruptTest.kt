package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Scan S35 (22. 11.): poškozené soubory (vybitá baterie, plný disk, ruční zásah) – aplikace se otevře a dá se dál psát. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsCorruptTest {
    private val ctx = RuntimeEnvironment.getApplication()
    @Test fun corruptedFilesDoNotBreakTheApp() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o1 = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val c = o1.newChat()
        val kv = java.io.File(ctx.filesDir, "vega/kv")
        kv.walkTopDown().filter { it.isFile && (it.name.endsWith(".json") || it.name == "entries") }.forEach { it.writeText("{rozbit") }
        java.io.File(kv, "memory").mkdirs(); java.io.File(kv, "memory/entries").writeText("[{\"nesmysl\":")
        java.io.File(kv, "chats.json").writeText("\u0000\u0000")
        val o = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        // úvod, seznam konverzací, paměť, nastavení – nic nespadne
        o.home(); o.chats(); o.memoryJson(); o.settingsJson(); o.messages(c)
        val before = ev.size; o.chat(c, "kolik je 15 % z 2400", emptyList())
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(20)
        val msg = ev.drop(before).lastOrNull { it.first == "message" }?.second?.toString().orEmpty()
        assertTrue(msg, msg.contains("360"))
        assertTrue(ev.drop(before).none { it.first == "error" })
    }
}
