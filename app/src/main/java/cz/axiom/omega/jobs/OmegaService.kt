package cz.axiom.omega.jobs

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.axiom.omega.Events
import cz.axiom.omega.MainActivity
import cz.axiom.omega.Omega
import cz.axiom.omega.OmegaApp
import cz.axiom.omega.R
import cz.axiom.omega.core.agent.AskListener
import cz.axiom.omega.core.agent.AskRequest
import cz.axiom.omega.core.agent.AskResult
import cz.axiom.omega.core.agent.PassTrace
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.CancelledException
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.Delta
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.Role
import cz.axiom.omega.core.randy.RandyLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground služba pro myšlení a stahování mozku. Android ji nezabije při zhasnutí displeje
 * a uživatel vidí stav v oznámení (s tlačítkem Zastavit).
 *
 * AXIOM 2.1: jediný typ FGS "specialUse" i pro stahování, bez wake/wifi locku → stahování
 * se při zhasnutí displeje zastavovalo; nevyčistila se notifikace po chybě.
 */
class OmegaService : Service() {

    private val omega by lazy { Omega.get(this) }
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_CANCEL) {
            current?.cancel()
            if (!RUNNING.get()) stopSelf()
            return START_NOT_STICKY
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Events.note("Právě probíhá jiná úloha – počkej nebo ji zastav.")
            return START_NOT_STICKY
        }
        val download = intent.action == ACTION_DOWNLOAD || intent.action == ACTION_IMPORT
        val type = if (download) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Připravuji…", -1), type)
        acquireLocks(download)

        val cancel = CancelToken().also { current = it }
        Thread({
            try {
                when (intent.action) {
                    ACTION_ASK -> ask(intent, cancel)
                    ACTION_DOWNLOAD -> download(intent.getStringExtra("id")!!, cancel)
                    ACTION_IMPORT -> import(intent.getParcelableExtraCompat("uri")!!, cancel)
                }
            } catch (e: Throwable) {
                omega.job.phase = "Chyba"
                Events.emit("error", JSONObject().put("message", e.message ?: e.javaClass.simpleName))
            } finally {
                omega.job.busy = false
                RUNNING.set(false)
                current = null
                releaseLocks()
                Events.emit("state", omega.job.toJson())
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "omega-job").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ dotaz

    private fun ask(intent: Intent, cancel: CancelToken) {
        val threadId = intent.getLongExtra("threadId", 0)
        val text = intent.getStringExtra("text").orEmpty()
        val mode = intent.getStringExtra("mode") ?: omega.settings.mode
        val forced = RandyLevel.fromCode(intent.getStringExtra("level"))
        val job = omega.job
        job.reset("ask", threadId)
        Events.emit("state", job.toJson())
        omega.refreshRatesIfStale()

        val history = omega.db.messages(threadId, 40).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.getString("role") in setOf("user", "assistant") && it.optJSONObject("meta")?.optBoolean("error") != true }
                .map { ChatMessage(if (it.getString("role") == "user") Role.USER else Role.ASSISTANT, it.getString("content")) }
        }.dropLast(1) // poslední je právě odeslaný dotaz

        val s = omega.settings
        var lastEmit = 0L
        val listener = object : AskListener {
            override fun onPhase(text: String) {
                job.phase = text
                updateNotification(text, -1)
                Events.emit("phase", JSONObject().put("text", text))
            }

            override fun onDelta(pass: Int, totalPasses: Int, delta: Delta) {
                if (pass != job.pass) { job.partial.setLength(0); job.reasoning.setLength(0) }
                job.pass = pass; job.passes = totalPasses
                if (delta.reasoning) job.reasoning.append(delta.text) else job.partial.append(delta.text)
                val now = System.currentTimeMillis()
                if (now - lastEmit > 60) {
                    lastEmit = now
                    Events.emit("stream", JSONObject().put("pass", pass).put("passes", totalPasses)
                        .put("text", job.partial.toString()).put("reasoning", job.reasoning.toString().takeLast(2000)))
                }
            }

            override fun onPassComplete(trace: PassTrace) {
                Events.emit("pass", JSONObject().put("index", trace.index).put("label", trace.pass.label).put("millis", trace.millis))
            }
        }

        try {
            val result: AskResult
            if (mode == "council") {
                val (members, judge) = omega.councilMembers()
                result = omega.orchestrator().council(
                    AskRequest(text, history, forced, 1, s.reflect, s.web, s.thinking, true, s.rates, s.userName.ifBlank { null }),
                    members, judge, cancel, listener,
                )
            } else {
                val engine = omega.resolve(mode)
                listener.onPhase("Probouzím ${engine.label}…")
                val passes = if (engine.kind == EngineKind.LOCAL) s.localMaxPasses else s.maxPasses
                result = omega.orchestrator().run(
                    AskRequest(text, history, forced, passes, s.reflect, s.web && engine.kind == EngineKind.CLAUDE, s.thinking, true, s.rates, s.userName.ifBlank { null }),
                    engine, cancel, listener,
                )
            }
            omega.rememberConfidence(result.confidence)
            val meta = metaOf(result)
            val id = omega.db.addMessage(threadId, "assistant", result.answer, meta)
            Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", result.answer).put("meta", meta))
        } catch (e: CancelledException) {
            val partial = job.partial.toString().trim()
            if (partial.isNotEmpty()) {
                omega.db.addMessage(threadId, "assistant", "$partial\n\n[Zastaveno.]", JSONObject().put("stopped", true).put("engine", job.phase))
            }
            Events.emit("done", JSONObject().put("threadId", threadId).put("stopped", true))
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            omega.db.addMessage(threadId, "assistant", msg, JSONObject().put("error", true))
            Events.emit("error", JSONObject().put("threadId", threadId).put("message", msg))
        }
    }

    private fun metaOf(r: AskResult): JSONObject = JSONObject()
        .put("level", r.level.code).put("levelTitle", r.level.title).put("levelExplicit", r.levelExplicit)
        .put("engine", r.engineLabel).put("model", r.model).put("millis", r.millis)
        .put("confidence", r.confidence ?: JSONObject.NULL).put("lesson", r.lesson ?: JSONObject.NULL)
        .put("facts", JSONArray().apply { r.facts.forEach { put(it.toString()) } })
        .put("sources", JSONArray().apply { r.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
        .put("snippets", JSONArray().apply { r.snippets.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("source", it.source)) } })
        .put("memories", JSONArray().apply { r.memoriesUsed.forEach { put(it.text) } })
        .put("notes", JSONArray().apply { r.memoryNotes.forEach { put(it) } })
        .put("trace", JSONArray().apply {
            r.trace.forEach { put(JSONObject().put("index", it.index).put("label", it.pass.label).put("millis", it.millis).put("model", it.model).put("text", it.text.take(20_000))) }
        })
        .put("reasoning", r.reasoning.take(8_000))

    // ------------------------------------------------------------------ mozky

    private fun download(id: String, cancel: CancelToken) {
        val job = omega.job
        job.reset("download")
        val spec = omega.brains.catalog().first { it.id == id }
        job.detail = spec.name
        try {
            omega.brains.download(id, cancel) { p ->
                job.progress = p.percent
                job.phase = "${p.phase} · ${spec.name}"
                val speed = if (p.bytesPerSec > 0) " · ${"%.1f".format(p.bytesPerSec / 1e6)} MB/s" else ""
                job.detail = "${"%.2f".format(p.bytes / 1e9)} / ${"%.2f".format(p.total / 1e9)} GB$speed"
                updateNotification("${p.phase} ${p.percent} % · ${job.detail}", p.percent)
                Events.emit("progress", job.toJson())
            }
            Events.emit("brains", omega.brains.toJson())
            Events.note("Mozek ${spec.name} je stažený, ověřený (SHA-256) a aktivní.")
        } catch (e: CancelledException) {
            Events.note("Stahování pozastaveno. Příště naváže tam, kde skončilo.")
            Events.emit("brains", omega.brains.toJson())
        }
    }

    private fun import(uri: Uri, cancel: CancelToken) {
        val job = omega.job
        job.reset("import")
        omega.brains.import(uri, cancel) { bytes ->
            job.detail = "%.2f GB".format(bytes / 1e9)
            job.phase = "Importuji mozek"
            Events.emit("progress", job.toJson())
        }
        Events.emit("brains", omega.brains.toJson())
        Events.note("Vlastní mozek importován a aktivován.")
    }

    // ------------------------------------------------------------------ systém

    private fun notification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OmegaService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, OmegaApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_omega)
            .setContentTitle("AXIOM Ω")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Zastavit", stop)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private var lastNotif = 0L
    private fun updateNotification(text: String, progress: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 1000) return
        lastNotif = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, progress))
    }

    private fun acquireLocks(download: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omega:job").apply { acquire(if (download) 4 * 3_600_000L else 30 * 60_000L) }
        if (download) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(mode, "omega:download").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        wake = null; wifi = null
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)

    companion object {
        const val ACTION_ASK = "cz.axiom.omega.ASK"
        const val ACTION_DOWNLOAD = "cz.axiom.omega.DOWNLOAD"
        const val ACTION_IMPORT = "cz.axiom.omega.IMPORT"
        const val ACTION_CANCEL = "cz.axiom.omega.CANCEL"
        private const val NOTIF_ID = 7
        val RUNNING = AtomicBoolean(false)
        @Volatile private var current: CancelToken? = null

        fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent.setClass(context, OmegaService::class.java))
        }

        fun cancel(context: Context) {
            current?.cancel()
            context.startService(Intent(context, OmegaService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
