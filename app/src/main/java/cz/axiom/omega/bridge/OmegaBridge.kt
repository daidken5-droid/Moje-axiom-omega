package cz.axiom.omega.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import cz.axiom.omega.BuildConfig
import cz.axiom.omega.Events
import cz.axiom.omega.MainActivity
import cz.axiom.omega.Omega
import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.hub.HfHub
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.randy.Randy
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.self.Identity
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryImport
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.step0.Step0
import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.data.Vault
import cz.axiom.omega.engine.LocalEngine
import cz.axiom.omega.engine.NativeLlama
import cz.axiom.omega.jobs.OmegaService
import cz.axiom.omega.sys.Introspect
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Most JavaScript → Kotlin (window.Omega). Pravidla:
 *  • API klíče jdou jen dovnitř (saveKey), nikdy ven,
 *  • vše pomalé běží mimo vlákno mostu a výsledek přijde událostí,
 *  • každý vstup se validuje.
 */
class OmegaBridge(private val activity: MainActivity) {

    private val omega = Omega.get(activity)
    private val io = Executors.newSingleThreadExecutor()

    private fun async(block: () -> Unit) = io.execute {
        try { block() } catch (e: Exception) { Events.note(e.message ?: e.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ stav

    @JavascriptInterface
    fun state(): String {
        val s = omega.settings
        val active = omega.brains.active()
        return JSONObject()
            .put("version", Identity.VERSION).put("name", Identity.NAME).put("debug", BuildConfig.DEBUG)
            .put("job", omega.job.toJson())
            .put("settings", s.toJson())
            .put("keys", JSONObject().put("kimi", omega.vault.has(Vault.KIMI)).put("claude", omega.vault.has(Vault.CLAUDE)).put("server", omega.vault.has(Vault.SERVER))
                .put("node", omega.vault.has(Vault.NODE)).put("hf", omega.vault.has(Vault.HF)))
            .put("engines", JSONArray(omega.availableEngines()))
            .put("sense", omega.embedderFile() != null)
            .put("online", Introspect.online(activity))
            .put("native", NativeLlama.available(activity))
            .put("brain", active?.second?.name ?: active?.first?.nameWithoutExtension ?: JSONObject.NULL)
            .put("brainLoaded", LocalEngine.loadedLabel() ?: JSONObject.NULL)
            .put("counts", JSONObject().put("memory", omega.db.memoryCount()).put("docs", omega.db.docCount()).put("threads", omega.db.threadCount()))
            .toString()
    }

    /** Sebemodel pro obrazovku "Já" – co o sobě AXIOM Ω ví. */
    @JavascriptInterface
    fun self(): String {
        val st = omega.selfState()
        return JSONObject()
            .put("name", Identity.NAME).put("codename", Identity.CODENAME).put("version", Identity.VERSION).put("lineage", Identity.LINEAGE)
            .put("principles", JSONArray(Identity.principles))
            .put("describe", st.describe())
            .put("battery", st.batteryPct ?: JSONObject.NULL).put("charging", st.charging ?: JSONObject.NULL)
            .put("ramFreeMb", st.freeRamMb ?: JSONObject.NULL).put("ramTotalGb", "%.1f".format(Introspect.totalRamGb(activity)))
            .put("thermal", st.thermal ?: JSONObject.NULL).put("online", st.online).put("device", Introspect.device())
            .put("confidence", st.selfConfidenceAvg ?: JSONObject.NULL)
            .put("memory", st.memoryCount).put("docs", st.knowledgeDocs).put("threads", st.conversations)
            .put("levels", JSONArray().apply { RandyLevel.entries.forEach { put(JSONObject().put("code", it.code).put("title", it.title).put("label", it.label).put("passes", it.passes.size).put("effort", it.effort)) } })
            .toString()
    }

    // ------------------------------------------------------------------ vlákna

    @JavascriptInterface fun threads(): String = omega.db.threads().toString()

    @JavascriptInterface fun messages(threadId: Long): String = omega.db.messages(threadId).toString()

    @JavascriptInterface fun newThread(): Long = omega.db.newThread("Nové vlákno")

    @JavascriptInterface fun renameThread(id: Long, title: String) = omega.db.renameThread(id, title)

    @JavascriptInterface fun deleteThread(id: Long) = omega.db.deleteThread(id)

    /** Odeslání dotazu: {threadId?, text, mode?, level?} → vrací id vlákna. */
    @JavascriptInterface
    fun send(json: String): Long = try {
        sendInternal(json)
    } catch (e: Exception) {
        Events.note(e.message ?: "Dotaz se nepodařilo odeslat.")
        -1L
    }

    private fun sendInternal(json: String): Long {
        val o = JSONObject(json)
        val text = o.getString("text").trim()
        require(text.isNotEmpty()) { "Prázdný dotaz." }
        require(text.length <= 32_000) { "Dotaz je příliš dlouhý (max. 32 000 znaků)." }
        if (OmegaService.RUNNING.get()) { Events.note("Právě přemýšlím – počkej nebo mě zastav."); return o.optLong("threadId", 0) }
        var threadId = o.optLong("threadId", 0)
        if (threadId <= 0) threadId = omega.db.newThread(TextUtil.clip(Randy.detect(text).cleanedPrompt.ifBlank { text }, 48))
        else omega.db.threads().let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (t.getLong("id") == threadId && t.getString("title") == "Nové vlákno") omega.db.renameThread(threadId, TextUtil.clip(text, 48))
            }
        }
        omega.db.addMessage(threadId, "user", text)
        val mode = o.optString("mode", "").takeIf { it in cz.axiom.omega.data.Settings.MODES } ?: omega.settings.mode
        OmegaService.start(
            activity,
            Intent(OmegaService.ACTION_ASK).putExtra("threadId", threadId).putExtra("text", text).putExtra("mode", mode)
                .putExtra("kind", o.optString("kind", "").takeIf { it in setOf("agent", "chat", "council") })
                .putExtra("level", o.optString("level", "").takeIf { RandyLevel.fromCode(it) != null }),
        )
        return threadId
    }

    @JavascriptInterface fun cancel() = OmegaService.cancel(activity)

    /** Náhled RANDY + STEP-0 ještě před odesláním (živě při psaní). */
    @JavascriptInterface
    fun preview(text: String): String {
        val d = Randy.detect(text)
        val facts = Step0.analyze(d.cleanedPrompt, omega.settings.rates).take(6)
        return JSONObject().put("level", d.level.code).put("title", d.level.title).put("label", d.level.label).put("explicit", d.explicit)
            .put("passes", d.level.passes.size).put("facts", JSONArray(facts.map { it.toString() })).toString()
    }

    /** Uživatel potvrdil/odmítl odpověď (DNA "Potvrdit ověření") → posílí/oslabí poučení v paměti. */
    @JavascriptInterface
    fun rate(messageId: Long, good: Boolean, lesson: String?) {
        if (!lesson.isNullOrBlank()) omega.db.boostMemory(lesson, if (good) 0.4 else -0.5)
        Events.note(if (good) "Díky – posiluji, co fungovalo." else "Rozumím – oslabuji toto poučení a příště budu opatrnější.")
    }

    // ------------------------------------------------------------------ nastavení a klíče

    @JavascriptInterface
    fun configure(json: String) {
        try {
            omega.settings.apply(JSONObject(json))
            Events.emit("config", JSONObject(state()))
        } catch (e: Exception) {
            Events.note(e.message ?: "Neplatné nastavení.")
        }
    }

    @JavascriptInterface
    fun saveKey(provider: String, key: String) = try {
        saveKeyInternal(provider, key)
    } catch (e: Exception) {
        Events.note(e.message ?: "Klíč se nepodařilo uložit.")
    }

    private fun saveKeyInternal(provider: String, key: String) {
        require(provider in setOf(Vault.KIMI, Vault.CLAUDE, Vault.SERVER, Vault.NODE, Vault.HF)) { "Neznámý poskytovatel." }
        val k = key.trim()
        if (k.isNotEmpty()) require(k.length in 8..400 && k.none { it.isWhitespace() }) { "Klíč má neplatný formát." }
        omega.vault.save(provider, k)
        Events.note(if (k.isEmpty()) "Klíč odstraněn." else "Klíč uložen šifrovaně (Android Keystore).")
        Events.emit("config", JSONObject(state()))
    }

    @JavascriptInterface
    fun forgetKeys() {
        listOf(Vault.KIMI, Vault.CLAUDE, Vault.SERVER, Vault.NODE, Vault.HF).forEach(omega.vault::remove)
        Events.note("Všechny klíče odstraněny.")
        Events.emit("config", JSONObject(state()))
    }

    /** Ověří spojení krátkým dotazem a načte modely. */
    @JavascriptInterface
    fun testProvider(provider: String) = async {
        val models: List<String> = when (provider) {
            "kimi" -> KimiEngine.listModels(omega.settings.kimiBase, omega.vault.read(Vault.KIMI) ?: error("Nejdřív ulož klíč Kimi."))
            "server" -> { omega.server(); KimiEngine.listModels(omega.settings.serverUrl, omega.vault.read(Vault.SERVER)) }
            "node" -> { omega.node(); KimiEngine.listModels(omega.settings.nodeUrl, omega.vault.read(Vault.NODE)) }
            "claude" -> ClaudeEngine.listModels(omega.vault.read(Vault.CLAUDE) ?: error("Nejdřív ulož klíč Claude."))
            else -> error("Neznámý poskytovatel.")
        }
        Events.emit("models", JSONObject().put("provider", provider).put("models", JSONArray(models)))
        Events.note("Spojení funguje · ${models.size} modelů.")
    }

    // ------------------------------------------------------------------ aktivní agenti

    @JavascriptInterface fun tasks(): String = omega.db.tasks().toString()

    @JavascriptInterface fun deleteTask(id: Long) { omega.db.deleteTask(id); Events.emit("tasks", JSONObject()) }

    // ------------------------------------------------------------------ Hugging Face

    @JavascriptInterface
    fun hfSearch(q: String) = async {
        val list = HfHub().searchModels(q.ifBlank { "gguf" }, 20)
        Events.emit("hf_models", JSONObject().put("q", q).put("models", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("downloads", it.downloads).put("likes", it.likes).put("updated", it.updated).put("gated", it.gated)) }
        }))
    }

    @JavascriptInterface
    fun hfFiles(repo: String) = async {
        val ram = Introspect.totalRamGb(activity)
        val files = HfHub().ggufFiles(repo)
        Events.emit("hf_files", JSONObject().put("repo", repo).put("files", JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().put("path", f.path).put("quant", f.quant).put("size", f.size).put("sha256", f.sha256)
                    .put("sizeLabel", "%.2f GB".format(f.size / 1e9)).put("fits", f.size * 1.35 / 1e9 <= ram + 0.3))
            }
        }))
    }

    @JavascriptInterface
    fun hfDownload(repo: String, path: String, kind: String) = async {
        if (OmegaService.RUNNING.get()) error("Počkej na dokončení běžící úlohy.")
        val hub = HfHub()
        val f = hub.ggufFiles(repo).firstOrNull { it.path == path } ?: error("Soubor nenalezen.")
        val b = hub.toBrain(f)
        val spec = JSONObject().put("id", b.id).put("name", b.name).put("family", b.family).put("description", b.description)
            .put("url", b.urls.first()).put("size", b.sizeBytes).put("sha256", b.sha256).put("license", b.license)
            .put("kind", if (kind == "embedding") "embedding" else "chat")
        OmegaService.start(activity, Intent(OmegaService.ACTION_DOWNLOAD).putExtra("spec", spec.toString()))
    }

    /** Libovolný odkaz na .gguf (bez katalogu). */
    @JavascriptInterface
    fun brainFromUrl(url: String) = async {
        if (OmegaService.RUNNING.get()) error("Počkej na dokončení běžící úlohy.")
        val u = url.trim()
        require(u.startsWith("https://") && u.contains(".gguf")) { "Vlož https:// odkaz na soubor .gguf" }
        OmegaService.start(activity, Intent(OmegaService.ACTION_DOWNLOAD).putExtra("spec", JSONObject().put("url", u).toString()))
    }

    @JavascriptInterface
    fun datasetImport(dataset: String, config: String, split: String, count: Int, query: String) {
        if (OmegaService.RUNNING.get()) { Events.note("Počkej na dokončení běžící úlohy."); return }
        if (!dataset.matches(Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?"))) { Events.note("Neplatný název datasetu."); return }
        OmegaService.start(
            activity,
            Intent(OmegaService.ACTION_DATASET).putExtra("dataset", dataset).putExtra("config", config).putExtra("split", split.ifBlank { "train" })
                .putExtra("count", count).putExtra("query", query.ifBlank { null }),
        )
    }

    @JavascriptInterface
    fun wikiImport(topic: String, lang: String, count: Int) {
        if (OmegaService.RUNNING.get()) { Events.note("Počkej na dokončení běžící úlohy."); return }
        if (topic.isBlank()) { Events.note("Zadej téma."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_WIKI).putExtra("topic", topic.trim()).putExtra("lang", lang).putExtra("count", count))
    }

    @JavascriptInterface
    fun indexStart() {
        if (OmegaService.RUNNING.get()) { Events.note("Počkej na dokončení běžící úlohy."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_INDEX))
    }

    // ------------------------------------------------------------------ cloud (Hugging Face dataset)

    @JavascriptInterface
    fun cloudBackup() = async {
        val token = omega.vault.read(Vault.HF) ?: error("Nejdřív ulož Hugging Face token (právo write).")
        val cb = cz.axiom.omega.data.CloudBackup(activity, token)
        val repo = cb.ensureRepo(omega.settings.hfRepo)
        omega.settings.hfRepo = repo
        val size = cb.upload(repo, omega.db)
        Events.emit("config", JSONObject(state()))
        Events.note("Záloha nahrána do soukromého datasetu $repo (${size / 1024} kB).")
    }

    @JavascriptInterface
    fun cloudRestore() = async {
        if (OmegaService.RUNNING.get()) error("Počkej na dokončení běžící úlohy.")
        val token = omega.vault.read(Vault.HF) ?: error("Nejdřív ulož Hugging Face token.")
        val repo = omega.settings.hfRepo.ifBlank { error("Není nastavený dataset se zálohou.") }
        val f = cz.axiom.omega.data.CloudBackup(activity, token).download(repo)
        omega.packs.restoreFile(f)
        Events.emit("kb", JSONObject()); Events.emit("mem", JSONObject()); Events.emit("config", JSONObject(state()))
        Events.note("Databáze obnovena z cloudu ($repo).")
    }

    // ------------------------------------------------------------------ mozky

    @JavascriptInterface fun brains(): String = omega.brains.toJson().toString()

    @JavascriptInterface
    fun brainDownload(id: String) {
        if (OmegaService.RUNNING.get()) { Events.note("Počkej na dokončení běžící úlohy."); return }
        if (!Introspect.unmetered(activity)) Events.note("Pozor: stahuješ přes mobilní data.")
        OmegaService.start(activity, Intent(OmegaService.ACTION_DOWNLOAD).putExtra("id", id))
    }

    @JavascriptInterface fun brainActivate(id: String) { omega.brains.activate(id); LocalEngine.release(); Events.emit("brains", omega.brains.toJson()) }

    @JavascriptInterface fun brainDelete(id: String) { omega.brains.delete(id); Events.emit("brains", omega.brains.toJson()) }

    @JavascriptInterface fun brainImport() = activity.runOnUiThread { activity.pickBrain.launch(arrayOf("application/octet-stream", "*/*")) }

    @JavascriptInterface
    fun brainRefresh() = async {
        val n = omega.brains.refreshCatalog()
        Events.emit("brains", omega.brains.toJson())
        Events.note("Katalog mozků aktualizován ($n mozků).")
    }

    fun onBrainFile(uri: Uri) {
        OmegaService.start(activity, Intent(OmegaService.ACTION_IMPORT).putExtra("uri", uri))
    }

    // ------------------------------------------------------------------ znalosti a paměť

    @JavascriptInterface fun kbList(): String = omega.db.documents().toString()

    @JavascriptInterface fun kbSearch(q: String): String = omega.db.searchJson(q, 20).toString()

    @JavascriptInterface
    fun kbAdd(title: String, text: String) = async {
        omega.db.addDocument(title.take(200), text, "Vlastní poznámka")
        Events.emit("kb", JSONObject()); Events.note("Uloženo a zaindexováno.")
    }

    @JavascriptInterface fun kbDelete(id: Long) { omega.db.deleteDocument(id); Events.emit("kb", JSONObject()) }

    @JavascriptInterface fun kbImport() = activity.runOnUiThread { activity.pickText.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "text/csv", "application/json", "application/x-ndjson", "application/zip")) }

    fun onTextFiles(uris: List<Uri>) = async {
        var ok = 0
        var fail = 0
        for (u in uris.take(100)) {
            runCatching {
                val name = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Dokument"
                val bytes = activity.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                require(bytes.size <= 200_000_000) { "příliš velký" }
                ok += if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    var n = 0
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            if (e.isDirectory || !e.name.substringAfterLast('.').lowercase().let { it in setOf("md", "txt", "csv", "json", "jsonl") }) continue
                            val data = zip.readBytes()
                            if (data.size > 50_000_000) continue
                            n += importText(e.name.substringAfterLast('/'), String(data, Charsets.UTF_8))
                        }
                    }
                    n
                } else {
                    importText(name, String(bytes, Charsets.UTF_8))
                }
            }.onFailure { fail++ }
        }
        Events.emit("kb", JSONObject())
        Events.note("Do offline databáze přidáno $ok dokumentů" + if (fail > 0) ", $fail souborů se nepodařilo načíst." else ".")
        if (omega.settings.semantic && omega.embedderFile() != null && !OmegaService.RUNNING.get()) OmegaService.start(activity, Intent(OmegaService.ACTION_INDEX))
    }

    /** Text → dokumenty: JSONL (dataset) po řádcích, Markdown po kapitolách „## “, jinak jeden dokument. */
    private fun importText(name: String, text: String): Int {
        require(text.indexOf('\u0000') < 0) { "není text" }
        val db = omega.db
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "jsonl" || (ext == "json" && text.trimStart().startsWith("{") && text.lines().count { it.trimStart().startsWith("{") } > 1)) {
            var n = 0
            for (line in text.lineSequence()) {
                val l = line.trim()
                if (!l.startsWith("{")) continue
                val row = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(l) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
                HfHub.rowToDoc(row, name)?.let { db.addDocument(it.first.take(200), it.second, "Import: $name"); n++ }
                if (n >= 20_000) break
            }
            return n
        }
        if (ext == "md" && text.contains("\n## ")) {
            val sections = cz.axiom.omega.core.packs.Packs.sections(text)
            sections.forEach { db.addDocument(it.title.take(200), it.body, "Balíček: $name") }
            if (sections.isNotEmpty()) return sections.size
        }
        require(text.length <= 10_000_000) { "příliš velký" }
        db.addDocument(name, text, "Import z telefonu")
        return 1
    }

    @JavascriptInterface
    fun memList(): String = JSONArray().apply {
        omega.db.all().forEach { put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("label", it.kind.label).put("text", it.text).put("weight", it.weight).put("uses", it.uses).put("created", it.createdAt)) }
    }.toString()

    @JavascriptInterface
    fun memAdd(kind: String, text: String) {
        val k = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.FACT)
        if (text.isBlank()) return
        omega.db.remember(MemoryItem(kind = k, text = text.trim().take(600), weight = 1.3))
        Events.emit("mem", JSONObject())
    }

    // ------------------------------------------------------------------ import paměti z Gemini

    /** Dotaz, který si uživatel zkopíruje do Gemini (odpověď pak vloží zpět). */
    @JavascriptInterface fun memImportPrompt(): String = MemoryImport.PROMPT

    @JavascriptInterface
    fun memImportCopyPrompt() = copy(MemoryImport.PROMPT)

    @JavascriptInterface
    fun memImportText(text: String) = async {
        require(text.length <= 2_000_000) { "Text je příliš dlouhý." }
        applyImport(MemoryImport.parseAny(text))
    }

    @JavascriptInterface
    fun memImportFile() = activity.runOnUiThread {
        activity.pickMemory.launch(arrayOf("application/json", "text/html", "text/plain", "application/zip", "*/*"))
    }

    fun onMemoryFile(uri: Uri) = async {
        val name = uri.lastPathSegment.orEmpty()
        val bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        require(bytes.size <= 200_000_000) { "Soubor je příliš velký." }
        val result = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            // Google Takeout ZIP → najdi aktivitu Aplikace Gemini
            var found: Pair<String, String>? = null
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val n = e.name
                    if (!e.isDirectory && (n.contains("Gemini", true) || n.contains("Bard", true)) &&
                        (n.endsWith("MyActivity.json", true) || n.endsWith("MyActivity.html", true) || n.endsWith(".json", true))
                    ) {
                        val data = zip.readBytes()
                        require(data.size <= 200_000_000)
                        found = n to String(data, Charsets.UTF_8)
                        if (n.endsWith(".json", true)) break
                    }
                }
            }
            val (n, content) = found ?: error("V ZIPu není aktivita „Aplikace Gemini“ (Moje aktivita → Gemini Apps).")
            MemoryImport.parseAny(content, n)
        } else {
            MemoryImport.parseAny(String(bytes, Charsets.UTF_8), name)
        }
        applyImport(result)
    }

    private fun applyImport(r: MemoryImport.Result) {
        r.items.forEach { omega.db.remember(it) }
        r.transcript?.let { omega.db.addDocument("Gemini – historie dotazů", it, "Import z Gemini") }
        Events.emit("mem", JSONObject()); Events.emit("kb", JSONObject())
        Events.note(
            "Z Gemini importováno ${r.items.size} vzpomínek" +
                (if (r.prompts > 0) " a ${r.prompts} dotazů do znalostí" else "") +
                (if (r.skipped > 0) " (přeskočeno ${r.skipped})." else "."),
        )
    }

    @JavascriptInterface fun memDelete(id: Long) { omega.db.deleteMemory(id); Events.emit("mem", JSONObject()) }

    @JavascriptInterface fun memClear() { omega.db.clearMemory(); Events.emit("mem", JSONObject()); Events.note("Paměť vymazána.") }

    // ------------------------------------------------------------------ offline databáze

    @JavascriptInterface fun packs(): String = omega.packs.toJson().toString()

    @JavascriptInterface
    fun packInstall(id: String) = async {
        omega.packs.install(id)
        Events.emit("kb", JSONObject()); Events.note("Offline balíček nainstalován do lokální databáze.")
    }

    @JavascriptInterface
    fun packRemove(id: String) = async {
        omega.packs.remove(id)
        Events.emit("kb", JSONObject()); Events.note("Balíček odebrán.")
    }

    @JavascriptInterface
    fun packRefresh() = async {
        val n = omega.packs.refreshCatalog()
        Events.emit("kb", JSONObject()); Events.note("Katalog offline databází aktualizován ($n balíčků).")
    }

    @JavascriptInterface
    fun dbExport() = activity.runOnUiThread {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        activity.exportDb.launch("axiom-omega-zaloha-$date.db")
    }

    @JavascriptInterface fun dbImport() = activity.runOnUiThread { activity.importDb.launch(arrayOf("*/*")) }

    fun onExport(uri: Uri) = async {
        omega.packs.export(uri)
        Events.note("Záloha uložena (vlákna, paměť, znalosti). API klíče se nezálohují.")
    }

    fun onRestore(uri: Uri) = async {
        if (OmegaService.RUNNING.get()) error("Počkej na dokončení běžící úlohy.")
        omega.packs.restore(uri)
        Events.emit("kb", JSONObject()); Events.emit("mem", JSONObject()); Events.emit("config", JSONObject(state()))
        Events.note("Databáze obnovena ze zálohy.")
    }

    // ------------------------------------------------------------------ systémové

    @JavascriptInterface fun open(url: String) = activity.openExternal(url)

    @JavascriptInterface
    fun share(text: String) = activity.runOnUiThread {
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Sdílet odpověď"))
    }

    @JavascriptInterface
    fun copy(text: String) = activity.runOnUiThread {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AXIOM Ω", text))
        Events.note("Zkopírováno.")
    }
}
