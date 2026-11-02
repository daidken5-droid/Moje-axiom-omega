package cz.axiom.omega

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import cz.axiom.omega.engine.LocalEngine

class OmegaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_work_desc)
            },
        )
        // Offline znalostní balíčky z APK → lokální databáze (bez internetu, jen poprvé / po aktualizaci)
        Thread({
            runCatching { Omega.get(this).db.failStaleTasks() }
            runCatching { Omega.get(this).packs.ensureAutoInstalled() }
        }, "omega-packs").start()
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val o = Omega.get(this)
        // Nikdy neuvolňovat nativní paměť, kterou právě něco používá (předehřátí, dotaz, indexace) –
        // dřív to vedlo k pádu aplikace uprostřed odpovědi.
        val busy = o.warming || cz.axiom.omega.jobs.OmegaService.RUNNING_ASK.get() || cz.axiom.omega.jobs.OmegaService.RUNNING_BG.get()
        if (busy) return
        // Mozek držený v paměti (služba „AVIOR připraven“) uvolnit jen v krajní nouzi
        val brainLevel = if (o.settings.keepBrain) ComponentCallbacks2.TRIM_MEMORY_COMPLETE else ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (level >= brainLevel) LocalEngine.releaseIfIdle()
        // Sémantický embedder (~0,6 GB) jde pryč dřív – je levné ho načíst znovu
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cz.axiom.omega.engine.Embedder.close()
    }

    /** Zapíše pád v Javě/Kotlinu do souboru – při dalším spuštění ho AVIOR ukáže a přizpůsobí se. */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText("${System.currentTimeMillis()}\nvlákno ${t.name}\n${sw.toString().take(6000)}")
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CHANNEL_WORK = "omega-work"
    }
}
