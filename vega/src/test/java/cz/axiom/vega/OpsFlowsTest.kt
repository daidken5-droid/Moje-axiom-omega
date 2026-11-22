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

    /** Scan S13: starý výsledek z jiné konverzace se nesmí exportovat na běžnou prosbu („vytvoř mi tabulku na svatbu“). */
    @Test fun oldResultIsNotExportedForUnrelatedTableRequest() {
        val r = Rec(); val o = ops(r); val c1 = o.newChat()
        val id = o.attach("vypis.csv", "text/csv", "Datum;Částka;Popis\n01.03.2026;1 000,00;Výplata\n02.03.2026;-250,00;Nákup".toByteArray()).getString("id")
        ask(o, r, c1, "spočítej příjmy a výdaje z výpisu", listOf(id))
        val c2 = o.newChat()
        val m = ask(o, r, c2, "vytvoř mi tabulku na plánování svatby")
        assertFalse("export starého výpisu v jiné konverzaci: $m", m.optJSONObject("card")?.optString("type") == "file" || m.optString("text").contains(".csv"))
        // ve stejné konverzaci hned po výsledku export funguje
        val ex = ask(o, r, c1, "ulož to jako csv")
        assertTrue(ex.toString(), ex.optJSONObject("card")?.optString("type") == "file" || ex.optString("text").contains(".csv"))
    }

    /** Scan S13b: oprava kalkulace funguje ve stejné konverzaci; v jiné (po 30 min) se stará kalkulace nepoužije. */
    @Test fun breakEvenCorrectionOnlyInSameChat() {
        val r = Rec(); val o = ops(r); val c1 = o.newChat()
        assertTrue(ask(o, r, c1, "cena 250, náklad na kus 150, fixní náklady 8000, 60 kusů").optString("text").contains("ztrátu 2 000"))
        val fix = ask(o, r, c1, "oprava: fixní náklady jsou 5 000").optString("text")
        assertTrue(fix, fix.contains("Přepočteno") && fix.contains("zisk 1 000"))
        // jiná konverzace po víc než 30 minutách: stará kalkulace se nepoužije
        val f = java.io.File(ctx.filesDir, "vega/kv").walkTopDown().first { it.name.startsWith("global") && it.parentFile.name == "econ" && it.name.endsWith(".json") }
        f.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L)
        val other = ask(o, r, o.newChat(), "ne, vlastně chci 2 lístky za 300").optString("text")
        assertFalse(other, other.contains("Přepočteno"))
    }

    /** Scan S20b: „najdi v mých souborech do kdy platím nájem“ – najde smlouvu, ne recept (přes „do“). */
    @Test fun localSearchIgnoresStopWords() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        o.attach("smlouva.txt", "text/plain", "Nájemní smlouva. Nájemné je splatné vždy do 5. dne v měsíci. Výpovědní lhůta tři měsíce.".toByteArray())
        o.attach("recept.txt", "text/plain", "Svíčková. Maso naložte přes noc do zeleniny a pečte dvě hodiny do měkka.".toByteArray())
        val m = ask(o, r, c, "najdi v mých souborech do kdy platím nájem")
        val all = m.toString()
        assertTrue(all, all.contains("smlouva.txt") && !all.contains("recept.txt"))
    }

    /** Scan S27: „zapomeň všechno“ dřív odpovědělo „Nic takového si nepamatuji“ a paměť zůstala. Teď tlačítko s počtem, smaže až klepnutí. */
    @Test fun forgetEverythingAsksThenClears() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        ask(o, r, c, "pamatuj si, že jsem vegetarián"); ask(o, r, c, "pamatuj si, že bydlím v Brně")
        val m = ask(o, r, c, "zapomeň všechno")
        assertEquals("memClear", m.optJSONObject("card")?.optString("type"))
        assertEquals(2, m.getJSONObject("card").getInt("n"))
        assertEquals("bez klepnutí se nic nemaže", 2, o.memoryJson().length())
        assertEquals(2, o.memoryClear())
        assertEquals(0, o.memoryJson().length())
        assertTrue(ask(o, r, c, "vymaž paměť").optString("text").contains("prázdná"))
        // bez modelu na pozdrav lidsky, ne „potřebuji model“
        assertTrue(ask(o, r, c, "ahoj").optString("text").startsWith("Ahoj!"))
    }

    /** Scan S28: „vytvoř tabulku výdajů“ po hledání v souborech uložilo PRÁZDNÝ tabulka.csv (export starého výsledku; \b v JVM je jen ASCII). */
    @Test fun newTableRequestIsNotExportOfOldResultAndEmptyIsNeverSaved() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        ask(o, r, c, "najdi v mých souborech smlouvu")  // prázdný výsledek (žádné soubory)
        val m = ask(o, r, c, "vytvoř tabulku výdajů")
        assertFalse(m.toString(), m.optJSONObject("card")?.optString("type") == "file" || m.optString("text").contains(".csv"))
        val e = ask(o, r, c, "ulož to jako csv")
        assertTrue(e.toString(), e.optString("text").contains("prázdný") && e.optJSONObject("card") == null)
        // povely pro telefon: poctivě, nic se nepředstírá
        assertTrue(ask(o, r, c, "nastav budík na 7").optString("text").contains("Nic jsem nenastavil"))
        assertTrue(ask(o, r, c, "zavolej mámě").optString("text").startsWith("Volat neumím"))
    }

    /** Scan S37: „ulož to do Wordu“ po textové odpovědi uloží tu odpověď jako .docx (bez markdownu); bez odpovědi nic nevznikne. */
    @Test fun saveLastAnswerAsWord() {
        val r = Rec(); val o = ops(r); val c = o.newChat()
        val nothing = ask(o, r, c, "ulož to do wordu")
        assertFalse(nothing.toString(), nothing.optJSONObject("card")?.optString("type") == "file")
        ask(o, r, c, "co umíš")  // delší textová odpověď (bez modelu)
        val m = ask(o, r, c, "ulož to do wordu")
        assertEquals(m.toString(), "file", m.optJSONObject("card")?.optString("type"))
        val name = m.getJSONObject("card").getString("name")
        assertTrue(name, name.endsWith(".docx"))
        val txt = cz.axiom.vega.core.Docx.text(java.io.File(o.out.dir, name).readBytes())
        assertTrue(txt, txt.contains("výpočty") && !txt.contains("**"))
    }
}
