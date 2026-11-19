package cz.axiom.vega

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * PŘIJÍMACÍ ZKOUŠKY ZADÁNÍ (body 1–4) přes běžné vstupy aplikace (Ops.chat, karty, „restart“ = nová instance nad
 * stejným úložištěm). Model = LOKÁLNÍ OpenAI-kompatibilní server (stejné API jako llama.cpp server) se skriptovanými
 * odpověďmi → testuje se VEGA (směrování, obnova, smyčka testů), ne kvalita modelu. JS testy běží v Node se stejnou
 * sadou test/expect jako Worker v aplikaci. Označení: JVM + Robolectric, ne zařízení.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsSpecTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private fun fixture(n: String) = javaClass.getResource("/$n")!!.readBytes()

    /** Komerční klíč JE uložený – důkaz, že režim „Pouze vlastní modely“ ho přesto nepoužije. */
    private class FakeVault(c: android.content.Context) : Vault(c) {
        val m = java.util.concurrent.ConcurrentHashMap(mapOf("gemini" to "AIzaTEST-not-a-real-key-000000000000"))
        override fun read(name: String) = m[name]
        override fun has(name: String) = m.containsKey(name)
        override fun save(name: String, secret: String) { m[name] = secret }
        override fun remove(name: String) { m.remove(name) }
    }

    // ---------------------------------------------------------------- lokální „vlastní server“ (OpenAI API)
    /** Minimální HTTP server na soketu (v Android unit testech není com.sun.net.httpserver). */
    private class MockLlm(val answer: (JSONObject) -> String) {
        val calls = CopyOnWriteArrayList<JSONObject>()
        @Volatile var cutAfter: String? = null // přeruší stream po tomto textu (výpadek uprostřed odpovědi)
        private val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        init { Thread { while (!ss.isClosed) { val so = try { ss.accept() } catch (e: Exception) { break }; Thread { runCatching { handle(so) }; runCatching { so.close() } }.start() } }.apply { isDaemon = true }.start() }

        private fun handle(so: java.net.Socket) {
            val inp = java.io.BufferedInputStream(so.getInputStream())
            fun line(): String { val b = java.io.ByteArrayOutputStream(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
            val req = line(); var len = 0
            while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt() }
            val body = ByteArray(len); var got = 0; while (got < len) { val n = inp.read(body, got, len - got); if (n < 0) break; got += n }
            val out = so.getOutputStream()
            if (req.contains("/models")) { val b = """{"data":[{"id":"qwen2.5-1.5b-instruct"}]}""".toByteArray(); out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b); out.flush(); return }
            val j = JSONObject(String(body, Charsets.UTF_8)); calls += j
            val text = answer(j)
            fun ev(p: String) = ("data: " + JSONObject().put("model", "qwen2.5-1.5b-instruct").put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", p)))) + "\n\n").toByteArray()
            val cut = cutAfter
            if (cut != null && text.contains(cut)) {
                // výpadek uprostřed odpovědi: ohlášená délka se nedodrží, spojení se utne
                // výpadek uprostřed odpovědi (server spadl): část textu, pak chybová událost streamu a konec spojení
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.write(ev(text.substringBefore(cut) + cut))
                out.write("data: {\"error\":{\"message\":\"server přerušil generování\"}}\n\n".toByteArray()); out.flush(); return
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            text.chunked(40).forEach { out.write(ev(it)); out.flush() }
            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
        }
        val url get() = "http://127.0.0.1:${ss.localPort}/v1"
        fun stop() = ss.close()
    }

    private val servers = ArrayList<MockLlm>()
    @After fun down() { servers.forEach { it.stop() }; cz.axiom.omega.core.llm.Http.airgap = false }

    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }

    /** Ops s automatickým souhlasem (uživatel klepne „Odeslat“) a JS testy spuštěnými v Node (jako Worker v aplikaci). */
    private fun ops(r: Rec, vault: Vault = FakeVault(ctx)): Ops {
        lateinit var o: Ops
        o = Ops(ctx, vault) { t, d ->
            r.ev += t to d
            when (t) {
                "consent" -> Thread { o.answer(d.getString("req"), JSONObject().put("ok", true)) }.start()
                "jstest" -> if (!holdTests) Thread { o.answer(d.getString("req"), node(d.getJSONObject("files"))) }.start()
            }
        }
        o.settings.airgap = false
        return o
    }
    @Volatile private var holdTests = false

    private fun node(files: JSONObject): JSONObject {
        val runner = File.createTempFile("runner", ".js").apply { writeBytes(fixture("node-runner.js")); deleteOnExit() }
        val p = ProcessBuilder("node", runner.absolutePath).redirectErrorStream(false).start()
        p.outputStream.use { it.write(files.toString().toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8); p.waitFor()
        return JSONObject(out)
    }

    private fun awaitMsg(r: Rec, before: Int, ms: Long = 30_000): JSONObject {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (r.ev.drop(before).any { it.first == "idle" }) break; Thread.sleep(20) }
        r.ev.drop(before).firstOrNull { it.first == "error" }?.let { throw AssertionError("chyba: ${it.second}") }
        return r.ev.drop(before).last { it.first == "message" }.second.getJSONObject("msg")
    }
    private fun msgs(r: Rec, before: Int) = r.ev.drop(before).filter { it.first == "message" }.map { it.second.getJSONObject("msg") }

    // ================================================================= 1. Pouze vlastní modely

    @Test fun ownOnlyCompletesThenOutageKeepsStateOffersResumeAndZeroCommercial() {
        val llm = MockLlm { req ->
            val sys = req.getJSONArray("messages").getJSONObject(0).getString("content")
            if (sys.contains("Vypiš z pasáže")) "Fakt z pasáže." else "## 1. Odpověď\nHotovo z vlastního modelu."
        }.also { servers += it }
        val r = Rec(); val o = ops(r)
        o.settings.ownUrl = llm.url; o.settings.ownOnly = true
        val c0 = o.settings.commercialRequests()
        // kontrola: bez režimu by řetěz po vlastním serveru pokračoval na Gemini (klíč je uložený)
        o.settings.ownOnly = false
        assertTrue(o.engines.pick()!!.chain.map { it.first }.let { it.first() == "Vlastní model" && it.size >= 2 })
        o.settings.ownOnly = true
        assertEquals(listOf("Vlastní model"), o.engines.pick()!!.chain.map { it.first })
        assertTrue(o.engines.pendingPaid().isEmpty())

        // (a) server dostupný: běžný rozhovor i dlouhé zadání po částech (pomocné kroky = výtahy pasáží)
        val chat = o.newChat()
        var n = r.ev.size
        o.chat(chat, "Ahoj, jak se máš?")
        assertTrue(awaitMsg(r, n).getString("text").contains("vlastního modelu"))
        n = r.ev.size
        val long = (1..9).joinToString("\n\n") { "Odstavec $it: " + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(60) } + "\n\nOtázky: Co je v odstavci 1? Co je v odstavci 9?"
        o.longTask(chat, long)
        awaitMsg(r, n, 60_000)
        val helperCalls = llm.calls.count { it.getJSONArray("messages").getJSONObject(0).getString("content").contains("Vypiš z pasáže") }
        assertTrue("pomocné kroky musí jít na vlastní server", helperCalls >= 2)
        assertTrue(o.settings.requests("own") >= helperCalls + 2)
        assertEquals(c0, o.settings.commercialRequests())

        // (b) výpadek serveru: dlouhé zadání se přeruší → stav uložen, karta „Pokračovat“, nic na komerční API
        val before = llm.calls.size
        llm.stop()
        n = r.ev.size
        o.chat(chat, "Shrň mi prosím, co jsme probrali.")
        val m = awaitMsg(r, n)
        assertEquals("resume", m.getJSONObject("card").getString("type"))
        assertTrue(m.getString("text"), m.getString("text").contains("nic se neztratilo"))
        assertNotNull(o.pendingResume(chat))
        assertEquals(c0, o.settings.commercialRequests())
        // uživatelova zpráva je v historii jen jednou
        assertEquals(1, (0 until o.messages(chat).length()).count { o.messages(chat).getJSONObject(it).optString("text") == "Shrň mi prosím, co jsme probrali." })

        // (c) „restart“ + server zase běží na stejné adrese → Pokračovat → hotovo, zpráva se nezdvojí
        val port = java.net.URI(llm.url).port
        val llm2 = MockLlm { "Shrnutí: mluvili jsme o odstavcích." }
        servers += llm2
        val r2 = Rec(); val o2 = ops(r2)
        o2.settings.ownUrl = llm2.url
        n = r2.ev.size
        o2.retry(chat)
        assertTrue(awaitMsg(r2, n).getString("text").contains("Shrnutí"))
        assertNull(o2.pendingResume(chat))
        assertEquals(1, (0 until o2.messages(chat).length()).count { o2.messages(chat).getJSONObject(it).optString("text") == "Shrň mi prosím, co jsme probrali." })
        assertEquals(c0, o2.settings.commercialRequests())
        assertTrue(before > 0 && port > 0)
    }

    @Test fun outageMidAnswerKeepsPartialAndLongTaskResumesWithoutRedoingPassages() {
        val passages = AtomicInteger()
        val llm = MockLlm { req ->
            val sys = req.getJSONArray("messages").getJSONObject(0).getString("content")
            if (sys.contains("Vypiš z pasáže")) { passages.incrementAndGet(); "Výtah." } else "## 1. Část\nPrvní polovina odpovědi PŘERUŠENÍ a zbytek."
        }.also { servers += it }
        val r = Rec(); val o = ops(r)
        o.settings.ownUrl = llm.url; o.settings.ownOnly = true
        llm.cutAfter = "PŘERUŠENÍ"
        val chat = o.newChat()
        val long = (1..8).joinToString("\n\n") { "Kapitola $it. " + "Text kapitoly s fakty a čísly. ".repeat(110) } + "\n\nCo je v kapitole 1? Co je v kapitole 8?"
        var n = r.ev.size
        o.longTask(chat, long)
        awaitMsg(r, n, 60_000)
        val ms = msgs(r, n)
        assertTrue("rozepsaná odpověď uložena", ms.any { it.getString("text").contains("První polovina") })
        assertEquals("resume", ms.last().getJSONObject("card").getString("type"))
        val firstRound = passages.get()
        assertTrue(firstRound >= 2)
        // obnova: výtahy pasáží se NEpočítají znovu (cache), dopočítá se jen syntéza
        llm.cutAfter = null
        n = r.ev.size
        o.retry(chat)
        awaitMsg(r, n, 60_000)
        assertEquals(firstRound, passages.get())
        assertEquals(0L, o.settings.commercialRequests())
    }

    // ================================================================= 2. navázání na práci + pravidla projektu

    @Test fun projectRuleIsolatedAfterRestart() {
        val r = Rec(); val o = ops(r)
        val chat = o.newChat()
        var n = r.ev.size
        o.chat(chat, "Vždy používej čárku jako oddělovač v CSV"); assertTrue(awaitMsg(r, n).getString("text").contains("platí všude"))
        val a = o.createProject("Účetnictví A", emptyMap())
        n = r.ev.size
        o.chat(chat, "tady používej středník"); assertTrue(awaitMsg(r, n).getString("text").contains("jen pro projekt"))
        val b = o.createProject("Projekt B", emptyMap())
        // po restartu: A → středník (pravidlo projektu má přednost), B → čárka (globální), pravidlo A do B neproniklo
        val o2 = ops(Rec())
        assertEquals(';', o2.csvDelimiter(a))
        assertEquals(',', o2.csvDelimiter(b))
        assertTrue(o2.memory.context("project:$b")?.contains("středník") != true)
        assertTrue(o2.memory.context("project:$a")!!.contains("středník"))
        // „tady…“ bez otevřeného projektu se neuloží potichu globálně – otázka
        o2.settings.activeProject = null
        val r3 = Rec(); val o3 = ops(r3); o3.settings.activeProject = null
        n = r3.ev.size; o3.chat(o3.newChat(), "tady piš testy v Jestu")
        assertEquals("memScope", awaitMsg(r3, n).getJSONObject("card").getString("type"))
        assertTrue(o3.memory.context(null)?.contains("Jestu") != true)
    }

    // ================================================================= 4. programovací smyčka (+ 2. navázání, přerušení)

    private val buggy = mapOf(
        "src/money.js" to "// převod české částky na číslo\nfunction parseCzk(s) {\n  return parseFloat(s.replace(/\\s/g, ''));\n}\nmodule.exports = { parseCzk };\n",
        "src/total.js" to "const { parseCzk } = require('./money');\nfunction total(lines) {\n  return lines.reduce((a, l) => a + parseCzk(l), 0);\n}\nmodule.exports = { total };\n",
        "src/format.js" to "function fmt(n) {\n  return n.toFixed(2);\n}\nmodule.exports = { fmt };\n",
        "test/total.test.js" to "const { total } = require('../src/total');\nconst { parseCzk } = require('../src/money');\ntest('součet s desetinnou čárkou', () => { expect(total(['1 289,90', '10,10'])).toBeCloseTo(1300); });\ntest('záporná částka', () => { expect(parseCzk('-14 200,50')).toBe(-14200.5); });\n",
    )

    private val wrongFix = "Odstraním čárku.\n\n### UPRAVIT: src/money.js\n<<<<<<< HLEDAT\n  return parseFloat(s.replace(/\\s/g, ''));\n=======\n  return parseFloat(s.replace(/\\s/g, '').replace(',', ''));\n>>>>>>> NAHRADIT\n\n" +
        "### UPRAVIT: src/format.js\n<<<<<<< HLEDAT\nfunction fmt(n) {\n=======\n// formát na 2 desetinná místa\nfunction fmt(n) {\n>>>>>>> NAHRADIT\n"
    private val rightFix = "Čárka je desetinná – nahradím ji tečkou.\n\n### UPRAVIT: src/money.js\n<<<<<<< HLEDAT\n.replace(',', ''));\n=======\n.replace(',', '.'));\n>>>>>>> NAHRADIT\n"

    @Test fun codeLoopUsesRealTestOutputThenSelectiveAcceptReverified() {
        val llm = MockLlm { req ->
            val u = req.getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
            if (u.contains("SKUTEČNÝ VÝSTUP TESTŮ")) rightFix else wrongFix
        }.also { servers += it }
        val r = Rec(); val o = ops(r)
        o.settings.ownUrl = llm.url; o.settings.ownOnly = true
        val pid = o.createProject("Pokladna", buggy.mapValues { it.value.toByteArray() })
        // reprodukce: projde „kompilací“ (statická kontrola), ale test vrací špatný výsledek
        val v0 = o.verify(o.workspace(pid))
        assertEquals(1, v0.exitCode); assertTrue(v0.output, v0.output.contains("je 1299"))
        val chat = o.newChat()
        var n = r.ev.size
        o.chat(chat, "oprav součet – s desetinnou čárkou vychází špatně")
        val m = awaitMsg(r, n, 60_000)
        // druhé kolo dostalo SKUTEČNÝ výstup testu z prvního (špatná oprava → 129000)
        assertTrue(llm.calls.size >= 2)
        val second = llm.calls[1].getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
        assertTrue(second, second.contains("je 130000"))
        val card = m.getJSONObject("card")
        assertEquals(0, card.getJSONObject("verification").getInt("exitCode"))
        assertEquals(2, card.getJSONArray("rounds").length())
        assertEquals(o.workspace(pid).revision(), card.getString("revision"))
        assertTrue(card.getJSONArray("files").length() >= 2) // diff money.js + format.js
        val task = (0 until card.getJSONArray("task").length()).map { card.getJSONArray("task").getInt(it) }
        assertEquals(3, task.size)
        // přijmout JEN opravu money.js (bez kosmetiky ve format.js) → ověří se právě tato kombinace
        val keepMoney = o.workspace(pid).changes().filter { it.id in task && it.path == "src/money.js" }.map { it.id }
        n = r.ev.size
        o.keepChanges(chat, pid, task, keepMoney)
        val k = awaitMsg(r, n, 60_000)
        assertTrue(k.getString("text"), k.getString("text").contains("PROŠLA"))
        assertFalse(o.workspace(pid).read("src/format.js").contains("formát na 2"))
        assertTrue(o.workspace(pid).read("src/money.js").contains(".replace(',', '.')"))
        // opačný výběr (jen kosmetika) → kombinace ověřena a NEprojde
        val pid2 = o.createProject("Pokladna 2", buggy.mapValues { it.value.toByteArray() })
        n = r.ev.size; o.chat(chat, "oprav součet – s desetinnou čárkou vychází špatně"); awaitMsg(r, n, 60_000)
        val t2 = o.workspace(pid2).changes().map { it.id }
        n = r.ev.size; o.keepChanges(chat, pid2, t2, o.workspace(pid2).changes().filter { it.path == "src/format.js" }.map { it.id })
        assertTrue(awaitMsg(r, n, 60_000).getString("text").contains("NEPROŠLA"))
        assertEquals(0L, o.settings.commercialRequests())
    }

    @Test fun interruptedTaskResumesWithoutDuplicatesAndKeepsManualEdit() {
        // model je deterministický: stejné zadání → stejné úpravy (+ pokus přepsat celý format.js)
        val llm = MockLlm { req ->
            val u = req.getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
            val fix = "### UPRAVIT: src/money.js\n<<<<<<< HLEDAT\n  return parseFloat(s.replace(/\\s/g, ''));\n=======\n  return parseFloat(s.replace(/\\s/g, '').replace(',', '.'));\n>>>>>>> NAHRADIT\n"
            if (u.contains("POKRAČOVÁNÍ")) fix + "\n### NOVÝ: src/format.js\n```js\nmodule.exports = { fmt: n => String(n) };\n```\n" else fix
        }.also { servers += it }
        val r = Rec(); val o = ops(r)
        o.settings.ownUrl = llm.url; o.settings.ownOnly = true
        val pid = o.createProject("Pokladna", buggy.mapValues { it.value.toByteArray() })
        val chat = o.newChat()
        var n = r.ev.size
        o.chat(chat, "oprav součet – s desetinnou čárkou vychází špatně"); awaitMsg(r, n, 60_000) // první práce → uložený stav projektu
        // nová úloha, PŘERUŠENÁ uprostřed (úpravy aplikovány, testy nedoběhly – aplikace „zabita“)
        val ws = o.workspace(pid)
        ws.undo(); assertTrue(ws.read("src/money.js").contains("replace(/\\s/g, ''));"))
        holdTests = true
        n = r.ev.size
        o.chat(chat, "oprav součet – s desetinnou čárkou vychází špatně")
        val end = System.currentTimeMillis() + 20_000
        while (r.ev.drop(n).none { it.first == "jstest" } && System.currentTimeMillis() < end) Thread.sleep(20)
        o.stop(); awaitMsg(r, n)
        holdTests = false
        val applied = ws.changes().size
        // uživatel mezitím ručně upravil soubor
        val manual = ws.read("src/format.js").replace("toFixed(2)", "toFixed(2) // ručně: vždy 2 místa")
        ws.file("src/format.js").writeText(manual)
        // „restart“ → „pokračuj tam, kde jsme skončili“
        val r2 = Rec(); val o2 = ops(r2)
        o2.settings.ownUrl = llm.url; o2.settings.activeProject = null
        n = r2.ev.size
        o2.chat(o2.newChat(), "pokračuj tam, kde jsme skončili")
        val c = awaitMsg(r2, n)
        assertEquals(pid, o2.settings.activeProject)
        assertTrue(c.getString("text"), c.getString("text").contains("Pokladna") && c.getString("text").contains("Přerušená úloha") && c.getString("text").contains("src/format.js"))
        assertEquals("Tvoje ruční změny", c.getJSONObject("card").getString("title"))
        assertNotNull(c.getJSONObject("card").optJSONObject("resumeTask"))
        n = r2.ev.size
        o2.resumeTask(chat, pid)
        val done = awaitMsg(r2, n, 60_000)
        val w2 = o2.workspace(pid)
        assertEquals(1, Regex(Regex.escape(".replace(',', '.')")).findAll(w2.read("src/money.js")).count()) // nezdvojeno
        assertTrue(w2.read("src/format.js").contains("ručně: vždy 2 místa")) // ruční zásah nepřepsán
        assertEquals(applied, w2.changes().size) // žádná nová (duplicitní) změna
        assertTrue(done.getString("text"), done.getString("text").contains("NEaplikováno")) // pokus přepsat format.js odmítnut
        assertEquals(0, done.getJSONObject("card").getJSONObject("verification").getInt("exitCode"))
    }

    // ================================================================= 3. + hlavní souvislý průchod (CSV)

    @Test fun mainPassFixCsvTogetherSaveButtonRestartOfflineShuffledColumns() {
        val r = Rec(); val o = ops(r, FakeVault(ctx).also { it.m.clear() })
        o.settings.airgap = true // celé: bez sítě a bez modelu
        val chat = o.newChat()
        // 1) uživatel popíše problém
        var n = r.ev.size
        val f1 = o.attach("vypis-cerven.csv", "text/csv", fixture("vypis-cerven.csv")).getString("id")
        o.chat(chat, "Z banky mi chodí tenhle výpis, udělej z toho nástroj na zpracování výpisu, saldo mi nesedí", listOf(f1))
        val m1 = awaitMsg(r, n)
        assertTrue(m1.getString("text"), m1.getString("text").contains("ZŮSTATEK") && m1.getString("text").contains("částka je ve sloupci Pohyb"))
        // 2) VEGA opraví „program“ (parser) podle věty uživatele, 3) test (návaznost na zůstatek banky) potvrdí
        n = r.ev.size
        o.chat(chat, "částka je ve sloupci Pohyb")
        val m2 = awaitMsg(r, n)
        assertTrue(m2.getString("text"), m2.getString("text").contains("4/4 návazností sedí"))
        assertTrue(m2.getString("text"), m2.getString("text").contains("Příjmy 42 350,50"))
        // 4) uloží postup jako tlačítko: návrh → ověření na JINÉM souboru → uložit
        n = r.ev.size
        o.chat(chat, "udělej mi z toho tlačítko")
        val d = awaitMsg(r, n).getJSONObject("card")
        assertEquals("buttonDraft", d.getString("type")); assertFalse(d.getBoolean("canSave")); assertTrue(d.getBoolean("offline"))
        assertTrue(d.getJSONArray("fixed").getJSONObject(0).getString("detail").contains("částka = pohyb"))
        n = r.ev.size
        o.buttonTry(chat, o.attach("vypis-cervenec.csv", "text/csv", fixture("vypis-cervenec.csv")).getString("id"))
        val tr = awaitMsg(r, n)
        assertTrue(tr.getString("text"), tr.getJSONObject("card").getBoolean("canSave"))
        val saved = o.buttonSave(chat, "Měsíční výpis")
        // 5) restart + offline + nový výpis s PŘEHÁZENÝMI sloupci → připnuté tlačítko i věta = stejná implementace
        val r2 = Rec(); val o2 = ops(r2, FakeVault(ctx).also { it.m.clear() }); o2.settings.airgap = true
        val btn = o2.home().getJSONArray("buttons").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("id") == saved.getString("id") }
        assertTrue(btn.getBoolean("offline"))
        n = r2.ev.size
        o2.runProcedure(chat, btn.getString("id"), JSONObject().put("file", o2.attach("vypis-srpen-prehazeny.csv", "text/csv", fixture("vypis-srpen-prehazeny.csv")).getString("id")))
        val viaButton = awaitMsg(r2, n).getJSONObject("card")
        n = r2.ev.size
        o2.chat(o2.newChat(), "měsíční výpis", listOf(o2.attach("vypis-srpen-prehazeny.csv", "text/csv", fixture("vypis-srpen-prehazeny.csv")).getString("id")))
        val viaChat = awaitMsg(r2, n).getJSONObject("card")
        assertEquals(viaButton.getJSONObject("procedure").toString(), viaChat.getJSONObject("procedure").toString())
        // ověření proti předem připravenému výsledku
        val exp = String(fixture("vypis-srpen-ocekavane.csv")).trim().lines()
        val rows = o2.runJson(viaButton.getString("run"))!!.getJSONArray("stepValues").getJSONObject(0)
        val got = rows.getJSONArray("rows").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> (0 until r.length()).joinToString(";") { r.getString(it) } } } }
        assertEquals(exp.drop(1).dropLast(1), got)
        val notes = rows.getJSONArray("notes").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }
        assertTrue(notes, notes.contains("Příjmy 44 350,00") && notes.contains("výdaje −29 074,90") && notes.contains("saldo 15 275,10") && notes.contains("6 pohybů"))
        assertTrue(notes, notes.contains("5/5 návazností sedí"))
        assertEquals(0L, o2.settings.commercialRequests())
    }

    // ================================================================= změna zadání během běžící úlohy

    @Test fun revisionDuringRunningCompareReusesUnchangedFilesAndDropsLateAnswer() {
        val rev1Arrived = java.util.concurrent.CountDownLatch(1)
        val releaseRev1 = java.util.concurrent.CountDownLatch(1)
        val llm = MockLlm { req ->
            val u = req.getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
            if (u.contains("REVIZE 1")) { rev1Arrived.countDown(); releaseRev1.await(30, java.util.concurrent.TimeUnit.SECONDS); "Shrnutí ke staré revizi: celkem 74 789,70 Kč." }
            else "Shrnutí aktuální revize: únor se po opravě zlepšil."
        }.also { servers += it }
        val r = Rec(); val o = ops(r)
        o.settings.ownUrl = llm.url; o.settings.ownOnly = true
        val chat = o.newChat()
        val ids = listOf("cmp-leden.csv", "cmp-unor.csv", "cmp-brezen.csv").map { o.attach(it, "text/csv", fixture(it)).getString("id") }
        val n = r.ev.size
        o.chat(chat, "porovnej tyhle tři výpisy a sečti saldo", ids)
        // cloudová odpověď ke staré revizi „visí“ – mezitím uživatel vymění prostřední soubor
        assertTrue("model nedostal dotaz", rev1Arrived.await(20, java.util.concurrent.TimeUnit.SECONDS))
        o.chat(chat, "nahraď prostřední soubor touto opravenou verzí", listOf(o.attach("cmp-unor-opraveny.csv", "text/csv", fixture("cmp-unor-opraveny.csv")).getString("id")))
        assertTrue(msgs(r, n).any { it.getString("text").contains("revizi 2") })
        releaseRev1.countDown() // původní odpověď dorazí až PO opravě
        awaitMsg(r, n, 30_000)
        val all = msgs(r, n)
        val fin = all.last()
        // konečný součet odpovídá NOVÝM datům a stará odpověď se nikde nezobrazila
        assertTrue(fin.getString("text"), fin.getString("text").contains("77\u00a0739,70"))
        assertTrue(fin.getString("text").contains("Shrnutí aktuální revize"))
        assertTrue(all.none { it.getString("text").contains("Shrnutí ke staré revizi") })
        assertTrue(r.ev.none { it.first == "delta" && it.second.optString("text").contains("staré revizi") })
        // uživatel vidí rozdíl
        assertTrue(fin.getString("text"), fin.getString("text").contains("cmp-unor.csv → cmp-unor-opraveny.csv") && fin.getString("text").contains("74\u00a0789,70 → 77\u00a0739,70"))
        val card = fin.getJSONObject("card"); assertEquals(2, card.getInt("rev"))
        val files = card.getJSONArray("files")
        assertEquals(listOf("znovu použito", "spočítáno", "znovu použito"), (0 until 3).map { files.getJSONObject(it).getString("status") })
        assertTrue(files.getJSONObject(1).getBoolean("changed"))
        // záznam běhu: nezměněné soubory zpracovány PRÁVĚ JEDNOU (v revizi 1), stará odpověď zahozena
        val log = card.getJSONArray("log").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun computed(name: String) = log.count { it.getString("input") == name && it.getString("action") == "spočítáno" }
        assertEquals(1, computed("cmp-leden.csv")); assertEquals(1, computed("cmp-brezen.csv"))
        assertEquals(1, computed("cmp-unor.csv")); assertEquals(1, computed("cmp-unor-opraveny.csv"))
        assertTrue(log.any { it.getInt("rev") == 2 && it.getString("input") == "cmp-leden.csv" && it.getString("action").startsWith("znovu použito") })
        assertTrue(log.any { it.getInt("rev") == 1 && it.getString("action").startsWith("ZAHOZENO") })
        // po restartu: další oprava (rozsah) navazuje na uložený stav – žádný soubor se znovu nezpracuje
        val r2 = Rec(); val o2 = ops(r2); o2.settings.ownUrl = llm.url
        val n2 = r2.ev.size
        o2.chat(chat, "jen výdaje")
        awaitMsg(r2, n2, 30_000)
        val f2 = msgs(r2, n2).last()
        assertEquals(3, f2.getJSONObject("card").getInt("rev"))
        assertTrue(f2.getString("text"), f2.getString("text").contains("−50\u00a0960,30")) // výdaje: 16 810,40 + 17 750 + 16 399,90
        val log2 = f2.getJSONObject("card").getJSONArray("log").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertTrue(log2.none { it.getInt("rev") == 3 && it.getString("action") == "spočítáno" && it.getString("step") == "zpracování souboru" })
        assertEquals(0L, o.settings.commercialRequests())
    }
}
