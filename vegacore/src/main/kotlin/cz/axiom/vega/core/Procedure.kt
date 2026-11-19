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
}

class MemKv : Kv {
    val map = LinkedHashMap<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
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
)

class ProcedureStore(private val kv: Kv) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<Procedure> = kv.keys("proc/").mapNotNull { k -> runCatching { json.decodeFromString(Procedure.serializer(), kv.read(k)!!) }.getOrNull() }.sortedByDescending { it.updated }
    fun get(id: String) = kv.read("proc/$id")?.let { runCatching { json.decodeFromString(Procedure.serializer(), it) }.getOrNull() }

    /** Uloží postup; existuje-li se stejným názvem, zvýší verzi a zapíše změnu (předchozí verze zůstane v historii). */
    fun save(name: String, steps: List<Step>, description: String, inputKind: Kind, inputDesc: String, outputKind: Kind, change: String = "první verze"): Procedure {
        require(steps.isNotEmpty()) { "Postup bez kroků" }
        steps.forEach { require(Tools.get(it.tool) != null) { "Neznámý nástroj ${it.tool}" } }
        val perms = steps.flatMap { Tools.get(it.tool)!!.perms }.distinct().map { it.label }
        val old = list().firstOrNull { it.name.equals(name, true) }
        val p = if (old != null) {
            kv.write("proc-hist/${old.id}/${old.version}", json.encodeToString(Procedure.serializer(), old))
            old.copy(version = old.version + 1, steps = steps, description = description, inputKind = inputKind.name, inputDesc = inputDesc,
                outputKind = outputKind.name, perms = perms, updated = System.currentTimeMillis(), changelog = old.changelog + "v${old.version + 1}: $change")
        } else Procedure(slug(name), name, 1, description, inputKind.name, inputDesc, outputKind.name, perms, steps, changelog = listOf("v1: $change"))
        kv.write("proc/${p.id}", json.encodeToString(Procedure.serializer(), p))
        return p
    }

    fun used(p: Procedure) = kv.write("proc/${p.id}", json.encodeToString(Procedure.serializer(), p.copy(uses = p.uses + 1)))

    fun delete(id: String) { kv.delete("proc/$id"); kv.keys("proc-hist/$id/").forEach { kv.delete(it) } }

    /** Postup podle slov v chatu („zpracuj výpis“ → „Zpracovat výpis“). */
    fun find(text: String): Procedure? {
        val f = cz.axiom.omega.core.util.TextUtil.fold(text).lowercase()
        return list().firstOrNull { p ->
            val words = cz.axiom.omega.core.util.TextUtil.fold(p.name).lowercase().split(' ').filter { it.length >= 3 }
            words.isNotEmpty() && words.all { w -> f.contains(w.take(minOf(w.length, maxOf(4, w.length - 4)))) }
        }
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

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("started", started)
        put("steps", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Step.serializer()), steps))
        put("input", Value.toJson(input))
        put("results", buildJsonArray { results.forEach { r -> add(buildJsonObject {
            put("i", r.index); put("tool", r.tool); put("ms", r.millis); r.error?.let { put("error", it) }; r.value?.let { put("value", Value.toJson(it)) }
        }) } })
    }

    companion object {
        fun fromJson(o: JsonObject) = Run(
            o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
            Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Step.serializer()), o["steps"]!!),
            Value.fromJson(o["input"]!!.jsonObject),
            o["results"]!!.jsonArray.map { e -> val r = e.jsonObject
                StepResult(r["i"]!!.jsonPrimitive.content.toInt(), r["tool"]!!.jsonPrimitive.content, r["value"]?.jsonObject?.let { Value.fromJson(it) }, r["error"]?.jsonPrimitive?.content, r["ms"]!!.jsonPrimitive.content.toLong())
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
            } catch (e: cz.axiom.omega.core.llm.CancelledException) {
                StepResult(i, s.tool, null, "Zrušeno – pokračuj od kroku ${i + 1}", (System.nanoTime() - t0) / 1_000_000)
            } catch (e: Exception) {
                StepResult(i, s.tool, null, e.message ?: e.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000)
            }
            cur = cur.copy(results = cur.results + res)
            save(cur)
            if (res.error != null) break
        }
        return cur
    }

    fun start(name: String, steps: List<Step>, input: Value, ctx: ToolCtx, save: (Run) -> Unit = {}): Run =
        run(Run("run-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random(), name, steps, input, emptyList()), ctx, save)

    /** Pokračování po chybě/pádu: hotové kroky se neopakují. */
    fun resume(run: Run, ctx: ToolCtx, save: (Run) -> Unit = {}) = run(run, ctx, save)
}

fun runsOf(kv: Kv): List<Run> = kv.keys("run/").mapNotNull { k -> runCatching { Run.fromJson(Json.parseToJsonElement(kv.read(k)!!).jsonObject) }.getOrNull() }.sortedByDescending { it.started }
fun saveRun(kv: Kv, r: Run) = kv.write("run/${r.id}", r.toJson().toString())
/** Držet posledních [keep] běhů (mezivýsledky mohou být velké) – volá se při startu nového běhu, ne po každém kroku. */
fun pruneRuns(kv: Kv, keep: Int = 30) { runsOf(kv).drop(keep).forEach { kv.delete("run/${it.id}") } }
