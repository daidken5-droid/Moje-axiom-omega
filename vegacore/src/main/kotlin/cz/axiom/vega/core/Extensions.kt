package cz.axiom.vega.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * AUTONOMIE, VRSTVA B – ROZŠÍŘENÍ: nástroj v JavaScriptu, který VEGA napíše (projekt s `tool.js` + `*.test.js`).
 *
 * Pravidla:
 *  - kód běží jen v izolovaném Workeru WebView: bez sítě, bez úložiště, bez přístupu k aplikaci, s limitem času –
 *    jen vstup (text) → výstup (text). Oprávnění žádná a nejdou přidat,
 *  - zapnout jde jen verze, jejíž PŘESNÝ otisk prošel testy ([Ext.testedSha] == otisk kódu) – změna po testu = znovu testovat,
 *  - každá verze se uchová (max. [KEEP_VERSIONS]) → „Vrátit“ obnoví předchozí otestovanou verzi,
 *  - nové rozšíření je vypnuté, dokud ho uživatel nezapne (nebo dokud nepovolí samozapínání bezpečných rozšíření).
 */
class Extensions(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable data class Version(val v: Int, val code: String, val sha: String, val tests: Map<String, String>, val testedSha: String, val testOutput: String, val at: Long)
    @Serializable data class Event(val at: Long, val action: String, val note: String = "")
    @Serializable data class Ext(val id: String, val name: String, val description: String, val enabled: Boolean, val current: Int,
                                 val versions: List<Version>, val origin: String = "", val history: List<Event> = emptyList()) {
        fun cur(): Version = versions.first { it.v == current }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private fun k(id: String) = "ext/$id.json"
    fun list(): List<Ext> = kv.keys("ext/").mapNotNull { get(it.removePrefix("ext/").removeSuffix(".json")) }.sortedBy { it.name }
    fun get(id: String): Ext? = kv.read(k(id))?.let { runCatching { json.decodeFromString(Ext.serializer(), it) }.getOrNull() }
    private fun save(e: Ext) = kv.write(k(e.id), json.encodeToString(Ext.serializer(), e))

    /**
     * Uloží novou verzi z projektu. [testExit] a [testOutput] musí pocházet z běhu testů nad PŘESNĚ tímto [code]
     * a [tests] ([testedSha] spočítá volající ze souborů, které testům předal). Neprošlé testy = nic se neuloží.
     */
    fun saveVersion(name: String, description: String, code: String, tests: Map<String, String>, testExit: Int, testOutput: String, testedSha: String, origin: String = ""): Ext = synchronized(lock) {
        require(tests.isNotEmpty()) { "Rozšíření musí mít testy (*.test.js)." }
        require(code.contains("run")) { "tool.js musí exportovat funkci run(input, params)." }
        require(code.length <= MAX_CODE) { "Kód rozšíření je příliš velký (${code.length} znaků)." }
        val sha = shaOf(code, tests)
        require(testExit == 0) { "Testy neprošly – rozšíření se neuloží.\n" + testOutput.take(600) }
        require(testedSha == sha) { "Testovaný kód se liší od ukládaného – spusť testy znovu." }
        val id = idOf(name)
        val old = get(id)
        val n = (old?.versions?.maxOfOrNull { it.v } ?: 0) + 1
        val ver = Version(n, code, sha, tests, testedSha, testOutput.take(4000), clock())
        val e = if (old == null) Ext(id, name.take(60), description.take(300), false, n, listOf(ver), origin, listOf(Event(clock(), "uloženo v$n", "testy prošly")))
                else old.copy(description = description.take(300), current = n, versions = (old.versions + ver).takeLast(KEEP_VERSIONS),
                              history = old.history + Event(clock(), "nová verze v$n", if (old.enabled) "zůstává zapnuté" else "testy prošly"))
        save(e); e
    }

    fun setEnabled(id: String, on: Boolean): Ext? = synchronized(lock) {
        val e = get(id) ?: return null; val c = e.cur()
        if (on && c.testedSha != c.sha) throw IllegalStateException("Verze v${c.v} neprošla testy ve stejné podobě – nejde zapnout.")
        e.copy(enabled = on, history = e.history + Event(clock(), if (on) "zapnuto" else "vypnuto", "v${c.v}")).also { save(it) }
    }

    /** Vrátit na předchozí uloženou verzi (ta prošla testy, jinak by se neuložila). */
    fun rollback(id: String): Ext? = synchronized(lock) {
        val e = get(id) ?: return null
        val prev = e.versions.filter { it.v < e.current }.maxByOrNull { it.v } ?: return null
        e.copy(current = prev.v, history = e.history + Event(clock(), "vráceno na v${prev.v}", "z v${e.current}")).also { save(it) }
    }
    fun delete(id: String) = synchronized(lock) { kv.delete(k(id)) }

    companion object {
        const val KEEP_VERSIONS = 5
        const val MAX_CODE = 200_000
        fun idOf(name: String) = cz.axiom.vega.base.Text.fold(name).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "rozsireni" }
        /** Otisk kódu + testů (pořadí souborů nehraje roli). */
        fun shaOf(code: String, tests: Map<String, String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(code.toByteArray(Charsets.UTF_8)); tests.toSortedMap().forEach { (k, v) -> md.update(0); md.update(k.toByteArray()); md.update(0); md.update(v.toByteArray()) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
