package cz.axiom.vega

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Scan S8 (22. 11.): laické toky přes celou cestu zprávy – paměť, dočasný chat, export, správa konverzací. Bez sítě a modelu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsFlowsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }
    private fun ops(r: Rec) = Ops(ctx) { t, d -> r.ev += t to d }.also { it.settings.airgap = true }
    private fun waitIdle(r: Rec, before: Int) { val end = System.currentTimeMillis() + 20_000; while (System.currentTimeMillis() < end && r.ev.drop(before).none { it.first == "idle" }) Thread.sleep(20) }
    private fun ask(o: Ops, r: Rec, chat: String, text: String, ids: List<String> = emptyList()): JSONObject {
        val before = r.ev.size; o.chat(chat, text, ids); waitIdle(r, before)
        r.ev.drop(before).firstOrNull { it.first == "error" }?.let { return JSONObject().put("text", "CHYBA: " + it.second.optString("text")) }
        return r.ev.drop(before).lastOrNull { it.first == "message" }?.second?.getJSONObject("msg") ?: JSONObject().put("text", "(žádná odpověď)")
    }

    @Test fun memoryRememberListForget() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        val saved = ask(o, r, c, "pamatuj si, že mám psa Rexe").optString("text")
        assertTrue(saved, saved.contains("Zapamatováno") && saved.contains("Rexe"))
        assertTrue(o.memoryJson().toString().contains("Rexe"))
        val list = ask(o, r, c, "co si pamatuješ").optString("text")
        assertTrue(list, list.contains("Rexe"))
        val gone = ask(o, r, c, "zapomeň na psa Rexe").optString("text")
        assertFalse(gone, gone.startsWith("CHYBA"))
        assertFalse("po zapomenutí v paměti nesmí zůstat", o.memoryJson().toString().contains("Rexe"))
    }

    @Test fun tempChatStoresNothing() {
        val r = Rec(); val o = ops(r)
        val chatsBefore = o.chats().length()
        val before = r.ev.size; o.chatTemp("kolik je 15 % z 2400"); waitIdle(r, before)
        val msg = r.ev.drop(before).lastOrNull { it.first == "message" }?.second
        assertTrue(msg.toString(), msg.toString().contains("360"))
        assertEquals("dočasný chat nesmí založit konverzaci", chatsBefore, o.chats().length())
        // „pamatuj si“ v dočasném chatu se neuloží
        val b2 = r.ev.size; o.chatTemp("pamatuj si, že mám kočku Micku"); waitIdle(r, b2)
        assertFalse(o.memoryJson().toString().contains("Micku"))
    }

    @Test fun exportResultAndManageChats() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        val csv = "Datum;Částka;Popis\n01.03.2026;1 000,00;Výplata\n02.03.2026;-250,00;Nákup"
        val id = o.attach("vypis.csv", "text/csv", csv.toByteArray()).getString("id")
        ask(o, r, c, "spočítej příjmy a výdaje z výpisu", listOf(id))
        val ex = ask(o, r, c, "ulož to jako csv")
        assertTrue(ex.toString(), ex.optJSONObject("card")?.optString("type") == "file" || ex.optString("text").contains(".csv"))
        // přejmenování (dlouhý název se zkrátí, prázdný se nepřijme) a smazání
        o.renameChat(c, "x".repeat(500))
        val t = (0 until o.chats().length()).map { o.chats().getJSONObject(it) }.first { it.getString("id") == c }.getString("title")
        assertTrue("název ${t.length} znaků", t.length in 1..120)
        o.deleteChat(c)
        assertFalse((0 until o.chats().length()).any { o.chats().getJSONObject(it).getString("id") == c })
        assertEquals(0, o.messages(c).length())
    }
}
