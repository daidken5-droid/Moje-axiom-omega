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
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(web)
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

    private fun handleShare(i: Intent?) {
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
}
