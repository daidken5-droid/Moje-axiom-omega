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
import cz.axiom.omega.engine.LocalEngine
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
import cz.axiom.omega.core.randy.Supervisor as Sup
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
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KEEPING.set(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { if (!busyAny() && Lane.KEEP !in active) stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_CANCEL) {
            if (intent.getStringExtra("lane") == "bg") bgToken?.cancel() else askToken?.cancel()
            if (!busyAny() && Lane.KEEP !in active) stopSelf()
            return START_NOT_STICKY
        }
        // MOZEK PŘIPRAVEN: služba v popředí drží proces (a s ním načtený mozek) naživu i v pozadí →
        // dotaz nezačíná načítáním 3–5 GB, systém aplikaci nezabíjí kvůli paměti.
        if (intent.action == ACTION_KEEP) {
            runCatching { promote(Lane.KEEP, keepText()) }.onFailure { KEEPING.set(false); return START_NOT_STICKY }
            KEEPING.set(true)
            omega.warmup()
            // po načtení přepsat oznámení na „Připraven · mozek v paměti“
            Thread({
                var n = 0
                while (n++ < 240 && (omega.warming || LocalEngine.loaded() == null) && KEEPING.get()) Thread.sleep(500)
                if (KEEPING.get() && Lane.KEEP in active && active.size == 1)
                    runCatching { getSystemService(NotificationManager::class.java).notify(Lane.KEEP.notif, notification(keepText(), -1, Lane.KEEP)) }
            }, "omega-keep").apply { isDaemon = true }.start()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_UNKEEP) {
            KEEPING.set(false)
            if (Lane.KEEP in active) demote(Lane.KEEP)
            if (intent.getBooleanExtra("release", false) && !busyAny()) LocalEngine.releaseIfIdle()
            Events.emit("brain", omega.brainState())
            return START_NOT_STICKY
        }
        // DVĚ NEZÁVISLÉ LINKY: myšlení (odpovědi, samostudium) a pozadí (stahování, knihovny, indexace, importy)
        // → AVIOR odpovídá i ve chvíli, kdy stahuje mozek nebo Wikipedii.
        val bg = intent.action in BG_ACTIONS
        val lock = if (bg) RUNNING_BG else RUNNING_ASK
        if (!lock.compareAndSet(false, true)) {
            Events.note(if (bg) "Na pozadí už běží jiné stahování – počkej nebo ho pozastav." else "Právě přemýšlím – počkej nebo mě zastav.")
            if (!busyAny() && Lane.KEEP !in active) stopSelf()
            return START_NOT_STICKY
        }
        val lane = if (bg) Lane.BG else Lane.ASK
        try {
            promote(lane, "Připravuji…")
            acquireLocks(lane)
        } catch (e: Exception) {
            // nikdy nenechat zámek viset (dřív: další dotazy pak „čekaly“ navždy)
            lock.set(false)
            active -= lane
            Events.emit("error", JSONObject().put("message", "Službu se nepodařilo spustit: ${e.message ?: e.javaClass.simpleName}")
                .put("lane", lane.name.lowercase()).put("threadId", intent.getLongExtra("threadId", 0)))
            if (!busyAny()) stopSelf()
            return START_NOT_STICKY
        }

        val cancel = CancelToken().also { if (bg) bgToken = it else askToken = it }
        Thread({
            val job = if (bg) omega.bg else omega.job
            try {
                when (intent.action) {
                    ACTION_ASK -> ask(intent, cancel)
                    ACTION_DOWNLOAD -> download(intent.getStringExtra("id"), intent.getStringExtra("spec"), cancel)
                    ACTION_INDEX -> index(cancel)
                    ACTION_STUDY -> study(cancel)
                    ACTION_DATASET -> dataset(intent, cancel)
                    ACTION_WIKI -> wiki(intent, cancel)
                    ACTION_LIBRARY -> library(intent.getStringExtra("id")!!, cancel)
                    ACTION_RAG -> rag(intent.getStringExtra("id")!!, cancel)
                    ACTION_IMPORT -> import(intent.getParcelableExtraCompat("uri")!!, cancel)
                }
            } catch (e: Throwable) {
                job.phase = "Chyba"
                val tid = intent.getLongExtra("threadId", 0)
                val raw = e.message ?: e.javaClass.simpleName
                // DOTAZ spadl mimo hlavní zpracování (plánování, statistiky…): i tak zkusit knihovnu / web a odpovědět
                // srozumitelně – dřív uživatel viděl jen surovou zprávu („Value null at conf …“). Uložená chyba umožní „Pokračuj“.
                if (intent.action == ACTION_ASK && tid > 0) {
                    val text = intent.getStringExtra("text").orEmpty()
                    val answered = runCatching { offlineAnswer(tid, text, "vnitřní chyba: ${raw.take(80)}") }.getOrDefault(false)
                    if (!answered) {
                        val human = "Při zpracování dotazu nastala vnitřní chyba a odpověď nevznikla. Napiš „Pokračuj“ a zkusím to znovu od začátku.\n\n_Detail: ${raw.take(160)}_"
                        runCatching { omega.db.addMessage(tid, "assistant", human, JSONObject().put("error", true)) }
                        Events.emit("error", JSONObject().put("message", human).put("lane", lane.name.lowercase()).put("threadId", tid))
                    }
                } else Events.emit("error", JSONObject().put("message", raw).put("lane", lane.name.lowercase()).put("threadId", tid))
            } finally {
                job.busy = false
                if (bg) bgToken = null else askToken = null
                releaseLocks(lane)
                lock.set(false)
                Events.emit("state", job.toJson())
                demote(lane)
            }
        }, if (bg) "omega-bg" else "omega-ask").apply { priority = if (bg) Thread.NORM_PRIORITY else Thread.NORM_PRIORITY + 1 }.start()
        return START_NOT_STICKY
    }

    private enum class Lane(val notif: Int, val type: Int) {
        ASK(7, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        BG(8, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        KEEP(9, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
    }

    private fun keepText(): String {
        val e = LocalEngine.loaded()
        return if (e != null) "Připraven · ${e.label} v paměti – odpovídám okamžitě" else "Připravuji mozek do paměti…"
    }

    private val active = java.util.concurrent.CopyOnWriteArraySet<Lane>()

    /** Služba je v popředí za všechny běžící linky (typ = sjednocení), každá linka má své oznámení. */
    @Synchronized
    private fun promote(lane: Lane, text: String) {
        active += lane
        val type = active.fold(0) { acc, l -> acc or l.type }
        ServiceCompat.startForeground(this, lane.notif, notification(text, -1, lane), type)
    }

    @Synchronized
    private fun demote(lane: Lane) {
        active -= lane
        val nm = getSystemService(NotificationManager::class.java)
        // KEEP se ukazuje až jako poslední (pracovní linky mají přednost)
        val other = active.firstOrNull { it != Lane.KEEP } ?: active.firstOrNull()
        if (other == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            nm.cancel(lane.notif)
            stopSelf()
        } else {
            // druhá linka převezme roli oznámení v popředí
            val type = active.fold(0) { acc, l -> acc or l.type }
            val text = when (other) { Lane.BG -> omega.bg.phase; Lane.KEEP -> keepText(); else -> omega.job.phase }
            ServiceCompat.startForeground(this, other.notif, notification(text, -1, other), type)
            nm.cancel(lane.notif)
        }
    }

    private fun busyAny() = RUNNING_ASK.get() || RUNNING_BG.get()

    // ------------------------------------------------------------------ dotaz

    /**
     * „POKRAČUJ“ PO CHYBĚ / ZASEKNUTÍ: najde poslední skutečnou otázku vlákna; když na ni nepřišla odpověď,
     * skončila chybou nebo byla zastavena (i naším resetem zaseknuté úlohy), vrátí ji k novému položení. Jinak null.
     */
    private fun retryTarget(threadId: Long, forced: Boolean): String? {
        val arr = omega.db.messages(threadId, 30)
        val msgs = (0 until arr.length()).map { arr.getJSONObject(it) }.dropLast(1) // poslední = právě napsané „Pokračuj“
        val qi = msgs.indexOfLast { it.getString("role") == "user" && !cz.axiom.omega.core.agent.Conversation.isRetryCommand(it.getString("content")) }
        if (qi < 0) return null
        val after = msgs.drop(qi + 1).filter { it.getString("role") == "assistant" }
        val last = after.lastOrNull()
        val failed = forced || last == null || last.optJSONObject("meta")?.let { it.optBoolean("error") || it.optBoolean("stopped") } == true ||
            last.getString("content").endsWith("[Zastaveno.]")
        return if (failed) msgs[qi].getString("content") else null
    }

    private fun ask(intent: Intent, cancel: CancelToken) {
        val threadId = intent.getLongExtra("threadId", 0)
        val typed = intent.getStringExtra("text").orEmpty()
        // RESET po chybě / zaseknutí: „Pokračuj“ = poslední otázka znovu, přemýšlení od začátku
        val retryOf = if (cz.axiom.omega.core.agent.Conversation.isRetryCommand(typed)) retryTarget(threadId, intent.getBooleanExtra("forceRetry", false)) else null
        val text = retryOf ?: typed
        if (retryOf != null) Events.emit("phase", JSONObject().put("text", "Začínám znovu: ${cz.axiom.omega.core.util.TextUtil.clip(retryOf, 60)}"))
        val mode = intent.getStringExtra("mode") ?: omega.settings.mode
        // Výslovné volby uživatele (Ω varianta, režim, role, hloubka) – RANDY je nikdy nepřepíše
        val userVariant = cz.axiom.omega.core.agent.Variant.of(intent.getStringExtra("variant"))
        val userKind = when (intent.getStringExtra("kind")) {
            "agent" -> Router.Mode.AGENT; "chat" -> Router.Mode.CHAT; "council" -> Router.Mode.COUNCIL
            else -> if (mode == "council") Router.Mode.COUNCIL else null
        }
        val userRole = cz.axiom.omega.core.agent.AgentRole.of(intent.getStringExtra("role"))
        val userLevel = RandyLevel.fromCode(intent.getStringExtra("level"))
        // Ω FLASH · OKAMŽITĚ: jednoduché otázky (čas, datum, výpočet, převod, krátká definice) bez mozku, v milisekundách
        if (instant(threadId, text, userVariant, userKind, userRole, userLevel)) return
        // RANDY SUPERVISOR: nadřazený modul naplánuje podfunkce a zdroje
        val authority = Sup.Authority.of(omega.settings.randyAuthority)
        val (battery, charging) = Introspect.battery(this)
        val engines = omega.availableEngines()
        // E-ARL: rozhodne, kolik myšlení je potřeba (výňatek / lehký mozek / mozek / hloubka) – RANDY pak neplánuje triviality
        val sig = cz.axiom.omega.core.agent.Pavucina.of(text)
        val userForced = userKind != null || userRole != null || userLevel != null || userVariant != cz.axiom.omega.core.agent.Variant.AUTO ||
            mode !in setOf("auto", "local", "free", "turbo") || text.trimStart().startsWith("/")
        val earl = if (userForced) null else runCatching {
            cz.axiom.omega.core.agent.Earl.decide(sig, cz.axiom.omega.core.agent.Earl.Ctx(
                knowledge = { q -> omega.quickKnowledge().search(q, 3) }, brainLoaded = LocalEngine.loaded() != null,
                cloud = mode != "local" && Introspect.online(this) && engines.any { it != "local" && it != "server" }, thresholds = omega.earlThresholds(),
            ))
        }.getOrNull()
        // výňatek místo mozku jen v Auto – když si uživatel výslovně zvolil telefon / cloud / Turbo, dostane ho
        if (earl?.path == cz.axiom.omega.core.agent.Earl.Path.RETRIEVE && mode == "auto" && earlRetrieve(threadId, text, earl)) return
        val light = earl?.path == cz.axiom.omega.core.agent.Earl.Path.LIGHT
        // ŽÁDNÝ MOZEK (offline bez staženého mozku, bez cloudu) → odpověď z offline knihovny místo chyby
        if (engines.isEmpty() && offlineAnswer(threadId, text, if (Introspect.online(this)) "není stažený mozek ani připojený cloud" else "jsi offline a v telefonu není mozek")) return
        val device = Sup.Device(battery, charging, Introspect.thermalLevel(this), Introspect.freeRamMb(this), Introspect.online(this),
            local = mode == "local" || ((mode == "auto" || mode == "duo") && engines.firstOrNull() == "local" && !(mode == "auto" && omega.settings.freeFirst && "free" in engines)) ||
                (mode == "turbo" && !omega.turboReady() && "local" in engines))
        // SEBEPOZNÁNÍ: co o sobě vím v oblasti této otázky (z každé předchozí otázky a úvahy)
        val learning = omega.settings.learning
        val (domain, selfInsight) = omega.cognition.brief(text)
        val competence = omega.cognition.competence(domain)
        // REGULÁTOR VÝKONU: závazně určí, kolik výkonu tento dotaz dostane (stupeň, délka, vlákna, řetězce)
        val budget = cz.axiom.omega.core.device.Governor.decide(cz.axiom.omega.core.device.Governor.Input(
            prompt = text, level = userLevel ?: cz.axiom.omega.core.randy.Randy.detect(text).level,
            userPower = if (omega.powerAuto()) null else omega.power(),
            batteryPct = battery, charging = charging, thermal = Introspect.thermalLevel(this), thermalCaution = omega.device.thermalCaution,
            freeRamMb = Introspect.freeRamMb(this), local = device.local, writeTps = omega.writeTps, threads = omega.device.threads,
            cores = Runtime.getRuntime().availableProcessors(), strength = omega.settings.powerStrength,
        ))
        // vlákna platí pro KAŽDOU cestu (odpověď, agent, oba mozky, řetězec) – nastaví se rovnou na načteném mozku
        cz.axiom.omega.engine.LocalEngine.loaded()?.setThreads(budget.threads, budget.batchThreads)
        val plan = Sup.plan(Sup.Request(text, userVariant, userKind, userRole, userLevel, Introspect.online(this),
            weakDomain = if (learning && competence?.weak == true) domain.label else null,
            // Turbo = rychlá cesta i v cloudu (1 průchod, bez přemýšlení, stručně)
            power = budget.power, thermalCaution = omega.device.thermalCaution, localBrain = device.local || mode == "turbo"), device, omega.randy.stats(), authority)
        // DOLADĚNÍ DOVEDNOSTÍ v telefonu: ukázka odpovědi z této oblasti, kterou uživatel ocenil (few-shot)
        // v telefonu stojí každý token čas – ukázku přidáme jen cloudu nebo v režimu Sherlock
        val exemplar = if (learning && (!device.local || omega.power() == cz.axiom.omega.core.device.Power.SHERLOCK)) omega.cognition.exemplar(domain) else null
        val insight = if (learning) listOfNotNull(selfInsight, exemplar).joinToString("\n\n") else null
        val variant = plan.variant
        val forced = plan.level
        val job = omega.job
        job.reset("ask", threadId)
        Events.emit("state", job.toJson())
        omega.refreshRatesIfStale()

        val fullHistory = omega.db.messages(threadId, 40).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.getString("role") in setOf("user", "assistant") && it.optJSONObject("meta")?.optBoolean("error") != true }
                .map { ChatMessage(if (it.getString("role") == "user") Role.USER else Role.ASSISTANT, it.getString("content")) }
        }.dropLast(1) // poslední je právě odeslaný dotaz
            .let { h -> if (retryOf == null) h else h.dropLastWhile { it.role == Role.USER || it.role == Role.ASSISTANT && it.content.endsWith("[Zastaveno.]") }
                .let { t -> if (t.lastOrNull()?.content == retryOf) t.dropLast(1) else t } }
        val prevQ = fullHistory.lastOrNull { it.role == Role.USER }?.content
        val prevA = fullHistory.lastOrNull { it.role == Role.ASSISTANT }?.content
        // OPRAVA OD UŽIVATELE („Špatně, hraje to Petr Rychlý“) → špatnou odpověď zapomenout a opravu si zapamatovat
        // PROOFCORE: doložený závěr se nemění kvůli nepodloženému nesouhlasu (ušetří i celý průchod mozku)
        if (prevQ != null && prevA != null && cz.axiom.omega.core.agent.Conversation.isCorrection(text)) {
            val rev = runCatching { cz.axiom.omega.core.agent.Proof.onCorrection(prevA, text, omega.quickKnowledge()) }.getOrNull()
            if (rev?.stance == cz.axiom.omega.core.agent.Proof.Stance.PENDING) runCatching {
                // doložená odpověď zůstává; oprava jen jako nepotvrzené tvrzení k přezkoumání
                omega.db.remember(cz.axiom.omega.core.self.MemoryItem(kind = cz.axiom.omega.core.self.MemoryKind.CORRECTION,
                    text = cz.axiom.omega.core.agent.Proof.correctionRecord(prevQ, prevA, text), weight = 0.6))
            }
            if (rev?.stance == cz.axiom.omega.core.agent.Proof.Stance.KEEP || rev?.stance == cz.axiom.omega.core.agent.Proof.Stance.PENDING) {
                val meta = JSONObject().put("engine", "PROOFCORE · bez mozku").put("variant", "Δ revize").put("reflex", 95).put("private", true).put("level", "L0")
                val id = omega.db.addMessage(threadId, "assistant", rev.reply, meta)
                Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", rev.reply).put("meta", meta))
                return
            }
        }
        if (prevQ != null && cz.axiom.omega.core.agent.Conversation.isCorrection(text)) runCatching {
            omega.db.forgetMatching(prevQ)
            // M1-C: oprava NENÍ fakt – nepotvrzené tvrzení s historií původní odpovědi (dřív FACT s váhou 0,95)
            omega.db.remember(cz.axiom.omega.core.self.MemoryItem(kind = cz.axiom.omega.core.self.MemoryKind.CORRECTION,
                text = cz.axiom.omega.core.agent.Proof.correctionRecord(prevQ, prevA, text), weight = 0.6))
        }
        // MALÝ MOZEK V TELEFONU: nová otázka na jiné téma → bez staré výměny (malý model ji jinak zkopíroval)
        // a s kratším zadáním (rychlejší čtení); navazující otázky a cloud dostávají historii dál
        val history = if (device.local && cz.axiom.omega.core.agent.Conversation.standalone(text, prevQ)) emptyList() else fullHistory

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
                // dlouhé odpovědi (cloud píše stovky tokenů/s) → řidší snímky: každý nese celý text přes most do WebView
                if (now - lastEmit > 80 + job.partial.length / 150) {
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
            val userEngine = mode.takeIf { it != "auto" && it != "council" && it != "duo" }
            val role = plan.role
            synchronized(omega.forge.session) { omega.forge.session.clear() }
            val route = Router.route(text, Router.Context(Introspect.online(this), engines, userEngine, plan.mode,
                boost = if (s.claudeBoost && "claude" in engines) "claude" else null,
                sherlock = omega.power() == cz.axiom.omega.core.device.Power.SHERLOCK,
                duo = if (mode == "duo" || (mode == "auto" && s.autoDuo)) omega.duoPartner(engines) else null,
                forceDuo = mode == "duo",
                phoneFast = device.local && mode != "turbo" && omega.power() != cz.axiom.omega.core.device.Power.SHERLOCK,
                cloudFirst = if (mode == "auto" && s.freeFirst && "free" in engines) "free" else null))
            val agentRole = role ?: if (route.mode == Router.Mode.AGENT) Router.autoRole(route.prompt) else null
            listener.onPhase(plan.summary + " · ${route.mode.label}" + (plan.decisions.firstOrNull()?.let { " · $it" } ?: ""))
            val watchdog = Sup.Watchdog(plan.budgetMillis, authority)
            var escalatedTo: cz.axiom.omega.core.agent.Variant? = null
            val meta: JSONObject
            val answer: String
            // E-ARL LIGHT: krátká jasná otázka → žádný řetězec podmodelů (zbytečná práce)
            if (plan.pipeline.size >= 2 && route.mode != Router.Mode.COUNCIL && budget.allowPipeline && !light) {
                // RANDY ŘETĚZEC: víc podmodelů za sebou, každý navazuje na výstup předchozího
                val (a, m) = runPipeline(plan, route.prompt, route.engine, route.reason, history, insight, watchdog, threadId, listener, cancel)
                meta = m; answer = a
            } else when (route.mode) {
                Router.Mode.COUNCIL -> {
                    val (members, judge) = if (route.duo) omega.duoMembers(route.engine) else omega.councilMembers()
                    val r = omega.orchestrator().council(
                        AskRequest(route.prompt, history, forced, 1, s.reflect, s.web, s.thinking, true, s.rates, s.userName.ifBlank { null },
                            webGround = if (s.web && Introspect.online(this)) { q -> cz.axiom.omega.core.hub.WebGround.gather(q).snippets } else null),
                        members, judge, cancel, listener, parallel = route.duo,
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
                        omega.agent().run(route.prompt, history, engine, plan.steps?.let { if (device.lowPower || device.hot) it else maxOf(s.agentSteps, it) } ?: s.agentSteps, cancel, object : AgentListener {
                            override fun onPhase(text: String) = listener.onPhase(text)
                            override fun onDelta(delta: Delta) = listener.onDelta(steps.length() + 1, 0, delta)
                            override fun onStep(step: AgentStep) {
                                val j = JSONObject().put("index", step.index).put("thought", step.thought).put("tool", step.tool ?: JSONObject.NULL)
                                    .put("args", step.args ?: JSONObject.NULL).put("observation", step.observation?.take(1_500) ?: JSONObject.NULL).put("millis", step.millis)
                                steps.put(j)
                                omega.db.updateTask(taskId, "running", steps.length(), null)
                                Events.emit("step", j)
                            }
                        }, s.rates, s.userName.ifBlank { null }, agentRole, listOfNotNull(variant.style, plan.extraStyle, insight).joinToString("\n").ifBlank { null }, variant.temperature ?: 0.4f, watchdog)
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
                    val tLoad = System.currentTimeMillis()
                    // E-ARL LIGHT + cloud → rychlý model poskytovatele (gpt-oss-20b / 8B), stačí na krátkou jasnou otázku
                    val engine = if (light && route.engine == "free") omega.shield(omega.free(fast = true)) else omega.resolve(route.engine)
                    val loadMs = System.currentTimeMillis() - tLoad
                    (engine as? cz.axiom.omega.engine.LocalEngine)?.setThreads(budget.threads, budget.batchThreads)
                    listener.onPhase("Probouzím ${engine.label}…")
                    // RYCHLOST v telefonu: každý průchod = celé čtení + psaní znovu. Běžný dotaz 1 průchod,
                    // strategické/kritické (L3+) 2, Sherlock nejvýš 2 + oponentura v řetězci.
                    val passes = if (light) 1 else if (engine.kind == EngineKind.LOCAL) {
                        // PAVUČINA: úroveň RANDY už je spočítaná v signálu dotazu
                        val lvl = (forced ?: if (route.prompt == text) sig.level else cz.axiom.omega.core.randy.Randy.detect(route.prompt).level).ordinal
                        minOf(plan.passes ?: if (lvl >= RandyLevel.L3_STRATEGIC_EV.ordinal) 2 else 1, s.localMaxPasses, 2)
                    } else plan.passes ?: s.maxPasses
                    val orch = omega.orchestrator()
                    fun req(e: cz.axiom.omega.core.llm.LlmEngine) = AskRequest(route.prompt, history, forced, passes, s.reflect, s.web && e.kind == EngineKind.CLAUDE,
                        !light && (plan.thinking ?: s.thinking) && budget.thinking, true, s.rates,
                        s.userName.ifBlank { null }, listOfNotNull(variant.style, plan.extraStyle).joinToString("\n").ifBlank { null }, variant.temperature, insight,
                        maxTokens = budget.maxTokens.takeIf { e.kind == EngineKind.LOCAL }, knowledgeLimit = budget.knowledge,
                        // ONLINE MODUL: webové podklady s citacemi pro všechny cloudové modely (Claude má vlastní webový nástroj)
                        webGround = webGround(e))
                    var failover: String? = null
                    var r = try {
                        orch.run(req(engine), engine, cancel, listener)
                    } catch (e: CancelledException) {
                        throw e
                    } catch (e: Exception) {
                        // CLOUD SELHAL (limit, výpadek, síť) a telefon má mozek → odpoví telefon, uživatel dostane odpověď vždy
                        if (engine.kind == EngineKind.LOCAL || !omega.localAvailable()) throw e
                        failover = e.message?.take(120)
                        listener.onPhase("Cloud nedostupný – odpovídá mozek v telefonu")
                        job.partial.setLength(0); job.reasoning.setLength(0)
                        val phone = omega.local()
                        phone.setThreads(budget.threads, budget.batchThreads)
                        orch.run(req(phone).copy(maxPasses = 1, maxTokens = budget.maxTokens), phone, cancel, listener)
                    }
                    // RANDY eskalace: nejistá odpověď → silnější varianta (jen s plnou pravomocí)
                    Sup.escalation(plan, r.confidence, r.answer.length)?.let { up ->
                        listener.onPhase("RANDY eskaluje → ${up.title} (jistota ${r.confidence ?: "?"} %)")
                        omega.randy.record(variant, r.millis, r.confidence, true)
                        val r2 = omega.orchestrator().run(
                            AskRequest(route.prompt, history, up.level ?: forced, up.passes ?: 3, s.reflect, s.web && engine.kind == EngineKind.CLAUDE, up.thinking ?: true, true,
                                s.rates, s.userName.ifBlank { null }, up.style, up.temperature, insight, webGround = webGround(engine)),
                            engine, cancel, listener,
                        )
                        if ((r2.confidence ?: 0) >= (r.confidence ?: 0)) { r = r2; escalatedTo = up }
                    }
                    // OZVĚNA: mozek v telefonu zopakoval minulou odpověď na jinou otázku → znovu bez historie
                    if (engine.kind == EngineKind.LOCAL && history.isNotEmpty() && cz.axiom.omega.core.agent.Conversation.isEcho(r.answer, prevA, text, prevQ)) {
                        listener.onPhase("Odpověď zopakovala minulou – ptám se znovu bez historie")
                        job.partial.setLength(0); job.reasoning.setLength(0)
                        r = orch.run(req(engine).copy(history = emptyList()), engine, cancel, listener)
                    }
                    omega.rememberConfidence(r.confidence)
                    meta = metaOf(r).put("route", route.reason + (failover?.let { " · cloud selhal ($it) → telefon" } ?: "")); answer = r.answer
                    (engine as? cz.axiom.omega.engine.LocalEngine)?.perf()?.let { meta.put("perf", it.put("loadMs", loadMs).put("knowledgeMs", orch.knowledgeMillis)); omega.rememberSpeed(it.optDouble("writeTps")) }
                }
            }
            val finalVariant = escalatedTo ?: variant
            if (finalVariant != cz.axiom.omega.core.agent.Variant.AUTO) meta.put("variant", finalVariant.title)
            meta.put("power", plan.power.label)
            earl?.let { e -> meta.put("earl", JSONObject().put("path", e.path.name).put("label", e.path.label).put("conf", e.confidence).put("category", e.category).put("reasons", JSONArray(e.reasons))) }
            meta.put("governor", JSONObject().put("power", budget.power.label).put("demand", budget.demand).put("maxTokens", budget.maxTokens).put("strength", omega.settings.powerStrength)
                .put("threads", budget.threads).put("limited", budget.limited).put("auto", omega.powerAuto())
                .put("reasons", JSONArray().apply { budget.reasons.forEach { put(it) } }))
            val randyMeta = JSONObject().put("summary", plan.summary).put("authority", authority.label).put("chosen", plan.chosenByRandy)
                .put("variantId", finalVariant.name)
                .put("decisions", JSONArray().apply { plan.decisions.forEach { put(it) } })
                .put("interventions", JSONArray().apply { watchdog.interventions.forEach { put(it) }; escalatedTo?.let { put("eskalace na ${it.title}") } })
            meta.put("randy", randyMeta)
            if (meta.optString("mode") != "pipeline") omega.randy.record(finalVariant, meta.optLong("millis"), meta.optInt("confidence", -1).takeIf { it >= 0 }, escalatedTo != null)
            omega.randy.log(JSONObject().put("prompt", text.take(80)).put("summary", plan.summary).put("decisions", randyMeta.getJSONArray("decisions"))
                .put("interventions", randyMeta.getJSONArray("interventions")).put("ms", meta.optLong("millis")))
            agentRole?.takeIf { route.mode == Router.Mode.AGENT }?.let { meta.put("role", it.title) }
            // MAPA DŮKAZŮ: u každé věty odkud pochází (spočítáno / doloženo / z paměti / odhad / úvaha)
            runCatching {
                fun strs(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                // M1-B: [n] je doloženo jen když podklad n skutečně existuje a věta s ním souvisí
                val srcs = meta.optJSONArray("sources")?.let { arr -> (0 until arr.length()).map { i -> arr.optJSONObject(i)?.let { it.optString("title") + " " + it.optString("source") + " " + it.optString("snippet") } ?: arr.optString(i) } } ?: emptyList()
                val ev = cz.axiom.omega.core.agent.Evidence.map(answer, strs(meta.optJSONArray("facts")), strs(meta.optJSONArray("memories")), srcs)
                val K = cz.axiom.omega.core.agent.Evidence.Kind.entries
                meta.put("evidence", JSONObject().put("grounded", ev.grounded).put("heuristic", true)
                    .put("counts", JSONObject().apply { K.forEach { put(it.code, ev.count(it)) } })
                    .put("spans", JSONArray().apply { ev.spans.forEach { put(JSONArray().put(it.start).put(it.end).put(it.kind.code)) } }))
            }
            // PEČEŤ SOUKROMÍ: myslel mozek v telefonu a nic nešlo na internet
            run {
                val webTools = setOf("web_search", "fetch_url", "wiki_search", "hf_search", "http_audit", "dns", "subdomains", "rdap", "vulns")
                val steps = meta.optJSONArray("steps")
                val usedWeb = steps != null && (0 until steps.length()).any { steps.getJSONObject(it).optString("tool") in webTools }
                // výčet POVOLENÝCH (ne zakázaných) cest: nový cloud se tak nikdy omylem neoznačí jako „v telefonu“
                val localBrain = route.engine == "local" || (route.engine == "turbo" && !omega.turboReady() && "local" in engines)
                meta.put("private", localBrain && !usedWeb && (meta.optJSONArray("sources")?.length() ?: 0) == 0 && route.mode != Router.Mode.COUNCIL)
            }
            // UČEBNÍ SMYČKA: kalibrace jistoty, kompetence, vlastní poznatek, otevřené otázky, milníky
            if (learning) {
                val raw = meta.optInt("confidence", -1).takeIf { it >= 0 }
                cz.axiom.omega.core.self.Cognition.calibrated(raw, competence)?.let { cal ->
                    if (cal != raw) { meta.put("rawConfidence", raw); meta.put("confidence", cal) }
                }
                val milestones = omega.cognition.learn(route.prompt, answer, raw, ok = true)
                meta.put("learned", JSONObject().put("domain", domain.label).put("icon", domain.icon).put("milestones", JSONArray(milestones)))
                // milníky učení se už neukazují jako vyskakovací zpráva (rozptylovaly) – jsou ve Vědomí

            }
            // Δ STRÁŽCE FAKTŮ: ověření odpovědi proti offline Wikipedii (data narození/úmrtí, vznik) – bez internetu, ms.
            // Běží PŘED uložením do paměti odpovědí: vyvrácená odpověď se nikdy nestane odpovědí „bez mozku“.
            // úklid artefaktů modelů (citační značky gpt-oss 【4】, zbytky značek sebereflexe)
            val clean = cz.axiom.omega.core.agent.Conversation.clean(answer)
            val audit = runCatching { cz.axiom.omega.core.agent.Sentinel.audit(clean, omega.quickKnowledge()) }.getOrDefault(cz.axiom.omega.core.agent.Sentinel.Audit.EMPTY)
            val deltaFlags = audit.flags
            // PAMĚŤ ODPOVĚDÍ: jistou, nadčasovou odpověď si uložit → příště stejná otázka bez mozku
            if (deltaFlags.isEmpty()) runCatching {
                // mozek v telefonu nevrací číselnou jistotu → uložit s 80 (použije se jen u téměř stejné otázky)
                val c = meta.optInt("confidence", -1).takeIf { it >= 0 } ?: if (meta.optBoolean("private")) 80 else -1
                if (c >= 80 && route.mode == Router.Mode.CHAT && answer.length in 20..3000 && text.length <= 200 && !cz.axiom.omega.core.agent.Reflex.timeSensitive(text))
                    omega.db.putAnswer(text, answer, c, good = false, origin = cz.axiom.omega.core.agent.Proof.originOf(text, clean, audit))
            }
            val made = synchronized(omega.forge.session) { omega.forge.session.toList() }
            if (made.isNotEmpty()) meta.put("files", JSONArray().apply { made.forEach { put(it) } })
            // PROOFCORE: co Wikipedie kladně potvrdila (důkaz, ne dojem) – jen když nic není v rozporu
            if (audit.proven) meta.put("proof", JSONArray().apply { audit.confirmed.forEach { put(it.text()) } })
            if (deltaFlags.isNotEmpty()) meta.put("delta", JSONArray().apply { deltaFlags.forEach { put(JSONObject().put("text", it.text()).put("title", it.title)) } })
            val id = omega.db.addMessage(threadId, "assistant", clean, meta)
            runCatching {
                val cg = omega.cognition
                cz.axiom.omega.widget.AviorWidget.publish(this, text, answer,
                    "${cg.totalQuestions()} otázek · ${plan.power.label}" + (meta.optString("variant").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            }
            Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", clean).put("meta", meta))
        } catch (e: CancelledException) {
            val partial = job.partial.toString().trim()
            if (partial.isNotEmpty()) {
                omega.db.addMessage(threadId, "assistant", "$partial\n\n[Zastaveno.]", JSONObject().put("stopped", true).put("engine", job.phase))
            }
            Events.emit("done", JSONObject().put("threadId", threadId).put("stopped", true))
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            // i při chybě zkusit odpovědět z offline knihovny (s vysvětlením, proč bez mozku)
            if (offlineAnswer(threadId, text, "mozek teď nejde použít: ${msg.take(90)}")) return
            // ani knihovna, ani web: srozumitelně, co se stalo a co zkusit (technický detail až za tím)
            val human = "Teď se mi nepodařilo odpovědět – mozek ani cloud nejsou dostupné a v offline knihovně ani na webu jsem nic nenašel. " +
                "Zkus to za chvíli znovu, zeptej se jinými slovy, nebo v Nastavení zapni cloud / stáhni mozek.\n\n_Detail: ${msg.take(160)}_"
            omega.db.addMessage(threadId, "assistant", human, JSONObject().put("error", true))
            Events.emit("error", JSONObject().put("threadId", threadId).put("message", human))
        }
    }

    /** Webové podklady pro daný engine: jen online, se zapnutým webem, ne pro Claude (ten hledá sám). */
    private fun webGround(e: cz.axiom.omega.core.llm.LlmEngine): ((String) -> List<cz.axiom.omega.core.agent.Snippet>)? =
        if (!omega.settings.web || e.kind == EngineKind.CLAUDE || !Introspect.online(this)) null
        else { q -> cz.axiom.omega.core.hub.WebGround.gather(q).snippets }

    /** E-ARL RETRIEVE: přesné heslo v offline knihovně → výňatek místo načítání mozku. */
    private fun earlRetrieve(threadId: Long, text: String, e: cz.axiom.omega.core.agent.Earl.Decision): Boolean {
        val started = System.currentTimeMillis()
        val q = e.entity ?: text
        val a = runCatching { cz.axiom.omega.core.agent.OfflineAnswer.compose(text, omega.quickKnowledge().search(q, 3)) }.getOrNull() ?: return false
        val meta = JSONObject().put("engine", "E-ARL · offline knihovna").put("millis", System.currentTimeMillis() - started).put("route", e.reasons.joinToString(" · "))
            .put("instant", a.kind).put("reflex", a.confidence).put("confidence", a.confidence).put("private", true).put("level", "L0")
            .put("earl", JSONObject().put("path", e.path.name).put("label", e.path.label).put("conf", e.confidence).put("category", e.category).put("reasons", JSONArray(e.reasons)))
        val id = omega.db.addMessage(threadId, "assistant", a.text, meta)
        Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", a.text).put("meta", meta))
        return true
    }

    /** Odpověď z offline knihovny (Wikipedie, dokumenty, balíčky) – extraktivní, se zdrojem. false = nic vhodného. */
    private fun offlineAnswer(threadId: Long, text: String, reason: String): Boolean {
        val started = System.currentTimeMillis()
        val q = cz.axiom.omega.core.agent.Topic.bare(text) ?: text
        val local = runCatching { cz.axiom.omega.core.agent.OfflineAnswer.compose(text, omega.quickKnowledge().search(q, 5)) }.getOrNull()
        // knihovna nic → při internetu výňatek z webu (Wikipedie / výsledky hledání) místo surové chyby
        val a = local ?: (if (omega.settings.web && Introspect.online(this)) runCatching {
            cz.axiom.omega.core.hub.WebAnswer.compose(text) { cz.axiom.omega.core.hub.WebGround.gather(it).snippets }
        }.getOrNull() else null) ?: return false
        val meta = JSONObject().put("engine", if (local != null) "Offline knihovna · bez mozku" else "Web · bez mozku").put("millis", System.currentTimeMillis() - started).put("route", reason)
            .put("instant", a.kind).put("reflex", a.confidence).put("confidence", a.confidence).put("private", local != null).put("level", "L0").put("offline", local != null)
        a.contract?.let { k -> meta.put("contract", JSONObject().put("type", k.type.code).put("label", k.type.label).put("reason", k.reason).put("refs", JSONArray(k.evidenceRefs))) }
        omega.job.busy = false
        val id = omega.db.addMessage(threadId, "assistant", a.text, meta)
        Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", a.text).put("meta", meta))
        return true
    }

    private fun instant(
        threadId: Long, text: String, variant: cz.axiom.omega.core.agent.Variant, kind: Router.Mode?,
        role: cz.axiom.omega.core.agent.AgentRole?, level: RandyLevel?,
    ): Boolean {
        val s = omega.settings
        val flash = variant == cz.axiom.omega.core.agent.Variant.FLASH
        if (!s.instant || kind != null || role != null || level != null) return false
        if (!flash && variant != cz.axiom.omega.core.agent.Variant.AUTO) return false
        if (omega.power() == cz.axiom.omega.core.device.Power.SHERLOCK && !flash) return false
        val started = System.currentTimeMillis()
        val (battery, charging) = Introspect.battery(this)
        // REFLEXY: deterministika → kalendář → vzpomínky → ověřené odpovědi → definice z knihovny.
        // Odpoví jen nad prahem jistoty (Flash 70, Auto 85); jinak dotaz převezme mozek.
        val ictx = cz.axiom.omega.core.agent.Instant.Ctx(
            userName = s.userName.takeIf { it.isNotBlank() }, battery = battery, charging = charging,
            brain = omega.brains.active()?.second?.name, rates = s.rates, knowledge = omega.quickKnowledge(),
        )
        val a = runCatching {
            cz.axiom.omega.core.agent.Reflex.answer(text, cz.axiom.omega.core.agent.Reflex.Ctx(
                instant = ictx, memories = omega.db.all(), cache = omega.db.answers(),
                threshold = if (flash) 70 else 85, definitions = true,
                // Δ STRÁŽCE BEZ MOZKU: uložená odpověď, kterou offline Wikipedie vyvrací, se nepoužije a smaže
                onRejected = { bad, _ -> if (cz.axiom.omega.core.agent.Reflex.fromCache(bad)) { omega.db.forgetMatching(text); Events.note("Δ Uložená odpověď nesouhlasila s Wikipedií – smazal jsem ji a odpovím znovu.") } },
            ))
        }.getOrNull() ?: return false
        val ms = System.currentTimeMillis() - started
        val meta = JSONObject().put("engine", "Reflex AVIOR · bez mozku").put("millis", ms).put("variant", if (flash) "Ω Flash" else "Ω Reflex")
            .put("instant", a.kind).put("reflex", a.confidence).put("confidence", a.confidence).put("private", true).put("level", "L0")
        if (a.sources.isNotEmpty()) meta.put("sources", JSONArray().apply { a.sources.forEach { put(JSONObject().put("title", it.title).put("url", "").put("source", it.source)) } })
        // ANSWER CONTRACT (M3): typ, důvod, zdroj, stav k datu, předpoklady, čas výpočtu – UI ho jen zobrazí, nikdy nezvýší
        a.contract?.let { k -> meta.put("contract", JSONObject().put("type", k.type.code).put("label", k.type.label).put("reason", k.reason)
            .put("refs", JSONArray(k.evidenceRefs)).put("asOf", k.asOf ?: JSONObject.NULL).put("assumptions", JSONArray(k.assumptions)).put("us", k.elapsedNs / 1000)) }
        val id = omega.db.addMessage(threadId, "assistant", a.text, meta)
        Events.emit("done", JSONObject().put("threadId", threadId).put("id", id).put("content", a.text).put("meta", meta))
        return true
    }

    /** Provede řetězec podmodelů naplánovaný RANDY Supervisorem. */
    private fun runPipeline(
        plan: Sup.Plan, prompt: String, engineId: String, reason: String, history: List<ChatMessage>, insight: String?,
        watchdog: Sup.Watchdog, threadId: Long, listener: AskListener, cancel: CancelToken,
    ): Pair<String, JSONObject> {
        val s = omega.settings
        val engine = omega.resolve(engineId)
        val local = engine.kind == EngineKind.LOCAL
        val n = plan.pipeline.size
        val taskId = omega.db.addTask(threadId, prompt, engine.label)
        omega.job.kind = "agent"
        val allSteps = JSONArray()
        val stagesMeta = JSONArray()
        var prev: Pair<Sup.Stage, String>? = null
        var lastChat: AskResult? = null
        var answer = ""
        val started = System.currentTimeMillis()
        try {
            for ((i, stage) in plan.pipeline.withIndex()) {
                cancel.check()
                if (i > 0 && watchdog.overBudget()) break
                val v = stage.variant
                listener.onPhase("RANDY řetězec ${i + 1}/$n · ${v.title} · ${stage.purpose}")
                val stagePrompt = if (prev == null) "$prompt\n\nTVOJE FÁZE 1/$n: ${stage.purpose}."
                else buildString {
                    append(prompt)
                    append("\n\nVÝSTUP PŘEDCHOZÍ FÁZE (${prev!!.first.variant.title} – ${prev!!.first.purpose}), použij ho jako podklad:\n")
                    append(cz.axiom.omega.core.util.TextUtil.clip(prev!!.second, if (local) 2_500 else 8_000))
                    append("\n\nTVOJE FÁZE ${i + 1}/$n: ${stage.purpose}. Navaž na podklady, neopakuj je zbytečně.")
                    if (i == n - 1) append(" Toto je poslední fáze – výsledek je finální odpověď pro uživatele.")
                }
                val t0 = System.currentTimeMillis()
                val out: String
                if (v.mode == Router.Mode.AGENT) {
                    val r = omega.agent().run(stagePrompt, if (i == 0) history else emptyList(), engine, minOf(v.steps ?: s.agentSteps, 10), cancel, object : AgentListener {
                        override fun onPhase(text: String) = listener.onPhase("${v.title} · $text")
                        override fun onDelta(delta: Delta) = listener.onDelta(allSteps.length() + 1, 0, delta)
                        override fun onStep(step: AgentStep) {
                            val j = JSONObject().put("index", allSteps.length() + 1).put("thought", "[${v.title}] ${step.thought}").put("tool", step.tool ?: JSONObject.NULL)
                                .put("args", step.args ?: JSONObject.NULL).put("observation", step.observation?.take(1_500) ?: JSONObject.NULL).put("millis", step.millis)
                            allSteps.put(j)
                            omega.db.updateTask(taskId, "running", allSteps.length(), null)
                            Events.emit("step", j)
                        }
                    }, s.rates, s.userName.ifBlank { null }, v.role, listOfNotNull(v.style, plan.extraStyle, insight).joinToString("\n").ifBlank { null }, v.temperature ?: 0.4f, watchdog)
                    out = r.answer
                } else {
                    val r = omega.orchestrator().run(
                        AskRequest(stagePrompt, if (i == 0) history else emptyList(), v.level,
                            if (local) minOf(v.passes ?: 1, if (i == n - 1) 2 else 1) else v.passes ?: s.maxPasses,
                            s.reflect && i == n - 1, false, if (plan.power == cz.axiom.omega.core.device.Power.SHERLOCK) true else v.thinking ?: s.thinking, true, s.rates,
                            s.userName.ifBlank { null }, listOfNotNull(v.style, plan.extraStyle).joinToString("\n").ifBlank { null }, v.temperature, insight,
                            // web jen pro první stupeň řetězce (další navazují na jeho výstup)
                            webGround = if (i == 0) webGround(engine) else null),
                        engine, cancel, listener,
                    )
                    lastChat = r
                    out = r.answer
                    allSteps.put(JSONObject().put("index", allSteps.length() + 1).put("thought", "[${v.title}] ${stage.purpose}").put("tool", JSONObject.NULL)
                        .put("observation", out.take(600)).put("millis", r.millis))
                }
                val ms = System.currentTimeMillis() - t0
                omega.randy.record(v, ms, if (v.mode == Router.Mode.AGENT) null else lastChat?.confidence, false)
                stagesMeta.put(JSONObject().put("variant", v.title).put("purpose", stage.purpose).put("millis", ms).put("preview", out.take(240)))
                prev = stage to out
                answer = out
            }
        } catch (e: Exception) {
            omega.db.updateTask(taskId, if (e is CancelledException) "stopped" else "failed", allSteps.length(), e.message)
            throw e
        }
        omega.db.updateTask(taskId, "done", allSteps.length(), answer)
        val meta = (lastChat?.takeIf { plan.pipeline.last().variant.mode != Router.Mode.AGENT }?.let { metaOf(it) } ?: JSONObject())
            .put("mode", "pipeline").put("engine", engine.label).put("millis", System.currentTimeMillis() - started)
            .put("route", reason).put("steps", allSteps).put("pipeline", stagesMeta).put("taskId", taskId)
        return answer to meta
    }

    private fun metaOf(r: AskResult): JSONObject = JSONObject()
        .put("level", r.level.code).put("levelTitle", r.level.title).put("levelExplicit", r.levelExplicit)
        .put("engine", r.engineLabel).put("model", r.model).put("millis", r.millis).put("truncated", r.truncated)
        .put("confidence", r.confidence ?: JSONObject.NULL).put("lesson", r.lesson ?: JSONObject.NULL)
        .put("facts", JSONArray().apply { r.facts.forEach { put(it.toString()) } })
        .put("sources", JSONArray().apply { r.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
        .put("snippets", JSONArray().apply { r.snippets.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("source", it.source)) } })
        .put("memories", JSONArray().apply { r.memoriesUsed.forEach { put(it.text) } })
        .put("notes", JSONArray().apply { r.memoryNotes.forEach { put(it) } })
        .put("trace", JSONArray().apply {
            // text průchodů UI nezobrazuje – neukládat (dřív až 20 000 znaků na průchod → pomalé načítání vláken)
            r.trace.forEach { put(JSONObject().put("index", it.index).put("label", it.pass.label).put("millis", it.millis).put("model", it.model)) }
        })
        .put("reasoning", r.reasoning.take(4_000))
        .also { m ->
            // EVIDENCE MATRIX: ze štítků [FAKT]/[ZDROJ]/[INFERENCE]/… a podkladů [n]; [FAKT] bez platného podkladu = [NEZNÁMÉ]
            val mx = cz.axiom.omega.core.agent.EvidenceMatrix.build(r.answer, r.snippets.map { it.source })
            if (mx.claims.size >= 2) m.put("matrix", JSONObject().put("independent", mx.independentSources).put("invalidRefs", mx.invalidRefs)
                .put("counts", JSONObject().apply { cz.axiom.omega.core.agent.EvidenceMatrix.Kind.entries.forEach { k -> put(k.name, mx.count(k)) } })
                .put("claims", JSONArray().apply { mx.claims.take(60).forEach { c -> put(JSONArray().put(c.kind.name).put(cz.axiom.omega.core.util.TextUtil.clip(c.text, 300)).put(JSONArray(c.refs))) } }))
        }

    // ------------------------------------------------------------------ mozky

    private fun download(id: String?, specJson: String?, cancel: CancelToken) {
        val job = omega.bg
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
                index(cancel) // průběh ukazuje štítek ↓ %, hláška přijde až po dokončení
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
        val job = omega.bg
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
        val job = omega.bg
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
        val job = omega.bg
        job.reset("library")
        val spec = cz.axiom.omega.core.library.Libraries.of(id) ?: error("Neznámá knihovna $id")
        val lib = cz.axiom.omega.data.LibraryDb.get(this)
        val hub = HfHub()
        if (lib.hasPrebuilt(id)) { Events.note("${spec.name} je už stažená."); return }
        // 1) Předpřipravená databáze z GitHub Release (rychlé: ~1 GB stažení, žádné indexování v telefonu)
        val manifest = try {
            job.phase = "Knihovna · zjišťuji balíček"; Events.emit("progress", job.toJson())
            cz.axiom.omega.core.library.Prebuilt.fetchManifest(spec)
        } catch (e: Exception) {
            if (cancel.cancelled) throw CancelledException()
            null
        }
        if (manifest != null) { libraryPrebuilt(spec, manifest, lib, cancel); return }
        // 2) Záloha: postupné stahování po 100 článcích z Hugging Face datasets-server
        var (offset, _, done) = lib.progress(id)
        if (done) { Events.note("${spec.name} je už stažená."); return }
        var failures = 0
        var lastNote = 0L
        val started = System.currentTimeMillis()
        val startOffset = offset
        try {
            while (offset < spec.rows) {
                cancel.check()
                if (!cz.axiom.omega.sys.Introspect.reserveBytes(this, filesDir, 800_000_000L)) error("Dochází místo v telefonu (méně než 0,8 GB). Stahování pozastaveno na $offset článcích.")
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

    private fun libraryPrebuilt(
        spec: cz.axiom.omega.core.library.LibrarySpec,
        m: cz.axiom.omega.core.library.PrebuiltManifest,
        lib: cz.axiom.omega.data.LibraryDb,
        cancel: CancelToken,
    ) {
        val job = omega.bg
        val P = cz.axiom.omega.core.library.Prebuilt
        val work = java.io.File(filesDir, "library-parts/${spec.id}")
        val have = work.listFiles()?.sumOf { it.length() } ?: 0L
        val free = cz.axiom.omega.sys.Introspect.allocatableBytes(this, filesDir)
        if (free + have < P.requiredBytes(m) || !cz.axiom.omega.sys.Introspect.reserveBytes(this, filesDir, P.requiredBytes(m) - have)) {
            error("Na ${spec.name} je potřeba ~%.1f GB volného místa (volno %.1f GB).".format(P.requiredBytes(m) / 1e9, free / 1e9))
        }
        val target = lib.prebuiltFile(spec.id)
        try {
            P.install(spec, m, work, target, cancel) { st ->
                val pct = if (st.total > 0) (st.bytes * 100 / st.total).toInt() else -1
                job.progress = pct
                job.phase = "Knihovna · ${st.phase}"
                job.detail = "%.2f / %.2f GB".format(st.bytes / 1e9, st.total / 1e9) +
                    if (st.bytesPerSec > 0) " · %.1f MB/s".format(st.bytesPerSec / 1e6) else ""
                updateNotification("${spec.name} · ${st.phase} $pct %", pct)
                Events.emit("progress", job.toJson())
            }
        } catch (e: CancelledException) {
            Events.note("Stahování knihovny pozastaveno – příště naváže.")
            Events.emit("kb", JSONObject())
            return
        }
        work.delete()
        lib.closePrebuilt(spec.id)
        lib.dropStreamed(spec.id)
        Events.emit("kb", JSONObject())
        Events.note("${spec.name} je stažená a funguje offline (%,d článků).".format(m.articles).replace(',', ' '))
    }

    /** RAG INDEX knihovny: stáhne předpočítané vektory (~140 MB) → hledání podle významu v celé Wikipedii. */
    private fun rag(id: String, cancel: CancelToken) {
        val job = omega.bg
        job.reset("library")
        val spec = cz.axiom.omega.core.library.Libraries.of(id) ?: error("Neznámá knihovna $id")
        val lib = cz.axiom.omega.data.LibraryDb.get(this)
        if (!lib.hasPrebuilt(id)) error("Nejdřív stáhni knihovnu ${spec.name} (předpřipravenou).")
        if (lib.hasRag(id)) { Events.note("RAG index pro ${spec.name} už je v telefonu."); return }
        job.phase = "RAG index · zjišťuji balíček"; Events.emit("progress", job.toJson())
        val m = cz.axiom.omega.core.rag.RagManifest.fetch(spec)
            ?: error("RAG index pro ${spec.name} zatím není sestavený (GitHub Actions → Build RAG index).")
        val pm = m.asPrebuilt()
        if (!cz.axiom.omega.sys.Introspect.reserveBytes(this, filesDir, cz.axiom.omega.core.library.Prebuilt.requiredBytes(pm))) error("Na RAG index je potřeba ~%.1f GB volného místa.".format(cz.axiom.omega.core.library.Prebuilt.requiredBytes(pm) / 1e9))
        val work = java.io.File(filesDir, "rag-parts/$id")
        try {
            cz.axiom.omega.core.library.Prebuilt.install(spec, pm, work, lib.ragFile(id), cancel, urlOf = { cz.axiom.omega.core.rag.RagManifest.url(spec, it.name) }) { st ->
                val pct = if (st.total > 0) (st.bytes * 100 / st.total).toInt() else -1
                job.progress = pct
                job.phase = "RAG index · ${spec.name} · ${st.phase.replace("databázi", "index")}"
                job.detail = "%.0f / %.0f MB".format(st.bytes / 1e6, st.total / 1e6)
                updateNotification("${spec.name} · RAG $pct %", pct)
                Events.emit("progress", job.toJson())
            }
        } catch (e: CancelledException) {
            Events.note("Stahování RAG indexu pozastaveno – příště naváže."); Events.emit("kb", JSONObject()); return
        }
        work.deleteRecursively()
        lib.closeRag(id)
        Events.emit("kb", JSONObject())
        val sense = omega.embedderFile() != null
        Events.note("RAG index ${spec.name}: %,d článků hledatelných podle významu.".format(m.count).replace(',', ' ') +
            if (sense) "" else " Ještě stáhni rozšíření OMEGA Sense (Moduly) – počítá význam dotazu.")
    }

    /**
     * SAMOSTUDIUM: AVIOR se vrátí k otázkám, na které neznal odpověď, dohledá je (online agentem,
     * offline z knihovny a poznatků), uloží si závěr jako poznatek a zapíše to do deníku.
     */
    private fun study(cancel: CancelToken) {
        val job = omega.job
        job.reset("study")
        val open = omega.cognition.openQuestions().takeLast(3)
        if (open.isEmpty()) { Events.note("Nemám žádné otevřené otázky – vše, na co jsem narazil, už vím."); return }
        val online = Introspect.online(this)
        val engine = omega.resolve(if ("local" in omega.availableEngines()) "local" else omega.availableEngines().firstOrNull() ?: "local")
        var learned = 0
        for ((i, q) in open.withIndex()) {
            cancel.check()
            val question = q.getString("q")
            job.phase = "Samostudium ${i + 1}/${open.size}"
            job.detail = question.take(80)
            updateNotification("Samostudium: ${question.take(60)}", (i * 100) / open.size)
            Events.emit("progress", job.toJson())
            val answer = runCatching {
                if (online) {
                    omega.agent().run("Samostudium – dohledej a ověř odpověď, stručně se zdroji: $question", emptyList(), engine, 6, cancel,
                        role = cz.axiom.omega.core.agent.AgentRole.RESEARCHER,
                        watchdog = Sup.Watchdog(3 * 60_000L, Sup.Authority.GOVERN)).answer
                } else {
                    omega.orchestrator().run(AskRequest(question, maxPasses = 2, reflect = true, thinking = true, rates = omega.settings.rates), engine, cancel).answer
                }
            }.getOrElse { if (it is CancelledException) throw it else null } ?: continue
            if (cz.axiom.omega.core.self.Cognition.isOpen(answer, null)) continue
            omega.cognition.learn(question, answer, 75, ok = true)
            omega.cognition.resolve(q.getString("key"), "Samostudium: dohledal jsem „${question.take(70)}“.")
            learned++
        }
        Events.emit("kb", JSONObject())
        Events.note(if (learned > 0) "Samostudium hotovo: naučil jsem se $learned ${if (learned == 1) "novou věc" else "nové věci"}." else "Samostudium: zatím jsem odpovědi nenašel, zkusím to příště.")
    }

    /** Články z Wikipedie k tématu → offline znalosti. */
    private fun wiki(intent: Intent, cancel: CancelToken) {
        val job = omega.bg
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
        val job = omega.bg
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

    private fun notification(text: String, progress: Int, lane: Lane = currentLane()): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, lane.notif, Intent(this, OmegaService::class.java).setAction(ACTION_CANCEL)
            .putExtra("lane", if (lane == Lane.BG) "bg" else "ask"), PendingIntent.FLAG_IMMUTABLE)
        if (lane == Lane.KEEP) {
            val unkeep = PendingIntent.getService(this, 9, Intent(this, OmegaService::class.java).setAction(ACTION_UNKEEP).putExtra("release", true), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(this, OmegaApp.CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_stat_omega)
                .setContentTitle("AVIOR")
                .setContentText(text)
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(this, OmegaApp.CHANNEL_WORK).setSmallIcon(R.drawable.ic_stat_omega).setContentTitle("AVIOR").setContentText("AVIOR pracuje").build())
                .addAction(0, "Uvolnit paměť", unkeep)
                .build()
        }
        return NotificationCompat.Builder(this, OmegaApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_omega)
            .setContentTitle("AVIOR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(this, OmegaApp.CHANNEL_WORK).setSmallIcon(R.drawable.ic_stat_omega).setContentTitle("AVIOR").setContentText("AVIOR pracuje").build())
            .addAction(0, if (lane == Lane.BG) "Pozastavit" else "Zastavit", stop)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private fun currentLane() = if (Thread.currentThread().name == "omega-bg") Lane.BG else Lane.ASK

    private val lastNotif = java.util.concurrent.ConcurrentHashMap<Lane, Long>()
    private fun updateNotification(text: String, progress: Int) {
        val lane = currentLane()
        val now = System.currentTimeMillis()
        if (now - (lastNotif[lane] ?: 0L) < 1000) return
        lastNotif[lane] = now
        getSystemService(NotificationManager::class.java).notify(lane.notif, notification(text, progress, lane))
    }

    private val wakes = java.util.concurrent.ConcurrentHashMap<Lane, PowerManager.WakeLock>()

    private fun acquireLocks(lane: Lane) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakes[lane] = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omega:${lane.name.lowercase()}")
            .apply { acquire(if (lane == Lane.BG) 4 * 3_600_000L else 30 * 60_000L) }
        if (lane == Lane.BG) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(mode, "omega:download").apply { acquire() }
        }
    }

    private fun releaseLocks(lane: Lane) {
        runCatching { wakes.remove(lane)?.takeIf { it.isHeld }?.release() }
        if (lane == Lane.BG) { runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null }
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
        const val ACTION_STUDY = "cz.axiom.omega.STUDY"
        const val ACTION_RAG = "cz.axiom.omega.RAG"
        const val ACTION_KEEP = "cz.axiom.omega.KEEP"
        const val ACTION_UNKEEP = "cz.axiom.omega.UNKEEP"
        /** Běží služba „AVIOR připraven“ (mozek držený v paměti)? */
        val KEEPING = AtomicBoolean(false)
        /** Akce linky „pozadí“ – běží souběžně s myšlením. */
        val BG_ACTIONS = setOf(ACTION_DOWNLOAD, ACTION_IMPORT, ACTION_DATASET, ACTION_WIKI, ACTION_LIBRARY, ACTION_INDEX, ACTION_RAG)
        val RUNNING_ASK = AtomicBoolean(false)
        val RUNNING_BG = AtomicBoolean(false)
        @Volatile private var askToken: CancelToken? = null
        @Volatile private var bgToken: CancelToken? = null

        /** Je obsazená linka, do které patří [action]? */
        /** Spustí / ukončí držení mozku v paměti (jen z popředí – Android jinak FGS nedovolí). */
        fun keep(context: Context, on: Boolean) {
            runCatching {
                if (on) ContextCompat.startForegroundService(context, Intent(context, OmegaService::class.java).setAction(ACTION_KEEP))
                else if (KEEPING.get()) context.startService(Intent(context, OmegaService::class.java).setAction(ACTION_UNKEEP))
            }
        }

        fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent.setClass(context, OmegaService::class.java))
        }

        /** Zastaví myšlení (tlačítko Stop u odpovědi). */
        fun cancel(context: Context) {
            askToken?.cancel()
            context.startService(Intent(context, OmegaService::class.java).setAction(ACTION_CANCEL).putExtra("lane", "ask"))
        }

        /** Pozastaví stahování / úlohu na pozadí (naváže příště). */
        fun cancelBg(context: Context) {
            bgToken?.cancel()
            context.startService(Intent(context, OmegaService::class.java).setAction(ACTION_CANCEL).putExtra("lane", "bg"))
        }
    }
}
