package cz.axiom.omega

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import cz.axiom.omega.engine.LocalEngine

class OmegaApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
        // Při nedostatku paměti uvolni lokální mozek (pokud zrovna nepřemýšlí); znovu se načte při dalším dotazu.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE && !Omega.get(this).job.busy) LocalEngine.release()
        // Sémantický embedder (~0,6 GB) jde pryč dřív – je levné ho načíst znovu
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !Omega.get(this).job.busy) cz.axiom.omega.engine.Embedder.close()
    }

    companion object {
        const val CHANNEL_WORK = "omega-work"
    }
}
