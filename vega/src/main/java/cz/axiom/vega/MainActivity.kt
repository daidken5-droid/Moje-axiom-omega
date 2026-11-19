package cz.axiom.vega

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jedna obrazovka s WebView (UI v assets/ui). Most [Bridge] vrací jen data – klíče nikdy. Soubory volí uživatel
 * (systémový výběr), NFC se čte jen po klepnutí na „Načíst štítek“ (reader mode), síť pro UI je zakázaná CSP.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    lateinit var ops: Ops
    @Volatile private var nfcChat: String? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) Thread { attachUris(uris) }.start() else send("files", JSONObject().put("files", JSONArray())) }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) Thread { importTree(uri) }.start() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ops = (application as VegaApp).ops(this::send)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                // odkazy ze zdrojů otevřít v prohlížeči, ne ve WebView
                if (u.scheme == "https" || u.scheme == "http") startActivity(Intent(Intent.ACTION_VIEW, u))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "VEGA")
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { send("shared", JSONObject().put("text", it)) }
                @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { u -> Thread { attachUris(listOf(u)) }.start() }
            }
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { l -> Thread { attachUris(l) }.start() }
        }
    }

    fun send(type: String, data: JSONObject) {
        val js = "window.vega && vega.on(${JSONObject.quote(type)}, ${data})"
        runOnUiThread { if (!isFinishing) web.evaluateJavascript(js, null) }
    }

    private fun nameOf(u: Uri) = runCatching { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: u.lastPathSegment ?: "soubor"

    private fun attachUris(uris: List<Uri>) {
        val arr = JSONArray()
        for (u in uris.take(8)) {
            runCatching {
                val bytes = contentResolver.openInputStream(u)!!.use { s -> s.readBytes() }
                require(bytes.size <= 60_000_000) { "Soubor je větší než 60 MB." }
                arr.put(ops.attach(nameOf(u), contentResolver.getType(u), bytes))
            }.onFailure { arr.put(JSONObject().put("error", "${nameOf(u)}: ${it.message}")) }
        }
        send("files", JSONObject().put("files", arr))
    }

    /** Import složky jako projektu (kopie – originál zůstane netknutý). */
    private fun importTree(uri: Uri) {
        try {
            val root = DocumentFile.fromTreeUri(this, uri) ?: throw IllegalStateException("Složku nejde otevřít")
            val files = LinkedHashMap<String, ByteArray>(); var total = 0L
            fun walk(d: DocumentFile, prefix: String) {
                for (f in d.listFiles()) {
                    val n = f.name ?: continue
                    if (n in setOf(".git", "node_modules", "build", ".gradle", "dist", "__pycache__", ".idea")) continue
                    if (f.isDirectory) walk(f, "$prefix$n/")
                    else if (f.length() <= cz.axiom.vega.core.Workspace.MAX_FILE && files.size < 2000) {
                        total += f.length(); if (total > 80_000_000) throw IllegalStateException("Projekt je větší než 80 MB.")
                        files["$prefix$n"] = contentResolver.openInputStream(f.uri)!!.use { it.readBytes() }
                    }
                }
            }
            walk(root, "")
            val id = ops.createProject(root.name ?: "projekt", files)
            send("project", JSONObject().put("id", id).put("imported", files.size))
        } catch (e: Exception) { send("error", JSONObject().put("text", "Import složky: ${e.message}")) }
    }

    // ------------------------------------------------------------------ NFC (reader mode jen na vyžádání)

    fun nfcStart(chat: String?) {
        nfcChat = chat
        val a = NfcAdapter.getDefaultAdapter(this)
        when {
            a == null -> ops.onNfc(null, "Tenhle telefon nemá NFC – nástroj Čtení NDEF umí aspoň rozebrat soubor se záznamem.", chat)
            !a.isEnabled -> { send("nfc.off", JSONObject()); ops.onNfc(null, "NFC je vypnuté. Zapni ho v Nastavení → Připojená zařízení (tlačítko níže) a zkus znovu.", chat) }
            else -> runOnUiThread {
                a.enableReaderMode(this, { tag -> readTag(tag) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
                send("nfc.listening", JSONObject())
            }
        }
    }

    fun nfcStop() { runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } }

    private fun readTag(tag: Tag) {
        val ndef = Ndef.get(tag)
        if (ndef == null) { ops.onNfc(null, "Štítek nemá NDEF záznam (technologie: ${tag.techList.joinToString { it.substringAfterLast('.') }}).", nfcChat); nfcStop(); return }
        try {
            ndef.connect()
            val msg = ndef.ndefMessage ?: ndef.cachedNdefMessage
            if (msg == null) ops.onNfc(null, "Štítek je prázdný (žádná NDEF zpráva).", nfcChat) else ops.onNfc(msg.toByteArray(), null, nfcChat)
        } catch (e: Exception) { ops.onNfc(null, "Čtení přerušeno (${e.message ?: "štítek oddálen"}) – přilož ho znovu a podrž.", nfcChat) }
        finally { runCatching { ndef.close() }; nfcStop() }
    }

    override fun onPause() { super.onPause(); nfcStop() }

    fun openNfcSettings() = startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))
    fun pick() = runOnUiThread { pickFiles.launch(arrayOf("*/*")) }
    fun pickFolder() = runOnUiThread { pickTree.launch(null) }

    fun share(name: String) {
        val f = ops.out.file(name) ?: return
        val mime = when (f.extension.lowercase()) { "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "csv" -> "text/csv"; "zip" -> "application/zip"; else -> "*/*" }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, ops.out.uri(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
    }

    fun open(name: String) {
        val f = ops.out.file(name) ?: return
        val mime = when (f.extension.lowercase()) { "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "csv" -> "text/csv"; else -> "*/*" }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ops.out.uri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { send("error", JSONObject().put("text", "Žádná aplikace neumí otevřít ${f.extension} – použij Sdílet.")) }
    }

}

