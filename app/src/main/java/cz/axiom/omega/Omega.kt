package cz.axiom.omega

import android.content.Context
import android.os.Handler
import android.os.Looper
import cz.axiom.omega.brain.BrainManager
import cz.axiom.omega.core.agent.ActiveAgent
import cz.axiom.omega.core.agent.AgentTool
import cz.axiom.omega.core.agent.KnowledgeSource
import cz.axiom.omega.core.agent.Orchestrator
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.agent.Tools
import cz.axiom.omega.engine.Embedder
import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.llm.EngineException
import cz.axiom.omega.core.llm.EngineKind
import cz.axiom.omega.core.llm.Http
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.llm.LlmEngine
import cz.axiom.omega.core.self.SelfState
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.data.Db
import cz.axiom.omega.data.Settings
import cz.axiom.omega.data.Vault
import cz.axiom.omega.engine.LocalEngine
import cz.axiom.omega.engine.NativeLlama
import cz.axiom.omega.sys.Introspect
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Události Kotlin → UI. Posluchač (MainActivity) je přeposílá do WebView. */
object Events {
    private val listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: (String, JSONObject) -> Unit) { listeners += l }
    fun unlisten(l: (String, JSONObject) -> Unit) { listeners -= l }

    fun emit(type: String, data: JSONObject = JSONObject()) {
        main.post { listeners.forEach { runCatching { it(type, data) } } }
    }

    fun note(text: String) = emit("note", JSONObject().put("text", text))
}

/** Stav běžící úlohy – přežije zavření aktivity (UI si ho po návratu přečte). */
class JobState {
    @Volatile var busy = false
    @Volatile var kind = ""
    @Volatile var phase = ""
    @Volatile var threadId = 0L
    @Volatile var progress = -1
    @Volatile var detail = ""
    val partial = StringBuffer()
    val reasoning = StringBuffer()
    @Volatile var pass = 0
    @Volatile var passes = 0

    fun reset(kind: String, threadId: Long = 0) {
        busy = true; this.kind = kind; this.threadId = threadId
        phase = "Připravuji…"; progress = -1; detail = ""; pass = 0; passes = 0
        partial.setLength(0); reasoning.setLength(0)
    }

    fun toJson(): JSONObject = JSONObject().put("busy", busy).put("kind", kind).put("phase", phase).put("threadId", threadId)
        .put("progress", progress).put("detail", detail).put("partial", partial.toString())
        .put("reasoning", reasoning.toString().takeLast(4000)).put("pass", pass).put("passes", passes)
}

/** Kořen aplikace: nastavení, trezor, databáze, mozky, výběr enginu a sebemodel. */
class Omega private constructor(val app: Context) {
    val settings = Settings(app)
    val vault = Vault(app)
    val db: Db get() = Db.get(app)
    val packs = cz.axiom.omega.data.PackManager(app)
    val brains = BrainManager(app)
    /** Linka myšlení (odpovědi, agenti, samostudium). */
    val job = JobState()
    /** Linka pozadí (stahování mozku, knihovny, rozšíření, indexace) – běží souběžně s myšlením. */
    val bg = JobState()
    private val confidences = ArrayDeque<Int>()

    fun orchestrator(): Orchestrator = Orchestrator(knowledge(), db, { selfState(currentLabel, currentKind) })

