package cz.axiom.omega

import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.axiom.omega.bridge.OmegaBridge
import cz.axiom.omega.data.Db
import cz.axiom.omega.jobs.OmegaService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CRUCIBLE – běhové testy na skutečném Androidu (emulátor v CI): celá cesta UI → most → služba → reflex → DB.
 * Mozek ani cloud tu nejsou (kontrolované prostředí) → testuje se modul bez mozku a orchestrace, ne kvalita LLM.
 */
@RunWith(AndroidJUnit4::class)
class CrucibleRuntimeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Aplikace při startu žádá o notifikace (Android 13+) – systémový dialog by překryl aktivitu (CI běh #1: PAUSED). */
    @get:Rule val notifications: GrantPermissionRule =
        if (android.os.Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private fun <T> field(a: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(50) }
        throw AssertionError("Nesplněno do $timeoutMs ms: $what")
    }

    private fun pageReady(s: ActivityScenario<MainActivity>): Boolean { var r = false; s.onActivity { r = field(it, "pageReady") }; return r }

    private fun js(s: ActivityScenario<MainActivity>, code: String): String {
        val latch = CountDownLatch(1); var out = ""
        s.onActivity { field<WebView>(it, "web").evaluateJavascript(code) { v -> out = v ?: ""; latch.countDown() } }
        assertTrue("JS neodpověděl", latch.await(10, TimeUnit.SECONDS)); return out
    }

    /** Pošle dotaz přes most (stejně jako UI) a počká na odpověď v databázi. Vrací (text, ms). */
    private fun ask(s: ActivityScenario<MainActivity>, text: String, timeoutMs: Long = 20_000): Pair<String, Long> {
        waitFor(30_000, "volná linka dotazů") { !OmegaService.RUNNING_ASK.get() }
        var bridge: OmegaBridge? = null
        s.onActivity { bridge = field(it, "bridge") }
        val t0 = System.currentTimeMillis()
        val thread = bridge!!.send(JSONObject().put("text", text).put("mode", "auto").toString())
        assertTrue("send vrátil $thread", thread > 0)
        var answer = ""
        try {
            waitFor(timeoutMs, "odpověď na „$text“") {
                val arr = Db.get(ctx).messages(thread)
                (0 until arr.length()).map { arr.getJSONObject(it) }.lastOrNull { it.getString("role") == "assistant" }?.getString("content")?.also { answer = it } != null
            }
        } catch (e: AssertionError) {
            // DIAGNOSTIKA: co se dělo, když odpověď nepřišla (běží služba? v jaké fázi? co je ve vlákně?)
            val omega = Omega.get(ctx)
            throw AssertionError("${e.message} · RUNNING_ASK=${OmegaService.RUNNING_ASK.get()} · fáze=„${omega.job.phase}“ · " +
                "zprávy=${Db.get(ctx).messages(thread).toString().take(600)}")
        }
        return answer to (System.currentTimeMillis() - t0)
    }

    @Test fun coldStartRendersUi() {
        val t0 = System.currentTimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitFor(30_000, "WebView načteno") { pageReady(s) }
            waitFor(10_000, "UI vykresleno") { js(s, "!!document.querySelector('#main .wrap')") == "true" }
            println("CRUCIBLE coldStartToUiMs=${System.currentTimeMillis() - t0}")
            Log.i("CRUCIBLE", "coldStartToUiMs=${System.currentTimeMillis() - t0}")
            // žádná chyba JS při startu
            assertEquals("true", js(s, "typeof window.Omega === 'object'"))
        }
    }

    @Test fun noBrainAnswersEndToEnd() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitFor(30_000, "WebView načteno") { pageReady(s) }
            val cases = listOf("kolik je 7 krát 8" to "56", "kolik je 15 % z 2400" to "360", "kolik metrů je 5 km" to "000",
                "prosím tě, sleva 20 % z 8100" to "6480", "spočítej 629 : 3" to "209", "-13 °C na fahrenheity" to "8,6")
            val lat = mutableListOf<Long>()
            for ((q, expect) in cases) {
                val (a, ms) = ask(s, q); lat += ms
                assertTrue("„$q“ → „$a“ (čekáno $expect)", a.replace(" ", "").replace(" ", "").contains(expect.replace(" ", "")))
            }
            // latence celé cesty (most → služba v popředí → reflex → DB → událost), 20 dotazů
            repeat(14) { i -> lat += ask(s, "kolik je ${i + 2} krát ${i + 3}").second }
            lat.sort()
            println("CRUCIBLE noBrainEndToEndMs p50=${lat[lat.size / 2]} p95=${lat[lat.size * 95 / 100]} max=${lat.last()} n=${lat.size}")
            Log.i("CRUCIBLE", "noBrainEndToEndMs p50=${lat[lat.size / 2]} p95=${lat[lat.size * 95 / 100]} max=${lat.last()} n=${lat.size}")
        }
    }

    @Test fun answerSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitFor(30_000, "WebView načteno") { pageReady(s) }
            val (a, _) = ask(s, "kolik je 12 krát 12")
            assertTrue(a, a.contains("144"))
            val before = Db.get(ctx).threads().length()
            s.recreate() // změna konfigurace (otočení, tmavý režim…)
            waitFor(30_000, "WebView po znovuvytvoření") { pageReady(s) }
            assertEquals("vlákna se po znovuvytvoření neztratí ani nezdvojí", before, Db.get(ctx).threads().length())
            assertTrue(ask(s, "kolik je 9 krát 9").first.contains("81"))
        }
    }

    @Test fun offlineWithoutBrainNeverHangs() {
        // otázka pro mozek, ale mozek ani cloud nejsou → AVIOR musí odpovědět (offline odpověď / vysvětlení), ne viset
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitFor(30_000, "WebView načteno") { pageReady(s) }
            val (a, ms) = ask(s, "napiš mi básničku o podzimu", timeoutMs = 90_000)
            println("CRUCIBLE noEnginePathMs=$ms")
            Log.i("CRUCIBLE", "noEnginePath ms=$ms odpověď=${a.take(120)}")
            assertTrue("prázdná odpověď", a.isNotBlank())
        }
    }
}
