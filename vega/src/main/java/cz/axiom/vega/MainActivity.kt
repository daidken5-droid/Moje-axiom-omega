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
const val CSP = "default-src 'none'; script-src 'self' 'unsafe-eval' blob:; worker-src blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'none'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'"

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val sinkRef: (String, JSONObject) -> Unit = { t, d -> send(t, d) }
    lateinit var ops: Ops
    @Volatile private var nfcChat: String? = null
    /** Události před načtením stránky (studený start, sdílení) se drží a pošlou, až JS zavolá ready(). */
    private val early = java.util.ArrayList<String>()
    @Volatile private var pageReady = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) Thread { attachUris(uris) }.start() else send("files", JSONObject().put("files", JSONArray())) }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) Thread { importTree(uri) }.start() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ops = (application as VegaApp).ops(sinkRef)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                // CSP i jako HTTP hlavička (platí spolehlivě i pro blob: Workery testů): žádná síť, žádné inline skripty
                loader.shouldInterceptRequest(request.url)?.also { r -> r.responseHeaders = (r.responseHeaders ?: emptyMap()) + mapOf("Content-Security-Policy" to CSP, "X-Content-Type-Options" to "nosniff") }
            /** Renderer spadl (např. paměťová bomba v testech projektu) → nepadat s celou aplikací, jen znovu načíst UI. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // mrtvý WebView se nesmí dál používat: odpojit, zničit a teprve pak obnovit aktivitu
                synchronized(early) { pageReady = false }
                runOnUiThread { runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }; recreate() }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net" && u.scheme == "https") return false
                // ven jen http(s), jen hlavní rámec a jen po skutečném klepnutí (skript nesmí sám otevírat stránky = únik dat)
                if ((u.scheme == "https" || u.scheme == "http") && request.isForMainFrame && request.hasGesture()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)) }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "VEGA")
        // Zpět: nejdřív zavřít list / režim nástroje v UI; jinak aplikaci jen schovat (úlohy a dotazy běží dál, nic se neztratí)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.vega && vega.back && vega.back()) ? 'y' : 'n'") { r -> if (!r.contains("y")) moveTaskToBack(true) }
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        // po recreate()/obnově procesu nesdílet tytéž soubory znovu
        if (savedInstanceState == null) handleShare(intent)
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

    /** Události jdou na UI vlákno POD zámkem → pořadí zůstane i kolem ready() (žádné předbíhání fronty). */
    fun send(type: String, data: JSONObject) {
        val js = "window.vega && vega.on(${JSONObject.quote(type)}, ${data})"
        synchronized(early) {
            if (!pageReady) { early += js; if (early.size > 300) early.removeAt(0); return }
            runOnUiThread { if (!isFinishing && !isDestroyed) web.evaluateJavascript(js, null) }
        }
    }

    /** Stránka načtena (volá JS) → poslat odložené události a znovu ukázat otevřené dotazy (bez duplicit). */
    fun ready() {
        val skip = HashSet<String>()
        synchronized(early) {
            early.forEach { js -> Regex("\"req\":\"(q[0-9a-z]+)\"").findAll(js).forEach { skip += it.groupValues[1] } }
            val q = early.toList(); early.clear(); pageReady = true
            runOnUiThread { q.forEach { web.evaluateJavascript(it, null) } }
        }
        ops.replay(skip)
    }

    override fun onDestroy() {
        synchronized(early) { pageReady = false }
        (application as VegaApp).detach(sinkRef)
        if (::web.isInitialized) runCatching { web.stopLoading(); (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    private fun nameOf(u: Uri) = runCatching { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: u.lastPathSegment ?: "soubor"

    /** Sdílené / vybrané soubory: jen content:// od CIZÍ aplikace (ne soukromá data VEGA, ne file://), čtení s limitem. */
    private fun attachUris(uris: List<Uri>) {
        val arr = JSONArray(); var sum = 0L
        for (u in uris.take(8)) {
            runCatching {
                require(u.scheme == "content") { "Soubor nejde otevřít (jen přes systémový výběr / sdílení)." }
                require(u.authority?.startsWith(packageName) != true) { "Vlastní soubory VEGA sem nepatří." }
                val size = runCatching { contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } }.getOrNull() ?: -1L
                require(size <= 60_000_000) { "Soubor je větší než 60 MB." }
                val bytes = contentResolver.openInputStream(u)!!.use { s ->
                    val o = java.io.ByteArrayOutputStream(if (size in 1..60_000_000) size.toInt() else 64 * 1024); val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; require(total <= 60_000_000) { "Soubor je větší než 60 MB." }
                        require(sum + total <= 120_000_000) { "Sdílené soubory dohromady přesahují 120 MB." }; o.write(buf, 0, n) }
                    sum += total
                    o.toByteArray()
                }
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
                // čtečka neběží donekonečna: po 60 s ji vypnout a říct to
                web.postDelayed({ if (nfcChat == chat && nfcActive) { nfcStop(); ops.onNfc(null, "Žádný štítek jsem do minuty nenačetl – zkus to znovu a podrž ho u zadní strany telefonu.", chat) } }, 60_000)
                nfcActive = true
            }
        }
    }

    @Volatile private var nfcActive = false
    fun nfcStop() { nfcActive = false; runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } }

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
    /** Balíček ověřovače citací (.zip) – instaluje se mimo chat, ověří otisky souborů. */
    private val pickVerifier = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val r = try { contentResolver.openInputStream(uri)!!.use { ops.installVerifier(it) } } catch (e: Exception) { "Instalace selhala: ${e.message}" }
            send("verifier", JSONObject().put("message", r).put("ready", ops.grounding.ready))
        }.start()
    }
    fun pickVerifierZip() = runOnUiThread { pickVerifier.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    /** Hlasové zadání přes systémové rozpoznávání (čeština); aplikace sama mikrofon nepotřebuje. */
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        send("voice", JSONObject().put("text", t ?: "").put("ok", t != null))
    }
    fun voice() = runOnUiThread {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ").putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Mluv…")
        try { speech.launch(i) } catch (e: android.content.ActivityNotFoundException) { send("voice", JSONObject().put("ok", false).put("error", "Rozpoznávání řeči v telefonu není k dispozici.")) }
    }
    fun pickFolder() = runOnUiThread { pickTree.launch(null) }

    fun share(name: String) {
        val f = ops.out.file(name) ?: return
        val mime = when (f.extension.lowercase()) { "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "csv" -> "text/csv"; "zip" -> "application/zip"; else -> "*/*" }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, ops.out.uri(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
    }

    /**
     * Instalace samoúpravy: APK musí být STEJNÝ balíček, podepsaný STEJNÝM certifikátem a s vyšší verzí.
     * Instalaci potvrdí uživatel v systémovém dialogu (Android jinak nedovolí). Vrací null = dialog otevřen, jinak důvod.
     */
    fun installUpdate(u: cz.axiom.vega.selfupdate.SelfUpdateStore.Upd): String? {
        if (u.status != cz.axiom.vega.selfupdate.SelfUpdateStore.Status.READY.name) return "Úprava není připravená k instalaci (${u.status})."
        val f = ops.selfUpdateApk(u.apk) ?: return "APK úpravy už v telefonu není – zkontroluj sestavení znovu."
        // otisk znovu těsně před instalací: soubor musí být přesně ten, který prošel kontrolou po stažení
        val sha = f.inputStream().use { s -> val md = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }; md.digest().joinToString("") { "%02x".format(it) } }
        if (u.apkSha.isBlank() || sha != u.apkSha) return "Otisk APK se od kontroly změnil – neinstaluji. Zkontroluj sestavení znovu."
        val pm = packageManager
        @Suppress("DEPRECATION") val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val arch = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return "Soubor není platné APK."
        if (arch.packageName != packageName) return "APK je jiná aplikace (${arch.packageName}) – neinstaluji."
        fun certs(pi: android.content.pm.PackageInfo): Set<String> {
            @Suppress("DEPRECATION") val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners?.toList().orEmpty() else pi.signatures?.toList().orEmpty()
            return sigs.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val mine = pm.getPackageInfo(packageName, flags)
        if (certs(arch).isEmpty() || certs(arch) != certs(mine)) return "APK je podepsané jiným klíčem než VEGA – neinstaluji."
        @Suppress("DEPRECATION") val newer = if (android.os.Build.VERSION.SDK_INT >= 28) arch.longVersionCode > mine.longVersionCode else arch.versionCode > mine.versionCode
        if (!newer) return "APK není novější než nainstalovaná verze."
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return "Povol VEGA instalovat aplikace (otevřel jsem nastavení) a klepni na Nainstalovat znovu."
        }
        return runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ops.out.uri(f), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            .getOrElse { "Instalaci nejde spustit: ${it.message}" }
    }

    fun open(name: String) {
        val f = ops.out.file(name) ?: return
        val mime = when (f.extension.lowercase()) { "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "csv" -> "text/csv"; else -> "*/*" }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ops.out.uri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { send("error", JSONObject().put("text", "Žádná aplikace neumí otevřít ${f.extension} – použij Sdílet.")) }
    }

}

/** Obsah vložený do pole klíče: „export GEMINI_API_KEY="AIza…"“, uvozovky, středník, celý řádek → jen klíč. */
fun normalizeKey(provider: String, raw: String): String {
    var k = raw.trim().removePrefix("export ").trim()
    // jen „NÁZEV_PROMĚNNÉ=…“ (velká písmena) – token typu „abc:def“ nebo base64 „…==“ zůstane celý
    Regex("^[A-Z_][A-Z0-9_]*\\s*[=:]\\s*").find(k)?.let { k = k.substring(it.range.last + 1) } // jen první oddělovač
    k = k.trim().trimEnd(';').trim().trim('"', '\'', '`').trim()
    if (provider.startsWith("gemini")) Regex("AIza[0-9A-Za-z_-]{30,}").find(raw)?.let { k = it.value }
    return k
}

/** Most JS → Kotlin. Každá metoda vrací JSON (nebo prázdné), dlouhé operace hlásí výsledek událostí. */
class Bridge(private val a: MainActivity) {
    private val ops get() = a.ops
    private fun safe(block: () -> Any?): String = try { (block() ?: "").toString() } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString() }
    private fun params(json: String): Map<String, String> = runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrDefault(emptyMap())

    @JavascriptInterface fun home() = safe { ops.home() }
    @JavascriptInterface fun tools() = safe { ops.toolsJson() }
    @JavascriptInterface fun chats() = safe { ops.chats() }
    // řetězcové výsledky jako JSON (JS je parsuje – holý text by se zahodil)
    @JavascriptInterface fun newChat() = safe { JSONObject.quote(ops.newChat()) }
    @JavascriptInterface fun messages(chat: String) = safe { ops.messages(chat) }
    @JavascriptInterface fun dropAttachment(id: String) = safe { ops.dropAttachment(id); true }
    @JavascriptInterface fun chatRev(chat: String): String = runCatching { ops.chatRev(chat) }.getOrDefault("")
    @JavascriptInterface fun send(chat: String, text: String, ids: String) = safe {
        val f = runCatching { JSONArray(ids).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        if (chat == "temp") ops.chatTemp(text, f) else if (text.length > 6000) ops.longTask(chat, text, f) else ops.chat(chat, text, f)
    }
    @JavascriptInterface fun ready() = safe { a.ready() }
    @JavascriptInterface fun clearTemp() = safe { ops.clearTemp() }
    @JavascriptInterface fun stop() = safe { ops.stop(); a.nfcStop() }
    @JavascriptInterface fun answer(req: String, json: String) = safe { ops.answer(req, JSONObject(json)) }
    @JavascriptInterface fun isBusy() = safe { ops.busy }
    @JavascriptInterface fun pickFiles() = safe { a.pick() }
    @JavascriptInterface fun pickFolder() = safe { a.pickFolder() }
    @JavascriptInterface fun runTool(chat: String, tool: String, input: String, p: String) = safe { ops.runTool(chat.takeIf { it != "temp" }, tool, JSONObject(input), params(p)) }
    @JavascriptInterface fun run(id: String) = safe { ops.runJson(id) }
    @JavascriptInterface fun resume(chat: String, id: String, fix: String) = safe {
        val o = runCatching { JSONObject(fix) }.getOrDefault(JSONObject())
        ops.resume(chat.takeIf { it != "temp" }, id, o.keys().asSequence().associate { k -> k.toInt() to params(o.getJSONObject(k).toString()) })
    }
    // čísla jako text: most WebView by JS řetězec do Int převedl na 0
    @JavascriptInterface fun export(run: String, step: String) = safe { ops.exportValue(run, step.toIntOrNull() ?: -1) }
    @JavascriptInterface fun procedures() = safe { JSONArray().apply { ops.procedures.list().forEach { p -> put(JSONObject().put("id", p.id).put("pinned", p.pinned).put("name", p.name).put("version", p.version).put("description", p.description).put("inputDesc", p.inputDesc).put("inputKind", p.inputKind).put("outputKind", p.outputKind).put("perms", JSONArray(p.perms)).put("steps", JSONArray(p.steps.map { it.tool })).put("changelog", JSONArray(p.changelog)).put("uses", p.uses)) } } }
    @JavascriptInterface fun saveProcedure(json: String) = safe { ops.saveProcedure(JSONObject(json)) }
    @JavascriptInterface fun deleteProcedure(id: String) = safe { ops.procedures.delete(id) }
    @JavascriptInterface fun revertProcedure(id: String) = safe { ops.procedures.revert(id)?.let { JSONObject().put("version", it.version) } ?: throw IllegalStateException("Starší verze není.") }
    @JavascriptInterface fun runProcedure(chat: String, id: String, input: String) = safe { ops.runProcedure(chat.takeIf { it != "temp" }, id, JSONObject(input)) }
    @JavascriptInterface fun memory() = safe { ops.memoryJson() }
    @JavascriptInterface fun improvements() = safe { ops.improvementsJson() }
    @JavascriptInterface fun selfUpdates() = safe { ops.selfUpdatesJson() }
    @JavascriptInterface fun selfUpdateStart(task: String, chat: String) = safe { require(task.isNotBlank()) { "Napiš, co mám na sobě změnit." }; ops.selfUpdateStart(chat.ifBlank { null }, task.take(1000)); "true" }
    @JavascriptInterface fun selfUpdateRollback(chat: String) = safe { ops.selfUpdateRollback(chat.ifBlank { null }); true }
    @JavascriptInterface fun selfUpdateAction(id: String, action: String) = safe {
        val u = ops.selfUpdates.get(id) ?: throw IllegalArgumentException("Úprava neexistuje")
        when (action) {
            "poll" -> { ops.selfUpdatePoll(id); "true" }
            "discard" -> { ops.selfUpdateDiscard(id); "true" }
            "install" -> a.installUpdate(u)?.let { JSONObject.quote(it) } ?: "true"
            else -> throw IllegalArgumentException("Neznámá akce")
        }
    }
    @JavascriptInterface fun improvementAction(id: String, action: String) = safe { ops.improvementAction(id, action)?.let { JSONObject.quote(it) } ?: "true" }
    @JavascriptInterface fun memoryRevert(id: String) = safe { ops.memory.revert(id)?.text?.let { JSONObject.quote(it) } }
    @JavascriptInterface fun memoryForget(id: String) = safe { ops.memory.forget(id).size }
    @JavascriptInterface fun memoryAdd(text: String) = safe {
        when (val o = ops.memory.remember(text)) {
            is cz.axiom.vega.core.Mem.Outcome.Rejected -> JSONObject().put("ok", false).put("reason", o.reason)
            else -> JSONObject().put("ok", true)
        }
    }
    @JavascriptInterface fun projects() = safe { ops.projects() }
    @JavascriptInterface fun project(id: String) = safe { ops.projectInfo(id) }
    @JavascriptInterface fun newProject(name: String) = safe { JSONObject.quote(ops.createProject(name.ifBlank { "Nový projekt" }, emptyMap())) }
    @JavascriptInterface fun demandSample() = safe { ops.demandSample() }
    @JavascriptInterface fun activate(id: String) = safe {
        require(id.isBlank() || ops.validProjectId(id)) { "Neplatný projekt" }
        ops.settings.activeProject = id.ifBlank { null }
    }
    @JavascriptInterface fun file(id: String, path: String) = safe {
        // citlivý soubor: ani obsah, ani otisk (otisk by umožnil přepsat ho zástupným textem)
        if (cz.axiom.vega.core.Workspace.isSecret(path)) JSONObject().put("secret", true)
        else ops.workspace(id).let { JSONObject().put("text", it.read(path).take(200_000)).put("hash", it.hash(path)) }
    }
    @JavascriptInterface fun saveFile(id: String, path: String, text: String, hash: String) = safe {
        require(!cz.axiom.vega.core.Workspace.isSecret(path)) { "Soubory s klíči v aplikaci neupravuji." }
        ops.workspace(id).write(path, text, hash.ifBlank { null }, "ruční úprava").id
    }
    @JavascriptInterface fun undo(id: String, change: String) = safe { JSONObject.quote(ops.undoChange(id, change.toIntOrNull()?.takeIf { it > 0 })) }
    @JavascriptInterface fun diff(id: String) = safe { JSONArray().apply { ops.workspace(id).diffFromBase().forEach { (p, d) -> put(JSONObject().put("path", p).put("added", d.added).put("removed", d.removed).put("diff", cz.axiom.vega.core.Diff.unified(d.lines, 3))) } } }
    @JavascriptInterface fun verify(id: String) = safe {
        // přes frontu úloh: testy nesmí běžet souběžně s úpravami, které právě aplikuje model
        ops.submit {
            try {
                val v = ops.verify(ops.workspace(id))
                a.send("verified", JSONObject().put("project", id).put("kind", v.kind).put("exitCode", v.exitCode).put("label", v.label).put("output", v.output.take(6000)).put("command", v.command).put("revision", v.revision))
            } catch (e: cz.axiom.vega.llm.CancelledException) { a.send("verified", JSONObject().put("project", id).put("kind", "none").put("exitCode", -1).put("label", "zrušeno"))
            } catch (e: Exception) { a.send("error", JSONObject().put("text", "Testy se nepodařilo spustit: ${e.message?.take(160)}")) }
        }
    }
    @JavascriptInterface fun exportProject(id: String) = safe { val w = ops.workspace(id); JSONObject.quote(ops.out.save("${w.name}.zip", "application/zip", w.exportZip()).name) }
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
            "usdKc" -> s.usdKc = (value.replace(',', '.').toFloatOrNull() ?: 23.5f).coerceIn(10f, 60f)
            "initiative" -> s.initiative = value == "true"
            "airgap" -> s.airgap = value == "true"
            "ownOnly" -> s.ownOnly = value == "true"
            "webAllowed" -> s.webAllowed = value == "true"
            "ghRepo" -> { require(value.isBlank() || Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$").matches(value.trim())) { "Repozitář zadej jako vlastník/název." }; s.ghRepo = value }
            "ghBase" -> { require(Regex("^[A-Za-z0-9._/-]{1,120}$").matches(value.trim()) && !value.trim().startsWith("vega/auto-")) { "Neplatná větev." }; s.ghBase = value }
            "autoImprove" -> s.autoImprove = value == "true"
            "userName" -> s.userName = value
            "ownUrl" -> {
                if (value.isNotBlank()) cz.axiom.vega.net.Http.requireAllowed(value.trim())
                // klíč patří serveru: při změně serveru (host:port) ho neposílat jinam
                fun hostOf(u: String) = runCatching { java.net.URI(u.trim()).let { "${it.host}:${it.port}" } }.getOrNull()
                if (hostOf(value) != hostOf(s.ownUrl)) ops.vault.remove("own")
                s.ownUrl = value
            }
            "ownModel" -> { s.ownModel = value; s.setModel("own", value.trim()) }
            else -> when {
                key.startsWith("allow.") -> s.setAllowed(key.removePrefix("allow."), value == "true")
                key.startsWith("budget.") -> s.setBudgetKc(key.removePrefix("budget."), value.toIntOrNull() ?: 0)
            }
        }
    }
    @JavascriptInterface fun saveKey(provider: String, key: String) = safe {
        require(provider in ops.engines.PROVIDERS || provider == "own" || provider == "github") { "Neznámý poskytovatel" }
        val k = normalizeKey(provider, key)
        // stejný klíč v obou slotech Gemini = „zdarma“ by čerpal placený projekt bez stropu
        if (k.isNotEmpty() && provider.startsWith("gemini")) {
            val other = if (provider == "gemini") "geminipro" else "gemini"
            require(ops.vault.read(other) != k) { "Stejný klíč už je ve slotu „${ops.engines.name(other)}“. Klíč z projektu s fakturací patří jen do „Gemini – kredit Pro“." }
        }
        // klíč nikdy neprojde zpět do WebView; formát: bez mezer, rozumná délka (vložený celý odstavec ≠ klíč)
        require(k.isEmpty() || (k.length in (if (provider == "own") 1 else 16)..400 && k.none { it.isWhitespace() })) { "Tohle nevypadá jako API klíč (mezery nebo špatná délka)." }
        if (provider == "gemini" && k.isNotEmpty()) ops.settings.geminiFreeConfirmed = true // UI potvrzení „projekt bez fakturace“ proběhlo před uložením
        ops.vault.save(provider, k)
        if (k.isNotEmpty() && provider != "github") Thread { val c = ops.engines.check(provider); a.send("keycheck", JSONObject().put("provider", provider).put("ok", c.ok).put("message", c.message).put("model", c.model ?: "")) }.start()
        true
    }
    @JavascriptInterface fun checkKey(provider: String) = safe { Thread { val c = ops.engines.check(provider); a.send("keycheck", JSONObject().put("provider", provider).put("ok", c.ok).put("message", c.message).put("model", c.model ?: "")) }.start() }
    @JavascriptInterface fun testOwn() = safe { require(!ops.settings.airgap) { "Air-gap je zapnutý." }; Thread { val c = ops.engines.testOwn(); a.send("keycheck", JSONObject().put("provider", "own").put("ok", c.ok).put("message", c.message).put("model", c.model ?: "")) }.start() }
    @JavascriptInterface fun retry(chat: String, req: String) = safe { ops.retry(chat.takeIf { it != "temp" }, req.ifBlank { null }) }
    @JavascriptInterface fun resumeTask(chat: String, project: String) = safe { ops.resumeTask(chat.takeIf { it != "temp" }, project) }
    @JavascriptInterface fun keepChanges(chat: String, project: String, task: String, keep: String) = safe {
        fun ids(s: String) = s.split(',').mapNotNull { it.trim().toIntOrNull() }
        ops.keepChanges(chat.takeIf { it != "temp" }, project, ids(task), ids(keep))
    }
    @JavascriptInterface fun buttonTry(chat: String, file: String) = safe { ops.buttonTry(chat.takeIf { it != "temp" }, file) }
    @JavascriptInterface fun buttonSave(chat: String, name: String) = safe { ops.buttonSave(chat.takeIf { it != "temp" }, name) }
    @JavascriptInterface fun pinProcedure(id: String, on: String) = safe { ops.procedures.setPinned(id, on == "true"); true }
    @JavascriptInterface fun rememberGlobal(text: String) = safe { ops.memory.remember(text, "global").let { JSONObject.quote(it.javaClass.simpleName) } }
    @JavascriptInterface fun voice() = safe { a.voice() }
    @JavascriptInterface fun pairCode(code: String) = safe { cz.axiom.vega.llm.Pairing.parse(code).let { JSONObject().put("url", it.url).put("model", it.model) } }
    @JavascriptInterface fun verifierInstall() = safe { a.pickVerifierZip() }
    @JavascriptInterface fun verifierDownload() = safe { ops.downloadVerifier(); true }
    @JavascriptInterface fun verifierRemove() = safe { ops.removeVerifier(); true }
    @JavascriptInterface fun exportRetry(chat: String, run: String) = safe { ops.exportRetry(chat.takeIf { it != "temp" }, run) }
}
