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
    private val ALLOWED = listOf("vega/src/", "vegacore/src/")
    private val DENY = Regex("(\\.gradle(\\.kts)?$|AndroidManifest\\.xml$|/res/xml/|proguard|\\.(jks|keystore|p12|pem|key)$|(^|/)\\.github/|(^|/)gradle/|local\\.properties)", RegexOption.IGNORE_CASE)

    /** null = cesta je povolená; jinak důvod. */
    fun check(path: String): String? = when {
        path.contains("..") || path.startsWith("/") || path.contains('\\') -> "neplatná cesta"
        ALLOWED.none { path.startsWith(it) } -> "mimo vega/ a vegacore/ (AVIOR, CI ani sestavení VEGA měnit nesmí)"
        DENY.containsMatchIn(path) -> "chráněný soubor (sestavení, oprávnění, síťová politika, klíče)"
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
                                 val runUrl: String = "", val pr: Long = 0, val apk: String = "", val apkSha: String = "", val note: String = "", val origin: String = "uživatel",
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
