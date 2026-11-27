package cz.axiom.vega.selfupdate

import cz.axiom.vega.base.Text
import cz.axiom.vega.core.Kv
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AUTONOMIE, VRSTVA C – VEGA UPRAVUJE VLASTNÍ APLIKACI.
 * Tok: zadání (od uživatele, nebo z deníku slabin) → zdroj z GitHubu → úprava modelem v izolované kopii → pravidla cest
 * → commit na větev `vega/auto-…` → CI (testy BEZ podpisových klíčů, podpis až v odděleném jobu) → APK → ověření
 * balíčku a certifikátu → instalace potvrzená uživatelem (vyžaduje Android) → návrat = předchozí APK / nová úprava.
 */
object Policy {
    /** POVOLENÉ cesty (ne zakázané): kód aplikace, UI a testy. Res, manifest, sestavení, CI ani klíče sem nepatří. */
    private val ALLOWED = listOf("vega/src/main/java/", "vega/src/main/assets/ui/", "vegacore/src/main/kotlin/", "vegacore/src/test/kotlin/", "vega/src/test/java/")
    private val DENY = Regex("(\\.gradle(\\.kts)?$|AndroidManifest\\.xml$|/res/|proguard|\\.(jks|keystore|p12|pem|key)$|(^|/)\\.github/|(^|/)gradle/|local\\.properties)", RegexOption.IGNORE_CASE)
    /** Soubory s trezorem klíčů, mostem JS↔Kotlin, sítí a samotnými samoúpravami – samoúprava je měnit nesmí. */
    private val PROTECTED_FILES = Regex("(/Store\\.kt|/MainActivity\\.kt|/net/Http\\.kt|/core/Extensions\\.kt|/VegaApp\\.kt|/Engines\\.kt)$|/selfupdate/|/web/|/llm/")
    /**
     * Citlivá API v PŘIDANÝCH řádcích: klíče, síť, procesy, most, sandbox, samoúpravy. Model by je jinak mohl
     * (třeba po vložené instrukci v textu) použít k vynesení klíčů. Tentýž seznam kontroluje i CI (vega-auto.yml).
     */
    val SENSITIVE = Regex("(?i)(vault|keystore|getSharedPreferences|Http\\.(open|request|download|get)|HttpURLConnection|URLConnection|java\\.net\\.|Socket|Runtime\\.getRuntime|ProcessBuilder|" +
        "addJavascriptInterface|evaluateJavascript|loadUrl|XMLHttpRequest|WebSocket|EventSource|importScripts|fetch\\s*\\(|new\\s+Worker|postMessage|eval\\s*\\(|new\\s+Function|" +
        "selfupdate|GitHub\\(|gh\\(\\)|\\.headers|Web\\.|fetchText|toolCtx|engines\\.|REQUEST_INSTALL|PackageInstaller|DexClassLoader|System\\.loadLibrary|Class\\.forName|reflect|" +
        // kolo 15: únik přes Intent/aktivitu, reflexe přes text, soubory na disku, alias importu, brány plateb/souhlasu/air-gapu
        "Intent\\s*\\(|startActivity|sendBroadcast|ContentResolver|loadClass|getDeclared(Field|Method|Constructor)|getMethod\\s*\\(|getField\\s*\\(|java\\.io\\.|(^|[^\\p{L}\\p{N}_])File\\s*\\(|java\\.nio\\.file|Files\\.(write|read|copy|move|delete|new)|" +
        "import\\s+[\\p{L}\\p{N}_.]+\\s+as\\s|typealias|Policy|capKc|MAX_RUN_USD|approvePaid|paidHelps|airgap|consent|CostGuard|budget)")
    /** Neviditelné a obousměrné znaky (Trojan Source) – v kódu od modelu nemají co dělat. */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    /** Balíčky chráněného kódu: nový soubor se do nich nesmí „přihlásit“ a dostat se k internal členům. */
    private val PROTECTED_PACKAGES = Regex("^cz\\.axiom\\.vega\\.(selfupdate|net|llm|web)(\\.|$)")

    /**
     * Citlivé změny v diffu: přidané I SMAZANÉ řádky (smazání brány je stejně nebezpečné jako přidání úniku),
     * přidané řádky i slepené dohromady (obejití rozdělením volání na dva řádky) a neviditelné znaky.
     */
    fun sensitiveChanges(diff: String): List<String> {
        // hlavička souboru (---/+++) jen PŘED prvním @@ daného souboru – uvnitř hunku je i „+++i“ přidaný řádek „++i“ (dřív by prošel bez kontroly)
        // Soubory odděluje řádek „diff --git“ (uvnitř hunku začíná každý řádek +, - nebo mezerou, takže ho nejde podvrhnout).
        var inHunk = false
        val body = diff.lines().filter { l ->
            when {
                l.startsWith("diff --git ") -> { inHunk = false; false }
                l.startsWith("@@") -> { inHunk = true; false }
                !inHunk -> false // hlavička souboru (---/+++, index…)
                else -> l.startsWith("+") || l.startsWith("-")
            }
        }
        val hits = body.filter { SENSITIVE.containsMatchIn(it) || INVISIBLE.containsMatchIn(it) }.map { (if (INVISIBLE.containsMatchIn(it)) "neviditelný znak: " else "") + it.take(160) }
        if (hits.isNotEmpty()) return hits
        val joined = body.filter { it.startsWith("+") }.joinToString("") { it.drop(1).trim() }
        return SENSITIVE.find(joined)?.let { listOf("+… ${joined.substring(maxOf(0, it.range.first - 40), minOf(joined.length, it.range.last + 40))} (rozděleno na více řádků)") } ?: emptyList()
    }

