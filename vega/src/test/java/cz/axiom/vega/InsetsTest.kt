package cz.axiom.vega

import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uživatel 23. 11. (snímek z telefonu): hlavička pod stavovým řádkem – na ☰ a „VEGA ▾“ nešlo klepnout. WebView padding ignoruje. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = VegaApp::class)
class InsetsTest {
    @Test fun webViewSitsBelowStatusBarAndAboveGestureBar() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as ViewGroup
        val web = (0 until root.childCount).map { root.getChildAt(it) }.first { it is WebView }
        val ins = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 96, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48)).build()
        ViewCompat.dispatchApplyWindowInsets(root, ins)
        assertTrue("WebView je v obalovém kontejneru", root !== web)
        assertEquals("kontejner odsazený pod stavový řádek", 96, root.paddingTop)
        assertEquals("a nad lištu gest", 48, root.paddingBottom)
        // klávesnice vyšší než lišta gest → odsazení podle klávesnice
        val withIme = WindowInsetsCompat.Builder(ins).setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 800)).build()
        ViewCompat.dispatchApplyWindowInsets(root, withIme)
        assertEquals(800, root.paddingBottom)
    }

    /** Noční kolo N10: velké systémové písmo se přenese do WebView (textZoom); CSS je ověřené auditem při 130 % a 200 %. */
    @Test fun systemFontScaleReachesWebView() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val web = (0 until root.childCount).map { root.getChildAt(it) }.first { it is WebView } as WebView
        assertEquals(130, web.settings.textZoom)
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }
}
