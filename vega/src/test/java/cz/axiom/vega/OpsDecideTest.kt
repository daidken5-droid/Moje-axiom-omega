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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Navazující zadání: oponentura s opravou závěru, práce s důkazy, přiměřená hloubka, pravdivý stav exportu.
 * Běžný chat (Ops.chat), air-gap zapnutý = žádný model ani síť → tyto cesty rozhoduje VEGA sama.
 * Vstupy = VÝVOJOVÉ příklady ze zadání; závěrečné (jiná čísla a formulace) jsou v VegaEval2Test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsDecideTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }
    private class NoKeys(c: android.content.Context) : Vault(c) {
        override fun read(name: String): String? = null; override fun has(name: String) = false
        override fun save(name: String, secret: String) {}; override fun remove(name: String) {}
    }
    private fun ops(r: Rec) = Ops(ctx, NoKeys(ctx)) { t, d -> r.ev += t to d }.also { it.settings.airgap = true }
    private fun say(o: Ops, r: Rec, chat: String, text: String, ids: List<String> = emptyList()): JSONObject {
        val n = r.ev.size; o.chat(chat, text, ids)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && r.ev.drop(n).none { it.first == "idle" }) Thread.sleep(20)
        r.ev.drop(n).firstOrNull { it.first == "error" }?.let { throw AssertionError("chyba: ${it.second}") }
        return r.ev.drop(n).last { it.first == "message" }.second.getJSONObject("msg")
    }
    private fun csvRows(o: Ops, name: String): List<List<String>> {
        val f = o.out.file(name)!!
        return cz.axiom.vega.core.Csv.read(cz.axiom.vega.core.Encoding.decode(f.readBytes())).rows
    }

    @Test fun oppositionCorrectionScopeAndCsvAfterRestart() {
        val r = Rec(); val o = ops(r)
        val a = o.createProject("Svíčky A", emptyMap())
        val chat = o.newChat()
        val m1 = say(o, r, chat, "Při ceně 250 Kč, variabilním nákladu 150 Kč a fixních nákladech 8 000 Kč jsem po 60 kusech v plusu. Spočítej zisk.").getString("text")
        assertTrue(m1, m1.startsWith("Při 60 kusech máš ztrátu 2 000 Kč – nejsi v plusu."))
        assertTrue(m1, m1.contains("přispěje 100 Kč") && m1.contains("Bod zvratu je 80 kusů"))
        // naléhání bez nových dat závěr nemění
        val m2 = say(o, r, chat, "Ne, jsem si jistý, že jsem v plusu.").getString("text")
        assertTrue(m2, m2.contains("ztrátu 2 000 Kč") && m2.contains("Bez nových čísel"))
        // nový údaj ano
        val m3 = say(o, r, chat, "Oprava: fixní náklady jsou 5 000 Kč.").getString("text")
        assertTrue(m3, m3.contains("zisk 1 000 Kč") && m3.contains("Bod zvratu je 50 kusů") && m3.contains("fixní náklady 8 000 → 5 000"))
        // druhý projekt s vlastními náklady
        val b = o.createProject("Mýdla B", emptyMap())
        say(o, r, chat, "Cena 90 Kč, variabilní náklad 40 Kč a fixní náklady 2 000 Kč.")
        // restart → projekt A → CSV pro 40, 50, 60 kusů
        val r2 = Rec(); val o2 = ops(r2); o2.settings.activeProject = a
        val c = say(o2, r2, o2.newChat(), "Udělej mi české CSV pro 40, 50 a 60 kusů.")
        assertTrue(c.getString("text"), c.getString("text").contains("ověřeno zpětným přečtením"))
        val rowsA = csvRows(o2, c.getJSONObject("card").getString("name"))
        assertEquals(listOf("-1000", "0", "1000"), rowsA.map { it[4] })
        assertTrue(String(o2.out.file(c.getJSONObject("card").getString("name"))!!.readBytes()).lines().first().contains(";"))
        o2.settings.activeProject = b
        val cb = say(o2, r2, o2.newChat(), "Udělej mi české CSV pro 30, 40 a 50 kusů.")
        assertEquals(listOf("-500", "0", "500"), csvRows(o2, cb.getJSONObject("card").getString("name")).map { it[4] })
        // paměť: kalkulace projektu A je jen v projektu A
        assertTrue(o2.memory.context("project:$a")!!.contains("5 000 Kč"))
        assertFalse(o2.memory.context("project:$b")!!.contains("5 000 Kč"))
    }

    private val postA = "Autor: Jana Malá, uznávaná redaktorka s 20 lety praxe.\nPodle anonymního příspěvku na fóru Kvasar došlo 4. 6. 2025 k úniku dat z městského úřadu, protože správce chtěl poškodit starostu."
    private val postB = "Blog Pepa_X (anonymní autor bez historie).\nJak uvedl anonymní příspěvek na fóru Kvasar, 4. 6. 2025 unikla data z městského úřadu – správce to prý udělal, aby poškodil starostu."
    private val protocol = "Protokol o bezpečnostním incidentu č. 17/2025, Městský úřad, vyhotoveno 6. 6. 2025.\nIncident zaznamenán 4. 6. 2025 ve 21:40, rozsah se prověřuje."

    private fun evidenceRun(swap: Boolean): Pair<JSONObject, JSONObject> {
        val r = Rec(); val o = ops(r); val chat = o.newChat()
        val (x, y) = if (swap) postA.replace("Jana Malá, uznávaná redaktorka s 20 lety praxe", "anonymní autor bez historie") to postB.replace("anonymní autor bez historie", "uznávaná redaktorka s 20 lety praxe") else postA to postB
        val ids = listOf(o.attach("clanek1.txt", "text/plain", x.toByteArray()).getString("id"), o.attach("clanek2.txt", "text/plain", y.toByteArray()).getString("id"))
        val m1 = say(o, r, chat, "Dva články tvrdí totéž. Na kolik procent je to pravdivé?", ids)
        val m2 = say(o, r, chat, "Přidávám nezávislý doklad.", listOf(o.attach("protokol.txt", "text/plain", protocol.toByteArray()).getString("id")))
        return m1 to m2
    }

    @Test fun evidenceIndependenceSameCriteriaRegardlessOfReputation() {
        val (m1, m2) = evidenceRun(false)
        val t1 = m1.getString("text")
        assertTrue(t1, t1.contains("tvrzení šířilo") && t1.contains("tutéž výpověď") && t1.contains("chybí nezávislé potvrzení nebo ověřitelný prvotní doklad"))
        assertFalse("žádná procenta bez základu: $t1", Regex("\\d+\\s*%").containsMatchIn(t1))
        val t2 = m2.getString("text")
        assertTrue(t2, t2.contains("Změna hodnocení") && t2.contains("Datum události (4. 6. 2025) je potvrzené nezávislým datovaným dokladem") && t2.contains("motiv / důvod zůstává otevřený"))
        // prohozená pověst autorů, stejný obsah důkazů → stejné hodnocení
        val (s1, s2) = evidenceRun(true)
        fun st(m: JSONObject) = m.getJSONObject("card").getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> p.getString("label") + "=" + p.getString("status") } } }
        assertEquals(st(m1), st(s1)); assertEquals(st(m2), st(s2))
    }

    @Test fun depthRoutineVsConditionalDecision() {
        val r = Rec(); val o = ops(r); val chat = o.newChat()
        val p = say(o, r, chat, "Kolik je 12 × 250 Kč?").getString("text")
        assertEquals("3 000 Kč", p)
        val t = say(o, r, chat, "Vyplatí se roční tarif za 2 400 Kč oproti 250 Kč měsíčně?").getString("text")
        assertTrue(t, t.startsWith("Roční tarif se vyplatí, pokud budeš platit aspoň 10 měsíců"))
        assertTrue(t, t.contains("Při 8 měsících vyjde měsíční varianta o 400 Kč levněji") && t.contains("při 12 měsících roční o 600 Kč levněji") && t.contains("nevratná"))
        assertEquals(1, Regex("\\?").findAll(t).count()) // jediná konkrétní otázka
        val c = say(o, r, chat, "A co když jde roční tarif vrátit s poměrnou částí?").getString("text")
        assertTrue(c, c.startsWith("Roční tarif vychází levněji při jakékoli délce využití") && c.contains("200 Kč za měsíc"))
        val m = say(o, r, chat, "Budu ho používat 8 měsíců.").getString("text")
        assertTrue(m, m.contains("Při 8 měsících vyjde roční tarif o 400 Kč levněji"))
    }

    @Test fun exportFailureIsReportedRowsKeptThenOneReadableFile() {
        val r = Rec(); val o = ops(r); val chat = o.newChat()
        val f = javaClass.getResource("/vypis-duben.csv")!!.readBytes()
        say(o, r, chat, "udělej z toho nástroj na výpis", listOf(o.attach("vypis-duben.csv", "text/csv", f).getString("id")))
        // skutečná chyba zápisu: cílová „složka“ je soubor
        val good = o.out.dir
        val bad = File(ctx.filesDir, "nezapisovatelne").apply { delete(); writeText("x") }
        o.out.dir = bad
        val fail = say(o, r, chat, "ulož mi to jako CSV")
        assertTrue(fail.getString("text"), fail.getString("text").startsWith("Export se NEPODAŘIL") && fail.getString("text").contains("Spočítané řádky"))
        assertEquals("exportRetry", fail.getJSONObject("card").getString("type"))
        val run = fail.getJSONObject("card").getString("run")
        assertTrue((o.run(run)!!.last as cz.axiom.vega.core.Value.Table).rows.isNotEmpty()) // řádky zůstaly
        // obnovení zápisu → jeden čitelný soubor se správnými hodnotami
        o.out.dir = good; good.listFiles()?.forEach { it.delete() }
        val n = r.ev.size; o.exportRetry(chat, run)
        val end = System.currentTimeMillis() + 10_000; while (System.currentTimeMillis() < end && r.ev.drop(n).none { it.first == "idle" }) Thread.sleep(20)
        val ok = r.ev.drop(n).last { it.first == "message" }.second.getJSONObject("msg")
        assertTrue(ok.getString("text"), ok.getString("text").startsWith("Uloženo:"))
        val files = good.listFiles()!!.filter { !it.name.startsWith(".") }
        assertEquals(1, files.size)
        val table = o.run(run)!!.last as cz.axiom.vega.core.Value.Table
        // částky jdou do CSV jako čísla pro Excel („−16 124,46“ → „-16124,46“), jinak beze změny (den 24. 11., kolo 11)
        fun excel(c: String) = c.replace('−', '-').replace("\u00a0", "").replace("\u202f", "")
        assertEquals(table.rows.map { r -> r.map(::excel) }, cz.axiom.vega.core.Csv.read(cz.axiom.vega.core.Encoding.decode(files[0].readBytes())).rows)
    }

    @Test fun groundingPerCitationAndMissingBundle() {
        val o = ops(Rec())
        // bez balíčku: každé tvrzení nevyhodnoceno s důvodem, nic nepadá
        val miss = o.groundChecks("Výbor se v pondělí sešel [1].", listOf("The committee was scheduled to meet on Monday."))
        assertEquals("NEVYHODNOCENO", miss.getJSONObject(0).getString("label"))
        assertTrue(miss.getJSONObject(0).getString("reason").contains("není nainstalovaný"))
        // zástupný ověřovač: dva zdroje si odporují → dva samostatné výsledky; souvětí s motivem → datum a motiv zvlášť
        o.grounding = object : cz.axiom.vega.core.Grounding.Checker {
            override val ready = true; override val status = "test"
            override fun check(claim: String, passage: String) = cz.axiom.vega.core.Grounding.Verdict(
                if (passage.contains("did not")) cz.axiom.vega.core.Grounding.Label.ODPORUJE else if (claim.contains("protože")) cz.axiom.vega.core.Grounding.Label.NEDOSTACUJE else cz.axiom.vega.core.Grounding.Label.PODPORUJE, floatArrayOf(.5f, .3f, .2f))
        }
        val r = o.groundChecks("Výbor se 3. 3. 2025 sešel, protože chtěl schválit rozpočet [1][2].", listOf("The committee met on 3 March 2025.", "The committee did not meet on 3 March 2025."))
        val got = (0 until r.length()).map { r.getJSONObject(it).let { j -> j.getString("kind") + "/" + j.getInt("n") + "=" + j.getString("label") } }
        assertEquals(listOf("událost/1=PODPORUJE", "událost/2=ODPORUJE", "motiv / důvod/1=NEDOSTACUJE", "motiv / důvod/2=ODPORUJE"), got)
        // citace bez zdroje
        assertEquals("NEVYHODNOCENO", o.groundChecks("Něco tvrdím s odkazem [5].", listOf("x")).getJSONObject(0).getString("label"))
    }
}