    /** Řádek `package` v Kotlinu musí odpovídat složce a nesmí mířit do chráněného balíčku. null = v pořádku. */
    fun packageIssue(path: String, text: String): String? {
        if (!path.endsWith(".kt")) return null
        val root = listOf("vega/src/main/java/", "vegacore/src/main/kotlin/", "vegacore/src/test/kotlin/", "vega/src/test/java/").firstOrNull { path.startsWith(it) } ?: return null
        val expected = path.removePrefix(root).substringBeforeLast('/', "").replace('/', '.')
        val declared = Regex("(?m)^\\s*package\\s+([\\p{L}\\p{N}_.]+)").find(text)?.groupValues?.get(1) ?: ""
        return when {
            declared != expected -> "balíček „$declared“ neodpovídá složce („$expected“)"
            PROTECTED_PACKAGES.containsMatchIn(declared) -> "chráněný balíček $declared"
            else -> null
        }
    }

    /** null = cesta je povolená; jinak důvod. */
    fun check(path: String): String? = when {
        path.contains("..") || path.startsWith("/") || path.contains('\\') || path.any { it.code < 32 || it.code > 126 } -> "neplatná cesta"
        path.split('/').any { it.startsWith(".") || it.endsWith(".") || it.endsWith(" ") } -> "skrytý nebo zvláštní soubor (.gitattributes a podobné měnit nesmí)"
        ALLOWED.none { path.startsWith(it) } -> "mimo kód a UI VEGY (AVIOR, prostředky, manifest, CI ani sestavení měnit nesmí)"
        DENY.containsMatchIn(path) -> "chráněný soubor (sestavení, oprávnění, síťová politika, klíče)"
        PROTECTED_FILES.containsMatchIn(path) -> "chráněný soubor (trezor klíčů, most, síť nebo samoúpravy)"
        else -> null
    }
    fun editable(path: String) = check(path) == null && path.substringAfterLast('.').lowercase() in setOf("kt", "js", "css", "html", "md", "json", "txt", "xml")

    /** Soubory pro kontext modelu: podle slov zadání v cestě; jen upravitelné; v limitu znaků. */
    fun pickFiles(tree: Map<String, Long>, task: String, maxChars: Long = 40_000): List<String> {
        val words = Text.words(Text.fold(task).lowercase(), 4).map { it.take(5) }.toSet()
        val cand = tree.filter { (p, size) -> editable(p) && !p.contains("/test/") && size in 1..60_000 }
        val scored = cand.keys.map { p -> val n = Text.fold(p).lowercase(); p to words.count { n.contains(it) } * 10 + (if (p.endsWith("Ops.kt") || p.endsWith("app.js")) 2 else 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { cand[it.first] })
        val out = ArrayList<String>(); var used = 0L
        for ((p, score) in scored) { if (score <= 0 && out.isNotEmpty()) break; val sz = cand[p]!!; if (used + sz > maxChars) continue; out += p; used += sz }
        return out
    }

    fun branchName(task: String, now: Long = System.currentTimeMillis()): String {
        val slug = Text.fold(task).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).trim('-').ifBlank { "uprava" }
        return "vega/auto-${java.lang.Long.toString(now / 1000, 36)}-$slug"
    }
}

class SelfUpdateStore(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Status(val label: String) { PUSHED("odesláno, čeká na sestavení"), BUILDING("sestavuje se a testuje"), FAILED("sestavení nebo testy selhaly"),
        READY("připraveno k instalaci"), INSTALLED("nainstalováno"), DISCARDED("zahozeno") }
    @Serializable data class Event(val at: Long, val action: String)
    @Serializable data class Upd(val id: String, val task: String, val branch: String, val status: String, val files: List<String>, val commit: String = "",
                                 val runUrl: String = "", val pr: Long = 0, val diff: String = "", val apk: String = "", val apkSha: String = "", val note: String = "", val origin: String = "uživatel",
                                 val at: Long = 0, val history: List<Event> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun list(): List<Upd> = kv.keys("selfupdate/upd/").mapNotNull { k -> kv.read(k)?.let { runCatching { json.decodeFromString(Upd.serializer(), it) }.getOrNull() } }.sortedByDescending { it.at }
    fun get(id: String) = kv.read("selfupdate/upd/$id.json")?.let { runCatching { json.decodeFromString(Upd.serializer(), it) }.getOrNull() }
    fun save(u: Upd) = kv.write("selfupdate/upd/${u.id}.json", json.encodeToString(Upd.serializer(), u))
    fun create(task: String, branch: String, files: List<String>, commit: String, origin: String): Upd {
        val id = "u" + java.lang.Long.toString(clock(), 36)
        return Upd(id, task.take(300), branch, Status.PUSHED.name, files, commit, origin = origin, at = clock(), history = listOf(Event(clock(), "odesláno na ${branch}"))).also { save(it) }
    }
    fun update(id: String, f: (Upd) -> Upd): Upd? = get(id)?.let { f(it) }?.also { save(it) }
    fun mark(u: Upd, st: Status, note: String = "", extra: (Upd) -> Upd = { it }) = extra(u.copy(status = st.name, note = note, history = u.history + Event(clock(), st.label + if (note.isNotBlank()) " – $note" else ""))).also { save(it) }

    /** Poslední automatický návrh (deník slabin) – nejvýš jeden denně. */
    fun lastAuto(): Long = kv.read("selfupdate/lastAuto")?.toLongOrNull() ?: 0L
    fun setLastAuto(t: Long = clock()) = kv.write("selfupdate/lastAuto", t.toString())
}