    /** Hybridní znalosti: fulltext + (je-li nainstalovaný OMEGA Sense) hledání podle významu. */
    fun knowledge(): KnowledgeSource {
        val d = db
        return object : KnowledgeSource {
            override fun search(query: String, limit: Int): List<Snippet> {
                val lib = runCatching { cz.axiom.omega.data.LibraryDb.get(app).search(query, (limit / 2).coerceAtLeast(2), minScore = 3) }.getOrDefault(emptyList())
                return (docs(query, limit) + lib).distinctBy { it.title }.take(limit + lib.size.coerceAtMost(2))
            }

            private fun docs(query: String, limit: Int): List<Snippet> {
                val words = d.search(query, limit)
                // sémantika jen když má co hledat (indexované úseky) – jinak by zbytečně načítala 600MB model
                if (!settings.semantic || d.vectorCount() == 0 || !ensureEmbedder()) return words
                val q = Embedder.embedQuery(query) ?: return words
                val sem = d.semantic(q, limit).filter { it.second > 0.35f }.map { it.first }
                // proložit: nejlepší sémantické + nejlepší slovní, bez duplicit
                val out = LinkedHashMap<String, Snippet>()
                val it1 = sem.iterator(); val it2 = words.iterator()
                while (out.size < limit && (it1.hasNext() || it2.hasNext())) {
                    if (it1.hasNext()) it1.next().let { out.putIfAbsent("${it.id}:${it.text.take(40)}", it) }
                    if (out.size < limit && it2.hasNext()) it2.next().let { out.putIfAbsent("${it.id}:${it.text.take(40)}", it) }
                }
                return out.values.toList()
            }
        }
    }

    fun embedderFile(): java.io.File? = brains.catalog().firstOrNull { it.kind == "embedding" && brains.isInstalled(it) }?.let { brains.fileFor(it) }

    fun ensureEmbedder(): Boolean {
        if (Embedder.ready()) return true
        val f = embedderFile() ?: return false
        // Při málo volné RAM (8GB telefon s načteným mozkem) sémantiku vynech – fulltext stačí
        if (cz.axiom.omega.sys.Introspect.freeRamMb(app) in 1..1_500) return false
        return runCatching { Embedder.open(app, f, minOf(device.threads, 4)) }.getOrDefault(false)
    }

    /** Nástroje aktivního agenta (síťové jen online). */
    fun agentTools(): List<AgentTool> {
        val offline = Tools.offline(knowledge(), db, { settings.rates }) { title, text -> db.addDocument(title, text, "Agent") } +
            Tools.securityOffline() + libraryTool() + Tools.files(forge)
        return if (Introspect.online(app)) offline + Tools.online() + Tools.securityOnline() else offline
    }

