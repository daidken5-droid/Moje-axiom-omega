package cz.axiom.vega

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
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

private const val UI_URL = "https://appassets.androidplatform.net/assets/ui/index.html"

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val sinkRef: (String, JSONObject) -> Unit = { t, d -> send(t, d) }
    lateinit var ops: Ops
    @Volatile private var nfcChat: String? = null
    @Volatile private var wifiChat: String? = null
    // Android 12+ ignoruje žádost o přesnou polohu bez přibližné → žádáme o obě; seznam WiFi vydá jen s přesnou
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        when {
            r[android.Manifest.permission.ACCESS_FINE_LOCATION] == true -> doWifiScan(wifiChat)
            r[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true -> ops.onWifi("[]", null, null, "Povolil jsi jen přibližnou polohu – Android ale okolní WiFi ukáže jen s přesnou. Změníš to v Nastavení telefonu → Aplikace → VEGA → Oprávnění → Poloha → „Použít přesnou polohu“. VEGA polohu nikam neposílá.", wifiChat)
            else -> ops.onWifi("[]", null, null, "Bez přístupu k poloze nemůžu přečíst okolní WiFi (Android to vyžaduje). Můžeš ho povolit v Nastavení telefonu → Aplikace → VEGA → Oprávnění.", wifiChat)
        }
    }
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
        // WebView padding IGNORUJE (obsah kreslí dál pod stavový řádek a lištu gest) → odsazení nese obalový kontejner
        val root = android.widget.FrameLayout(this)
        root.addView(web, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Android 15 (targetSdk 35) kreslí od okraje k okraji a adjustResize nefunguje: odsazení podle systémových lišt
        // a KLÁVESNICE ručně – jinak hlavička vjede pod stavový řádek a klávesnice zakryje pole pro psaní
        web.setBackgroundColor(0xFF0B0D12.toInt()); root.setBackgroundColor(0xFF0B0D12.toInt()); window.decorView.setBackgroundColor(0xFF0B0D12.toInt()) // pruh pod lištami v barvě aplikace (ne bílý)
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            blockNetworkLoads = true // UI se načítá jen z WebViewAssetLoader; modely volá Kotlin.
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
            // velké systémové písmo (Nastavení › Displej › Velikost písma) i ve VEGA – WebView ho sám spolehlivě nepřebírá
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(85, 200)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                // CSP i jako HTTP hlavička (platí spolehlivě i pro blob: Workery testů): žádná síť, žádné inline skripty
                loader.shouldInterceptRequest(request.url)?.also { r -> r.responseHeaders = (r.responseHeaders ?: emptyMap()) + mapOf("Content-Security-Policy" to CSP, "X-Content-Type-Options" to "nosniff") }
                    ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
            /** Renderer spadl (např. paměťová bomba v testech projektu) → nepadat s celou aplikací, jen znovu načíst UI. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // mrtvý WebView se nesmí dál používat: odpojit, zničit a teprve pak obnovit aktivitu
                synchronized(early) { pageReady = false }
                runOnUiThread { runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }; recreate() }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return u != Uri.parse(UI_URL) || !request.isForMainFrame
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
        web.loadUrl(UI_URL)
        // po recreate()/obnově procesu nesdílet tytéž soubory znovu
        if (savedInstanceState == null) handleShare(intent)
        runCatching { NewsAlarms.schedule(this, ops.settings.newsEnabled) }  // plán po aktualizaci / prvním spuštění
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (!g) send("toast", JSONObject().put("text", "Bez povolení oznámení ranní přehled jen uložím do konverzací (notifikace nepřijde).")) }
    /** Zapnutí/vypnutí ranního přehledu: povolení oznámení (Android 13+) a naplánování 05:20 / 05:30. */
    fun newsChanged() = runOnUiThread {
        val on = ops.settings.newsEnabled
        if (on && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        NewsAlarms.channel(this); NewsAlarms.schedule(this, on)
    }

    private fun handleShare(i: Intent?) {
        if (i == null) return
        i.getStringExtra("openChat")?.let { send("openChat", JSONObject().put("id", it)); i.removeExtra("openChat") }
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { send("shared", JSONObject().put("text", it)) }
                @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { u -> Thread { attachUris(listOf(u), indexNow = false) }.start() }
            }
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { l -> Thread { attachUris(l, indexNow = false) }.start() }
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
    private fun attachUris(uris: List<Uri>, indexNow: Boolean = true) {
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
                arr.put(ops.attach(nameOf(u), contentResolver.getType(u), bytes, indexNow))
            }.onFailure { arr.put(JSONObject().put("error", "${nameOf(u)}: ${it.message}")) }
        }
        send("files", JSONObject().put("files", arr).put("skipped", maxOf(0, uris.size - 8)))
    }

    /** Import složky jako projektu (kopie – originál zůstane netknutý). */
    private fun importTree(uri: Uri) {
        try {
            val root = DocumentFile.fromTreeUri(this, uri) ?: throw IllegalStateException("Složku nejde otevřít")
            val files = LinkedHashMap<String, ByteArray>(); var total = 0L
            var skipped = 0
            var visited = 0
            fun walk(d: DocumentFile, prefix: String, depth: Int) {
                require(depth <= 64) { "Složka má příliš mnoho úrovní. Vyber menší podsložku." }
                for (entry in d.listFiles()) {
                    require(++visited <= 20_000) { "Složka je příliš rozsáhlá. Vyber menší podsložku." }
                    val name = entry.name ?: throw IllegalStateException("Jedna z položek nemá název.")
                    require(name !in setOf(".", "..") && '/' !in name && '\\' !in name) { "Neplatný název položky ve složce." }
                    if (name in setOf(".git", "node_modules", "build", ".gradle", "dist", "__pycache__", ".idea")) continue
                    val path = prefix + name
                    if (entry.isDirectory) {
                        walk(entry, "$path/", depth + 1)
                        continue
                    }
                    val limit = cz.axiom.vega.core.Workspace.MAX_FILE
                    if (entry.length() > limit) { skipped++; continue }
                    require(files.size < 2000) { "Složka má více než 2000 souborů. Vyber menší podsložku." }
                    val stream = contentResolver.openInputStream(entry.uri) ?: error("Soubor $path nejde otevřít.")
                    val bytes = stream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > limit) return@use null
                            require(total + out.size() + count <= 80_000_000L) { "Projekt je větší než 80 MB." }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    if (bytes == null) { skipped++; continue }
                    require(path !in files) { "Dvě položky mají stejnou cestu: $path." }
                    files[path] = bytes
                    total += bytes.size
                }
            }
            walk(root, "", 0)
            val id = ops.createProject(root.name ?: "projekt", files)
            send("project", JSONObject().put("id", id).put("imported", files.size).put("skipped", skipped))
        } catch (e: Exception) { send("error", JSONObject().put("text", "Import složky: ${e.message}")) }
    }

    // ------------------------------------------------------------------ NFC (reader mode jen na vyžádání)

    @Volatile private var nfcMode = "inspect"   // inspect | card | write
    @Volatile private var pendingWrite: Pair<String, String>? = null  // (kind: text|url, content)

    fun nfcStart(chat: String?, mode: String = "inspect") {
        nfcChat = chat; nfcMode = mode
        val a = NfcAdapter.getDefaultAdapter(this)
        when {
            a == null -> dispatchNfcError("Tenhle telefon nemá NFC.")
            !a.isEnabled -> { send("nfc.off", JSONObject()); dispatchNfcError("NFC je vypnuté. Zapni ho v Nastavení telefonu → Připojená zařízení (tlačítko níže) a zkus znovu.") }
            else -> runOnUiThread {
                a.enableReaderMode(this, { tag -> onTag(tag) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
                send("nfc.listening", JSONObject())
                web.postDelayed({ if (nfcChat == chat && nfcActive) { nfcStop(); dispatchNfcError("Nic jsem do minuty nenačetl – zkus to znovu a podrž telefon u sebe.") } }, 60_000)
                nfcActive = true
            }
        }
    }

    private fun dispatchNfcError(msg: String) = when (nfcMode) {
        "card" -> ops.onCardRead(null, msg, nfcChat); "write" -> send("nfc.writeDone", JSONObject().put("error", msg)); else -> ops.onNfc(null, msg, nfcChat)
    }

    private fun onTag(tag: Tag) = when (nfcMode) {
        "card" -> { readVirtualCard(tag); nfcStop() }
        "write" -> { writeTag(tag); nfcStop() }
        else -> readTag(tag)
    }

    /** Čtečka virtuální karty VEGA z druhého telefonu přes ISO-DEP (SELECT AID → READ → CardApdu.decode). */
    private fun readVirtualCard(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return ops.onCardRead(null, "Tohle není karta VEGA (chybí ISO-DEP). Druhý telefon musí mít aktivní virtuální kartu.", nfcChat)
        try {
            iso.connect(); iso.timeout = 3000
            val sel = iso.transceive(cz.axiom.vega.core.CardApdu.selectApdu())
            if (cz.axiom.vega.core.CardApdu.splitSw(sel).second != cz.axiom.vega.core.CardApdu.SW_OK_INT)
                return ops.onCardRead(null, "Druhý telefon nemá aktivní kartu VEGA (nebo ji právě deaktivoval).", nfcChat)
            val read = iso.transceive(cz.axiom.vega.core.CardApdu.readApdu())
            val (data, sw) = cz.axiom.vega.core.CardApdu.splitSw(read)
            if (sw != cz.axiom.vega.core.CardApdu.SW_OK_INT) ops.onCardRead(null, "Karta přestala být aktivní během čtení.", nfcChat)
            else ops.onCardRead(data, null, nfcChat)
        } catch (e: Exception) { ops.onCardRead(null, "Spojení se přerušilo (${e.message ?: "telefon oddálen"}) – přilož telefony k sobě a zkus znovu.", nfcChat) }
        finally { runCatching { iso.close() } }
    }

    fun nfcWriteStart(chat: String?, kind: String, content: String) { pendingWrite = kind to content; nfcStart(chat, "write") }

    /** Zápis textu/URL na VLASTNÍ zapisovatelný štítek: před zápisem kapacita, po zápisu zpětné čtení. */
    private fun writeTag(tag: Tag) {
        val (kind, content) = pendingWrite ?: return send("nfc.writeDone", JSONObject().put("error", "Není co zapsat."))
        val rec = if (kind == "url") NdefRecord.createUri(content) else NdefRecord.createTextRecord("cs", content)
        val msg = NdefMessage(arrayOf(rec)); val size = msg.toByteArray().size
        val ndef = Ndef.get(tag); var fmt: NdefFormatable? = null
        try {
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) return send("nfc.writeDone", JSONObject().put("error", "Štítek je jen pro čtení – na tenhle zapsat nejde."))
                if (ndef.maxSize < size) return send("nfc.writeDone", JSONObject().put("error", "Na štítek se to nevejde (potřeba $size B, kapacita ${ndef.maxSize} B)."))
                ndef.writeNdefMessage(msg)
                val back = (ndef.ndefMessage ?: ndef.cachedNdefMessage)?.toByteArray()
                send("nfc.writeDone", JSONObject().put("ok", true).put("size", size).put("verified", back != null && back.contentEquals(msg.toByteArray())))
            } else {
                fmt = NdefFormatable.get(tag) ?: return send("nfc.writeDone", JSONObject().put("error", "Tenhle štítek neumím popsat (není NDEF)."))
                fmt!!.connect(); fmt!!.format(msg); send("nfc.writeDone", JSONObject().put("ok", true).put("size", size).put("verified", true))
            }
        } catch (e: Exception) { send("nfc.writeDone", JSONObject().put("error", "Zápis selhal (${e.message ?: "štítek oddálen"}) – podrž štítek u telefonu.")) }
        finally { runCatching { ndef?.close() }; runCatching { fmt?.close() }; pendingWrite = null }
    }

    @Volatile private var nfcActive = false
    fun nfcStop() { nfcActive = false; runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } }

    /** Inspektor štítku: UID, technologie, u NFC-A hodnoty SAK/ATQA a veřejné NDEF. Jen čtení – nic se nezapisuje ani neklonuje. */
    private fun readTag(tag: Tag) {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val nfcA = runCatching { NfcA.get(tag) }.getOrNull()
        val sak = nfcA?.sak?.toInt()?.and(0xFF)
        val atqa = nfcA?.atqa?.let { if (it.size >= 2) (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) else null }
        var ndefBytes: ByteArray? = null
        var readFailed = false
        Ndef.get(tag)?.let { ndef ->
            try { ndef.connect(); ndefBytes = (ndef.ndefMessage ?: ndef.cachedNdefMessage)?.toByteArray() }
            // štítek se vzdálil / spojení spadlo: zkusit aspoň obsah z doby objevení, jinak říct „přilož znovu“ (ne „štítek je prázdný“)
            catch (_: Exception) { ndefBytes = runCatching { ndef.cachedNdefMessage?.toByteArray() }.getOrNull(); readFailed = ndefBytes == null }
            finally { runCatching { ndef.close() } }
        }
        ops.onNfcTag(tag.id ?: ByteArray(0), techs, sak, atqa, ndefBytes, nfcChat, readFailed)
        nfcStop()
    }

    @Volatile private var bleStop: (() -> Unit)? = null
    override fun onPause() { super.onPause(); nfcStop(); bleStop?.invoke() }

    fun openNfcSettings() = startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))

    /** WiFi analyzátor: pasivní sken okolí (co sítě samy vysílají). Vyžaduje polohu (Android) – požádáme o ni. */
    fun wifiScan(chat: String?) {
        wifiChat = chat
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm == null || !wm.isWifiEnabled) { ops.onWifi("[]", null, null, "WiFi je vypnutá. Zapni ji a zkus to znovu.", chat); return }
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm != null && !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) { ops.onWifi("[]", null, null, "Zapni Polohu (systémové nastavení) – Android bez ní sken WiFi nepovolí. VEGA polohu nikam neposílá, jen díky ní vidí sítě.", chat); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        doWifiScan(chat)
    }

    @SuppressLint("MissingPermission")
    private fun doWifiScan(chat: String?) = Thread {
        try {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            runCatching { @Suppress("DEPRECATION") wm.startScan() }
            val results = wm.scanResults ?: emptyList()
            val arr = JSONArray()
            // ScanResult.SSID je od API 33 zastaralé (wifiSsid), minSdk je nižší → záměrně staré pole
            @Suppress("DEPRECATION") for (r in results) arr.put(JSONObject().put("ssid", r.SSID ?: "").put("bssid", r.BSSID ?: "").put("rssi", r.level).put("freq", r.frequency).put("caps", r.capabilities ?: ""))
            val info = runCatching { @Suppress("DEPRECATION") wm.connectionInfo }.getOrNull()
            ops.onWifi(arr.toString(), info?.ssid, info?.bssid, null, chat)
        } catch (e: Exception) { ops.onWifi("[]", null, null, "WiFi sken se nepovedl (${e.message ?: "neznámá chyba"}).", chat) }
    }.start()

    /** Dostupnost hardwaru pro UI (co má smysl nabídnout). */
    fun hwCapabilities(): JSONObject {
        val nfc = NfcAdapter.getDefaultAdapter(this)
        val pm = packageManager
        return JSONObject()
            .put("nfc", nfc != null).put("nfcOn", nfc?.isEnabled == true)
            .put("hce", pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            .put("ble", pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE))
    }

    /** Diagnostika AKTUÁLNÍHO připojení – lokální, funguje i bez internetu. */
    @SuppressLint("MissingPermission")
    fun wifiLink(chat: String?) = Thread {
        try {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wm.isWifiEnabled) return@Thread ops.onWifiLink("{}", "WiFi je vypnutá – zapni ji a zkus to znovu.", chat)
            @Suppress("DEPRECATION") val info = wm.connectionInfo
            if (info == null || info.networkId == -1) return@Thread ops.onWifiLink("{}", "Telefon teď není připojený k žádné WiFi.", chat)
            val rssi = info.rssi
            @Suppress("DEPRECATION") val ip = info.ipAddress.takeIf { it != 0 }?.let { "%d.%d.%d.%d".format(it and 0xFF, it shr 8 and 0xFF, it shr 16 and 0xFF, it shr 24 and 0xFF) }
            val sec = if (android.os.Build.VERSION.SDK_INT >= 31) when (info.currentSecurityType) {
                android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN -> "Otevřená (bez hesla)"; android.net.wifi.WifiInfo.SECURITY_TYPE_WEP -> "WEP (slabé)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2"; android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> "WPA3"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP -> "WPA-Enterprise"; else -> "jiné / neuvedeno"
            } else "neuvedeno (starší Android)"
            val o = JSONObject().put("ssid", (info.ssid ?: "").trim('"').ifBlank { "neuvedeno (chybí oprávnění polohy)" })
                .put("security", sec).put("rssi", rssi).put("quality", when { rssi >= -55 -> "výborný"; rssi >= -67 -> "dobrý"; rssi >= -75 -> "slabší"; else -> "slabý" })
                .put("freq", info.frequency).put("band", if (info.frequency < 2500) "2,4 GHz" else if (info.frequency < 5955) "5 GHz" else "6 GHz")
                .put("link", info.linkSpeed).put("ip", ip ?: "—").put("stale", false)
            ops.onWifiLink(o.toString(), null, chat)
        } catch (e: Exception) { ops.onWifiLink("{}", "Diagnostika selhala (${e.message ?: "neznámá chyba"}).", chat) }
    }.start()

    /** WiFi QR – vygeneruje obrázek offline, pošle ho JEN k zobrazení (ne do historie, logu ani exportu). */
    fun wifiQr(ssid: String, password: String, type: String, hidden: Boolean): Boolean {
        val payload = cz.axiom.vega.core.WifiTools.qrPayload(ssid, password, type, hidden)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(512 * 512) { i -> if (m.get(i % 512, i / 512)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 0, 512, 0, 0, 512, 512)  // jedno volání místo 262 144× setPixel
        val bos = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        runOnUiThread { send("wifiQr", JSONObject().put("img", "data:image/png;base64,$b64").put("ssid", ssid)) }
        return true
    }

    private val askBle = registerForActivityResult(ActivityResultContracts.RequestPermission()) { g -> if (g) doBleScan(bleChat) else ops.onBle("[]", "Bez oprávnění Bluetooth nejde hledat zařízení. Povol ho v Nastavení telefonu → Aplikace → VEGA → Oprávnění.", bleChat) }
    @Volatile private var bleChat: String? = null
    private val askBleLegacy = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) doBleScan(bleChat)
        else ops.onBle("[]", "Na tomhle Androidu hledání Bluetooth zařízení vyžaduje přesnou polohu. Povol ji v Nastavení telefonu → Aplikace → VEGA → Oprávnění (VEGA polohu nikam neposílá).", bleChat)
    }
    fun bleScan(chat: String?) {
        bleChat = chat
        val bt = (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (bt == null || !bt.isEnabled) return ops.onBle("[]", "Bluetooth je vypnuté – zapni ho a zkus to znovu.", chat)
        if (android.os.Build.VERSION.SDK_INT >= 31 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return askBle.launch(android.Manifest.permission.BLUETOOTH_SCAN)
        // Android 8–11: bez polohy sken BLE nic nevrátí
        if (android.os.Build.VERSION.SDK_INT < 31 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return askBleLegacy.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        doBleScan(chat)
    }
    @SuppressLint("MissingPermission")
    private fun doBleScan(chat: String?) {
        val sc = (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter.bluetoothLeScanner
            ?: return ops.onBle("[]", "Skener BLE není dostupný.", chat)
        val found = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val name = runCatching { r.device.name }.getOrNull() ?: r.scanRecord?.deviceName
                found[r.device.address] = JSONObject().put("name", name ?: "(bez názvu)").put("addr", r.device.address).put("rssi", r.rssi)
            }
        }
        send("nfc.listening", JSONObject()) // sdílený „poslouchám“ indikátor
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(report: Boolean) {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { sc.stopScan(cb) }; bleStop = null; send("idle", JSONObject())
            if (report) ops.onBle(JSONArray(found.values.sortedByDescending { it.optInt("rssi") }).toString(), null, chat)
            else ops.onBle("[]", "Hledání přerušeno (odešel jsi z obrazovky).", chat)
        }
        bleStop = { finish(false) } // opuštění obrazovky uvolní skener i spojení
        sc.startScan(cb)
        web.postDelayed({ finish(true) }, 6000)
    }

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
    fun voice(data: String) = runOnUiThread {
        // V režimu „Bez sítě“ nesmíme posílat zvuk k (možná online) rozpoznávání – nabídneme jen OVĚŘENÉ místní.
        val airgap = runCatching { JSONObject(data).optBoolean("airgap") }.getOrDefault(false)
        if (airgap) {
            val onDevice = android.os.Build.VERSION.SDK_INT >= 33 &&
                runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
            if (!onDevice) {
                send("voice", JSONObject().put("ok", false).put("error", "V režimu Bez sítě není ověřené místní diktování k dispozici – napiš text, prosím."))
                return@runOnUiThread
            }
        }
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ").putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Mluv…")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, airgap) // v air-gapu vynutit místní rozpoznávání
        try { speech.launch(i) } catch (e: android.content.ActivityNotFoundException) { send("voice", JSONObject().put("ok", false).put("error", "Rozpoznávání řeči v telefonu není k dispozici.")) }
    }
    fun pickFolder() = runOnUiThread { pickTree.launch(null) }

    fun share(name: String) {
        val f = ops.out.file(name) ?: return
        val mime = cz.axiom.vega.base.Mime.of(f.name)
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
        val mime = cz.axiom.vega.base.Mime.of(f.name)
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
    private fun safe(block: () -> Any?): String = try { (block() ?: "").toString() } catch (e: Exception) {
        // české hlášky (ToolError, require s českým textem) projdou; anglický text cizí knihovny laik nepotřebuje vidět
        val m = e.message.orEmpty()
        val shown = if (e is cz.axiom.vega.core.ToolError || Regex("[ěščřžýáíéůúňťďĚŠČŘŽÝÁÍÉŮÚŇŤĎ]").containsMatchIn(m)) m
            else { android.util.Log.w("VEGA", "most: ${e.javaClass.simpleName}: ${if (cz.axiom.vega.core.Mem.looksSecret(m)) "[skryto]" else m.take(120)}"); "Tohle se teď nepovedlo – zkus to prosím znovu. (${e.javaClass.simpleName})" }
        JSONObject().put("error", shown).toString()
    }
    private fun params(json: String): Map<String, String> = runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrDefault(emptyMap())

    @JavascriptInterface fun home() = safe { ops.home() }
    @JavascriptInterface fun tools() = safe { ops.toolsJson() }
    @JavascriptInterface fun chats() = safe { ops.chats() }
    @JavascriptInterface fun deleteChat(id: String) = safe { ops.deleteChat(id); true }
    @JavascriptInterface fun editLast(id: String) = safe { ops.takeLastUser(id) ?: "" }
    @JavascriptInterface fun regenerate(id: String) = safe { ops.regenerate(id) }
    @JavascriptInterface fun searchChats(q: String) = safe { ops.searchChats(q.take(100)) }
    @JavascriptInterface fun newsRunNow() = safe { Thread { val r = runCatching { ops.newsDigest(manual = true) }.getOrElse { Ops.NewsResult(false, "Nepovedlo se: ${it.javaClass.simpleName}", null) }
        a.send("newsDone", JSONObject().put("ok", r.ok).put("text", r.text).put("chat", r.chat ?: "")) }.start(); true }
    @JavascriptInterface fun newsExact() = safe { JSONObject().put("exact", NewsAlarms.exactAllowed(a)) }
    @JavascriptInterface fun newsAllowExact() = safe { if (android.os.Build.VERSION.SDK_INT >= 31) a.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName))); true }
    @JavascriptInterface fun renameChat(id: String, title: String) = safe { ops.renameChat(id, title); true }
    // řetězcové výsledky jako JSON (JS je parsuje – holý text by se zahodil)
    @JavascriptInterface fun newChat() = safe { JSONObject.quote(ops.newChat()) }
    @JavascriptInterface fun messages(chat: String) = safe { ops.messages(chat) }
    @JavascriptInterface fun dropAttachment(id: String) = safe { ops.dropAttachment(id); true }
    @JavascriptInterface fun indexDocs() = safe { ops.indexDocs() }
    @JavascriptInterface fun indexRemove(id: String) = safe { ops.indexRemove(id); true }
    @JavascriptInterface fun chatRev(chat: String): String = runCatching { ops.chatRev(chat) }.getOrDefault("")
    @JavascriptInterface fun send(chat: String, text: String, ids: String) = safe {
        val f = JSONArray(ids).let { a -> (0 until a.length()).map { a.getString(it) } }
        require(chat != "temp" || f.isEmpty()) { "V dočasném chatu nelze odeslat přílohu. Otevři běžnou konverzaci." }
        if (chat == "temp") ops.chatTemp(text, f) else if (text.length > 6000) ops.longTask(chat, text, f) else ops.chat(chat, text, f)
        true
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
    @JavascriptInterface fun memoryClear() = safe { ops.memoryClear() }
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
    @JavascriptInterface fun wifiScan(chat: String) = safe { a.wifiScan(chat.takeIf { it != "temp" }); true }
    @JavascriptInterface fun nfc(chat: String) = safe { a.nfcStart(chat.takeIf { it != "temp" }) }
    // --- Hardware: virtuální karty VEGA ---
    @JavascriptInterface fun hwJson() = safe { ops.hwJson() }
    @JavascriptInterface fun hwCapabilities() = safe { a.hwCapabilities() }
    @JavascriptInterface fun hwSaveCard(json: String) = safe { JSONObject.quote(ops.hwSaveCard(json)) }
    @JavascriptInterface fun hwDeleteCard(id: String) = safe { ops.hwDeleteCard(id); true }
    @JavascriptInterface fun hwActivate(id: String) = safe { JSONObject.quote(ops.hwActivate(id)) }
    @JavascriptInterface fun hwDeactivate() = safe { ops.hwDeactivate(); true }
    @JavascriptInterface fun nfcReadCard(chat: String) = safe { a.nfcStart(chat.takeIf { it != "temp" }, "card"); true }
    @JavascriptInterface fun nfcWrite(chat: String, kind: String, content: String) = safe { require(content.isNotBlank()) { "Napiš, co se má zapsat." }; a.nfcWriteStart(chat.takeIf { it != "temp" }, kind, content); true }
    // --- Hardware: WiFi diagnostika a QR ---
    @JavascriptInterface fun wifiLink(chat: String) = safe { a.wifiLink(chat.takeIf { it != "temp" }); true }
    @JavascriptInterface fun wifiQr(ssid: String, password: String, type: String, hidden: Boolean) = safe { a.wifiQr(ssid, password, type, hidden) }
    @JavascriptInterface fun wifiCheckPw(pw: String, ssid: String) = safe { cz.axiom.vega.core.WifiTools.checkPassword(pw, ssid.ifBlank { null }).let { JSONObject().put("score", it.score).put("level", it.level).put("reason", it.reason) } }
    @JavascriptInterface fun wifiGenPw() = safe { JSONObject.quote(cz.axiom.vega.core.WifiTools.passphrase()) }
    @JavascriptInterface fun hwSaveMeasure(json: String) = safe { JSONObject.quote(ops.hwSaveMeasure(json)) }
    // --- Hardware: BLE ---
    @JavascriptInterface fun bleScan(chat: String) = safe { a.bleScan(chat.takeIf { it != "temp" }); true }
    // --- Hardware: volitelný root (libsu) ---
    @JavascriptInterface fun rootVerify() = safe { RootAccess.verify { r -> a.send("root", JSONObject().put("ok", r.ok).put("message", r.message)) }; true }
    @JavascriptInterface fun rootDisable() = safe { RootAccess.disable(); true }
    @JavascriptInterface fun rootState() = safe { JSONObject().put("allowed", RootAccess.isAllowed()) }
    @JavascriptInterface fun nfcSettings() = safe { a.openNfcSettings() }
    @JavascriptInterface fun demand(chat: String, json: String) = safe { ops.demand(chat.takeIf { it != "temp" }, JSONObject(json)) }
    @JavascriptInterface fun share(name: String) = safe { a.share(name) }
    /** Kopírovat / sdílet text odpovědi (laik chce napsaný e-mail vložit jinam). Délka omezená – schránka nesnese megabajty. */
    @JavascriptInterface fun copyText(text: String) = safe { a.runOnUiThread { (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("VEGA", text.take(200_000))) }; true }
    @JavascriptInterface fun shareText(text: String) = safe { a.runOnUiThread { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(100_000)), "Sdílet odpověď")) }; true }
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
            "selfUpdate" -> s.selfUpdate = value == "true"
            "news" -> { s.newsEnabled = value == "true"; a.newsChanged() }
            "newsSrc" -> { val (id, on) = value.split(':').let { it[0] to (it.getOrNull(1) == "true") }; if (cz.axiom.vega.news.News.source(id) != null) s.newsSources = if (on) s.newsSources + id else s.newsSources - id }
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
            "preferredProvider" -> { require(value.isBlank() || value == "own" || value in ops.engines.PROVIDERS) { "Neznámý poskytovatel." }; s.preferredProvider = value }
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
    @JavascriptInterface fun providerChoices() = safe { ops.engines.choices() }
    @JavascriptInterface fun testOwn() = safe { require(!ops.settings.airgap) { "Je zapnutý režim Bez sítě – vypneš ho nahoře v „VEGA ▾“." }; Thread { val c = ops.engines.testOwn(); a.send("keycheck", JSONObject().put("provider", "own").put("ok", c.ok).put("message", c.message).put("model", c.model ?: "")) }.start() }
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
    @JavascriptInterface fun voice(data: String) = safe { a.voice(data) }
    @JavascriptInterface fun pairCode(code: String) = safe { cz.axiom.vega.llm.Pairing.parse(code).let { JSONObject().put("url", it.url).put("model", it.model) } }
    @JavascriptInterface fun verifierInstall() = safe { a.pickVerifierZip() }
    @JavascriptInterface fun verifierDownload() = safe { ops.downloadVerifier(); true }
    @JavascriptInterface fun verifierRemove() = safe { ops.removeVerifier(); true }
    @JavascriptInterface fun exportRetry(chat: String, run: String) = safe { ops.exportRetry(chat.takeIf { it != "temp" }, run) }
}
