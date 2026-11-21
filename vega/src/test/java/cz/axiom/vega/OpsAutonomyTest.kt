package cz.axiom.vega

import cz.axiom.vega.core.SelfTune
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Autonomie v aplikaci: deník slabin napojený na chat a tlačítko „Navrhnout rozšíření“ (air-gap = bez modelu a sítě). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsAutonomyTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private class NoKeys(c: android.content.Context) : Vault(c) {
        override fun read(name: String): String? = null; override fun has(name: String) = false
        override fun save(name: String, secret: String) {}; override fun remove(name: String) {}
    }
    private val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private fun ops() = Ops(ctx, NoKeys(ctx)) { t, d -> ev += t to d }.also { it.settings.airgap = true }
    private fun waitIdle(from: Int) { val end = System.currentTimeMillis() + 20_000; while (System.currentTimeMillis() < end && ev.drop(from).none { it.first == "idle" }) Thread.sleep(20) }
    private fun say(o: Ops, chat: String, text: String) { val n = ev.size; o.chat(chat, text, emptyList()); waitIdle(n) }

    @Test fun correctionInChatIsJournaledWithResolvedArea() {
        val o = ops(); val chat = o.newChat()
        say(o, chat, "jaký je otisk té fotky")
        say(o, chat, "ne, spočítej sha256 té fotky")
        val s = o.selftune.signals().single { it.kind == "CORRECTION" }
        assertEquals("záměr:Talk", s.area); assertEquals("nástroj:hash", s.resolved)
        assertEquals("jaký je otisk té fotky", s.prevExcerpt)
    }

    @Test fun proposeExtensionChecksConditionsAndIgnoresDoubleTap() {
        val o = ops(); val area = "nástroj:csv.inspect"
        assertNotNull("bez chyb v deníku nic nenavrhuje", o.improvementAction(area, "extPropose"))
        assertNotNull("rozšíření se nenavrhuje k rozšíření", o.improvementAction("nástroj:ext.x", "extPropose"))
        repeat(2) { o.selftune.observe(SelfTune.Kind.ERROR, area, detail = "ToolError: nečekaný oddělovač") }
        // výkonné vlákno chvíli zaměstnat, ať oba „klepnutí“ přijdou před zpracováním prvního
        val n = ev.size; o.submit { Thread.sleep(400) }
        assertNull(o.improvementAction(area, "extPropose"))
        assertTrue(o.improvementAction(area, "extPropose")!!.contains("už běží"))
        waitIdle(n); Thread.sleep(300)
        // návrh v air-gapu selže (bez modelu) – a NESMÍ se připsat nástroji jako další chyba
        assertEquals(2, o.selftune.signals().count { it.area == area && it.kind == "ERROR" })
        assertTrue(o.projects().length() >= 1)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultAndProjectIdTest {
    @Test fun vaultDropsKeyOnlyWhenPermanentlyUnreadable() {
        assertTrue(Vault.dropOn(javax.crypto.AEADBadTagException("tag")))
        assertTrue(Vault.dropOn(android.security.keystore.KeyPermanentlyInvalidatedException()))
        // dočasné chyby Keystore (po restartu / zamčení) klíč NESMÍ smazat
        assertTrue(!Vault.dropOn(java.security.KeyStoreException("busy")))
        assertTrue(!Vault.dropOn(java.security.ProviderException("keystore")))
        assertTrue(!Vault.dropOn(java.security.UnrecoverableKeyException("locked")))
    }

    @Test fun activeProjectIdCannotEscapeProjectsDir() {
        val ctx = RuntimeEnvironment.getApplication()
        val o = Ops(ctx, object : Vault(ctx) { override fun read(name: String): String? = null; override fun has(name: String) = false
            override fun save(name: String, secret: String) {}; override fun remove(name: String) {} }) { _, _ -> }
        for (bad in listOf("..", "../..", "a/b", "A", "")) assertTrue(bad, !o.validProjectId(bad))
        o.settings.activeProject = "../.."
        assertNull("podvržené id = žádný aktivní projekt", o.activeWorkspace())
    }
}
