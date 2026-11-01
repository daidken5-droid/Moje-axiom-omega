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
import cz.axiom.omega.core.agent.AgentListener
import cz.axiom.omega.core.agent.AgentStep
import cz.axiom.omega.core.agent.AskListener
import cz.axiom.omega.core.agent.Router
import cz.axiom.omega.core.hub.HfHub
import cz.axiom.omega.core.hub.Web
import cz.axiom.omega.engine.Embedder
import cz.axiom.omega.sys.Introspect
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
        val download = intent.action in setOf(ACTION_DOWNLOAD, ACTION_IMPORT, ACTION_DATASET, ACTION_WIKI, ACTION_LIBRARY)
        val type = if (download) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Připravuji…", -1), type)
        acquireLocks(download)

        val cancel = CancelToken().also { current = it }
        Thread({
            try {
                when (intent.action) {
                    ACTION_ASK -> ask(intent, cancel)
                    ACTION_DOWNLOAD -> download(intent.getStringExtra("id"), intent.getStringExtra("spec"), cancel)
                    ACTION_INDEX -> index(cancel)
                    ACTION_DATASET -> dataset(intent, cancel)
                    ACTION_WIKI -> wiki(intent, cancel)
                    ACTION_LIBRARY -> library(intent.getStringExtra("id")!!, cancel)
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
            val userEngine = mode.takeIf { it != "auto" && it != "council" }
            val userMode = when (intent.getStringExtra("kind")) {
                "agent" -> Router.Mode.AGENT
                "chat" -> Router.Mode.CHAT
                "council" -> Router.Mode.COUNCIL
                else -> if (mode == "council") Router.Mode.COUNCIL else null
            }
            val route = Router.route(text, Router.Context(Introspect.online(this), omega.availableEngines(), userEngine, userMode))
            listener.onPhase("Ω volí: ${route.mode.label} · ${route.reason}")
            val meta: JSONObject
            val answer: String
            when (route.mode) {
                Router.Mode.COUNCIL -> {
                    val (members, judge) = omega.councilMembers()
                    val r = omega.orchestrator().council(
                        AskRequest(route.prompt, history, forced, 1, s.reflect, s.web, s.thinking, true, s.rates, s.userName.ifBlank { null }),
                        members, judge, cancel, listener,
                    )
                    omega.rememberConfidence(r.confidence)
                    meta = metaOf(r); answer = r.answer
                }
                Router.Mode.AGENT -> {
                    val engine = omega.resolve(route.engine)
                    val taskId = omega.db.addTask(threadId, route.prompt, engine.label)
                    job.kind = "agent"
                    val steps = JSONArray()
                    val r = try {
                        omega.agent().run(route.prompt, history, engine, s.agentSteps, cancel, object : AgentListener {
                            override fun onPhase(text: String) = listener.onPhase(text)
                            override fun onDelta(delta: Delta) = listener.onDelta(steps.length() + 1, 0, delta)
                            override fun onStep(step: AgentStep) {
                                val j = JSONObject().put("index", step.index).put("thought", step.thought).put("tool", step.tool ?: JSONObject.NULL)
                                    .put("args", step.args ?: JSONObject.NULL).put("observation", step.observation?.take(1_500) ?: JSONObject.NULL).put("millis", step.millis)
                                steps.put(j)
                                omega.db.updateTask(taskId, "running", steps.length(), null)
                                Events.emit("step", j)
                            }
                        }, s.rates, s.userName.ifBlank { null }, cz.axiom.omega.core.agent.AgentRole.of(intent.getStringExtra("role")))
                    } catch (e: Exception) {
                        omega.db.updateTask(taskId, if (e is CancelledException) "stopped" else "failed", steps.length(), e.message)
                        throw e
                    }
                    omega.db.updateTask(taskId, if (r.finish == "done") "done" else "partial", steps.length(), r.answer)
                    meta = JSONObject().put("mode", "agent").put("engine", r.engineLabel).put("model", r.model).put("millis", r.millis)
                        .put("route", route.reason).put("steps", steps).put("finish", r.finish).put("taskId", taskId)
                    answer = r.answer
                }
                Router.Mode.CHAT -> {
                    val engine = omega.resolve(route.engine)
                    listener.onPhase("Probouzím ${engine.label}…")
                    val passes = if (engine.kind == EngineKind.LOCAL) s.localMaxPasses else s.maxPasses
                    val r = omega.orchestrator().run(
                        AskRequest(route.prompt, history, forced, passes, s.reflect, s.web && engine.kind == EngineKind.CLAUDE, s.thinking, true, s.rates, s.userName.ifBlank { null }),
                        engine, cancel, listener,
                    )
                    omega.rememberConfidence(r.confidence)
                    meta = metaOf(r).put("route", route.reason); answer = r.answer
                }
            }
            val id = omega.db.addMessage(threadId, "assistant", answer, meta)
            Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", answer).put("meta", meta))
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

    private fun download(id: String?, specJson: String?, cancel: CancelToken) {
        val job = omega.job
        job.reset("download")
        val spec = specJson?.let { omega.brains.addCustom(it) } ?: omega.brains.catalog().first { it.id == id }
        job.detail = spec.name
        try {
            omega.brains.download(spec.id, cancel) { p ->
                job.progress = p.percent
                job.phase = "${p.phase} · ${spec.name}"
                val speed = if (p.bytesPerSec > 0) " · ${"%.1f".format(p.bytesPerSec / 1e6)} MB/s" else ""
                job.detail = "${"%.2f".format(p.bytes / 1e9)} / ${"%.2f".format(p.total / 1e9)} GB$speed"
                updateNotification("${p.phase} ${p.percent} % · ${job.detail}", p.percent)
                Events.emit("progress", job.toJson())
            }
            Events.emit("brains", omega.brains.toJson())
            if (spec.kind == "embedding") {
                Events.note("${spec.name} je stažený. Stavím sémantický index…")
                index(cancel)
            } else {
                Events.note("${spec.name} je stažený, ověřený a aktivní.")
            }
        } catch (e: CancelledException) {
            Events.note("Stahování pozastaveno. Příště naváže tam, kde skončilo.")
            Events.emit("brains", omega.brains.toJson())
        }
    }

    /** Sémantický index: vektor pro každý úsek znalostní databáze (OMEGA Sense). */
    private fun index(cancel: CancelToken) {
        val job = omega.job
        job.reset("index")
        if (!omega.ensureEmbedder()) throw IllegalStateException("Nejdřív stáhni rozšíření OMEGA Sense (Moduly).")
        val db = omega.db
        db.pruneVectors()
        val total = db.chunksWithoutVectors(1_000_000).size
        var done = 0
        while (true) {
            cancel.check()
            val batch = db.chunksWithoutVectors(24)
            if (batch.isEmpty()) break
            for ((id, title, body) in batch) {
                cancel.check()
                val v = Embedder.embed("$title\n$body") ?: FloatArray(1)
                db.putVector(id, v)
                done++
            }
            job.progress = if (total > 0) done * 100 / total else 100
            job.phase = "Sémantický index"
            job.detail = "$done / $total úseků"
            updateNotification("Sémantický index $done / $total", job.progress)
            Events.emit("progress", job.toJson())
        }
        Events.emit("kb", JSONObject())
        Events.note("Sémantický index hotov ($done nových úseků). Hledám teď i podle významu.")
    }

    /** Dataset z Hugging Face → offline znalosti. */
    private fun dataset(intent: Intent, cancel: CancelToken) {
        val job = omega.job
        job.reset("download")
        val ds = intent.getStringExtra("dataset")!!
        val count = intent.getIntExtra("count", 200).coerceIn(1, 5_000)
        val query = intent.getStringExtra("query")
        val hub = HfHub()
        val split = intent.getStringExtra("config")?.takeIf { it.isNotBlank() }?.let { cfg -> HfHub.DatasetSplit(cfg, intent.getStringExtra("split") ?: "train") }
            ?: hub.splits(ds).firstOrNull { it.split == "train" } ?: hub.splits(ds).first()
        var added = 0
        var offset = 0
        while (added < count) {
            cancel.check()
            val rows = hub.rows(ds, split.config, split.split, offset, minOf(100, count - added + 20), query)
            if (rows.isEmpty()) break
            for (r in rows) {
                val doc = HfHub.rowToDoc(r, ds) ?: continue
                omega.db.addDocument(doc.first.take(200), doc.second.take(200_000), "hf:$ds")
                added++
                if (added >= count) break
            }
            offset += rows.size
            job.progress = added * 100 / count
            job.phase = "Dataset $ds"
            job.detail = "$added / $count záznamů"
            Events.emit("progress", job.toJson())
            if (rows.size < 20) break
        }
        Events.emit("kb", JSONObject())
        Events.note("Z datasetu $ds uloženo $added záznamů do offline databáze.")
        if (omega.settings.semantic && omega.embedderFile() != null) index(cancel)
    }

    /** Offline knihovna (celá Wikipedie): stránky po 100 článcích, navazování, šetrné tempo, hlídání místa. */
    private fun library(id: String, cancel: CancelToken) {
        val job = omega.job
        job.reset("library")
        val spec = cz.axiom.omega.core.library.Libraries.of(id) ?: error("Neznámá knihovna $id")
        val lib = cz.axiom.omega.data.LibraryDb.get(this)
        val hub = HfHub()
        var (offset, _, done) = lib.progress(id)
        if (done) { Events.note("${spec.name} je už stažená."); return }
        var failures = 0
        var lastNote = 0L
        val started = System.currentTimeMillis()
        val startOffset = offset
        try {
            while (offset < spec.rows) {
                cancel.check()
                if (filesDir.usableSpace < 800_000_000L) error("Dochází místo v telefonu (méně než 0,8 GB). Stahování pozastaveno na $offset článcích.")
                val rows = try {
                    hub.rows(spec.dataset, spec.config, spec.split, offset, 100)
                } catch (e: Exception) {
                    if (cancel.cancelled) throw CancelledException()
                    failures++
                    if (failures > 12) throw e
                    val wait = minOf(120_000L, 3_000L * failures)
                    job.phase = "Knihovna · výpadek, zkusím za ${wait / 1000} s"
                    Events.emit("progress", job.toJson())
                    Thread.sleep(wait)
                    continue
                }
                failures = 0
                if (rows.isEmpty()) break
                val items = rows.mapNotNull { r ->
                    val title = (r["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val text = (r["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: return@mapNotNull null
                    if (text.length < 80) return@mapNotNull null
                    val url = (r["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    Triple(title, text, url)
                }
                offset += rows.size
                lib.insertPage(id, items, offset, spec.rows)
                val pct = (offset * 100L / spec.rows).toInt()
                val rate = (offset - startOffset) * 1000.0 / (System.currentTimeMillis() - started).coerceAtLeast(1)
                val etaMin = if (rate > 0) ((spec.rows - offset) / rate / 60).toInt() else -1
                job.progress = pct
                job.phase = "Knihovna · ${spec.name}"
                job.detail = "%,d / %,d článků".format(offset, spec.rows).replace(',', ' ') + if (etaMin >= 0) " · zbývá ~$etaMin min" else ""
                updateNotification("${spec.name} $pct % · ${job.detail}", pct)
                if (System.currentTimeMillis() - lastNote > 1_500) { Events.emit("progress", job.toJson()); lastNote = System.currentTimeMillis() }
                Thread.sleep(150) // šetrně k serveru
            }
            lib.setProgress(id, offset, spec.rows, true)
            runCatching { lib.writableDatabase.execSQL("INSERT INTO articles(articles) VALUES('optimize')") }
            Events.emit("kb", JSONObject())
            Events.note("${spec.name} je stažená a funguje offline (%,d článků).".format(offset).replace(',', ' '))
        } catch (e: CancelledException) {
            Events.note("Knihovna pozastavena na %,d článcích – příště naváže.".format(offset).replace(',', ' '))
            Events.emit("kb", JSONObject())
        }
    }

    /** Články z Wikipedie k tématu → offline znalosti. */
    private fun wiki(intent: Intent, cancel: CancelToken) {
        val job = omega.job
        job.reset("download")
        val topic = intent.getStringExtra("topic")!!
        val lang = intent.getStringExtra("lang")?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "cs"
        val count = intent.getIntExtra("count", 10).coerceIn(1, 50)
        val titles = Web.wikiSearch(topic, lang, count)
        var added = 0
        for ((i, t) in titles.withIndex()) {
            cancel.check()
            Web.wikiPage(t, lang)?.let { omega.db.addDocument("Wikipedie: ${it.title}", it.text + "\n\nZdroj: ${it.url} (CC BY-SA)", "wikipedia:$lang"); added++ }
            job.progress = (i + 1) * 100 / titles.size.coerceAtLeast(1)
            job.phase = "Wikipedie · $topic"
            job.detail = "$added / ${titles.size} článků"
            Events.emit("progress", job.toJson())
            Thread.sleep(250)
        }
        Events.emit("kb", JSONObject())
        Events.note("Z Wikipedie uloženo $added článků k tématu „$topic“.")
        if (omega.settings.semantic && omega.embedderFile() != null) index(cancel)
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
        const val ACTION_INDEX = "cz.axiom.omega.INDEX"
        const val ACTION_DATASET = "cz.axiom.omega.DATASET"
        const val ACTION_WIKI = "cz.axiom.omega.WIKI"
        const val ACTION_LIBRARY = "cz.axiom.omega.LIBRARY"
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