/** Most JS → Kotlin. Každá metoda vrací JSON (nebo prázdné), dlouhé operace hlásí výsledek událostí. */
class Bridge(private val a: MainActivity) {
    private val ops get() = a.ops
    private fun safe(block: () -> Any?): String = try { (block() ?: "").toString() } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString() }
    private fun params(json: String): Map<String, String> = runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrDefault(emptyMap())

    @JavascriptInterface fun home() = safe { ops.home() }
    @JavascriptInterface fun tools() = safe { ops.toolsJson() }
    @JavascriptInterface fun chats() = safe { ops.chats() }
    @JavascriptInterface fun newChat() = safe { ops.newChat() }
    @JavascriptInterface fun messages(chat: String) = safe { ops.messages(chat) }
    @JavascriptInterface fun send(chat: String, text: String) = safe { if (chat == "temp") ops.chatTemp(text) else if (text.length > 6000) ops.longTask(chat, text) else ops.chat(chat, text) }
    @JavascriptInterface fun clearTemp() = safe { ops.clearTemp() }
    @JavascriptInterface fun stop() = safe { ops.stop() }
    @JavascriptInterface fun answer(req: String, json: String) = safe { ops.answer(req, JSONObject(json)) }
    @JavascriptInterface fun pickFiles() = safe { a.pick() }
    @JavascriptInterface fun pickFolder() = safe { a.pickFolder() }
    @JavascriptInterface fun clearFiles() = safe { ops.clearAttachments() }
    @JavascriptInterface fun runTool(chat: String, tool: String, input: String, p: String) = safe { ops.runTool(chat.takeIf { it != "temp" }, tool, JSONObject(input), params(p)) }
    @JavascriptInterface fun run(id: String) = safe { ops.runJson(id) }
    @JavascriptInterface fun resume(chat: String, id: String, fix: String) = safe {
        val o = runCatching { JSONObject(fix) }.getOrDefault(JSONObject())
        ops.resume(chat.takeIf { it != "temp" }, id, o.keys().asSequence().associate { k -> k.toInt() to params(o.getJSONObject(k).toString()) })
    }
    @JavascriptInterface fun export(run: String, step: Int) = safe { ops.exportValue(run, step) }
    @JavascriptInterface fun procedures() = safe { JSONArray().apply { ops.procedures.list().forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("version", p.version).put("description", p.description).put("inputDesc", p.inputDesc).put("inputKind", p.inputKind).put("outputKind", p.outputKind).put("perms", JSONArray(p.perms)).put("steps", JSONArray(p.steps.map { it.tool })).put("changelog", JSONArray(p.changelog)).put("uses", p.uses)) } } }
    @JavascriptInterface fun saveProcedure(json: String) = safe { ops.saveProcedure(JSONObject(json)) }
    @JavascriptInterface fun deleteProcedure(id: String) = safe { ops.procedures.delete(id) }
    @JavascriptInterface fun runProcedure(chat: String, id: String, input: String) = safe { ops.runProcedure(chat.takeIf { it != "temp" }, id, JSONObject(input)) }
    @JavascriptInterface fun memory() = safe { ops.memoryJson() }
    @JavascriptInterface fun memoryRevert(id: String) = safe { ops.memory.revert(id)?.text }
    @JavascriptInterface fun memoryForget(id: String) = safe { ops.memory.forget(id).size }
    @JavascriptInterface fun memoryAdd(text: String) = safe { ops.memory.remember(text).toString() }
    @JavascriptInterface fun projects() = safe { ops.projects() }
    @JavascriptInterface fun project(id: String) = safe { ops.projectInfo(id) }
    @JavascriptInterface fun newProject(name: String) = safe { ops.createProject(name.ifBlank { "Nový projekt" }, emptyMap()) }
    @JavascriptInterface fun activate(id: String) = safe { ops.settings.activeProject = id.ifBlank { null } }
    @JavascriptInterface fun file(id: String, path: String) = safe { JSONObject().put("text", ops.workspace(id).let { if (cz.axiom.vega.core.Workspace.SECRET.containsMatchIn(path)) "(citlivý soubor – nezobrazuji)" else it.read(path).take(200_000) }).put("hash", ops.workspace(id).hash(path)) }
    @JavascriptInterface fun saveFile(id: String, path: String, text: String, hash: String) = safe { ops.workspace(id).write(path, text, hash.ifBlank { null }, "ruční úprava").id }
    @JavascriptInterface fun undo(id: String, change: Int) = safe { ops.workspace(id).undo(change.takeIf { it > 0 }).path }
    @JavascriptInterface fun diff(id: String) = safe { JSONArray().apply { ops.workspace(id).diffFromBase().forEach { (p, d) -> put(JSONObject().put("path", p).put("added", d.added).put("removed", d.removed).put("diff", cz.axiom.vega.core.Diff.unified(d.lines, 3))) } } }
    @JavascriptInterface fun verify(id: String) = safe { Thread { val v = ops.verify(ops.workspace(id)); a.send("verified", JSONObject().put("project", id).put("exitCode", v.exitCode).put("label", v.label).put("output", v.output.take(6000)).put("command", v.command).put("revision", v.revision)) }.start() }
    @JavascriptInterface fun exportProject(id: String) = safe { val w = ops.workspace(id); ops.out.save("${w.name}.zip", "application/zip", w.exportZip()).name }
    @JavascriptInterface fun nfc(chat: String) = safe { a.nfcStart(chat.takeIf { it != "temp" }) }
    @JavascriptInterface fun nfcSettings() = safe { a.openNfcSettings() }
    @JavascriptInterface fun demand(chat: String, json: String) = safe { ops.demand(chat.takeIf { it != "temp" }, JSONObject(json)) }
    @JavascriptInterface fun share(name: String) = safe { a.share(name) }
    @JavascriptInterface fun openOut(name: String) = safe { a.open(name) }
    @JavascriptInterface fun settings() = safe { ops.settingsJson() }
    @JavascriptInterface fun setSetting(key: String, value: String) = safe {
        val s = ops.settings
        when (key) {
            "budgetKc" -> s.budgetKc = value.toIntOrNull() ?: 0
            "initiative" -> s.initiative = value == "true"
            "airgap" -> s.airgap = value == "true"
            "claudeModel" -> s.claudeModel = value
            "pinned" -> s.pinned = value.split(',')
            else -> if (key.startsWith("allow.")) s.setAllowed(key.removePrefix("allow."), value == "true")
        }
    }
    @JavascriptInterface fun saveKey(provider: String, key: String) = safe { require(provider in ops.engines.PROVIDERS); ops.vault.save(provider, key); true }
    @JavascriptInterface fun index() = safe { JSONArray(ops.index.list()) }
}
