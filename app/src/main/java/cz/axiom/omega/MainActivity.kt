package cz.axiom.omega

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import cz.axiom.omega.bridge.OmegaBridge
import org.json.JSONObject

/**
 * Jediná aktivita: WebView s lokálním UI (assets/ui) servírovaným přes WebViewAssetLoader
 * na https://appassets.androidplatform.net – žádný vzdálený obsah, žádný přístup k souborům.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: OmegaBridge
    private var pageReady = false
    private val pending = ArrayList<String>()

    private val forward: (String, JSONObject) -> Unit = { type, data -> dispatch(type, data) }

    val pickText = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> bridge.onTextFiles(uris) }
    val pickBrain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(bridge::onBrainFile) }
    val pickMemory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(bridge::onMemoryFile) }
    val exportDb = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri -> uri?.let(bridge::onExport) }
    val importDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(bridge::onRestore) }
    /** Příloha k dotazu (textový soubor) – obsah se vloží do dotazu, ne do znalostí. */
    val pickAttach = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(bridge::onAttach) }
    val pickLora = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(bridge::onLoraFile) }
    /** Hlasové zadávání: systémové rozpoznávání řeči (offline, pokud má telefon stažený český balík). */
    val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) cz.axiom.omega.Events.emit("voice", org.json.JSONObject().put("text", text))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge všude stejně: okraje (lišty i klávesnici) řešíme sami přes insets → žádné dvojité odsazení
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor("#05060A")
        window.navigationBarColor = Color.parseColor("#05060A")

        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#05060A"))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.textZoom = 100
        }
        bridge = OmegaBridge(this)
        web.addJavascriptInterface(bridge, "Omega")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                if (u.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pending.forEach { web.evaluateJavascript(it, null) }
                pending.clear()
                handleShare(intent)
            }
        }
        // WebView vnitřní odsazení (padding) ignoruje – obsah by se kreslil pod stavový řádek (hodiny, baterie)
        // a pod gesta. Proto ho zabalíme do kontejneru, který dostane odsazení podle systémových lišt,
        // výřezu kamery (Pixel 9a) a klávesnice.
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05060A"))
            addView(web, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.omegaBack ? omegaBack() : false") { handled ->
                    if (handled != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
    }

    override fun onStart() {
        super.onStart()
        Events.listen(forward)
        dispatch("resume", JSONObject())
        // samostudium otevřených otázek, když se telefon nabíjí na Wi-Fi
        window.decorView.postDelayed({ runCatching { bridge.maybeSelfStudy() } }, 4_000)
        window.decorView.postDelayed({ runCatching { bridge.checkUpdate(false) } }, 6_000)
        publishShortcuts()
        // předehřátí mozku: načíst do paměti hned, ne až při prvním dotazu
        window.decorView.postDelayed({ runCatching { Omega.get(this).warmup() } }, 1_500)
    }

    override fun onStop() {
        Events.unlisten(forward)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (pageReady) handleShare(intent)
    }

    /** Widget na ploše a zkratky ikony: zeptat se, hlasem, Sherlock, Vědomí. */
    private fun handleQuick(i: Intent?): Boolean {
        val a = i?.action ?: return false
        val w = cz.axiom.omega.widget.AviorWidget
        val what = when (a) {
            w.ACTION_ASK -> "ask"; w.ACTION_VOICE -> "voice"; w.ACTION_SELF -> "self"; ACTION_SHERLOCK -> "sherlock"
            else -> return false
        }
        if (what == "sherlock") Omega.get(this).settings.power = "SHERLOCK"
        dispatch("quick", JSONObject().put("what", what))
        if (what == "voice") bridge.voice()
        i.action = null
        return true
    }

    /** Dynamické zkratky (dlouhé podržení ikony) – fungují i s balíčkem .debug. */
    private fun publishShortcuts() = runCatching {
        fun sc(id: String, label: Int, action: String) = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(getString(label))
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(action)).build()
        val w = cz.axiom.omega.widget.AviorWidget
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
            sc("ask", R.string.shortcut_ask, w.ACTION_ASK), sc("voice", R.string.shortcut_voice, w.ACTION_VOICE),
            sc("sherlock", R.string.shortcut_sherlock, ACTION_SHERLOCK), sc("self", R.string.shortcut_vedomi, w.ACTION_SELF),
        ))
    }

    private fun handleShare(i: Intent?) {
        if (handleQuick(i)) return
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            dispatch("shared", JSONObject().put("text", text.take(16_000)))
            i.action = null
        }
    }

    fun dispatch(type: String, data: JSONObject) {
        val js = "window.omegaReceive && omegaReceive(${JSONObject.quote(type)}, $data)"
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (pageReady) web.evaluateJavascript(js, null) else pending += js
        }
    }

    fun openExternal(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https") return
        runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) } }
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("Omega")
        web.destroy()
        super.onDestroy()
    }

    companion object { const val ACTION_SHERLOCK = "cz.axiom.omega.SHERLOCK" }
}
