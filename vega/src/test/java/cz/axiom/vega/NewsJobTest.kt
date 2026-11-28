package cz.axiom.vega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Ranní přehled bez přesných budíků (Android 14+ je novým instalacím nepovoluje): pojistná úloha s podmínkou sítě od 05:20,
 * kterou budík 05:30 nepřeplánuje; oznámení s hotovým přehledem jen jednou za den.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewsJobTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val js get() = ctx.getSystemService(android.app.job.JobScheduler::class.java)
    private val evening = LocalDateTime.parse("2026-11-25T21:00:00").atZone(cz.axiom.vega.news.News.ZONE)

    @Test fun jobScheduledWithNetworkFrom0520AndSurvives0530Reschedule() {
        NewsAlarms.schedule(ctx, true, evening)
        val j = js.allPendingJobs.firstOrNull { it.id == NewsAlarms.JOB_ID }; assertNotNull("pojistná úloha naplánovaná", j); j!!
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, j.networkType)
        assertEquals(8 * 3600_000L + 20 * 60_000L, j.minLatencyMillis)  // 21:00 → 05:20
        assertTrue(j.isPersisted)
        // budík 05:30 přeplánuje budíky na zítřek – čekající pojistná úloha na dnešek musí zůstat
        NewsAlarms.schedule(ctx, true, LocalDateTime.parse("2026-11-26T05:30:05").atZone(cz.axiom.vega.news.News.ZONE))
        assertEquals(j.minLatencyMillis, js.allPendingJobs.first { it.id == NewsAlarms.JOB_ID }.minLatencyMillis)
        NewsAlarms.schedule(ctx, false, evening)
        assertTrue(js.allPendingJobs.none { it.id == NewsAlarms.JOB_ID })
    }

    @Test fun successfulDigestNotifiedOnlyOnce() {
        val o = Ops(ctx) { _, _ -> }
        val day = java.time.LocalDate.of(2026, 11, 26)
        assertFalse(o.newsNotified(day))
        assertTrue(NewsAlarms.notifyOnce(ctx, o, day, Ops.NewsResult(true, "3 témata", "c1")))
        assertFalse("druhé oznámení téhož přehledu ne", NewsAlarms.notifyOnce(ctx, o, day, Ops.NewsResult(true, "3 témata", "c1")))
        // neúspěch neoznačí den jako hotový – pozdější úspěch se ještě oznámí
        val d2 = day.plusDays(1)
        assertTrue(NewsAlarms.notifyOnce(ctx, o, d2, Ops.NewsResult(false, "nešlo stáhnout", null)))
        assertTrue(NewsAlarms.notifyOnce(ctx, o, d2, Ops.NewsResult(true, "2 témata", "c2")))
    }
}