    private fun libraryTool(): AgentTool = object : AgentTool {
        override val name = "library"
        override val args = """{"q": "dotaz"}"""
        override val description = "offline encyklopedie v telefonu (stažená Wikipedie) – funguje bez internetu"
        override fun run(args: kotlinx.serialization.json.JsonObject, cancel: cz.axiom.omega.core.llm.CancelToken): String {
            val q = (args["q"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return "Chybí q."
            val r = cz.axiom.omega.data.LibraryDb.get(app).search(q, 4)
            if (r.isEmpty()) return "V offline knihovně nic (je stažená? Paměť → Offline databáze → Knihovna)."
            return r.joinToString("\n\n") { "${it.title} (${it.source}):\n${it.text.take(1_400)}" }
        }
    }

    /** Profil zařízení (např. Google Pixel 9a): vlákna podle výkonných jader, kontext podle RAM a režimu. */
    val device by lazy {
        val freqs = (0 until 16).mapNotNull { i ->
            runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL.takeIf { it.isNotBlank() && it != "unknown" } else null
        cz.axiom.omega.core.device.DeviceProfiles.detect(
            android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, android.os.Build.MODEL, soc,
            cz.axiom.omega.core.brain.BrainCatalog.nominalRamGb(Introspect.totalRamGb(app)), freqs,
        )
    }

    fun power() = cz.axiom.omega.core.device.Power.of(settings.power)
    fun effectiveCtx() = if (settings.ctxManual) settings.ctxTokens else device.ctxFor(power())

    fun loraDir() = java.io.File(app.filesDir, "lora").apply { mkdirs() }

    val randy by lazy { cz.axiom.omega.data.RandyStore(app) }
    val cognition by lazy { cz.axiom.omega.data.CognitionStore(app, db) }

    val forge by lazy { cz.axiom.omega.forge.AndroidForge(app) }

    fun agent() = ActiveAgent(agentTools(), db) { selfState(currentLabel, currentKind) }

    @Volatile private var currentLabel = "neurčen"
    @Volatile private var currentKind = EngineKind.LOCAL

    fun rememberConfidence(c: Int?) {
        if (c == null) return
        synchronized(confidences) { confidences.addLast(c); while (confidences.size > 20) confidences.removeFirst() }
    }

    fun selfState(label: String = currentLabel, kind: EngineKind = currentKind): SelfState {
        val (bat, charging) = Introspect.battery(app)
        val brain = brains.active()
        val avg = synchronized(confidences) { if (confidences.isEmpty()) null else confidences.average().toInt() }
        return SelfState(
            engineLabel = label,
            engineKind = kind.label,
            online = Introspect.online(app),
            brainName = brain?.second?.name ?: brain?.first?.nameWithoutExtension,
            contextTokens = if (kind == EngineKind.LOCAL) effectiveCtx() else null,
            memoryCount = db.memoryCount(),
            knowledgeDocs = db.docCount(),
            conversations = db.threadCount(),
            batteryPct = bat,
            charging = charging,
            freeRamMb = Introspect.freeRamMb(app),
            thermal = Introspect.thermal(app),
            localTime = Introspect.now(),
            selfConfidenceAvg = avg,
        )
    }

    // ------------------------------------------------------------------ enginy

    fun local(): LocalEngine {
        val (file, spec) = brains.active() ?: throw EngineException("Lokální mozek není stažený. Otevři Mozky a stáhni doporučený.")
        if (LocalEngine.loadedLabel() == null) {
            cz.axiom.omega.Events.emit("phase", org.json.JSONObject().put("text", "Načítám mozek ${spec?.name ?: ""} do paměti – jen u prvního dotazu, chvilku…"))
        }
        val lora = settings.lora.takeIf { it.isNotBlank() }?.let { java.io.File(loraDir(), it) }?.takeIf { it.exists() }
        return LocalEngine.obtain(app, file, spec, effectiveCtx(), lora, settings.loraScale / 100f, device.threads)
    }

    fun kimi(): KimiEngine {
        val key = vault.read(Vault.KIMI) ?: throw EngineException("Chybí API klíč Kimi (platform.moonshot.ai). Zadej ho v Systému.")
        return KimiEngine(settings.kimiBase, key, settings.kimiModel, EngineKind.KIMI_CLOUD)
    }

    fun server(): KimiEngine {
        val url = settings.serverUrl.ifBlank { throw EngineException("Není nastavená adresa vlastního Kimi K3 serveru.") }
        Http.requireAllowed(url)
        return KimiEngine(url, vault.read(Vault.SERVER), settings.serverModel, EngineKind.KIMI_SERVER)
    }

    fun node(): KimiEngine {
        val url = settings.nodeUrl.ifBlank { throw EngineException("Není nastavená adresa AVIOR Node (Moduly → AVIOR Node).") }
        Http.requireAllowed(url)
        return KimiEngine(url, vault.read(Vault.NODE), settings.nodeModel, EngineKind.NODE)
    }

    /** Mozky, které jsou právě použitelné, v pořadí síly (Claude je až poslední – AVIOR na něm nezávisí). */
    fun availableEngines(): List<String> {
        val online = Introspect.online(app)
        // Primární je výkonný mozek v telefonu; cloudy jsou jen volitelné doplňky.
        return buildList {
            if (localAvailable()) add("local")
            if (online && settings.nodeUrl.isNotBlank()) add("node")
            if (settings.serverUrl.isNotBlank()) add("server")
            if (online && vault.has(Vault.KIMI)) add("kimi")
            if (online && vault.has(Vault.CLAUDE)) add("claude")
        }
    }

    fun claude(): ClaudeEngine {
        val key = vault.read(Vault.CLAUDE) ?: throw EngineException("Chybí API klíč Claude (console.anthropic.com). Zadej ho v Systému.")
        return ClaudeEngine(key, settings.claudeModel)
    }

    fun localAvailable() = NativeLlama.available(app) && brains.active() != null

    /** AUTO: online → Kimi K3, jinak Claude; offline → lokální mozek, jinak vlastní K3 server. */
    fun resolve(mode: String = settings.mode): LlmEngine {
        val e: LlmEngine = when (mode) {
            "local" -> local()
            "kimi" -> kimi()
            "node" -> node()
            "server" -> server()
            "claude" -> claude()
            else -> {
                val online = Introspect.online(app)
                when {
                    localAvailable() -> local()
                    online && settings.nodeUrl.isNotBlank() -> node()
                    settings.serverUrl.isNotBlank() -> server()
                    online && vault.has(Vault.KIMI) -> kimi()
                    online && vault.has(Vault.CLAUDE) -> claude()
                    else -> throw EngineException(
                        if (online) "Žádný mozek: stáhni mozek do telefonu (Moduly → Gemma 4)."
                        else "Jsi offline a lokální mozek není stažený. Připoj se a stáhni ho v sekci Mozky.",
                    )
                }
            }
        }
        currentLabel = e.label
        currentKind = e.kind
        return e
    }

    fun councilMembers(): Pair<List<LlmEngine>, LlmEngine> {
        val online = Introspect.online(app)
        val members = buildList {
            if (online && vault.has(Vault.KIMI)) runCatching { kimi() }.getOrNull()?.let(::add)
            if (online && settings.nodeUrl.isNotBlank()) runCatching { node() }.getOrNull()?.let(::add)
            if (online && vault.has(Vault.CLAUDE)) runCatching { claude() }.getOrNull()?.let(::add)
            if (settings.serverUrl.isNotBlank()) runCatching { server() }.getOrNull()?.let(::add)
            if (localAvailable()) runCatching { local() }.getOrNull()?.let { add(0, it) }
        }
        if (members.size < 2) throw EngineException("Konzilium potřebuje aspoň 2 mozky (např. Kimi K3 + Claude, nebo cloud + lokální).")
        val judge = when (settings.councilJudge) {
            "kimi" -> members.firstOrNull { it.kind == EngineKind.KIMI_CLOUD }
            "server" -> members.firstOrNull { it.kind == EngineKind.KIMI_SERVER }
            "node" -> members.firstOrNull { it.kind == EngineKind.NODE }
            "local" -> members.firstOrNull { it.kind == EngineKind.LOCAL }
            "claude" -> members.firstOrNull { it.kind == EngineKind.CLAUDE }
            else -> members.firstOrNull { it.kind == EngineKind.KIMI_CLOUD }
        } ?: members.first()
        currentLabel = "Konzilium"
        currentKind = judge.kind
        return members to judge
    }

    /** Kurzy ČNB jednou denně (jen online, chyba nevadí – zůstanou poslední/vestavěné). */
    @Volatile private var ratesRefreshing = false

    /** Kurzy ČNB se obnovují na pozadí – dotaz na ně nikdy nečeká (dřív až 35 s bez signálu). */
    fun refreshRatesIfStale() {
        if (System.currentTimeMillis() - settings.ratesAt < 12 * 3_600_000L || ratesRefreshing) return
        ratesRefreshing = true
        Thread({ try { refreshRatesNow() } finally { ratesRefreshing = false } }, "omega-rates").start()
    }

    private fun refreshRatesNow() {
        if (!Introspect.online(app)) return
        runCatching {
            val c = Http.open(Rates.CNB_URL, readMs = 15_000)
            try {
                if (c.responseCode == 200) settings.rates = Rates.parseCnb(Http.readAll(c.inputStream, 200_000))
            } finally {
                c.disconnect()
            }
        }
    }

    companion object {
        @Volatile private var instance: Omega? = null
        fun get(context: Context): Omega = instance ?: synchronized(this) { instance ?: Omega(context.applicationContext).also { instance = it } }
    }
}
