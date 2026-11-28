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

    /** Kolo C7: dlouhá konverzace (450 výměn) neztratí začátek a zápis zůstane rychlý. */
    @Test fun longConversationKeepsBeginning() {
        val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val o = Ops(ctx) { t, d -> ev += t to d }.also { it.settings.airgap = true }
        val c = o.newChat(); val t0 = System.currentTimeMillis()
        for (i in 1..450) { val before = ev.size; o.chat(c, "kolik je $i + 1", emptyList()); val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && ev.drop(before).none { it.first == "idle" }) Thread.sleep(2) }
        val ms = System.currentTimeMillis() - t0
        val m = o.messages(c)
        assertTrue("${m.length()} zpráv", m.length() == 900)
        assertTrue(m.getJSONObject(0).optString("text"), m.getJSONObject(0).optString("text") == "kolik je 1 + 1")
        assertTrue("450 výměn za $ms ms", ms < 60_000)
    }

    /** H14: hledání v obsahu 300 konverzací (po 40 zprávách) – odpověď rychle (volá se po psaní v menu). */
    @Test fun searchManyChatsIsFast() {
        val o = Ops(ctx) { _, _ -> }
        val kv = java.io.File(ctx.filesDir, "vega/kv")
        repeat(300) { i -> val id = o.newChat()
            val arr = org.json.JSONArray(); repeat(40) { k -> arr.put(org.json.JSONObject().put("role", if (k % 2 == 0) "user" else "vega").put("text", "Zpráva $k v konverzaci $i o nákupu, dovolené a rozpočtu. " + if (i == 250 && k == 7) "Hledaný unikát Zanzibar." else "")) }
            java.io.File(kv, "chat/$id.json").apply { parentFile.mkdirs(); writeText(arr.toString()) } }
        val t0 = System.currentTimeMillis(); val r = o.searchChats("zanzibar"); val ms = System.currentTimeMillis() - t0
        println("SEARCH|300 konverzací: $ms ms, nalezeno ${r.length()}")
        assertTrue("nalezeno", r.length() == 1)
        assertTrue("$ms ms", ms < 1500)
    }
}
