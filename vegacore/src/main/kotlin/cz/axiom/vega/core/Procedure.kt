package cz.axiom.vega.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Trvalé úložiště klíč → text (aplikace: soubory s atomickým zápisem; testy: mapa). */
interface Kv {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun keys(prefix: String): List<String>
    /** Čas poslední změny (ms), 0 = neznámý (úklid podle stáří ho pak nechá být). */
    fun modified(key: String): Long = 0L
}

class MemKv(private val clock: () -> Long = System::currentTimeMillis) : Kv {
    val map = LinkedHashMap<String, String>()
    val times = HashMap<String, Long>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String) { map[key] = value; times[key] = clock() }
    override fun delete(key: String) { map.remove(key); times.remove(key) }
    override fun modified(key: String) = times[key] ?: 0L
    override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}

@Serializable
data class Step(val tool: String, val params: Map<String, String> = emptyMap(), val note: String = "")

/**
 * ULOŽENÝ POSTUP: verze, popis vstupu, výstup, oprávnění (sjednocení nástrojů), kroky. Spustí se ručně tlačítkem
 * i z chatu („zpracuj výpis“) – oba vstupy volají [Runner].
 */
@Serializable
data class Procedure(
    val id: String,
    val name: String,
    val version: Int = 1,
    val description: String = "",
    val inputKind: String = "TEXT",
    val inputDesc: String = "",
    val outputKind: String = "TABLE",
    val perms: List<String> = emptyList(),
    val steps: List<Step>,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val uses: Int = 0,
    /** Změny mezi verzemi (pro uživatele: co se změnilo). */
    val changelog: List<String> = emptyList(),
    /** Připnuté tlačítko (zobrazí se nad vstupem). */
    val pinned: Boolean = false,
    /** Na kterých souborech byl ověřen (názvy) – druhý soubor = ověřeno mimo původní data. */
    val verifiedOn: List<String> = emptyList(),
)

class ProcedureStore(private val kv: Kv) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<Procedure> = kv.keys("proc/").mapNotNull { k -> runCatching { json.decodeFromString(Procedure.serializer(), kv.read(k)!!) }.getOrNull() }.sortedByDescending { it.updated }
    fun get(id: String) = kv.read("proc/$id")?.let { runCatching { json.decodeFromString(Procedure.serializer(), it) }.getOrNull() }

    /** Uloží postup; existuje-li se stejným názvem, zvýší verzi a zapíše změnu (předchozí verze zůstane v historii). */
    fun save(name: String, steps: List<Step>, description: String, inputKind: Kind, inputDesc: String, outputKind: Kind, change: String = "první verze", pinned: Boolean = false, verifiedOn: List<String> = emptyList()): Procedure {
        require(steps.isNotEmpty()) { "Postup bez kroků" }
        steps.forEach { require(Tools.get(it.tool) != null) { "Neznámý nástroj ${it.tool}" } }
        val perms = steps.flatMap { Tools.get(it.tool)!!.perms }.distinct().map { it.label }
        val old = list().firstOrNull { it.name.equals(name, true) }
        val p = if (old != null) {
            kv.write("proc-hist/${old.id}/${old.version}", json.encodeToString(Procedure.serializer(), old))
            old.copy(version = old.version + 1, steps = steps, description = description, inputKind = inputKind.name, inputDesc = inputDesc,
                outputKind = outputKind.name, perms = perms, updated = System.currentTimeMillis(), changelog = old.changelog + "v${old.version + 1}: $change",
                pinned = pinned || old.pinned, verifiedOn = (old.verifiedOn + verifiedOn).distinct())
        } else Procedure(slug(name), name, 1, description, inputKind.name, inputDesc, outputKind.name, perms, steps, changelog = listOf("v1: $change"), pinned = pinned, verifiedOn = verifiedOn)
        kv.write("proc/${p.id}", json.encodeToString(Procedure.serializer(), p))
        return p
    }

    fun setPinned(id: String, v: Boolean) { get(id)?.let { kv.write("proc/$id", json.encodeToString(Procedure.serializer(), it.copy(pinned = v))) } }

    /** Kroky beze změny → žádná nová verze (opakované „ulož“ nevyrábí v2, v3…). */

    fun used(p: Procedure) { val cur = get(p.id) ?: return; kv.write("proc/${p.id}", json.encodeToString(Procedure.serializer(), cur.copy(uses = cur.uses + 1))) }

    fun delete(id: String) { kv.delete("proc/$id"); kv.keys("proc-hist/$id/").forEach { kv.delete(it) } }

    /** Postup podle slov v chatu („zpracuj výpis“ → „Zpracovat výpis“). */
    fun find(text: String): Procedure? {
        // celá slova (shodný začátek slova, ne podřetězec): „fakt dobrý nápad“ ≠ „Faktury“, „nezpracovávej“ ≠ „zpracovat“
        val tw = cz.axiom.vega.base.Text.words(cz.axiom.vega.base.Text.fold(text).lowercase(), 3)
        return list().firstOrNull { p ->
            val words = cz.axiom.vega.base.Text.words(cz.axiom.vega.base.Text.fold(p.name).lowercase(), 3)
            fun hit(w: String) = tw.any { x -> val k = maxOf(5, minOf(w.length, x.length) - 3).coerceAtMost(w.length); x.length >= k && w.length >= k && x.take(k) == w.take(k) }
            words.isNotEmpty() && (words.size >= 2 || tw.size <= 6) && words.all { hit(it) }
        }
    }

    /** Návrat na předchozí uloženou verzi postupu (historie proc-hist); vrátí obnovenou verzi s novým číslem. */
    fun revert(id: String): Procedure? {
        val cur = get(id) ?: return null
        val prevKey = kv.keys("proc-hist/$id/").maxByOrNull { it.substringAfterLast('/').toIntOrNull() ?: 0 } ?: return null
        val prev = runCatching { json.decodeFromString(Procedure.serializer(), kv.read(prevKey)!!) }.getOrNull() ?: return null
        kv.write("proc-hist/$id/${cur.version}", json.encodeToString(Procedure.serializer(), cur))
        kv.delete(prevKey)
        val p = prev.copy(version = cur.version + 1, updated = System.currentTimeMillis(), changelog = cur.changelog + "v${cur.version + 1}: vráceno na v${prev.version}", uses = cur.uses, pinned = cur.pinned)
        kv.write("proc/$id", json.encodeToString(Procedure.serializer(), p)); return p
    }

    private fun slug(n: String): String {
        val base = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "postup" }
        var s = base; var i = 2
        while (kv.read("proc/$s") != null) s = "$base-${i++}"
        return s
    }
}

