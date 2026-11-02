package cz.axiom.omega.bridge

import android.os.Build
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
import cz.axiom.omega.core.randy.Supervisor as Sup
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
            .put("version", Identity.VERSION).put("name", Identity.NAME).put("debug", BuildConfig.DEBUG).put("build", BuildConfig.VERSION_NAME)
            .put("job", omega.job.toJson()).put("bg", omega.bg.toJson())
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

    /** Sebemodel pro obrazovku "Já" – co o sobě AVIOR ví. */
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
        if (OmegaService.RUNNING_ASK.get()) { Events.note("Právě přemýšlím – počkej nebo mě zastav."); return -1L }
        var threadId = o.optLong("threadId", 0)
        if (threadId <= 0) threadId = omega.db.newThread(TextUtil.clip(Randy.detect(text).cleanedPrompt.ifBlank { text }, 48))
        else omega.db.threads().let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (t.getLong("id") == threadId && t.getString("title") == "Nové vlákno") omega.db.renameThread(threadId, TextUtil.clip(text, 48))
            }
        }
        omega.db.addMessage(threadId, "user", text)
        // OVLÁDÁNÍ TELEFONU: povel z vlastních slov uživatele (nikdy z webu/souborů/modelu) – bez mozku, hned
        if (omega.settings.phoneControl) cz.axiom.omega.core.phone.PhoneCommands.parse(text)?.let { cmd ->
            if (cmd.confirm) {
                Events.emit("phoneConfirm", JSONObject().put("threadId", threadId).put("label", cmd.label).put("root", cmd.root)
                    .put("cmd", JSONObject().put("action", cmd.action.name).put("args", JSONObject(cmd.args as Map<*, *>)).put("label", cmd.label).put("root", cmd.root)))
                return threadId
            }
            val r = cz.axiom.omega.phone.PhoneControl(activity).execute(cmd)
            if (r.handled) { phoneReply(threadId, cmd, r.text); return threadId }
        }
        val mode = o.optString("mode", "").takeIf { it in cz.axiom.omega.data.Settings.MODES } ?: omega.settings.mode
        OmegaService.start(
            activity,
            Intent(OmegaService.ACTION_ASK).putExtra("threadId", threadId).putExtra("text", text).putExtra("mode", mode)
                .putExtra("kind", o.optString("kind", "").takeIf { it in setOf("agent", "chat", "council") })
                .putExtra("role", o.optString("role", "").takeIf { cz.axiom.omega.core.agent.AgentRole.of(it) != null })
                .putExtra("variant", o.optString("variant", "").takeIf { it.isNotBlank() })
                .putExtra("level", o.optString("level", "").takeIf { RandyLevel.fromCode(it) != null }),
        )
        return threadId
    }

    private fun phoneReply(threadId: Long, cmd: cz.axiom.omega.core.phone.PhoneCommands.Command, text: String) {
        val meta = JSONObject().put("engine", "Ovládání telefonu").put("phone", cmd.action.name).put("root", cmd.root)
            .put("private", true).put("millis", 0)
        val id = omega.db.addMessage(threadId, "assistant", text, meta)
        Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", text).put("meta", meta))
    }

    /** Potvrzený povel (root / nevratná akce) – provede se až po klepnutí na „Provést“. */
    @JavascriptInterface
    fun phoneRun(threadId: Long, json: String, approved: Boolean) = async {
        val o = JSONObject(json)
        val action = cz.axiom.omega.core.phone.PhoneCommands.Action.valueOf(o.getString("action"))
        val args = o.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } } ?: emptyMap()
        val cmd = cz.axiom.omega.core.phone.PhoneCommands.Command(action, args, o.optString("label"), confirm = true, root = o.optBoolean("root"))
        if (!approved) { phoneReply(threadId, cmd, "Zrušeno – nic jsem neprovedl."); return@async }
        Events.emit("phase", JSONObject().put("text", cmd.label))
        phoneReply(threadId, cmd, cz.axiom.omega.phone.PhoneControl(activity).execute(cmd).text)
    }

    /** Stav oprávnění ovládání telefonu pro kartu v Nastavení. */
    @JavascriptInterface fun phoneStatus(): String {
        val nm = activity.getSystemService(android.app.NotificationManager::class.java)
        return JSONObject().put("root", cz.axiom.omega.phone.RootShell.present())
            .put("dnd", nm.isNotificationPolicyAccessGranted).put("write", android.provider.Settings.System.canWrite(activity)).toString()
    }

    @JavascriptInterface fun phoneGrant(what: String) = activity.runOnUiThread {
        val i = when (what) {
            "dnd" -> Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "write" -> Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + activity.packageName))
            else -> return@runOnUiThread
        }
        runCatching { activity.startActivity(i) }
    }

    @JavascriptInterface fun cancel() = OmegaService.cancel(activity)

    /** Pozastaví stahování / úlohu na pozadí (myšlení běží dál). */
    @JavascriptInterface fun cancelBg() = OmegaService.cancelBg(activity)

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
            val o = JSONObject(json)
            omega.settings.apply(o)
            if (o.has("power")) cz.axiom.omega.widget.AviorWidget.refresh(activity)
            if (o.has("keepBrain")) OmegaService.keep(activity, omega.settings.keepBrain && omega.localAvailable())
            if (o.has("airgap")) {
                cz.axiom.omega.core.llm.Http.airgap = omega.settings.airgap
                if (omega.settings.airgap) { OmegaService.cancelBg(activity); Events.note("Air-gap zapnut: AVIOR nepošle po síti ani bajt. Myslí jen telefon.") }
                else Events.note("Air-gap vypnut – síť je zase povolená.")
            }
            if (o.has("secureScreen")) {
                activity.runOnUiThread { activity.applySecureScreen() }
                if (omega.settings.secureScreen) cz.axiom.omega.widget.AviorWidget.clear(activity)
            }
            if (o.has("memSafe")) {
                // změna způsobu načtení → mozek načíst znovu (až nic nepočítá)
                if (!OmegaService.RUNNING_ASK.get()) cz.axiom.omega.engine.LocalEngine.releaseIfIdle()
                omega.warmup()
            }
            Events.emit("config", JSONObject(state()))
            Events.emit("brain", omega.brainState())
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

    /**
     * NOUZOVÉ SMAZÁNÍ: konverzace, paměť, vlastní dokumenty, klíče, statistiky učení a vytvořené soubory.
     * Mozky a offline knihovna zůstanou (nejsou osobní data). Databáze se po smazání fyzicky přepíše (VACUUM).
     */
    @JavascriptInterface
    fun panicWipe() = async {
        OmegaService.cancel(activity)
        listOf(Vault.KIMI, Vault.CLAUDE, Vault.SERVER, Vault.NODE, Vault.HF).forEach(omega.vault::remove)
        omega.db.wipeAll()
        omega.cognition.reset(); omega.randy.reset()
        runCatching { java.io.File(activity.filesDir, "crash.txt").delete() }
        runCatching { omega.forge.wipe() }
        runCatching { cz.axiom.omega.widget.AviorWidget.clear(activity) }
        Events.emit("config", JSONObject(state()))
        Events.emit("wiped", JSONObject())
        Events.note("Smazáno: konverzace, paměť, dokumenty, klíče i statistiky. Mozky a knihovna zůstaly.")
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
                    .put("sizeLabel", "%.2f GB".format(f.size / 1e9)).put("fits", f.size * 1.35 / 1e9 <= cz.axiom.omega.core.brain.BrainCatalog.nominalRamGb(ram)))
            }
        }))
    }

    @JavascriptInterface
    fun hfDownload(repo: String, path: String, kind: String) = async {
        if (OmegaService.RUNNING_BG.get()) error("Počkej na dokončení běžící úlohy.")
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
        if (OmegaService.RUNNING_BG.get()) error("Počkej na dokončení běžící úlohy.")
        val u = url.trim()
        require(u.startsWith("https://") && u.contains(".gguf")) { "Vlož https:// odkaz na soubor .gguf" }
        OmegaService.start(activity, Intent(OmegaService.ACTION_DOWNLOAD).putExtra("spec", JSONObject().put("url", u).toString()))
    }

    @JavascriptInterface
    fun datasetImport(dataset: String, config: String, split: String, count: Int, query: String) {
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        if (!dataset.matches(Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?"))) { Events.note("Neplatný název datasetu."); return }
        OmegaService.start(
            activity,
            Intent(OmegaService.ACTION_DATASET).putExtra("dataset", dataset).putExtra("config", config).putExtra("split", split.ifBlank { "train" })
                .putExtra("count", count).putExtra("query", query.ifBlank { null }),
        )
    }

    @JavascriptInterface
    fun wikiImport(topic: String, lang: String, count: Int) {
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        if (topic.isBlank()) { Events.note("Zadej téma."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_WIKI).putExtra("topic", topic.trim()).putExtra("lang", lang).putExtra("count", count))
    }

    @JavascriptInterface
    fun indexStart() {
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_INDEX))
    }

    // ------------------------------------------------------------------ offline knihovna (několik GB)

    @JavascriptInterface fun libraries(): String = cz.axiom.omega.data.LibraryDb.get(activity).status(activity).toString()

    @JavascriptInterface
    fun libraryStart(id: String) {
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        val spec = cz.axiom.omega.core.library.Libraries.of(id) ?: return
        if (activity.filesDir.usableSpace < (spec.approxGb * 1e9).toLong()) Events.note("Pozor: knihovna zabere ~${spec.approxGb} GB a místa je méně – stahování se zastaví při nedostatku.")
        if (!Introspect.unmetered(activity)) Events.note("Doporučuji Wi-Fi – knihovna má několik GB.")
        OmegaService.start(activity, Intent(OmegaService.ACTION_LIBRARY).putExtra("id", id))
    }

    /** Stáhne předpočítaný RAG index (vektory významu) k předpřipravené knihovně. */
    @JavascriptInterface
    fun libraryRag(id: String) {
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_RAG).putExtra("id", id))
    }

    @JavascriptInterface
    fun libraryRagRemove(id: String) = async {
        cz.axiom.omega.data.LibraryDb.get(activity).removeRag(id)
        Events.emit("kb", JSONObject()); Events.note("RAG index odstraněn – knihovna hledá dál podle slov.")
    }

    @JavascriptInterface
    fun libraryRemove(id: String) = async {
        cz.axiom.omega.data.LibraryDb.get(activity).remove(id)
        Events.emit("kb", JSONObject()); Events.note("Knihovna odstraněna.")
    }

    @JavascriptInterface
    fun librarySearch(q: String): String = JSONArray().apply {
        cz.axiom.omega.data.LibraryDb.get(activity).search(q, 10).forEach { put(JSONObject().put("title", it.title).put("text", it.text).put("url", it.source)) }
    }.toString()

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
        if ((OmegaService.RUNNING_ASK.get() || OmegaService.RUNNING_BG.get())) error("Počkej na dokončení běžící úlohy.")
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
        if (OmegaService.RUNNING_BG.get()) { Events.note("Počkej – na pozadí už běží jiné stahování."); return }
        if (!Introspect.unmetered(activity)) Events.note("Pozor: stahuješ přes mobilní data.")
        OmegaService.start(activity, Intent(OmegaService.ACTION_DOWNLOAD).putExtra("id", id))
    }

    // uvolnění mozku čeká na doběhnutí výpočtu → mimo UI vlákno (jinak by aplikace zamrzla)
    @JavascriptInterface fun brainActivate(id: String) = async {
        omega.brains.activate(id); LocalEngine.release(); Events.emit("brains", omega.brains.toJson()); omega.warmup()
    }

    // smazání čeká na doběhnutí výpočtu (uvolnění paměti) → mimo vlákno JS mostu, jinak by UI zamrzlo
    @JavascriptInterface fun brainDelete(id: String) = async { omega.brains.delete(id); Events.emit("brains", omega.brains.toJson()) }

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

    @JavascriptInterface fun kbImport() = activity.runOnUiThread { activity.pickText.launch(arrayOf("*/*")) }

    /** Import souborů do offline znalostí: PDF, Word, Excel, PowerPoint, OpenDocument, EPUB, HTML, obrázky (OCR), zvuk (přepis), ZIP, text. */
    fun onTextFiles(uris: List<Uri>) = async {
        var ok = 0
        var fail = 0
        val notes = mutableListOf<String>()
        for ((i, u) in uris.take(100).withIndex()) {
            runCatching {
                val r = omega.files.read(u) { if (uris.size == 1) Events.note(it) }
                if (uris.size > 1) Events.note("Čtu ${i + 1}/${uris.size}: ${r.name}")
                notes += r.notes
                require(r.text.isNotBlank()) { "bez textu" }
                ok += importText(r.name, r.text)
            }.onFailure { fail++ }
        }
        Events.emit("kb", JSONObject())
        Events.note("Do offline databáze přidáno $ok dokumentů" + (if (fail > 0) ", $fail souborů bez čitelného textu." else ".") +
            (notes.distinct().firstOrNull()?.let { " $it" } ?: ""))
        if (omega.settings.semantic && omega.embedderFile() != null && !OmegaService.RUNNING_BG.get()) OmegaService.start(activity, Intent(OmegaService.ACTION_INDEX))
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
        if ((OmegaService.RUNNING_ASK.get() || OmegaService.RUNNING_BG.get())) error("Počkej na dokončení běžící úlohy.")
        omega.packs.restore(uri)
        Events.emit("kb", JSONObject()); Events.emit("mem", JSONObject()); Events.emit("config", JSONObject(state()))
        Events.note("Databáze obnovena ze zálohy.")
    }

    // ------------------------------------------------------------------ systémové

    @JavascriptInterface fun open(url: String) = activity.openExternal(url)

    // ------------------------------------------------------------------ Ω varianty, agenti, vytvořené soubory

    // ------------------------------------------------------------------ RANDY Supervisor

    @JavascriptInterface
    fun randy(): String = omega.randy.toJson(cz.axiom.omega.core.randy.Supervisor.Authority.of(omega.settings.randyAuthority)).toString()

    @JavascriptInterface fun randyRate(variantId: String, good: Boolean) = omega.randy.rate(variantId, good)

    // ------------------------------------------------------------------ Kognice (učení z každé otázky)

    // ------------------------------------------------------------------ profil zařízení a režim výkonu

    @JavascriptInterface
    fun device(): String {
        val d = omega.device
        return JSONObject().put("name", d.name).put("soc", d.soc ?: JSONObject.NULL).put("ramGb", d.ramGb).put("threads", d.threads)
            .put("known", d.known).put("recommendedBrain", d.recommendedBrain).put("ctx", omega.effectiveCtx()).put("ctxManual", omega.settings.ctxManual)
            .put("notes", JSONArray(d.notes)).put("power", omega.power().name)
            .put("powers", JSONArray().apply { cz.axiom.omega.core.device.Power.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label).put("desc", it.description)) } })
            .toString()
    }

    // ------------------------------------------------------------------ aktualizace

    /** Zkontroluje novou verzi (ručně, nebo automaticky jednou denně při startu). */
    @JavascriptInterface
    fun checkUpdate(manual: Boolean) = async {
        val p = activity.getSharedPreferences("omega", Context.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - p.getLong("updateCheckAt", 0) < 20 * 3_600_000L) return@async
        if (!Introspect.online(activity)) { if (manual) Events.note("Jsem offline – aktualizace zkontroluji, až bude internet."); return@async }
        val l = runCatching { cz.axiom.omega.core.update.Updates.latest() }.getOrNull()
        p.edit().putLong("updateCheckAt", System.currentTimeMillis()).apply()
        val local = BuildConfig.VERSION_NAME
        if (l != null && cz.axiom.omega.core.update.Updates.newer(l.version, local)) {
            Events.emit("update", JSONObject().put("version", l.version).put("apk", l.apkUrl).put("page", l.pageUrl).put("current", local))
        } else if (manual) Events.note(if (l == null) "Nepodařilo se ověřit aktualizace." else "Máš nejnovější verzi ($local).")
    }

    // ------------------------------------------------------------------ vlákna: hledání, export, příloha, čtení nahlas

    @JavascriptInterface fun searchThreads(q: String): String = omega.db.searchThreads(q).toString()

    /** Export konverzace do Stažené/AVIOR jako Markdown (md) nebo PDF. */
    @JavascriptInterface
    fun exportThread(threadId: Long, format: String) = async {
        val title = runCatching {
            val arr = omega.db.threads(); (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getLong("id") == threadId }.getString("title")
        }.getOrDefault("Konverzace")
        val msgs = omega.db.messages(threadId)
        val md = buildString {
            append("# ").append(title).append("\n\n")
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                if (m.optJSONObject("meta")?.optBoolean("error") == true) continue
                append(if (m.getString("role") == "user") "## Ty\n\n" else "## AVIOR\n\n").append(m.getString("content").trim()).append("\n\n")
            }
            append("---\nExportováno z AVIOR ${java.text.SimpleDateFormat("d. M. yyyy HH:mm", java.util.Locale("cs")).format(java.util.Date())}\n")
        }
        val base = cz.axiom.omega.core.forge.FileForge.safeName(
            java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(50).ifBlank { "konverzace" }, "md",
        ).substringBeforeLast('.')
        val msg = if (format == "pdf") omega.forge.save("$base.pdf", "application/pdf", omega.forge.pdf(title, md.replace(Regex("(?m)^#+ "), "")))
        else omega.forge.save("$base.md", "text/markdown", md.toByteArray())
        Events.note(msg)
    }

    @JavascriptInterface fun attach() = activity.runOnUiThread {
        activity.pickAttach.launch(arrayOf("*/*"))
    }

    fun onAttach(uri: android.net.Uri) = async {
        val r = try {
            omega.files.read(uri) { Events.note(it) }
        } catch (e: Exception) { Events.note("Soubor se nepodařilo přečíst: ${e.message}"); return@async }
        if (r.text.isBlank()) { Events.note((r.notes.firstOrNull() ?: "V souboru jsem nenašel žádný text.")); return@async }
        Events.emit("attach", JSONObject().put("name", r.name).put("kind", r.kind.label).put("text", r.text.take(60_000)).put("chars", r.text.length)
            .put("notes", JSONArray(r.notes)))
    }

    private var tts: android.speech.tts.TextToSpeech? = null

    /** Přečte text nahlas českým hlasem Androidu (funguje offline, pokud je stažený český hlas). */
    @JavascriptInterface
    fun speak(text: String) = activity.runOnUiThread {
        val clean = text.replace(Regex("[*#`>|_]"), " ").replace(Regex("\\[(\\d+)]"), "").replace(Regex("\\s+"), " ").trim().take(3_900)
        val t = tts
        if (t != null) { t.stop(); t.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "avior"); return@runOnUiThread }
        tts = android.speech.tts.TextToSpeech(activity) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale("cs", "CZ")
                tts?.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "avior")
            } else Events.note("Čtení nahlas není v telefonu dostupné.")
        }
    }

    @JavascriptInterface fun stopSpeaking() = activity.runOnUiThread { tts?.stop() }

    // ------------------------------------------------------------------ Hugging Face služby

    @JavascriptInterface
    fun hfStatus(): String = JSONObject().put("token", omega.hfToken() != null).put("online", Introspect.online(activity)).toString()

    @JavascriptInterface
    fun hfModels() = async {
        val t = omega.hfToken() ?: return@async Events.note("Nejdřív ulož token Hugging Face.")
        val list = runCatching { cz.axiom.omega.core.hub.HfServices.chatModels(t) }.getOrDefault(emptyList())
        if (list.isEmpty()) Events.note("Seznam modelů se nepodařilo načíst – zkontroluj token.")
        else Events.emit("models", JSONObject().put("provider", "hf").put("models", JSONArray(list.take(300))))
    }

    @JavascriptInterface
    fun hfTest() = async {
        val t = omega.hfToken() ?: return@async Events.note("Nejdřív ulož token Hugging Face.")
        val r = runCatching {
            val e = cz.axiom.omega.core.hub.HfServices.chatEngine(t, omega.settings.hfChatModel)
            e.generate(listOf(cz.axiom.omega.core.llm.ChatMessage(cz.axiom.omega.core.llm.Role.USER, "Odpověz jedním slovem: funguješ?")),
                cz.axiom.omega.core.llm.GenOptions(maxTokens = 20), cz.axiom.omega.core.llm.CancelToken()) {}.text
        }
        Events.note(r.fold({ "Hugging Face funguje (${omega.settings.hfChatModel.substringAfterLast('/')}): ${it.take(60)}" }, { "Hugging Face: ${it.message}" }))
    }

    @JavascriptInterface fun cognition(): String = omega.cognition.toJson().toString()

    @JavascriptInterface
    fun learnRate(question: String, answer: String, good: Boolean, confidence: Int) {
        omega.cognition.rate(question, good, confidence.takeIf { it >= 0 })
        omega.cognition.exemplarRated(question, answer, good)
        // ✓ = ověřená odpověď (příště reflexem bez mozku), ✗ = z paměti odpovědí pryč
        runCatching {
            if (good && !cz.axiom.omega.core.agent.Reflex.timeSensitive(question)) omega.db.putAnswer(question, answer, maxOf(confidence, 80), good = true)
            else if (!good) omega.db.forgetAnswer(question)
        }
    }

    // ------------------------------------------------------------------ Doladění (LoRA + trénovací data)

    @JavascriptInterface
    fun tuning(): String {
        val dir = omega.loraDir()
        val files = dir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
        return JSONObject().put("active", omega.settings.lora).put("scale", omega.settings.loraScale)
            .put("adapters", JSONArray().apply { files.forEach { put(JSONObject().put("name", it.name).put("mb", it.length() / 1_048_576)) } })
            .put("training", omega.cognition.trainingCount())
            .put("base", omega.brains.active()?.second?.name ?: JSONObject.NULL)
            .put("loaded", cz.axiom.omega.engine.LocalEngine.loadedLabel() ?: JSONObject.NULL).toString()
    }

    @JavascriptInterface fun loraImport() = activity.runOnUiThread { activity.pickLora.launch(arrayOf("*/*")) }

    fun onLoraFile(uri: android.net.Uri) = async {
        val display = runCatching {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val name = (display ?: "adapter.gguf")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        val out = java.io.File(omega.loraDir(), name)
        activity.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        if (!cz.axiom.omega.core.brain.Downloader.isGguf(out)) { out.delete(); Events.note("Soubor není GGUF adaptér."); return@async }
        omega.settings.lora = name
        cz.axiom.omega.engine.LocalEngine.release() // nasadí se při dalším dotazu nad aktivním mozkem
        Events.emit("config", JSONObject(state()))
        Events.note("Adaptér $name nahrán a zapnut – projeví se u dalšího dotazu.")
    }

    @JavascriptInterface
    fun loraUse(name: String) {
        omega.settings.lora = name.takeIf { it.isEmpty() || java.io.File(omega.loraDir(), it).exists() } ?: ""
        cz.axiom.omega.engine.LocalEngine.release()
        Events.emit("config", JSONObject(state()))
        Events.note(if (name.isEmpty()) "Doladění vypnuto – základní mozek." else "Doladění $name zapnuto.")
    }

    @JavascriptInterface
    fun loraDelete(name: String) = async {
        java.io.File(omega.loraDir(), name).delete()
        if (omega.settings.lora == name) { omega.settings.lora = ""; cz.axiom.omega.engine.LocalEngine.release() }
        Events.emit("config", JSONObject(state()))
    }

    /** Export trénovacích dat (JSONL): oceněné odpovědi + vestavěná sada dovedností AVIORu. */
    @JavascriptInterface
    fun trainingExport() = async {
        val system = "Jsi AVIOR – česká AI v telefonu. Odpovídáš česky, věcně a přesně, rozlišuješ fakta a odhady, " +
            "čísla bereš ze STEP-0, jako agent používáš protokol MYŠLENKA/AKCE/OBSAH/HOTOVO."
        val lines = omega.cognition.trainingJsonl(system).toMutableList()
        runCatching { activity.assets.open("finetune/skills.jsonl").bufferedReader().readLines().filter { it.isNotBlank() } }.getOrNull()?.let { lines += it }
        if (lines.isEmpty()) { Events.note("Zatím nemám data – hodnoť odpovědi ✓."); return@async }
        val msg = omega.forge.save("avior-trenink.jsonl", "application/jsonl", lines.joinToString("\n").toByteArray())
        Events.note("Trénovací data: ${lines.size} příkladů. $msg Návod: finetune/README.md v repozitáři.")
    }

    @JavascriptInterface
    fun selfStudy() {
        if (OmegaService.RUNNING_ASK.get()) { Events.note("Počkej, až dopřemýšlím."); return }
        OmegaService.start(activity, Intent(OmegaService.ACTION_STUDY))
    }

    @JavascriptInterface fun cognitionReset() { omega.cognition.reset(); Events.note("Paměť učení vymazána – začínám znovu.") }

    /** Uvolní RAM pro AVIOR: Android zavře aplikace na pozadí (viz MemoryReclaimer). */
    @JavascriptInterface fun reclaimMemory() {
        if (OmegaService.RUNNING_ASK.get() || omega.warming) { Events.note("Počkej, až dopíšu odpověď – pak paměť uvolním."); return }
        async {
            Events.emit("phase", JSONObject().put("text", "Uvolňuji paměť…"))
            val r = cz.axiom.omega.sys.MemoryReclaimer.reclaim(activity) { }
            Events.emit("reclaim", JSONObject().put("before", r.beforeMb).put("after", r.afterMb).put("gained", r.gainedMb).put("note", r.note))
            Events.note(r.note)
        }
    }

    /** Vývojářské možnosti → Omezit procesy na pozadí (systémový limit, nejsilnější varianta). */
    @JavascriptInterface fun openDevOptions() = activity.runOnUiThread {
        val ok = runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        Events.note(if (ok) "Najdi „Omezit procesy na pozadí“ a zvol např. „Nejvýše 2 procesy“."
        else "Nejdřív zapni Vývojářské možnosti: Nastavení → O telefonu → 7× klepni na Číslo sestavení.")
    }

    @JavascriptInterface fun memInfo(): String {
        val mi = android.app.ActivityManager.MemoryInfo().also { (activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        return JSONObject().put("free", mi.availMem / 1_048_576).put("total", mi.totalMem / 1_048_576).put("low", mi.lowMemory).toString()
    }

    /** Je AVIOR výchozí digitální asistent (místo Gemini)? */
    @JavascriptInterface fun assistantStatus(): String {
        val held = if (Build.VERSION.SDK_INT >= 29) runCatching {
            activity.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false) else false
        return JSONObject().put("default", held).toString()
    }

    /** Otevře systémovou volbu výchozího asistenta (Android nedovolí roli převzít bez uživatele). */
    @JavascriptInterface fun openAssistantSettings() = activity.runOnUiThread {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in tries) if (runCatching { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            Events.note("Vyber „Digitální asistent“ → AVIOR. Pak dlouhý stisk vypínače otevře AVIOR s hlasem.")
            break
        }
    }

    /** Stav mozku v paměti (loaded / loading / none). */
    @JavascriptInterface fun brainState(): String = omega.brainState().toString()

    /** Ručně načíst mozek do paměti hned teď. */
    @JavascriptInterface fun preload() {
        if (omega.settings.keepBrain) OmegaService.keep(activity, true) else omega.warmup()
    }

    /**
     * Proč se AVIOR minule ukončil (Android 11+: ApplicationExitInfo; plus vlastní záznam pádu).
     * Při pádu kvůli paměti nebo v nativním kódu se zapne paměťově bezpečný režim a UI dostane vysvětlení.
     */
    fun reportLastExit() {
        val s = omega.settings
        var reason: String? = null
        var detail = ""
        var time = 0L
        var memory = false
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = activity.getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(activity.packageName, 0, 1).firstOrNull()
            if (e != null && e.timestamp > s.exitSeen) {
                time = e.timestamp
                when (e.reason) {
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> { reason = "Systém AVIOR ukončil kvůli nedostatku paměti RAM."; memory = true }
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> { reason = "Pád v nativním jádru mozku (llama.cpp)."; memory = true }
                    android.app.ApplicationExitInfo.REASON_CRASH -> reason = "Pád aplikace."
                    android.app.ApplicationExitInfo.REASON_ANR -> reason = "Aplikace přestala reagovat (ANR)."
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> if (e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) { reason = "Systém AVIOR ukončil (signál ${e.status}) – nejspíš kvůli paměti."; memory = true }
                }
                detail = e.description.orEmpty()
            }
        }
        val crash = java.io.File(activity.filesDir, "crash.txt")
        if (crash.exists()) {
            val txt = runCatching { crash.readText() }.getOrDefault("")
            val ts = txt.substringBefore('\n').toLongOrNull() ?: 0L
            if (ts > s.exitSeen) { reason = reason ?: "Pád aplikace."; detail = txt.substringAfter('\n').lineSequence().take(4).joinToString(" · "); time = maxOf(time, ts) }
            crash.delete()
        }
        if (reason == null) return
        s.exitSeen = maxOf(time, System.currentTimeMillis())
        val wasSafe = s.memSafe
        if (memory && !wasSafe) s.memSafe = true
        val big = omega.brains.active()?.second?.id?.contains("e4b") == true && omega.device.ramGb <= 8.5
        Events.emit("crash", JSONObject().put("reason", reason).put("detail", detail.take(600))
            .put("memSafeOn", memory && !wasSafe).put("suggestLite", memory && big))
    }

    /** Automatické samostudium: nabíjí se, Wi-Fi, aspoň 2 otevřené otázky, naposledy před 6 h. */
    fun maybeSelfStudy() {
        val s = omega.settings
        if (!s.selfStudy || OmegaService.RUNNING_ASK.get()) return
        val (_, charging) = Introspect.battery(activity)
        if (charging != true || !Introspect.unmetered(activity)) return
        if (omega.cognition.openQuestions().size < 2) return
        if (System.currentTimeMillis() - omega.cognition.lastStudy() < 6 * 3_600_000L) return
        OmegaService.start(activity, Intent(OmegaService.ACTION_STUDY))
    }

    @JavascriptInterface fun randyReset() { omega.randy.reset(); Events.note("Statistiky RANDY vynulovány.") }

    /** Náhled plánu RANDY během psaní (co by aktivoval). */
    @JavascriptInterface
    fun randyPlan(text: String, variant: String): String {
        val (b, c) = Introspect.battery(activity)
        val p = Sup.plan(Sup.Request(text, cz.axiom.omega.core.agent.Variant.of(variant), toolsOnline = Introspect.online(activity)),
            Sup.Device(b, c, Introspect.thermalLevel(activity), Introspect.freeRamMb(activity), Introspect.online(activity)),
            omega.randy.stats(), Sup.Authority.of(omega.settings.randyAuthority))
        return JSONObject().put("summary", p.summary).put("variant", p.variant.name).put("decisions", JSONArray(p.decisions)).toString()
    }

    @JavascriptInterface
    fun variants(): String = JSONArray().apply {
        cz.axiom.omega.core.agent.Variant.entries.forEach {
            put(JSONObject().put("id", it.name).put("title", it.title).put("icon", it.icon).put("tagline", it.tagline).put("inspired", it.inspiredBy)
                .put("agent", it.mode == cz.axiom.omega.core.agent.Router.Mode.AGENT))
        }
    }.toString()

    @JavascriptInterface
    fun roles(): String = JSONArray().apply {
        cz.axiom.omega.core.agent.AgentRole.entries.forEach { put(JSONObject().put("id", it.name).put("title", it.title).put("icon", it.icon).put("tagline", it.tagline)) }
    }.toString()

    @JavascriptInterface fun createdFiles(): String = omega.forge.list().toString()

    /** Otevře/nainstaluje soubor podle URI (z karty u odpovědi) nebo indexu v seznamu. */
    @JavascriptInterface
    fun openFile(ref: String) = activity.runOnUiThread {
        val e = fileEntry(ref) ?: return@runOnUiThread Events.note("Soubor už neexistuje.")
        if (e.optString("mime") == "application/vnd.android.package-archive" && android.os.Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Events.note("Povol AVIORu instalaci aplikací a pak klepni na Instalovat znovu.")
            runCatching {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + activity.packageName)))
            }
            return@runOnUiThread
        }
        runCatching { activity.startActivity(omega.forge.openIntent(e)) }.onFailure { Events.note("Pro tento typ souboru není v telefonu aplikace.") }
    }

    @JavascriptInterface
    fun shareFile(ref: String) = activity.runOnUiThread {
        val e = fileEntry(ref) ?: return@runOnUiThread
        runCatching { activity.startActivity(omega.forge.shareIntent(e)) }
    }

    private fun fileEntry(ref: String): JSONObject? {
        ref.toIntOrNull()?.let { return omega.forge.entry(it) }
        val arr = omega.forge.list()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("uri") == ref || it.optString("local") == ref) return it }
        return null
    }

    @JavascriptInterface
    fun voice() = activity.runOnUiThread {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Mluv – AVIOR poslouchá")
        runCatching { activity.voice.launch(i) }.onFailure { Events.note("Rozpoznávání řeči není v telefonu dostupné.") }
    }

    @JavascriptInterface
    fun haptic() = activity.runOnUiThread {
        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    @JavascriptInterface
    fun share(text: String) = activity.runOnUiThread {
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Sdílet odpověď"))
    }

    @JavascriptInterface
    fun copy(text: String) = activity.runOnUiThread {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AVIOR", text))
        Events.note("Zkopírováno.")
    }
}
