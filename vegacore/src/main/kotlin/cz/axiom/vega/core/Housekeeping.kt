package cz.axiom.vega.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ÚKLID ÚLOŽIŠTĚ (běží při startu na výkonném vlákně, nic nemaže z živých dat):
 *  - data chatů, které vypadly ze seznamu (max. 50): zprávy, podklady, rozpracované dotazy, návrhy, porovnání,
 *  - dočasný chat a nedokončené „Pokračovat“ po [resumeDays] dnech, mezivýsledky dlouhých zadání po [cacheDays],
 *  - úlohy s revizemi bez odkazu z chatu po [taskDays] dnech,
 *  - přílohy úloh (taskfile/<otisk>), na které už žádná úloha neodkazuje (uložené jednou podle otisku),
 *  - zálohy poškozených souborů (.bad) po [badDays] dnech.
 * Soubor bez známého času změny (0) se podle stáří nemaže.
 */
object Housekeeping {
    data class Report(val deleted: Int, val bytes: Long, val byPrefix: Map<String, Int>) {
        override fun toString() = if (deleted == 0) "úklid: nic" else "úklid: $deleted souborů (${bytes / 1024} kB) – " + byPrefix.entries.joinToString(", ") { "${it.key} ${it.value}" }
    }

    private const val DAY = 86_400_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** [keepChat] = chaty mimo seznam, které žijí jinde (konverzace projektu „proj-<id>“, dokud projekt existuje). */
    fun run(kv: Kv, now: Long = System.currentTimeMillis(), resumeDays: Int = 7, cacheDays: Int = 3, taskDays: Int = 30, tempDays: Int = 1, badDays: Int = 30,
            keepChat: (String) -> Boolean = { it.startsWith("proj-") }): Report {
        val del = LinkedHashMap<String, String>() // klíč → důvod (prefix)
        fun old(k: String, days: Int) = kv.modified(k).let { it > 0 && now - it > days * DAY }

        val live = kv.read("chats.json")?.let { raw -> runCatching { (json.parseToJsonElement(raw) as JsonArray)
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }.toSet() }.getOrNull() }
        // 1) data chatů mimo seznam (jen když seznam existuje a jde přečíst – chybějící/rozbitý seznam ≠ smazat všechno)
        if (live != null) {
            fun chatOf(k: String, prefix: String) = k.removePrefix(prefix).substringBefore('/').removeSuffix(".json")
            for (prefix in listOf("chat/", "evidence/", "draft/button/", "resume/", "state/compare/"))
                kv.keys(prefix).filter { !it.endsWith(".bad") }.forEach { k -> val c = chatOf(k, prefix)
                    if (c == "temp") { if (old(k, tempDays)) del[k] = prefix } else if (c !in live && !keepChat(c)) del[k] = prefix }
        }
        // 2) podle stáří
        kv.keys("resume/").filter { old(it, resumeDays) }.forEach { del.putIfAbsent(it, "resume/") }
        kv.keys("cache/long/").filter { old(it, cacheDays) }.forEach { del[it] = "cache/long/" }
        // 2b) zálohy poškozených souborů (chat/…json.bad, chats.json.bad, memory/entries.bad-…): k diagnóze, ne navždy
        (kv.keys("chat/") + kv.keys("memory/") + kv.keys("evidence/") + listOf("chats.json.bad").filter { kv.read(it) != null })
            .filter { (it.endsWith(".bad") || it.contains(".bad-")) && old(it, badDays) }.forEach { del[it] = "zálohy .bad" }
        // 3) úlohy s revizemi: bez odkazu z (zbývajícího) porovnání a staré
        val referenced = kv.keys("state/compare/").filter { it !in del }.mapNotNull { kv.read(it)?.trim() }.toSet()
        kv.keys("task/").filter { it.endsWith(".json") }.forEach { k -> val id = k.removePrefix("task/").removeSuffix(".json")
            if (id !in referenced && old(k, taskDays)) del[k] = "task/" }
        // 4) přílohy úloh bez odkazu (mladší než hodina nechat: úloha se právě zakládá)
        val shas = kv.keys("task/").filter { it.endsWith(".json") && it !in del }.flatMapTo(HashSet()) { k ->
            runCatching { RevTask(kv, k.removePrefix("task/").removeSuffix(".json")).state().revs.flatMap { r -> r.inputs.map { it.sha } } }.getOrDefault(emptyList()) }
        kv.keys("taskfile/").forEach { k -> if (k.removePrefix("taskfile/") !in shas && kv.modified(k).let { it > 0 && now - it > 3_600_000L }) del[k] = "taskfile/" }

        var bytes = 0L
        del.keys.forEach { k -> bytes += kv.read(k)?.length ?: 0; kv.delete(k) }
        pruneRuns(kv)
        return Report(del.size, bytes, del.values.groupingBy { it }.eachCount())
    }
}