/** Výsledek jednoho kroku – každý mezivýsledek jde otevřít. */
data class StepResult(val index: Int, val tool: String, val value: Value?, val error: String?, val millis: Long)

/**
 * BĚH POSTUPU s kontrolními body: po každém kroku se stav uloží. Při chybě zůstane vstup i hotové kroky a
 * [Runner.resume] pokračuje od posledního hotového kroku (i po pádu aplikace).
 */
data class Run(val id: String, val name: String, val steps: List<Step>, val input: Value, val results: List<StepResult>, val started: Long = System.currentTimeMillis()) {
    val done get() = results.size == steps.size && results.all { it.error == null }
    val failed get() = results.lastOrNull()?.error != null
    val last: Value get() = results.lastOrNull { it.error == null }?.value ?: input
    val nextIndex get() = results.count { it.error == null }

    fun toJson(enc: (Value) -> kotlinx.serialization.json.JsonElement = { Value.toJson(it) }): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("started", started)
        put("steps", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Step.serializer()), steps))
        put("input", enc(input))
        put("results", buildJsonArray { results.forEach { r -> add(buildJsonObject {
            put("i", r.index); put("tool", r.tool); put("ms", r.millis); r.error?.let { put("error", it) }; r.value?.let { put("value", enc(it)) }
        }) } })
    }

    companion object {
        fun fromJson(o: JsonObject, dec: (JsonObject) -> Value = { Value.fromJson(it) }) = Run(
            o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
            Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Step.serializer()), o["steps"]!!),
            dec(o["input"]!!.jsonObject),
            o["results"]!!.jsonArray.map { e -> val r = e.jsonObject
                StepResult(r["i"]!!.jsonPrimitive.content.toInt(), r["tool"]!!.jsonPrimitive.content, r["value"]?.jsonObject?.let { dec(it) }, r["error"]?.jsonPrimitive?.content, r["ms"]!!.jsonPrimitive.content.toLong())
            }, o["started"]?.jsonPrimitive?.content?.toLong() ?: 0L,
        )
    }
}

