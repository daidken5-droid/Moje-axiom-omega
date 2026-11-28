package cz.axiom.vega

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MĚŘENÍ „co VEGA přidává“: stejný vlastní model (OpenAI API, např. llama.cpp server) SAMOSTATNĚ × tentýž model
 * UVNITŘ VEGA, na závěrečných příkladech z eval/cases.json (kritéria stanovena předem). Spouští se jen ručně:
 * adresa serveru v ~/.vega-eval-url (např. http://127.0.0.1:8091/v1). Výsledek: build/vega-eval.json.
 * Prostředí: JVM + Robolectric v kontejneru (NE telefon, NE emulátor); model běží na CPU kontejneru.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VegaEvalTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val url: String? = (System.getenv("VEGA_EVAL_URL") ?: File(System.getProperty("user.home"), ".vega-eval-url").takeIf { it.exists() }?.readText())?.trim()?.ifBlank { null }
    /** ~/.vega-eval-only = čárkami oddělená id případů (dodělání přerušeného měření); jinak všechny. */
    private val only: Set<String>? = File(System.getProperty("user.home"), ".vega-eval-only").takeIf { it.exists() }?.readText()?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
    private fun want(id: String) = only == null || id in only
    private fun res(n: String) = javaClass.getResource("/$n")!!.readBytes()

    private class NoKeys(c: android.content.Context) : Vault(c) {
        override fun read(name: String): String? = null; override fun has(name: String) = false
        override fun save(name: String, secret: String) {}; override fun remove(name: String) {}
    }
    private class Rec { val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>() }

    private fun ops(r: Rec): Ops {
        lateinit var o: Ops
        o = Ops(ctx, NoKeys(ctx)) { t, d ->
            r.ev += t to d
            when (t) {
                "consent" -> Thread { o.answer(d.getString("req"), JSONObject().put("ok", true)) }.start()
                "jstest" -> Thread { o.answer(d.getString("req"), node(d.getJSONObject("files"))) }.start()
            }
        }
        o.settings.airgap = false; o.settings.ownUrl = url!!; o.settings.ownOnly = true; o.settings.webAllowed = true
        return o
    }

    private fun node(files: JSONObject): JSONObject {
        val runner = File.createTempFile("runner", ".js").apply { writeBytes(res("node-runner.js")); deleteOnExit() }
        val p = ProcessBuilder("node", runner.absolutePath).start()
        p.outputStream.use { it.write(files.toString().toByteArray()) }
        return JSONObject(p.inputStream.readBytes().toString(Charsets.UTF_8).also { p.waitFor() })
    }

    /** Pošle zprávu a počká na konec; vrátí všechny odpovědi VEGA (text + karty). */
    private fun say(o: Ops, r: Rec, chat: String, text: String, files: List<Pair<String, ByteArray>> = emptyList(), ms: Long = 900_000): List<JSONObject> {
        val n = r.ev.size
        o.chat(chat, text, files.map { o.attach(it.first, "text/csv", it.second).getString("id") })
        return wait(r, n, ms)
    }
    private fun wait(r: Rec, n: Int, ms: Long): List<JSONObject> {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && r.ev.drop(n).none { it.first == "idle" }) Thread.sleep(50)
        return r.ev.drop(n).filter { it.first == "message" || it.first == "error" }.map { if (it.first == "error") JSONObject().put("text", "CHYBA: " + it.second.optString("text")) else it.second.getJSONObject("msg") }
    }
    private fun allText(ms: List<JSONObject>) = ms.joinToString("\n") { m -> m.optString("text") + "\n" + (m.optJSONObject("card")?.toString() ?: "") }

    /** Model SAMOSTATNĚ: jeden dotaz na stejný server, bez nástrojů, paměti a ověřování (jako holý chat s modelem). */
    private fun raw(user: String, system: String? = null, maxTokens: Int = 1200): String {
        val msgs = JSONArray().apply { system?.let { put(JSONObject().put("role", "system").put("content", it)) }; put(JSONObject().put("role", "user").put("content", user)) }
        val c = java.net.URL("$url/chat/completions").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 900_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("model", "default").put("messages", msgs).put("max_tokens", maxTokens).put("temperature", 0.4).toString().toByteArray()) }
        return if (c.responseCode in 200..299) JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        else "CHYBA HTTP ${c.responseCode}: " + (c.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) ?: "")
    }

    private fun norm(s: String) = s.replace(' ', ' ').replace('−', '-').replace(Regex("(?<=\\d) (?=\\d{3}\\b)"), "")
    private val czech = Regex("[ěščřžýáíéůúňťď]", RegexOption.IGNORE_CASE)

    private val rows = JSONArray()
    private fun record(id: String, cat: String, variant: String, ok: Boolean, done: Boolean, interventions: Int, sec: Double, note: String, sample: String) {
        rows.put(JSONObject().put("id", id).put("kategorie", cat).put("varianta", variant).put("spravne", ok).put("dokonceno", done).put("zasahy", interventions)
            .put("cas_s", "%.1f".format(java.util.Locale.ROOT, sec).toDouble()).put("cena_kc", 0).put("poznamka", note).put("ukazka", sample.take(400)))
        println("EVAL $id | $variant | správně=$ok | dokončeno=$done | zásahy=$interventions | ${"%.1f".format(sec)} s | $note")
    }
    private inline fun <T> timed(f: () -> T): Pair<T, Double> { val s = System.nanoTime(); val v = f(); return v to (System.nanoTime() - s) / 1e9 }

    private val sleva = mapOf(
        "src/discount.js" to "// sleva v procentech\nfunction applyDiscount(price, pct) {\n  return price - pct / 100;\n}\nmodule.exports = { applyDiscount };\n",
        "src/cart.js" to "const { applyDiscount } = require('./discount');\nfunction cartTotal(items, pct) {\n  const sum = items.reduce((a, i) => a + i.price * i.qty, 0);\n  return applyDiscount(sum, pct);\n}\nmodule.exports = { cartTotal };\n",
        "test/cart.test.js" to "const { cartTotal } = require('../src/cart');\nconst { applyDiscount } = require('../src/discount');\ntest('10 % z 200', () => { expect(applyDiscount(200, 10)).toBeCloseTo(180); });\ntest('košík se slevou', () => { expect(cartTotal([{ price: 50, qty: 2 }, { price: 100, qty: 1 }], 25)).toBeCloseTo(150); });\n",
    )

    @Test fun measure() {
        assumeTrue("měření se spouští jen s ~/.vega-eval-url", url != null)
        val r = Rec(); val o = ops(r)
        val c0 = o.settings.commercialRequests()

        // ---- 1. český rozhovor: výpočet
        if (want("konverzace-vypocet")) run {
            val q = "Kolik je 48 × 37?"
            val (a, t) = timed { raw(q) }; record("konverzace-vypocet", "český rozhovor", "model sám", norm(a).contains("1776"), !a.startsWith("CHYBA"), 0, t, "", a)
            val (m, t2) = timed { say(o, r, o.newChat(), q) }; val x = allText(m)
            record("konverzace-vypocet", "český rozhovor", "model ve VEGA", norm(x).contains("1776"), !x.contains("CHYBA"), 0, t2, if (x.contains("bez modelu")) "deterministické jádro" else "model", x)
        }
        // ---- 2. český rozhovor: vysvětlení
        if (want("konverzace-vysvetleni")) run {
            val q = "Vysvětli mi jednou nebo dvěma větami, co je inflace."
            fun okc(s: String) = czech.containsMatchIn(s) && !Regex("[\\u4e00-\\u9fff]").containsMatchIn(s) && s.trim().length in 30..700 && s.lowercase().contains("cen")
            val (a, t) = timed { raw(q) }; record("konverzace-vysvetleni", "český rozhovor", "model sám", okc(a), !a.startsWith("CHYBA"), 0, t, "${a.length} znaků", a)
            val (m, t2) = timed { say(o, r, o.newChat(), q) }; val x = m.lastOrNull()?.optString("text") ?: ""
            record("konverzace-vysvetleni", "český rozhovor", "model ve VEGA", okc(x), !x.startsWith("CHYBA"), 0, t2, "${x.length} znaků", x)
        }
        // ---- 3. dlouhé vícedílné zadání
        if (want("dlouhe-zadani")) run {
            val filler = "Text kapitoly popisuje organizační detaily, harmonogram porad, seznam účastníků a obecné zásady komunikace. "
            val chapters = (1..12).map { i -> "Kapitola $i.\n" + filler.repeat(22) + when (i) { 3 -> "Rozpočet projektu činí 4,2 milionu Kč. "; 7 -> "Termín dokončení je 30. září 2027. "; 9 -> "Odpovědnou osobou je Ing. Marta Dvořáková. "; else -> "" } + filler.repeat(4) }
            val doc = chapters.joinToString("\n\n") + "\n\nOtázky: Jaký je rozpočet projektu? Kdy je termín dokončení? Kdo je odpovědná osoba?"
            fun okc(s: String) = s.contains("4,2") && (s.contains("září 2027") || s.contains("30. 9. 2027") || s.contains("30.9.2027")) && s.contains("Dvořáková")
            val (a, t) = timed { raw(doc) }; record("dlouhe-zadani", "dlouhé vícedílné zadání", "model sám", okc(a), !a.startsWith("CHYBA"), 0, t, "${doc.length} znaků vstupu", a)
            val chat = o.newChat(); val n = r.ev.size
            val (m, t2) = timed { o.longTask(chat, doc); wait(r, n, 1_800_000) }; val x = allText(m)
            record("dlouhe-zadani", "dlouhé vícedílné zadání", "model ve VEGA", okc(x), !x.contains("CHYBA") && !x.contains("\"type\":\"resume\""), 0, t2, Regex("Pokryto \\d+/\\d+").find(x)?.value ?: "", x)
        }
        // ---- 4. rešerše
        if (want("reserse")) run {
            val q = "Operace Anthropoid"
            val (a, t) = timed { raw(q) }; record("reserse", "rešerše", "model sám", false, !a.startsWith("CHYBA"), 0, t, "model nemá web – zdroje nejde ověřit" + if (Regex("https?://").containsMatchIn(a)) " (uvedl URL bez stažení)" else "", a)
            val (m, t2) = timed { say(o, r, o.newChat(), q) }; val x = allText(m)
            val src = m.mapNotNull { it.optJSONObject("card") }.firstOrNull { it.optString("type") == "sources" }?.getJSONArray("items")?.length() ?: 0
            record("reserse", "rešerše", "model ve VEGA", src > 0 && Regex("\\[\\d+]").containsMatchIn(m.lastOrNull()?.optString("text") ?: ""), src > 0, 0, t2, "staženo zdrojů: $src", x)
        }
        // ---- 5. oprava programu
        if (want("oprava-programu")) run {
            val task = "Sleva se počítá špatně, oprav to."
            val files = sleva.entries.joinToString("\n\n") { "=== ${it.key} ===\n${it.value}" }
            val (a, t) = timed { raw("ÚKOL: $task\n\n$files", cz.axiom.vega.core.CodeAgent.SYSTEM, 1500) }
            val dir = kotlin.io.path.createTempDirectory("raw").toFile(); val ws = cz.axiom.vega.core.Workspace.create(File(dir, "p"), "Sleva", sleva.mapValues { it.value.toByteArray() })
            val ap = cz.axiom.vega.core.CodeAgent.apply(ws, cz.axiom.vega.core.CodeAgent.parse(a), ws.files().associateWith { ws.hash(it)!! }, "raw")
            val rv = node(JSONObject().apply { ws.files().forEach { put(it, ws.read(it)) } })
            record("oprava-programu", "oprava programu", "model sám", rv.getInt("exitCode") == 0, !a.startsWith("CHYBA"), 0, t, "úprav ${ap.changes.size}, neaplikováno ${ap.failed.size}, test kód ${rv.getInt("exitCode")}", a)
            val pid = o.createProject("Sleva", sleva.mapValues { it.value.toByteArray() })
            val (m, t2) = timed { say(o, r, o.newChat(), task) }; val x = allText(m)
            val v = o.verify(o.workspace(pid))
            val rounds = m.mapNotNull { it.optJSONObject("card")?.optJSONArray("rounds") }.lastOrNull()?.length() ?: 0
            record("oprava-programu", "oprava programu", "model ve VEGA", v.exitCode == 0, !x.contains("CHYBA"), 0, t2, "kol $rounds, test kód ${v.exitCode}, revize ${v.revision}", x)
        }
        // ---- 6. práce s daty
        if (want("data-vypis")) run {
            val csv = res("eval/vypis-rijen.csv"); val q = "Spočítej mi z výpisu příjmy, výdaje a saldo."
            fun okc(s: String) = norm(s).let { it.contains("49712,47") && (it.contains("24085,2") || it.contains("24085.2")) && it.contains("25627,27") } ||
                norm(s).let { it.contains("49712.47") && it.contains("24085.2") && it.contains("25627.27") }
            val (a, t) = timed { raw("$q\n\n[Příloha vypis-rijen.csv:]\n" + cz.axiom.vega.core.Encoding.decode(csv)) }
            record("data-vypis", "práce s daty", "model sám", okc(a), !a.startsWith("CHYBA"), 0, t, "", a)
            val (m, t2) = timed { say(o, r, o.newChat(), q, listOf("vypis-rijen.csv" to csv)) }; val x = allText(m)
            record("data-vypis", "práce s daty", "model ve VEGA", okc(x), !x.contains("CHYBA"), 0, t2, Regex("Kontrola proti zůstatku[^\"\\\\]*").find(x)?.value ?: "", x)
        }
        // ---- 7. dokument
        if (want("dokument-vyzva")) run {
            val q = "Napiš předžalobní výzvu: věřitel Jan Malý, Praha; dlužník Stavby s.r.o., Brno; faktura 2026/118 na 48 400 Kč za stavební práce, vystavena 1. 3. 2026, splatnost 15. 3. 2026; dlužník uhradil 10 000 Kč dne 1. 5. 2026. Spočítej dlužnou jistinu a úrok z prodlení k 19. 11. 2026."
            val (a, t) = timed { raw(q, null, 1500) }
            fun okc(s: String) = norm(s).let { it.contains("38400") || it.contains("38 400") } && Regex("(?i)úrok").containsMatchIn(s) && Regex("\\d+[,.]?\\d*\\s*%").containsMatchIn(s)
            record("dokument-vyzva", "dokument", "model sám", okc(a), !a.startsWith("CHYBA"), 0, t, "", a)
            val chat = o.newChat(); var n = r.ev.size
            val (m, t2) = timed {
                o.chat(chat, "pošli dlužníkovi předžalobní výzvu"); wait(r, n, 60_000)
                val form = JSONObject().put("creditor", "Jan Malý").put("creditorAddr", "Praha").put("creditorId", "").put("debtor", "Stavby s.r.o.").put("debtorAddr", "Brno").put("debtorId", "")
                    .put("invoice", "2026/118").put("issued", "2026-03-01").put("due", "2026-03-15").put("amount", 48400.0).put("subject", "stavební práce")
                    .put("payDate", "2026-05-01").put("payAmount", 10000.0).put("payDoc", "výpis z účtu").put("asOf", "2026-11-19").put("account", "").put("b2b", true)
                n = r.ev.size; o.demand(chat, form); wait(r, n, 120_000)
            }
            val x = allText(m)
            record("dokument-vyzva", "dokument", "model ve VEGA", okc(x) && x.contains(".pdf") && x.contains(".docx"), !x.contains("CHYBA"), 1, t2, "formulář = 1 zásah; " + (Regex("PDF jsem zpětně přečetl: \\d/\\d")).find(x)?.value.orEmpty(), x)
        }
        // ---- 8. návrat k projektu (po „restartu“ = nová instance nad stejným úložištěm)
        if (want("navrat-k-projektu")) run {
            val q = "pokračuj tam, kde jsme skončili"
            val (a, t) = timed { raw(q) }; record("navrat-k-projektu", "návrat k projektu", "model sám", a.contains("Sleva"), !a.startsWith("CHYBA"), 0, t, "model nemá stav mezi sezeními", a)
            val r2 = Rec(); val o2 = ops(r2); o2.settings.activeProject = null
            val (m, t2) = timed { say(o2, r2, o2.newChat(), q) }; val x = allText(m)
            val st = runCatching { o2.projectState(o2.workspace(o2.settings.activeProject!!)) }.getOrNull()
            val rev = st?.optJSONObject("lastVerified")?.optString("revision").orEmpty()
            record("navrat-k-projektu", "návrat k projektu", "model ve VEGA", x.contains("Sleva") && x.contains("Sleva se počítá") && rev.isNotBlank() && x.contains(rev), !x.contains("CHYBA"), 0, t2, "revize $rev", x)
        }

        val out = JSONObject().put("server", url).put("prostredi", "JVM + Robolectric v kontejneru (ne telefon, ne emulátor); model na CPU kontejneru")
            .put("komercni_pozadavky", o.settings.commercialRequests() - c0).put("vlastni_pozadavky", o.settings.requests("own")).put("vysledky", rows)
            .put("treti_varianta", "komerční asistent neměřen – bez klíče a bez schváleného rozpočtu (výchozí útrata 0 Kč)")
        File(if (only != null) "build/vega-eval-part.json" else "build/vega-eval.json").writeText(out.toString(2))
        println("EVAL komerční požadavky: ${o.settings.commercialRequests() - c0}")
    }
}
