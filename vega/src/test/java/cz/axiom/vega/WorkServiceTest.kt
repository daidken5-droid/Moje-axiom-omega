package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hlášení uživatele 25. 11.: „VEGA se stopne v odpovídání, když se telefon zamkne nebo obrazovka zčerná“ – odpověď běžela jen ve vlákně
 * aplikace, kterou Android po zhasnutí obrazovky zmrazí. Nově: po dobu práce služba v popředí + částečný wake lock, po dokončení sama skončí.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkServiceTest {
    @Test fun opsReportsWorkStartAndEnd() {
        val h = OpsHarness(airgap = true)
        val seen = CopyOnWriteArrayList<Boolean>(); h.o.onWork = { seen += it }
        val c = h.o.newChat(); h.say(c, "kolik je 12 × 250 Kč?")
        val end = System.currentTimeMillis() + 5_000; while (System.currentTimeMillis() < end && seen.lastOrNull() != false) Thread.sleep(10)
        assertEquals(listOf(true, false), seen.toList())
    }

    @Test fun serviceRunsInForegroundWithWakeLockAndStopsWhenIdle() {
        var working = true
        WorkService.isWorking = { working }
        val ctl = Robolectric.buildService(WorkService::class.java).create().startCommand(0, 1)
        val s = ctl.get()
        assertNotNull("služba v popředí musí mít oznámení", shadowOf(s).lastForegroundNotification)
        assertTrue("wake lock po dobu práce", s.wakeHeld())
        working = false
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue("po dokončení práce se služba sama ukončí", shadowOf(s).isStoppedBySelf)
        ctl.destroy()
        assertTrue("wake lock uvolněn", !s.wakeHeld())
    }
}