object Runner {
    /** Spustí kroky od [from]; [save] se volá po každém kroku (kontrolní bod). */
    fun run(run: Run, ctx: ToolCtx, save: (Run) -> Unit = {}): Run {
        var cur = run.copy(results = run.results.filter { it.error == null }.take(run.nextIndex))
        for (i in cur.nextIndex until cur.steps.size) {
            val s = cur.steps[i]
            val tool = Tools.get(s.tool)
            val t0 = System.nanoTime()
            val res = try {
                tool ?: throw ToolError("Nástroj ${s.tool} neexistuje")
                StepResult(i, s.tool, tool.run(cur.last, s.params, ctx), null, (System.nanoTime() - t0) / 1_000_000)
            } catch (e: cz.axiom.vega.llm.CancelledException) {
                StepResult(i, s.tool, null, "Zrušeno – pokračuj od kroku ${i + 1}", (System.nanoTime() - t0) / 1_000_000)
            } catch (e: Throwable) { // i StackOverflowError / OutOfMemoryError z nástroje = selhaný krok s kontrolním bodem, ne pád úlohy
                StepResult(i, s.tool, null, e.message ?: e.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000)
            }
            cur = cur.copy(results = cur.results + res)
            runCatching { save(cur) } // neuložitelný kontrolní bod (paměť, místo) nesmí shodit hotový výsledek
            if (res.error != null) break
        }
        return cur
    }

    fun start(name: String, steps: List<Step>, input: Value, ctx: ToolCtx, save: (Run) -> Unit = {}): Run =
        run(Run("run-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random(), name, steps, input, emptyList()), ctx, save)

    /** Pokračování po chybě/pádu: hotové kroky se neopakují. */
    fun resume(run: Run, ctx: ToolCtx, save: (Run) -> Unit = {}) = run(run, ctx, save)
}

fun runsOf(kv: Kv): List<Run> = kv.keys("run/").mapNotNull { k -> loadRun(kv, k.removePrefix("run/")) }.sortedByDescending { it.started }

/** Soubory nad tuto velikost jdou do kontrolního bodu jako odkaz na blob (jednou), ne jako base64 po každém kroku. */
private const val RUN_BLOB_MIN = 256_000
private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
fun saveRun(kv: Kv, r: Run) = kv.write("run/${r.id}", r.toJson { v ->
    if (v is Value.Bytes && v.bytes.size >= RUN_BLOB_MIN) {
        val sha = sha256(v.bytes)
        if (kv.modified("runblob/$sha") <= 0L) kv.write("runblob/$sha", java.util.Base64.getEncoder().encodeToString(v.bytes))
        buildJsonObject { put("k", "bytesRef"); put("name", v.name); v.mime?.let { put("mime", it) }; put("sha", sha) }
    } else Value.toJson(v)
}.toString())
fun loadRun(kv: Kv, id: String): Run? = kv.read("run/$id")?.let { raw -> runCatching { Run.fromJson(Json.parseToJsonElement(raw).jsonObject) { o ->
    if (o["k"]?.jsonPrimitive?.content == "bytesRef") {
        val sha = o["sha"]!!.jsonPrimitive.content
        val b64 = kv.read("runblob/$sha") ?: throw ToolError("Vstupní soubor běhu už není uložený.")
        Value.Bytes(o["name"]!!.jsonPrimitive.content, java.util.Base64.getDecoder().decode(b64), o["mime"]?.jsonPrimitive?.content)
    } else Value.fromJson(o)
} }.getOrNull() }
/** Držet posledních [keep] běhů (mezivýsledky mohou být velké) – volá se při startu nového běhu, ne po každém kroku. */
/** Úklid starých běhů podle času souboru – bez parsování všech (velké běhy by se při každém startu načítaly celé). */
fun pruneRuns(kv: Kv, keep: Int = 30) {
    val keys = kv.keys("run/")
    if (keys.size <= keep) return
    val byTime = keys.sortedByDescending { kv.modified(it) }
    if (byTime.all { kv.modified(it) == 0L }) runsOf(kv).drop(keep).forEach { kv.delete("run/${it.id}") } else byTime.drop(keep).forEach { kv.delete(it) }
    pruneRunBlobs(kv)
}
/** Bloby, na které už žádný běh neodkazuje, smazat (hledá se jen text „sha“ v uložených bězích – bez parsování). */
fun pruneRunBlobs(kv: Kv) {
    val blobs = kv.keys("runblob/"); if (blobs.isEmpty()) return
    val all = kv.keys("run/").mapNotNull { kv.read(it) }.joinToString("\n")
    blobs.filter { !all.contains(it.removePrefix("runblob/")) }.forEach { kv.delete(it) }
}
