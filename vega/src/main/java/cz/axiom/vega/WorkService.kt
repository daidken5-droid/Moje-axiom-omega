package cz.axiom.vega

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Rozpracovaná odpověď doběhne i se zhasnutou obrazovkou. Bez toho Android aplikaci na pozadí zmrazí (Pixel / Android 14+ během
 * pár sekund) a volání modelu se zastaví – hlášení uživatele 25. 11. Služba v popředí (typ dataSync) + částečný wake lock (max. 15 min)
 * jen po dobu práce; sama se ukončí, jakmile Ops nepracuje. Nic se tím neposílá jinam – jen se nezastaví to, co už běží.
 */
class WorkService : Service() {
    companion object {
        private const val CHANNEL = "vega_work"
        private const val ID = 531
        private const val WAKE_MAX_MS = 15 * 60_000L
        /** Pracuje VEGA? (VegaApp nastaví na Ops.busy; v testu podvrh.) */
        @Volatile var isWorking: () -> Boolean = { false }

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WorkService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } // spuštění z pozadí může Android odmítnout – práce pak běží jako dřív, jen bez ochrany
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() { if (isWorking()) handler.postDelayed(this, 1_000) else { stopForegroundCompat(); stopSelf() } }
    }

    internal fun wakeHeld() = wake?.isHeld == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wake?.isHeld != true) wake = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VEGA:odpoved")
            ?.apply { setReferenceCounted(false); acquire(WAKE_MAX_MS) }
        handler.removeCallbacks(check); handler.postDelayed(check, 1_000)
        return START_NOT_STICKY
    }

    /** Android 15: limit služby typu dataSync vypršel → skončit čistě (práce se nepřeruší, jen ztratí ochranu). */
    override fun onTimeout(startId: Int, fgsType: Int) { stopForegroundCompat(); stopSelf() }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        super.onDestroy()
    }

    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Rozpracovaná odpověď", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Zobrazí se jen během práce, aby se odpověď nezastavila při zhasnuté obrazovce"; setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(applicationInfo.icon).setContentTitle("VEGA dokončuje odpověď…").setContentText("Můžeš zamknout telefon – odpověď počká v chatu.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
}
