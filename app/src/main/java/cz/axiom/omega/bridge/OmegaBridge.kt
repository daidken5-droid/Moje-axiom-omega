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
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.randy.Randy
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.self.Identity
import cz.axiom.omega.core.self.MemoryItem
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
            .put("keys", JSONObject().put("kimi", omega.vault.has(Vault.KIMI)).put("claude", omega.vault.has(Vault.CLAUDE)).put("server", omega.vault.has(Vault.SERVER)))
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
        require(provider in setOf(Vault.KIMI, Vault.CLAUDE, Vault.SERVER)) { "Neznámý poskytovatel." }
        val k = key.trim()
        if (k.isNotEmpty()) require(k.length in 8..400 && k.none { it.isWhitespace() }) { "Klíč má neplatný formát." }
        omega.vault.save(provider, k)
        Events.note(if (k.isEmpty()) "Klíč odstraněn." else "Klíč uložen šifrovaně (Android Keystore).")
        Events.emit("config", JSONObject(state()))
    }

    @JavascriptInterface
    fun forgetKeys() {
        listOf(Vault.KIMI, Vault.CLAUDE, Vault.SERVER).forEach(omega.vault::remove)
        Events.note("Všechny klíče odstraněny.")
        Events.emit("config", JSONObject(state()))
    }

    /** Ověří spojení krátkým dotazem a načte modely. */
    @JavascriptInterface
    fun testProvider(provider: String) = async {
        val models: List<String> = when (provider) {
            "kimi" -> KimiEngine.listModels(omega.settings.kimiBase, omega.vault.read(Vault.KIMI) ?: error("Nejdřív ulož klíč Kimi."))
            "server" -> { omega.server(); KimiEngine.listModels(omega.settings.serverUrl, omega.vault.read(Vault.SERVER)) }
            "claude" -> ClaudeEngine.listModels(omega.vault.read(Vault.CLAUDE) ?: error("Nejdřív ulož klíč Claude."))
            else -> error("Neznámý poskytovatel.")
        }
        Events.emit("models", JSONObject().put("provider", provider).put("models", JSONArray(models)))
        Events.note("Spojení funguje · ${models.size} modelů.")
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

    @JavascriptInterface fun kbImport() = activity.runOnUiThread { activity.pickText.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "text/csv", "application/json")) }

    fun onTextFiles(uris: List<Uri>) = async {
        var ok = 0
        var fail = 0
        for (u in uris.take(100)) {
            runCatching {
                val bytes = activity.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                require(bytes.size <= 10_000_000) { "příliš velký" }
                val text = String(bytes, Charsets.UTF_8)
                require(text.indexOf('\u0000') < 0) { "není text" }
                omega.db.addDocument(u.lastPathSegment?.substringAfterLast('/') ?: "Dokument", text, "Import z telefonu")
            }.onSuccess { ok++ }.onFailure { fail++ }
        }
        Events.emit("kb", JSONObject())
        Events.note("Importováno $ok dokumentů" + if (fail > 0) ", $fail se nepodařilo (jen UTF-8 text do 10 MB)." else ".")
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
